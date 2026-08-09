package kz.oyla.app.data.remote.dto

import kotlinx.serialization.Serializable
import kz.oyla.app.domain.model.DeviceRole

@Serializable data class DeviceCatalogChild(
    val id: String,
    val firstName: String,
    val lastName: String? = null,
    val birthDate: String? = null,
    val status: String
)

@Serializable data class DeviceCatalogSpecialist(
    val id: String,
    val firstName: String,
    val lastName: String? = null,
    val specialization: String? = null,
    val status: String
)

@Serializable data class AvailableChildDevice(
    val id: String,
    val name: String,
    val role: DeviceRole,
    val status: String,
    val isOnline: Boolean
)

@Serializable data class DeviceLessonTemplate(
    val id: String,
    val name: String,
    val description: String? = null,
    val ownership: String,
    val exerciseCount: Int
)

@Serializable data class CreateDeviceLessonRequest(
    val specialistId: String,
    val childId: String,
    val childDeviceId: String,
    val lessonTemplateId: String = "00000000-0000-0000-0000-000000000201"
)

@Serializable data class DeviceLessonResponse(
    val sessionId: String,
    val specialistId: String,
    val specialistName: String,
    val childId: String,
    val childName: String,
    val childDeviceId: String,
    val status: String,
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
    val status: String,
    val assignedAt: String
)
