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
import kz.oyla.server.model.dto.ErrorResponse
import kz.oyla.server.repository.DatabaseSessionRepository
import kz.oyla.server.repository.SessionRepository
import kz.oyla.server.routes.healthRoutes
import kz.oyla.server.routes.sessionRoutes
import kz.oyla.server.routes.sessionWebSocketRoutes
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.SessionEventHub
import kz.oyla.server.service.SessionService

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
    )
) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    val repository = sessionRepository ?: run {
        DatabaseFactory.initialize()
        DatabaseSessionRepository()
    }
    val sessionService = SessionService(repository, clock = clock, codeTtl = codeTtl)
    val eventHub = SessionEventHub(json)

    install(CallLogging) {
        // Deliberately log only the path: WebSocket access tokens live in the query string.
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()}" }
    }
    install(ContentNegotiation) { json(json) }
    install(WebSockets) {
        pingPeriodMillis = 20_000
        timeoutMillis = 30_000
        maxFrameSize = 32 * 1024L
        masking = false
    }
    install(StatusPages) {
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", "Некорректные данные запроса"))
        }
        exception<ApiException> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.httpCode),
                ErrorResponse(cause.errorCode, cause.clientMessage)
            )
        }
        exception<Throwable> { call, cause ->
            this@module.log.error("Unhandled server error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("INTERNAL_ERROR", "Внутренняя ошибка сервера")
            )
        }
    }
    routing {
        healthRoutes()
        sessionRoutes(sessionService, eventHub)
        sessionWebSocketRoutes(sessionService, eventHub, json)
    }
}
