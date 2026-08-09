package kz.oyla.server.service

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.SessionStatus
import kz.oyla.server.model.dto.ConnectSessionRequest
import kz.oyla.server.model.dto.ConnectSessionResponse
import kz.oyla.server.model.dto.CreateSessionRequest
import kz.oyla.server.model.dto.CreateSessionResponse
import kz.oyla.server.model.dto.SessionStateResponse
import kz.oyla.server.repository.ChildConnectionResult
import kz.oyla.server.repository.SessionRecord
import kz.oyla.server.repository.SessionRepository
import kz.oyla.server.util.CodeGenerator

class SessionService(
    private val repository: SessionRepository,
    private val codeGenerator: CodeGenerator = CodeGenerator(),
    private val clock: Clock = Clock.systemUTC(),
    private val codeTtl: Duration = Duration.ofMinutes(
        (System.getenv("SESSION_CODE_TTL_MINUTES") ?: "30").toLongOrNull()?.coerceAtLeast(1) ?: 30
    )
) {
    suspend fun create(request: CreateSessionRequest): CreateSessionResponse {
        val childName = request.childName.trim()
        if (childName.isEmpty() || childName.length > 80 || request.deviceId.isBlank()) {
            throw ApiException.badRequest("Некорректные данные занятия")
        }
        val now = clock.instant()
        repeat(50) {
            val session = SessionRecord(
                id = UUID.randomUUID(),
                connectionCode = codeGenerator.nextConnectionCode(),
                childName = childName,
                status = SessionStatus.WAITING_FOR_CHILD,
                specialistDeviceId = request.deviceId.trim(),
                specialistToken = codeGenerator.nextAccessToken(),
                childDeviceId = null,
                childToken = null,
                createdAt = now,
                expiresAt = now.plus(codeTtl),
                connectedAt = null,
                completedAt = null
            )
            if (repository.createSession(session)) return session.toCreateResponse()
        }
        throw IllegalStateException("No unused session connection code available")
    }

    suspend fun connect(request: ConnectSessionRequest): ConnectSessionResponse {
        val code = request.connectionCode.trim()
        if (!code.matches(Regex("\\d{4}")) || request.deviceId.isBlank()) {
            throw ApiException.badRequest("Некорректный код подключения")
        }
        return when (val result = repository.connectChild(
            code = code,
            childDeviceId = request.deviceId.trim(),
            childToken = codeGenerator.nextAccessToken(),
            now = clock.instant()
        )) {
            is ChildConnectionResult.Connected -> result.session.toConnectResponse()
            ChildConnectionResult.NotFound -> throw ApiException.notFound("Код не найден")
            ChildConnectionResult.Expired -> throw ApiException.gone("Срок действия кода истёк")
            ChildConnectionResult.ConnectedToAnotherDevice -> throw ApiException.alreadyConnected(
                "К этому занятию уже подключено другое устройство"
            )
        }
    }

    suspend fun getState(sessionId: String, token: String?): SessionStateResponse {
        val session = loadAndAuthorize(sessionId, token)
        return session.toStateResponse()
    }

    suspend fun authorize(sessionId: String, token: String?): AuthorizedSession {
        val session = loadAndAuthorize(sessionId, token)
        val role = when (token) {
            session.specialistToken -> DeviceRole.SPECIALIST
            session.childToken -> DeviceRole.CHILD
            else -> throw ApiException.unauthorized()
        }
        return AuthorizedSession(session, role)
    }

    suspend fun cancel(sessionId: String, token: String?): SessionRecord {
        val authorized = authorize(sessionId, token)
        return repository.cancel(authorized.session.id, clock.instant())
            ?: throw ApiException.notFound("Занятие не найдено")
    }

    suspend fun complete(sessionId: String, token: String?): SessionRecord {
        val authorized = authorize(sessionId, token)
        if (authorized.role != DeviceRole.SPECIALIST) throw ApiException.forbidden()
        return repository.complete(authorized.session.id, clock.instant())
            ?: throw ApiException.notFound("Занятие не найдено")
    }

    suspend fun markSocketPresence(authorized: AuthorizedSession, connected: Boolean) {
        repository.updateDeviceConnection(authorized.session, authorized.role, connected, clock.instant())
    }

    private suspend fun loadAndAuthorize(sessionId: String, token: String?): SessionRecord {
        if (token.isNullOrBlank()) throw ApiException.unauthorized()
        val id = runCatching { UUID.fromString(sessionId) }
            .getOrElse { throw ApiException.badRequest("Некорректный идентификатор занятия") }
        val session = repository.expireIfNecessary(id, clock.instant())
            ?: throw ApiException.notFound("Занятие не найдено")
        if (session.specialistToken != token && session.childToken != token) throw ApiException.unauthorized()
        return session
    }

    private fun SessionRecord.toCreateResponse() = CreateSessionResponse(
        sessionId = id.toString(),
        connectionCode = connectionCode,
        status = status,
        specialistToken = specialistToken,
        expiresAt = expiresAt.toString()
    )

    private fun SessionRecord.toConnectResponse() = ConnectSessionResponse(
        sessionId = id.toString(),
        childName = childName,
        status = status,
        childToken = checkNotNull(childToken)
    )

    private fun SessionRecord.toStateResponse() = SessionStateResponse(
        sessionId = id.toString(),
        connectionCode = connectionCode,
        childName = childName,
        status = status,
        childConnected = childDeviceId != null,
        expiresAt = expiresAt.toString()
    )
}

