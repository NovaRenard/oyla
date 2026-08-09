package kz.oyla.server.service

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
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
import kz.oyla.server.model.dto.ActivateDeviceRequest
import kz.oyla.server.model.dto.ActivateDeviceResponse
import kz.oyla.server.model.dto.ActivationCodeDto
import kz.oyla.server.model.dto.AuthResponse
import kz.oyla.server.model.dto.CenterDto
import kz.oyla.server.model.dto.CenterMembershipDto
import kz.oyla.server.model.dto.ChildDto
import kz.oyla.server.model.dto.CreateChildRequest
import kz.oyla.server.model.dto.CreateSpecialistRequest
import kz.oyla.server.model.dto.CreateActivationCodeRequest
import kz.oyla.server.model.dto.CreateActivationCodeResponse
import kz.oyla.server.model.dto.DeviceAuthMeResponse
import kz.oyla.server.model.dto.DeviceDto
import kz.oyla.server.model.dto.DeviceHeartbeatRequest
import kz.oyla.server.model.dto.LoginRequest
import kz.oyla.server.model.dto.MeResponse
import kz.oyla.server.model.dto.RefreshRequest
import kz.oyla.server.model.dto.RegisterCenterRequest
import kz.oyla.server.model.dto.SelectCenterResponse
import kz.oyla.server.model.dto.UpdateCenterRequest
import kz.oyla.server.model.dto.UpdateDeviceRequest
import kz.oyla.server.model.dto.UserDto
import kz.oyla.server.model.dto.SpecialistDto
import kz.oyla.server.model.dto.UpdateChildRequest
import kz.oyla.server.model.dto.UpdateSpecialistRequest
import kz.oyla.server.repository.DeviceActivationResult
import kz.oyla.server.repository.DeviceListFilter
import kz.oyla.server.repository.ChildListFilter
import kz.oyla.server.repository.SpecialistListFilter
import kz.oyla.server.repository.SaasRepository
import kz.oyla.server.util.SecretGenerator
import org.mindrot.jbcrypt.BCrypt

