package kz.oyla.app.ui.session

import kz.oyla.app.data.remote.NetworkResult
import kz.oyla.app.data.remote.dto.AnswerExerciseResponse
import kz.oyla.app.data.remote.dto.ExerciseDto
import kz.oyla.app.data.remote.dto.ExerciseOptionDto
import kz.oyla.app.data.remote.dto.ExerciseStateResponse
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
}
