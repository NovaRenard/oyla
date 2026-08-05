package kz.oyla.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.delete
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
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kz.oyla.server.model.CenterMembershipRecord
import kz.oyla.server.model.MembershipRole
import kz.oyla.server.model.MembershipStatus
import kz.oyla.server.model.dto.ActivateDeviceResponse
import kz.oyla.server.model.dto.AuthResponse
import kz.oyla.server.model.dto.CreateActivationCodeResponse
import kz.oyla.server.model.dto.DeviceDto
import kz.oyla.server.model.dto.SaasErrorResponse
import kz.oyla.server.model.dto.SelectCenterResponse
import kz.oyla.server.repository.InMemorySaasRepository
import kz.oyla.server.repository.InMemorySessionRepository
import kz.oyla.server.service.SaasConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaasRoutesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `public registration is disabled unless explicitly enabled`() = testApplication {
        application {
            module(
                sessionRepository = InMemorySessionRepository(),
                saasRepository = InMemorySaasRepository(),
                saasConfig = SaasConfig("test-jwt-secret-that-is-long-enough", "test", "test", Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofMinutes(10), Duration.ofSeconds(90), 10)
            )
        }
        val response = client.post("/api/v1/auth/register-center") {
            jsonBody("""{"centerName":"Центр","firstName":"Алия","email":"owner@example.com","password":"Password123"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("REGISTRATION_DISABLED", errorCode(response))
    }

    @Test fun `registration creates owner membership`() = withServer { repository, _ ->
        val owner = register("Речевой центр", "owner@example.com")
        val centers = client.get("/api/v1/centers") { bearer(owner.accessToken) }
        assertEquals(HttpStatusCode.OK, centers.status)
        assertTrue(centers.bodyAsText().contains("OWNER"))
        assertTrue(repository.findUserByEmail("owner@example.com") != null)
    }

    @Test fun `duplicate email cannot register twice`() = withServer { _, _ ->
        register("Первый", "same@example.com")
        val second = registerResponse("Второй", "same@example.com")
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals("CONFLICT", errorCode(second))
    }

    @Test fun `login with correct password succeeds`() = withServer { _, _ ->
        register("Центр", "login@example.com")
        val response = client.post("/api/v1/auth/login") { jsonBody("""{"email":"LOGIN@EXAMPLE.COM","password":"Password123"}""") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(json.decodeFromString<AuthResponse>(response.bodyAsText()).accessToken.isNotBlank())
    }

    @Test fun `login with wrong password returns safe error`() = withServer { _, _ ->
        register("Центр", "wrong@example.com")
        val response = client.post("/api/v1/auth/login") { jsonBody("""{"email":"wrong@example.com","password":"not-the-password"}""") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("INVALID_CREDENTIALS", errorCode(response))
    }

    @Test fun `center A cannot list center B devices`() = withServer { _, _ ->
        val a = register("A", "a@example.com")
        val b = register("B", "b@example.com")
        val code = createCode(b.accessToken, "B tablet")
        activate(code.activationCode, "b-device")
        val response = client.get("/api/v1/devices") { bearer(a.accessToken) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText())
    }

    @Test fun `center A cannot update center B device by UUID`() = withServer { _, _ ->
        val a = register("A", "a-update@example.com")
        val b = register("B", "b-update@example.com")
        val device = activate(createCode(b.accessToken, "B tablet").activationCode, "b-update-device")
        val response = client.patch("/api/v1/devices/${device.deviceId}") { bearer(a.accessToken); jsonBody("""{"name":"compromised"}""") }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("DEVICE_NOT_FOUND", errorCode(response))
    }

    @Test fun `admin creates activation code`() = withServer { repository, _ ->
        val owner = register("A", "owner-admin@example.com")
        val admin = register("Other", "admin@example.com")
        addMembership(repository, owner, "admin@example.com", MembershipRole.ADMIN)
        val selected = selectCenter(admin.accessToken, owner.activeCenter!!.id)
        val response = client.post("/api/v1/devices/activation-codes") { bearer(selected); jsonBody("""{"deviceName":"Admin tablet","deviceRole":"CHILD"}""") }
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test fun `specialist cannot create activation code`() = withServer { repository, _ ->
        val owner = register("A", "owner-specialist@example.com")
        val specialist = register("Other", "specialist@example.com")
        addMembership(repository, owner, "specialist@example.com", MembershipRole.SPECIALIST)
        val selected = selectCenter(specialist.accessToken, owner.activeCenter!!.id)
        val response = client.post("/api/v1/devices/activation-codes") { bearer(selected); jsonBody("""{"deviceName":"Nope","deviceRole":"CHILD"}""") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("FORBIDDEN", errorCode(response))
    }

    @Test fun `activation code activates one device`() = withServer { _, _ ->
        val owner = register("Центр", "activate@example.com")
        val code = createCode(owner.accessToken, "Child tablet")
        val device = activate(code.activationCode, "uid-one")
        assertEquals("Child tablet", device.deviceName)
        assertTrue(device.deviceToken.isNotBlank())
        val list = client.get("/api/v1/devices") { bearer(owner.accessToken) }
        assertTrue(list.bodyAsText().contains(device.deviceId))
    }

    @Test fun `activation code cannot be used twice`() = withServer { _, _ ->
        val owner = register("Центр", "once@example.com")
        val code = createCode(owner.accessToken, "Tablet")
        assertEquals(HttpStatusCode.Created, activateResponse(code.activationCode, "uid-one").status)
        val second = activateResponse(code.activationCode, "uid-two")
        assertEquals(HttpStatusCode.BadRequest, second.status)
        assertEquals("INVALID_ACTIVATION_CODE", errorCode(second))
    }

    @Test fun `expired activation code is rejected`() = withServer { _, clock ->
        val owner = register("Центр", "expired@example.com")
        val code = createCode(owner.accessToken, "Tablet")
        clock.advanceSeconds(601)
        assertEquals(HttpStatusCode.BadRequest, activateResponse(code.activationCode, "expired-uid").status)
    }

    @Test fun `cancelled activation code is rejected`() = withServer { _, _ ->
        val owner = register("Центр", "cancel@example.com")
        val code = createCode(owner.accessToken, "Tablet")
        val deleted = client.delete("/api/v1/devices/activation-codes/${code.id}") { bearer(owner.accessToken) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals(HttpStatusCode.BadRequest, activateResponse(code.activationCode, "cancelled-uid").status)
    }

    @Test fun `old device token stops working after unlink`() = withServer { _, _ ->
        val owner = register("Центр", "unlink@example.com")
        val device = activate(createCode(owner.accessToken, "Tablet").activationCode, "unlink-uid")
        val unlink = client.post("/api/v1/devices/${device.deviceId}/unlink") { bearer(owner.accessToken) }
        assertEquals(HttpStatusCode.NoContent, unlink.status)
        val me = client.get("/api/v1/device-auth/me") { bearer(device.deviceToken) }
        assertEquals(HttpStatusCode.Forbidden, me.status)
        assertEquals("DEVICE_UNLINKED", errorCode(me))
    }

    @Test fun `blocked device cannot use protected device endpoints`() = withServer { _, _ ->
        val owner = register("Центр", "blocked@example.com")
        val device = activate(createCode(owner.accessToken, "Tablet").activationCode, "blocked-uid")
        val blocked = client.patch("/api/v1/devices/${device.deviceId}") { bearer(owner.accessToken); jsonBody("""{"status":"BLOCKED"}""") }
        assertEquals(HttpStatusCode.OK, blocked.status)
        val me = client.get("/api/v1/device-auth/me") { bearer(device.deviceToken) }
        assertEquals(HttpStatusCode.Forbidden, me.status)
        assertEquals("DEVICE_BLOCKED", errorCode(me))
    }

    @Test fun `heartbeat updates last seen and online status`() = withServer { _, clock ->
        val owner = register("Центр", "heartbeat@example.com")
        val device = activate(createCode(owner.accessToken, "Tablet").activationCode, "heartbeat-uid")
        clock.advanceSeconds(10)
        val heartbeat = client.post("/api/v1/device-auth/heartbeat") { bearer(device.deviceToken); jsonBody("""{"appVersion":"1.1.0","androidVersion":"14","model":"Tab"}""") }
        assertEquals(HttpStatusCode.OK, heartbeat.status)
        val list = json.decodeFromString<List<DeviceDto>>(client.get("/api/v1/devices?isOnline=true") { bearer(owner.accessToken) }.bodyAsText())
        assertEquals("1.1.0", list.single().appVersion)
        assertTrue(list.single().isOnline)
    }

    @Test fun `online status expires after ninety seconds`() = withServer { _, clock ->
        val owner = register("Центр", "online@example.com")
        activate(createCode(owner.accessToken, "Tablet").activationCode, "online-uid")
        clock.advanceSeconds(91)
        val online = client.get("/api/v1/devices?isOnline=true") { bearer(owner.accessToken) }
        val offline = json.decodeFromString<List<DeviceDto>>(client.get("/api/v1/devices?isOnline=false") { bearer(owner.accessToken) }.bodyAsText())
        assertEquals("[]", online.bodyAsText())
        assertEquals(1, offline.size)
        assertFalse(offline.single().isOnline)
    }

    @Test fun `parallel activation requests create only one device`() = withServer { _, _ ->
        val owner = register("Центр", "parallel@example.com")
        val code = createCode(owner.accessToken, "Tablet")
        val responses = coroutineScope { listOf(
            async { activateResponse(code.activationCode, "parallel-one") },
            async { activateResponse(code.activationCode, "parallel-two") }
        ).awaitAll() }
        assertEquals(1, responses.count { it.status == HttpStatusCode.Created })
        assertEquals(1, responses.count { it.status == HttpStatusCode.BadRequest })
        val devices = json.decodeFromString<List<DeviceDto>>(client.get("/api/v1/devices") { bearer(owner.accessToken) }.bodyAsText())
        assertEquals(1, devices.size)
    }

    @Test fun `active device cannot be moved to another center and code remains pending`() = withServer { _, _ ->
        val a = register("A", "move-active-a@example.com")
        val b = register("B", "move-active-b@example.com")
        val original = activate(createCode(a.accessToken, "A tablet").activationCode, "move-active-uid")
        val codeB = createCode(b.accessToken, "B tablet")

        val attempt = activateResponse(codeB.activationCode, "move-active-uid")
        assertEquals(HttpStatusCode.Conflict, attempt.status)
        assertEquals("DEVICE_ALREADY_ACTIVATED", errorCode(attempt))
        assertTrue(client.get("/api/v1/devices") { bearer(a.accessToken) }.bodyAsText().contains(original.deviceId))
        assertEquals("[]", client.get("/api/v1/devices") { bearer(b.accessToken) }.bodyAsText())
        assertTrue(client.get("/api/v1/devices/activation-codes") { bearer(b.accessToken) }.bodyAsText().contains("PENDING"))
    }

    @Test fun `blocked device cannot be moved to another center and code remains pending`() = withServer { _, _ ->
        val a = register("A", "move-blocked-a@example.com")
        val b = register("B", "move-blocked-b@example.com")
        val original = activate(createCode(a.accessToken, "A tablet").activationCode, "move-blocked-uid")
        assertEquals(HttpStatusCode.OK, client.patch("/api/v1/devices/${original.deviceId}") {
            bearer(a.accessToken); jsonBody("""{"status":"BLOCKED"}""")
        }.status)
        val codeB = createCode(b.accessToken, "B tablet")
        val attempt = activateResponse(codeB.activationCode, "move-blocked-uid")
        assertEquals(HttpStatusCode.Forbidden, attempt.status)
        assertEquals("DEVICE_BLOCKED", errorCode(attempt))
        assertTrue(client.get("/api/v1/devices/activation-codes") { bearer(b.accessToken) }.bodyAsText().contains("PENDING"))
    }

    @Test fun `unlinked device can move to another center and old token stops working`() = withServer { _, _ ->
        val a = register("A", "move-unlinked-a@example.com")
        val b = register("B", "move-unlinked-b@example.com")
        val original = activate(createCode(a.accessToken, "A tablet").activationCode, "move-unlinked-uid")
        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/devices/${original.deviceId}/unlink") { bearer(a.accessToken) }.status)

        val replacement = activate(createCode(b.accessToken, "B tablet").activationCode, "move-unlinked-uid")
        assertEquals(original.deviceId, replacement.deviceId)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/device-auth/me") { bearer(original.deviceToken) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/device-auth/me") { bearer(replacement.deviceToken) }.status)
        assertTrue(client.get("/api/v1/devices") { bearer(b.accessToken) }.bodyAsText().contains(original.deviceId))
    }

    @Test fun `parallel attempts cannot move an already active device or duplicate it`() = withServer { _, _ ->
        val a = register("A", "parallel-move-a@example.com")
        val b = register("B", "parallel-move-b@example.com")
        val original = activate(createCode(a.accessToken, "A tablet").activationCode, "parallel-move-uid")
        val codeB = createCode(b.accessToken, "B tablet")
        val responses = coroutineScope { listOf(
            async { activateResponse(codeB.activationCode, "parallel-move-uid") },
            async { activateResponse(codeB.activationCode, "parallel-move-uid") }
        ).awaitAll() }
        assertTrue(responses.all { it.status == HttpStatusCode.Conflict })
        assertEquals(1, json.decodeFromString<List<DeviceDto>>(client.get("/api/v1/devices") { bearer(a.accessToken) }.bodyAsText()).size)
        assertEquals("[]", client.get("/api/v1/devices") { bearer(b.accessToken) }.bodyAsText())
        assertTrue(client.get("/api/v1/devices/activation-codes") { bearer(b.accessToken) }.bodyAsText().contains("PENDING"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/device-auth/me") { bearer(original.deviceToken) }.status)
    }

    @Test fun `foreign center selection does not change tenant context`() = withServer { _, _ ->
        val a = register("A", "tenant-a@example.com")
        val b = register("B", "tenant-b@example.com")
        val response = client.post("/api/v1/centers/${b.activeCenter!!.id}/select") { bearer(a.accessToken) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("MEMBERSHIP_NOT_FOUND", errorCode(response))
        assertNotEquals(b.activeCenter.id, json.decodeFromString<kz.oyla.server.model.dto.CenterDto>(client.get("/api/v1/centers/current") { bearer(a.accessToken) }.bodyAsText()).id)
    }

    private fun withServer(block: suspend ApplicationTestBuilder.(InMemorySaasRepository, MutableClock) -> Unit) = testApplication {
        val clock = MutableClock(Instant.now())
        val repository = InMemorySaasRepository()
        application {
            module(
                sessionRepository = InMemorySessionRepository(),
                clock = clock,
                saasRepository = repository,
                saasConfig = SaasConfig("test-jwt-secret-that-is-long-enough", "test", "test", Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofMinutes(10), Duration.ofSeconds(90), 10, allowPublicRegistration = true)
            )
        }
        block(repository, clock)
    }

    private suspend fun ApplicationTestBuilder.register(name: String, email: String): AuthResponse {
        val response = registerResponse(name, email)
        assertEquals(HttpStatusCode.Created, response.status)
        return json.decodeFromString(response.bodyAsText())
    }
    private suspend fun ApplicationTestBuilder.registerResponse(name: String, email: String): HttpResponse = client.post("/api/v1/auth/register-center") {
        jsonBody("""{"centerName":"$name","firstName":"Алия","lastName":"Тест","email":"$email","password":"Password123"}""")
    }
    private suspend fun ApplicationTestBuilder.createCode(token: String, name: String): CreateActivationCodeResponse {
        val response = client.post("/api/v1/devices/activation-codes") { bearer(token); jsonBody("""{"deviceName":"$name","deviceRole":"CHILD"}""") }
        assertEquals(HttpStatusCode.Created, response.status)
        return json.decodeFromString(response.bodyAsText())
    }
    private suspend fun ApplicationTestBuilder.activate(code: String, uid: String): ActivateDeviceResponse {
        val response = activateResponse(code, uid)
        assertEquals(HttpStatusCode.Created, response.status)
        return json.decodeFromString(response.bodyAsText())
    }
    private suspend fun ApplicationTestBuilder.activateResponse(code: String, uid: String): HttpResponse = client.post("/api/v1/device-auth/activate") {
        jsonBody("""{"activationCode":"$code","deviceUid":"$uid","appVersion":"1.0.0","androidVersion":"14","model":"Test tablet"}""")
    }
    private suspend fun ApplicationTestBuilder.selectCenter(token: String, centerId: String): String {
        val response = client.post("/api/v1/centers/$centerId/select") { bearer(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString<SelectCenterResponse>(response.bodyAsText()).accessToken
    }
    private suspend fun addMembership(repository: InMemorySaasRepository, centerOwner: AuthResponse, email: String, role: MembershipRole) {
        val user = checkNotNull(repository.findUserByEmail(email)); val now = Instant.now()
        repository.seedMembership(CenterMembershipRecord(UUID.randomUUID(), UUID.fromString(centerOwner.activeCenter!!.id), user.id, role, MembershipStatus.ACTIVE, now, now))
    }
    private suspend fun errorCode(response: HttpResponse): String = json.decodeFromString<SaasErrorResponse>(response.bodyAsText()).error.code
    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) { header(HttpHeaders.Authorization, "Bearer $token") }
    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(value: String) { contentType(ContentType.Application.Json); setBody(value) }

    private class MutableClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun withZone(zone: ZoneId): Clock = this
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        fun advanceSeconds(seconds: Long) { now = now.plusSeconds(seconds) }
    }
}
