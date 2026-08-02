package kz.oyla.app.ui.session

import kz.oyla.app.data.remote.NetworkResult
import kz.oyla.app.data.remote.dto.AnswerExerciseResponse
import kz.oyla.app.data.remote.dto.ExerciseDto
import kz.oyla.app.data.remote.dto.ExerciseOptionDto
import kz.oyla.app.data.remote.dto.ExerciseStateResponse
import kz.oyla.app.data.remote.dto.ExerciseSummaryItemDto
import kz.oyla.app.data.remote.dto.SessionSummaryResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseUiModelsTest {
    private val exercise = ExerciseDto(
        "sound-r-rocket", "Найди картинку, в названии которой есть звук «Р»", "exercise_sound_r",
        listOf(ExerciseOptionDto("cat", "кот", "exercise_cat", 2), ExerciseOptionDto("rocket", "ракета", "exercise_rocket", 1))
    )

    @Test fun `EXERCISE_SHOWN mapping preserves child-safe exercise`() {
        val state = ExerciseStateResponse("session-exercise", "SHOWN", exercise, null, null, 0, null)
            .toUiState("session", "Алина", kz.oyla.app.data.remote.SocketConnectionState.CONNECTED)
        assertEquals(ExerciseUiStatus.SHOWN, state.exerciseStatus)
        assertEquals("rocket", state.exercise!!.options.first().id)
        assertNull(state.exercise.correctOptionId)
    }

    @Test fun `EXERCISE_STARTED mapping restores server start time`() {
        val state = ExerciseStateResponse("session-exercise", "RUNNING", exercise, null, null, 0, "2026-08-02T09:00:00Z")
            .toUiState("session", "Алина", kz.oyla.app.data.remote.SocketConnectionState.CONNECTED)
        assertEquals(ExerciseUiStatus.RUNNING, state.exerciseStatus)
        assertEquals("2026-08-02T09:00:00Z", state.startedAt)
    }

    @Test fun `incorrect answer remains running`() {
        val answer = AnswerExerciseResponse("se", "cat", "кот", false, 1, 2450, "RUNNING").toUi()
        assertFalse(answer.isCorrect); assertEquals(1, answer.attemptNumber); assertEquals(2450, answer.responseTimeMs)
    }

    @Test fun `correct answer maps completed result`() {
        val answer = AnswerExerciseResponse("se", "rocket", "ракета", true, 2, 5100, "COMPLETED").toUi()
        assertTrue(answer.isCorrect); assertEquals("ракета", answer.selectedOptionLabel)
    }

    @Test fun `answer guard blocks a double tap and allows retry`() {
        val guard = AnswerSubmissionGuard()
        assertTrue(guard.tryAcquire()); assertFalse(guard.tryAcquire())
        guard.release()
        assertTrue(guard.tryAcquire())
    }

    @Test fun `snapshot preserves reconnect state`() {
        val state = ExerciseStateResponse("se", "RUNNING", exercise, null,
            AnswerExerciseResponse("se", "cat", "кот", false, 1, 2450, "RUNNING"), 1, "2026-08-02T09:00:00Z")
            .toUiState("session", "Алина", kz.oyla.app.data.remote.SocketConnectionState.RECONNECTING)
        assertEquals(1, state.attemptCount); assertEquals("cat", state.latestAnswer?.selectedOptionId)
        assertEquals(kz.oyla.app.data.remote.SocketConnectionState.RECONNECTING, state.connectionState)
    }

    @Test fun `unknown asset uses nonzero placeholder resource`() {
        assertTrue(exerciseDrawableFor("missing_asset") != 0)
    }

    @Test fun `progress maps current position one of five`() {
        val state = ExerciseStateResponse(
            sessionExerciseId = "se", exerciseStatus = "PENDING", currentPosition = 1,
            totalExercises = 5, hasNext = true, planCompleted = false
        ).toUiState("session", "Алина", kz.oyla.app.data.remote.SocketConnectionState.CONNECTED)
        assertEquals(1, state.currentPosition)
        assertEquals(5, state.totalExercises)
        assertTrue(state.hasNext)
    }

    @Test fun `change to next exercise clears previous answer timer and feedback`() {
        val old = ExerciseUiState(
            sessionId = "session", childName = "Алина", sessionExerciseId = "old", exercise = exercise.toUi(),
            exerciseStatus = ExerciseUiStatus.COMPLETED,
            latestAnswer = AnswerExerciseResponse("old", "rocket", "ракета", true, 2, 6200, "COMPLETED").toUi(),
            attemptCount = 2, startedAt = "2026-08-02T09:00:00Z", elapsedMillis = 6200,
            feedbackMessage = "Отлично!", pendingOptionId = "rocket", isAnswerPending = true, playInstructionRequest = 3
        )
        val reset = old.resetForExerciseChange("new", null, ExerciseUiStatus.PENDING, 2, 5, true, false)

        assertEquals("new", reset.sessionExerciseId)
        assertNull(reset.exercise)
        assertNull(reset.latestAnswer)
        assertEquals(0, reset.attemptCount)
        assertNull(reset.startedAt)
        assertEquals(0, reset.elapsedMillis)
        assertNull(reset.feedbackMessage)
        assertFalse(reset.isAnswerPending)
        assertEquals(0, reset.playInstructionRequest)
    }

    @Test fun `next is only available for completed nonfinal position`() {
        assertFalse(ExerciseUiState(exerciseStatus = ExerciseUiStatus.RUNNING, currentPosition = 1, totalExercises = 5).canMoveToNext())
        assertTrue(ExerciseUiState(exerciseStatus = ExerciseUiStatus.COMPLETED, currentPosition = 4, totalExercises = 5).canMoveToNext())
        assertFalse(ExerciseUiState(exerciseStatus = ExerciseUiStatus.COMPLETED, currentPosition = 5, totalExercises = 5).canMoveToNext())
        assertTrue(ExerciseUiState(exerciseStatus = ExerciseUiStatus.COMPLETED, currentPosition = 5, totalExercises = 5).canOpenSummary())
    }

    @Test fun `formatting time and summary DTO preserve server values`() {
        assertEquals("01:14", formatTime(74_000))
        val summary = SessionSummaryResponse(
            "session", "Алина", 5, 5, 8, 3, 3, 60, 74_000,
            "2026-08-02T09:00:00Z", "2026-08-02T09:01:14Z",
            listOf(ExerciseSummaryItemDto(1, "sound-r-rocket", "...", "ракета", 2, 1, false, 6200,
                "2026-08-02T09:00:00Z", "2026-08-02T09:00:06Z"))
        )
        assertEquals(60, summary.firstAttemptCorrectPercent)
        assertEquals("ракета", summary.exercises.single().correctOptionLabel)
    }

    @Test fun `both command guards block fast double requests`() {
        val next = NextExerciseGuard()
        assertTrue(next.tryAcquire())
        assertFalse(next.tryAcquire())
        next.release()
        assertTrue(next.tryAcquire())
    }
}
