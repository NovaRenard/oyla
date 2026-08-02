package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.ExerciseStatus

class InMemoryExerciseRepository : ExerciseRepository {
    private val exercises = linkedMapOf(
        "sound-r-rocket" to ExerciseRecord(
            id = "sound-r-rocket",
            instructionText = "Найди картинку, в названии которой есть звук «Р»",
            audioAssetKey = "exercise_sound_r",
            correctOptionId = "rocket",
            isActive = true,
            createdAt = Instant.EPOCH,
            options = listOf(
                ExerciseOptionRecord("rocket", "sound-r-rocket", "ракета", "exercise_rocket", 1),
                ExerciseOptionRecord("cat", "sound-r-rocket", "кот", "exercise_cat", 2),
                ExerciseOptionRecord("house", "sound-r-rocket", "дом", "exercise_house", 3),
                ExerciseOptionRecord("fox", "sound-r-rocket", "лиса", "exercise_fox", 4)
            )
        )
    )
    private val sessionExercises = linkedMapOf<UUID, SessionExerciseRecord>()
    private val attempts = linkedMapOf<UUID, ExerciseAttemptRecord>()

    override suspend fun findExercise(id: String) = synchronized(this) { exercises[id] }
    override suspend fun findSessionExercise(sessionId: UUID) = synchronized(this) {
        sessionExercises.values.firstOrNull { it.sessionId == sessionId }
    }
    override suspend fun createSessionExercise(record: SessionExerciseRecord) = synchronized(this) {
        if (sessionExercises.values.any { it.sessionId == record.sessionId }) false else {
            sessionExercises[record.id] = record
            true
        }
    }
    override suspend fun updateSessionExercise(record: SessionExerciseRecord) = synchronized(this) {
        if (sessionExercises.containsKey(record.id)) {
            sessionExercises[record.id] = record
            true
        } else false
    }
    override suspend fun findAttemptByClientEventId(clientEventId: UUID) = synchronized(this) {
        attempts.values.firstOrNull { it.clientEventId == clientEventId }
    }
    override suspend fun findLatestAttempt(sessionExerciseId: UUID) = synchronized(this) {
        attempts.values.filter { it.sessionExerciseId == sessionExerciseId }.maxByOrNull { it.attemptNumber }
    }
    override suspend fun countAttempts(sessionExerciseId: UUID) = synchronized(this) {
        attempts.values.count { it.sessionExerciseId == sessionExerciseId }
    }
    override suspend fun createAttempt(record: ExerciseAttemptRecord) = synchronized(this) {
        if (attempts.values.any { it.clientEventId == record.clientEventId }) false else {
            attempts[record.id] = record
            true
        }
    }
}
