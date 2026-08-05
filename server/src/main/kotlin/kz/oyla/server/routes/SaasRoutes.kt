package kz.oyla.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID
import kz.oyla.server.config.WebUserPrincipal
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.DeviceStatus
import kz.oyla.server.model.dto.CreateActivationCodeRequest
import kz.oyla.server.model.dto.LoginRequest
import kz.oyla.server.model.dto.LogoutRequest
import kz.oyla.server.model.dto.RefreshRequest
import kz.oyla.server.model.dto.RegisterCenterRequest
import kz.oyla.server.model.dto.UpdateCenterRequest
import kz.oyla.server.model.dto.UpdateDeviceRequest
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.SaasService
import kz.oyla.server.util.ActivationRateLimiter

fun Route.saasRoutes(service: SaasService, activationRateLimiter: ActivationRateLimiter) {
    route("/api/v1/auth") {
        post("/register-center") {
            if (!service.publicRegistrationAllowed) throw ApiException.registrationDisabled()
            val auth = service.registerCenter(call.receive<RegisterCenterRequest>(), call.clientIp())
            call.setRefreshCookie(auth.refreshToken)
            call.respond(HttpStatusCode.Created, auth.redacted())
        }
        post("/login") {
            val auth = service.login(call.receive<LoginRequest>(), call.clientIp())
            call.setRefreshCookie(auth.refreshToken)
            call.respond(auth.redacted())
        }
        post("/refresh") {
            val request = call.receive<RefreshRequest>()
            val auth = service.refresh(request.copy(refreshToken = request.refreshToken ?: call.request.cookies[RefreshCookieName]))
            call.setRefreshCookie(auth.refreshToken)
            call.respond(auth.redacted())
        }
        post("/logout") {
            val request = call.receive<LogoutRequest>()
            service.logout(request.refreshToken ?: call.request.cookies[RefreshCookieName].orEmpty())
            call.clearRefreshCookie()
            call.respond(HttpStatusCode.NoContent)
        }
        authenticate("web-jwt") {
            get("/me") {
                val principal = call.webPrincipal()
                call.respond(service.me(principal.userId, principal.activeCenterId))
            }
        }
    }

    authenticate("web-jwt") {
        get("/api/v1/centers") { call.respond(service.listCenters(call.webPrincipal().userId)) }
        get("/api/v1/centers/current") {
            val principal = call.webPrincipal(); call.respond(service.currentCenter(principal.userId, principal.activeCenterId))
        }
        post("/api/v1/centers/{centerId}/select") {
            val principal = call.webPrincipal(); call.respond(service.selectCenter(principal.userId, call.uuidParameter("centerId")))
        }
        patch("/api/v1/centers/current") {
            val principal = call.webPrincipal(); call.respond(service.updateCurrentCenter(principal.userId, principal.activeCenterId, call.receive<UpdateCenterRequest>()))
        }

        route("/api/v1/devices") {
            get {
                val principal = call.webPrincipal()
                val role = call.enumQuery<DeviceRole>("role")
                val status = call.enumQuery<DeviceStatus>("status")
                val isOnline = call.booleanQuery("isOnline")
                call.respond(service.listDevices(principal.userId, principal.activeCenterId, role, status, isOnline))
            }
            get("/{deviceId}") {
                val principal = call.webPrincipal(); call.respond(service.getDevice(principal.userId, principal.activeCenterId, call.uuidParameter("deviceId")))
            }
            post("/activation-codes") {
                val principal = call.webPrincipal(); call.respond(HttpStatusCode.Created, service.createActivationCode(principal.userId, principal.activeCenterId, call.receive<CreateActivationCodeRequest>(), call.clientIp()))
            }
            get("/activation-codes") {
                val principal = call.webPrincipal(); call.respond(service.listActivationCodes(principal.userId, principal.activeCenterId))
            }
            delete("/activation-codes/{id}") {
                val principal = call.webPrincipal(); service.cancelActivationCode(principal.userId, principal.activeCenterId, call.uuidParameter("id"), call.clientIp()); call.respond(HttpStatusCode.NoContent)
            }
            patch("/{deviceId}") {
                val principal = call.webPrincipal(); call.respond(service.updateDevice(principal.userId, principal.activeCenterId, call.uuidParameter("deviceId"), call.receive<UpdateDeviceRequest>(), call.clientIp()))
            }
            post("/{deviceId}/unlink") {
                val principal = call.webPrincipal(); service.unlinkDevice(principal.userId, principal.activeCenterId, call.uuidParameter("deviceId"), call.clientIp()); call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    route("/api/v1/device-auth") {
        post("/activate") {
            val uid = runCatching { call.receive<kz.oyla.server.model.dto.ActivateDeviceRequest>() }.getOrElse { throw it }
            val ip = call.clientIp()
            if (!activationRateLimiter.allow("ip:$ip") || !activationRateLimiter.allow("uid:${uid.deviceUid.trim()}")) {
                service.recordRateLimitedActivation(ip)
                throw ApiException.rateLimited()
            }
            call.respond(HttpStatusCode.Created, service.activateDevice(uid, ip))
        }
        authenticate("device-token") {
            get("/me") { call.respond(service.deviceMe(call.devicePrincipal().device)) }
            post("/heartbeat") { call.respond(service.heartbeat(call.devicePrincipal().device, call.receive())) }
        }
    }
}

private fun ApplicationCall.webPrincipal(): WebUserPrincipal = principal<WebUserPrincipal>() ?: throw ApiException.unauthorized()
private fun ApplicationCall.devicePrincipal(): kz.oyla.server.config.DeviceTokenPrincipal = principal() ?: throw ApiException.unauthorized()
private fun ApplicationCall.uuidParameter(name: String): UUID = parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    ?: throw ApiException.validation("Некорректный идентификатор")
private fun ApplicationCall.clientIp(): String? = request.origin.remoteHost.takeIf { it.length <= 64 }
private inline fun <reified T : Enum<T>> ApplicationCall.enumQuery(name: String): T? = request.queryParameters[name]?.let { raw ->
    enumValues<T>().firstOrNull { it.name == raw.uppercase() } ?: throw ApiException.validation("Некорректный параметр $name")
}
private fun ApplicationCall.booleanQuery(name: String): Boolean? = request.queryParameters[name]?.let { raw ->
    raw.toBooleanStrictOrNull() ?: throw ApiException.validation("Некорректный параметр $name")
}

private const val RefreshCookieName = "oyla_refresh"

private fun ApplicationCall.setRefreshCookie(token: String?) {
    if (token.isNullOrBlank()) return
    response.cookies.append(Cookie(
        name = RefreshCookieName,
        value = token,
        path = "/api/v1/auth",
        httpOnly = true,
        // Local Vite development remains possible over HTTP. Set OYLA_COOKIE_SECURE=true
        // behind a TLS-terminating reverse proxy where Ktor sees an internal HTTP request.
        secure = System.getenv("OYLA_COOKIE_SECURE")?.toBooleanStrictOrNull()
            ?: request.local.scheme.equals("https", ignoreCase = true),
        extensions = mapOf("SameSite" to "Lax")
    ))
}

private fun ApplicationCall.clearRefreshCookie() {
    response.cookies.append(Cookie(name = RefreshCookieName, value = "", path = "/api/v1/auth", maxAge = 0, httpOnly = true))
}

private fun kz.oyla.server.model.dto.AuthResponse.redacted() = copy(refreshToken = null)
