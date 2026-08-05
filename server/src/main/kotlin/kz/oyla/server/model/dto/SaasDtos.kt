package kz.oyla.server.model.dto

import kotlinx.serialization.Serializable
import kz.oyla.server.model.ActivationCodeStatus
import kz.oyla.server.model.CenterStatus
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.DeviceStatus
import kz.oyla.server.model.MembershipRole
import kz.oyla.server.model.MembershipStatus
import kz.oyla.server.model.UserStatus

@Serializable data class RegisterCenterRequest(
    val centerName: String,
    val firstName: String,
    val lastName: String? = null,
    val email: String,
    val password: String
)
@Serializable data class LoginRequest(val email: String, val password: String)
// Web uses a HttpOnly cookie. The optional body field is retained for non-browser clients.
@Serializable data class RefreshRequest(val refreshToken: String? = null)
@Serializable data class LogoutRequest(val refreshToken: String? = null)
@Serializable data class UserDto(
    val id: String, val email: String, val firstName: String, val lastName: String? = null,
    val status: UserStatus, val lastLoginAt: String? = null
)
@Serializable data class CenterDto(
    val id: String, val name: String, val slug: String, val status: CenterStatus, val timezone: String
)
@Serializable data class CenterMembershipDto(
    val center: CenterDto, val role: MembershipRole, val status: MembershipStatus
)
@Serializable data class AuthResponse(
    val user: UserDto,
    val centers: List<CenterMembershipDto>,
    val activeCenter: CenterDto? = null,
    val accessToken: String,
    val refreshToken: String? = null,
    val accessTokenExpiresAt: String
)
@Serializable data class MeResponse(
    val user: UserDto, val centers: List<CenterMembershipDto>, val activeCenter: CenterDto? = null
)
@Serializable data class SelectCenterResponse(
    val activeCenter: CenterDto, val accessToken: String, val accessTokenExpiresAt: String
)
@Serializable data class UpdateCenterRequest(val name: String? = null, val timezone: String? = null)

@Serializable data class DeviceDto(
    val id: String,
    val name: String,
    val role: DeviceRole,
    val status: DeviceStatus,
    val appVersion: String? = null,
    val androidVersion: String? = null,
    val model: String? = null,
    val lastSeenAt: String? = null,
    val activatedAt: String? = null,
    val isOnline: Boolean
)
@Serializable data class CreateActivationCodeRequest(val deviceName: String, val deviceRole: DeviceRole)
@Serializable data class CreateActivationCodeResponse(
    val id: String,
    val activationCode: String,
    val expiresAt: String,
    val deviceName: String,
    val deviceRole: DeviceRole
)
@Serializable data class ActivationCodeDto(
    val id: String,
    val deviceName: String,
    val deviceRole: DeviceRole,
    val status: ActivationCodeStatus,
    val expiresAt: String,
    val createdAt: String
)
@Serializable data class UpdateDeviceRequest(
    val name: String? = null,
    val role: DeviceRole? = null,
    val status: DeviceStatus? = null
)

@Serializable data class ActivateDeviceRequest(
    val activationCode: String,
    val deviceUid: String,
    val appVersion: String? = null,
    val androidVersion: String? = null,
    val model: String? = null
)
@Serializable data class ActivateDeviceResponse(
    val deviceId: String,
    val centerId: String,
    val centerName: String,
    val deviceName: String,
    val deviceRole: DeviceRole,
    val deviceToken: String,
    val serverTime: String
)
@Serializable data class DeviceAuthMeResponse(
    val deviceId: String,
    val centerId: String,
    val centerName: String,
    val deviceName: String,
    val role: DeviceRole,
    val status: DeviceStatus,
    val serverTime: String
)
@Serializable data class DeviceHeartbeatRequest(
    val appVersion: String? = null,
    val androidVersion: String? = null,
    val model: String? = null
)

@Serializable data class ApiErrorBody(
    val code: String,
    val message: String,
    val details: String? = null,
    val requestId: String
)
@Serializable data class SaasErrorResponse(val error: ApiErrorBody)
