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
    val totalExercises: Int = 5,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = true,
    val planCompleted: Boolean = false
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
    val totalExercises: Int = 5
)

@Serializable
data class ExerciseStartedEvent(
    val type: String = "EXERCISE_STARTED",
    val sessionId: String,
    val sessionExerciseId: String,
    val exerciseStatus: ExerciseStatus,
    val startedAt: String,
    val currentPosition: Int = 1,
    val totalExercises: Int = 5
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
    val planCompleted: Boolean = false
)

@Serializable
data class ExercisePlanCompletedEvent(
    val type: String = "EXERCISE_PLAN_COMPLETED",
    val sessionId: String,
    val currentPosition: Int,
    val totalExercises: Int,
    val planCompleted: Boolean = true
)
