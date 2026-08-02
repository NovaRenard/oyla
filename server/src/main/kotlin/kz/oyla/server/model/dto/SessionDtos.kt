package kz.oyla.server.model.dto

import kotlinx.serialization.Serializable
import kz.oyla.server.model.SessionStatus

@Serializable
data class HealthResponse(val status: String = "ok")

@Serializable
data class CreateSessionRequest(
    val childName: String,
    val deviceId: String
)

@Serializable
data class CreateSessionResponse(
    val sessionId: String,
    val connectionCode: String,
    val status: SessionStatus,
    val specialistToken: String,
    val expiresAt: String
)

@Serializable
data class ConnectSessionRequest(
    val connectionCode: String,
    val deviceId: String
)

@Serializable
data class ConnectSessionResponse(
    val sessionId: String,
    val childName: String,
    val status: SessionStatus,
    val childToken: String
)

@Serializable
data class SessionStateResponse(
    val sessionId: String,
    val connectionCode: String,
    val childName: String,
    val status: SessionStatus,
    val childConnected: Boolean,
    val expiresAt: String
)

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String
)

@Serializable
data class StateSnapshotEvent(
    val type: String = "STATE_SNAPSHOT",
    val sessionId: String,
    val status: SessionStatus,
    val childConnected: Boolean
)

@Serializable
data class ChildConnectedEvent(
    val type: String = "CHILD_CONNECTED",
    val sessionId: String,
    val status: SessionStatus,
    val childConnected: Boolean = true
)

@Serializable
data class SessionCancelledEvent(
    val type: String = "SESSION_CANCELLED",
    val sessionId: String
)
