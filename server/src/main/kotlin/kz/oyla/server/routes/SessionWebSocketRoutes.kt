package kz.oyla.server.routes

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kz.oyla.server.model.dto.StateSnapshotEvent
import kz.oyla.server.service.SessionEventHub
import kz.oyla.server.service.SessionService

fun Route.sessionWebSocketRoutes(service: SessionService, eventHub: SessionEventHub, json: Json) {
    webSocket("/ws/sessions/{sessionId}") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val token = call.request.queryParameters["token"]
        val authorized = runCatching { service.authorize(sessionId, token) }.getOrElse {
            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return@webSocket
        }
        eventHub.register(authorized.session.id, authorized.role, this)
        service.markSocketPresence(authorized, connected = true)
        try {
            send(
                Frame.Text(
                    json.encodeToString(
                        StateSnapshotEvent(
                            sessionId = authorized.session.id.toString(),
                            status = authorized.session.status,
                            childConnected = authorized.session.childDeviceId != null
                        )
                    )
                )
            )
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    // Ignore unknown client messages, but treat them as a heartbeat.
                    frame.readText()
                    service.markSocketPresence(authorized, connected = true)
                }
            }
        } finally {
            eventHub.unregister(authorized.session.id, this)
            service.markSocketPresence(authorized, connected = false)
        }
    }
}
