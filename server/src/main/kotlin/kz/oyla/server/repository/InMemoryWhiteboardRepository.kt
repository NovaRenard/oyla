package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.DeviceRole

/** Test implementation with the same idempotency, revision and sequence semantics as PostgreSQL. */
class InMemoryWhiteboardRepository : WhiteboardRepository {
    private val states = linkedMapOf<UUID, WhiteboardStateRecord>()
    private val strokes = linkedMapOf<UUID, WhiteboardStrokeRecord>()

    override suspend fun ensureState(sessionExerciseId: UUID, childDrawingEnabled: Boolean, now: Instant) = synchronized(this) {
        states.getOrPut(sessionExerciseId) { WhiteboardStateRecord(sessionExerciseId, childDrawingEnabled, 0, 0, 0, now) }
    }

    override suspend fun snapshot(sessionExerciseId: UUID) = synchronized(this) {
        val state = states[sessionExerciseId] ?: return@synchronized null
        WhiteboardSnapshotRecord(state, strokes.values.filter {
            it.sessionExerciseId == sessionExerciseId && !it.isRemoved && it.clearRevision == state.clearRevision
        }.sortedBy { it.sequenceNumber })
    }

    override suspend fun findStrokeByClientEvent(clientEventId: UUID) = synchronized(this) { strokes.values.firstOrNull { it.clientEventId == clientEventId } }

    override suspend fun completeStroke(input: WhiteboardStrokeInput) = synchronized(this) {
        strokes.values.firstOrNull { it.clientEventId == input.clientEventId }?.let { return@synchronized it }
        val state = checkNotNull(states[input.sessionExerciseId]) { "Whiteboard state is not initialized" }
        val next = state.nextSequenceNumber + 1
        val stroke = WhiteboardStrokeRecord(
            input.id, input.sessionExerciseId, input.actorRole, input.actorDeviceId, next,
            input.tool, input.color, input.brushSize, input.points, state.clearRevision, false,
            input.clientEventId, input.createdAt
        )
        strokes[stroke.id] = stroke
        states[input.sessionExerciseId] = state.copy(boardRevision = state.boardRevision + 1, nextSequenceNumber = next, updatedAt = input.createdAt)
        stroke
    }

    override suspend fun setChildDrawingEnabled(sessionExerciseId: UUID, enabled: Boolean, now: Instant) = synchronized(this) {
        states[sessionExerciseId]?.let { state -> state.copy(childDrawingEnabled = enabled, boardRevision = state.boardRevision + 1, updatedAt = now).also { states[sessionExerciseId] = it } }
    }

    override suspend fun clear(sessionExerciseId: UUID, now: Instant) = synchronized(this) {
        states[sessionExerciseId]?.let { state -> state.copy(clearRevision = state.clearRevision + 1, boardRevision = state.boardRevision + 1, updatedAt = now).also { states[sessionExerciseId] = it } }
    }

    override suspend fun undoLatest(sessionExerciseId: UUID, actorRole: DeviceRole, now: Instant) = synchronized(this) {
        val state = states[sessionExerciseId] ?: return@synchronized null
        val candidate = strokes.values.filter {
            it.sessionExerciseId == sessionExerciseId && !it.isRemoved && it.clearRevision == state.clearRevision &&
                (actorRole == DeviceRole.SPECIALIST || it.actorRole == DeviceRole.CHILD)
        }.maxByOrNull { it.sequenceNumber } ?: return@synchronized null
        val removed = candidate.copy(isRemoved = true)
        strokes[removed.id] = removed
        val updated = state.copy(boardRevision = state.boardRevision + 1, updatedAt = now)
        states[sessionExerciseId] = updated
        removed to updated
    }

    override suspend fun strokeCounts(sessionExerciseId: UUID) = synchronized(this) {
        val values = strokes.values.filter { it.sessionExerciseId == sessionExerciseId }
        WhiteboardStrokeCounts(values.size, values.count { it.actorRole == DeviceRole.CHILD }, values.count { it.actorRole == DeviceRole.SPECIALIST })
    }
}
