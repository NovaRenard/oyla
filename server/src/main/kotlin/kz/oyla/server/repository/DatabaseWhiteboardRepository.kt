package kz.oyla.server.repository

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.WhiteboardBrushSize
import kz.oyla.server.model.WhiteboardColor
import kz.oyla.server.model.WhiteboardTool
import kz.oyla.server.model.dto.WhiteboardPointDto
import org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

/** PostgreSQL implementation: state-row locking serializes sequence/revision mutations. */
class DatabaseWhiteboardRepository : WhiteboardRepository {
    override suspend fun ensureState(sessionExerciseId: UUID, childDrawingEnabled: Boolean, now: Instant): WhiteboardStateRecord = database {
        connection.prepareStatement(
            """INSERT INTO whiteboard_states (session_exercise_id, child_drawing_enabled, board_revision, clear_revision, next_sequence_number, updated_at)
               VALUES (?, ?, 0, 0, 0, ?) ON CONFLICT (session_exercise_id) DO NOTHING"""
        ).use { statement -> statement.setObject(1, sessionExerciseId); statement.setBoolean(2, childDrawingEnabled); statement.setInstant(3, now); statement.executeUpdate() }
        checkNotNull(connection.state("SELECT * FROM whiteboard_states WHERE session_exercise_id = ?") { setObject(1, sessionExerciseId) })
    }

