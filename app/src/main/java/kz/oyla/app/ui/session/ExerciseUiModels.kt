package kz.oyla.app.ui.session

import kz.oyla.app.data.remote.SocketConnectionState
import kz.oyla.app.data.remote.dto.AnswerExerciseResponse
import kz.oyla.app.data.remote.dto.ExerciseDto
import kz.oyla.app.data.remote.dto.ExerciseStateResponse
import kz.oyla.app.BuildConfig

sealed interface ExerciseImage {
    data class LocalAsset(val key: String) : ExerciseImage
    data class RemoteAsset(val url: String) : ExerciseImage
}

enum class ExerciseUiStatus { PENDING, SHOWN, RUNNING, COMPLETED }

data class ExerciseOptionUiModel(
    val id: String,
    val label: String,
    val imageAssetKey: String,
    val position: Int,
    val imageUrl: String? = null
) { val image: ExerciseImage get() = imageUrl?.let(ExerciseImage::RemoteAsset) ?: ExerciseImage.LocalAsset(imageAssetKey) }

data class ExerciseUiModel(
    val id: String,
    val instructionText: String,
    val audioAssetKey: String?,
    val options: List<ExerciseOptionUiModel>,
    val correctOptionId: String? = null,
    val audioUrl: String? = null
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
    val currentPosition: Int = 1,
    val totalExercises: Int = 0,
    val hasNext: Boolean = false,
    val planCompleted: Boolean = false,
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
    options = options.sortedBy { it.position }.map { ExerciseOptionUiModel(it.id, it.label, it.imageAssetKey, it.position, it.imageUrl.toAbsoluteMediaUrl()) },
    correctOptionId = correctOptionId, audioUrl = audioUrl.toAbsoluteMediaUrl()
)

internal fun AnswerExerciseResponse.toUi() = AnswerUiModel(
    selectedOptionId, selectedOptionLabel, isCorrect, attemptNumber, responseTimeMs
)

internal fun ExerciseStateResponse.toUiState(sessionId: String, childName: String, connection: SocketConnectionState) = ExerciseUiState(
    sessionId = sessionId, childName = childName, connectionState = connection,
    exercise = exercise?.toUi(correctOptionId), sessionExerciseId = sessionExerciseId,
    exerciseStatus = exerciseStatus.toExerciseUiStatus(), latestAnswer = latestAnswer?.toUi(),
    attemptCount = attemptCount, startedAt = startedAt, currentPosition = currentPosition,
    totalExercises = totalExercises, hasNext = hasNext, planCompleted = planCompleted
)

/** A new plan position must never retain result, timer or audio state from its predecessor. */
internal fun ExerciseUiState.resetForExerciseChange(
    newSessionExerciseId: String?,
    newExercise: ExerciseUiModel?,
    newStatus: ExerciseUiStatus,
    newPosition: Int,
    newTotal: Int,
    newHasNext: Boolean,
    newPlanCompleted: Boolean
) = ExerciseUiState(
    sessionId = sessionId,
    childName = childName,
    connectionState = connectionState,
    exercise = newExercise,
    sessionExerciseId = newSessionExerciseId,
    exerciseStatus = newStatus,
    currentPosition = newPosition,
    totalExercises = newTotal,
    hasNext = newHasNext,
    planCompleted = newPlanCompleted
)

internal fun ExerciseUiState.canMoveToNext() =
    exerciseStatus == ExerciseUiStatus.COMPLETED && currentPosition < totalExercises && !isCommandLoading

internal fun ExerciseUiState.canOpenSummary() =
    exerciseStatus == ExerciseUiStatus.COMPLETED && currentPosition == totalExercises && !isCommandLoading

private fun String?.toAbsoluteMediaUrl(): String? = this?.let { url ->
    if (url.startsWith("http://") || url.startsWith("https://")) url else "${BuildConfig.API_BASE_URL.trimEnd('/')}$url"
}
