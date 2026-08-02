package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.SessionStatus

/** Deterministic repository used by HTTP tests; production always uses PostgreSQL. */
class InMemorySessionRepository : SessionRepository {
    private val sessions = linkedMapOf<UUID, SessionRecord>()

    override suspend fun isConnectionCodeActive(code: String, now: Instant): Boolean = synchronized(sessions) {
        sessions.values.any {
            it.connectionCode == code &&
                it.status in setOf(SessionStatus.WAITING_FOR_CHILD, SessionStatus.READY) &&
                it.expiresAt > now
        }
    }

    override suspend fun createSession(session: SessionRecord): Boolean = synchronized(sessions) {
        if (sessions.values.any {
                it.connectionCode == session.connectionCode &&
                    it.status in setOf(SessionStatus.WAITING_FOR_CHILD, SessionStatus.READY) &&
                    it.expiresAt > session.createdAt
            }
        ) {
            false
        } else {
            sessions[session.id] = session
            true
        }
    }

    override suspend fun findById(id: UUID): SessionRecord? = synchronized(sessions) { sessions[id] }

    override suspend fun connectChild(
        code: String,
        childDeviceId: String,
        childToken: String,
        now: Instant
    ): ChildConnectionResult = synchronized(sessions) {
        val session = sessions.values.firstOrNull { it.connectionCode == code }
            ?: return@synchronized ChildConnectionResult.NotFound
        if (session.expiresAt <= now || session.status in setOf(
                SessionStatus.CANCELLED,
                SessionStatus.COMPLETED,
                SessionStatus.EXPIRED
            )
        ) {
            if (session.status == SessionStatus.WAITING_FOR_CHILD && session.expiresAt <= now) {
                sessions[session.id] = session.copy(status = SessionStatus.EXPIRED)
            }
            return@synchronized ChildConnectionResult.Expired
        }
        if (session.childDeviceId != null && session.childDeviceId != childDeviceId) {
            return@synchronized ChildConnectionResult.ConnectedToAnotherDevice
        }
        val result = if (session.childDeviceId == null) {
            session.copy(
                childDeviceId = childDeviceId,
                childToken = childToken,
                status = SessionStatus.READY,
                connectedAt = now
            ).also { sessions[it.id] = it }
        } else {
            session
        }
        ChildConnectionResult.Connected(result)
    }

    override suspend fun expireIfNecessary(id: UUID, now: Instant): SessionRecord? = synchronized(sessions) {
        val session = sessions[id] ?: return@synchronized null
        if (session.status == SessionStatus.WAITING_FOR_CHILD && session.expiresAt <= now) {
            session.copy(status = SessionStatus.EXPIRED).also { sessions[id] = it }
        } else {
            session
        }
    }

    override suspend fun cancel(id: UUID, now: Instant): SessionRecord? = synchronized(sessions) {
        val session = sessions[id] ?: return@synchronized null
        if (session.status in setOf(SessionStatus.WAITING_FOR_CHILD, SessionStatus.READY)) {
            session.copy(status = SessionStatus.CANCELLED, completedAt = now).also { sessions[id] = it }
        } else {
            session
        }
    }

    override suspend fun complete(id: UUID, now: Instant): SessionRecord? = synchronized(sessions) {
        val session = sessions[id] ?: return@synchronized null
        if (session.status in setOf(SessionStatus.WAITING_FOR_CHILD, SessionStatus.READY)) {
            session.copy(status = SessionStatus.COMPLETED, completedAt = now).also { sessions[id] = it }
        } else session
    }

    override suspend fun updateDeviceConnection(
        session: SessionRecord,
        role: DeviceRole,
        connected: Boolean,
        now: Instant
    ) = Unit
}
