package kz.oyla.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kz.oyla.server.model.dto.AnswerExerciseResponse
import kz.oyla.server.model.dto.ConnectSessionResponse
import kz.oyla.server.model.dto.CreateSessionResponse
import kz.oyla.server.model.dto.ExerciseStateResponse
import kz.oyla.server.model.dto.ShowExerciseResponse
import kz.oyla.server.model.dto.StartExerciseResponse
import kz.oyla.server.model.dto.StateSnapshotEvent
import kz.oyla.server.repository.InMemorySessionRepository
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseRoutesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun `specialist can show an exercise idempotently`() = withServer { _ ->
        val tokens = readySession()
        val first = show(tokens.specialistToken, tokens.sessionId)
        val second = show(tokens.specialistToken, tokens.sessionId)
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(
            json.decodeFromString<ShowExerciseResponse>(first.bodyAsText()).sessionExerciseId,
            json.decodeFromString<ShowExerciseResponse>(second.bodyAsText()).sessionExerciseId
        )
    }

    @Test fun `child token cannot show exercise`() = withServer { _ ->
        val tokens = readySession()
        assertEquals(HttpStatusCode.Forbidden, show(tokens.childToken, tokens.sessionId).status)
    }

    @Test fun `cannot show exercise without child`() = withServer { _ ->
        val specialist = createSessionBody()
        assertEquals(HttpStatusCode.Conflict, show(specialist.specialistToken, specialist.sessionId).status)
    }

    @Test fun `specialist can start shown exercise`() = withServer { _ ->
        val tokens = readySession()
        val shown = json.decodeFromString<ShowExerciseResponse>(show(tokens.specialistToken, tokens.sessionId).bodyAsText())
        val started = start(tokens.specialistToken, tokens.sessionId, shown.sessionExerciseId)
        assertEquals(HttpStatusCode.OK, started.status)
        assertEquals("RUNNING", json.decodeFromString<StartExerciseResponse>(started.bodyAsText()).status.name)
    }

    @Test fun `cannot start pending or running exercise twice`() = withServer { _ ->
        val tokens = readySession()
        assertEquals(HttpStatusCode.NotFound, start(tokens.specialistToken, tokens.sessionId, java.util.UUID.randomUUID().toString()).status)
        val shown = json.decodeFromString<ShowExerciseResponse>(show(tokens.specialistToken, tokens.sessionId).bodyAsText())
        assertEquals(HttpStatusCode.OK, start(tokens.specialistToken, tokens.sessionId, shown.sessionExerciseId).status)
        assertEquals(HttpStatusCode.Conflict, start(tokens.specialistToken, tokens.sessionId, shown.sessionExerciseId).status)
    }

    @Test fun `cannot answer before running`() = withServer { _ ->
        val tokens = readySession()
        val shown = json.decodeFromString<ShowExerciseResponse>(show(tokens.specialistToken, tokens.sessionId).bodyAsText())
        assertEquals(HttpStatusCode.Conflict, answer(tokens.childToken, tokens.sessionId, shown.sessionExerciseId, "cat", java.util.UUID.randomUUID().toString()).status)
    }

    @Test fun `incorrect answer remains running and correct answer completes with attempts`() = withServer { clock ->
        val tokens = readySession()
        val shown = json.decodeFromString<ShowExerciseResponse>(show(tokens.specialistToken, tokens.sessionId).bodyAsText())
        start(tokens.specialistToken, tokens.sessionId, shown.sessionExerciseId)
        clock.advanceSeconds(3)
        val wrong = json.decodeFromString<AnswerExerciseResponse>(answer(tokens.childToken, tokens.sessionId, shown.sessionExerciseId, "cat", java.util.UUID.randomUUID().toString()).bodyAsText())
        assertFalse(wrong.isCorrect); assertEquals("RUNNING", wrong.exerciseStatus.name); assertEquals(1, wrong.attemptNumber); assertEquals(3_000, wrong.responseTimeMs)
        clock.advanceSeconds(2)
        val correct = json.decodeFromString<AnswerExerciseResponse>(answer(tokens.childToken, tokens.sessionId, shown.sessionExerciseId, "rocket", java.util.UUID.randomUUID().toString()).bodyAsText())
        assertTrue(correct.isCorrect); assertEquals("COMPLETED", correct.exerciseStatus.name); assertEquals(2, correct.attemptNumber); assertEquals(5_000, correct.responseTimeMs)
    }

    @Test fun `duplicate event id does not create another attempt`() = withServer { _ ->
        val tokens = readySession()
        val shown = json.decodeFromString<ShowExerciseResponse>(show(tokens.specialistToken, tokens.sessionId).bodyAsText())
        start(tokens.specialistToken, tokens.sessionId, shown.sessionExerciseId)
        val eventId = java.util.UUID.randomUUID().toString()
        val first = json.decodeFromString<AnswerExerciseResponse>(answer(tokens.childToken, tokens.sessionId, shown.sessionExerciseId, "cat", eventId).bodyAsText())
        val replay = json.decodeFromString<AnswerExerciseResponse>(answer(tokens.childToken, tokens.sessionId, shown.sessionExerciseId, "cat", eventId).bodyAsText())
        assertEquals(first.attemptNumber, replay.attemptNumber)
        val state = exerciseState(tokens.childToken, tokens.sessionId)
        assertEquals(1, json.decodeFromString<ExerciseStateResponse>(state.bodyAsText()).attemptCount)
    }

    @Test fun `foreign option is rejected`() = withServer { _ ->
        val tokens = readySession()
        val shown = json.decodeFromString<ShowExerciseResponse>(show(tokens.specialistToken, tokens.sessionId).bodyAsText())
        start(tokens.specialistToken, tokens.sessionId, shown.sessionExerciseId)
        assertEquals(HttpStatusCode.BadRequest, answer(tokens.childToken, tokens.sessionId, shown.sessionExerciseId, "other-exercise-option", java.util.UUID.randomUUID().toString()).status)
    }

    @Test fun `state snapshot data contains active exercise but hides correct option from child`() = withServer { _ ->
        val tokens = readySession()
        val shown = json.decodeFromString<ShowExerciseResponse>(show(tokens.specialistToken, tokens.sessionId).bodyAsText())
        start(tokens.specialistToken, tokens.sessionId, shown.sessionExerciseId)
        val child = json.decodeFromString<ExerciseStateResponse>(exerciseState(tokens.childToken, tokens.sessionId).bodyAsText())
        val specialist = json.decodeFromString<ExerciseStateResponse>(exerciseState(tokens.specialistToken, tokens.sessionId).bodyAsText())
        assertEquals("RUNNING", child.exerciseStatus.name); assertNotNull(child.exercise); assertNull(child.correctOptionId)
        assertEquals("rocket", specialist.correctOptionId)
    }

    @Test fun `websocket snapshot restores current state without exposing correct option to child`() = withServer { _ ->
        val tokens = readySession()
        val shown = json.decodeFromString<ShowExerciseResponse>(show(tokens.specialistToken, tokens.sessionId).bodyAsText())
        start(tokens.specialistToken, tokens.sessionId, shown.sessionExerciseId)
        var rawSnapshot = ""
        createClient { install(WebSockets) }.webSocket("/ws/sessions/${tokens.sessionId}?token=${tokens.childToken}") {
            rawSnapshot = withTimeout(2_000) { (incoming.receive() as Frame.Text).readText() }
        }
        val snapshot = json.decodeFromString<StateSnapshotEvent>(rawSnapshot)
        assertEquals("RUNNING", snapshot.exerciseStatus.name)
        assertNotNull(snapshot.exercise)
        assertFalse(rawSnapshot.contains("correctOptionId"))
    }

    private fun withServer(block: suspend ApplicationTestBuilder.(MutableClock) -> Unit) = testApplication {
        val clock = MutableClock(Instant.parse("2026-08-02T09:00:00Z"))
        application { module(InMemorySessionRepository(), clock) }
        block(clock)
    }

    private suspend fun ApplicationTestBuilder.readySession(): Tokens {
        val specialist = createSessionBody()
        val child = json.decodeFromString<ConnectSessionResponse>(client.post("/api/v1/sessions/connect") {
            contentType(ContentType.Application.Json); setBody("{\"connectionCode\":\"${specialist.connectionCode}\",\"deviceId\":\"child-1\"}")
        }.bodyAsText())
        return Tokens(specialist.sessionId, specialist.specialistToken, child.childToken)
    }
    private suspend fun ApplicationTestBuilder.createSessionBody() = json.decodeFromString<CreateSessionResponse>(client.post("/api/v1/sessions") {
        contentType(ContentType.Application.Json); setBody("{\"childName\":\"Алина\",\"deviceId\":\"specialist-1\"}")
    }.bodyAsText())
    private suspend fun ApplicationTestBuilder.show(token: String, sessionId: String) = client.post("/api/v1/sessions/$sessionId/exercise/show") {
        header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json); setBody("{\"exerciseId\":\"sound-r-rocket\"}")
    }
    private suspend fun ApplicationTestBuilder.start(token: String, sessionId: String, exerciseId: String) = client.post("/api/v1/sessions/$sessionId/exercise/start") {
        header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json); setBody("{\"sessionExerciseId\":\"$exerciseId\"}")
    }
    private suspend fun ApplicationTestBuilder.answer(token: String, sessionId: String, exerciseId: String, option: String, event: String) = client.post("/api/v1/sessions/$sessionId/exercise/answer") {
        header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json); setBody("{\"sessionExerciseId\":\"$exerciseId\",\"selectedOptionId\":\"$option\",\"clientEventId\":\"$event\"}")
    }
    private suspend fun ApplicationTestBuilder.exerciseState(token: String, sessionId: String) = client.get("/api/v1/sessions/$sessionId/exercise/state") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }
    private data class Tokens(val sessionId: String, val specialistToken: String, val childToken: String)
    private class MutableClock(private var current: Instant) : Clock() {
        override fun instant(): Instant = current
        override fun withZone(zone: ZoneId): Clock = this
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        fun advanceSeconds(seconds: Long) { current = current.plusSeconds(seconds) }
    }
}
