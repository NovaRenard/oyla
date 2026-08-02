package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.SessionStatus

data class SessionRecord(
    val id: UUID,
    val connectionCode: String,
    val childName: String,
    val status: SessionStatus,
    val specialistDeviceId: String,
    val specialistToken: String,
    val childDeviceId: String?,
    val childToken: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val connectedAt: Instant?,
    val completedAt: Instant?
)

sealed interface ChildConnectionResult {
    data class Connected(val session: SessionRecord) : ChildConnectionResult
    data object NotFound : ChildConnectionResult
    data object Expired : ChildConnectionResult
    data object ConnectedToAnotherDevice : ChildConnectionResult
}

interface SessionRepository {
    suspend fun isConnectionCodeActive(code: String, now: Instant): Boolean
    /** Returns false when another active session claimed the generated code first. */
    suspend fun createSession(session: SessionRecord): Boolean
    suspend fun findById(id: UUID): SessionRecord?
    suspend fun connectChild(
        code: String,
        childDeviceId: String,
        childToken: String,
        now: Instant
    ): ChildConnectionResult

    suspend fun expireIfNecessary(id: UUID, now: Instant): SessionRecord?
    suspend fun cancel(id: UUID, now: Instant): SessionRecord?
    suspend fun complete(id: UUID, now: Instant): SessionRecord?
    suspend fun updateDeviceConnection(
        session: SessionRecord,
        role: DeviceRole,
        connected: Boolean,
        now: Instant
    )
}
