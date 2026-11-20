package kz.oyla.server.service

import java.time.Clock
import java.time.Duration
import java.util.UUID
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.ExerciseStatus
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.ExerciseSnapshot
import kz.oyla.server.model.GameExerciseConfig
import kz.oyla.server.model.isChoiceLike
import kz.oyla.server.model.isGame
import kz.oyla.server.model.SessionStatus
import kz.oyla.server.model.dto.AnswerExerciseRequest
import kz.oyla.server.model.dto.AnswerExerciseResponse
import kz.oyla.server.model.dto.ExerciseDto
import kz.oyla.server.model.dto.ExerciseOptionDto
import kz.oyla.server.model.dto.ExerciseStateResponse
import kz.oyla.server.model.dto.ExerciseSummaryItemDto
import kz.oyla.server.model.dto.GameExerciseConfigDto
import kz.oyla.server.model.dto.NextExerciseRequest
import kz.oyla.server.model.dto.SessionSummaryResponse
import kz.oyla.server.model.dto.ShowExerciseRequest
import kz.oyla.server.model.dto.ShowExerciseResponse
import kz.oyla.server.model.dto.SpecialistExerciseDto
import kz.oyla.server.model.dto.StartExerciseRequest
import kz.oyla.server.model.dto.StartExerciseResponse
import kz.oyla.server.model.dto.WhiteboardExerciseConfigDto
import kz.oyla.server.repository.ExerciseAttemptRecord
import kz.oyla.server.repository.ExerciseRecord
import kz.oyla.server.repository.ExerciseRepository
import kz.oyla.server.repository.SessionExerciseRecord

