package kz.oyla.server.model

import kotlinx.serialization.Serializable

@Serializable
enum class ExerciseStatus {
    PENDING,
    SHOWN,
    RUNNING,
    COMPLETED
}
