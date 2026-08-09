package kz.oyla.server.model.dto

import kotlinx.serialization.Serializable
import kz.oyla.server.model.ExerciseStatus
import kz.oyla.server.model.SessionStatus

@Serializable
data class HealthResponse(val status: String = "ok")

@Serializable
data class CreateSessionRequest(
    val childName: String,
    val deviceId: String
)

@Serializable
data class CreateSessionResponse(
    val sessionId: String,
    val connectionCode: String,
    val status: SessionStatus,
    val specialistToken: String,
    val expiresAt: String
)

@Serializable
data class ConnectSessionRequest(
    val connectionCode: String,
    val deviceId: String
)

@Serializable
data class ConnectSessionResponse(
    val sessionId: String,
    val childName: String,
    val status: SessionStatus,
    val childToken: String
)

@Serializable
data class SessionStateResponse(
    val sessionId: String,
    val connectionCode: String,
    val childName: String,
    val status: SessionStatus,
    val childConnected: Boolean,
    val expiresAt: String
)

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String
)

@Serializable
data class StateSnapshotEvent(
    val type: String = "STATE_SNAPSHOT",
    val sessionId: String,
    val status: SessionStatus,
    val childConnected: Boolean,
    val exerciseStatus: ExerciseStatus = ExerciseStatus.PENDING,
    val sessionExerciseId: String? = null,
    val exercise: ExerciseDto? = null,
    val correctOptionId: String? = null,
    val latestAnswer: AnswerExerciseResponse? = null,
    val attemptCount: Int = 0,
    val startedAt: String? = null,
    val currentPosition: Int = 1,
    val totalExercises: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = true,
    val planCompleted: Boolean = false,
    val whiteboardState: WhiteboardStateSnapshotDto? = null
)

@Serializable
data class ChildConnectedEvent(
    val type: String = "CHILD_CONNECTED",
    val sessionId: String,
    val status: SessionStatus,
    val childConnected: Boolean = true
)

@Serializable
data class SessionCancelledEvent(
    val type: String = "SESSION_CANCELLED",
    val sessionId: String
)

@Serializable
data class SessionCompletedEvent(
    val type: String = "SESSION_COMPLETED",
    val sessionId: String
)

@Serializable
data class ExerciseShownEvent(
    val type: String = "EXERCISE_SHOWN",
    val sessionId: String,
    val sessionExerciseId: String,
    val exercise: ExerciseDto,
    val exerciseStatus: ExerciseStatus,
    val correctOptionId: String? = null,
    val currentPosition: Int = 1,
    val totalExercises: Int = 0
)

@Serializable
data class ExerciseStartedEvent(
    val type: String = "EXERCISE_STARTED",
    val sessionId: String,
    val sessionExerciseId: String,
    val exerciseStatus: ExerciseStatus,
    val startedAt: String,
    val currentPosition: Int = 1,
    val totalExercises: Int = 0,
    val whiteboardState: WhiteboardStateSnapshotDto? = null
)

@Serializable
data class AnswerReceivedEvent(
    val type: String = "ANSWER_RECEIVED",
    val sessionId: String,
    val sessionExerciseId: String,
    val selectedOptionId: String,
    val selectedOptionLabel: String,
    val isCorrect: Boolean,
    val attemptNumber: Int,
    val responseTimeMs: Long,
    val exerciseStatus: ExerciseStatus
)

@Serializable
data class ExerciseCompletedEvent(
    val type: String = "EXERCISE_COMPLETED",
    val sessionId: String,
    val sessionExerciseId: String,
    val selectedOptionId: String,
    val attemptNumber: Int,
    val responseTimeMs: Long,
    val exerciseStatus: ExerciseStatus
)

@Serializable
data class ExerciseChangedEvent(
    val type: String = "EXERCISE_CHANGED",
    val sessionId: String,
    val sessionExerciseId: String,
    val exerciseStatus: ExerciseStatus,
    val exercise: ExerciseDto? = null,
    val correctOptionId: String? = null,
    val currentPosition: Int,
    val totalExercises: Int,
    val attemptCount: Int = 0,
    val latestAnswer: AnswerExerciseResponse? = null,
    val startedAt: String? = null,
    val planCompleted: Boolean = false,
    val whiteboardState: WhiteboardStateSnapshotDto? = null
)

@Serializable
data class ExercisePlanCompletedEvent(
    val type: String = "EXERCISE_PLAN_COMPLETED",
    val sessionId: String,
    val currentPosition: Int,
    val totalExercises: Int,
    val planCompleted: Boolean = true
)

@Serializable data class WhiteboardStrokeStartedEvent(
    val type: String = "WHITEBOARD_STROKE_STARTED",
    val sessionId: String,
    val sessionExerciseId: String,
    val strokeId: String,
    val actorRole: String,
    val tool: String,
    val color: String? = null,
    val brushSize: String
)
@Serializable data class WhiteboardStrokePointsEvent(
    val type: String = "WHITEBOARD_STROKE_POINTS",
    val sessionId: String,
    val sessionExerciseId: String,
    val strokeId: String,
    val points: List<WhiteboardPointDto>
)
@Serializable data class WhiteboardStrokeCompletedEvent(
    val type: String = "WHITEBOARD_STROKE_COMPLETED",
    val sessionId: String,
    val sessionExerciseId: String,
    val stroke: WhiteboardStrokeDto
)
@Serializable data class WhiteboardClearEvent(
    val type: String = "WHITEBOARD_CLEARED",
    val sessionId: String,
    val sessionExerciseId: String,
    val clearRevision: Int,
    val boardRevision: Int
)

/** One envelope keeps the existing session WebSocket backwards compatible for old clients. */
@Serializable data class WhiteboardClientEvent(
    val type: String,
    val sessionExerciseId: String? = null,
    val strokeId: String? = null,
    val tool: kz.oyla.server.model.WhiteboardTool? = null,
    val color: kz.oyla.server.model.WhiteboardColor? = null,
    val brushSize: kz.oyla.server.model.WhiteboardBrushSize? = null,
    val points: List<WhiteboardPointDto> = emptyList(),
    val clientEventId: String? = null,
    val childDrawingEnabled: Boolean? = null
)
@Serializable data class WhiteboardUndoEvent(
    val type: String = "WHITEBOARD_UNDONE",
    val sessionId: String,
    val sessionExerciseId: String,
    val strokeId: String,
    val boardRevision: Int
)
@Serializable data class WhiteboardChildPermissionChangedEvent(
    val type: String = "WHITEBOARD_CHILD_PERMISSION_CHANGED",
    val sessionId: String,
    val sessionExerciseId: String,
    val childDrawingEnabled: Boolean,
    val boardRevision: Int
)
