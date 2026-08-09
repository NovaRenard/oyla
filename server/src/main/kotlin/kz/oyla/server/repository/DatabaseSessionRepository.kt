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

    override suspend fun createManagedSession(session: SessionRecord, participant: LessonParticipantRecord, exerciseIds: List<String>): Boolean = database {
        require(session.isManaged && session.centerId != null && session.specialistId != null && session.specialistDeviceUuid != null && session.startedAt != null)
        if (exerciseIds.size != 5) return@database false
        // Lock both device rows in a stable order before checking availability. This closes the
        // race between two specialist tablets attempting to claim the same child tablet.
        connection.prepareStatement("SELECT id FROM devices WHERE id IN (?, ?) ORDER BY id FOR UPDATE").use { statement ->
            statement.setObject(1, session.specialistDeviceUuid); statement.setObject(2, participant.deviceId)
            statement.executeQuery().use { result -> if (!result.next() || !result.next()) return@database false }
        }
        connection.prepareStatement(
            """SELECT 1 FROM sessions s LEFT JOIN lesson_participants p ON p.session_id = s.id
               WHERE s.is_managed = TRUE AND s.status IN ('WAITING_FOR_CHILD', 'READY')
                 AND (s.specialist_device_uuid IN (?, ?) OR p.device_id IN (?, ?)) LIMIT 1"""
        ).use { statement ->
            statement.setObject(1, session.specialistDeviceUuid); statement.setObject(2, participant.deviceId)
            statement.setObject(3, session.specialistDeviceUuid); statement.setObject(4, participant.deviceId)
            statement.executeQuery().use { if (it.next()) return@database false }
        }
        connection.prepareStatement(
            """INSERT INTO sessions (
                id, connection_code, child_name, status, specialist_device_id, specialist_token, child_device_id, child_token,
                created_at, expires_at, connected_at, completed_at, center_id, specialist_id, specialist_device_uuid, started_at, is_managed
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
            ON CONFLICT DO NOTHING"""
        ).use { statement ->
            statement.setObject(1, session.id); statement.setString(2, session.connectionCode); statement.setString(3, session.childName)
            statement.setString(4, session.status.name); statement.setString(5, session.specialistDeviceId); statement.setString(6, session.specialistToken)
            statement.setString(7, session.childDeviceId); statement.setString(8, session.childToken); statement.setTimestamp(9, java.sql.Timestamp.from(session.createdAt))
            statement.setTimestamp(10, java.sql.Timestamp.from(session.expiresAt)); statement.setTimestamp(11, session.connectedAt?.let(java.sql.Timestamp::from))
            statement.setTimestamp(12, session.completedAt?.let(java.sql.Timestamp::from)); statement.setObject(13, session.centerId)
            statement.setObject(14, session.specialistId); statement.setObject(15, session.specialistDeviceUuid); statement.setTimestamp(16, java.sql.Timestamp.from(session.startedAt))
            if (statement.executeUpdate() != 1) return@database false
        }
        connection.prepareStatement(
            """INSERT INTO lesson_participants (id, session_id, child_id, device_id, created_at)
               VALUES (?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setObject(1, participant.id); statement.setObject(2, participant.sessionId); statement.setObject(3, participant.childId)
            statement.setObject(4, participant.deviceId); statement.setTimestamp(5, java.sql.Timestamp.from(participant.createdAt)); statement.executeUpdate()
        }
        connection.prepareStatement(
            """INSERT INTO session_exercises
               (id, session_id, exercise_id, status, shown_at, started_at, completed_at, created_at, position, is_current)
               VALUES (?, ?, ?, 'PENDING', NULL, NULL, NULL, ?, ?, ?)"""
        ).use { statement ->
            exerciseIds.forEachIndexed { index, exerciseId ->
                statement.setObject(1, UUID.randomUUID()); statement.setObject(2, session.id); statement.setString(3, exerciseId)
                statement.setTimestamp(4, java.sql.Timestamp.from(session.createdAt)); statement.setInt(5, index + 1); statement.setBoolean(6, index == 0); statement.addBatch()
            }
            statement.executeBatch()
        }
        upsertDeviceConnection(session.id, session.specialistDeviceId, DeviceRole.SPECIALIST, false, session.createdAt)
        upsertDeviceConnection(session.id, checkNotNull(session.childDeviceId), DeviceRole.CHILD, false, session.createdAt)
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

    override suspend fun isManagedDeviceBusy(deviceId: UUID): Boolean = database {
        connection.prepareStatement(
            """SELECT 1 FROM sessions s
               LEFT JOIN lesson_participants p ON p.session_id = s.id
               WHERE s.is_managed = TRUE AND s.status IN ('WAITING_FOR_CHILD', 'READY')
                 AND (s.specialist_device_uuid = ? OR p.device_id = ?) LIMIT 1"""
        ).use { statement -> statement.setObject(1, deviceId); statement.setObject(2, deviceId); statement.executeQuery().use(ResultSet::next) }
    }

    override suspend fun currentChildAssignment(deviceId: UUID): ManagedLessonRecord? = database {
        connection.findManagedLesson(
            """SELECT s.*, p.id AS participant_id, p.child_id AS participant_child_id, p.device_id AS participant_device_id, p.created_at AS participant_created_at
               FROM sessions s JOIN lesson_participants p ON p.session_id = s.id
               WHERE s.is_managed = TRUE AND p.device_id = ? AND s.status IN ('WAITING_FOR_CHILD', 'READY')
               ORDER BY s.started_at DESC LIMIT 1"""
        ) { setObject(1, deviceId) }
    }

    override suspend fun currentSpecialistLesson(deviceId: UUID): ManagedLessonRecord? = database {
        connection.findManagedLesson(
            """SELECT s.*, p.id AS participant_id, p.child_id AS participant_child_id, p.device_id AS participant_device_id, p.created_at AS participant_created_at
               FROM sessions s JOIN lesson_participants p ON p.session_id = s.id
               WHERE s.is_managed = TRUE AND s.specialist_device_uuid = ? AND s.status IN ('WAITING_FOR_CHILD', 'READY')
               ORDER BY s.started_at DESC LIMIT 1"""
        ) { setObject(1, deviceId) }
    }

    override suspend fun listManagedLessons(centerId: UUID, childId: UUID?, specialistId: UUID?, status: SessionStatus?): List<ManagedLessonRecord> = database {
        val clauses = mutableListOf("s.is_managed = TRUE", "s.center_id = ?")
        if (childId != null) clauses += "p.child_id = ?"
        if (specialistId != null) clauses += "s.specialist_id = ?"
        if (status != null) clauses += "s.status = ?"
        connection.prepareStatement(
            """SELECT s.*, p.id AS participant_id, p.child_id AS participant_child_id, p.device_id AS participant_device_id, p.created_at AS participant_created_at
               FROM sessions s JOIN lesson_participants p ON p.session_id = s.id
               WHERE ${clauses.joinToString(" AND ")} ORDER BY s.started_at DESC"""
        ).use { statement ->
            var index = 1; statement.setObject(index++, centerId)
            childId?.let { statement.setObject(index++, it) }; specialistId?.let { statement.setObject(index++, it) }; status?.let { statement.setString(index, it.name) }
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toManagedLessonRecord()) } }
        }
    }

    override suspend fun getManagedLesson(centerId: UUID, sessionId: UUID): LessonDetailRecord? = database {
        val lesson = connection.findManagedLesson(
            """SELECT s.*, p.id AS participant_id, p.child_id AS participant_child_id, p.device_id AS participant_device_id, p.created_at AS participant_created_at
               FROM sessions s JOIN lesson_participants p ON p.session_id = s.id
               WHERE s.is_managed = TRUE AND s.center_id = ? AND s.id = ?"""
        ) { setObject(1, centerId); setObject(2, sessionId) } ?: return@database null
        val exercises = connection.prepareStatement(
            """SELECT se.position, se.exercise_id, e.instruction_text, se.status,
                      COUNT(a.id) AS attempt_count,
                      COUNT(a.id) FILTER (WHERE a.is_correct = FALSE) AS incorrect_attempts,
                      MIN(a.response_time_ms) FILTER (WHERE a.is_correct = TRUE) AS time_to_correct_ms
               FROM session_exercises se JOIN exercises e ON e.id = se.exercise_id
               LEFT JOIN attempts a ON a.session_exercise_id = se.id
               WHERE se.session_id = ?
               GROUP BY se.position, se.exercise_id, e.instruction_text, se.status
               ORDER BY se.position"""
        ).use { statement ->
            statement.setObject(1, sessionId); statement.executeQuery().use { result ->
                buildList { while (result.next()) add(result.toLessonExerciseHistory()) }
            }
        }
        LessonDetailRecord(lesson, exercises)
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

    private fun Connection.findManagedLesson(sql: String, bind: java.sql.PreparedStatement.() -> Unit): ManagedLessonRecord? =
        prepareStatement(sql).use { statement -> statement.bind(); statement.executeQuery().use { result -> if (result.next()) result.toManagedLessonRecord() else null } }

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
        ,centerId = getObject("center_id", UUID::class.java)
        ,specialistId = getObject("specialist_id", UUID::class.java)
        ,specialistDeviceUuid = getObject("specialist_device_uuid", UUID::class.java)
        ,startedAt = getTimestamp("started_at")?.toInstant()
        ,isManaged = getBoolean("is_managed")
    )

    private fun ResultSet.toManagedLessonRecord() = ManagedLessonRecord(
        toSessionRecord(),
        LessonParticipantRecord(getObject("participant_id", UUID::class.java), getObject("id", UUID::class.java),
            getObject("participant_child_id", UUID::class.java), getObject("participant_device_id", UUID::class.java), getTimestamp("participant_created_at").toInstant())
    )
    private fun ResultSet.toLessonExerciseHistory() = LessonExerciseHistoryRecord(
        getInt("position"), getString("exercise_id"), getString("instruction_text"), kz.oyla.server.model.ExerciseStatus.valueOf(getString("status")),
        getInt("attempt_count"), getInt("incorrect_attempts"), getLong("time_to_correct_ms").takeIf { !wasNull() }
    )

    private suspend fun <T> database(block: () -> T): T = withContext(Dispatchers.IO) {
        transaction { block() }
    }
}
