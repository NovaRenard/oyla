package kz.oyla.app.data.session

import kz.oyla.app.data.local.ActiveSession
import kz.oyla.app.data.local.SessionStorage
import kz.oyla.app.data.remote.NetworkResult
import kz.oyla.app.data.remote.OylaApi
import kz.oyla.app.data.remote.dto.ConnectSessionRequest
import kz.oyla.app.data.remote.dto.ConnectSessionResponse
import kz.oyla.app.data.remote.dto.CreateSessionRequest
import kz.oyla.app.data.remote.dto.CreateSessionResponse
import kz.oyla.app.data.remote.dto.SessionStateResponse
import kz.oyla.app.domain.model.DeviceRole

data class SessionDetails(
    val sessionId: String,
    val childName: String,
    val connectionCode: String?,
    val status: String,
    val childConnected: Boolean,
    val token: String,
    val role: DeviceRole
)

sealed interface SessionActionResult<out T> {
    data class Success<T>(val value: T) : SessionActionResult<T>
    data class Failure(val error: SessionUserError) : SessionActionResult<Nothing>
}

enum class SessionUserError(val message: String) {
    NETWORK("Не удалось подключиться к серверу"),
    INVALID_CODE("Код не найден"),
    EXPIRED("Срок действия кода истёк"),
    ALREADY_CONNECTED("К этому занятию уже подключено другое устройство"),
    INVALID_DATA("Проверьте введённые данные"),
    UNKNOWN("Не удалось выполнить действие. Попробуйте ещё раз")
}

class SessionRepository(
    private val api: OylaApi,
    private val storage: SessionStorage
) {
    suspend fun createSession(childName: String): SessionActionResult<SessionDetails> {
        val result = api.createSession(
            CreateSessionRequest(childName.trim(), storage.getOrCreateDeviceId())
        )
        return when (result) {
            is NetworkResult.Success -> result.data.toSpecialistDetails().also { storage.saveActiveSession(it.toActiveSession()) }
                .let { SessionActionResult.Success(it) }
            else -> SessionActionResult.Failure(result.toUserError())
        }
    }

    suspend fun connectSession(connectionCode: String): SessionActionResult<SessionDetails> {
        val result = api.connectSession(
            ConnectSessionRequest(connectionCode, storage.getOrCreateDeviceId())
        )
        return when (result) {
            is NetworkResult.Success -> result.data.toChildDetails(connectionCode).also { storage.saveActiveSession(it.toActiveSession()) }
                .let { SessionActionResult.Success(it) }
            else -> SessionActionResult.Failure(result.toUserError())
        }
    }

    suspend fun restoreActiveSession(role: DeviceRole): SessionActionResult<SessionDetails>? {
        val active = storage.getActiveSession()?.takeIf { it.role == role } ?: return null
        return when (val result = api.getSessionState(active.sessionId, active.sessionToken)) {
            is NetworkResult.Success -> SessionActionResult.Success(result.data.toDetails(active))
            is NetworkResult.HttpError -> {
                if (result.statusCode in setOf(401, 404, 410)) storage.clearActiveSession()
                SessionActionResult.Failure(result.toUserError())
            }
            NetworkResult.NetworkError -> SessionActionResult.Failure(SessionUserError.NETWORK)
        }
    }

    suspend fun cancelActiveSession(): SessionActionResult<Unit> {
        val active = storage.getActiveSession() ?: return SessionActionResult.Success(Unit)
        return when (val result = api.cancelSession(active.sessionId, active.sessionToken)) {
            is NetworkResult.Success -> {
                storage.clearActiveSession()
                SessionActionResult.Success(Unit)
            }
            else -> SessionActionResult.Failure(result.toUserError())
        }
    }

    private fun CreateSessionResponse.toSpecialistDetails() = SessionDetails(
        sessionId = sessionId,
        childName = "",
        connectionCode = connectionCode,
        status = status,
        childConnected = false,
        token = specialistToken,
        role = DeviceRole.SPECIALIST
    )

    private fun ConnectSessionResponse.toChildDetails(code: String) = SessionDetails(
        sessionId = sessionId,
        childName = childName,
        connectionCode = code,
        status = status,
        childConnected = true,
        token = childToken,
        role = DeviceRole.CHILD
    )

    private fun SessionStateResponse.toDetails(active: ActiveSession) = SessionDetails(
        sessionId = sessionId,
        childName = childName,
        connectionCode = if (active.role == DeviceRole.SPECIALIST) connectionCode else active.connectionCode,
        status = status,
        childConnected = childConnected,
        token = active.sessionToken,
        role = active.role
    )

    private fun SessionDetails.toActiveSession() = ActiveSession(
        sessionId = sessionId,
        sessionToken = token,
        role = role,
        connectionCode = if (role == DeviceRole.SPECIALIST) connectionCode else null
    )

    private fun NetworkResult<*>.toUserError(): SessionUserError = when (this) {
        NetworkResult.NetworkError -> SessionUserError.NETWORK
        is NetworkResult.HttpError -> when (statusCode) {
            400 -> SessionUserError.INVALID_DATA
            404 -> SessionUserError.INVALID_CODE
            409 -> SessionUserError.ALREADY_CONNECTED
            410 -> SessionUserError.EXPIRED
            else -> SessionUserError.UNKNOWN
        }
        is NetworkResult.Success -> SessionUserError.UNKNOWN
    }
}
