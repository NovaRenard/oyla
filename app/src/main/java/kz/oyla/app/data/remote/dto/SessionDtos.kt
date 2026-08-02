package kz.oyla.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateSessionRequest(val childName: String, val deviceId: String)

@Serializable
data class CreateSessionResponse(
    val sessionId: String,
    val connectionCode: String,
    val status: String,
    val specialistToken: String,
    val expiresAt: String
)

@Serializable
data class ConnectSessionRequest(val connectionCode: String, val deviceId: String)

@Serializable
data class ConnectSessionResponse(
    val sessionId: String,
    val childName: String,
    val status: String,
    val childToken: String
)

@Serializable
data class SessionStateResponse(
    val sessionId: String,
    val connectionCode: String,
    val childName: String,
    val status: String,
    val childConnected: Boolean,
    val expiresAt: String
)

@Serializable
data class ErrorResponse(val code: String, val message: String)

@Serializable
data class SessionWebSocketEvent(
    val type: String,
    val sessionId: String,
    val status: String? = null,
    val childConnected: Boolean? = null
)
