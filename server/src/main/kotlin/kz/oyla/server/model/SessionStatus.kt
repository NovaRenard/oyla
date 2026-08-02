package kz.oyla.server.model

enum class SessionStatus {
    WAITING_FOR_CHILD,
    READY,
    COMPLETED,
    CANCELLED,
    EXPIRED
}
