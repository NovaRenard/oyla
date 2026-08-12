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
import kz.oyla.server.model.SessionStatus
import kz.oyla.server.model.dto.CreateActivationCodeRequest
import kz.oyla.server.model.dto.CreateChildRequest
import kz.oyla.server.model.dto.CreateSpecialistRequest
import kz.oyla.server.model.dto.LoginRequest
import kz.oyla.server.model.dto.LogoutRequest
import kz.oyla.server.model.dto.RefreshRequest
import kz.oyla.server.model.dto.RegisterCenterRequest
import kz.oyla.server.model.dto.UpdateCenterRequest
import kz.oyla.server.model.dto.UpdateDeviceRequest
import kz.oyla.server.model.dto.UpdateChildRequest
import kz.oyla.server.model.dto.UpdateSpecialistRequest
import kz.oyla.server.model.dto.CreateDeviceLessonRequest
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.SaasService
import kz.oyla.server.service.LessonService
import kz.oyla.server.service.ContentService
import kz.oyla.server.service.ExternalIntegrationService
import kz.oyla.server.util.ActivationRateLimiter

fun Route.saasRoutes(service: SaasService, lessons: LessonService, content: ContentService, activationRateLimiter: ActivationRateLimiter, integrations: ExternalIntegrationService) {
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

        route("/api/v1/children") {
            get {
                val principal = call.webPrincipal()
                call.respond(service.listChildren(principal.userId, principal.activeCenterId, call.request.queryParameters["status"], call.request.queryParameters["search"]))
            }
            post {
                val principal = call.webPrincipal()
                call.respond(HttpStatusCode.Created, service.createChild(principal.userId, principal.activeCenterId, call.receive<CreateChildRequest>(), call.clientIp()))
            }
            get("/{childId}") {
                val principal = call.webPrincipal(); call.respond(service.getChild(principal.userId, principal.activeCenterId, call.uuidParameter("childId")))
            }
            patch("/{childId}") {
                val principal = call.webPrincipal(); call.respond(service.updateChild(principal.userId, principal.activeCenterId, call.uuidParameter("childId"), call.receive<UpdateChildRequest>(), call.clientIp()))
            }
            post("/{childId}/archive") {
                val principal = call.webPrincipal(); call.respond(service.archiveChild(principal.userId, principal.activeCenterId, call.uuidParameter("childId"), false, call.clientIp()))
            }
            post("/{childId}/restore") {
                val principal = call.webPrincipal(); call.respond(service.archiveChild(principal.userId, principal.activeCenterId, call.uuidParameter("childId"), true, call.clientIp()))
            }
            get("/{childId}/lessons") {
                val principal = call.webPrincipal(); val context = service.requireCenterContext(principal.userId, principal.activeCenterId)
                val childId = call.uuidParameter("childId"); lessons.ensureChildInCenter(context, childId)
                call.respond(lessons.listForWeb(context, childId = childId, status = call.enumQuery<SessionStatus>("status")))
            }
        }

        route("/api/v1/integrations/crm") {
            get {
                val principal = call.webPrincipal(); val integration = integrations.getCrmIntegration(principal.userId, principal.activeCenterId)
                if (integration == null) call.respond(HttpStatusCode.NoContent) else call.respond(integration)
            }
            post {
                val principal = call.webPrincipal(); call.respond(HttpStatusCode.Created, integrations.createCrmIntegration(principal.userId, principal.activeCenterId, call.receive(), call.clientIp()))
            }
            patch {
                val principal = call.webPrincipal(); call.respond(integrations.updateCrmIntegration(principal.userId, principal.activeCenterId, call.receive(), call.clientIp()))
            }
            delete {
                val principal = call.webPrincipal(); integrations.disableCrmIntegration(principal.userId, principal.activeCenterId, call.clientIp()); call.respond(HttpStatusCode.NoContent)
            }
            post("/test") {
                val principal = call.webPrincipal(); call.respond(integrations.testConnection(principal.userId, principal.activeCenterId, call.receive(), call.clientIp()))
            }
            get("/children") {
                val principal = call.webPrincipal(); call.respond(integrations.previewChildren(principal.userId, principal.activeCenterId))
            }
            post("/import-children") {
                val principal = call.webPrincipal(); call.respond(integrations.importChildren(principal.userId, principal.activeCenterId, call.receive(), call.clientIp()))
            }
            post("/sync") {
                val principal = call.webPrincipal(); call.respond(integrations.syncChildren(principal.userId, principal.activeCenterId, call.clientIp()))
            }
        }

        route("/api/v1/specialists") {
            get {
                val principal = call.webPrincipal()
                call.respond(service.listSpecialists(principal.userId, principal.activeCenterId, call.request.queryParameters["status"], call.request.queryParameters["search"]))
            }
            post {
                val principal = call.webPrincipal()
                call.respond(HttpStatusCode.Created, service.createSpecialist(principal.userId, principal.activeCenterId, call.receive<CreateSpecialistRequest>(), call.clientIp()))
            }
            get("/{specialistId}") {
                val principal = call.webPrincipal(); call.respond(service.getSpecialist(principal.userId, principal.activeCenterId, call.uuidParameter("specialistId")))
            }
            patch("/{specialistId}") {
                val principal = call.webPrincipal(); call.respond(service.updateSpecialist(principal.userId, principal.activeCenterId, call.uuidParameter("specialistId"), call.receive<UpdateSpecialistRequest>(), call.clientIp()))
            }
            post("/{specialistId}/archive") {
                val principal = call.webPrincipal(); call.respond(service.archiveSpecialist(principal.userId, principal.activeCenterId, call.uuidParameter("specialistId"), false, call.clientIp()))
            }
            post("/{specialistId}/restore") {
                val principal = call.webPrincipal(); call.respond(service.archiveSpecialist(principal.userId, principal.activeCenterId, call.uuidParameter("specialistId"), true, call.clientIp()))
            }
            get("/{specialistId}/lessons") {
                val principal = call.webPrincipal(); val context = service.requireCenterContext(principal.userId, principal.activeCenterId)
                val specialistId = call.uuidParameter("specialistId"); lessons.ensureSpecialistInCenter(context, specialistId)
                call.respond(lessons.listForWeb(context, specialistId = specialistId, status = call.enumQuery<SessionStatus>("status")))
            }
        }

        route("/api/v1/lessons") {
            get {
                val principal = call.webPrincipal(); val context = service.requireCenterContext(principal.userId, principal.activeCenterId)
                val childId = call.request.queryParameters["childId"]?.let { runCatching { UUID.fromString(it) }.getOrElse { throw ApiException.validation("Некорректный идентификатор ребёнка") } }
                val specialistId = call.request.queryParameters["specialistId"]?.let { runCatching { UUID.fromString(it) }.getOrElse { throw ApiException.validation("Некорректный идентификатор специалиста") } }
                call.respond(lessons.listForWeb(context, childId, specialistId, call.enumQuery<SessionStatus>("status")))
            }
            get("/{lessonId}") {
                val principal = call.webPrincipal(); val context = service.requireCenterContext(principal.userId, principal.activeCenterId)
                call.respond(lessons.detailsForWeb(context, call.uuidParameter("lessonId")))
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

    authenticate("device-token") {
        route("/api/v1/device-data") {
            get("/children") { call.respond(service.deviceChildren(call.devicePrincipal().device)) }
            get("/specialists") { call.respond(service.deviceSpecialists(call.devicePrincipal().device)) }
            get("/lesson-templates") {
                val device = call.devicePrincipal().device
                call.respond(content.deviceTemplates(service.specialistDeviceCenter(device)))
            }
        }
        route("/api/v1/device-lessons") {
            post { call.respond(HttpStatusCode.Created, lessons.create(call.devicePrincipal().device, call.receive<CreateDeviceLessonRequest>())) }
            get("/current-assignment") {
                val assignment = lessons.childAssignment(call.devicePrincipal().device)
                if (assignment == null) call.respond(HttpStatusCode.NoContent) else call.respond(assignment)
            }
            get("/current") {
                val current = lessons.currentSpecialistLesson(call.devicePrincipal().device)
                if (current == null) call.respond(HttpStatusCode.NoContent) else call.respond(current)
            }
            get("/available-child-devices") { call.respond(lessons.availableChildDevices(call.devicePrincipal().device)) }
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
