package kz.oyla.server.service

import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.ExerciseStatus
import kz.oyla.server.model.WhiteboardBrushSize
import kz.oyla.server.model.WhiteboardColor
import kz.oyla.server.model.WhiteboardTool
import kz.oyla.server.model.dto.WhiteboardPointDto
import kz.oyla.server.model.dto.WhiteboardStateSnapshotDto
import kz.oyla.server.model.dto.WhiteboardStrokeDto
import kz.oyla.server.repository.ExerciseRepository
import kz.oyla.server.repository.SessionExerciseRecord
import kz.oyla.server.repository.WhiteboardRepository
import kz.oyla.server.repository.WhiteboardStrokeInput

data class WhiteboardStartedStroke(
    val sessionExerciseId: String, val strokeId: String, val actorRole: DeviceRole,
    val tool: WhiteboardTool, val color: WhiteboardColor?, val brushSize: WhiteboardBrushSize
)
data class WhiteboardPointBatch(val sessionExerciseId: String, val strokeId: String, val points: List<WhiteboardPointDto>)
data class WhiteboardUndoResult(val sessionExerciseId: String, val strokeId: String, val boardRevision: Int)
data class WhiteboardPermissionResult(val sessionExerciseId: String, val childDrawingEnabled: Boolean, val boardRevision: Int)
data class WhiteboardClearResult(val sessionExerciseId: String, val clearRevision: Int, val boardRevision: Int)

/**
 * Server-side whiteboard gatekeeper. Completed strokes are durable; in-progress gestures remain
 * transient so a pointer move never creates a database write. PostgreSQL state revisions are the
 * source of truth for reconnects.
 */
