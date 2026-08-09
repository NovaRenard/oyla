package kz.oyla.server

import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.DeviceStatus
import kz.oyla.server.model.WhiteboardBrushSize
import kz.oyla.server.model.WhiteboardColor
import kz.oyla.server.model.WhiteboardTool
import kz.oyla.server.model.dto.ActivateDeviceRequest
import kz.oyla.server.model.dto.CreateActivationCodeRequest
import kz.oyla.server.model.dto.RegisterCenterRequest
import kz.oyla.server.model.dto.UpdateDeviceRequest
import kz.oyla.server.model.dto.WhiteboardPointDto
import kz.oyla.server.repository.DatabaseSaasRepository
import kz.oyla.server.repository.DatabaseWhiteboardRepository
import kz.oyla.server.repository.WhiteboardStrokeInput
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.SaasConfig
import kz.oyla.server.service.SaasService
import kz.oyla.server.util.SecretGenerator
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer

/** Real PostgreSQL/Flyway coverage for the production Saas repository. Docker is supplied by Testcontainers. */
class PostgresSaasIntegrationTest {
    private lateinit var flyway: Flyway
    private lateinit var repository: DatabaseSaasRepository
    private lateinit var clock: MutableClock
    private lateinit var service: SaasService

    @Before fun migrateEmptyDatabase() {
        flyway = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration").cleanDisabled(false).load()
        flyway.clean()
        flyway.migrate()
        Database.connect(postgres.jdbcUrl, driver = "org.postgresql.Driver", user = postgres.username, password = postgres.password)
        repository = DatabaseSaasRepository()
        clock = MutableClock(Instant.parse("2026-08-06T08:00:00Z"))
        service = SaasService(repository, testConfig, SecretGenerator(pepper = TestPepper), clock)
    }

