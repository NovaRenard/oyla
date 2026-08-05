package kz.oyla.server.model

import java.time.Instant
import java.util.UUID

enum class CenterStatus { ACTIVE, SUSPENDED, ARCHIVED }
enum class UserStatus { ACTIVE, BLOCKED }
enum class MembershipRole { OWNER, ADMIN, METHODIST, SPECIALIST }
enum class MembershipStatus { ACTIVE, INVITED, BLOCKED }
enum class DeviceStatus { ACTIVE, BLOCKED, UNLINKED }
enum class ActivationCodeStatus { PENDING, USED, EXPIRED, CANCELLED }
enum class AuditActorType { USER, DEVICE, SYSTEM }

data class CenterRecord(
    val id: UUID,
    val name: String,
    val slug: String,
    val status: CenterStatus,
    val timezone: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class UserRecord(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val firstName: String,
    val lastName: String?,
    val status: UserStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastLoginAt: Instant?
)

data class CenterMembershipRecord(
    val id: UUID,
    val centerId: UUID,
    val userId: UUID,
    val role: MembershipRole,
    val status: MembershipStatus,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class UserCenterMembership(
    val membership: CenterMembershipRecord,
    val center: CenterRecord
)

data class DeviceRecord(
    val id: UUID,
    val centerId: UUID,
    val name: String,
    val role: DeviceRole,
    val status: DeviceStatus,
    val deviceUid: String?,
    val tokenHash: String?,
    val tokenRevokedAt: Instant?,
    val appVersion: String?,
    val androidVersion: String?,
    val model: String?,
    val lastSeenAt: Instant?,
    val activatedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class DeviceActivationCodeRecord(
    val id: UUID,
    val centerId: UUID,
    val createdByUserId: UUID,
    val deviceName: String,
    val deviceRole: DeviceRole,
    val codeHash: String,
    val status: ActivationCodeStatus,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val createdAt: Instant
)

data class RefreshTokenRecord(
    val id: UUID,
    val userId: UUID,
    val tokenHash: String,
    val expiresAt: Instant,
    val createdAt: Instant,
    val revokedAt: Instant?
)

data class AuditLogRecord(
    val id: UUID,
    val centerId: UUID?,
    val actorType: AuditActorType,
    val actorId: UUID?,
    val action: String,
    val entityType: String,
    val entityId: UUID?,
    val metadata: String,
    val ipAddress: String?,
    val createdAt: Instant
)