    override suspend fun snapshot(sessionExerciseId: UUID): WhiteboardSnapshotRecord? = database {
        val state = connection.state("SELECT * FROM whiteboard_states WHERE session_exercise_id = ?") { setObject(1, sessionExerciseId) } ?: return@database null
        val strokes = connection.prepareStatement(
            """SELECT * FROM whiteboard_strokes WHERE session_exercise_id = ? AND clear_revision = ? AND is_removed = FALSE
               ORDER BY sequence_number"""
        ).use { statement ->
            statement.setObject(1, sessionExerciseId); statement.setInt(2, state.clearRevision)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toStroke()) } }
        }
        WhiteboardSnapshotRecord(state, strokes)
    }

    override suspend fun findStrokeByClientEvent(clientEventId: UUID): WhiteboardStrokeRecord? = database { connection.findStrokeByEvent(clientEventId) }

    override suspend fun completeStroke(input: WhiteboardStrokeInput): WhiteboardStrokeRecord = database {
        // The state lock makes the sequence deterministic even when both tablets complete together.
        val state = checkNotNull(connection.state("SELECT * FROM whiteboard_states WHERE session_exercise_id = ? FOR UPDATE") { setObject(1, input.sessionExerciseId) })
        connection.findStrokeByEvent(input.clientEventId)?.let { return@database it }
        val next = state.nextSequenceNumber + 1
        val stroke = WhiteboardStrokeRecord(input.id, input.sessionExerciseId, input.actorRole, input.actorDeviceId, next,
            input.tool, input.color, input.brushSize, input.points, state.clearRevision, false, input.clientEventId, input.createdAt)
        connection.prepareStatement(
            """INSERT INTO whiteboard_strokes
               (id, session_exercise_id, actor_device_id, actor_role, sequence_number, tool, color, brush_size, points_json, clear_revision, is_removed, client_event_id, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, FALSE, ?, ?)"""
        ).use { statement ->
            statement.setObject(1, stroke.id); statement.setObject(2, stroke.sessionExerciseId); statement.setString(3, stroke.actorDeviceId)
            statement.setString(4, stroke.actorRole.name); statement.setInt(5, stroke.sequenceNumber); statement.setString(6, stroke.tool.name)
            statement.setString(7, stroke.color?.name); statement.setString(8, stroke.brushSize.name); statement.setString(9, json.encodeToString(stroke.points))
            statement.setInt(10, stroke.clearRevision); statement.setObject(11, stroke.clientEventId); statement.setInstant(12, stroke.createdAt)
            statement.executeUpdate()
        }
        connection.updateState(state.copy(boardRevision = state.boardRevision + 1, nextSequenceNumber = next, updatedAt = input.createdAt))
        stroke
    }

    override suspend fun setChildDrawingEnabled(sessionExerciseId: UUID, enabled: Boolean, now: Instant): WhiteboardStateRecord? = database {
        val state = connection.state("SELECT * FROM whiteboard_states WHERE session_exercise_id = ? FOR UPDATE") { setObject(1, sessionExerciseId) } ?: return@database null
        val updated = state.copy(childDrawingEnabled = enabled, boardRevision = state.boardRevision + 1, updatedAt = now)
        connection.updateState(updated); updated
    }

    override suspend fun clear(sessionExerciseId: UUID, now: Instant): WhiteboardStateRecord? = database {
        val state = connection.state("SELECT * FROM whiteboard_states WHERE session_exercise_id = ? FOR UPDATE") { setObject(1, sessionExerciseId) } ?: return@database null
        val updated = state.copy(clearRevision = state.clearRevision + 1, boardRevision = state.boardRevision + 1, updatedAt = now)
        connection.updateState(updated); updated
    }

    override suspend fun undoLatest(sessionExerciseId: UUID, actorRole: DeviceRole, now: Instant): Pair<WhiteboardStrokeRecord, WhiteboardStateRecord>? = database {
        val state = connection.state("SELECT * FROM whiteboard_states WHERE session_exercise_id = ? FOR UPDATE") { setObject(1, sessionExerciseId) } ?: return@database null
        val rolePredicate = if (actorRole == DeviceRole.CHILD) " AND actor_role = 'CHILD'" else ""
        val stroke = connection.prepareStatement(
            """SELECT * FROM whiteboard_strokes WHERE session_exercise_id = ? AND clear_revision = ? AND is_removed = FALSE$rolePredicate
               ORDER BY sequence_number DESC LIMIT 1 FOR UPDATE"""
        ).use { statement ->
            statement.setObject(1, sessionExerciseId); statement.setInt(2, state.clearRevision)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toStroke() else null }
        } ?: return@database null
        connection.prepareStatement("UPDATE whiteboard_strokes SET is_removed = TRUE WHERE id = ?").use { it.setObject(1, stroke.id); it.executeUpdate() }
        val updated = state.copy(boardRevision = state.boardRevision + 1, updatedAt = now)
        connection.updateState(updated)
        stroke.copy(isRemoved = true) to updated
    }

    override suspend fun strokeCounts(sessionExerciseId: UUID): WhiteboardStrokeCounts = database {
        connection.prepareStatement(
            """SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE actor_role = 'CHILD') AS child,
                      COUNT(*) FILTER (WHERE actor_role = 'SPECIALIST') AS specialist
               FROM whiteboard_strokes WHERE session_exercise_id = ?"""
        ).use { statement -> statement.setObject(1, sessionExerciseId); statement.executeQuery().use { rows -> rows.next(); WhiteboardStrokeCounts(rows.getInt("total"), rows.getInt("child"), rows.getInt("specialist")) } }
    }

    private fun Connection.state(sql: String, bind: java.sql.PreparedStatement.() -> Unit): WhiteboardStateRecord? =
        prepareStatement(sql).use { statement -> statement.bind(); statement.executeQuery().use { rows -> if (rows.next()) rows.toState() else null } }

    private fun Connection.findStrokeByEvent(clientEventId: UUID): WhiteboardStrokeRecord? = prepareStatement("SELECT * FROM whiteboard_strokes WHERE client_event_id = ?").use { statement ->
        statement.setObject(1, clientEventId); statement.executeQuery().use { rows -> if (rows.next()) rows.toStroke() else null }
    }

    private fun Connection.updateState(state: WhiteboardStateRecord) {
        prepareStatement("""UPDATE whiteboard_states SET child_drawing_enabled=?, board_revision=?, clear_revision=?, next_sequence_number=?, updated_at=? WHERE session_exercise_id=?""").use { statement ->
            statement.setBoolean(1, state.childDrawingEnabled); statement.setInt(2, state.boardRevision); statement.setInt(3, state.clearRevision)
            statement.setInt(4, state.nextSequenceNumber); statement.setInstant(5, state.updatedAt); statement.setObject(6, state.sessionExerciseId); check(statement.executeUpdate() == 1)
        }
    }

    private fun ResultSet.toState() = WhiteboardStateRecord(getObject("session_exercise_id", UUID::class.java), getBoolean("child_drawing_enabled"), getInt("board_revision"), getInt("clear_revision"), getInt("next_sequence_number"), getTimestamp("updated_at").toInstant())
    private fun ResultSet.toStroke() = WhiteboardStrokeRecord(
        getObject("id", UUID::class.java), getObject("session_exercise_id", UUID::class.java), DeviceRole.valueOf(getString("actor_role")), getString("actor_device_id"), getInt("sequence_number"),
        WhiteboardTool.valueOf(getString("tool")), getString("color")?.let(WhiteboardColor::valueOf), WhiteboardBrushSize.valueOf(getString("brush_size")),
        json.decodeFromString(getString("points_json")), getInt("clear_revision"), getBoolean("is_removed"), getObject("client_event_id", UUID::class.java), getTimestamp("created_at").toInstant()
    )
    private fun java.sql.PreparedStatement.setInstant(index: Int, value: Instant) = setTimestamp(index, java.sql.Timestamp.from(value))
    private val connection: Connection get() = (TransactionManager.current().connection as JdbcConnectionImpl).connection
    private suspend fun <T> database(block: () -> T): T = withContext(Dispatchers.IO) { transaction { block() } }
    private companion object { val json = Json { encodeDefaults = true; explicitNulls = false } }
}