data class SaasConfig(
    val jwtSecret: String,
    val jwtIssuer: String,
    val jwtAudience: String,
    val accessTtl: Duration,
    val refreshTtl: Duration,
    val activationCodeTtl: Duration,
    val onlineWindow: Duration,
    val bcryptRounds: Int,
    val allowPublicRegistration: Boolean = false,
    val environment: String = "development"
) {
    companion object {
        fun fromEnvironment(): SaasConfig {
            val environment = System.getenv("OYLA_ENV")?.trim()?.lowercase(Locale.ROOT) ?: "development"
            val production = environment == "production"
            val jwtSecret = System.getenv("JWT_SECRET") ?: if (!production) "development-only-change-me-before-production-oyla" else missing("JWT_SECRET")
            val pepper = System.getenv("OYLA_SECRET_PEPPER") ?: if (!production) "development-only-change-me-before-production-pepper" else missing("OYLA_SECRET_PEPPER")
            // Force validation here, before Ktor starts accepting requests. SecretGenerator uses the same variable.
            require(pepper.length >= 32) { "OYLA_SECRET_PEPPER must be at least 32 characters" }
            require(!production || jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters in production" }
            require(!production || System.getenv("OYLA_COOKIE_SECURE")?.toBooleanStrictOrNull() == true) {
                "OYLA_COOKIE_SECURE=true is required in production"
            }
            return SaasConfig(
            jwtSecret = jwtSecret,
            jwtIssuer = System.getenv("JWT_ISSUER") ?: "oyla-server",
            jwtAudience = System.getenv("JWT_AUDIENCE") ?: "oyla-web",
            accessTtl = Duration.ofMinutes(envLong("JWT_ACCESS_TTL_MINUTES", 15, 1, 120)),
            refreshTtl = Duration.ofDays(envLong("REFRESH_TOKEN_TTL_DAYS", 30, 1, 180)),
            activationCodeTtl = Duration.ofMinutes(envLong("ACTIVATION_CODE_TTL_MINUTES", 10, 1, 60)),
            onlineWindow = Duration.ofSeconds(envLong("DEVICE_ONLINE_WINDOW_SECONDS", 90, 30, 600)),
            bcryptRounds = envLong("BCRYPT_LOG_ROUNDS", 12, 10, 14).toInt(),
            allowPublicRegistration = !production && (System.getenv("ALLOW_PUBLIC_REGISTRATION")?.toBooleanStrictOrNull() ?: false),
            environment = environment
        )
        }
        private fun missing(name: String): Nothing = throw IllegalStateException("$name must be set when OYLA_ENV=production")
        private fun envLong(name: String, default: Long, min: Long, max: Long) =
            (System.getenv(name)?.toLongOrNull() ?: default).coerceIn(min, max)
    }
}

data class AccessToken(val value: String, val expiresAt: Instant)
data class CenterContext(val user: UserRecord, val center: CenterRecord, val membership: CenterMembershipRecord)
data class AdminCenterResult(val centerId: UUID, val centerName: String, val ownerEmail: String)

class SaasService(
    private val repository: SaasRepository,
    private val config: SaasConfig = SaasConfig.fromEnvironment(),
    private val secrets: SecretGenerator = SecretGenerator(),
    private val clock: Clock = Clock.systemUTC()
) {
    private val algorithm = Algorithm.HMAC512(config.jwtSecret)
    val jwtVerifier: JWTVerifier = JWT.require(algorithm).withIssuer(config.jwtIssuer).withAudience(config.jwtAudience).build()
    val publicRegistrationAllowed: Boolean get() = config.allowPublicRegistration

    suspend fun registerCenter(request: RegisterCenterRequest, ipAddress: String?): AuthResponse {
        if (!config.allowPublicRegistration) throw ApiException.registrationDisabled()
        val created = provisionCenter(
            centerName = request.centerName,
            firstName = request.firstName,
            lastName = request.lastName,
            emailInput = request.email,
            password = request.password,
            timezoneInput = "Asia/Almaty",
            auditAction = "CENTER_CREATED",
            auditActorType = AuditActorType.USER,
            ipAddress = ipAddress
        )
        return issueAuthResponse(created.user, listOf(UserCenterMembership(created.membership, created.center)), created.center)
    }

    /** Shared provisioning path used by the disabled-by-default public endpoint and the admin CLI. */
    suspend fun createCenterByAdmin(
        centerName: String,
        firstName: String,
        lastName: String?,
        email: String,
        password: String,
        timezone: String
    ): AdminCenterResult {
        val created = provisionCenter(centerName, firstName, lastName, email, password, timezone,
            "CENTER_CREATED_BY_ADMIN", AuditActorType.SYSTEM, null)
        return AdminCenterResult(created.center.id, created.center.name, created.user.email)
    }

    /** Returns false for an unknown user so a production CLI need not disclose account existence. */
    suspend fun resetPasswordByAdmin(emailInput: String, password: String): Boolean {
        val email = emailInput.normalizeEmail()
        validatePassword(password)
        val user = repository.findUserByEmail(email) ?: return false
        val now = clock.instant()
        val centerId = repository.listCentersForUser(user.id).firstActiveCenter()?.id
        return repository.resetUserPassword(
            user.id,
            hashPassword(password),
            now,
            AuditLogRecord(UUID.randomUUID(), centerId, AuditActorType.SYSTEM, null, "USER_PASSWORD_RESET_BY_ADMIN", "USER", user.id, "{}", null, now)
        )
    }

    suspend fun login(request: LoginRequest, ipAddress: String?): AuthResponse {
        val email = request.email.normalizeEmail()
        val user = repository.findUserByEmail(email)
        if (user == null || user.status != UserStatus.ACTIVE || !verifyPassword(request.password, user.passwordHash)) throw ApiException.invalidCredentials()
        val now = clock.instant()
        repository.updateLastLogin(user.id, now)
        val updatedUser = user.copy(lastLoginAt = now, updatedAt = now)
        val centers = repository.listCentersForUser(user.id)
        val active = centers.firstActiveCenter()
        repository.recordAudit(AuditLogRecord(UUID.randomUUID(), active?.id, AuditActorType.USER, user.id, "USER_LOGIN", "USER", user.id, "{}", ipAddress, now))
        return issueAuthResponse(updatedUser, centers, active)
    }

    suspend fun refresh(request: RefreshRequest): AuthResponse {
        val refreshToken = request.refreshToken?.takeIf { it.isNotBlank() } ?: throw ApiException.unauthorized()
        val now = clock.instant()
        val oldHash = secrets.secretHash(refreshToken)
        val old = repository.findRefreshTokenByHash(oldHash)
        val user = old?.let { repository.findUserById(it.userId) }
        if (old == null || old.revokedAt != null || old.expiresAt <= now || user == null || user.status != UserStatus.ACTIVE) throw ApiException.unauthorized()
        val centers = repository.listCentersForUser(user.id)
        val active = centers.firstActiveCenter()
        val rawReplacement = secrets.nextOpaqueToken()
        val replacement = RefreshTokenRecord(UUID.randomUUID(), user.id, secrets.secretHash(rawReplacement), now.plus(config.refreshTtl), now, null)
        if (!repository.rotateRefreshToken(oldHash, replacement, now)) throw ApiException.unauthorized()
        return issueAuthResponse(user, centers, active, rawReplacement)
    }

    suspend fun logout(refreshToken: String) {
        if (refreshToken.isNotBlank()) repository.revokeRefreshToken(secrets.secretHash(refreshToken), clock.instant())
    }

    suspend fun me(userId: UUID, activeCenterId: UUID?): MeResponse {
        val user = requireActiveUser(userId)
        val centers = repository.listCentersForUser(user.id)
        val active = centers.firstOrNull {
            it.center.id == activeCenterId && it.center.status == CenterStatus.ACTIVE && it.membership.status == MembershipStatus.ACTIVE
        }?.center
        return MeResponse(user.toDto(), centers.map { it.toDto() }, active?.toDto())
    }

    suspend fun listCenters(userId: UUID): List<CenterMembershipDto> {
        requireActiveUser(userId)
        return repository.listCentersForUser(userId).map { it.toDto() }
    }

    suspend fun selectCenter(userId: UUID, centerId: UUID): SelectCenterResponse {
        val user = requireActiveUser(userId)
        // Check membership before the center row so a guessed UUID cannot distinguish a foreign center from a missing one.
        val membership = repository.findMembership(centerId, user.id)
            ?.takeIf { it.status == MembershipStatus.ACTIVE } ?: throw ApiException.membershipNotFound()
        val center = repository.findCenter(membership.centerId)?.takeIf { it.status == CenterStatus.ACTIVE } ?: throw ApiException.membershipNotFound()
        val access = issueAccessToken(user, center)
        return SelectCenterResponse(center.toDto(), access.value, access.expiresAt.toString())
    }

    suspend fun currentCenter(userId: UUID, centerId: UUID?): CenterDto = requireCenterContext(userId, centerId).center.toDto()

    suspend fun updateCurrentCenter(userId: UUID, centerId: UUID?, request: UpdateCenterRequest): CenterDto {
        val context = requireCenterContext(userId, centerId, setOf(MembershipRole.OWNER, MembershipRole.ADMIN))
        val name = request.name?.cleanRequired(160, "Название центра") ?: context.center.name
        val timezone = request.timezone?.cleanRequired(64, "Часовой пояс") ?: context.center.timezone
        if (runCatching { ZoneId.of(timezone) }.isFailure) throw ApiException.validation("Некорректный часовой пояс")
        return (repository.updateCenter(context.center.id, name, timezone, clock.instant()) ?: throw ApiException.centerNotFound()).toDto()
    }

    suspend fun listDevices(userId: UUID, centerId: UUID?, role: DeviceRole?, status: DeviceStatus?, isOnline: Boolean?): List<DeviceDto> {
        val context = requireCenterContext(userId, centerId)
        val now = clock.instant()
        return repository.listDevices(context.center.id, DeviceListFilter(role, status, now.minus(config.onlineWindow), isOnline)).map { it.toDto(now) }
    }

    suspend fun getDevice(userId: UUID, centerId: UUID?, deviceId: UUID): DeviceDto {
        val context = requireCenterContext(userId, centerId)
        return (repository.findDevice(context.center.id, deviceId) ?: throw ApiException.deviceNotFound()).toDto(clock.instant())
    }

    suspend fun createActivationCode(userId: UUID, centerId: UUID?, request: CreateActivationCodeRequest, ipAddress: String?): CreateActivationCodeResponse {
        val context = requireCenterContext(userId, centerId, setOf(MembershipRole.OWNER, MembershipRole.ADMIN))
        val deviceName = request.deviceName.cleanRequired(160, "Название устройства")
        val now = clock.instant()
        repeat(10) {
            val code = secrets.nextActivationCode()
            val record = DeviceActivationCodeRecord(UUID.randomUUID(), context.center.id, context.user.id, deviceName, request.deviceRole,
                secrets.secretHash(code), ActivationCodeStatus.PENDING, now.plus(config.activationCodeTtl), null, now)
            val audit = AuditLogRecord(UUID.randomUUID(), context.center.id, AuditActorType.USER, context.user.id, "ACTIVATION_CODE_CREATED", "DEVICE_ACTIVATION_CODE", record.id, "{}", ipAddress, now)
            if (repository.createActivationCode(record, audit)) return CreateActivationCodeResponse(record.id.toString(), code, record.expiresAt.toString(), record.deviceName, record.deviceRole)
        }
        throw ApiException.conflict("Не удалось создать код активации")
    }

    suspend fun listActivationCodes(userId: UUID, centerId: UUID?): List<ActivationCodeDto> {
        val context = requireCenterContext(userId, centerId, setOf(MembershipRole.OWNER, MembershipRole.ADMIN))
        return repository.listActiveActivationCodes(context.center.id, clock.instant()).map { it.toDto() }
    }

    suspend fun cancelActivationCode(userId: UUID, centerId: UUID?, codeId: UUID, ipAddress: String?) {
        val context = requireCenterContext(userId, centerId, setOf(MembershipRole.OWNER, MembershipRole.ADMIN))
        val now = clock.instant()
        val audit = AuditLogRecord(UUID.randomUUID(), context.center.id, AuditActorType.USER, context.user.id, "ACTIVATION_CODE_CANCELLED", "DEVICE_ACTIVATION_CODE", codeId, "{}", ipAddress, now)
        if (!repository.cancelActivationCode(context.center.id, codeId, now, audit)) throw ApiException.invalidActivationCode()
    }

    suspend fun updateDevice(userId: UUID, centerId: UUID?, deviceId: UUID, request: UpdateDeviceRequest, ipAddress: String?): DeviceDto {
        val context = requireCenterContext(userId, centerId, setOf(MembershipRole.OWNER, MembershipRole.ADMIN))
        val current = repository.findDevice(context.center.id, deviceId) ?: throw ApiException.deviceNotFound()
        val name = request.name?.cleanRequired(160, "Название устройства") ?: current.name
        if (request.status == DeviceStatus.UNLINKED) throw ApiException.validation("Для отвязки устройства используйте отдельный endpoint")
        if (request.role != null && request.role != current.role && repository.isDeviceInActiveSession(current.id)) {
            throw ApiException.conflict("Нельзя изменить роль устройства во время активного занятия")
        }
        val updated = current.copy(name = name, role = request.role ?: current.role, status = request.status ?: current.status, updatedAt = clock.instant())
        if (!repository.updateDevice(updated)) throw ApiException.deviceNotFound()
        val action = when {
            current.status != DeviceStatus.BLOCKED && updated.status == DeviceStatus.BLOCKED -> "DEVICE_BLOCKED"
            current.status == DeviceStatus.BLOCKED && updated.status == DeviceStatus.ACTIVE -> "DEVICE_UNBLOCKED"
            else -> "DEVICE_UPDATED"
        }
        repository.recordAudit(AuditLogRecord(UUID.randomUUID(), context.center.id, AuditActorType.USER, context.user.id, action, "DEVICE", updated.id, "{}", ipAddress, updated.updatedAt))
        return updated.toDto(clock.instant())
    }

    suspend fun unlinkDevice(userId: UUID, centerId: UUID?, deviceId: UUID, ipAddress: String?) {
        val context = requireCenterContext(userId, centerId, setOf(MembershipRole.OWNER, MembershipRole.ADMIN))
        val now = clock.instant()
        val audit = AuditLogRecord(UUID.randomUUID(), context.center.id, AuditActorType.USER, context.user.id, "DEVICE_UNLINKED", "DEVICE", deviceId, "{}", ipAddress, now)
        repository.unlinkDevice(context.center.id, deviceId, now, audit) ?: throw ApiException.deviceNotFound()
    }

    suspend fun activateDevice(request: ActivateDeviceRequest, ipAddress: String?): ActivateDeviceResponse {
        val code = secrets.normalizeActivationCode(request.activationCode)
        val uid = request.deviceUid.trim()
        if (code.length != 8 || uid.isEmpty() || uid.length > 255) {
            failedActivation(ipAddress)
            throw ApiException.invalidActivationCode()
        }
        val now = clock.instant()
        val rawDeviceToken = secrets.nextOpaqueToken()
        return when (val result = repository.activateDevice(secrets.secretHash(code), uid, secrets.secretHash(rawDeviceToken),
            request.appVersion?.cleanOptional(80, "Версия приложения"), request.androidVersion?.cleanOptional(80, "Версия Android"), request.model?.cleanOptional(160, "Модель"), now, ipAddress)) {
            is DeviceActivationResult.Activated -> ActivateDeviceResponse(result.device.id.toString(), result.center.id.toString(), result.center.name,
                result.device.name, result.device.role, rawDeviceToken, now.toString())
            DeviceActivationResult.AlreadyActivated -> {
                failedActivation(ipAddress)
                throw ApiException.deviceAlreadyActivated()
            }
            DeviceActivationResult.DeviceBlocked -> {
                failedActivation(ipAddress)
                throw ApiException.deviceBlocked()
            }
            else -> {
                failedActivation(ipAddress)
                // One public response avoids using activation-code status as an enumeration oracle.
                throw ApiException.invalidActivationCode()
            }
        }
    }

    suspend fun recordRateLimitedActivation(ipAddress: String?) = repository.recordAudit(
        AuditLogRecord(UUID.randomUUID(), null, AuditActorType.SYSTEM, null, "DEVICE_ACTIVATION_RATE_LIMITED", "DEVICE_ACTIVATION_CODE", null, "{}", ipAddress, clock.instant())
    )

    suspend fun authenticateDeviceToken(rawToken: String): DeviceRecord? =
        rawToken.takeIf { it.isNotBlank() }?.let { repository.findDeviceByTokenHash(secrets.secretHash(it)) }

    suspend fun deviceMe(device: DeviceRecord): DeviceAuthMeResponse {
        val current = requireActiveDevice(device)
        val center = repository.findCenter(current.centerId) ?: throw ApiException.centerNotFound()
        return DeviceAuthMeResponse(current.id.toString(), center.id.toString(), center.name, current.name, current.role, current.status, clock.instant().toString())
    }

    suspend fun heartbeat(device: DeviceRecord, request: DeviceHeartbeatRequest): DeviceAuthMeResponse {
        val current = requireActiveDevice(device)
        val now = clock.instant()
        val updated = repository.heartbeatDevice(current.id, request.appVersion?.cleanOptional(80, "Версия приложения"), request.androidVersion?.cleanOptional(80, "Версия Android"), request.model?.cleanOptional(160, "Модель"), now) ?: throw ApiException.deviceNotFound()
        val center = repository.findCenter(updated.centerId) ?: throw ApiException.centerNotFound()
        return DeviceAuthMeResponse(updated.id.toString(), center.id.toString(), center.name, updated.name, updated.role, updated.status, now.toString())
    }

    suspend fun listChildren(userId: UUID, centerId: UUID?, status: String?, search: String?): List<ChildDto> {
        val context = requireCenterContext(userId, centerId)
        return repository.listChildren(context.center.id, ChildListFilter(parseChildStatus(status), cleanSearch(search))).map { it.toDto() }
    }

    suspend fun getChild(userId: UUID, centerId: UUID?, childId: UUID): ChildDto {
        val context = requireCenterContext(userId, centerId)
        return (repository.findChild(context.center.id, childId) ?: throw ApiException.childNotFound()).toDto()
    }

    suspend fun createChild(userId: UUID, centerId: UUID?, request: CreateChildRequest, ipAddress: String?): ChildDto {
        val context = requireCenterContext(userId, centerId, managementRoles)
        val now = clock.instant()
        val child = ChildRecord(UUID.randomUUID(), context.center.id, request.firstName.cleanRequired(100, "Имя"), request.lastName?.cleanOptional(100, "Фамилия"), request.birthDate.parseBirthDate(), ChildStatus.ACTIVE, now, now)
        repository.createChild(child)
        audit(context, "CHILD_CREATED", "CHILD", child.id, ipAddress, now)
        return child.toDto()
    }

    suspend fun updateChild(userId: UUID, centerId: UUID?, childId: UUID, request: UpdateChildRequest, ipAddress: String?): ChildDto {
        val context = requireCenterContext(userId, centerId, managementRoles)
        val current = repository.findChild(context.center.id, childId) ?: throw ApiException.childNotFound()
        val now = clock.instant()
        val updated = current.copy(
            firstName = request.firstName?.cleanRequired(100, "Имя") ?: current.firstName,
            lastName = request.lastName.patchOptional(current.lastName, 100, "Фамилия"),
            birthDate = request.birthDate.patchBirthDate(current.birthDate),
            updatedAt = now
        )
        if (!repository.updateChild(updated)) throw ApiException.childNotFound()
        audit(context, "CHILD_UPDATED", "CHILD", updated.id, ipAddress, now)
        return updated.toDto()
    }

    suspend fun archiveChild(userId: UUID, centerId: UUID?, childId: UUID, restore: Boolean, ipAddress: String?): ChildDto {
        val context = requireCenterContext(userId, centerId, managementRoles)
        val current = repository.findChild(context.center.id, childId) ?: throw ApiException.childNotFound()
        val target = if (restore) ChildStatus.ACTIVE else ChildStatus.ARCHIVED
        val now = clock.instant()
        val updated = current.copy(status = target, updatedAt = now)
        if (!repository.updateChild(updated)) throw ApiException.childNotFound()
        audit(context, if (restore) "CHILD_RESTORED" else "CHILD_ARCHIVED", "CHILD", updated.id, ipAddress, now)
        return updated.toDto()
    }

    suspend fun listSpecialists(userId: UUID, centerId: UUID?, status: String?, search: String?): List<SpecialistDto> {
        val context = requireCenterContext(userId, centerId)
        return repository.listSpecialists(context.center.id, SpecialistListFilter(parseSpecialistStatus(status), cleanSearch(search))).map { it.toDto() }
    }

    suspend fun getSpecialist(userId: UUID, centerId: UUID?, specialistId: UUID): SpecialistDto {
        val context = requireCenterContext(userId, centerId)
        return (repository.findSpecialist(context.center.id, specialistId) ?: throw ApiException.specialistNotFound()).toDto()
    }

    suspend fun createSpecialist(userId: UUID, centerId: UUID?, request: CreateSpecialistRequest, ipAddress: String?): SpecialistDto {
        val context = requireCenterContext(userId, centerId, managementRoles)
        val now = clock.instant()
        val specialist = SpecialistRecord(UUID.randomUUID(), context.center.id, request.firstName.cleanRequired(100, "Имя"), request.lastName?.cleanOptional(100, "Фамилия"), request.specialization?.cleanOptional(160, "Специализация"), SpecialistStatus.ACTIVE, now, now)
        repository.createSpecialist(specialist)
        audit(context, "SPECIALIST_CREATED", "SPECIALIST", specialist.id, ipAddress, now)
        return specialist.toDto()
    }

    suspend fun updateSpecialist(userId: UUID, centerId: UUID?, specialistId: UUID, request: UpdateSpecialistRequest, ipAddress: String?): SpecialistDto {
        val context = requireCenterContext(userId, centerId, managementRoles)
        val current = repository.findSpecialist(context.center.id, specialistId) ?: throw ApiException.specialistNotFound()
        val now = clock.instant()
        val updated = current.copy(
            firstName = request.firstName?.cleanRequired(100, "Имя") ?: current.firstName,
            lastName = request.lastName.patchOptional(current.lastName, 100, "Фамилия"),
            specialization = request.specialization.patchOptional(current.specialization, 160, "Специализация"),
            updatedAt = now
        )
        if (!repository.updateSpecialist(updated)) throw ApiException.specialistNotFound()
        audit(context, "SPECIALIST_UPDATED", "SPECIALIST", updated.id, ipAddress, now)
        return updated.toDto()
    }

    suspend fun archiveSpecialist(userId: UUID, centerId: UUID?, specialistId: UUID, restore: Boolean, ipAddress: String?): SpecialistDto {
        val context = requireCenterContext(userId, centerId, managementRoles)
        val current = repository.findSpecialist(context.center.id, specialistId) ?: throw ApiException.specialistNotFound()
        val target = if (restore) SpecialistStatus.ACTIVE else SpecialistStatus.ARCHIVED
        val now = clock.instant()
        val updated = current.copy(status = target, updatedAt = now)
        if (!repository.updateSpecialist(updated)) throw ApiException.specialistNotFound()
        audit(context, if (restore) "SPECIALIST_RESTORED" else "SPECIALIST_ARCHIVED", "SPECIALIST", updated.id, ipAddress, now)
        return updated.toDto()
    }

    suspend fun deviceChildren(device: DeviceRecord): List<ChildDto> {
        val active = requireActiveDevice(device)
        requireSpecialistDevice(active)
        return repository.listChildren(active.centerId, ChildListFilter(ChildStatus.ACTIVE)).map { it.toDto() }
    }

    suspend fun deviceSpecialists(device: DeviceRecord): List<SpecialistDto> {
        val active = requireActiveDevice(device)
        requireSpecialistDevice(active)
        return repository.listSpecialists(active.centerId, SpecialistListFilter(SpecialistStatus.ACTIVE)).map { it.toDto() }
    }

    /** Keeps device-data endpoints subject to the same revocation and centre-status checks. */
    suspend fun specialistDeviceCenter(device: DeviceRecord): UUID {
        val active = requireActiveDevice(device)
        requireSpecialistDevice(active)
        return active.centerId
    }

    suspend fun deviceContentCenter(device: DeviceRecord): UUID = requireActiveDevice(device).centerId

    suspend fun requireCenterContext(userId: UUID, activeCenterId: UUID?, roles: Set<MembershipRole> = emptySet()): CenterContext {
        val user = requireActiveUser(userId)
        val centerId = activeCenterId ?: throw ApiException.membershipNotFound()
        val center = repository.findCenter(centerId) ?: throw ApiException.centerNotFound()
        if (center.status != CenterStatus.ACTIVE) throw ApiException.forbidden()
        val membership = repository.findMembership(centerId, user.id)
            ?.takeIf { it.status == MembershipStatus.ACTIVE } ?: throw ApiException.membershipNotFound()
        if (roles.isNotEmpty() && membership.role !in roles) throw ApiException.forbidden()
        return CenterContext(user, center, membership)
    }

    fun issueAccessToken(user: UserRecord, activeCenter: CenterRecord?): AccessToken {
        val now = clock.instant(); val expiresAt = now.plus(config.accessTtl)
        val builder = JWT.create().withIssuer(config.jwtIssuer).withAudience(config.jwtAudience).withSubject(user.id.toString())
            .withClaim("typ", "access").withIssuedAt(Date.from(now)).withExpiresAt(Date.from(expiresAt))
        if (activeCenter != null) builder.withClaim("active_center_id", activeCenter.id.toString())
        return AccessToken(builder.sign(algorithm), expiresAt)
    }

    private suspend fun issueAuthResponse(user: UserRecord, centers: List<UserCenterMembership>, activeCenter: CenterRecord?, rawRefreshToken: String? = null): AuthResponse {
        val access = issueAccessToken(user, activeCenter)
        val refresh = rawRefreshToken ?: secrets.nextOpaqueToken()
        if (rawRefreshToken == null) repository.createRefreshToken(RefreshTokenRecord(UUID.randomUUID(), user.id, secrets.secretHash(refresh), clock.instant().plus(config.refreshTtl), clock.instant(), null))
        return AuthResponse(user.toDto(), centers.map { it.toDto() }, activeCenter?.toDto(), access.value, refresh, access.expiresAt.toString())
    }
    private suspend fun provisionCenter(
        centerName: String,
        firstName: String,
        lastName: String?,
        emailInput: String,
        password: String,
        timezoneInput: String,
        auditAction: String,
        auditActorType: AuditActorType,
        ipAddress: String?
    ): ProvisionedCenter {
        val name = centerName.cleanRequired(160, "Название центра")
        val ownerFirstName = firstName.cleanRequired(100, "Имя")
        val ownerLastName = lastName?.cleanOptional(100, "Фамилия")
        val email = emailInput.normalizeEmail()
        val timezone = timezoneInput.cleanRequired(64, "Часовой пояс")
        if (runCatching { ZoneId.of(timezone) }.isFailure) throw ApiException.validation("Некорректный часовой пояс")
        validatePassword(password)
        val now = clock.instant()
        val user = UserRecord(UUID.randomUUID(), email, hashPassword(password), ownerFirstName, ownerLastName, UserStatus.ACTIVE, now, now, null)
        val center = CenterRecord(UUID.randomUUID(), name, slugFor(name), CenterStatus.ACTIVE, timezone, now, now)
        val membership = CenterMembershipRecord(UUID.randomUUID(), center.id, user.id, MembershipRole.OWNER, MembershipStatus.ACTIVE, now, now)
        val audit = AuditLogRecord(UUID.randomUUID(), center.id, auditActorType, if (auditActorType == AuditActorType.USER) user.id else null,
            auditAction, "CENTER", center.id, "{}", ipAddress, now)
        if (!repository.registerCenter(center, user, membership, audit)) throw ApiException.conflict("Пользователь с таким email уже существует")
        return ProvisionedCenter(user, center, membership)
    }

    private suspend fun requireActiveUser(userId: UUID): UserRecord = repository.findUserById(userId)?.takeIf { it.status == UserStatus.ACTIVE } ?: throw ApiException.unauthorized()
    private suspend fun requireActiveDevice(principalDevice: DeviceRecord): DeviceRecord {
        val device = repository.findDevice(principalDevice.centerId, principalDevice.id) ?: throw ApiException.deviceUnlinked()
        when (device.status) {
            DeviceStatus.ACTIVE -> Unit
            DeviceStatus.BLOCKED -> throw ApiException.deviceBlocked()
            DeviceStatus.UNLINKED -> throw ApiException.deviceUnlinked()
        }
        if (device.tokenRevokedAt != null) throw ApiException.deviceUnlinked()
        val center = repository.findCenter(device.centerId) ?: throw ApiException.deviceUnlinked()
        if (center.status != CenterStatus.ACTIVE) throw ApiException.forbidden()
        return device
    }

    private fun requireSpecialistDevice(device: DeviceRecord) {
        if (device.role != DeviceRole.SPECIALIST) throw ApiException.forbidden()
    }
    private suspend fun audit(context: CenterContext, action: String, entityType: String, entityId: UUID, ipAddress: String?, now: Instant) =
        repository.recordAudit(AuditLogRecord(UUID.randomUUID(), context.center.id, AuditActorType.USER, context.user.id, action, entityType, entityId, "{}", ipAddress, now))
    private fun parseChildStatus(raw: String?): ChildStatus? = when (raw?.uppercase()) {
        null, "ACTIVE" -> ChildStatus.ACTIVE
        "ARCHIVED" -> ChildStatus.ARCHIVED
        "ALL" -> null
        else -> throw ApiException.validation("Некорректный параметр status")
    }
    private fun parseSpecialistStatus(raw: String?): SpecialistStatus? = when (raw?.uppercase()) {
        null, "ACTIVE" -> SpecialistStatus.ACTIVE
        "ARCHIVED" -> SpecialistStatus.ARCHIVED
        "ALL" -> null
        else -> throw ApiException.validation("Некорректный параметр status")
    }
    private fun cleanSearch(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > 100) throw ApiException.validation("Поисковый запрос слишком длинный") }
    private fun String?.patchOptional(current: String?, maxLength: Int, label: String): String? {
        if (this == null) return current
        val cleaned = trim()
        if (cleaned.length > maxLength) throw ApiException.validation("$label заполнено некорректно")
        return cleaned.takeIf { it.isNotEmpty() }
    }
    private fun String?.parseBirthDate(): java.time.LocalDate? = this?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { java.time.LocalDate.parse(it) }.getOrElse { throw ApiException.validation("Некорректная дата рождения") }
    }
    private fun String?.patchBirthDate(current: java.time.LocalDate?): java.time.LocalDate? = if (this == null) current else parseBirthDate()
    private suspend fun failedActivation(ipAddress: String?) = repository.recordAudit(
        AuditLogRecord(UUID.randomUUID(), null, AuditActorType.SYSTEM, null, "DEVICE_ACTIVATION_FAILED", "DEVICE_ACTIVATION_CODE", null, "{\"reason\":\"invalid_code\"}", ipAddress, clock.instant())
    )
    private suspend fun hashPassword(value: String): String = withContext(Dispatchers.IO) { BCrypt.hashpw(value, BCrypt.gensalt(config.bcryptRounds)) }
    private suspend fun verifyPassword(value: String, hash: String): Boolean = withContext(Dispatchers.IO) { runCatching { BCrypt.checkpw(value, hash) }.getOrDefault(false) }
    private fun validatePassword(value: String) { if (value.length !in 8..128) throw ApiException.validation("Пароль должен содержать от 8 до 128 символов") }
    private fun String.normalizeEmail(): String {
        val normalized = trim().lowercase(Locale.ROOT)
        if (normalized.length !in 3..254 || !normalized.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) throw ApiException.validation("Некорректный email")
        return normalized
    }
    private fun String.cleanRequired(maxLength: Int, label: String): String = trim().also { if (it.isEmpty() || it.length > maxLength) throw ApiException.validation("$label заполнено некорректно") }
    private fun String.cleanOptional(maxLength: Int, label: String): String? = trim().takeIf { it.isNotEmpty() }?.also { if (it.length > maxLength) throw ApiException.validation("$label заполнено некорректно") }
    private fun slugFor(name: String): String {
        val base = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(120).ifBlank { "center" }
        return "$base-${UUID.randomUUID().toString().take(8)}"
    }
    private fun List<UserCenterMembership>.firstActiveCenter(): CenterRecord? = firstOrNull { it.membership.status == MembershipStatus.ACTIVE && it.center.status == CenterStatus.ACTIVE }?.center
    private fun UserRecord.toDto() = UserDto(id.toString(), email, firstName, lastName, status, lastLoginAt?.toString())
    private fun CenterRecord.toDto() = CenterDto(id.toString(), name, slug, status, timezone)
    private fun UserCenterMembership.toDto() = CenterMembershipDto(center.toDto(), membership.role, membership.status)
    private fun DeviceRecord.toDto(now: Instant) = DeviceDto(id.toString(), name, role, status, appVersion, androidVersion, model, lastSeenAt?.toString(), activatedAt?.toString(), lastSeenAt?.isAfter(now.minus(config.onlineWindow)) == true)
    private fun DeviceActivationCodeRecord.toDto() = ActivationCodeDto(id.toString(), deviceName, deviceRole, status, expiresAt.toString(), createdAt.toString())
    private fun ChildRecord.toDto() = ChildDto(id.toString(), firstName, lastName, birthDate?.toString(), status, createdAt.toString(), updatedAt.toString())
    private fun SpecialistRecord.toDto() = SpecialistDto(id.toString(), firstName, lastName, specialization, status, createdAt.toString(), updatedAt.toString())

    private companion object {
        val managementRoles = setOf(MembershipRole.OWNER, MembershipRole.ADMIN)
    }

    private data class ProvisionedCenter(val user: UserRecord, val center: CenterRecord, val membership: CenterMembershipRecord)
}
