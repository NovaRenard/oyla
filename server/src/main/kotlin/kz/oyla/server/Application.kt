package kz.oyla.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.request.path
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.time.Clock
import java.time.Duration
import kotlinx.serialization.json.Json
import kz.oyla.server.config.DatabaseFactory
import kz.oyla.server.config.configureSaasAuthentication
import kz.oyla.server.config.respondSaasError
import kz.oyla.server.model.dto.ErrorResponse
import kz.oyla.server.repository.DatabaseSessionRepository
import kz.oyla.server.repository.DatabaseExerciseRepository
import kz.oyla.server.repository.ExerciseRepository
import kz.oyla.server.repository.InMemoryExerciseRepository
import kz.oyla.server.repository.InMemorySessionRepository
import kz.oyla.server.repository.SessionRepository
import kz.oyla.server.repository.SaasRepository
import kz.oyla.server.repository.DatabaseSaasRepository
import kz.oyla.server.repository.InMemorySaasRepository
import kz.oyla.server.routes.healthRoutes
import kz.oyla.server.routes.sessionRoutes
import kz.oyla.server.routes.sessionWebSocketRoutes
import kz.oyla.server.routes.saasRoutes
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.SessionEventHub
import kz.oyla.server.service.SessionService
import kz.oyla.server.service.ExerciseService
import kz.oyla.server.service.SaasConfig
import kz.oyla.server.service.SaasService
import kz.oyla.server.util.ActivationRateLimiter

fun main() {
    val port = (System.getenv("PORT") ?: "8080").toIntOrNull() ?: 8080
    embeddedServer(
        factory = Netty,
        host = "0.0.0.0",
        port = port,
        module = { module() }
    ).start(wait = true)
}

fun Application.module(
    sessionRepository: SessionRepository? = null,
    clock: Clock = Clock.systemUTC(),
    codeTtl: Duration = Duration.ofMinutes(
        (System.getenv("SESSION_CODE_TTL_MINUTES") ?: "30").toLongOrNull()?.coerceAtLeast(1) ?: 30
    ),
    exerciseRepository: ExerciseRepository? = null,
    saasRepository: SaasRepository? = null,
    saasConfig: SaasConfig = SaasConfig.fromEnvironment(),
    activationRateLimiter: ActivationRateLimiter? = null
) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    val usesInMemoryMvp = sessionRepository is InMemorySessionRepository
    if (sessionRepository == null || (saasRepository == null && !usesInMemoryMvp)) DatabaseFactory.initialize()
    val repository = sessionRepository ?: run {
        DatabaseSessionRepository()
    }
    val exercises = exerciseRepository ?: if (repository is InMemorySessionRepository) {
        InMemoryExerciseRepository()
    } else {
        DatabaseExerciseRepository()
    }
    val sessionService = SessionService(repository, clock = clock, codeTtl = codeTtl)
    val exerciseService = ExerciseService(exercises, clock)
    val eventHub = SessionEventHub(json)
    val tenants = saasRepository ?: if (repository is InMemorySessionRepository) InMemorySaasRepository() else DatabaseSaasRepository()
    val saasService = SaasService(tenants, saasConfig, clock = clock)
    val limiter = activationRateLimiter ?: ActivationRateLimiter(clock)

    install(CallLogging) {
        // Deliberately log only the path: WebSocket access tokens live in the query string.
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()}" }
    }
    install(ContentNegotiation) { json(json) }
    configureSaasAuthentication(saasService)
    install(WebSockets) {
        pingPeriodMillis = 20_000
        timeoutMillis = 30_000
        maxFrameSize = 32 * 1024L
        masking = false
    }
    install(StatusPages) {
        exception<ContentTransformationException> { call, _ ->
            if (call.isSaasPath()) call.respondSaasError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Некорректные данные запроса")
            else call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", "Некорректные данные запроса"))
        }
        exception<ApiException> { call, cause ->
            if (call.isSaasPath()) call.respondSaasError(HttpStatusCode.fromValue(cause.httpCode), cause.errorCode, cause.clientMessage)
            else call.respond(HttpStatusCode.fromValue(cause.httpCode), ErrorResponse(cause.errorCode, cause.clientMessage))
        }
        exception<Throwable> { call, cause ->
            this@module.log.error("Unhandled server error", cause)
            if (call.isSaasPath()) call.respondSaasError(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "Внутренняя ошибка сервера")
            else call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", "Внутренняя ошибка сервера"))
        }
    }
    routing {
        healthRoutes()
        sessionRoutes(sessionService, exerciseService, eventHub)
        sessionWebSocketRoutes(sessionService, exerciseService, eventHub, json)
        saasRoutes(saasService, limiter)
    }
}

private fun io.ktor.server.application.ApplicationCall.isSaasPath(): Boolean = request.path().let {
    it.startsWith("/api/v1/auth") || it.startsWith("/api/v1/centers") ||
        it.startsWith("/api/v1/devices") || it.startsWith("/api/v1/device-auth")
}
