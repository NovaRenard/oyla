package kz.oyla.server.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Maps the Flyway-managed sessions table for database tooling and diagnostics. */
object SessionsTable : Table("sessions") {
    val id = uuid("id")
    val connectionCode = varchar("connection_code", 4)
    val childName = varchar("child_name", 80)
    val status = varchar("status", 32)
    val specialistDeviceId = varchar("specialist_device_id", 255)
    val specialistToken = varchar("specialist_token", 255)
    val childDeviceId = varchar("child_device_id", 255).nullable()
    val childToken = varchar("child_token", 255).nullable()
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val connectedAt = timestamp("connected_at").nullable()
    val completedAt = timestamp("completed_at").nullable()
    val centerId = uuid("center_id").nullable()
    val specialistId = uuid("specialist_id").nullable()
    val specialistDeviceUuid = uuid("specialist_device_uuid").nullable()
    val startedAt = timestamp("started_at").nullable()
    val isManaged = bool("is_managed")

    override val primaryKey = PrimaryKey(id)
}