data class AuthorizedSession(val session: SessionRecord, val role: DeviceRole)

class ApiException private constructor(
    val httpCode: Int,
    val errorCode: String,
    val clientMessage: String
) : RuntimeException(clientMessage) {
    companion object {
        fun badRequest(message: String) = ApiException(400, "BAD_REQUEST", message)
        fun validation(message: String) = ApiException(400, "VALIDATION_ERROR", message)
        fun invalidCredentials() = ApiException(401, "INVALID_CREDENTIALS", "Неверный email или пароль")
        fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Необходима авторизация устройства")
        fun forbidden() = ApiException(403, "FORBIDDEN", "Недостаточно прав для этого действия")
        fun notFound(message: String) = ApiException(404, "NOT_FOUND", message)
        fun centerNotFound() = ApiException(404, "CENTER_NOT_FOUND", "Центр не найден")
        fun membershipNotFound() = ApiException(403, "MEMBERSHIP_NOT_FOUND", "Нет активного доступа к центру")
        fun deviceNotFound() = ApiException(404, "DEVICE_NOT_FOUND", "Устройство не найдено")
        fun childNotFound() = ApiException(404, "CHILD_NOT_FOUND", "Ребёнок не найден")
        fun specialistNotFound() = ApiException(404, "SPECIALIST_NOT_FOUND", "Специалист не найден")
        fun deviceBlocked() = ApiException(403, "DEVICE_BLOCKED", "Устройство заблокировано")
        fun deviceUnlinked() = ApiException(403, "DEVICE_UNLINKED", "Устройство отвязано от центра")
        fun deviceAlreadyActivated() = ApiException(409, "DEVICE_ALREADY_ACTIVATED", "Этот планшет уже подключён к центру. Сначала отвяжите его в текущем кабинете")
        fun invalidActivationCode() = ApiException(400, "INVALID_ACTIVATION_CODE", "Недействительный код активации")
        fun activationCodeExpired() = ApiException(410, "ACTIVATION_CODE_EXPIRED", "Срок действия кода активации истёк")
        fun activationCodeUsed() = ApiException(409, "ACTIVATION_CODE_ALREADY_USED", "Код активации уже использован")
        fun rateLimited() = ApiException(429, "RATE_LIMITED", "Слишком много попыток. Повторите позже")
        fun registrationDisabled() = ApiException(404, "REGISTRATION_DISABLED", "Регистрация центра недоступна")
        fun conflict(message: String) = ApiException(409, "CONFLICT", message)
        fun alreadyConnected(message: String) = ApiException(409, "ALREADY_CONNECTED", message)
        fun planCompleted() = ApiException(409, "PLAN_COMPLETED", "Все задания уже выполнены")
        fun planNotCompleted() = ApiException(409, "EXERCISE_PLAN_NOT_COMPLETED", "Не все задания завершены")
        fun gone(message: String) = ApiException(410, "SESSION_EXPIRED", message)
    }
}
