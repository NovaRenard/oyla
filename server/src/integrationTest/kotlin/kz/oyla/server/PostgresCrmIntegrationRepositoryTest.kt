package kz.oyla.server

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kz.oyla.server.integration.CredentialCipher
import kz.oyla.server.integration.EncryptedCredential
import kz.oyla.server.model.AuditActorType
import kz.oyla.server.model.AuditLogRecord
import kz.oyla.server.model.ChildStatus
import kz.oyla.server.model.ExternalCrmChild
import kz.oyla.server.model.ExternalIntegrationRecord
import kz.oyla.server.model.ExternalIntegrationStatus
import kz.oyla.server.model.ExternalIntegrationType
import kz.oyla.server.model.dto.RegisterCenterRequest
import kz.oyla.server.repository.DatabaseExternalIntegrationRepository
import kz.oyla.server.repository.DatabaseSaasRepository
import kz.oyla.server.service.SaasConfig
import kz.oyla.server.service.SaasService
import kz.oyla.server.util.SecretGenerator
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.AfterClass
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.testcontainers.containers.PostgreSQLContainer

class PostgresCrmIntegrationRepositoryTest {
    private lateinit var flyway: Flyway
    private lateinit var links: DatabaseExternalIntegrationRepository
    private lateinit var saas: SaasService
    private lateinit var clock: FixedClock

    @Before fun setUp() {
        flyway = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).locations("classpath:db/migration").cleanDisabled(false).load()
        flyway.clean(); flyway.migrate(); Database.connect(postgres.jdbcUrl, driver = "org.postgresql.Driver", user = postgres.username, password = postgres.password)
        clock = FixedClock(Instant.parse("2026-08-12T07:30:00Z")); links = DatabaseExternalIntegrationRepository()
        saas = SaasService(DatabaseSaasRepository(), Config, SecretGenerator(pepper = "test-pepper-with-at-least-thirty-two-characters"), clock, links)
    }

    @Test fun `Flyway V10 persists encrypted tenant links and sync never deletes local learning child`() = runBlocking {
        val a = saas.registerCenter(RegisterCenterRequest("A", "Owner", null, "a@example.com", "Password123"), null)
        val b = saas.registerCenter(RegisterCenterRequest("B", "Owner", null, "b@example.com", "Password123"), null)
        val centerA = UUID.fromString(a.activeCenter!!.id); val centerB = UUID.fromString(b.activeCenter!!.id); val now = clock.instant()
        val encrypted = CredentialCipher.fromSecret(TestKey).encrypt("crm-api-key")
        val integration = ExternalIntegrationRecord(UUID.randomUUID(), centerA, ExternalIntegrationType.CUSTOM_CRM, "CRM", "https://crm.example.kz", encrypted.ciphertext, encrypted.nonce, encrypted.keyVersion, ExternalIntegrationStatus.ACTIVE, now, null, now, now)
        links.saveIntegration(integration, audit(centerA, "CRM_INTEGRATION_CREATED", integration.id, now))
        assertNull(links.findActiveCrmIntegration(centerB)); assertFalse(query("SELECT encrypted_credential FROM external_integrations").contains("crm-api-key"))

        val external = child("Алихан", ChildStatus.ACTIVE)
        val first = links.importChildren(integration, listOf(external), now, listOf(audit(centerA, "CRM_IMPORT_COMPLETED", integration.id, now)))
        assertEquals(1, first.imported); assertEquals(1, queryInt("SELECT COUNT(*) FROM external_entity_links")); assertEquals(1, queryInt("SELECT COUNT(*) FROM children WHERE center_id = '$centerA'"))
        assertEquals(0, links.importChildren(integration, listOf(external), now.plusSeconds(1), emptyList()).imported)
        val childId = first.importedChildIds.single()

        val archived = links.syncChildren(integration, listOf(child("Алихан", ChildStatus.ARCHIVED)), now.plusSeconds(2), emptyList())
        assertEquals(1, archived.archived); assertEquals("ARCHIVED", query("SELECT status FROM children WHERE id = '$childId'"))
        val restored = links.syncChildren(integration, listOf(child("Алихан", ChildStatus.ACTIVE)), now.plusSeconds(3), emptyList())
        assertEquals(1, restored.restored); assertEquals("ACTIVE", query("SELECT status FROM children WHERE id = '$childId'"))
        links.disableIntegration(centerA, integration.id, now.plusSeconds(4), audit(centerA, "CRM_INTEGRATION_DISABLED", integration.id, now))
        assertEquals(1, queryInt("SELECT COUNT(*) FROM children WHERE id = '$childId'")); assertEquals("DISABLED", query("SELECT status FROM external_integrations WHERE id = '${integration.id}'")); assertTrue(queryBoolean("SELECT encrypted_credential IS NULL FROM external_integrations WHERE id = '${integration.id}'"))
    }

    private fun child(name: String, status: ChildStatus) = ExternalCrmChild("external-child-1", name, "Сарсенов", java.time.LocalDate.parse("2019-04-16"), status, Instant.parse("2026-08-12T07:30:00Z"))
    private fun audit(centerId: UUID, action: String, entityId: UUID, now: Instant) = AuditLogRecord(UUID.randomUUID(), centerId, AuditActorType.SYSTEM, null, action, "EXTERNAL_INTEGRATION", entityId, "{}", null, now)
    private fun query(sql: String): String = postgres.createConnection("").use { connection -> connection.createStatement().use { statement -> statement.executeQuery(sql).use { result -> result.next(); result.getString(1) } } }
    private fun queryBoolean(sql: String): Boolean = postgres.createConnection("").use { connection -> connection.createStatement().use { statement -> statement.executeQuery(sql).use { result -> result.next(); result.getBoolean(1) } } }
    private fun queryInt(sql: String): Int = query(sql).toInt()
    private class FixedClock(private var current: Instant) : Clock() { override fun instant() = current; override fun withZone(zone: ZoneId) = this; override fun getZone() = ZoneId.of("UTC") }
    private companion object {
        const val TestKey = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        val Config = SaasConfig("test-jwt-secret-that-is-long-enough", "test", "test", Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofMinutes(10), Duration.ofSeconds(90), 10, allowPublicRegistration = true)
        @JvmField @ClassRule val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @JvmStatic @AfterClass fun stop() { postgres.stop() }
    }
}
