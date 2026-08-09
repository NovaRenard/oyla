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

@Serializable data class CreateDeviceLessonRequest(
    val specialistId: String,
    val childId: String,
    val childDeviceId: String
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
    val startedAt: String
)

@Serializable data class ChildLessonAssignmentResponse(
    val sessionId: String,
    val childId: String,
    val childName: String,
    val sessionToken: String,
    val status: String,
    val assignedAt: String
)