class ExerciseService(
    private val exercises: ExerciseRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val whiteboards: WhiteboardService? = null,
    private val locks: SessionLockRegistry = SessionLockRegistry()
) {
    companion object {
        val defaultPlan = listOf(
            "sound-r-rocket", "sound-r-fish", "sound-l-lamp", "sound-s-dog", "sound-sh-ball"
        )
        const val GroupA = "A"
        const val GroupB = "B"
        const val MazeFinish = "finish"
        const val MazeOffPath = "off_path"
        private val TapCountPattern = Regex("[0-9]{1,2}")
    }

    /** Creates missing plan positions only; legacy position 1 and its attempts stay untouched. */
    suspend fun ensureDefaultExercisePlan(sessionId: UUID) = locks.withLock(sessionId) {
        ensureDefaultExercisePlanLocked(sessionId)
    }

    /** Used by the in-memory HTTP host; PostgreSQL already persisted these in the lesson transaction. */
    suspend fun ensureSnapshotPlan(records: List<SessionExerciseRecord>) {
        if (records.isNotEmpty()) exercises.createSessionExercises(records)
    }

    private suspend fun ensureDefaultExercisePlanLocked(sessionId: UUID) {
        val existing = exercises.findSessionExercises(sessionId).associateBy { it.position }
        val now = clock.instant()
        val missing = defaultPlan.mapIndexedNotNull { index, exerciseId ->
            val position = index + 1
            if (existing.containsKey(position)) null else SessionExerciseRecord(
                id = UUID.randomUUID(), sessionId = sessionId, exerciseId = exerciseId,
                status = ExerciseStatus.PENDING, shownAt = null, startedAt = null, completedAt = null,
                createdAt = now, position = position, isCurrent = existing.isEmpty() && position == 1
            )
        }
        if (missing.isNotEmpty()) exercises.createSessionExercises(missing)
    }

    /** Managed lessons persist their whole plan at creation; only legacy sessions use the fixed plan. */
    private suspend fun ensurePlanLocked(authorized: AuthorizedSession) {
        if (!authorized.session.isManaged) ensureDefaultExercisePlanLocked(authorized.session.id)
    }

    suspend fun getExercise(id: String): ExerciseDto = exercises.findExercise(id)
        ?.takeIf { it.isActive }?.toDto() ?: throw ApiException.notFound("Упражнение не найдено")

    suspend fun show(authorized: AuthorizedSession, request: ShowExerciseRequest): ShowExerciseResponse {
        requireSpecialist(authorized)
        if (authorized.session.status != SessionStatus.READY || authorized.session.childDeviceId == null) {
            throw ApiException.conflict("Нельзя показать задание: ребёнок не подключён")
        }
        return locks.withLock(authorized.session.id) {
            ensurePlanLocked(authorized)
            val record = currentLocked(authorized.session.id)
            if (request.exerciseId != record.exerciseId) throw ApiException.conflict("Можно показать только текущее задание")
            val updated = when (record.status) {
                ExerciseStatus.PENDING -> record.copy(status = ExerciseStatus.SHOWN, shownAt = clock.instant())
                    .also { exercises.updateSessionExercise(it) }
                ExerciseStatus.SHOWN -> record // idempotent repeat of show
                ExerciseStatus.RUNNING -> throw ApiException.conflict("Упражнение уже запущено")
                ExerciseStatus.COMPLETED -> throw ApiException.conflict("Упражнение уже завершено")
            }
            ShowExerciseResponse(updated.id.toString(), updated.exerciseId, updated.status)
        }
    }

    suspend fun start(authorized: AuthorizedSession, request: StartExerciseRequest): StartExerciseResponse {
        requireSpecialist(authorized)
        val recordId = request.sessionExerciseId.toUuid()
        return locks.withLock(authorized.session.id) {
            ensurePlanLocked(authorized)
            val record = currentLocked(authorized.session.id).takeIf { it.id == recordId }
                ?: throw ApiException.notFound("Упражнение занятия не найдено")
            if (record.status != ExerciseStatus.SHOWN) {
                throw ApiException.conflict(
                    when (record.status) {
                        ExerciseStatus.PENDING -> "Упражнение ещё не показано"
                        ExerciseStatus.RUNNING -> "Упражнение уже запущено"
                        ExerciseStatus.COMPLETED -> "Упражнение уже завершено"
                        ExerciseStatus.SHOWN -> error("unreachable")
                    }
                )
            }
            val started = clock.instant()
            val updated = record.copy(status = ExerciseStatus.RUNNING, startedAt = started)
            exercises.updateSessionExercise(updated)
            if (updated.activityType() == ActivityType.WHITEBOARD) checkNotNull(whiteboards) { "Whiteboard service is required" }.initialize(updated)
            StartExerciseResponse(updated.id.toString(), updated.status, started.toString())
        }
    }

    suspend fun answer(authorized: AuthorizedSession, request: AnswerExerciseRequest): AnswerExerciseResponse {
        if (authorized.role != DeviceRole.CHILD) throw ApiException.forbidden()
        val sessionExerciseId = request.sessionExerciseId.toUuid()
        val eventId = request.clientEventId.toUuid()
        return locks.withLock(authorized.session.id) {
            ensurePlanLocked(authorized)
            val record = currentLocked(authorized.session.id).takeIf { it.id == sessionExerciseId }
                ?: throw ApiException.notFound("Упражнение занятия не найдено")
            val type = record.activityType()
            if (type == ActivityType.WHITEBOARD) throw ApiException.conflict("Это упражнение завершается специалистом")
            // Only MEMORY, SORT and SHADOW carry a second value; for the rest it is ignored and never persisted.
            val secondary = request.secondaryValue.takeIf { type == ActivityType.MEMORY || type == ActivityType.SORT || type == ActivityType.SHADOW }
            exercises.findAttemptByClientEventId(eventId)?.let { duplicate ->
                if (duplicate.sessionExerciseId != sessionExerciseId || duplicate.selectedOptionId != request.selectedOptionId || duplicate.secondaryValue != secondary) {
                    throw ApiException.badRequest("Некорректный повтор ответа")
                }
                return@withLock responseFor(record, exercises.findAttempts(record.id), duplicate, record.status)
            }
            if (record.status == ExerciseStatus.COMPLETED) throw ApiException.conflict("Упражнение уже завершено")
            if (record.status != ExerciseStatus.RUNNING || record.startedAt == null) {
                throw ApiException.conflict("Упражнение ещё не началось")
            }
            val previous = exercises.findAttempts(record.id)
            val isCorrect = evaluate(record, request.selectedOptionId, secondary, previous)
            val acceptedAt = clock.instant()
            val attempt = ExerciseAttemptRecord(
                id = UUID.randomUUID(), sessionExerciseId = record.id, selectedOptionId = request.selectedOptionId,
                attemptNumber = previous.size + 1,
                isCorrect = isCorrect,
                responseTimeMs = Duration.between(record.startedAt, acceptedAt).toMillis().coerceAtLeast(0),
                clientEventId = eventId, createdAt = acceptedAt, secondaryValue = secondary
            )
            if (!exercises.createAttempt(attempt)) {
                val duplicate = checkNotNull(exercises.findAttemptByClientEventId(eventId))
                return@withLock responseFor(record, exercises.findAttempts(record.id), duplicate, record.status)
            }
            val attempts = previous + attempt
            val progress = progressFor(record, attempts)
            val completed = attempt.isCorrect && progress.done >= progress.total
            val status = if (completed) ExerciseStatus.COMPLETED else ExerciseStatus.RUNNING
            if (completed) exercises.updateSessionExercise(record.copy(status = status, completedAt = acceptedAt))
            responseFor(record, attempts, attempt, status)
        }
    }

    /** Validates the child's move against the immutable snapshot and decides whether it is correct. */
    private suspend fun evaluate(record: SessionExerciseRecord, selectedOptionId: String, secondary: String?, previous: List<ExerciseAttemptRecord>): Boolean {
        val type = record.activityType()
        if (type.isChoiceLike) {
            val definition = definitionFor(record)
            val option = definition.options.firstOrNull { it.id == selectedOptionId } ?: throw foreignOption()
            return option.id == definition.correctOptionId
        }
        val options = checkNotNull(record.snapshot).options
        val solved = progressFor(record, previous).solvedOptionIds
        return when (type) {
            ActivityType.MEMORY -> {
                val first = options.firstOrNull { it.id == selectedOptionId } ?: throw foreignOption()
                val second = options.firstOrNull { it.id == secondary } ?: throw foreignOption()
                first.id == second.id && first.id !in solved
            }
            ActivityType.SORT -> {
                val item = options.firstOrNull { it.id == selectedOptionId } ?: throw foreignOption()
                if (secondary != GroupA && secondary != GroupB) throw ApiException.badRequest("Нужно указать корзину A или B")
                if (item.id in solved) throw ApiException.conflict("Уже разложено")
                item.groupKey == secondary
            }
            ActivityType.WORD_BUILD -> {
                val tile = options.firstOrNull { it.id == selectedOptionId } ?: throw foreignOption()
                val expected = options.firstOrNull { it.sequenceIndex == solved.size + 1 } ?: return false
                tile.id !in solved && tile.label.orEmpty().equals(expected.label.orEmpty(), ignoreCase = true)
            }
            ActivityType.POP -> {
                val balloon = options.firstOrNull { it.id == selectedOptionId } ?: throw foreignOption()
                if (balloon.id in solved) throw ApiException.conflict("Уже лопнул")
                balloon.isTarget == true
            }
            ActivityType.SYLLABLES -> {
                val taps = syllableTaps(selectedOptionId) ?: throw ApiException.badRequest("Некорректное число ударов")
                taps == checkNotNull(record.snapshot).gameConfig?.syllables.orEmpty().size
            }
            ActivityType.SHADOW -> {
                val picture = options.firstOrNull { it.id == selectedOptionId } ?: throw foreignOption()
                val shadow = options.firstOrNull { it.id == secondary } ?: throw foreignOption()
                if (picture.id in solved) throw ApiException.conflict("Уже на месте")
                picture.id == shadow.id
            }
            ActivityType.MAZE -> when (selectedOptionId) {
                MazeFinish -> true
                MazeOffPath -> false
                else -> throw ApiException.badRequest("Некорректное действие в лабиринте")
            }
            else -> throw ApiException.conflict("Это упражнение завершается специалистом")
        }
    }

    private data class GameProgress(val done: Int, val total: Int, val solvedOptionIds: List<String>)

    /** Progress is derived from the attempt log only, so reconnecting clients restore the same board. */
    private fun progressFor(record: SessionExerciseRecord, attempts: List<ExerciseAttemptRecord>): GameProgress {
        val correct = attempts.filter { it.isCorrect }.map { it.selectedOptionId }
        val options = record.snapshot?.options.orEmpty()
        return when (record.activityType()) {
            ActivityType.WHITEBOARD -> GameProgress(0, 0, emptyList())
            ActivityType.MEMORY, ActivityType.SORT, ActivityType.SHADOW -> correct.distinct().let { GameProgress(it.size, options.size, it) }
            ActivityType.POP -> correct.distinct().let { GameProgress(it.size, options.count { option -> option.isTarget == true }, it) }
            ActivityType.WORD_BUILD -> GameProgress(correct.size, options.count { it.sequenceIndex != null }, correct)
            else -> GameProgress(if (correct.isEmpty()) 0 else 1, 1, emptyList())
        }
    }

    /** Rebuilds the response as it was when [attempt] was accepted (used for idempotent replays too). */
    private suspend fun responseFor(record: SessionExerciseRecord, attempts: List<ExerciseAttemptRecord>, attempt: ExerciseAttemptRecord, status: ExerciseStatus): AnswerExerciseResponse {
        val progress = progressFor(record, attempts.filter { it.attemptNumber <= attempt.attemptNumber })
        return AnswerExerciseResponse(
            attempt.sessionExerciseId.toString(), attempt.selectedOptionId, labelFor(record, attempt), attempt.isCorrect,
            attempt.attemptNumber, attempt.responseTimeMs, status, attempt.secondaryValue, progress.done, progress.total
        )
    }

    private fun foreignOption() = ApiException.badRequest("Выбранный вариант не относится к упражнению")

    /** SYLLABLES: the child's drum taps arrive as a decimal string, "1".."12". */
    private fun syllableTaps(value: String): Int? = value.takeIf { it.matches(TapCountPattern) }?.toInt()?.takeIf { it in 1..12 }

    suspend fun next(authorized: AuthorizedSession, request: NextExerciseRequest): ExerciseStateResponse {
        requireSpecialist(authorized)
        val currentId = request.currentSessionExerciseId.toUuid()
        return locks.withLock(authorized.session.id) {
            ensurePlanLocked(authorized)
            val current = currentLocked(authorized.session.id)
            if (current.id != currentId) throw ApiException.conflict("Текущее задание уже изменилось")
            if (current.status != ExerciseStatus.COMPLETED) throw ApiException.conflict("Сначала завершите текущее задание")
            if (current.position == exercises.findSessionExercises(authorized.session.id).size) throw ApiException.planCompleted()
            val next = exercises.findSessionExerciseByPosition(authorized.session.id, current.position + 1)
                ?: throw ApiException.conflict("Следующее задание не найдено")
            if (next.status != ExerciseStatus.PENDING) throw ApiException.conflict("Следующее задание уже недоступно")
            if (!exercises.setCurrentExercise(authorized.session.id, next.id)) {
                throw ApiException.conflict("Не удалось переключить задание")
            }
            stateForLocked(authorized)
        }
    }

    /** WHITEBOARD has no correctness model: only the specialist may complete it explicitly. */
    suspend fun completeWhiteboard(authorized: AuthorizedSession, sessionExerciseId: String): ExerciseStateResponse {
        requireSpecialist(authorized)
        val id = sessionExerciseId.toUuid()
        return locks.withLock(authorized.session.id) {
            ensurePlanLocked(authorized)
            val record = currentLocked(authorized.session.id).takeIf { it.id == id }
                ?: throw ApiException.notFound("Упражнение занятия не найдено")
            if (record.activityType() != ActivityType.WHITEBOARD) throw ApiException.conflict("Текущее упражнение не является доской")
            if (record.status != ExerciseStatus.RUNNING) throw ApiException.conflict("Доска ещё не запущена или уже завершена")
            exercises.updateSessionExercise(record.copy(status = ExerciseStatus.COMPLETED, completedAt = clock.instant()))
            whiteboards?.discardActiveStrokes(record.id)
            stateForLocked(authorized)
        }
    }

    suspend fun stateFor(authorized: AuthorizedSession): ExerciseStateResponse {
        if (!authorized.session.isManaged) ensureDefaultExercisePlan(authorized.session.id)
        return stateForLocked(authorized)
    }

    private suspend fun stateForLocked(authorized: AuthorizedSession): ExerciseStateResponse {
        val record = exercises.findCurrentSessionExercise(authorized.session.id) ?: return ExerciseStateResponse()
        val definition = definitionFor(record)
        val attempts = exercises.findAttempts(record.id)
        val latest = attempts.maxByOrNull { it.attemptNumber }
        val progress = progressFor(record, attempts)
        val total = exercises.countSessionExercises(authorized.session.id)
        val planCompleted = exercises.countCompletedSessionExercises(authorized.session.id) == total
        val childCanSeeExercise = record.status != ExerciseStatus.PENDING
        return ExerciseStateResponse(
            sessionExerciseId = record.id.toString(), exerciseStatus = record.status,
            exercise = dtoFor(record, definition).takeIf { authorized.role == DeviceRole.SPECIALIST || childCanSeeExercise },
            correctOptionId = definition.correctOptionId.takeIf { authorized.role == DeviceRole.SPECIALIST && record.activityType().isChoiceLike },
            latestAnswer = latest?.let { responseFor(record, attempts, it, record.status) },
            attemptCount = attempts.size, startedAt = record.startedAt?.toString(),
            currentPosition = record.position, totalExercises = total, hasPrevious = record.position > 1,
            hasNext = record.position < total, planCompleted = planCompleted,
            whiteboardState = if (record.activityType() == ActivityType.WHITEBOARD) whiteboards?.snapshotFor(record) else null,
            progressDone = progress.done, progressTotal = progress.total, solvedOptionIds = progress.solvedOptionIds
        )
    }

    suspend fun specialistExercise(authorized: AuthorizedSession): SpecialistExerciseDto {
        requireSpecialist(authorized)
        val state = stateFor(authorized)
        return SpecialistExerciseDto(
            exercise = checkNotNull(state.exercise), correctOptionId = state.correctOptionId
        )
    }

    suspend fun summary(authorized: AuthorizedSession): SessionSummaryResponse {
        requireSpecialist(authorized)
        if (!authorized.session.isManaged) ensureDefaultExercisePlan(authorized.session.id)
        val plan = exercises.findSessionExercises(authorized.session.id)
        if (plan.isEmpty() || plan.any { it.status != ExerciseStatus.COMPLETED }) {
            throw ApiException.planNotCompleted()
        }
        val items = buildList { for (record in plan.sortedBy { it.position }) {
            val definition = definitionFor(record)
            val attempts = exercises.findAttempts(record.id)
            val startedAt = checkNotNull(record.startedAt); val completedAt = checkNotNull(record.completedAt)
            if (record.activityType() == ActivityType.WHITEBOARD) {
                val metrics = whiteboards?.metrics(record.id)
                add(ExerciseSummaryItemDto(
                    position = record.position, exerciseId = record.exerciseId, activityType = ActivityType.WHITEBOARD,
                    instructionText = definition.instructionText, attemptCount = 0, incorrectAttempts = 0,
                    firstAttemptCorrect = false, startedAt = startedAt.toString(), completedAt = completedAt.toString(),
                    durationMs = Duration.between(startedAt, completedAt).toMillis().coerceAtLeast(0),
                    strokeCount = metrics?.total ?: 0, childStrokeCount = metrics?.child ?: 0, specialistStrokeCount = metrics?.specialist ?: 0
                ))
            } else if (record.activityType().isGame) {
                val snapshot = checkNotNull(record.snapshot)
                val incorrect = attempts.count { !it.isCorrect }
                val duration = Duration.between(startedAt, completedAt).toMillis().coerceAtLeast(0)
                val correctLabel = if (snapshot.activityType == ActivityType.ODD_ONE || snapshot.activityType == ActivityType.REMEMBER || snapshot.activityType == ActivityType.COUNT) {
                    snapshot.options.firstOrNull { it.id == snapshot.correctOptionId }?.label
                } else null
                add(ExerciseSummaryItemDto(
                    position = record.position, exerciseId = record.exerciseId, activityType = snapshot.activityType,
                    instructionText = snapshot.instructionText, correctOptionLabel = correctLabel ?: snapshot.title,
                    attemptCount = attempts.size, incorrectAttempts = incorrect, firstAttemptCorrect = incorrect == 0,
                    timeToCorrectMs = duration, startedAt = startedAt.toString(), completedAt = completedAt.toString(), durationMs = duration
                ))
            } else {
                val correct = attempts.firstOrNull { it.isCorrect } ?: throw ApiException.planNotCompleted()
                add(ExerciseSummaryItemDto(
                    position = record.position, exerciseId = record.exerciseId, activityType = ActivityType.SINGLE_CHOICE, instructionText = definition.instructionText,
                    correctOptionLabel = definition.options.first { it.id == definition.correctOptionId }.label,
                    attemptCount = attempts.size, incorrectAttempts = attempts.count { !it.isCorrect },
                    firstAttemptCorrect = attempts.firstOrNull()?.isCorrect == true, timeToCorrectMs = correct.responseTimeMs,
                    startedAt = startedAt.toString(), completedAt = completedAt.toString(), durationMs = Duration.between(startedAt, completedAt).toMillis().coerceAtLeast(0)
                ))
            }
        } }
        val totalAttempts = items.sumOf { it.attemptCount }
        val firstCorrect = items.count { it.firstAttemptCorrect }
        val started = items.minOf { java.time.Instant.parse(it.startedAt) }
        val completed = items.maxOf { java.time.Instant.parse(it.completedAt) }
        return SessionSummaryResponse(
            sessionId = authorized.session.id.toString(), childName = authorized.session.childName,
            completedExercises = items.size, totalExercises = plan.size, totalAttempts = totalAttempts,
            incorrectAttempts = items.sumOf { it.incorrectAttempts }, firstAttemptCorrectCount = firstCorrect,
            firstAttemptCorrectPercent = firstCorrect * 100 / plan.size,
            activeDurationMs = Duration.between(started, completed).toMillis().coerceAtLeast(0),
            startedAt = started.toString(), completedAt = completed.toString(), exercises = items
        )
    }

    private suspend fun currentLocked(sessionId: UUID): SessionExerciseRecord =
        exercises.findCurrentSessionExercise(sessionId) ?: throw ApiException.conflict("Текущее задание не найдено")

    private suspend fun definitionFor(record: SessionExerciseRecord): ExerciseRecord = record.snapshot?.let { snapshot ->
        ExerciseRecord(snapshot.sourceExerciseId, snapshot.instructionText, snapshot.localAudioAssetKey, snapshot.correctOptionId.orEmpty(), true, clock.instant(),
            snapshot.options.map { option -> kz.oyla.server.repository.ExerciseOptionRecord(option.id, snapshot.sourceExerciseId, option.label.orEmpty(), option.localImageAssetKey.orEmpty(), option.position) })
    } ?: exercises.findExercise(record.exerciseId) ?: throw ApiException.notFound("Упражнение не найдено")

    private suspend fun dtoFor(record: SessionExerciseRecord, definition: ExerciseRecord): ExerciseDto = record.snapshot?.let { snapshot ->
        ExerciseDto(snapshot.sourceExerciseId, snapshot.instructionText, snapshot.localAudioAssetKey,
            snapshot.options.sortedBy { it.position }.map { option ->
                ExerciseOptionDto(option.id, option.label.orEmpty(), option.localImageAssetKey.orEmpty(), option.position, option.imageUrl, option.groupKey, option.sequenceIndex,
                    isTarget = if (snapshot.activityType == ActivityType.POP) option.isTarget == true else null)
            },
            snapshot.title, snapshot.activityType.name, snapshot.audioUrl, snapshot.whiteboardConfig?.toDto(), gameConfigDto(snapshot))
    } ?: definition.toDto()

    /** Game settings for the tablets; REMEMBER also reveals its target so the child can see it first. */
    private fun gameConfigDto(snapshot: ExerciseSnapshot): GameExerciseConfigDto? {
        if (!snapshot.activityType.isGame) return null
        val config = snapshot.gameConfig ?: GameExerciseConfig()
        val remember = snapshot.activityType == ActivityType.REMEMBER
        return GameExerciseConfigDto(
            groupLabels = config.groupLabels,
            showTargetMs = if (remember) config.showTargetMs ?: GameExerciseConfig.DefaultShowTargetMs else config.showTargetMs,
            mazeLayout = config.mazeLayout, imageAssetKey = config.imageAssetKey, imageAssetId = config.imageAssetId,
            imageUrl = config.imageUrl ?: config.imageAssetId?.let { "/api/v1/media/$it" },
            targetOptionId = if (remember) snapshot.correctOptionId else null,
            syllables = config.syllables, countValue = config.countValue
        )
    }

    private suspend fun labelFor(record: SessionExerciseRecord, attempt: ExerciseAttemptRecord): String {
        val optionId = attempt.selectedOptionId
        val snapshot = record.snapshot
        return when (record.activityType()) {
            ActivityType.MAZE -> if (optionId == MazeFinish) "Финиш" else "Сошёл с дорожки"
            ActivityType.SORT -> {
                val item = snapshot?.options?.firstOrNull { it.id == optionId }?.label ?: optionId
                val groupIndex = when (attempt.secondaryValue) { GroupA -> 0; GroupB -> 1; else -> -1 }
                val group = snapshot?.gameConfig?.groupLabels?.getOrNull(groupIndex) ?: attempt.secondaryValue.orEmpty()
                "$item → $group"
            }
            ActivityType.SHADOW -> {
                val picture = snapshot?.options?.firstOrNull { it.id == optionId }?.label ?: optionId
                val shadow = snapshot?.options?.firstOrNull { it.id == attempt.secondaryValue }?.label ?: attempt.secondaryValue.orEmpty()
                "$picture → $shadow"
            }
            ActivityType.SYLLABLES -> optionId.toIntOrNull()?.let { "$it ${beatsWord(it)}" } ?: optionId
            ActivityType.MEMORY, ActivityType.WORD_BUILD, ActivityType.POP -> snapshot?.options?.firstOrNull { it.id == optionId }?.label ?: optionId
            else -> definitionFor(record).options.firstOrNull { it.id == optionId }?.label ?: optionId
        }
    }

    private fun beatsWord(count: Int): String = when {
        count % 10 == 1 && count % 100 != 11 -> "удар"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "удара"
        else -> "ударов"
    }

    private fun requireSpecialist(authorized: AuthorizedSession) {
        if (authorized.role != DeviceRole.SPECIALIST) throw ApiException.forbidden()
    }
    private fun String.toUuid(): UUID = runCatching { UUID.fromString(this) }
        .getOrElse { throw ApiException.badRequest("Некорректный идентификатор упражнения") }
    private fun ExerciseRecord.toDto() = ExerciseDto(
        id, instructionText, audioAssetKey,
        options.sortedBy { it.position }.map { ExerciseOptionDto(it.id, it.label, it.imageAssetKey, it.position) }
    )
    private fun SessionExerciseRecord.activityType() = snapshot?.activityType ?: ActivityType.SINGLE_CHOICE
    private fun kz.oyla.server.model.WhiteboardExerciseConfig.toDto() = WhiteboardExerciseConfigDto(backgroundAssetId, backgroundUrl, childDrawingInitiallyEnabled, availableColors, defaultColor, defaultBrushSize, allowEraser, allowClear, traceTemplate)
}
