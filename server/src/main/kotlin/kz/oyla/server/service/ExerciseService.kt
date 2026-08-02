package kz.oyla.server.service

import java.time.Clock
import java.time.Duration
import java.time.Instant
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
    private val sessionMutexes = mutableMapOf<UUID, Mutex>()
    private fun mutexFor(id: UUID): Mutex = synchronized(sessionMutexes) { sessionMutexes.getOrPut(id) { Mutex() } }

    suspend fun getExercise(id: String): ExerciseDto = exercises.findExercise(id)
        ?.takeIf { it.isActive }
        ?.toDto()
        ?: throw ApiException.notFound("Упражнение не найдено")

    suspend fun show(authorized: AuthorizedSession, request: ShowExerciseRequest): ShowExerciseResponse {
        requireSpecialist(authorized)
        if (authorized.session.status != SessionStatus.READY || authorized.session.childDeviceId == null) {
            throw ApiException.conflict("Нельзя показать задание: ребёнок не подключён")
        }
        val definition = exercises.findExercise(request.exerciseId)?.takeIf { it.isActive }
            ?: throw ApiException.notFound("Упражнение не найдено")
        return mutexFor(authorized.session.id).withLock {
            val existing = exercises.findSessionExercise(authorized.session.id)
            val record = when {
                existing == null -> SessionExerciseRecord(
                    id = UUID.randomUUID(), sessionId = authorized.session.id, exerciseId = definition.id,
                    status = ExerciseStatus.SHOWN, shownAt = clock.instant(), startedAt = null,
                    completedAt = null, createdAt = clock.instant()
                ).let { created ->
                    if (exercises.createSessionExercise(created)) created else {
                        val raced = exercises.findSessionExercise(authorized.session.id)
                            ?: throw ApiException.conflict("Не удалось создать упражнение")
                        if (raced.exerciseId != definition.id) throw ApiException.conflict("В этом занятии уже создано другое упражнение")
                        raced
                    }
                }
                existing.exerciseId == definition.id -> existing
                else -> throw ApiException.conflict("В этом занятии уже создано другое упражнение")
            }
            ShowExerciseResponse(record.id.toString(), record.exerciseId, record.status)
        }
    }

    suspend fun start(authorized: AuthorizedSession, request: StartExerciseRequest): StartExerciseResponse {
        requireSpecialist(authorized)
        val recordId = request.sessionExerciseId.toUuid()
        return mutexFor(authorized.session.id).withLock {
            val record = exercises.findSessionExercise(authorized.session.id)
                ?.takeIf { it.id == recordId }
                ?: throw ApiException.notFound("Упражнение занятия не найдено")
            when (record.status) {
                ExerciseStatus.SHOWN -> Unit
                ExerciseStatus.PENDING -> throw ApiException.conflict("Упражнение ещё не началось")
                ExerciseStatus.RUNNING -> throw ApiException.conflict("Упражнение уже запущено")
                ExerciseStatus.COMPLETED -> throw ApiException.conflict("Занятие уже завершено")
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
            exercises.findAttemptByClientEventId(eventId)?.let { duplicate ->
                if (duplicate.sessionExerciseId != sessionExerciseId) throw ApiException.badRequest("Некорректный ответ")
                return@withLock duplicate.toResponse(exercises.findSessionExercise(authorized.session.id)?.status ?: ExerciseStatus.RUNNING)
            }
            val record = exercises.findSessionExercise(authorized.session.id)
                ?.takeIf { it.id == sessionExerciseId }
                ?: throw ApiException.notFound("Упражнение занятия не найдено")
            if (record.status == ExerciseStatus.COMPLETED) throw ApiException.conflict("Занятие уже завершено")
            if (record.status != ExerciseStatus.RUNNING || record.startedAt == null) {
                throw ApiException.conflict("Упражнение ещё не началось")
            }
            val definition = exercises.findExercise(record.exerciseId) ?: throw ApiException.notFound("Упражнение не найдено")
            val option = definition.options.firstOrNull { it.id == request.selectedOptionId }
                ?: throw ApiException.badRequest("Выбранный вариант не относится к упражнению")
            val acceptedAt = clock.instant()
            val responseTime = Duration.between(record.startedAt, acceptedAt).toMillis().coerceAtLeast(0)
            val attempt = ExerciseAttemptRecord(
                id = UUID.randomUUID(), sessionExerciseId = record.id, selectedOptionId = option.id,
                attemptNumber = exercises.countAttempts(record.id) + 1,
                isCorrect = option.id == definition.correctOptionId, responseTimeMs = responseTime,
                clientEventId = eventId, createdAt = acceptedAt
            )
            if (!exercises.createAttempt(attempt)) {
                return@withLock checkNotNull(exercises.findAttemptByClientEventId(eventId)).toResponse(record.status)
            }
            val status = if (attempt.isCorrect) ExerciseStatus.COMPLETED else ExerciseStatus.RUNNING
            if (attempt.isCorrect) exercises.updateSessionExercise(
                record.copy(status = ExerciseStatus.COMPLETED, completedAt = acceptedAt)
            )
            attempt.toResponse(status, option.label)
        }
    }

    suspend fun stateFor(authorized: AuthorizedSession): ExerciseStateResponse {
        val sessionExercise = exercises.findSessionExercise(authorized.session.id) ?: return ExerciseStateResponse()
        val definition = exercises.findExercise(sessionExercise.exerciseId) ?: return ExerciseStateResponse()
        val latest = exercises.findLatestAttempt(sessionExercise.id)
        return ExerciseStateResponse(
            sessionExerciseId = sessionExercise.id.toString(), exerciseStatus = sessionExercise.status,
            exercise = definition.toDto(),
            correctOptionId = definition.correctOptionId.takeIf { authorized.role == DeviceRole.SPECIALIST },
            latestAnswer = latest?.toResponse(sessionExercise.status, definition.options.first { it.id == latest.selectedOptionId }.label),
            attemptCount = exercises.countAttempts(sessionExercise.id), startedAt = sessionExercise.startedAt?.toString()
        )
    }

    suspend fun specialistExercise(authorized: AuthorizedSession): SpecialistExerciseDto {
        requireSpecialist(authorized)
        val state = stateFor(authorized)
        return SpecialistExerciseDto(
            exercise = state.exercise ?: getExercise("sound-r-rocket"),
            correctOptionId = state.correctOptionId ?: "rocket"
        )
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
    private fun ExerciseAttemptRecord.toResponse(status: ExerciseStatus, label: String = selectedOptionId) = AnswerExerciseResponse(
        sessionExerciseId.toString(), selectedOptionId, label, isCorrect, attemptNumber, responseTimeMs, status
    )
}
