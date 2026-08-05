package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.ActivationCodeStatus
import kz.oyla.server.model.AuditLogRecord
import kz.oyla.server.model.CenterMembershipRecord
import kz.oyla.server.model.CenterRecord
import kz.oyla.server.model.CenterStatus
import kz.oyla.server.model.DeviceActivationCodeRecord
import kz.oyla.server.model.DeviceRecord
import kz.oyla.server.model.DeviceStatus
import kz.oyla.server.model.RefreshTokenRecord
import kz.oyla.server.model.UserCenterMembership
import kz.oyla.server.model.UserRecord

/** Route-test repository. Production uses DatabaseSaasRepository and Flyway schema. */
class InMemorySaasRepository : SaasRepository {
    private val centers = linkedMapOf<UUID, CenterRecord>()
    private val users = linkedMapOf<UUID, UserRecord>()
    private val memberships = linkedMapOf<UUID, CenterMembershipRecord>()
    private val refreshTokens = linkedMapOf<UUID, RefreshTokenRecord>()
    private val codes = linkedMapOf<UUID, DeviceActivationCodeRecord>()
    private val devices = linkedMapOf<UUID, DeviceRecord>()
    private val audits = mutableListOf<AuditLogRecord>()

    /** Test-fixture hook for role-based route tests; production has no direct membership mutation API yet. */
    suspend fun seedMembership(record: CenterMembershipRecord) = synchronized(this) { memberships[record.id] = record }

    override suspend fun registerCenter(center: CenterRecord, user: UserRecord, membership: CenterMembershipRecord, audit: AuditLogRecord) = synchronized(this) {
        if (users.values.any { it.email == user.email } || centers.values.any { it.slug == center.slug }) return@synchronized false
        centers[center.id] = center; users[user.id] = user; memberships[membership.id] = membership; audits += audit
        true
    }
    override suspend fun findUserByEmail(email: String) = synchronized(this) { users.values.firstOrNull { it.email == email } }
    override suspend fun findUserById(id: UUID) = synchronized(this) { users[id] }
    override suspend fun updateLastLogin(userId: UUID, now: Instant) = synchronized(this) {
        users[userId]?.let { users[userId] = it.copy(lastLoginAt = now, updatedAt = now) }
        Unit
    }
    override suspend fun listCentersForUser(userId: UUID) = synchronized(this) {
        memberships.values.filter { it.userId == userId }.mapNotNull { membership -> centers[membership.centerId]?.let { UserCenterMembership(membership, it) } }
    }
    override suspend fun findCenter(id: UUID) = synchronized(this) { centers[id] }
    override suspend fun findMembership(centerId: UUID, userId: UUID) = synchronized(this) {
        memberships.values.firstOrNull { it.centerId == centerId && it.userId == userId }
    }
    override suspend fun updateCenter(centerId: UUID, name: String, timezone: String, updatedAt: Instant) = synchronized(this) {
        centers[centerId]?.copy(name = name, timezone = timezone, updatedAt = updatedAt)?.also { centers[centerId] = it }
    }

    override suspend fun createRefreshToken(token: RefreshTokenRecord) = synchronized(this) { refreshTokens[token.id] = token }
    override suspend fun findRefreshTokenByHash(tokenHash: String) = synchronized(this) { refreshTokens.values.firstOrNull { it.tokenHash == tokenHash } }
    override suspend fun rotateRefreshToken(oldHash: String, replacement: RefreshTokenRecord, now: Instant) = synchronized(this) {
        val old = refreshTokens.values.firstOrNull { it.tokenHash == oldHash && it.revokedAt == null && it.expiresAt > now }
            ?: return@synchronized false
        refreshTokens[old.id] = old.copy(revokedAt = now); refreshTokens[replacement.id] = replacement; true
    }
    override suspend fun revokeRefreshToken(tokenHash: String, now: Instant) = synchronized(this) {
        val token = refreshTokens.values.firstOrNull { it.tokenHash == tokenHash && it.revokedAt == null } ?: return@synchronized false
        refreshTokens[token.id] = token.copy(revokedAt = now); true
    }
    override suspend fun resetUserPassword(userId: UUID, passwordHash: String, now: Instant, audit: AuditLogRecord) = synchronized(this) {
        val user = users[userId] ?: return@synchronized false
        users[userId] = user.copy(passwordHash = passwordHash, updatedAt = now)
        refreshTokens.values.filter { it.userId == userId && it.revokedAt == null }.forEach { token ->
            refreshTokens[token.id] = token.copy(revokedAt = now)
        }
        audits += audit
        true
    }

