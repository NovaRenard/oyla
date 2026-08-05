package kz.oyla.app.data.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kz.oyla.app.data.local.DeviceIdentity
import kz.oyla.app.data.local.DeviceIdentityStorage
import kz.oyla.app.data.remote.DeviceAuthGateway
import kz.oyla.app.data.remote.NetworkResult
import kz.oyla.app.data.remote.dto.ActivateDeviceRequest
import kz.oyla.app.data.remote.dto.ActivateDeviceResponse
import kz.oyla.app.data.remote.dto.DeviceAuthMeResponse
import kz.oyla.app.data.remote.dto.DeviceHeartbeatRequest
import kz.oyla.app.domain.model.DeviceRole

data class DeviceRuntimeInfo(val appVersion: String, val androidVersion: String, val model: String)

sealed interface DeviceStartupResult {
    data object ActivationRequired : DeviceStartupResult
    data class Ready(val identity: DeviceIdentity) : DeviceStartupResult
    data class Blocked(val identity: DeviceIdentity) : DeviceStartupResult
    data class Offline(val identity: DeviceIdentity) : DeviceStartupResult
}

sealed interface DeviceActivationResult {
    data class Success(val identity: DeviceIdentity) : DeviceActivationResult
    data class Failure(val message: String) : DeviceActivationResult
}

/** Pure lifecycle rules: a network failure never removes a valid local token. */
class DeviceLifecycle(
    private val storage: DeviceIdentityStorage,
    private val gateway: DeviceAuthGateway,
    private val runtimeInfo: DeviceRuntimeInfo
) {
    suspend fun validateStartup(): DeviceStartupResult {
        val identity = storage.getDeviceIdentity() ?: return DeviceStartupResult.ActivationRequired
        return when (val result = gateway.me(identity.deviceToken)) {
            is NetworkResult.Success -> result.data.toStartupResult(identity)
            NetworkResult.NetworkError -> DeviceStartupResult.Offline(identity)
            is NetworkResult.HttpError -> when {
                result.errorCode == "DEVICE_BLOCKED" -> DeviceStartupResult.Blocked(identity)
                result.errorCode == "DEVICE_UNLINKED" || result.statusCode == 401 -> {
                    storage.clearDeviceIdentity()
                    DeviceStartupResult.ActivationRequired
                }
                else -> DeviceStartupResult.Offline(identity)
            }
        }
    }

    suspend fun activate(rawCode: String): DeviceActivationResult {
        val code = normalizeActivationCode(rawCode)
        if (code.length != ActivationCodeLength) return DeviceActivationResult.Failure("Введите код подключения полностью")
        val uid = storage.getOrCreateDeviceUid()
        val request = ActivateDeviceRequest(code, uid, runtimeInfo.appVersion, runtimeInfo.androidVersion, runtimeInfo.model)
        return when (val result = gateway.activate(request)) {
            is NetworkResult.Success -> {
                val identity = result.data.toIdentity(uid)
                storage.saveDeviceIdentity(identity)
                DeviceActivationResult.Success(identity)
            }
            NetworkResult.NetworkError -> DeviceActivationResult.Failure("Нет соединения. Проверьте интернет и попробуйте снова")
            is NetworkResult.HttpError -> DeviceActivationResult.Failure(result.toActivationMessage())
        }
    }

    suspend fun updateFromServer(identity: DeviceIdentity, response: DeviceAuthMeResponse): DeviceStartupResult = response.toStartupResult(identity)

    private suspend fun DeviceAuthMeResponse.toStartupResult(identity: DeviceIdentity): DeviceStartupResult {
        val updated = identity.copy(
            deviceId = deviceId,
            centerId = centerId,
            centerName = centerName,
            deviceName = deviceName,
            deviceRole = role
        )
        return when (status) {
            "BLOCKED" -> DeviceStartupResult.Blocked(updated)
            "UNLINKED" -> { storage.clearDeviceIdentity(); DeviceStartupResult.ActivationRequired }
            else -> { storage.saveDeviceIdentity(updated); DeviceStartupResult.Ready(updated) }
        }
    }

    private fun ActivateDeviceResponse.toIdentity(deviceUid: String) = DeviceIdentity(
        deviceId = deviceId, centerId = centerId, centerName = centerName, deviceName = deviceName,
        deviceRole = deviceRole, deviceToken = deviceToken, deviceUid = deviceUid
    )

    private fun NetworkResult.HttpError.toActivationMessage(): String = when {
        errorCode == "INVALID_ACTIVATION_CODE" -> "Код неверный или срок его действия закончился"
        errorCode == "RATE_LIMITED" || statusCode == 429 -> "Слишком много попыток. Подождите немного и повторите"
        errorCode == "CONFLICT" || statusCode == 409 -> "Это устройство уже подключено. Обратитесь к администратору"
        statusCode >= 500 -> "Сервер временно недоступен. Попробуйте позже"
        else -> "Не удалось подключить планшет. Проверьте код и попробуйте снова"
    }

    companion object {
        const val ActivationCodeLength = 8
    }
}

fun normalizeActivationCode(value: String): String = value.filter(Char::isLetterOrDigit).uppercase()

/** A single cooperative heartbeat loop owned by the ViewModel scope. */
class HeartbeatController(
    private val scope: CoroutineScope,
    private val storage: DeviceIdentityStorage,
    private val gateway: DeviceAuthGateway,
    private val runtimeInfo: DeviceRuntimeInfo,
    private val onResult: suspend (DeviceStartupResult) -> Unit
) {
    private var heartbeatJob: Job? = null

    fun start(identity: DeviceIdentity) {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            var nextDelay = HeartbeatIntervalMillis
            while (isActive) {
                when (val result = gateway.heartbeat(identity.deviceToken, DeviceHeartbeatRequest(runtimeInfo.appVersion, runtimeInfo.androidVersion, runtimeInfo.model))) {
                    is NetworkResult.Success -> {
                        nextDelay = HeartbeatIntervalMillis
                        val updated = identity.copy(
                            deviceId = result.data.deviceId, centerId = result.data.centerId,
                            centerName = result.data.centerName, deviceName = result.data.deviceName,
                            deviceRole = result.data.role
                        )
                        when (result.data.status) {
                            "BLOCKED" -> { onResult(DeviceStartupResult.Blocked(updated)); break }
                            "UNLINKED" -> { storage.clearDeviceIdentity(); onResult(DeviceStartupResult.ActivationRequired); break }
                            else -> storage.saveDeviceIdentity(updated)
                        }
                    }
                    NetworkResult.NetworkError -> nextDelay = (nextDelay * 2).coerceAtMost(MaxBackoffMillis)
                    is NetworkResult.HttpError -> when {
                        result.errorCode == "DEVICE_BLOCKED" -> { onResult(DeviceStartupResult.Blocked(identity)); break }
                        result.errorCode == "DEVICE_UNLINKED" || result.statusCode == 401 -> {
                            storage.clearDeviceIdentity(); onResult(DeviceStartupResult.ActivationRequired); break
                        }
                        else -> nextDelay = (nextDelay * 2).coerceAtMost(MaxBackoffMillis)
                    }
                }
                delay(nextDelay)
            }
        }
    }

    fun stop() { heartbeatJob?.cancel(); heartbeatJob = null }
    internal fun isRunningForTest() = heartbeatJob?.isActive == true

    private companion object {
        const val HeartbeatIntervalMillis = 30_000L
        const val MaxBackoffMillis = 5 * 60_000L
    }
}
