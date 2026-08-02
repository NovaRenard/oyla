package kz.oyla.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateSessionRequest(val childName: String, val deviceId: String)

@Serializable
data class CreateSessionResponse(
    val sessionId: String,
    val connectionCode: String,
    val status: String,
    val specialistToken: String,
    val expiresAt: String
)

@Serializable
data class ConnectSessionRequest(val connectionCode: String, val deviceId: String)

@Serializable
data class ConnectSessionResponse(
    val sessionId: String,
    val childName: String,
    val status: String,
    val childToken: String
)

@Serializable
data class SessionStateResponse(
    val sessionId: String,
    val connectionCode: String,
    val childName: String,
    val status: String,
    val childConnected: Boolean,
    val expiresAt: String
)

@Serializable
data class ErrorResponse(val code: String, val message: String)

@Serializable
data class ExerciseOptionDto(
    val id: String,
    val label: String,
    val imageAssetKey: String,
    val position: Int
)

@Serializable
data class ExerciseDto(
    val id: String,
    val instructionText: String,
    val audioAssetKey: String? = null,
    val options: List<ExerciseOptionDto>
)

@Serializable
data class SpecialistExerciseDto(val exercise: ExerciseDto, val correctOptionId: String)

@Serializable
data class ShowExerciseRequest(val exerciseId: String)

@Serializable
data class ShowExerciseResponse(val sessionExerciseId: String, val exerciseId: String, val status: String)

@Serializable
data class StartExerciseRequest(val sessionExerciseId: String)

@Serializable
data class StartExerciseResponse(val sessionExerciseId: String, val status: String, val startedAt: String)

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
    val exerciseStatus: String
)

@Serializable
data class ExerciseStateResponse(
    val sessionExerciseId: String? = null,
    val exerciseStatus: String = "PENDING",
    val exercise: ExerciseDto? = null,
    val correctOptionId: String? = null,
    val latestAnswer: AnswerExerciseResponse? = null,
    val attemptCount: Int = 0,
    val startedAt: String? = null
)

@Serializable
data class SessionWebSocketEvent(
    val type: String,
    val sessionId: String,
    val status: String? = null,
    val childConnected: Boolean? = null,
    val sessionExerciseId: String? = null,
    val exerciseStatus: String? = null,
    val exercise: ExerciseDto? = null,
    val correctOptionId: String? = null,
    val selectedOptionId: String? = null,
    val selectedOptionLabel: String? = null,
    val isCorrect: Boolean? = null,
    val attemptNumber: Int? = null,
    val responseTimeMs: Long? = null,
    val latestAnswer: AnswerExerciseResponse? = null,
    val attemptCount: Int? = null,
    val startedAt: String? = null
)
