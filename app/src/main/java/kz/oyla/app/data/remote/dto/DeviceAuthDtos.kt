package kz.oyla.app.data.remote.dto

import kotlinx.serialization.Serializable
import kz.oyla.app.domain.model.DeviceRole

@Serializable data class ActivateDeviceRequest(
    val activationCode: String,
    val deviceUid: String,
    val appVersion: String? = null,
    val androidVersion: String? = null,
    val model: String? = null
)

@Serializable data class ActivateDeviceResponse(
    val deviceId: String,
    val centerId: String,
    val centerName: String,
    val deviceName: String,
    val deviceRole: DeviceRole,
    val deviceToken: String,
    val serverTime: String
)

@Serializable data class DeviceAuthMeResponse(
    val deviceId: String,
    val centerId: String,
    val centerName: String,
    val deviceName: String,
    val role: DeviceRole,
    val status: String,
    val serverTime: String
)

@Serializable data class DeviceHeartbeatRequest(
    val appVersion: String? = null,
    val androidVersion: String? = null,
    val model: String? = null
)

@Serializable data class SaasApiErrorBody(val code: String, val message: String)
@Serializable data class SaasErrorResponse(val error: SaasApiErrorBody)
