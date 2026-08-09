package kz.oyla.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kz.oyla.server.model.ChildStatus
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.SpecialistStatus
import kz.oyla.server.model.dto.ActivateDeviceResponse
import kz.oyla.server.model.dto.AuthResponse
import kz.oyla.server.model.dto.ChildDto
import kz.oyla.server.model.dto.CreateActivationCodeResponse
import kz.oyla.server.model.dto.SaasErrorResponse
import kz.oyla.server.model.dto.SpecialistDto
import kz.oyla.server.repository.InMemorySaasRepository
import kz.oyla.server.repository.InMemorySessionRepository
import kz.oyla.server.service.SaasConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRoutesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `children are tenant-scoped and archived children are excluded by default`() = withServer { repository ->
        val a = register("A", "children-a@example.com")
        val b = register("B", "children-b@example.com")
        val child = createChild(a.accessToken, "Алихан")

        assertEquals(1, children(a.accessToken).size)
        assertTrue(children(b.accessToken).isEmpty())
        val foreign = client.get("/api/v1/children/${child.id}") { bearer(b.accessToken) }
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals("CHILD_NOT_FOUND", errorCode(foreign))

        val archived = client.post("/api/v1/children/${child.id}/archive") { bearer(a.accessToken) }
        assertEquals(HttpStatusCode.OK, archived.status)
        assertEquals(ChildStatus.ARCHIVED, json.decodeFromString<ChildDto>(archived.bodyAsText()).status)
        assertTrue(children(a.accessToken).isEmpty())
        assertEquals(1, children(a.accessToken, "ARCHIVED").size)

        val restored = client.post("/api/v1/children/${child.id}/restore") { bearer(a.accessToken) }
        assertEquals(HttpStatusCode.OK, restored.status)
        assertEquals(ChildStatus.ACTIVE, json.decodeFromString<ChildDto>(restored.bodyAsText()).status)
        assertEquals(1, children(a.accessToken).size)
        assertNull(repository.findUserById(java.util.UUID.fromString(child.id)))
    }

    @Test fun `specialists are tenant-scoped and are not users`() = withServer { repository ->
        val a = register("A", "specialists-a@example.com")
        val b = register("B", "specialists-b@example.com")
        val specialist = createSpecialist(a.accessToken, "Анна", "Логопед")

        assertEquals(1, specialists(a.accessToken).size)
        assertTrue(specialists(b.accessToken).isEmpty())
        val foreign = client.get("/api/v1/specialists/${specialist.id}") { bearer(b.accessToken) }
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertEquals("SPECIALIST_NOT_FOUND", errorCode(foreign))
        assertNull(repository.findUserById(java.util.UUID.fromString(specialist.id)))

        assertEquals(HttpStatusCode.OK, client.post("/api/v1/specialists/${specialist.id}/archive") { bearer(a.accessToken) }.status)
        assertTrue(specialists(a.accessToken).isEmpty())
        assertEquals(SpecialistStatus.ARCHIVED, specialists(a.accessToken, "ARCHIVED").single().status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/specialists/${specialist.id}/restore") { bearer(a.accessToken) }.status)
        assertEquals(SpecialistStatus.ACTIVE, specialists(a.accessToken).single().status)
    }

    @Test fun `specialist tablet receives only active catalog entries from its center`() = withServer { _ ->
        val a = register("A", "devices-a@example.com")
        val b = register("B", "devices-b@example.com")
        val active = createChild(a.accessToken, "Активный")
        val archived = createChild(a.accessToken, "Архивный")
        client.post("/api/v1/children/${archived.id}/archive") { bearer(a.accessToken) }
        createChild(b.accessToken, "Чужой")
        createSpecialist(a.accessToken, "Анна", "Логопед")
        createSpecialist(b.accessToken, "Бек", "Психолог")
        val specialistTablet = activate(createCode(a.accessToken, "Планшет специалиста", DeviceRole.SPECIALIST).activationCode, "catalog-specialist")
        val childTablet = activate(createCode(a.accessToken, "Детский планшет", DeviceRole.CHILD).activationCode, "catalog-child")

        val deviceChildren = json.decodeFromString<List<ChildDto>>(client.get("/api/v1/device-data/children") { bearer(specialistTablet.deviceToken) }.bodyAsText())
        assertEquals(listOf(active.id), deviceChildren.map { it.id })
        val deviceSpecialists = json.decodeFromString<List<SpecialistDto>>(client.get("/api/v1/device-data/specialists") { bearer(specialistTablet.deviceToken) }.bodyAsText())
        assertEquals(listOf("Анна"), deviceSpecialists.map { it.firstName })
        val childDenied = client.get("/api/v1/device-data/children") { bearer(childTablet.deviceToken) }
        assertEquals(HttpStatusCode.Forbidden, childDenied.status)
    }

    private fun withServer(block: suspend ApplicationTestBuilder.(InMemorySaasRepository) -> Unit) = testApplication {
        val repository = InMemorySaasRepository()
        application {
            module(
                sessionRepository = InMemorySessionRepository(),
                clock = MutableClock(Instant.now()),
                saasRepository = repository,
                saasConfig = SaasConfig("test-jwt-secret-that-is-long-enough", "test", "test", Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofMinutes(10), Duration.ofSeconds(90), 10, allowPublicRegistration = true)
            )
        }
        block(repository)
    }

    private suspend fun ApplicationTestBuilder.register(name: String, email: String): AuthResponse {
        val response = client.post("/api/v1/auth/register-center") { jsonBody("""{"centerName":"$name","firstName":"Алия","email":"$email","password":"Password123"}""") }
        assertEquals(HttpStatusCode.Created, response.status)
        return json.decodeFromString(response.bodyAsText())
    }
    private suspend fun ApplicationTestBuilder.createChild(token: String, firstName: String): ChildDto {
        val response = client.post("/api/v1/children") { bearer(token); jsonBody("""{"firstName":"$firstName"}""") }
        assertEquals(HttpStatusCode.Created, response.status)
        return json.decodeFromString(response.bodyAsText())
    }
    private suspend fun ApplicationTestBuilder.createSpecialist(token: String, firstName: String, specialization: String): SpecialistDto {
        val response = client.post("/api/v1/specialists") { bearer(token); jsonBody("""{"firstName":"$firstName","specialization":"$specialization"}""") }
        assertEquals(HttpStatusCode.Created, response.status)
        return json.decodeFromString(response.bodyAsText())
    }
    private suspend fun ApplicationTestBuilder.children(token: String, status: String? = null): List<ChildDto> =
        json.decodeFromString(client.get("/api/v1/children${status?.let { "?status=$it" } ?: ""}") { bearer(token) }.bodyAsText())
    private suspend fun ApplicationTestBuilder.specialists(token: String, status: String? = null): List<SpecialistDto> =
        json.decodeFromString(client.get("/api/v1/specialists${status?.let { "?status=$it" } ?: ""}") { bearer(token) }.bodyAsText())
    private suspend fun ApplicationTestBuilder.createCode(token: String, name: String, role: DeviceRole): CreateActivationCodeResponse {
        val response = client.post("/api/v1/devices/activation-codes") { bearer(token); jsonBody("""{"deviceName":"$name","deviceRole":"$role"}""") }
        assertEquals(HttpStatusCode.Created, response.status)
        return json.decodeFromString(response.bodyAsText())
    }
    private suspend fun ApplicationTestBuilder.activate(code: String, uid: String): ActivateDeviceResponse {
        val response = client.post("/api/v1/device-auth/activate") { jsonBody("""{"activationCode":"$code","deviceUid":"$uid"}""") }
        assertEquals(HttpStatusCode.Created, response.status)
        return json.decodeFromString(response.bodyAsText())
    }
    private suspend fun errorCode(response: HttpResponse) = json.decodeFromString<SaasErrorResponse>(response.bodyAsText()).error.code
    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) { header(HttpHeaders.Authorization, "Bearer $token") }
    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(body: String) { contentType(ContentType.Application.Json); setBody(body) }

    private class MutableClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun withZone(zone: ZoneId): Clock = this
        override fun getZone(): ZoneId = ZoneId.of("UTC")
    }
}
