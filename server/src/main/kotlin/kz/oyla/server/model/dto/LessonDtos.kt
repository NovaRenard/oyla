package kz.oyla.server.model.dto

import kotlinx.serialization.Serializable
import kz.oyla.server.model.ExerciseStatus
import kz.oyla.server.model.SessionStatus

@Serializable data class CreateDeviceLessonRequest(
    val specialistId: String,
    val childId: String,
    val childDeviceId: String,
    /** Keeps previously released specialist tablets compatible while they update. */
    val lessonTemplateId: String = "00000000-0000-0000-0000-000000000201"
)

/** This response is device-only: its specialist session token is never exposed through web history APIs. */
@Serializable data class DeviceLessonResponse(
    val sessionId: String,
    val specialistId: String,
    val specialistName: String,
    val childId: String,
    val childName: String,
    val childDeviceId: String,
    val status: SessionStatus,
    val sessionToken: String,
    val startedAt: String,
    val templateName: String? = null,
    val exerciseCount: Int = 0
)

@Serializable data class ChildLessonAssignmentResponse(
    val sessionId: String,
    val childId: String,
    val childName: String,
    val sessionToken: String,
    val status: SessionStatus,
    val assignedAt: String
)

@Serializable data class LessonDto(
    val id: String,
    val childId: String,
    val childName: String,
    val specialistId: String,
    val specialistName: String,
    val status: SessionStatus,
    val startedAt: String,
    val completedAt: String? = null,
    val durationMs: Long? = null,
    val exerciseCount: Int = 0,
    val templateName: String? = null
)

@Serializable data class LessonExerciseDto(
    val position: Int,
    val exerciseId: String,
    val instructionText: String,
    val status: ExerciseStatus,
    val attemptCount: Int,
    val incorrectAttempts: Int,
    val timeToCorrectMs: Long? = null
)

@Serializable data class LessonDetailsDto(
    val lesson: LessonDto,
    val specialistDeviceId: String,
    val specialistDeviceName: String,
    val childDeviceId: String,
    val childDeviceName: String,
    val exercises: List<LessonExerciseDto>
)
