package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.AuditLogRecord
import kz.oyla.server.model.ChildRecord
import kz.oyla.server.model.ChildStatus
import kz.oyla.server.model.CenterMembershipRecord
import kz.oyla.server.model.CenterRecord
import kz.oyla.server.model.DeviceActivationCodeRecord
import kz.oyla.server.model.DeviceRecord
import kz.oyla.server.model.DeviceStatus
import kz.oyla.server.model.RefreshTokenRecord
import kz.oyla.server.model.UserCenterMembership
import kz.oyla.server.model.UserRecord
import kz.oyla.server.model.SpecialistRecord
import kz.oyla.server.model.SpecialistStatus

data class DeviceListFilter(
    val role: kz.oyla.server.model.DeviceRole? = null,
    val status: DeviceStatus? = null,
    val onlineSince: Instant? = null,
    val isOnline: Boolean? = null
)

data class ChildListFilter(val status: ChildStatus? = ChildStatus.ACTIVE, val search: String? = null)
data class SpecialistListFilter(val status: SpecialistStatus? = SpecialistStatus.ACTIVE, val search: String? = null)

sealed interface DeviceActivationResult {
    data class Activated(val device: DeviceRecord, val center: CenterRecord) : DeviceActivationResult
    /** An existing tablet must be explicitly unlinked before another center can claim it. */
    data object AlreadyActivated : DeviceActivationResult
    data object DeviceBlocked : DeviceActivationResult
    data object Invalid : DeviceActivationResult
    data object Expired : DeviceActivationResult
    data object Used : DeviceActivationResult
    data object CenterUnavailable : DeviceActivationResult
}

interface SaasRepository {
    /** Atomically persists a center, the first user, its OWNER membership, and its audit entry. */
    suspend fun registerCenter(
        center: CenterRecord,
        user: UserRecord,
        membership: CenterMembershipRecord,
        audit: AuditLogRecord
    ): Boolean

    suspend fun findUserByEmail(email: String): UserRecord?
    suspend fun findUserById(id: UUID): UserRecord?
    suspend fun updateLastLogin(userId: UUID, now: Instant)
    suspend fun listCentersForUser(userId: UUID): List<UserCenterMembership>
    suspend fun findCenter(id: UUID): CenterRecord?
    suspend fun findMembership(centerId: UUID, userId: UUID): CenterMembershipRecord?
    suspend fun updateCenter(centerId: UUID, name: String, timezone: String, updatedAt: Instant): CenterRecord?

    suspend fun createRefreshToken(token: RefreshTokenRecord)
    suspend fun findRefreshTokenByHash(tokenHash: String): RefreshTokenRecord?
    /** Returns false if token was already revoked or expired; otherwise creates the replacement atomically. */
    suspend fun rotateRefreshToken(oldHash: String, replacement: RefreshTokenRecord, now: Instant): Boolean
    suspend fun revokeRefreshToken(tokenHash: String, now: Instant): Boolean
    /** Atomically changes a password, revokes all existing browser sessions and writes the audit event. */
    suspend fun resetUserPassword(userId: UUID, passwordHash: String, now: Instant, audit: AuditLogRecord): Boolean

    suspend fun createActivationCode(record: DeviceActivationCodeRecord, audit: AuditLogRecord): Boolean
    suspend fun listActiveActivationCodes(centerId: UUID, now: Instant): List<DeviceActivationCodeRecord>
    suspend fun cancelActivationCode(centerId: UUID, id: UUID, now: Instant, audit: AuditLogRecord): Boolean
    suspend fun activateDevice(
        codeHash: String,
        deviceUid: String,
        deviceTokenHash: String,
        appVersion: String?,
        androidVersion: String?,
        model: String?,
        now: Instant,
        ipAddress: String?
    ): DeviceActivationResult

    suspend fun listDevices(centerId: UUID, filter: DeviceListFilter): List<DeviceRecord>
    suspend fun findDevice(centerId: UUID, id: UUID): DeviceRecord?
    suspend fun findDeviceByTokenHash(tokenHash: String): DeviceRecord?
    suspend fun isDeviceInActiveSession(deviceId: UUID): Boolean
    suspend fun updateDevice(record: DeviceRecord): Boolean
    suspend fun unlinkDevice(centerId: UUID, id: UUID, now: Instant, audit: AuditLogRecord): DeviceRecord?
    suspend fun heartbeatDevice(id: UUID, appVersion: String?, androidVersion: String?, model: String?, now: Instant): DeviceRecord?

    suspend fun createChild(record: ChildRecord): ChildRecord
    suspend fun listChildren(centerId: UUID, filter: ChildListFilter): List<ChildRecord>
    suspend fun findChild(centerId: UUID, id: UUID): ChildRecord?
    suspend fun updateChild(record: ChildRecord): Boolean

    suspend fun createSpecialist(record: SpecialistRecord): SpecialistRecord
    suspend fun listSpecialists(centerId: UUID, filter: SpecialistListFilter): List<SpecialistRecord>
    suspend fun findSpecialist(centerId: UUID, id: UUID): SpecialistRecord?
    suspend fun updateSpecialist(record: SpecialistRecord): Boolean

    suspend fun recordAudit(record: AuditLogRecord)
}
