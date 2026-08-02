package kz.oyla.server.service

import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.ExerciseStatus
import kz.oyla.server.model.SessionStatus
import kz.oyla.server.model.dto.AnswerExerciseRequest
import kz.oyla.server.model.dto.AnswerExerciseResponse
import kz.oyla.server.model.dto.ExerciseDto
import kz.oyla.server.model.dto.ExerciseOptionDto
import kz.oyla.server.model.dto.ExerciseStateResponse
import kz.oyla.server.model.dto.ExerciseSummaryItemDto
import kz.oyla.server.model.dto.NextExerciseRequest
import kz.oyla.server.model.dto.SessionSummaryResponse
import kz.oyla.server.model.dto.ShowExerciseRequest
import kz.oyla.server.model.dto.ShowExerciseResponse
import kz.oyla.server.model.dto.SpecialistExerciseDto
import kz.oyla.server.model.dto.StartExerciseRequest
import kz.oyla.server.model.dto.StartExerciseResponse
import kz.oyla.server.repository.ExerciseAttemptRecord
import kz.oyla.server.repository.ExerciseRecord
import kz.oyla.server.repository.ExerciseRepository
import kz.oyla.server.repository.SessionExerciseRecord

class ExerciseService(
    private val exercises: ExerciseRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    companion object {
        val defaultPlan = listOf(
            "sound-r-rocket", "sound-r-fish", "sound-l-lamp", "sound-s-dog", "sound-sh-ball"
        )
    }

    private val sessionMutexes = mutableMapOf<UUID, Mutex>()
    private fun mutexFor(id: UUID): Mutex = synchronized(sessionMutexes) { sessionMutexes.getOrPut(id) { Mutex() } }

    /** Creates missing plan positions only; legacy position 1 and its attempts stay untouched. */
    suspend fun ensureDefaultExercisePlan(sessionId: UUID) = mutexFor(sessionId).withLock {
        ensureDefaultExercisePlanLocked(sessionId)
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

    suspend fun getExercise(id: String): ExerciseDto = exercises.findExercise(id)
        ?.takeIf { it.isActive }?.toDto() ?: throw ApiException.notFound("Упражнение не найдено")

    suspend fun show(authorized: AuthorizedSession, request: ShowExerciseRequest): ShowExerciseResponse {
        requireSpecialist(authorized)
        if (authorized.session.status != SessionStatus.READY || authorized.session.childDeviceId == null) {
            throw ApiException.conflict("Нельзя показать задание: ребёнок не подключён")
        }
        return mutexFor(authorized.session.id).withLock {
            ensureDefaultExercisePlanLocked(authorized.session.id)
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
        return mutexFor(authorized.session.id).withLock {
            ensureDefaultExercisePlanLocked(authorized.session.id)
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
            StartExerciseResponse(updated.id.toString(), updated.status, started.toString())
        }
    }

    suspend fun answer(authorized: AuthorizedSession, request: AnswerExerciseRequest): AnswerExerciseResponse {
        if (authorized.role != DeviceRole.CHILD) throw ApiException.forbidden()
        val sessionExerciseId = request.sessionExerciseId.toUuid()
        val eventId = request.clientEventId.toUuid()
        return mutexFor(authorized.session.id).withLock {
            ensureDefaultExercisePlanLocked(authorized.session.id)
            val record = currentLocked(authorized.session.id).takeIf { it.id == sessionExerciseId }
                ?: throw ApiException.notFound("Упражнение занятия не найдено")
            exercises.findAttemptByClientEventId(eventId)?.let { duplicate ->
                if (duplicate.sessionExerciseId != sessionExerciseId || duplicate.selectedOptionId != request.selectedOptionId) {
                    throw ApiException.badRequest("Некорректный повтор ответа")
                }
                return@withLock duplicate.toResponse(record.status, labelFor(record.exerciseId, duplicate.selectedOptionId))
            }
            if (record.status == ExerciseStatus.COMPLETED) throw ApiException.conflict("Упражнение уже завершено")
            if (record.status != ExerciseStatus.RUNNING || record.startedAt == null) {
                throw ApiException.conflict("Упражнение ещё не началось")
            }
            val definition = exercises.findExercise(record.exerciseId) ?: throw ApiException.notFound("Упражнение не найдено")
            val option = definition.options.firstOrNull { it.id == request.selectedOptionId }
                ?: throw ApiException.badRequest("Выбранный вариант не относится к упражнению")
            val acceptedAt = clock.instant()
            val attempt = ExerciseAttemptRecord(
                id = UUID.randomUUID(), sessionExerciseId = record.id, selectedOptionId = option.id,
                attemptNumber = exercises.countAttempts(record.id) + 1,
                isCorrect = option.id == definition.correctOptionId,
                responseTimeMs = Duration.between(record.startedAt, acceptedAt).toMillis().coerceAtLeast(0),
                clientEventId = eventId, createdAt = acceptedAt
            )
            if (!exercises.createAttempt(attempt)) {
                return@withLock checkNotNull(exercises.findAttemptByClientEventId(eventId))
                    .toResponse(record.status, option.label)
            }
            val status = if (attempt.isCorrect) ExerciseStatus.COMPLETED else ExerciseStatus.RUNNING
            if (attempt.isCorrect) exercises.updateSessionExercise(record.copy(status = status, completedAt = acceptedAt))
            attempt.toResponse(status, option.label)
        }
    }

    suspend fun next(authorized: AuthorizedSession, request: NextExerciseRequest): ExerciseStateResponse {
        requireSpecialist(authorized)
        val currentId = request.currentSessionExerciseId.toUuid()
        return mutexFor(authorized.session.id).withLock {
            ensureDefaultExercisePlanLocked(authorized.session.id)
            val current = currentLocked(authorized.session.id)
            if (current.id != currentId) throw ApiException.conflict("Текущее задание уже изменилось")
            if (current.status != ExerciseStatus.COMPLETED) throw ApiException.conflict("Сначала завершите текущее задание")
            if (current.position == defaultPlan.size) throw ApiException.planCompleted()
            val next = exercises.findSessionExerciseByPosition(authorized.session.id, current.position + 1)
                ?: throw ApiException.conflict("Следующее задание не найдено")
            if (next.status != ExerciseStatus.PENDING) throw ApiException.conflict("Следующее задание уже недоступно")
            if (!exercises.setCurrentExercise(authorized.session.id, next.id)) {
                throw ApiException.conflict("Не удалось переключить задание")
            }
            stateForLocked(authorized)
        }
    }

    suspend fun stateFor(authorized: AuthorizedSession): ExerciseStateResponse {
        ensureDefaultExercisePlan(authorized.session.id)
        return stateForLocked(authorized)
    }

    private suspend fun stateForLocked(authorized: AuthorizedSession): ExerciseStateResponse {
        val record = exercises.findCurrentSessionExercise(authorized.session.id) ?: return ExerciseStateResponse()
        val definition = exercises.findExercise(record.exerciseId) ?: throw ApiException.notFound("Упражнение не найдено")
        val latest = exercises.findLatestAttempt(record.id)
        val total = defaultPlan.size
        val planCompleted = exercises.countCompletedSessionExercises(authorized.session.id) == total
        val childCanSeeExercise = record.status != ExerciseStatus.PENDING
        return ExerciseStateResponse(
            sessionExerciseId = record.id.toString(), exerciseStatus = record.status,
            exercise = definition.toDto().takeIf { authorized.role == DeviceRole.SPECIALIST || childCanSeeExercise },
            correctOptionId = definition.correctOptionId.takeIf { authorized.role == DeviceRole.SPECIALIST },
            latestAnswer = latest?.toResponse(record.status, labelFor(record.exerciseId, latest.selectedOptionId)),
            attemptCount = exercises.countAttempts(record.id), startedAt = record.startedAt?.toString(),
            currentPosition = record.position, totalExercises = total, hasPrevious = record.position > 1,
            hasNext = record.position < total, planCompleted = planCompleted
        )
    }

    suspend fun specialistExercise(authorized: AuthorizedSession): SpecialistExerciseDto {
        requireSpecialist(authorized)
        val state = stateFor(authorized)
        return SpecialistExerciseDto(
            exercise = checkNotNull(state.exercise), correctOptionId = checkNotNull(state.correctOptionId)
        )
    }

    suspend fun summary(authorized: AuthorizedSession): SessionSummaryResponse {
        requireSpecialist(authorized)
        ensureDefaultExercisePlan(authorized.session.id)
        val plan = exercises.findSessionExercises(authorized.session.id)
        if (plan.size != defaultPlan.size || plan.any { it.status != ExerciseStatus.COMPLETED }) {
            throw ApiException.planNotCompleted()
        }
        val items = plan.sortedBy { it.position }.map { record ->
            val definition = exercises.findExercise(record.exerciseId) ?: throw ApiException.notFound("Упражнение не найдено")
            val attempts = exercises.findAttempts(record.id)
            val correct = attempts.firstOrNull { it.isCorrect } ?: throw ApiException.planNotCompleted()
            ExerciseSummaryItemDto(
                position = record.position, exerciseId = record.exerciseId, instructionText = definition.instructionText,
                correctOptionLabel = definition.options.first { it.id == definition.correctOptionId }.label,
                attemptCount = attempts.size, incorrectAttempts = attempts.count { !it.isCorrect },
                firstAttemptCorrect = attempts.firstOrNull()?.isCorrect == true,
                timeToCorrectMs = correct.responseTimeMs,
                startedAt = checkNotNull(record.startedAt).toString(), completedAt = checkNotNull(record.completedAt).toString()
            )
        }
        val totalAttempts = items.sumOf { it.attemptCount }
        val firstCorrect = items.count { it.firstAttemptCorrect }
        val started = items.minOf { java.time.Instant.parse(it.startedAt) }
        val completed = items.maxOf { java.time.Instant.parse(it.completedAt) }
        return SessionSummaryResponse(
            sessionId = authorized.session.id.toString(), childName = authorized.session.childName,
            completedExercises = items.size, totalExercises = defaultPlan.size, totalAttempts = totalAttempts,
            incorrectAttempts = items.sumOf { it.incorrectAttempts }, firstAttemptCorrectCount = firstCorrect,
            firstAttemptCorrectPercent = firstCorrect * 100 / defaultPlan.size,
            activeDurationMs = Duration.between(started, completed).toMillis().coerceAtLeast(0),
            startedAt = started.toString(), completedAt = completed.toString(), exercises = items
        )
    }

    private suspend fun currentLocked(sessionId: UUID): SessionExerciseRecord =
        exercises.findCurrentSessionExercise(sessionId) ?: throw ApiException.conflict("Текущее задание не найдено")

    private suspend fun labelFor(exerciseId: String, optionId: String): String = exercises.findExercise(exerciseId)
        ?.options?.firstOrNull { it.id == optionId }?.label ?: optionId

    private fun requireSpecialist(authorized: AuthorizedSession) {
        if (authorized.role != DeviceRole.SPECIALIST) throw ApiException.forbidden()
    }
    private fun String.toUuid(): UUID = runCatching { UUID.fromString(this) }
        .getOrElse { throw ApiException.badRequest("Некорректный идентификатор упражнения") }
    private fun ExerciseRecord.toDto() = ExerciseDto(
        id, instructionText, audioAssetKey,
        options.sortedBy { it.position }.map { ExerciseOptionDto(it.id, it.label, it.imageAssetKey, it.position) }
    )
    private fun ExerciseAttemptRecord.toResponse(status: ExerciseStatus, label: String = selectedOptionId) = AnswerExerciseResponse(
        sessionExerciseId.toString(), selectedOptionId, label, isCorrect, attemptNumber, responseTimeMs, status
    )
}