class WhiteboardService(
    private val boards: WhiteboardRepository,
    private val exercises: ExerciseRepository,
    private val locks: SessionLockRegistry,
    private val clock: Clock = Clock.systemUTC()
) {
    private data class ActiveStroke(
        val sessionId: UUID,
        val sessionExerciseId: UUID,
        val actorRole: DeviceRole,
        val actorDeviceId: String,
        val tool: WhiteboardTool,
        val color: WhiteboardColor?,
        val brushSize: WhiteboardBrushSize,
        val points: MutableList<WhiteboardPointDto> = mutableListOf()
    )
    private val activeStrokes = ConcurrentHashMap<UUID, ActiveStroke>()

    suspend fun initialize(record: SessionExerciseRecord) {
        val config = configFor(record)
        boards.ensureState(record.id, config.childDrawingInitiallyEnabled, clock.instant())
    }

    suspend fun snapshotFor(record: SessionExerciseRecord): WhiteboardStateSnapshotDto? =
        boards.snapshot(record.id)?.toDto()

    suspend fun startStroke(
        authorized: AuthorizedSession, sessionExerciseId: String, strokeId: String,
        tool: WhiteboardTool, color: WhiteboardColor?, brushSize: WhiteboardBrushSize
    ): WhiteboardStartedStroke = locks.withLock(authorized.session.id) {
        val record = runningRecord(authorized, sessionExerciseId)
        val config = configFor(record)
        ensureDrawingAllowed(record, authorized.role)
        validateTool(config, authorized.role, tool, color, brushSize)
        val id = strokeId.uuid("Штрих")
        val actorDeviceId = actorDeviceId(authorized)
        val current = ActiveStroke(authorized.session.id, record.id, authorized.role, actorDeviceId, tool, color, brushSize)
        val existing = activeStrokes[id]
        if (existing != null) {
            if (existing.sessionExerciseId != record.id || existing.actorDeviceId != actorDeviceId) throw ApiException.conflict("Идентификатор штриха уже используется")
            return@withLock WhiteboardStartedStroke(record.id.toString(), id.toString(), authorized.role, tool, color, brushSize)
        }
        if (activeStrokes.values.any { it.sessionExerciseId == record.id && it.actorDeviceId == actorDeviceId }) {
            throw ApiException.conflict("Предыдущий штрих ещё не завершён")
        }
        if (activeStrokes.putIfAbsent(id, current) != null) throw ApiException.conflict("Идентификатор штриха уже используется")
        WhiteboardStartedStroke(record.id.toString(), id.toString(), authorized.role, tool, color, brushSize)
    }

    suspend fun appendPoints(authorized: AuthorizedSession, sessionExerciseId: String, strokeId: String, points: List<WhiteboardPointDto>): WhiteboardPointBatch = locks.withLock(authorized.session.id) {
        val record = runningRecord(authorized, sessionExerciseId)
        ensureDrawingAllowed(record, authorized.role)
        if (points.isEmpty() || points.size > MaxPointsPerBatch) throw ApiException.validation("Недопустимый размер пакета точек")
        points.forEach(::validatePoint)
        val active = activeStrokes[strokeId.uuid("Штрих")] ?: throw ApiException.conflict("Штрих не начат")
        if (active.sessionExerciseId != record.id || active.actorRole != authorized.role || active.actorDeviceId != actorDeviceId(authorized)) throw ApiException.forbidden()
        if (active.points.size + points.size > MaxPointsPerStroke) throw ApiException.validation("Слишком много точек в штрихе")
        active.points += points
        WhiteboardPointBatch(record.id.toString(), strokeId, points)
    }

    suspend fun completeStroke(authorized: AuthorizedSession, sessionExerciseId: String, strokeId: String, clientEventId: String): WhiteboardStrokeDto = locks.withLock(authorized.session.id) {
        val record = runningRecord(authorized, sessionExerciseId)
        ensureDrawingAllowed(record, authorized.role)
        val eventId = clientEventId.uuid("Идентификатор события")
        boards.findStrokeByClientEvent(eventId)?.let { existing ->
            if (existing.sessionExerciseId != record.id || existing.actorRole != authorized.role || existing.actorDeviceId != actorDeviceId(authorized)) throw ApiException.forbidden()
            return@withLock existing.toDto()
        }
        val id = strokeId.uuid("Штрих")
        val active = activeStrokes.remove(id) ?: throw ApiException.conflict("Штрих не начат")
        if (active.sessionExerciseId != record.id || active.actorRole != authorized.role || active.actorDeviceId != actorDeviceId(authorized)) throw ApiException.forbidden()
        if (active.points.isEmpty()) throw ApiException.validation("Штрих должен содержать хотя бы одну точку")
        if (boards.strokeCounts(record.id).total >= MaxStrokesPerBoard) throw ApiException.validation("Достигнут лимит штрихов на доске")
        val stroke = boards.completeStroke(WhiteboardStrokeInput(id, record.id, active.actorRole, active.actorDeviceId, active.tool, active.color, active.brushSize, active.points.toList(), eventId, clock.instant()))
        stroke.toDto()
    }

    suspend fun setChildDrawingEnabled(authorized: AuthorizedSession, sessionExerciseId: String, enabled: Boolean): WhiteboardPermissionResult = locks.withLock(authorized.session.id) {
        if (authorized.role != DeviceRole.SPECIALIST) throw ApiException.forbidden()
        val record = runningRecord(authorized, sessionExerciseId)
        val state = checkNotNull(boards.setChildDrawingEnabled(record.id, enabled, clock.instant()))
        if (!enabled) activeStrokes.entries.removeIf { (_, active) -> active.sessionExerciseId == record.id && active.actorRole == DeviceRole.CHILD }
        WhiteboardPermissionResult(record.id.toString(), state.childDrawingEnabled, state.boardRevision)
    }

    suspend fun clear(authorized: AuthorizedSession, sessionExerciseId: String): WhiteboardClearResult = locks.withLock(authorized.session.id) {
        if (authorized.role != DeviceRole.SPECIALIST) throw ApiException.forbidden()
        val record = runningRecord(authorized, sessionExerciseId)
        if (!configFor(record).allowClear) throw ApiException.forbidden()
        val state = checkNotNull(boards.clear(record.id, clock.instant()))
        activeStrokes.entries.removeIf { (_, active) -> active.sessionExerciseId == record.id }
        WhiteboardClearResult(record.id.toString(), state.clearRevision, state.boardRevision)
    }

    suspend fun undo(authorized: AuthorizedSession, sessionExerciseId: String): WhiteboardUndoResult? = locks.withLock(authorized.session.id) {
        val record = runningRecord(authorized, sessionExerciseId)
        val result = boards.undoLatest(record.id, authorized.role, clock.instant()) ?: return@withLock null
        WhiteboardUndoResult(record.id.toString(), result.first.id.toString(), result.second.boardRevision)
    }

    suspend fun metrics(sessionExerciseId: UUID) = boards.strokeCounts(sessionExerciseId)

    /** A completed board can never accept its transient gestures later. */
    fun discardActiveStrokes(sessionExerciseId: UUID) {
        activeStrokes.entries.removeIf { (_, active) -> active.sessionExerciseId == sessionExerciseId }
    }

    private suspend fun runningRecord(authorized: AuthorizedSession, rawId: String): SessionExerciseRecord {
        if (authorized.session.status != kz.oyla.server.model.SessionStatus.READY) throw ApiException.conflict("Занятие не активно")
        val id = rawId.uuid("Упражнение занятия")
        val current = exercises.findCurrentSessionExercise(authorized.session.id) ?: throw ApiException.notFound("Упражнение занятия не найдено")
        if (current.id != id || current.activityType() != ActivityType.WHITEBOARD) throw ApiException.conflict("Текущая активность не является доской")
        if (current.status != ExerciseStatus.RUNNING) throw ApiException.conflict("Доска ещё не запущена или уже завершена")
        return current
    }

    private fun configFor(record: SessionExerciseRecord) = record.snapshot?.whiteboardConfig ?: throw ApiException.conflict("Отсутствует snapshot настроек доски")
    private fun SessionExerciseRecord.activityType() = snapshot?.activityType ?: kz.oyla.server.model.ActivityType.SINGLE_CHOICE
    private fun actorDeviceId(authorized: AuthorizedSession) = when (authorized.role) { DeviceRole.SPECIALIST -> authorized.session.specialistDeviceId; DeviceRole.CHILD -> checkNotNull(authorized.session.childDeviceId) }
    private suspend fun ensureDrawingAllowed(record: SessionExerciseRecord, role: DeviceRole) {
        if (role == DeviceRole.CHILD && boards.snapshot(record.id)?.state?.childDrawingEnabled != true) throw ApiException.forbidden()
    }
    private fun validateTool(config: kz.oyla.server.model.WhiteboardExerciseConfig, role: DeviceRole, tool: WhiteboardTool, color: WhiteboardColor?, brush: WhiteboardBrushSize) {
        if (tool == WhiteboardTool.ERASER && !config.allowEraser) throw ApiException.forbidden()
        if (tool == WhiteboardTool.PEN && (color == null || color !in config.availableColors)) throw ApiException.validation("Цвет недоступен")
        if (tool == WhiteboardTool.ERASER && color != null) throw ApiException.validation("Ластик не использует цвет")
        if (brush !in WhiteboardBrushSize.entries) throw ApiException.validation("Размер кисти недоступен")
    }
    private fun validatePoint(point: WhiteboardPointDto) {
        if (!point.x.isFinite() || !point.y.isFinite() || point.x !in 0f..1f || point.y !in 0f..1f) throw ApiException.validation("Координаты должны быть нормализованы")
    }
    private fun String.uuid(label: String): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw ApiException.validation("Некорректный идентификатор: $label") }
    private fun kz.oyla.server.repository.WhiteboardSnapshotRecord.toDto() = WhiteboardStateSnapshotDto(state.sessionExerciseId.toString(), state.childDrawingEnabled, state.boardRevision, state.clearRevision, strokes.map { it.toDto() })
    private fun kz.oyla.server.repository.WhiteboardStrokeRecord.toDto() = WhiteboardStrokeDto(id.toString(), sessionExerciseId.toString(), actorRole.name, actorDeviceId, sequenceNumber, tool, color, brushSize, points, createdAt.toString())

    private companion object {
        const val MaxPointsPerBatch = 96
        const val MaxPointsPerStroke = 2_048
        const val MaxStrokesPerBoard = 500
    }
}
