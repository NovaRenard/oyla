package kz.oyla.server.repository

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kz.oyla.server.model.ActivationCodeStatus
import kz.oyla.server.model.AuditActorType
import kz.oyla.server.model.AuditLogRecord
import kz.oyla.server.model.CenterMembershipRecord
import kz.oyla.server.model.CenterRecord
import kz.oyla.server.model.CenterStatus
import kz.oyla.server.model.ChildRecord
import kz.oyla.server.model.ChildStatus
import kz.oyla.server.model.DeviceActivationCodeRecord
import kz.oyla.server.model.DeviceRecord
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.DeviceStatus
import kz.oyla.server.model.MembershipRole
import kz.oyla.server.model.MembershipStatus
import kz.oyla.server.model.RefreshTokenRecord
import kz.oyla.server.model.UserCenterMembership
import kz.oyla.server.model.UserRecord
import kz.oyla.server.model.UserStatus
import kz.oyla.server.model.SpecialistRecord
import kz.oyla.server.model.SpecialistStatus
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

/** JDBC follows the existing repository style; Flyway owns all DDL. */
class DatabaseSaasRepository : SaasRepository {
    override suspend fun registerCenter(center: CenterRecord, user: UserRecord, membership: CenterMembershipRecord, audit: AuditLogRecord): Boolean = database {
        connection.prepareStatement(
            """INSERT INTO users (id, email, password_hash, first_name, last_name, status, created_at, updated_at, last_login_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (email) DO NOTHING"""
        ).use { statement ->
            statement.setObject(1, user.id); statement.setString(2, user.email); statement.setString(3, user.passwordHash)
            statement.setString(4, user.firstName); statement.setString(5, user.lastName); statement.setString(6, user.status.name)
            statement.setInstant(7, user.createdAt); statement.setInstant(8, user.updatedAt); statement.setInstant(9, user.lastLoginAt)
            if (statement.executeUpdate() != 1) return@database false
        }
        connection.prepareStatement(
            """INSERT INTO centers (id, name, slug, status, timezone, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setObject(1, center.id); statement.setString(2, center.name); statement.setString(3, center.slug)
            statement.setString(4, center.status.name); statement.setString(5, center.timezone)
            statement.setInstant(6, center.createdAt); statement.setInstant(7, center.updatedAt); statement.executeUpdate()
        }
        connection.prepareStatement(
            """INSERT INTO center_memberships (id, center_id, user_id, role, status, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setObject(1, membership.id); statement.setObject(2, membership.centerId); statement.setObject(3, membership.userId)
            statement.setString(4, membership.role.name); statement.setString(5, membership.status.name)
            statement.setInstant(6, membership.createdAt); statement.setInstant(7, membership.updatedAt); statement.executeUpdate()
        }
        insertAudit(audit)
        true
    }

    override suspend fun findUserByEmail(email: String): UserRecord? = database {
        connection.findUser("SELECT * FROM users WHERE email = ?") { setString(1, email) }
    }
    override suspend fun findUserById(id: UUID): UserRecord? = database {
        connection.findUser("SELECT * FROM users WHERE id = ?") { setObject(1, id) }
    }
    override suspend fun updateLastLogin(userId: UUID, now: Instant) = database {
        connection.prepareStatement("UPDATE users SET last_login_at = ?, updated_at = ? WHERE id = ?").use {
            it.setInstant(1, now); it.setInstant(2, now); it.setObject(3, userId); it.executeUpdate()
        }
        Unit
    }
    override suspend fun listCentersForUser(userId: UUID): List<UserCenterMembership> = database {
        connection.prepareStatement(
            """SELECT m.*, c.id AS c_id, c.name AS c_name, c.slug AS c_slug, c.status AS c_status,
                      c.timezone AS c_timezone, c.created_at AS c_created_at, c.updated_at AS c_updated_at
               FROM center_memberships m JOIN centers c ON c.id = m.center_id
               WHERE m.user_id = ? ORDER BY c.created_at"""
        ).use { statement ->
            statement.setObject(1, userId); statement.executeQuery().use { result ->
                buildList { while (result.next()) add(UserCenterMembership(result.toMembership(), result.toJoinedCenter())) }
            }
        }
    }
    override suspend fun findCenter(id: UUID): CenterRecord? = database {
        connection.findCenter("SELECT * FROM centers WHERE id = ?") { setObject(1, id) }
    }
    override suspend fun findMembership(centerId: UUID, userId: UUID): CenterMembershipRecord? = database {
        connection.findMembership("SELECT * FROM center_memberships WHERE center_id = ? AND user_id = ?") {
            setObject(1, centerId); setObject(2, userId)
        }
    }
    override suspend fun updateCenter(centerId: UUID, name: String, timezone: String, updatedAt: Instant): CenterRecord? = database {
        connection.prepareStatement("UPDATE centers SET name = ?, timezone = ?, updated_at = ? WHERE id = ?").use {
            it.setString(1, name); it.setString(2, timezone); it.setInstant(3, updatedAt); it.setObject(4, centerId); it.executeUpdate()
        }
        connection.findCenter("SELECT * FROM centers WHERE id = ?") { setObject(1, centerId) }
    }

    override suspend fun createRefreshToken(token: RefreshTokenRecord) = database { insertRefreshToken(token) }
    override suspend fun findRefreshTokenByHash(tokenHash: String): RefreshTokenRecord? = database {
        connection.findRefreshToken("SELECT * FROM refresh_tokens WHERE token_hash = ?") { setString(1, tokenHash) }
    }
    override suspend fun rotateRefreshToken(oldHash: String, replacement: RefreshTokenRecord, now: Instant): Boolean = database {
        val updated = connection.prepareStatement(
            """UPDATE refresh_tokens SET revoked_at = ?
               WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?"""
        ).use { statement -> statement.setInstant(1, now); statement.setString(2, oldHash); statement.setInstant(3, now); statement.executeUpdate() }
        if (updated != 1) return@database false
        insertRefreshToken(replacement)
        true
    }
    override suspend fun revokeRefreshToken(tokenHash: String, now: Instant): Boolean = database {
        connection.prepareStatement("UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL").use {
            it.setInstant(1, now); it.setString(2, tokenHash); it.executeUpdate() == 1
        }
    }
    override suspend fun resetUserPassword(userId: UUID, passwordHash: String, now: Instant, audit: AuditLogRecord): Boolean = database {
        val updated = connection.prepareStatement("UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?").use {
            it.setString(1, passwordHash); it.setInstant(2, now); it.setObject(3, userId); it.executeUpdate() == 1
        }
        if (!updated) return@database false
        connection.prepareStatement("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL").use {
            it.setInstant(1, now); it.setObject(2, userId); it.executeUpdate()
        }
        insertAudit(audit)
        true
    }

    override suspend fun createActivationCode(record: DeviceActivationCodeRecord, audit: AuditLogRecord): Boolean = database {
        connection.prepareStatement(
            """INSERT INTO device_activation_codes
               (id, center_id, created_by_user_id, device_name, device_role, code_hash, status, expires_at, used_at, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (code_hash) DO NOTHING"""
        ).use { statement ->
            statement.setObject(1, record.id); statement.setObject(2, record.centerId); statement.setObject(3, record.createdByUserId)
            statement.setString(4, record.deviceName); statement.setString(5, record.deviceRole.name); statement.setString(6, record.codeHash)
            statement.setString(7, record.status.name); statement.setInstant(8, record.expiresAt); statement.setInstant(9, record.usedAt); statement.setInstant(10, record.createdAt)
            if (statement.executeUpdate() != 1) return@database false
        }
        insertAudit(audit)
        true
    }
    override suspend fun listActiveActivationCodes(centerId: UUID, now: Instant): List<DeviceActivationCodeRecord> = database {
        connection.prepareStatement("UPDATE device_activation_codes SET status = 'EXPIRED' WHERE center_id = ? AND status = 'PENDING' AND expires_at <= ?").use {
            it.setObject(1, centerId); it.setInstant(2, now); it.executeUpdate()
        }
        connection.prepareStatement(
            """SELECT * FROM device_activation_codes WHERE center_id = ? AND status = 'PENDING' AND expires_at > ?
               ORDER BY created_at DESC"""
        ).use { statement ->
            statement.setObject(1, centerId); statement.setInstant(2, now); statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.toActivationCode()) }
            }
        }
    }
    override suspend fun cancelActivationCode(centerId: UUID, id: UUID, now: Instant, audit: AuditLogRecord): Boolean = database {
        val cancelled = connection.prepareStatement(
            """UPDATE device_activation_codes SET status = 'CANCELLED'
               WHERE id = ? AND center_id = ? AND status = 'PENDING' AND expires_at > ?"""
        ).use { statement -> statement.setObject(1, id); statement.setObject(2, centerId); statement.setInstant(3, now); statement.executeUpdate() == 1 }
        if (cancelled) insertAudit(audit)
        cancelled
    }
    override suspend fun activateDevice(codeHash: String, deviceUid: String, deviceTokenHash: String, appVersion: String?, androidVersion: String?, model: String?, now: Instant, ipAddress: String?): DeviceActivationResult = database {
        // A transaction-scoped advisory lock makes a device_uid a serialization point even when
        // two different activation codes are submitted concurrently for a brand-new install.
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { statement ->
            statement.setString(1, deviceUid); statement.execute()
        }
        val existing = connection.findDevice("SELECT * FROM devices WHERE device_uid = ? FOR UPDATE") { setString(1, deviceUid) }
        // Do this before looking at the supplied code. A tablet that has not been unlinked does
        // not participate in a different center's activation workflow at all.
        when (existing?.status) {
            DeviceStatus.ACTIVE -> return@database DeviceActivationResult.AlreadyActivated
            DeviceStatus.BLOCKED -> return@database DeviceActivationResult.DeviceBlocked
            DeviceStatus.UNLINKED, null -> Unit
        }
        val code = connection.findActivationCode(
            "SELECT * FROM device_activation_codes WHERE code_hash = ? FOR UPDATE"
        ) { setString(1, codeHash) } ?: return@database DeviceActivationResult.Invalid
        when (code.status) {
            ActivationCodeStatus.USED -> return@database DeviceActivationResult.Used
            ActivationCodeStatus.PENDING -> Unit
            else -> return@database DeviceActivationResult.Invalid
        }
        if (code.expiresAt <= now) {
            connection.prepareStatement("UPDATE device_activation_codes SET status = 'EXPIRED' WHERE id = ?").use { it.setObject(1, code.id); it.executeUpdate() }
            return@database DeviceActivationResult.Expired
        }
        val center = connection.findCenter("SELECT * FROM centers WHERE id = ? FOR UPDATE") { setObject(1, code.centerId) }
            ?: return@database DeviceActivationResult.Invalid
        if (center.status != CenterStatus.ACTIVE) return@database DeviceActivationResult.CenterUnavailable

        val device = if (existing == null) {
            DeviceRecord(UUID.randomUUID(), center.id, code.deviceName, code.deviceRole, DeviceStatus.ACTIVE, deviceUid,
                deviceTokenHash, null, appVersion, androidVersion, model, now, now, now, now).also(::insertDevice)
        } else { // only an explicitly UNLINKED record may be reactivated and move to another center
            existing.copy(centerId = center.id, name = code.deviceName, role = code.deviceRole, status = DeviceStatus.ACTIVE,
                tokenHash = deviceTokenHash, tokenRevokedAt = null, appVersion = appVersion, androidVersion = androidVersion,
                model = model, lastSeenAt = now, activatedAt = now, updatedAt = now).also(::replaceActivatedDevice)
        }
        connection.prepareStatement("UPDATE device_activation_codes SET status = 'USED', used_at = ? WHERE id = ? AND status = 'PENDING'").use {
            it.setInstant(1, now); it.setObject(2, code.id); check(it.executeUpdate() == 1)
        }
        insertAudit(AuditLogRecord(UUID.randomUUID(), center.id, AuditActorType.DEVICE, device.id, "DEVICE_ACTIVATED", "DEVICE", device.id, "{}", ipAddress, now))
        DeviceActivationResult.Activated(device, center)
    }

    override suspend fun listDevices(centerId: UUID, filter: DeviceListFilter): List<DeviceRecord> = database {
        val clauses = mutableListOf("center_id = ?")
        if (filter.role != null) clauses += "role = ?"
        if (filter.status != null) clauses += "status = ?"
        if (filter.isOnline == true) clauses += "last_seen_at >= ?"
        if (filter.isOnline == false) clauses += "(last_seen_at IS NULL OR last_seen_at < ?)"
        connection.prepareStatement("SELECT * FROM devices WHERE ${clauses.joinToString(" AND ")} ORDER BY name, id").use { statement ->
            var index = 1
            statement.setObject(index++, centerId)
            filter.role?.let { statement.setString(index++, it.name) }
            filter.status?.let { statement.setString(index++, it.name) }
            if (filter.isOnline != null) statement.setInstant(index, requireNotNull(filter.onlineSince))
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toDevice()) } }
        }
    }
    override suspend fun findDevice(centerId: UUID, id: UUID): DeviceRecord? = database {
        connection.findDevice("SELECT * FROM devices WHERE center_id = ? AND id = ?") { setObject(1, centerId); setObject(2, id) }
    }
    override suspend fun findDeviceByTokenHash(tokenHash: String): DeviceRecord? = database {
        connection.findDevice("SELECT * FROM devices WHERE token_hash = ?") { setString(1, tokenHash) }
    }
    override suspend fun isDeviceInActiveSession(deviceId: UUID): Boolean = database {
        connection.prepareStatement(
            """SELECT 1 FROM sessions
               WHERE status IN ('WAITING_FOR_CHILD', 'READY')
                 AND (specialist_device_id = ? OR child_device_id = ?) LIMIT 1"""
        ).use { statement ->
            val legacyId = deviceId.toString(); statement.setString(1, legacyId); statement.setString(2, legacyId)
            statement.executeQuery().use(ResultSet::next)
        }
    }
    override suspend fun updateDevice(record: DeviceRecord): Boolean = database {
        connection.prepareStatement(
            """UPDATE devices SET name = ?, role = ?, status = ?, app_version = ?, android_version = ?, model = ?,
               last_seen_at = ?, activated_at = ?, updated_at = ?, token_hash = ?, token_revoked_at = ?
               WHERE id = ? AND center_id = ?"""
        ).use { statement ->
            statement.setString(1, record.name); statement.setString(2, record.role.name); statement.setString(3, record.status.name)
            statement.setString(4, record.appVersion); statement.setString(5, record.androidVersion); statement.setString(6, record.model)
            statement.setInstant(7, record.lastSeenAt); statement.setInstant(8, record.activatedAt); statement.setInstant(9, record.updatedAt)
            statement.setString(10, record.tokenHash); statement.setInstant(11, record.tokenRevokedAt)
            statement.setObject(12, record.id); statement.setObject(13, record.centerId); statement.executeUpdate() == 1
        }
    }
    override suspend fun unlinkDevice(centerId: UUID, id: UUID, now: Instant, audit: AuditLogRecord): DeviceRecord? = database {
        val device = connection.findDevice("SELECT * FROM devices WHERE center_id = ? AND id = ? FOR UPDATE") {
            setObject(1, centerId); setObject(2, id)
        } ?: return@database null
        val updated = device.copy(status = DeviceStatus.UNLINKED, tokenRevokedAt = now, updatedAt = now)
        if (!updateDeviceInTransaction(updated)) return@database null
        insertAudit(audit)
        updated
    }
    override suspend fun heartbeatDevice(id: UUID, appVersion: String?, androidVersion: String?, model: String?, now: Instant): DeviceRecord? = database {
        val current = connection.findDevice("SELECT * FROM devices WHERE id = ? FOR UPDATE") { setObject(1, id) } ?: return@database null
        val updated = current.copy(appVersion = appVersion ?: current.appVersion, androidVersion = androidVersion ?: current.androidVersion,
            model = model ?: current.model, lastSeenAt = now, updatedAt = now)
        if (!updateDeviceInTransaction(updated)) null else updated
    }

    override suspend fun createChild(record: ChildRecord): ChildRecord = database {
        connection.prepareStatement(
            """INSERT INTO children (id, center_id, first_name, last_name, birth_date, status, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setObject(1, record.id); statement.setObject(2, record.centerId); statement.setString(3, record.firstName)
            statement.setString(4, record.lastName); statement.setObject(5, record.birthDate); statement.setString(6, record.status.name)
            statement.setInstant(7, record.createdAt); statement.setInstant(8, record.updatedAt); statement.executeUpdate()
        }
        record
    }

    override suspend fun listChildren(centerId: UUID, filter: ChildListFilter): List<ChildRecord> = database {
        val clauses = mutableListOf("center_id = ?")
        if (filter.status != null) clauses += "status = ?"
        if (!filter.search.isNullOrBlank()) clauses += "(first_name ILIKE ? OR COALESCE(last_name, '') ILIKE ?)"
        connection.prepareStatement("SELECT * FROM children WHERE ${clauses.joinToString(" AND ")} ORDER BY first_name, last_name NULLS LAST, id").use { statement ->
            var index = 1; statement.setObject(index++, centerId)
            filter.status?.let { statement.setString(index++, it.name) }
            filter.search?.takeIf { it.isNotBlank() }?.let { query -> statement.setString(index++, "%$query%"); statement.setString(index++, "%$query%") }
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toChild()) } }
        }
    }

    override suspend fun findChild(centerId: UUID, id: UUID): ChildRecord? = database {
        connection.findChild("SELECT * FROM children WHERE center_id = ? AND id = ?") { setObject(1, centerId); setObject(2, id) }
    }

    override suspend fun updateChild(record: ChildRecord): Boolean = database {
        connection.prepareStatement(
            """UPDATE children SET first_name = ?, last_name = ?, birth_date = ?, status = ?, updated_at = ?
               WHERE id = ? AND center_id = ?"""
        ).use { statement ->
            statement.setString(1, record.firstName); statement.setString(2, record.lastName); statement.setObject(3, record.birthDate)
            statement.setString(4, record.status.name); statement.setInstant(5, record.updatedAt); statement.setObject(6, record.id); statement.setObject(7, record.centerId)
            statement.executeUpdate() == 1
        }
    }

    override suspend fun createSpecialist(record: SpecialistRecord): SpecialistRecord = database {
        connection.prepareStatement(
            """INSERT INTO specialists (id, center_id, first_name, last_name, specialization, status, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setObject(1, record.id); statement.setObject(2, record.centerId); statement.setString(3, record.firstName)
            statement.setString(4, record.lastName); statement.setString(5, record.specialization); statement.setString(6, record.status.name)
            statement.setInstant(7, record.createdAt); statement.setInstant(8, record.updatedAt); statement.executeUpdate()
        }
        record
    }

    override suspend fun listSpecialists(centerId: UUID, filter: SpecialistListFilter): List<SpecialistRecord> = database {
        val clauses = mutableListOf("center_id = ?")
        if (filter.status != null) clauses += "status = ?"
        if (!filter.search.isNullOrBlank()) clauses += "(first_name ILIKE ? OR COALESCE(last_name, '') ILIKE ? OR COALESCE(specialization, '') ILIKE ?)"
        connection.prepareStatement("SELECT * FROM specialists WHERE ${clauses.joinToString(" AND ")} ORDER BY first_name, last_name NULLS LAST, id").use { statement ->
            var index = 1; statement.setObject(index++, centerId)
            filter.status?.let { statement.setString(index++, it.name) }
            filter.search?.takeIf { it.isNotBlank() }?.let { query -> repeat(3) { statement.setString(index++, "%$query%") } }
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toSpecialist()) } }
        }
    }

    override suspend fun findSpecialist(centerId: UUID, id: UUID): SpecialistRecord? = database {
        connection.findSpecialist("SELECT * FROM specialists WHERE center_id = ? AND id = ?") { setObject(1, centerId); setObject(2, id) }
    }

    override suspend fun updateSpecialist(record: SpecialistRecord): Boolean = database {
        connection.prepareStatement(
            """UPDATE specialists SET first_name = ?, last_name = ?, specialization = ?, status = ?, updated_at = ?
               WHERE id = ? AND center_id = ?"""
        ).use { statement ->
            statement.setString(1, record.firstName); statement.setString(2, record.lastName); statement.setString(3, record.specialization)
            statement.setString(4, record.status.name); statement.setInstant(5, record.updatedAt); statement.setObject(6, record.id); statement.setObject(7, record.centerId)
            statement.executeUpdate() == 1
        }
    }
    override suspend fun recordAudit(record: AuditLogRecord) = database { insertAudit(record) }

    private fun insertRefreshToken(token: RefreshTokenRecord) {
        connection.prepareStatement("INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, created_at, revoked_at) VALUES (?, ?, ?, ?, ?, ?)").use {
            it.setObject(1, token.id); it.setObject(2, token.userId); it.setString(3, token.tokenHash); it.setInstant(4, token.expiresAt); it.setInstant(5, token.createdAt); it.setInstant(6, token.revokedAt); it.executeUpdate()
        }
    }
    private fun insertDevice(device: DeviceRecord) {
        connection.prepareStatement(
            """INSERT INTO devices (id, center_id, name, role, status, device_uid, token_hash, token_revoked_at,
               app_version, android_version, model, last_seen_at, activated_at, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setObject(1, device.id); statement.setObject(2, device.centerId); statement.setString(3, device.name); statement.setString(4, device.role.name); statement.setString(5, device.status.name)
            statement.setString(6, device.deviceUid); statement.setString(7, device.tokenHash); statement.setInstant(8, device.tokenRevokedAt); statement.setString(9, device.appVersion); statement.setString(10, device.androidVersion)
            statement.setString(11, device.model); statement.setInstant(12, device.lastSeenAt); statement.setInstant(13, device.activatedAt); statement.setInstant(14, device.createdAt); statement.setInstant(15, device.updatedAt); statement.executeUpdate()
        }
    }
    private fun replaceActivatedDevice(device: DeviceRecord) {
        connection.prepareStatement(
            """UPDATE devices SET center_id = ?, name = ?, role = ?, status = ?, token_hash = ?, token_revoked_at = ?,
               app_version = ?, android_version = ?, model = ?, last_seen_at = ?, activated_at = ?, updated_at = ? WHERE id = ?"""
        ).use { statement ->
            statement.setObject(1, device.centerId); statement.setString(2, device.name); statement.setString(3, device.role.name); statement.setString(4, device.status.name)
            statement.setString(5, device.tokenHash); statement.setInstant(6, device.tokenRevokedAt); statement.setString(7, device.appVersion); statement.setString(8, device.androidVersion)
            statement.setString(9, device.model); statement.setInstant(10, device.lastSeenAt); statement.setInstant(11, device.activatedAt); statement.setInstant(12, device.updatedAt); statement.setObject(13, device.id); check(statement.executeUpdate() == 1)
        }
    }
    private fun updateDeviceInTransaction(record: DeviceRecord): Boolean = connection.prepareStatement(
        """UPDATE devices SET name = ?, role = ?, status = ?, app_version = ?, android_version = ?, model = ?, last_seen_at = ?,
           activated_at = ?, updated_at = ?, token_hash = ?, token_revoked_at = ? WHERE id = ? AND center_id = ?"""
    ).use { statement ->
        statement.setString(1, record.name); statement.setString(2, record.role.name); statement.setString(3, record.status.name)
        statement.setString(4, record.appVersion); statement.setString(5, record.androidVersion); statement.setString(6, record.model)
        statement.setInstant(7, record.lastSeenAt); statement.setInstant(8, record.activatedAt); statement.setInstant(9, record.updatedAt)
        statement.setString(10, record.tokenHash); statement.setInstant(11, record.tokenRevokedAt); statement.setObject(12, record.id); statement.setObject(13, record.centerId)
        statement.executeUpdate() == 1
    }
    private fun insertAudit(record: AuditLogRecord) {
        connection.prepareStatement(
            """INSERT INTO audit_logs (id, center_id, actor_type, actor_id, action, entity_type, entity_id, metadata, ip_address, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)"""
        ).use { statement ->
            statement.setObject(1, record.id); statement.setObject(2, record.centerId); statement.setString(3, record.actorType.name); statement.setObject(4, record.actorId)
            statement.setString(5, record.action); statement.setString(6, record.entityType); statement.setObject(7, record.entityId); statement.setString(8, record.metadata)
            statement.setString(9, record.ipAddress); statement.setInstant(10, record.createdAt); statement.executeUpdate()
        }
    }

    private fun Connection.findUser(sql: String, bind: PreparedStatement.() -> Unit): UserRecord? = prepareStatement(sql).use { statement -> statement.bind(); statement.executeQuery().use { if (it.next()) it.toUser() else null } }
    private fun Connection.findCenter(sql: String, bind: PreparedStatement.() -> Unit): CenterRecord? = prepareStatement(sql).use { statement -> statement.bind(); statement.executeQuery().use { if (it.next()) it.toCenter() else null } }
    private fun Connection.findMembership(sql: String, bind: PreparedStatement.() -> Unit): CenterMembershipRecord? = prepareStatement(sql).use { statement -> statement.bind(); statement.executeQuery().use { if (it.next()) it.toMembership() else null } }
    private fun Connection.findRefreshToken(sql: String, bind: PreparedStatement.() -> Unit): RefreshTokenRecord? = prepareStatement(sql).use { statement -> statement.bind(); statement.executeQuery().use { if (it.next()) it.toRefreshToken() else null } }
    private fun Connection.findActivationCode(sql: String, bind: PreparedStatement.() -> Unit): DeviceActivationCodeRecord? = prepareStatement(sql).use { statement -> statement.bind(); statement.executeQuery().use { if (it.next()) it.toActivationCode() else null } }
    private fun Connection.findDevice(sql: String, bind: PreparedStatement.() -> Unit): DeviceRecord? = prepareStatement(sql).use { statement -> statement.bind(); statement.executeQuery().use { if (it.next()) it.toDevice() else null } }
    private fun Connection.findChild(sql: String, bind: PreparedStatement.() -> Unit): ChildRecord? = prepareStatement(sql).use { statement -> statement.bind(); statement.executeQuery().use { if (it.next()) it.toChild() else null } }
    private fun Connection.findSpecialist(sql: String, bind: PreparedStatement.() -> Unit): SpecialistRecord? = prepareStatement(sql).use { statement -> statement.bind(); statement.executeQuery().use { if (it.next()) it.toSpecialist() else null } }
    private fun PreparedStatement.setInstant(index: Int, value: Instant?) = setTimestamp(index, value?.let(java.sql.Timestamp::from))

    private fun ResultSet.toCenter() = CenterRecord(getObject("id", UUID::class.java), getString("name"), getString("slug"), CenterStatus.valueOf(getString("status")), getString("timezone"), getTimestamp("created_at").toInstant(), getTimestamp("updated_at").toInstant())
    private fun ResultSet.toJoinedCenter() = CenterRecord(getObject("c_id", UUID::class.java), getString("c_name"), getString("c_slug"), CenterStatus.valueOf(getString("c_status")), getString("c_timezone"), getTimestamp("c_created_at").toInstant(), getTimestamp("c_updated_at").toInstant())
    private fun ResultSet.toUser() = UserRecord(getObject("id", UUID::class.java), getString("email"), getString("password_hash"), getString("first_name"), getString("last_name"), UserStatus.valueOf(getString("status")), getTimestamp("created_at").toInstant(), getTimestamp("updated_at").toInstant(), getTimestamp("last_login_at")?.toInstant())
    private fun ResultSet.toMembership() = CenterMembershipRecord(getObject("id", UUID::class.java), getObject("center_id", UUID::class.java), getObject("user_id", UUID::class.java), MembershipRole.valueOf(getString("role")), MembershipStatus.valueOf(getString("status")), getTimestamp("created_at").toInstant(), getTimestamp("updated_at").toInstant())
    private fun ResultSet.toRefreshToken() = RefreshTokenRecord(getObject("id", UUID::class.java), getObject("user_id", UUID::class.java), getString("token_hash"), getTimestamp("expires_at").toInstant(), getTimestamp("created_at").toInstant(), getTimestamp("revoked_at")?.toInstant())
    private fun ResultSet.toActivationCode() = DeviceActivationCodeRecord(getObject("id", UUID::class.java), getObject("center_id", UUID::class.java), getObject("created_by_user_id", UUID::class.java), getString("device_name"), DeviceRole.valueOf(getString("device_role")), getString("code_hash"), ActivationCodeStatus.valueOf(getString("status")), getTimestamp("expires_at").toInstant(), getTimestamp("used_at")?.toInstant(), getTimestamp("created_at").toInstant())
    private fun ResultSet.toDevice() = DeviceRecord(getObject("id", UUID::class.java), getObject("center_id", UUID::class.java), getString("name"), DeviceRole.valueOf(getString("role")), DeviceStatus.valueOf(getString("status")), getString("device_uid"), getString("token_hash"), getTimestamp("token_revoked_at")?.toInstant(), getString("app_version"), getString("android_version"), getString("model"), getTimestamp("last_seen_at")?.toInstant(), getTimestamp("activated_at")?.toInstant(), getTimestamp("created_at").toInstant(), getTimestamp("updated_at").toInstant())
    private fun ResultSet.toChild() = ChildRecord(getObject("id", UUID::class.java), getObject("center_id", UUID::class.java), getString("first_name"), getString("last_name"), getObject("birth_date", java.time.LocalDate::class.java), ChildStatus.valueOf(getString("status")), getTimestamp("created_at").toInstant(), getTimestamp("updated_at").toInstant())
    private fun ResultSet.toSpecialist() = SpecialistRecord(getObject("id", UUID::class.java), getObject("center_id", UUID::class.java), getString("first_name"), getString("last_name"), getString("specialization"), SpecialistStatus.valueOf(getString("status")), getTimestamp("created_at").toInstant(), getTimestamp("updated_at").toInstant())

    private val connection: Connection get() = (TransactionManager.current().connection as JdbcConnectionImpl).connection
    private suspend fun <T> database(block: () -> T): T = withContext(Dispatchers.IO) { transaction { block() } }
}
