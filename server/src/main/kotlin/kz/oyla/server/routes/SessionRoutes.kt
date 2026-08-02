package kz.oyla.server.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kz.oyla.server.model.dto.ConnectSessionRequest
import kz.oyla.server.model.dto.CreateSessionRequest
import kz.oyla.server.service.SessionEventHub
import kz.oyla.server.service.SessionService

fun Route.sessionRoutes(service: SessionService, eventHub: SessionEventHub) {
    route("/api/v1/sessions") {
        post {
            val result = service.create(call.receive<CreateSessionRequest>())
            call.respond(HttpStatusCode.Created, result)
        }
        post("/connect") {
            val result = service.connect(call.receive<ConnectSessionRequest>())
            eventHub.publishChildConnected(java.util.UUID.fromString(result.sessionId))
            call.respond(result)
        }
        get("/{sessionId}") {
            val state = service.getState(
                sessionId = call.parameters["sessionId"].orEmpty(),
                token = call.bearerToken()
            )
            call.respond(state)
        }
        post("/{sessionId}/cancel") {
            val cancelled = service.cancel(
                sessionId = call.parameters["sessionId"].orEmpty(),
                token = call.bearerToken()
            )
            eventHub.publishSessionCancelled(cancelled.id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        ?.trim()
