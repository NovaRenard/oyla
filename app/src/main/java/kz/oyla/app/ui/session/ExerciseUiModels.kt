package kz.oyla.app.ui.session

import kz.oyla.app.data.remote.SocketConnectionState
import kz.oyla.app.data.remote.dto.AnswerExerciseResponse
import kz.oyla.app.data.remote.dto.ExerciseDto
import kz.oyla.app.data.remote.dto.ExerciseStateResponse

enum class ExerciseUiStatus { PENDING, SHOWN, RUNNING, COMPLETED }

data class ExerciseOptionUiModel(
    val id: String,
    val label: String,
    val imageAssetKey: String,
    val position: Int
)

data class ExerciseUiModel(
    val id: String,
    val instructionText: String,
    val audioAssetKey: String?,
    val options: List<ExerciseOptionUiModel>,
    val correctOptionId: String? = null
)

data class AnswerUiModel(
    val selectedOptionId: String,
    val selectedOptionLabel: String,
    val isCorrect: Boolean,
    val attemptNumber: Int,
    val responseTimeMs: Long
)

data class ExerciseUiState(
    val sessionId: String = "",
    val childName: String = "",
    val connectionState: SocketConnectionState = SocketConnectionState.DISCONNECTED,
    val exercise: ExerciseUiModel? = null,
    val sessionExerciseId: String? = null,
    val exerciseStatus: ExerciseUiStatus = ExerciseUiStatus.PENDING,
    val latestAnswer: AnswerUiModel? = null,
    val attemptCount: Int = 0,
    val startedAt: String? = null,
    val elapsedMillis: Long = 0,
    val isCommandLoading: Boolean = false,
    val isAnswerPending: Boolean = false,
    val pendingOptionId: String? = null,
    val feedbackMessage: String? = null,
    val playInstructionRequest: Int = 0,
    val errorMessage: String? = null
)

internal fun String.toExerciseUiStatus(): ExerciseUiStatus = runCatching { ExerciseUiStatus.valueOf(this) }
    .getOrDefault(ExerciseUiStatus.PENDING)

internal fun ExerciseDto.toUi(correctOptionId: String? = null) = ExerciseUiModel(
    id = id, instructionText = instructionText, audioAssetKey = audioAssetKey,
    options = options.sortedBy { it.position }.map { ExerciseOptionUiModel(it.id, it.label, it.imageAssetKey, it.position) },
    correctOptionId = correctOptionId
)

internal fun AnswerExerciseResponse.toUi() = AnswerUiModel(
    selectedOptionId, selectedOptionLabel, isCorrect, attemptNumber, responseTimeMs
)

internal fun ExerciseStateResponse.toUiState(sessionId: String, childName: String, connection: SocketConnectionState) = ExerciseUiState(
    sessionId = sessionId, childName = childName, connectionState = connection,
    exercise = exercise?.toUi(correctOptionId), sessionExerciseId = sessionExerciseId,
    exerciseStatus = exerciseStatus.toExerciseUiStatus(), latestAnswer = latestAnswer?.toUi(),
    attemptCount = attemptCount, startedAt = startedAt
)
