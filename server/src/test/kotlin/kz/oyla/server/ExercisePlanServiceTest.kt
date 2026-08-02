package kz.oyla.server

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.ExerciseStatus
import kz.oyla.server.model.SessionStatus
import kz.oyla.server.model.dto.AnswerExerciseRequest
import kz.oyla.server.model.dto.NextExerciseRequest
import kz.oyla.server.model.dto.ShowExerciseRequest
import kz.oyla.server.model.dto.StartExerciseRequest
import kz.oyla.server.repository.ExerciseAttemptRecord
import kz.oyla.server.repository.InMemoryExerciseRepository
import kz.oyla.server.repository.SessionExerciseRecord
import kz.oyla.server.repository.SessionRecord
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.AuthorizedSession
import kz.oyla.server.service.ExerciseService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExercisePlanServiceTest {
    @Test fun `default plan has five ordered positions and is idempotent`() = runBlocking {
        val repository = InMemoryExerciseRepository()
        val service = ExerciseService(repository)
        val sessionId = UUID.randomUUID()

        service.ensureDefaultExercisePlan(sessionId)
        service.ensureDefaultExercisePlan(sessionId)

        val plan = repository.findSessionExercises(sessionId)
        assertEquals(5, plan.size)
        assertEquals((1..5).toList(), plan.map { it.position })
        assertEquals(ExerciseService.defaultPlan, plan.map { it.exerciseId })
        assertEquals(listOf(true, false, false, false, false), plan.map { it.isCurrent })
    }

    @Test fun `legacy first exercise and its attempts survive plan initialization`() = runBlocking {
        val repository = InMemoryExerciseRepository()
        val sessionId = UUID.randomUUID()
        val legacy = SessionExerciseRecord(
            UUID.randomUUID(), sessionId, "sound-r-rocket", ExerciseStatus.COMPLETED,
            null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, 1, true
        )
        repository.createSessionExercises(listOf(legacy))
        repository.createAttempt(ExerciseAttemptRecord(
            UUID.randomUUID(), legacy.id, "rocket", 1, true, 600, UUID.randomUUID(), Instant.EPOCH
        ))

        ExerciseService(repository).ensureDefaultExercisePlan(sessionId)

        assertEquals(5, repository.countSessionExercises(sessionId))
        assertEquals(legacy.id, repository.findSessionExerciseByPosition(sessionId, 1)?.id)
        assertEquals(1, repository.countAttempts(legacy.id))
    }

    @Test fun `correct answer stays current until specialist moves to next exercise`() = runBlocking {
        val fixture = Fixture()
        val first = fixture.current()
        fixture.showStartAndAnswer(first, "rocket")

        assertEquals(ExerciseStatus.COMPLETED, fixture.current().status)
        assertEquals(1, fixture.current().position)
        assertTrue(fixture.current().isCurrent)
        assertEquals(0, fixture.repository.countAttempts(fixture.plan()[1].id))

        val changed = fixture.service.next(fixture.specialist, NextExerciseRequest(first.id.toString()))
        assertEquals(2, changed.currentPosition)
        assertEquals(ExerciseStatus.PENDING, changed.exerciseStatus)
        assertEquals(2, fixture.current().position)
        assertTrue(fixture.current().isCurrent)
        assertFalse(fixture.plan().first().isCurrent)
    }

    @Test fun `child cannot advance and repeated next cannot skip a position`() = runBlocking {
        val fixture = Fixture()
        val first = fixture.current()
        fixture.showStartAndAnswer(first, "rocket")

        val childFailure = runCatching { fixture.service.next(fixture.child, NextExerciseRequest(first.id.toString())) }.exceptionOrNull()
        assertTrue(childFailure is ApiException)
        assertEquals("FORBIDDEN", (childFailure as ApiException).errorCode)

        fixture.service.next(fixture.specialist, NextExerciseRequest(first.id.toString()))
        val replayFailure = runCatching { fixture.service.next(fixture.specialist, NextExerciseRequest(first.id.toString())) }.exceptionOrNull()
        assertTrue(replayFailure is ApiException)
        assertEquals(2, fixture.current().position)
        assertEquals(5, fixture.repository.countSessionExercises(fixture.session.id))
    }

    @Test fun `answers for old session exercise are rejected after transition`() = runBlocking {
        val fixture = Fixture()
        val first = fixture.current()
        fixture.showStartAndAnswer(first, "rocket")
        fixture.service.next(fixture.specialist, NextExerciseRequest(first.id.toString()))

        val failure = runCatching {
            fixture.service.answer(fixture.child, AnswerExerciseRequest(first.id.toString(), "rocket", UUID.randomUUID().toString()))
        }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals("NOT_FOUND", (failure as ApiException).errorCode)
    }

    @Test fun `summary is blocked until five exercises complete and then calculates values`() = runBlocking {
        val fixture = Fixture()
        val premature = runCatching { fixture.service.summary(fixture.specialist) }.exceptionOrNull()
        assertTrue(premature is ApiException)
        assertEquals("EXERCISE_PLAN_NOT_COMPLETED", (premature as ApiException).errorCode)

        fixture.completePlanWithOneInitialMistake()
        val summary = fixture.service.summary(fixture.specialist)

        assertEquals(5, summary.completedExercises)
        assertEquals(6, summary.totalAttempts)
        assertEquals(1, summary.incorrectAttempts)
        assertEquals(4, summary.firstAttemptCorrectCount)
        assertEquals(80, summary.firstAttemptCorrectPercent)
        assertEquals(1_000, summary.exercises.first().timeToCorrectMs)
        assertEquals(5, summary.exercises.size)
        assertTrue(summary.activeDurationMs >= 1_000)
    }

    @Test fun `child state hides correctness and pending next definition`() = runBlocking {
        val fixture = Fixture()
        val pending = fixture.service.stateFor(fixture.child)
        assertNull(pending.exercise)
        assertNull(pending.correctOptionId)

        val first = fixture.current()
        fixture.service.show(fixture.specialist, ShowExerciseRequest(first.exerciseId))
        val shown = fixture.service.stateFor(fixture.child)
        assertNotNull(shown.exercise)
        assertNull(shown.correctOptionId)
    }

    private class Fixture {
        val clock = MutableClock(Instant.parse("2026-08-03T09:00:00Z"))
        val repository = InMemoryExerciseRepository()
        val service = ExerciseService(repository, clock)
        val session = SessionRecord(
            id = UUID.randomUUID(), connectionCode = "1234", childName = "Алина", status = SessionStatus.READY,
            specialistDeviceId = "specialist", specialistToken = "specialist-token", childDeviceId = "child",
            childToken = "child-token", createdAt = clock.instant(), expiresAt = clock.instant().plusSeconds(3600),
            connectedAt = clock.instant(), completedAt = null
        )
        val specialist = AuthorizedSession(session, DeviceRole.SPECIALIST)
        val child = AuthorizedSession(session, DeviceRole.CHILD)

        suspend fun plan() = repository.findSessionExercises(session.id)
        suspend fun current(): SessionExerciseRecord {
            service.ensureDefaultExercisePlan(session.id)
            return checkNotNull(repository.findCurrentSessionExercise(session.id))
        }

        suspend fun showStartAndAnswer(record: SessionExerciseRecord, correct: String) {
            service.show(specialist, ShowExerciseRequest(record.exerciseId))
            service.start(specialist, StartExerciseRequest(record.id.toString()))
            clock.advanceSeconds(1)
            service.answer(child, AnswerExerciseRequest(record.id.toString(), correct, UUID.randomUUID().toString()))
        }

        suspend fun completePlanWithOneInitialMistake() {
            var record = current()
            service.show(specialist, ShowExerciseRequest(record.exerciseId))
            service.start(specialist, StartExerciseRequest(record.id.toString()))
            clock.advanceSeconds(1)
            service.answer(child, AnswerExerciseRequest(record.id.toString(), "cat", UUID.randomUUID().toString()))
            service.answer(child, AnswerExerciseRequest(record.id.toString(), "rocket", UUID.randomUUID().toString()))
            repeat(4) { index ->
                record = current()
                service.next(specialist, NextExerciseRequest(record.id.toString()))
                record = current()
                val correct = listOf("fish", "lamp", "dog", "ball")[index]
                showStartAndAnswer(record, correct)
            }
        }
    }

    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
        fun advanceSeconds(seconds: Long) { now = now.plusSeconds(seconds) }
    }
}
