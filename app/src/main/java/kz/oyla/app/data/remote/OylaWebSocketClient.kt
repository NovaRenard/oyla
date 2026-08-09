package kz.oyla.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kz.oyla.app.data.remote.dto.SessionWebSocketEvent
import kz.oyla.app.data.remote.dto.WhiteboardClientEvent

enum class SocketConnectionState {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED
}

sealed interface SocketEvent {
    data class ConnectionState(val state: SocketConnectionState) : SocketEvent
    data class ServerEvent(val event: SessionWebSocketEvent) : SocketEvent
}

class OylaWebSocketClient(
    private val baseUrl: String,
    private val client: HttpClient = HttpClient(OkHttp) { install(WebSockets) },
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }
) {
    private val outgoing = ConcurrentHashMap<String, Channel<String>>()

    /** Returns false while reconnecting, so the renderer keeps drawing disabled until server authority is reachable. */
    fun sendWhiteboardEvent(sessionId: String, event: WhiteboardClientEvent): Boolean =
        outgoing[sessionId]?.trySend(json.encodeToString(event))?.isSuccess == true

    fun observeSession(sessionId: String, token: String): Flow<SocketEvent> = channelFlow flow@{
        val delays = longArrayOf(1_000, 2_000, 5_000, 10_000)
        var attempt = 0
        while (isActive) {
            try {
                this@flow.send(
                    SocketEvent.ConnectionState(
                        if (attempt == 0) SocketConnectionState.CONNECTING else SocketConnectionState.RECONNECTING
                    )
                )
                client.webSocket(
                    urlString = "${baseUrl.trimEnd('/')}/ws/sessions/$sessionId?token=${token.encodeURLParameter()}"
                ) {
                    val messages = Channel<String>(Channel.BUFFERED)
                    this@OylaWebSocketClient.outgoing[sessionId] = messages
                    val sender = launch { for (message in messages) this@webSocket.send(Frame.Text(message)) }
                    this@flow.send(SocketEvent.ConnectionState(SocketConnectionState.CONNECTED))
                    attempt = 0
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                runCatching {
                                    json.decodeFromString<SessionWebSocketEvent>(frame.readText())
                                }.getOrNull()?.let { this@flow.send(SocketEvent.ServerEvent(it)) }
                            }
                        }
                    } finally {
                        this@OylaWebSocketClient.outgoing.remove(sessionId, messages)
                        messages.close(); sender.cancel()
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // The UI receives a reconnecting state below; technical details stay out of user messages.
            }
            if (!isActive) break
            this@flow.send(SocketEvent.ConnectionState(SocketConnectionState.RECONNECTING))
            delay(delays[attempt.coerceAtMost(delays.lastIndex)])
            attempt++
        }
        this@flow.send(SocketEvent.ConnectionState(SocketConnectionState.DISCONNECTED))
    }
}
