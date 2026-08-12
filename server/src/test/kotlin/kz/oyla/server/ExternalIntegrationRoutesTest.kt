package kz.oyla.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kz.oyla.server.integration.CredentialCipher
import kz.oyla.server.integration.ExternalCrmClient
import kz.oyla.server.integration.ExternalCrmResult
import kz.oyla.server.integration.IntegrationConfig
import kz.oyla.server.model.ChildStatus
import kz.oyla.server.model.ExternalCrmChild
import kz.oyla.server.model.MembershipRole
import kz.oyla.server.model.MembershipStatus
import kz.oyla.server.model.CenterMembershipRecord
import kz.oyla.server.model.dto.AuthResponse
import kz.oyla.server.model.dto.ChildDto
import kz.oyla.server.model.dto.CrmConnectionTestStatus
import kz.oyla.server.model.dto.CrmIntegrationDto
import kz.oyla.server.model.dto.ExternalCrmHealthResponse
import kz.oyla.server.model.dto.ImportCrmChildrenResponse
import kz.oyla.server.model.dto.CrmSyncResponse
import kz.oyla.server.model.dto.SelectCenterResponse
import kz.oyla.server.repository.InMemoryExternalIntegrationRepository
import kz.oyla.server.repository.InMemorySaasRepository
import kz.oyla.server.repository.InMemorySessionRepository
import kz.oyla.server.service.SaasConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalIntegrationRoutesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `credentials stay encrypted and are never returned`() = withServer { repository, integrations, crm ->
        val owner = register("A", "owner-a@example.com")
        val response = client.post("/api/v1/integrations/crm") { bearer(owner.accessToken); jsonBody("""{"name":"CRM","baseUrl":"https://8.8.8.8","apiKey":"api-key-a"}""") }
        assertEquals(HttpStatusCode.Created, response.status)
        assertFalse(response.bodyAsText().contains("api-key-a"))
        val stored = checkNotNull(integrations.findCrmIntegration(UUID.fromString(owner.activeCenter!!.id)))
        assertFalse(stored.encryptedCredential!!.contains("api-key-a"))
        assertEquals("api-key-a", CredentialCipher.fromSecret(TestKey).decrypt(kz.oyla.server.integration.EncryptedCredential(stored.encryptedCredential, checkNotNull(stored.credentialNonce), checkNotNull(stored.credentialKeyVersion))))
        val get = client.get("/api/v1/integrations/crm") { bearer(owner.accessToken) }
        assertEquals(HttpStatusCode.OK, get.status); assertFalse(get.bodyAsText().contains("api-key-a")); assertTrue(get.bodyAsText().contains("hasCredential"))
        assertTrue(integrations.audits.any { it.action == "CRM_INTEGRATION_CREATED" })
    }

    @Test fun `tenant integration import is isolated idempotent and CRM fields sync`() = withServer { _, _, crm ->
        val a = register("A", "a@example.com"); val b = register("B", "b@example.com")
        connect(a.accessToken)
        assertEquals(HttpStatusCode.NoContent, client.get("/api/v1/integrations/crm") { bearer(b.accessToken) }.status)
        assertEquals(HttpStatusCode.Conflict, client.get("/api/v1/integrations/crm/children") { bearer(b.accessToken) }.status)
        val imported = client.post("/api/v1/integrations/crm/import-children") { bearer(a.accessToken); jsonBody("""{"externalIds":["external-1"]}""") }
        assertEquals(HttpStatusCode.OK, imported.status); assertEquals(1, json.decodeFromString<ImportCrmChildrenResponse>(imported.bodyAsText()).imported)
        val child = json.decodeFromString<List<ChildDto>>(client.get("/api/v1/children") { bearer(a.accessToken) }.bodyAsText()).single()
        assertEquals("Алихан", child.firstName); assertEquals("CRM", child.source?.provider); assertTrue(child.source!!.crmManaged)
        assertEquals("[]", client.get("/api/v1/children") { bearer(b.accessToken) }.bodyAsText())
        val again = json.decodeFromString<ImportCrmChildrenResponse>(client.post("/api/v1/integrations/crm/import-children") { bearer(a.accessToken); jsonBody("""{"externalIds":["external-1"]}""") }.bodyAsText())
        assertEquals(0, again.imported); assertEquals(1, again.skipped)
        assertEquals(HttpStatusCode.Conflict, client.patch("/api/v1/children/${child.id}") { bearer(a.accessToken); jsonBody("""{"firstName":"Ручное"}""") }.status)

        crm.children = listOf(crm.child(firstName = "Алихан-новое"))
        val synced = json.decodeFromString<CrmSyncResponse>(client.post("/api/v1/integrations/crm/sync") { bearer(a.accessToken) }.bodyAsText())
        assertEquals(1, synced.updated)
        assertEquals("Алихан-новое", json.decodeFromString<ChildDto>(client.get("/api/v1/children/${child.id}") { bearer(a.accessToken) }.bodyAsText()).firstName)

        crm.children = listOf(crm.child(status = ChildStatus.ARCHIVED))
        assertEquals(1, json.decodeFromString<CrmSyncResponse>(client.post("/api/v1/integrations/crm/sync") { bearer(a.accessToken) }.bodyAsText()).archived)
        assertEquals("ARCHIVED", json.decodeFromString<ChildDto>(client.get("/api/v1/children/${child.id}") { bearer(a.accessToken) }.bodyAsText()).status.name)
        crm.children = listOf(crm.child(status = ChildStatus.ACTIVE))
        assertEquals(1, json.decodeFromString<CrmSyncResponse>(client.post("/api/v1/integrations/crm/sync") { bearer(a.accessToken) }.bodyAsText()).restored)
    }

    @Test fun `connection failures do not replace existing credential and disconnect retains child`() = withServer { _, _, crm ->
        val owner = register("A", "owner@example.com"); connect(owner.accessToken)
        val initial = client.get("/api/v1/integrations/crm") { bearer(owner.accessToken) }.bodyAsText()
        val failure = client.patch("/api/v1/integrations/crm") { bearer(owner.accessToken); jsonBody("""{"apiKey":"wrong-key"}""") }
        assertEquals(HttpStatusCode.BadGateway, failure.status)
        assertEquals(initial, client.get("/api/v1/integrations/crm") { bearer(owner.accessToken) }.bodyAsText())
        client.post("/api/v1/integrations/crm/import-children") { bearer(owner.accessToken); jsonBody("""{"externalIds":["external-1"]}""") }
        val child = json.decodeFromString<List<ChildDto>>(client.get("/api/v1/children") { bearer(owner.accessToken) }.bodyAsText()).single()
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/integrations/crm") { bearer(owner.accessToken) }.status)
        val after = json.decodeFromString<ChildDto>(client.get("/api/v1/children/${child.id}") { bearer(owner.accessToken) }.bodyAsText())
        assertEquals("CRM", after.source?.provider); assertFalse(after.source!!.crmManaged)
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/children/${child.id}") { bearer(owner.accessToken); jsonBody("""{"firstName":"Локально"}""") }.status)
    }

    @Test fun `methodist cannot access CRM settings and malformed provider data leaves no children`() = withServer { repository, _, crm ->
        val owner = register("A", "owner@example.com"); val methodist = register("Other", "methodist@example.com")
        val ownerId = UUID.fromString(owner.activeCenter!!.id); val methodistUser = checkNotNull(repository.findUserByEmail("methodist@example.com")); val now = Instant.now()
        repository.seedMembership(CenterMembershipRecord(UUID.randomUUID(), ownerId, methodistUser.id, MembershipRole.METHODIST, MembershipStatus.ACTIVE, now, now))
        val methodistToken = selectCenter(methodist.accessToken, ownerId)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/integrations/crm") { bearer(methodistToken) }.status)
        connect(owner.accessToken)
        crm.invalidChildren = true
        val response = client.post("/api/v1/integrations/crm/import-children") { bearer(owner.accessToken); jsonBody("""{"externalIds":["external-1"]}""") }
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("[]", client.get("/api/v1/children") { bearer(owner.accessToken) }.bodyAsText())
    }

    private fun withServer(block: suspend ApplicationTestBuilder.(InMemorySaasRepository, InMemoryExternalIntegrationRepository, FakeCrmClient) -> Unit) = testApplication {
        val repository = InMemorySaasRepository(); val integrations = InMemoryExternalIntegrationRepository(repository); val crm = FakeCrmClient()
        application { module(sessionRepository = InMemorySessionRepository(), saasRepository = repository, externalIntegrationRepository = integrations, externalCrmClient = crm, integrationConfig = IntegrationConfig(false, TestKey, false), saasConfig = SaasConfig("test-jwt-secret-that-is-long-enough", "test", "test", Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofMinutes(10), Duration.ofSeconds(90), 10, allowPublicRegistration = true)) }
        block(repository, integrations, crm)
    }
    private suspend fun ApplicationTestBuilder.register(name: String, email: String): AuthResponse = json.decodeFromString(client.post("/api/v1/auth/register-center") { jsonBody("""{"centerName":"$name","firstName":"Алия","email":"$email","password":"Password123"}""") }.bodyAsText())
    private suspend fun ApplicationTestBuilder.connect(token: String) { assertEquals(HttpStatusCode.Created, client.post("/api/v1/integrations/crm") { bearer(token); jsonBody("""{"baseUrl":"https://8.8.8.8","apiKey":"api-key-a"}""") }.status) }
    private suspend fun ApplicationTestBuilder.selectCenter(token: String, centerId: UUID): String = json.decodeFromString<SelectCenterResponse>(client.post("/api/v1/centers/$centerId/select") { bearer(token) }.bodyAsText()).accessToken
    private fun HttpRequestBuilder.bearer(token: String) { header(HttpHeaders.Authorization, "Bearer $token") }
    private fun HttpRequestBuilder.jsonBody(value: String) { contentType(ContentType.Application.Json); setBody(value) }

    private class FakeCrmClient : ExternalCrmClient {
        var children: List<ExternalCrmChild> = listOf(child())
        var invalidChildren = false
        override suspend fun checkConnection(baseUrl: String, apiKey: String) = if (baseUrl == "https://8.8.8.8" && apiKey == "api-key-a") ExternalCrmResult.Success(ExternalCrmHealthResponse(), 200, 1) else ExternalCrmResult.Failure(CrmConnectionTestStatus.AUTH_FAILED)
        override suspend fun listChildren(baseUrl: String, apiKey: String): ExternalCrmResult<List<ExternalCrmChild>> = when {
            apiKey != "api-key-a" -> ExternalCrmResult.Failure(CrmConnectionTestStatus.AUTH_FAILED)
            invalidChildren -> ExternalCrmResult.Failure(CrmConnectionTestStatus.INVALID_RESPONSE)
            else -> ExternalCrmResult.Success(children, 200, 1)
        }
        fun child(firstName: String = "Алихан", status: ChildStatus = ChildStatus.ACTIVE) = ExternalCrmChild("external-1", firstName, "Сарсенов", java.time.LocalDate.parse("2019-04-16"), status, Instant.parse("2026-08-12T07:30:00Z"))
    }
    private companion object { const val TestKey = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=" }
}