    override suspend fun createActivationCode(record: DeviceActivationCodeRecord, audit: AuditLogRecord) = synchronized(this) {
        if (codes.values.any { it.codeHash == record.codeHash }) return@synchronized false
        codes[record.id] = record; audits += audit; true
    }
    override suspend fun listActiveActivationCodes(centerId: UUID, now: Instant) = synchronized(this) {
        codes.values.filter { it.centerId == centerId }.onEach { code ->
            if (code.status == ActivationCodeStatus.PENDING && code.expiresAt <= now) codes[code.id] = code.copy(status = ActivationCodeStatus.EXPIRED)
        }.mapNotNull { codes[it.id] }.filter { it.status == ActivationCodeStatus.PENDING && it.expiresAt > now }.sortedByDescending { it.createdAt }
    }
    override suspend fun cancelActivationCode(centerId: UUID, id: UUID, now: Instant, audit: AuditLogRecord) = synchronized(this) {
        val code = codes[id] ?: return@synchronized false
        if (code.centerId != centerId || code.status != ActivationCodeStatus.PENDING || code.expiresAt <= now) return@synchronized false
        codes[id] = code.copy(status = ActivationCodeStatus.CANCELLED); audits += audit; true
    }
    override suspend fun activateDevice(codeHash: String, deviceUid: String, deviceTokenHash: String, appVersion: String?, androidVersion: String?, model: String?, now: Instant, ipAddress: String?): DeviceActivationResult = synchronized(this) {
        val existing = devices.values.firstOrNull { it.deviceUid == deviceUid }
        when (existing?.status) {
            DeviceStatus.ACTIVE -> return@synchronized DeviceActivationResult.AlreadyActivated
            DeviceStatus.BLOCKED -> return@synchronized DeviceActivationResult.DeviceBlocked
            DeviceStatus.UNLINKED, null -> Unit
        }
        val code = codes.values.firstOrNull { it.codeHash == codeHash } ?: return@synchronized DeviceActivationResult.Invalid
        if (code.status == ActivationCodeStatus.USED) return@synchronized DeviceActivationResult.Used
        if (code.status != ActivationCodeStatus.PENDING) return@synchronized DeviceActivationResult.Invalid
        if (code.expiresAt <= now) { codes[code.id] = code.copy(status = ActivationCodeStatus.EXPIRED); return@synchronized DeviceActivationResult.Expired }
        val center = centers[code.centerId] ?: return@synchronized DeviceActivationResult.Invalid
        if (center.status != CenterStatus.ACTIVE) return@synchronized DeviceActivationResult.CenterUnavailable
        val device = existing?.copy(
            centerId = code.centerId, name = code.deviceName, role = code.deviceRole, status = DeviceStatus.ACTIVE,
            tokenHash = deviceTokenHash, tokenRevokedAt = null, appVersion = appVersion, androidVersion = androidVersion,
            model = model, lastSeenAt = now, activatedAt = now, updatedAt = now
        ) ?: DeviceRecord(UUID.randomUUID(), code.centerId, code.deviceName, code.deviceRole, DeviceStatus.ACTIVE, deviceUid,
            deviceTokenHash, null, appVersion, androidVersion, model, now, now, now, now)
        devices[device.id] = device; codes[code.id] = code.copy(status = ActivationCodeStatus.USED, usedAt = now)
        audits += AuditLogRecord(UUID.randomUUID(), center.id, kz.oyla.server.model.AuditActorType.DEVICE, device.id,
            "DEVICE_ACTIVATED", "DEVICE", device.id, "{}", ipAddress, now)
        DeviceActivationResult.Activated(device, center)
    }

    override suspend fun listDevices(centerId: UUID, filter: DeviceListFilter) = synchronized(this) {
        devices.values.filter { it.centerId == centerId && (filter.role == null || it.role == filter.role) && (filter.status == null || it.status == filter.status) }
            .filter { device -> filter.isOnline == null || (device.lastSeenAt?.let { last -> filter.onlineSince?.let { last >= it } } ?: false) == filter.isOnline }
            .sortedBy { it.name }
    }
    override suspend fun findDevice(centerId: UUID, id: UUID) = synchronized(this) { devices[id]?.takeIf { it.centerId == centerId } }
    override suspend fun findDeviceByTokenHash(tokenHash: String) = synchronized(this) { devices.values.firstOrNull { it.tokenHash == tokenHash } }
    override suspend fun isDeviceInActiveSession(deviceId: UUID) = false
    override suspend fun updateDevice(record: DeviceRecord) = synchronized(this) { if (devices.containsKey(record.id)) { devices[record.id] = record; true } else false }
    override suspend fun unlinkDevice(centerId: UUID, id: UUID, now: Instant, audit: AuditLogRecord) = synchronized(this) {
        val device = devices[id]?.takeIf { it.centerId == centerId } ?: return@synchronized null
        val unlinked = device.copy(status = DeviceStatus.UNLINKED, tokenRevokedAt = now, updatedAt = now)
        devices[id] = unlinked; audits += audit; unlinked
    }
    override suspend fun heartbeatDevice(id: UUID, appVersion: String?, androidVersion: String?, model: String?, now: Instant) = synchronized(this) {
        devices[id]?.copy(
            appVersion = appVersion ?: devices[id]!!.appVersion, androidVersion = androidVersion ?: devices[id]!!.androidVersion,
            model = model ?: devices[id]!!.model, lastSeenAt = now, updatedAt = now
        )?.also { devices[id] = it }
    }
    override suspend fun recordAudit(record: AuditLogRecord) = synchronized(this) { audits += record }
}
