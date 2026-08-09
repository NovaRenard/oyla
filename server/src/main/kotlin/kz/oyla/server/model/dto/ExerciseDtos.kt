package kz.oyla.server.model.dto

import kotlinx.serialization.Serializable
import kz.oyla.server.model.ExerciseStatus
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.WhiteboardColor
import kz.oyla.server.model.WhiteboardTool
import kz.oyla.server.model.WhiteboardBrushSize

@Serializable
data class ExerciseOptionDto(
    val id: String,
    val label: String,
    val imageAssetKey: String,
    val position: Int,
    val imageUrl: String? = null
)

@Serializable
data class ExerciseDto(
    val id: String,
    val instructionText: String,
    val audioAssetKey: String? = null,
    val options: List<ExerciseOptionDto> = emptyList(),
    val title: String? = null,
    val activityType: String = "SINGLE_CHOICE",
    val audioUrl: String? = null,
    val whiteboardConfig: WhiteboardExerciseConfigDto? = null
)

@Serializable data class WhiteboardPointDto(val x: Float, val y: Float)

@Serializable data class WhiteboardStrokeDto(
    val id: String,
    val sessionExerciseId: String,
    val actorRole: String,
    val actorDeviceId: String,
    val sequenceNumber: Int,
    val tool: WhiteboardTool,
    val color: WhiteboardColor? = null,
    val brushSize: WhiteboardBrushSize,
    val points: List<WhiteboardPointDto>,
    val createdAt: String
)

@Serializable data class WhiteboardStateSnapshotDto(
    val sessionExerciseId: String,
    val childDrawingEnabled: Boolean,
    val boardRevision: Int,
    val clearRevision: Int,
    val strokes: List<WhiteboardStrokeDto>
)

@Serializable data class CompleteWhiteboardExerciseRequest(val sessionExerciseId: String)

@Serializable
data class SpecialistExerciseDto(val exercise: ExerciseDto, val correctOptionId: String? = null)

@Serializable
data class ShowExerciseRequest(val exerciseId: String)

@Serializable
data class ShowExerciseResponse(
    val sessionExerciseId: String,
    val exerciseId: String,
    val status: ExerciseStatus
)

@Serializable
data class StartExerciseRequest(val sessionExerciseId: String)

@Serializable
data class StartExerciseResponse(
    val sessionExerciseId: String,
    val status: ExerciseStatus,
    val startedAt: String
)

@Serializable
data class AnswerExerciseRequest(
    val sessionExerciseId: String,
    val selectedOptionId: String,
    val clientEventId: String
)

@Serializable
data class AnswerExerciseResponse(
    val sessionExerciseId: String,
    val selectedOptionId: String,
    val selectedOptionLabel: String,
    val isCorrect: Boolean,
    val attemptNumber: Int,
    val responseTimeMs: Long,
    val exerciseStatus: ExerciseStatus
)

@Serializable
data class NextExerciseRequest(val currentSessionExerciseId: String)

@Serializable
data class ExerciseStateResponse(
    val sessionExerciseId: String? = null,
    val exerciseStatus: ExerciseStatus = ExerciseStatus.PENDING,
    val exercise: ExerciseDto? = null,
    val correctOptionId: String? = null,
    val latestAnswer: AnswerExerciseResponse? = null,
    val attemptCount: Int = 0,
    val startedAt: String? = null,
    val currentPosition: Int = 1,
    val totalExercises: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = true,
    val planCompleted: Boolean = false
    ,val whiteboardState: WhiteboardStateSnapshotDto? = null
)

@Serializable
data class ExerciseSummaryItemDto(
    val position: Int,
    val exerciseId: String,
    val activityType: ActivityType = ActivityType.SINGLE_CHOICE,
    val instructionText: String,
    val correctOptionLabel: String = "",
    val attemptCount: Int,
    val incorrectAttempts: Int,
    val firstAttemptCorrect: Boolean,
    val timeToCorrectMs: Long = 0,
    val startedAt: String,
    val completedAt: String,
    val durationMs: Long? = null,
    val strokeCount: Int? = null,
    val childStrokeCount: Int? = null,
    val specialistStrokeCount: Int? = null
)

@Serializable
data class SessionSummaryResponse(
    val sessionId: String,
    val childName: String,
    val completedExercises: Int,
    val totalExercises: Int,
    val totalAttempts: Int,
    val incorrectAttempts: Int,
    val firstAttemptCorrectCount: Int,
    val firstAttemptCorrectPercent: Int,
    val activeDurationMs: Long,
    val startedAt: String,
    val completedAt: String,
    val exercises: List<ExerciseSummaryItemDto>
)
