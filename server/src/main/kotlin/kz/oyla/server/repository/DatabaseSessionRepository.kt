package kz.oyla.server.repository

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.SessionStatus
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl

class DatabaseSessionRepository : SessionRepository {
    override suspend fun isConnectionCodeActive(code: String, now: Instant): Boolean = database {
        connection.prepareStatement(
            """
            SELECT 1 FROM sessions
            WHERE connection_code = ?
              AND status IN ('WAITING_FOR_CHILD', 'READY')
              AND expires_at > ?
            LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, code)
            statement.setTimestamp(2, java.sql.Timestamp.from(now))
            statement.executeQuery().use(ResultSet::next)
        }
    }

    override suspend fun createSession(session: SessionRecord): Boolean = database {
        connection.prepareStatement(
            """
            UPDATE sessions SET status = ?
            WHERE connection_code = ? AND status = ? AND expires_at <= ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, SessionStatus.EXPIRED.name)
            statement.setString(2, session.connectionCode)
            statement.setString(3, SessionStatus.WAITING_FOR_CHILD.name)
            statement.setTimestamp(4, java.sql.Timestamp.from(session.createdAt))
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO sessions (
                id, connection_code, child_name, status, specialist_device_id, specialist_token,
                child_device_id, child_token, created_at, expires_at, connected_at, completed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, session.id)
            statement.setString(2, session.connectionCode)
            statement.setString(3, session.childName)
            statement.setString(4, session.status.name)
            statement.setString(5, session.specialistDeviceId)
            statement.setString(6, session.specialistToken)
            statement.setString(7, session.childDeviceId)
            statement.setString(8, session.childToken)
            statement.setTimestamp(9, java.sql.Timestamp.from(session.createdAt))
            statement.setTimestamp(10, java.sql.Timestamp.from(session.expiresAt))
            statement.setTimestamp(11, session.connectedAt?.let(java.sql.Timestamp::from))
            statement.setTimestamp(12, session.completedAt?.let(java.sql.Timestamp::from))
            if (statement.executeUpdate() == 0) return@database false
        }
        upsertDeviceConnection(session.id, session.specialistDeviceId, DeviceRole.SPECIALIST, false, session.createdAt)
        true
    }

    override suspend fun findById(id: UUID): SessionRecord? = database {
        connection.findSession("SELECT * FROM sessions WHERE id = ?") { setObject(1, id) }
    }

    override suspend fun connectChild(
        code: String,
        childDeviceId: String,
        childToken: String,
        now: Instant
    ): ChildConnectionResult = database {
        val session = connection.findSession(
            "SELECT * FROM sessions WHERE connection_code = ? FOR UPDATE"
        ) { setString(1, code) } ?: return@database ChildConnectionResult.NotFound

        if (session.expiresAt <= now || session.status in setOf(
                SessionStatus.CANCELLED,
                SessionStatus.COMPLETED,
                SessionStatus.EXPIRED
            )
        ) {
            if (session.status == SessionStatus.WAITING_FOR_CHILD && session.expiresAt <= now) {
                updateStatus(session.id, SessionStatus.EXPIRED, now)
            }
            return@database ChildConnectionResult.Expired
        }

        if (session.childDeviceId != null && session.childDeviceId != childDeviceId) {
            return@database ChildConnectionResult.ConnectedToAnotherDevice
        }

        val connected = if (session.childDeviceId == null) {
            connection.prepareStatement(
                """
                UPDATE sessions SET child_device_id = ?, child_token = ?, status = ?, connected_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, childDeviceId)
                statement.setString(2, childToken)
                statement.setString(3, SessionStatus.READY.name)
                statement.setTimestamp(4, java.sql.Timestamp.from(now))
                statement.setObject(5, session.id)
                statement.executeUpdate()
            }
            session.copy(
                childDeviceId = childDeviceId,
                childToken = childToken,
                status = SessionStatus.READY,
                connectedAt = now
            )
        } else {
            session
        }
        upsertDeviceConnection(connected.id, childDeviceId, DeviceRole.CHILD, false, now)
        ChildConnectionResult.Connected(connected)
    }

    override suspend fun expireIfNecessary(id: UUID, now: Instant): SessionRecord? = database {
        connection.prepareStatement(
            """
            UPDATE sessions SET status = ?
            WHERE id = ? AND status = ? AND expires_at <= ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, SessionStatus.EXPIRED.name)
            statement.setObject(2, id)
            statement.setString(3, SessionStatus.WAITING_FOR_CHILD.name)
            statement.setTimestamp(4, java.sql.Timestamp.from(now))
            statement.executeUpdate()
        }
        connection.findSession("SELECT * FROM sessions WHERE id = ?") { setObject(1, id) }
    }

    override suspend fun cancel(id: UUID, now: Instant): SessionRecord? = database {
        connection.prepareStatement(
            """
            UPDATE sessions SET status = ?, completed_at = ?
            WHERE id = ? AND status IN ('WAITING_FOR_CHILD', 'READY')
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, SessionStatus.CANCELLED.name)
            statement.setTimestamp(2, java.sql.Timestamp.from(now))
            statement.setObject(3, id)
            statement.executeUpdate()
        }
        connection.findSession("SELECT * FROM sessions WHERE id = ?") { setObject(1, id) }
    }

    override suspend fun complete(id: UUID, now: Instant): SessionRecord? = database {
        connection.prepareStatement(
            """UPDATE sessions SET status = ?, completed_at = ?
               WHERE id = ? AND status IN ('WAITING_FOR_CHILD', 'READY')"""
        ).use { statement ->
            statement.setString(1, SessionStatus.COMPLETED.name)
            statement.setTimestamp(2, java.sql.Timestamp.from(now))
            statement.setObject(3, id)
            statement.executeUpdate()
        }
        connection.findSession("SELECT * FROM sessions WHERE id = ?") { setObject(1, id) }
    }

    override suspend fun updateDeviceConnection(
        session: SessionRecord,
        role: DeviceRole,
        connected: Boolean,
        now: Instant
    ) = database {
        val deviceId = when (role) {
            DeviceRole.SPECIALIST -> session.specialistDeviceId
            DeviceRole.CHILD -> session.childDeviceId
        } ?: return@database
        upsertDeviceConnection(session.id, deviceId, role, connected, now)
    }

    private fun updateStatus(id: UUID, status: SessionStatus, now: Instant) {
        connection.prepareStatement("UPDATE sessions SET status = ?, completed_at = ? WHERE id = ?").use { statement ->
            statement.setString(1, status.name)
            statement.setTimestamp(2, java.sql.Timestamp.from(now))
            statement.setObject(3, id)
            statement.executeUpdate()
        }
    }

    private fun Connection.findSession(sql: String, bind: java.sql.PreparedStatement.() -> Unit): SessionRecord? =
        prepareStatement(sql).use { statement ->
            statement.bind()
            statement.executeQuery().use { results ->
                if (results.next()) results.toSessionRecord() else null
            }
        }

    private fun upsertDeviceConnection(
        sessionId: UUID,
        deviceId: String,
        role: DeviceRole,
        connected: Boolean,
        now: Instant
    ) {
        connection.prepareStatement(
            """
            INSERT INTO device_connections (id, session_id, device_id, role, connected, last_seen_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (session_id, device_id, role)
            DO UPDATE SET connected = EXCLUDED.connected, last_seen_at = EXCLUDED.last_seen_at
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, sessionId)
            statement.setString(3, deviceId)
            statement.setString(4, role.name)
            statement.setBoolean(5, connected)
            statement.setTimestamp(6, java.sql.Timestamp.from(now))
            statement.setTimestamp(7, java.sql.Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private val connection: Connection
        get() = (TransactionManager.current().connection as JdbcConnectionImpl).connection

    private fun ResultSet.toSessionRecord() = SessionRecord(
        id = getObject("id", UUID::class.java),
        connectionCode = getString("connection_code"),
        childName = getString("child_name"),
        status = SessionStatus.valueOf(getString("status")),
        specialistDeviceId = getString("specialist_device_id"),
        specialistToken = getString("specialist_token"),
        childDeviceId = getString("child_device_id"),
        childToken = getString("child_token"),
        createdAt = getTimestamp("created_at").toInstant(),
        expiresAt = getTimestamp("expires_at").toInstant(),
        connectedAt = getTimestamp("connected_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant()
    )

    private suspend fun <T> database(block: () -> T): T = withContext(Dispatchers.IO) {
        transaction { block() }
    }
}
