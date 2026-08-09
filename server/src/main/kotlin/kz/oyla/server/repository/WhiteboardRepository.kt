package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.WhiteboardBrushSize
import kz.oyla.server.model.WhiteboardColor
import kz.oyla.server.model.WhiteboardTool
import kz.oyla.server.model.dto.WhiteboardPointDto

data class WhiteboardStateRecord(
    val sessionExerciseId: UUID,
    val childDrawingEnabled: Boolean,
    val boardRevision: Int,
    val clearRevision: Int,
    val nextSequenceNumber: Int,
    val updatedAt: Instant
)

data class WhiteboardStrokeRecord(
    val id: UUID,
    val sessionExerciseId: UUID,
    val actorRole: DeviceRole,
    val actorDeviceId: String,
    val sequenceNumber: Int,
    val tool: WhiteboardTool,
    val color: WhiteboardColor?,
    val brushSize: WhiteboardBrushSize,
    val points: List<WhiteboardPointDto>,
    val clearRevision: Int,
    val isRemoved: Boolean,
    val clientEventId: UUID,
    val createdAt: Instant
)

data class WhiteboardSnapshotRecord(val state: WhiteboardStateRecord, val strokes: List<WhiteboardStrokeRecord>)
data class WhiteboardStrokeInput(
    val id: UUID,
    val sessionExerciseId: UUID,
    val actorRole: DeviceRole,
    val actorDeviceId: String,
    val tool: WhiteboardTool,
    val color: WhiteboardColor?,
    val brushSize: WhiteboardBrushSize,
    val points: List<WhiteboardPointDto>,
    val clientEventId: UUID,
    val createdAt: Instant
)
data class WhiteboardStrokeCounts(val total: Int, val child: Int, val specialist: Int)

/** Persistence boundary for the authoritative whiteboard head and completed strokes. */
interface WhiteboardRepository {
    suspend fun ensureState(sessionExerciseId: UUID, childDrawingEnabled: Boolean, now: Instant): WhiteboardStateRecord
    suspend fun snapshot(sessionExerciseId: UUID): WhiteboardSnapshotRecord?
    suspend fun findStrokeByClientEvent(clientEventId: UUID): WhiteboardStrokeRecord?
    /** Idempotent by clientEventId and assigns the next server sequence number. */
    suspend fun completeStroke(input: WhiteboardStrokeInput): WhiteboardStrokeRecord
    suspend fun setChildDrawingEnabled(sessionExerciseId: UUID, enabled: Boolean, now: Instant): WhiteboardStateRecord?
    suspend fun clear(sessionExerciseId: UUID, now: Instant): WhiteboardStateRecord?
    /** When actorRole is CHILD, only the child's latest active stroke is eligible. */
    suspend fun undoLatest(sessionExerciseId: UUID, actorRole: DeviceRole, now: Instant): Pair<WhiteboardStrokeRecord, WhiteboardStateRecord>?
    suspend fun strokeCounts(sessionExerciseId: UUID): WhiteboardStrokeCounts
}
