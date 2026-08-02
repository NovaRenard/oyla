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
            ChildConnectionResult.ConnectedToAnotherDevice -> throw ApiException.conflict(
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
        if (authorized.role != DeviceRole.SPECIALIST) throw ApiException.forbidden()
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
        fun unauthorized() = ApiException(401, "UNAUTHORIZED", "Необходима авторизация устройства")
        fun forbidden() = ApiException(403, "FORBIDDEN", "Недостаточно прав для этого действия")
        fun notFound(message: String) = ApiException(404, "NOT_FOUND", message)
        fun conflict(message: String) = ApiException(409, "ALREADY_CONNECTED", message)
        fun gone(message: String) = ApiException(410, "SESSION_EXPIRED", message)
    }
}