    @Test fun `Flyway migrates an empty PostgreSQL database and owner constraint is enforced`() = runBlocking {
        assertEquals("SUCCESS", flyway.info().current()?.state?.name)
        val owner = register("Owner constraint", "owner-constraint@example.com")
        val centerId = UUID.fromString(owner.activeCenter!!.id)
        postgres.createConnection("").use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("DELETE FROM center_memberships WHERE center_id = ?").use { it.setObject(1, centerId); it.executeUpdate() }
            try {
                connection.commit()
                throw AssertionError("PostgreSQL allowed a center without an OWNER")
            } catch (_: SQLException) {
                connection.rollback()
            }
        }
    }

    @Test fun `registration persists center owner and only hashes secrets`() = runBlocking {
        val registered = register("Hash Center", "OWNER@EXAMPLE.COM")
        val centerId = UUID.fromString(registered.activeCenter!!.id)
        assertEquals("owner@example.com", registered.user.email)
        assertEquals(1, queryInt("SELECT COUNT(*) FROM center_memberships WHERE center_id = '$centerId' AND role = 'OWNER' AND status = 'ACTIVE'"))
        assertEquals(1, queryInt("SELECT COUNT(*) FROM audit_logs WHERE center_id = '$centerId' AND action = 'CENTER_CREATED'"))
        assertNotEquals(registered.refreshToken, queryText("SELECT token_hash FROM refresh_tokens LIMIT 1"))
        assertEquals(64, queryText("SELECT token_hash FROM refresh_tokens LIMIT 1")!!.length)
        try { register("Duplicate", "owner@example.com"); throw AssertionError("duplicate email was accepted") }
        catch (error: ApiException) { assertEquals("CONFLICT", error.errorCode) }
    }

    @Test fun `SQL repository isolates tenants and hashes activation and device tokens`() = runBlocking {
        val a = register("A", "sql-a@example.com")
        val b = register("B", "sql-b@example.com")
        val code = codeFor(a, "A tablet")
        val activated = activate(code.activationCode, "sql-isolated-uid")
        assertTrue(service.listDevices(UUID.fromString(b.user.id), UUID.fromString(b.activeCenter!!.id), null, null, null).isEmpty())
        assertNotEquals(code.activationCode, queryText("SELECT code_hash FROM device_activation_codes LIMIT 1"))
        assertNotEquals(activated.deviceToken, queryText("SELECT token_hash FROM devices WHERE id = '${activated.deviceId}'"))
        assertEquals(1, queryInt("SELECT COUNT(*) FROM audit_logs WHERE action = 'DEVICE_ACTIVATED'"))
    }

    @Test fun `one code concurrent activation creates one device and remains one time`() = runBlocking {
        val owner = register("Concurrent", "concurrent@example.com")
        val code = codeFor(owner, "Tablet")
        val outcomes = coroutineScope { listOf(
            async { runCatching { activate(code.activationCode, "concurrent-a") } },
            async { runCatching { activate(code.activationCode, "concurrent-b") } }
        ).awaitAll() }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.exceptionOrNull() is ApiException })
        assertEquals(1, queryInt("SELECT COUNT(*) FROM devices"))
        assertEquals("USED", queryText("SELECT status FROM device_activation_codes LIMIT 1"))
    }

    @Test fun `active and blocked devices cannot move but unlinked device can reactivate with a new token`() = runBlocking {
        val a = register("A", "move-a@example.com")
        val b = register("B", "move-b@example.com")
        val original = activate(codeFor(a, "A tablet").activationCode, "move-sql-uid")
        val codeB = codeFor(b, "B tablet")
        assertActivationError("DEVICE_ALREADY_ACTIVATED") { activate(codeB.activationCode, "move-sql-uid") }
        assertEquals("PENDING", queryText("SELECT status FROM device_activation_codes WHERE id = '${codeB.id}'"))
        assertEquals(a.centerId(), queryText("SELECT center_id::text FROM devices WHERE id = '${original.deviceId}'"))

        service.updateDevice(UUID.fromString(a.user.id), UUID.fromString(a.centerId()), UUID.fromString(original.deviceId), UpdateDeviceRequest(status = DeviceStatus.BLOCKED), null)
        val blockedCodeB = codeFor(b, "B tablet 2")
        assertActivationError("DEVICE_BLOCKED") { activate(blockedCodeB.activationCode, "move-sql-uid") }
        assertEquals("PENDING", queryText("SELECT status FROM device_activation_codes WHERE id = '${blockedCodeB.id}'"))

        service.updateDevice(UUID.fromString(a.user.id), UUID.fromString(a.centerId()), UUID.fromString(original.deviceId), UpdateDeviceRequest(status = DeviceStatus.ACTIVE), null)
        service.unlinkDevice(UUID.fromString(a.user.id), UUID.fromString(a.centerId()), UUID.fromString(original.deviceId), null)
        val replacement = activate(codeFor(b, "B tablet 3").activationCode, "move-sql-uid")
        assertEquals(original.deviceId, replacement.deviceId)
        assertNull(service.authenticateDeviceToken(original.deviceToken))
        assertTrue(service.authenticateDeviceToken(replacement.deviceToken) != null)
        assertEquals(b.centerId(), queryText("SELECT center_id::text FROM devices WHERE id = '${original.deviceId}'"))
    }

    @Test fun `heartbeat updates last seen and online state`() = runBlocking {
        val owner = register("Heartbeat", "heartbeat-sql@example.com")
        val device = activate(codeFor(owner, "Tablet").activationCode, "heartbeat-sql-uid")
        clock.advanceSeconds(91)
        assertFalse(service.listDevices(UUID.fromString(owner.user.id), UUID.fromString(owner.centerId()), null, null, false).single().isOnline)
        val principal = checkNotNull(service.authenticateDeviceToken(device.deviceToken))
        service.heartbeat(principal, kz.oyla.server.model.dto.DeviceHeartbeatRequest(appVersion = "2.0"))
        assertTrue(service.listDevices(UUID.fromString(owner.user.id), UUID.fromString(owner.centerId()), null, null, true).single().isOnline)
        assertEquals("2.0", queryText("SELECT app_version FROM devices WHERE id = '${device.deviceId}'"))
    }

    @Test fun `WHITEBOARD state and completed strokes survive PostgreSQL revisions`() = runBlocking {
        val sessionId = UUID.randomUUID(); val exerciseId = UUID.randomUUID(); seedWhiteboardSession(sessionId, exerciseId)
        val boards = DatabaseWhiteboardRepository(); val now = Instant.parse("2026-08-09T00:00:00Z")
        boards.ensureState(exerciseId, childDrawingEnabled = true, now)
        coroutineScope {
            listOf(
                async { boards.completeStroke(stroke(exerciseId, DeviceRole.SPECIALIST, "specialist", now)) },
                async { boards.completeStroke(stroke(exerciseId, DeviceRole.CHILD, "child", now.plusSeconds(1))) }
            ).awaitAll()
        }
        val afterStrokes = boards.snapshot(exerciseId)!!
        assertEquals(listOf(1, 2), afterStrokes.strokes.map { it.sequenceNumber })
        assertEquals(2, afterStrokes.strokes.size)

        boards.undoLatest(exerciseId, DeviceRole.SPECIALIST, now.plusSeconds(2))
        boards.clear(exerciseId, now.plusSeconds(3))
        val afterClear = boards.snapshot(exerciseId)!!

        assertTrue(afterClear.strokes.isEmpty())
        assertEquals(1, afterClear.state.clearRevision)
        assertEquals(2, queryInt("SELECT COUNT(*) FROM whiteboard_strokes WHERE session_exercise_id = '$exerciseId'"))
    }

    private suspend fun register(name: String, email: String) = service.registerCenter(RegisterCenterRequest(name, "Owner", null, email, "Password123"), null)
    private fun kz.oyla.server.model.dto.AuthResponse.centerId(): String = checkNotNull(activeCenter).id
    private suspend fun codeFor(owner: kz.oyla.server.model.dto.AuthResponse, name: String) = service.createActivationCode(
        UUID.fromString(owner.user.id), UUID.fromString(owner.activeCenter!!.id), CreateActivationCodeRequest(name, DeviceRole.CHILD), null
    )
    private suspend fun activate(code: String, uid: String) = service.activateDevice(ActivateDeviceRequest(code, uid, "1.0", "14", "Test"), null)
    private suspend fun assertActivationError(expected: String, action: suspend () -> Unit) {
        try { action(); throw AssertionError("Expected $expected") }
        catch (error: ApiException) { assertEquals(expected, error.errorCode) }
    }
    private fun queryText(sql: String): String? = postgres.createConnection("").use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery(sql).use { result -> if (result.next()) result.getString(1) else null } }
    }
    private fun queryInt(sql: String): Int = queryText(sql)!!.toInt()
    private fun seedWhiteboardSession(sessionId: UUID, exerciseId: UUID) {
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("""INSERT INTO sessions
                (id, connection_code, child_name, status, specialist_device_id, specialist_token, child_device_id, child_token, created_at, expires_at, connected_at)
                VALUES (?, '1234', 'Child', 'READY', 'specialist-device', 'specialist-token', 'child-device', 'child-token', NOW(), NOW() + INTERVAL '1 hour', NOW())""").use { statement ->
                statement.setObject(1, sessionId); statement.executeUpdate()
            }
            connection.prepareStatement("""INSERT INTO session_exercises
                (id, session_id, exercise_id, status, created_at, position, is_current, activity_type)
                VALUES (?, ?, 'whiteboard-test', 'RUNNING', NOW(), 1, TRUE, 'WHITEBOARD')""").use { statement ->
                statement.setObject(1, exerciseId); statement.setObject(2, sessionId); statement.executeUpdate()
            }
        }
    }
    private fun stroke(exerciseId: UUID, role: DeviceRole, deviceId: String, now: Instant) = WhiteboardStrokeInput(
        id = UUID.randomUUID(), sessionExerciseId = exerciseId, actorRole = role, actorDeviceId = deviceId,
        tool = WhiteboardTool.PEN, color = WhiteboardColor.BLUE, brushSize = WhiteboardBrushSize.MEDIUM,
        points = listOf(WhiteboardPointDto(.1f, .1f), WhiteboardPointDto(.2f, .2f)), clientEventId = UUID.randomUUID(), createdAt = now
    )

    private class MutableClock(private var instant: Instant) : Clock() {
        override fun instant(): Instant = instant
        override fun withZone(zone: ZoneId): Clock = this
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        fun advanceSeconds(seconds: Long) { instant = instant.plusSeconds(seconds) }
    }

    companion object {
        private const val TestPepper = "test-pepper-with-at-least-thirty-two-characters"
        private val testConfig = SaasConfig("test-jwt-secret-that-is-long-enough", "test", "test", Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofMinutes(10), Duration.ofSeconds(90), 10, allowPublicRegistration = true)
        @JvmField @ClassRule val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @JvmStatic @AfterClass fun stopContainer() { postgres.stop() }
    }
}
