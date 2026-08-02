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
import kotlinx.serialization.json.Json
import kz.oyla.app.data.remote.dto.SessionWebSocketEvent

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
                    this@flow.send(SocketEvent.ConnectionState(SocketConnectionState.CONNECTED))
                    attempt = 0
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            runCatching {
                                json.decodeFromString<SessionWebSocketEvent>(frame.readText())
                            }.getOrNull()?.let { this@flow.send(SocketEvent.ServerEvent(it)) }
                        }
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
