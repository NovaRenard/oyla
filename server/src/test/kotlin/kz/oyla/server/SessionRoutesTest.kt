package kz.oyla.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.Json
import kz.oyla.server.model.SessionStatus
import kz.oyla.server.model.dto.HealthResponse
import kz.oyla.server.model.dto.ConnectSessionResponse
import kz.oyla.server.model.dto.CreateSessionResponse
import kz.oyla.server.repository.InMemorySessionRepository
import kz.oyla.server.repository.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.parse("2026-08-02T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `health returns ok`() = withServer { _ ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertEquals("ok", json.decodeFromString<HealthResponse>(body).status)
        assertTrue(body.contains("\"status\""))
    }

    @Test
    fun `creates session with four digit code`() = withServer { _ ->
        val response = createSession()
        assertEquals(HttpStatusCode.Created, response.status)
        val body = json.decodeFromString<CreateSessionResponse>(response.bodyAsText())
        assertTrue(body.connectionCode.matches(Regex("\\d{4}")))
        assertEquals(SessionStatus.WAITING_FOR_CHILD, body.status)
    }

    @Test
    fun `connects a child using a valid code`() = withServer { _ ->
        val created = createSessionBody()
        val response = connect(created.connectionCode, "child-1")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<ConnectSessionResponse>(response.bodyAsText())
        assertEquals(created.sessionId, body.sessionId)
        assertEquals(SessionStatus.READY, body.status)
    }

    @Test
    fun `unknown code returns 404`() = withServer { _ ->
        assertEquals(HttpStatusCode.NotFound, connect("9999", "child-1").status)
    }

    @Test
    fun `expired code returns 410`() = withServer { repository ->
        repository.createSession(
            testSession(code = "1234", expiresAt = now.minusSeconds(1))
        )
        assertEquals(HttpStatusCode.Gone, connect("1234", "child-1").status)
    }

    @Test
    fun `same child device can connect repeatedly`() = withServer { _ ->
        val created = createSessionBody()
        val first = json.decodeFromString<ConnectSessionResponse>(connect(created.connectionCode, "child-1").bodyAsText())
        val second = json.decodeFromString<ConnectSessionResponse>(connect(created.connectionCode, "child-1").bodyAsText())
        assertEquals(first.sessionId, second.sessionId)
        assertEquals(first.childToken, second.childToken)
    }

    @Test
    fun `other child device receives 409`() = withServer { _ ->
        val created = createSessionBody()
        connect(created.connectionCode, "child-1")
        assertEquals(HttpStatusCode.Conflict, connect(created.connectionCode, "child-2").status)
    }

    @Test
    fun `child can cancel a connected session`() = withServer { _ ->
        val created = createSessionBody()
        val child = json.decodeFromString<ConnectSessionResponse>(
            connect(created.connectionCode, "child-1").bodyAsText()
        )

        val response = client.post("/api/v1/sessions/${created.sessionId}/cancel") {
            header(HttpHeaders.Authorization, "Bearer ${child.childToken}")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `invalid token cannot read session state`() = withServer { _ ->
        val created = createSessionBody()
        val response = client.get("/api/v1/sessions/${created.sessionId}") {
            header(HttpHeaders.Authorization, "Bearer invalid-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    private fun testSession(code: String, expiresAt: Instant) = SessionRecord(
        id = UUID.randomUUID(),
        connectionCode = code,
        childName = "Алина",
        status = SessionStatus.WAITING_FOR_CHILD,
        specialistDeviceId = "specialist-1",
        specialistToken = "specialist-token",
        childDeviceId = null,
        childToken = null,
        createdAt = now.minusSeconds(60),
        expiresAt = expiresAt,
        connectedAt = null,
        completedAt = null
    )

    private fun withServer(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(InMemorySessionRepository) -> Unit) =
        testApplication {
            val repository = InMemorySessionRepository()
            application { module(repository, clock) }
            block(repository)
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.createSession() = client.post("/api/v1/sessions") {
        contentType(ContentType.Application.Json)
        setBody("{\"childName\":\"Алина\",\"deviceId\":\"specialist-1\"}")
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.createSessionBody(): CreateSessionResponse =
        json.decodeFromString(createSession().bodyAsText())

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.connect(code: String, deviceId: String) =
        client.post("/api/v1/sessions/connect") {
            contentType(ContentType.Application.Json)
            setBody("{\"connectionCode\":\"$code\",\"deviceId\":\"$deviceId\"}")
        }
}
