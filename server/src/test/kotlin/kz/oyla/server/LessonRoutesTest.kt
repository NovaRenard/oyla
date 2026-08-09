package kz.oyla.server

import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.dto.ActivateDeviceResponse
import kz.oyla.server.model.dto.AuthResponse
import kz.oyla.server.model.dto.ChildDto
import kz.oyla.server.model.dto.ChildLessonAssignmentResponse
import kz.oyla.server.model.dto.CreateActivationCodeResponse
import kz.oyla.server.model.dto.DeviceDto
import kz.oyla.server.model.dto.DeviceLessonResponse
import kz.oyla.server.model.dto.LessonDto
import kz.oyla.server.model.dto.SpecialistDto
import kz.oyla.server.repository.InMemorySaasRepository
import kz.oyla.server.repository.InMemorySessionRepository
import kz.oyla.server.service.SaasConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonRoutesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `managed lesson is scoped to center and completion releases both tablets`() = withServer { sessions, _, _ ->
        val a = register("A", "lesson-a@example.com")
        val b = register("B", "lesson-b@example.com")
        val child = createChild(a.accessToken, "Алихан")
        val specialist = createSpecialist(a.accessToken, "Анна")
        val foreignChild = createChild(b.accessToken, "Чужой")
        val specialistTablet = activate(createCode(a.accessToken, "Кабинет", DeviceRole.SPECIALIST).activationCode, "lesson-specialist")
        val childTablet = activate(createCode(a.accessToken, "Алихан", DeviceRole.CHILD).activationCode, "lesson-child")
        val otherChildTablet = activate(createCode(a.accessToken, "Другой", DeviceRole.CHILD).activationCode, "lesson-other-child")

        val created = createLesson(specialistTablet.deviceToken, specialist.id, child.id, childTablet.deviceId)
        assertTrue(created.sessionToken.isNotBlank())
        assertTrue(sessions.getManagedLesson(a.activeCenter!!.id.toUuid(), created.sessionId.toUuid()) != null)

        val assignment = client.get("/api/v1/device-lessons/current-assignment") { bearer(childTablet.deviceToken) }
        assertEquals(HttpStatusCode.OK, assignment.status)
        assertEquals(created.sessionId, json.decodeFromString<ChildLessonAssignmentResponse>(assignment.bodyAsText()).sessionId)
        assertEquals(HttpStatusCode.NoContent, client.get("/api/v1/device-lessons/current-assignment") { bearer(otherChildTablet.deviceToken) }.status)

        val history = client.get("/api/v1/lessons") { bearer(a.accessToken) }
        assertEquals(HttpStatusCode.OK, history.status)
        assertEquals(created.sessionId, json.decodeFromString<List<LessonDto>>(history.bodyAsText()).single().id)
        assertFalse(history.bodyAsText().contains("sessionToken"))
        assertFalse(history.bodyAsText().contains("connectionCode"))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/lessons/${created.sessionId}") { bearer(b.accessToken) }.status)
        assertEquals(HttpStatusCode.NotFound, createLessonResponse(specialistTablet.deviceToken, specialist.id, foreignChild.id, childTablet.deviceId).status)

        assertEquals(HttpStatusCode.Forbidden, createLessonResponse(childTablet.deviceToken, specialist.id, child.id, otherChildTablet.deviceId).status)
        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/sessions/${created.sessionId}/complete") { bearer(created.sessionToken) }.status)
        assertEquals(HttpStatusCode.NoContent, client.get("/api/v1/device-lessons/current-assignment") { bearer(childTablet.deviceToken) }.status)
        assertEquals(HttpStatusCode.NoContent, client.get("/api/v1/device-lessons/current") { bearer(specialistTablet.deviceToken) }.status)
    }

    @Test fun `lesson creation rejects wrong role unavailable and busy child tablets`() = withServer { _, _, clock ->
        val owner = register("A", "lesson-reject@example.com")
        val child = createChild(owner.accessToken, "Алихан")
        val specialist = createSpecialist(owner.accessToken, "Анна")
        val specialistTablet = activate(createCode(owner.accessToken, "Кабинет", DeviceRole.SPECIALIST).activationCode, "reject-specialist")
        val childTablet = activate(createCode(owner.accessToken, "Алихан", DeviceRole.CHILD).activationCode, "reject-child")

        assertEquals(HttpStatusCode.Conflict, createLessonResponse(specialistTablet.deviceToken, specialist.id, child.id, specialistTablet.deviceId).status)
        val available = json.decodeFromString<List<DeviceDto>>(client.get("/api/v1/device-lessons/available-child-devices") { bearer(specialistTablet.deviceToken) }.bodyAsText())
        assertEquals(listOf(childTablet.deviceId), available.map { it.id })

        val created = createLesson(specialistTablet.deviceToken, specialist.id, child.id, childTablet.deviceId)
        assertEquals(HttpStatusCode.Conflict, createLessonResponse(specialistTablet.deviceToken, specialist.id, child.id, childTablet.deviceId).status)
        assertTrue(json.decodeFromString<List<DeviceDto>>(client.get("/api/v1/device-lessons/available-child-devices") { bearer(specialistTablet.deviceToken) }.bodyAsText()).isEmpty())

        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/sessions/${created.sessionId}/complete") { bearer(created.sessionToken) }.status)
        clock.advanceSeconds(91)
        assertTrue(json.decodeFromString<List<DeviceDto>>(client.get("/api/v1/device-lessons/available-child-devices") { bearer(specialistTablet.deviceToken) }.bodyAsText()).isEmpty())
    }

    private fun withServer(block: suspend ApplicationTestBuilder.(InMemorySessionRepository, InMemorySaasRepository, MutableClock) -> Unit) = testApplication {
        val sessions = InMemorySessionRepository()
        val tenants = InMemorySaasRepository()
        val clock = MutableClock(Instant.now())
        application {
            module(
                sessionRepository = sessions,
                clock = clock,
                saasRepository = tenants,
                saasConfig = SaasConfig("test-jwt-secret-that-is-long-enough", "test", "test", Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofMinutes(10), Duration.ofSeconds(90), 10, allowPublicRegistration = true)
            )
        }
        block(sessions, tenants, clock)
    }

    private suspend fun ApplicationTestBuilder.register(name: String, email: String): AuthResponse = json.decodeFromString(
        client.post("/api/v1/auth/register-center") { jsonBody("""{"centerName":"$name","firstName":"Алия","email":"$email","password":"Password123"}""") }.bodyAsText()
    )
    private suspend fun ApplicationTestBuilder.createChild(token: String, firstName: String): ChildDto = json.decodeFromString(
        client.post("/api/v1/children") { bearer(token); jsonBody("""{"firstName":"$firstName"}""") }.bodyAsText()
    )
    private suspend fun ApplicationTestBuilder.createSpecialist(token: String, firstName: String): SpecialistDto = json.decodeFromString(
        client.post("/api/v1/specialists") { bearer(token); jsonBody("""{"firstName":"$firstName"}""") }.bodyAsText()
    )
    private suspend fun ApplicationTestBuilder.createCode(token: String, name: String, role: DeviceRole): CreateActivationCodeResponse = json.decodeFromString(
        client.post("/api/v1/devices/activation-codes") { bearer(token); jsonBody("""{"deviceName":"$name","deviceRole":"$role"}""") }.bodyAsText()
    )
    private suspend fun ApplicationTestBuilder.activate(code: String, uid: String): ActivateDeviceResponse = json.decodeFromString(
        client.post("/api/v1/device-auth/activate") { jsonBody("""{"activationCode":"$code","deviceUid":"$uid"}""") }.bodyAsText()
    )
    private suspend fun ApplicationTestBuilder.createLesson(token: String, specialistId: String, childId: String, childDeviceId: String): DeviceLessonResponse {
        val response = createLessonResponse(token, specialistId, childId, childDeviceId)
        assertEquals(HttpStatusCode.Created, response.status)
        return json.decodeFromString(response.bodyAsText())
    }
    private suspend fun ApplicationTestBuilder.createLessonResponse(token: String, specialistId: String, childId: String, childDeviceId: String): HttpResponse =
        client.post("/api/v1/device-lessons") { bearer(token); jsonBody("""{"specialistId":"$specialistId","childId":"$childId","childDeviceId":"$childDeviceId"}""") }
    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) { header(HttpHeaders.Authorization, "Bearer $token") }
    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(body: String) { contentType(ContentType.Application.Json); setBody(body) }
    private fun String.toUuid() = java.util.UUID.fromString(this)

    private class MutableClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun withZone(zone: ZoneId): Clock = this
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        fun advanceSeconds(seconds: Long) { now = now.plusSeconds(seconds) }
    }
}
