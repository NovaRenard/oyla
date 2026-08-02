package kz.oyla.server.repository

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kz.oyla.server.model.ExerciseStatus
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

class DatabaseExerciseRepository : ExerciseRepository {
    override suspend fun findExercise(id: String): ExerciseRecord? = database {
        connection.prepareStatement("SELECT * FROM exercises WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { result ->
                if (!result.next()) return@database null
                result.toExerciseRecord(connection.loadOptions(id))
            }
        }
    }

    override suspend fun findSessionExercise(sessionId: UUID): SessionExerciseRecord? = database {
        connection.findSessionExercise("SELECT * FROM session_exercises WHERE session_id = ?") {
            setObject(1, sessionId)
        }
    }

    override suspend fun createSessionExercise(record: SessionExerciseRecord): Boolean = database {
        connection.prepareStatement(
            """INSERT INTO session_exercises (id, session_id, exercise_id, status, shown_at, started_at, completed_at, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (session_id) DO NOTHING"""
        ).use { statement ->
            statement.setObject(1, record.id)
            statement.setObject(2, record.sessionId)
            statement.setString(3, record.exerciseId)
            statement.setString(4, record.status.name)
            statement.setTimestamp(5, record.shownAt?.let(java.sql.Timestamp::from))
            statement.setTimestamp(6, record.startedAt?.let(java.sql.Timestamp::from))
            statement.setTimestamp(7, record.completedAt?.let(java.sql.Timestamp::from))
            statement.setTimestamp(8, java.sql.Timestamp.from(record.createdAt))
            statement.executeUpdate() == 1
        }
    }

    override suspend fun updateSessionExercise(record: SessionExerciseRecord): Boolean = database {
        connection.prepareStatement(
            """UPDATE session_exercises SET status = ?, shown_at = ?, started_at = ?, completed_at = ? WHERE id = ?"""
        ).use { statement ->
            statement.setString(1, record.status.name)
            statement.setTimestamp(2, record.shownAt?.let(java.sql.Timestamp::from))
            statement.setTimestamp(3, record.startedAt?.let(java.sql.Timestamp::from))
            statement.setTimestamp(4, record.completedAt?.let(java.sql.Timestamp::from))
            statement.setObject(5, record.id)
            statement.executeUpdate() == 1
        }
    }

    override suspend fun findAttemptByClientEventId(clientEventId: UUID): ExerciseAttemptRecord? = database {
        connection.findAttempt("SELECT * FROM attempts WHERE client_event_id = ?") { setObject(1, clientEventId) }
    }

    override suspend fun findLatestAttempt(sessionExerciseId: UUID): ExerciseAttemptRecord? = database {
        connection.findAttempt(
            "SELECT * FROM attempts WHERE session_exercise_id = ? ORDER BY attempt_number DESC LIMIT 1"
        ) { setObject(1, sessionExerciseId) }
    }

    override suspend fun countAttempts(sessionExerciseId: UUID): Int = database {
        connection.prepareStatement("SELECT COUNT(*) FROM attempts WHERE session_exercise_id = ?").use { statement ->
            statement.setObject(1, sessionExerciseId)
            statement.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    override suspend fun createAttempt(record: ExerciseAttemptRecord): Boolean = database {
        connection.prepareStatement(
            """INSERT INTO attempts (id, session_exercise_id, selected_option_id, attempt_number, is_correct,
               response_time_ms, client_event_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (client_event_id) DO NOTHING"""
        ).use { statement ->
            statement.setObject(1, record.id)
            statement.setObject(2, record.sessionExerciseId)
            statement.setString(3, record.selectedOptionId)
            statement.setInt(4, record.attemptNumber)
            statement.setBoolean(5, record.isCorrect)
            statement.setLong(6, record.responseTimeMs)
            statement.setObject(7, record.clientEventId)
            statement.setTimestamp(8, java.sql.Timestamp.from(record.createdAt))
            statement.executeUpdate() == 1
        }
    }

    private fun Connection.loadOptions(exerciseId: String): List<ExerciseOptionRecord> = prepareStatement(
        "SELECT * FROM exercise_options WHERE exercise_id = ? ORDER BY position"
    ).use { statement ->
        statement.setString(1, exerciseId)
        statement.executeQuery().use { result ->
            buildList { while (result.next()) add(result.toExerciseOptionRecord()) }
        }
    }

    private fun Connection.findSessionExercise(
        sql: String,
        bind: java.sql.PreparedStatement.() -> Unit
    ): SessionExerciseRecord? = prepareStatement(sql).use { statement ->
        statement.bind()
        statement.executeQuery().use { result -> if (result.next()) result.toSessionExerciseRecord() else null }
    }

    private fun Connection.findAttempt(
        sql: String,
        bind: java.sql.PreparedStatement.() -> Unit
    ): ExerciseAttemptRecord? = prepareStatement(sql).use { statement ->
        statement.bind()
        statement.executeQuery().use { result -> if (result.next()) result.toExerciseAttemptRecord() else null }
    }

    private fun ResultSet.toExerciseRecord(options: List<ExerciseOptionRecord>) = ExerciseRecord(
        id = getString("id"), instructionText = getString("instruction_text"),
        audioAssetKey = getString("audio_asset_key"), correctOptionId = getString("correct_option_id"),
        isActive = getBoolean("is_active"), createdAt = getTimestamp("created_at").toInstant(), options = options
    )
    private fun ResultSet.toExerciseOptionRecord() = ExerciseOptionRecord(
        id = getString("id"), exerciseId = getString("exercise_id"), label = getString("label"),
        imageAssetKey = getString("image_asset_key"), position = getInt("position")
    )
    private fun ResultSet.toSessionExerciseRecord() = SessionExerciseRecord(
        id = getObject("id", UUID::class.java), sessionId = getObject("session_id", UUID::class.java),
        exerciseId = getString("exercise_id"), status = ExerciseStatus.valueOf(getString("status")),
        shownAt = getTimestamp("shown_at")?.toInstant(), startedAt = getTimestamp("started_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(), createdAt = getTimestamp("created_at").toInstant()
    )
    private fun ResultSet.toExerciseAttemptRecord() = ExerciseAttemptRecord(
        id = getObject("id", UUID::class.java), sessionExerciseId = getObject("session_exercise_id", UUID::class.java),
        selectedOptionId = getString("selected_option_id"), attemptNumber = getInt("attempt_number"),
        isCorrect = getBoolean("is_correct"), responseTimeMs = getLong("response_time_ms"),
        clientEventId = getObject("client_event_id", UUID::class.java), createdAt = getTimestamp("created_at").toInstant()
    )

    private val connection: Connection get() = (TransactionManager.current().connection as JdbcConnectionImpl).connection
    private suspend fun <T> database(block: () -> T): T = withContext(Dispatchers.IO) { transaction { block() } }
}
