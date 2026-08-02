package kz.oyla.server.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Maps transient device presence records; schema creation is performed by Flyway. */
object DeviceConnectionsTable : Table("device_connections") {
    val id = uuid("id")
    val sessionId = uuid("session_id") references SessionsTable.id
    val deviceId = varchar("device_id", 255)
    val role = varchar("role", 16)
    val connected = bool("connected")
    val lastSeenAt = timestamp("last_seen_at")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
