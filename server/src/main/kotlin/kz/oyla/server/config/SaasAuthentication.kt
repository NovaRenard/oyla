package kz.oyla.server.config

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.Principal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import java.util.UUID
import kz.oyla.server.model.DeviceRecord
import kz.oyla.server.model.dto.ApiErrorBody
import kz.oyla.server.model.dto.SaasErrorResponse
import kz.oyla.server.service.SaasService

data class WebUserPrincipal(val userId: UUID, val activeCenterId: UUID?) : Principal
data class DeviceTokenPrincipal(val device: DeviceRecord) : Principal

fun Application.configureSaasAuthentication(service: SaasService) {
    install(Authentication) {
        jwt("web-jwt") {
            realm = "oyla-web"
            verifier(service.jwtVerifier)
            validate { credential ->
                if (credential.payload.getClaim("typ").asString() != "access") return@validate null
                val userId = runCatching { UUID.fromString(credential.payload.subject) }.getOrNull() ?: return@validate null
                val activeCenterId = credential.payload.getClaim("active_center_id").asString()
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                WebUserPrincipal(userId, activeCenterId)
            }
            challenge { _, _ ->
                call.respondSaasError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Необходима авторизация")
            }
        }
        register(DeviceTokenAuthenticationProvider(DeviceTokenAuthenticationProvider.Config("device-token", service)))
    }
}

private class DeviceTokenAuthenticationProvider(config: DeviceTokenAuthenticationProvider.Config) : AuthenticationProvider(config) {
    class Config(name: String, val service: SaasService) : AuthenticationProvider.Config(name)
    private val service = config.service

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val header = context.call.request.headers[HttpHeaders.Authorization]
        val token = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substringAfter(' ')?.trim()
        val device = token?.takeIf { it.isNotEmpty() }?.let { service.authenticateDeviceToken(it) }
        if (device != null) {
            context.principal(DeviceTokenPrincipal(device))
            return
        }
        context.challenge("device-token", AuthenticationFailedCause.InvalidCredentials) { challenge, call ->
            call.respondSaasError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Необходима авторизация устройства")
            challenge.complete()
        }
    }
}

suspend fun ApplicationCall.respondSaasError(status: HttpStatusCode, code: String, message: String, details: String? = null) {
    val requestId = request.headers["X-Request-ID"]?.takeIf { it.length in 1..128 } ?: UUID.randomUUID().toString()
    response.headers.append("X-Request-ID", requestId, safeOnly = false)
    respond(status, SaasErrorResponse(ApiErrorBody(code, message, details, requestId)))
}
