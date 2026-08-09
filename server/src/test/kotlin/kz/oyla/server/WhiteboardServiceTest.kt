package kz.oyla.server

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.ExerciseSnapshot
import kz.oyla.server.model.ExerciseStatus
import kz.oyla.server.model.SessionStatus
import kz.oyla.server.model.WhiteboardBrushSize
import kz.oyla.server.model.WhiteboardColor
import kz.oyla.server.model.WhiteboardExerciseConfig
import kz.oyla.server.model.WhiteboardTool
import kz.oyla.server.model.dto.WhiteboardPointDto
import kz.oyla.server.repository.InMemoryExerciseRepository
import kz.oyla.server.repository.InMemoryWhiteboardRepository
import kz.oyla.server.repository.SessionExerciseRecord
import kz.oyla.server.repository.SessionRecord
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.AuthorizedSession
import kz.oyla.server.service.SessionLockRegistry
import kz.oyla.server.service.WhiteboardService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteboardServiceTest {
    @Test fun `child drawing permission is server enforced and completed stroke is idempotent`() = runBlocking {
        val fixture = Fixture(initialChildPermission = false)
        val strokeId = UUID.randomUUID().toString()
        val blocked = runCatching { fixture.start(fixture.child, strokeId) }.exceptionOrNull()
        assertTrue(blocked is ApiException); assertEquals("FORBIDDEN", (blocked as ApiException).errorCode)

        fixture.whiteboards.setChildDrawingEnabled(fixture.specialist, fixture.exercise.id.toString(), true)
        fixture.start(fixture.child, strokeId)
        fixture.whiteboards.appendPoints(fixture.child, fixture.exercise.id.toString(), strokeId, listOf(WhiteboardPointDto(.2f, .2f), WhiteboardPointDto(.4f, .4f)))
        val eventId = UUID.randomUUID().toString()
        val completed = fixture.whiteboards.completeStroke(fixture.child, fixture.exercise.id.toString(), strokeId, eventId)
        val duplicate = fixture.whiteboards.completeStroke(fixture.child, fixture.exercise.id.toString(), strokeId, eventId)

        assertEquals(completed.id, duplicate.id)
        assertEquals(1, fixture.whiteboards.snapshotFor(fixture.exercise)?.strokes?.size)
    }

    @Test fun `undo and clear survive snapshot revisions`() = runBlocking {
        val fixture = Fixture(initialChildPermission = true)
        fixture.complete(fixture.specialist, "specialist", UUID.randomUUID().toString())
        fixture.complete(fixture.child, "child", UUID.randomUUID().toString())
        val beforeUndo = fixture.whiteboards.snapshotFor(fixture.exercise)!!
        assertEquals(listOf(1, 2), beforeUndo.strokes.map { it.sequenceNumber })

        val undone = fixture.whiteboards.undo(fixture.child, fixture.exercise.id.toString())
        assertNotNull(undone)
        assertEquals(1, fixture.whiteboards.snapshotFor(fixture.exercise)?.strokes?.size)

        val cleared = fixture.whiteboards.clear(fixture.specialist, fixture.exercise.id.toString())
        val afterClear = fixture.whiteboards.snapshotFor(fixture.exercise)!!
        assertTrue(cleared.clearRevision > 0)
        assertTrue(afterClear.strokes.isEmpty())
        assertEquals(cleared.clearRevision, afterClear.clearRevision)
        assertFalse(afterClear.childDrawingEnabled.not())
    }

    @Test fun `invalid normalized coordinate is rejected`() = runBlocking {
        val fixture = Fixture(initialChildPermission = true)
        val strokeId = UUID.randomUUID().toString()
        fixture.start(fixture.specialist, strokeId)
        val error = runCatching {
            fixture.whiteboards.appendPoints(fixture.specialist, fixture.exercise.id.toString(), strokeId, listOf(WhiteboardPointDto(1.01f, .2f)))
        }.exceptionOrNull()
        assertTrue(error is ApiException); assertEquals("VALIDATION_ERROR", (error as ApiException).errorCode)
    }

    @Test fun `child cannot clear and can undo only its own latest stroke`() = runBlocking {
        val fixture = Fixture(initialChildPermission = true)
        fixture.complete(fixture.child, "child-first", UUID.randomUUID().toString())
        fixture.complete(fixture.specialist, "specialist-last", UUID.randomUUID().toString())

        val clearError = runCatching { fixture.whiteboards.clear(fixture.child, fixture.exercise.id.toString()) }.exceptionOrNull()
        val undone = fixture.whiteboards.undo(fixture.child, fixture.exercise.id.toString())
        val remaining = fixture.whiteboards.snapshotFor(fixture.exercise)!!.strokes

        assertTrue(clearError is ApiException); assertEquals("FORBIDDEN", (clearError as ApiException).errorCode)
        assertNotNull(undone)
        assertEquals(listOf("SPECIALIST"), remaining.map { it.actorRole })
    }

    @Test fun `concurrent completed strokes receive consecutive server sequence numbers`() = runBlocking {
        val fixture = Fixture(initialChildPermission = true)
        coroutineScope {
            listOf(
                async { fixture.complete(fixture.specialist, "specialist-concurrent", UUID.randomUUID().toString()) },
                async { fixture.complete(fixture.child, "child-concurrent", UUID.randomUUID().toString()) }
            ).awaitAll()
        }

        val snapshot = fixture.whiteboards.snapshotFor(fixture.exercise)!!
        assertEquals(listOf(1, 2), snapshot.strokes.map { it.sequenceNumber })
        assertEquals(setOf("SPECIALIST", "CHILD"), snapshot.strokes.map { it.actorRole }.toSet())
    }

    @Test fun `oversized point batch is rejected before persistence`() = runBlocking {
        val fixture = Fixture(initialChildPermission = true); val strokeId = UUID.randomUUID().toString()
        fixture.start(fixture.specialist, strokeId)
        val error = runCatching {
            fixture.whiteboards.appendPoints(fixture.specialist, fixture.exercise.id.toString(), strokeId, List(97) { WhiteboardPointDto(.1f, .1f) })
        }.exceptionOrNull()

        assertTrue(error is ApiException); assertEquals("VALIDATION_ERROR", (error as ApiException).errorCode)
        assertTrue(fixture.whiteboards.snapshotFor(fixture.exercise)!!.strokes.isEmpty())
    }

    private class Fixture(initialChildPermission: Boolean) {
        val sessionId = UUID.randomUUID()
        val exercise = SessionExerciseRecord(UUID.randomUUID(), sessionId, UUID.randomUUID().toString(), ExerciseStatus.RUNNING, Instant.EPOCH, Instant.EPOCH, null, Instant.EPOCH, 1, true,
            ExerciseSnapshot(UUID.randomUUID().toString(), ActivityType.WHITEBOARD, "Доска", "Нарисуй", whiteboardConfig = WhiteboardExerciseConfig(childDrawingInitiallyEnabled = initialChildPermission)))
        private val exercises = InMemoryExerciseRepository().also { runBlocking { it.createSessionExercises(listOf(exercise)) } }
        val whiteboards = WhiteboardService(InMemoryWhiteboardRepository(), exercises, SessionLockRegistry())
        private val session = SessionRecord(sessionId, "1234", "Ребёнок", SessionStatus.READY, "specialist-device", "specialist-token", "child-device", "child-token", Instant.EPOCH, Instant.MAX, Instant.EPOCH, null)
        val specialist = AuthorizedSession(session, DeviceRole.SPECIALIST)
        val child = AuthorizedSession(session, DeviceRole.CHILD)

        init { runBlocking { whiteboards.initialize(exercise) } }
        suspend fun start(actor: AuthorizedSession, strokeId: String) = whiteboards.startStroke(actor, exercise.id.toString(), strokeId, WhiteboardTool.PEN, WhiteboardColor.BLACK, WhiteboardBrushSize.MEDIUM)
        suspend fun complete(actor: AuthorizedSession, strokeId: String, eventId: String) {
            val validStrokeId = UUID.nameUUIDFromBytes(strokeId.toByteArray()).toString()
            start(actor, validStrokeId)
            whiteboards.appendPoints(actor, exercise.id.toString(), validStrokeId, listOf(WhiteboardPointDto(.1f, .1f), WhiteboardPointDto(.3f, .3f)))
            whiteboards.completeStroke(actor, exercise.id.toString(), validStrokeId, eventId)
        }
    }
}
