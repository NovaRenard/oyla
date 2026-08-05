package kz.oyla.app.data.local

import kz.oyla.app.domain.model.DeviceRole

enum class DeviceActivationState { ACTIVATED }

/**
 * Server-issued tablet identity. The token is never rendered or logged.
 */
data class DeviceIdentity(
    val deviceId: String,
    val centerId: String,
    val centerName: String,
    val deviceName: String,
    val deviceRole: DeviceRole,
    val deviceToken: String,
    val deviceUid: String,
    val activationState: DeviceActivationState = DeviceActivationState.ACTIVATED
) {
    override fun toString(): String = "DeviceIdentity(deviceId=$deviceId, centerId=$centerId, centerName=$centerName, deviceName=$deviceName, deviceRole=$deviceRole, deviceToken=***, deviceUid=$deviceUid, activationState=$activationState)"
}

interface DeviceIdentityStorage {
    suspend fun getOrCreateDeviceUid(): String
    suspend fun getDeviceIdentity(): DeviceIdentity?
    suspend fun saveDeviceIdentity(identity: DeviceIdentity)
    suspend fun clearDeviceIdentity()
}
