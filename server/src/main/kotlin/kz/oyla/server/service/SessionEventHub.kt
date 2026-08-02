package kz.oyla.server.service

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.dto.ChildConnectedEvent
import kz.oyla.server.model.dto.SessionCancelledEvent

class SessionEventHub(private val json: Json) {
    private data class Connection(
        val role: DeviceRole,
        val socket: DefaultWebSocketServerSession
    )

    private val connections = ConcurrentHashMap<UUID, MutableSet<Connection>>()

    fun register(sessionId: UUID, role: DeviceRole, socket: DefaultWebSocketServerSession) {
        val connection = Connection(role, socket)
        connections.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }.add(connection)
    }

    fun unregister(sessionId: UUID, socket: DefaultWebSocketServerSession) {
        connections[sessionId]?.let { set ->
            set.removeIf { it.socket === socket }
            if (set.isEmpty()) connections.remove(sessionId, set)
        }
    }

    suspend fun publishChildConnected(sessionId: UUID) {
        publishToRole(
            sessionId,
            DeviceRole.SPECIALIST,
            json.encodeToString(ChildConnectedEvent(sessionId = sessionId.toString(), status = kz.oyla.server.model.SessionStatus.READY))
        )
    }

    suspend fun publishSessionCancelled(sessionId: UUID) {
        publishToAll(sessionId, json.encodeToString(SessionCancelledEvent(sessionId = sessionId.toString())))
    }

    private suspend fun publishToRole(sessionId: UUID, role: DeviceRole, payload: String) {
        connections[sessionId]
            ?.filter { it.role == role }
            ?.forEach { connection -> runCatching { connection.socket.send(Frame.Text(payload)) } }
    }

    private suspend fun publishToAll(sessionId: UUID, payload: String) {
        connections[sessionId]?.forEach { connection ->
            runCatching { connection.socket.send(Frame.Text(payload)) }
        }
    }
}
