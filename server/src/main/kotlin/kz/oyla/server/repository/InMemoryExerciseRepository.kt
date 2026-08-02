package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.ExerciseStatus

/** Test double with the same fixed exercise catalogue as the Flyway seed. */
class InMemoryExerciseRepository : ExerciseRepository {
    private val exercises = fixedExercises().associateBy { it.id }.toMutableMap()
    private val sessionExercises = linkedMapOf<UUID, SessionExerciseRecord>()
    private val attempts = linkedMapOf<UUID, ExerciseAttemptRecord>()

    override suspend fun findExercise(id: String) = synchronized(this) { exercises[id] }
    override suspend fun findSessionExercises(sessionId: UUID) = synchronized(this) {
        sessionExercises.values.filter { it.sessionId == sessionId }.sortedBy { it.position }
    }
    override suspend fun findCurrentSessionExercise(sessionId: UUID) = synchronized(this) {
        sessionExercises.values.singleOrNull { it.sessionId == sessionId && it.isCurrent }
    }
    override suspend fun findSessionExerciseById(id: UUID) = synchronized(this) { sessionExercises[id] }
    override suspend fun findSessionExerciseByPosition(sessionId: UUID, position: Int) = synchronized(this) {
        sessionExercises.values.singleOrNull { it.sessionId == sessionId && it.position == position }
    }
    override suspend fun createSessionExercises(records: List<SessionExerciseRecord>) = synchronized(this) {
        var inserted = 0
        records.forEach { record ->
            if (sessionExercises.values.none { it.sessionId == record.sessionId && it.position == record.position }) {
                sessionExercises[record.id] = record
                inserted++
            }
        }
        inserted
    }
    override suspend fun updateSessionExercise(record: SessionExerciseRecord) = synchronized(this) {
        if (sessionExercises.containsKey(record.id)) { sessionExercises[record.id] = record; true } else false
    }
    override suspend fun setCurrentExercise(sessionId: UUID, sessionExerciseId: UUID) = synchronized(this) {
        val target = sessionExercises[sessionExerciseId]
        if (target?.sessionId != sessionId || target.status != ExerciseStatus.PENDING) return@synchronized false
        sessionExercises.values.filter { it.sessionId == sessionId && it.isCurrent }.forEach { current ->
            sessionExercises[current.id] = current.copy(isCurrent = false)
        }
        sessionExercises[target.id] = target.copy(isCurrent = true)
        true
    }
    override suspend fun countSessionExercises(sessionId: UUID) = synchronized(this) {
        sessionExercises.values.count { it.sessionId == sessionId }
    }
    override suspend fun countCompletedSessionExercises(sessionId: UUID) = synchronized(this) {
        sessionExercises.values.count { it.sessionId == sessionId && it.status == ExerciseStatus.COMPLETED }
    }
    override suspend fun findAttemptByClientEventId(clientEventId: UUID) = synchronized(this) {
        attempts.values.firstOrNull { it.clientEventId == clientEventId }
    }
    override suspend fun findLatestAttempt(sessionExerciseId: UUID) = synchronized(this) {
        attempts.values.filter { it.sessionExerciseId == sessionExerciseId }.maxByOrNull { it.attemptNumber }
    }
    override suspend fun findAttempts(sessionExerciseId: UUID) = synchronized(this) {
        attempts.values.filter { it.sessionExerciseId == sessionExerciseId }.sortedBy { it.attemptNumber }
    }
    override suspend fun countAttempts(sessionExerciseId: UUID) = synchronized(this) {
        attempts.values.count { it.sessionExerciseId == sessionExerciseId }
    }
    override suspend fun createAttempt(record: ExerciseAttemptRecord) = synchronized(this) {
        if (attempts.values.any { it.clientEventId == record.clientEventId }) false else { attempts[record.id] = record; true }
    }

    private fun fixedExercises(): List<ExerciseRecord> = listOf(
        exercise("sound-r-rocket", "Найди картинку, в названии которой есть звук «Р»", "exercise_sound_r", "rocket",
            listOf("rocket" to "ракета" to "exercise_rocket", "cat" to "кот" to "exercise_cat", "house" to "дом" to "exercise_house", "fox" to "лиса" to "exercise_fox")),
        exercise("sound-r-fish", "Найди картинку, в названии которой есть звук «Р»", "exercise_sound_r", "fish",
            listOf("fish" to "рыба" to "exercise_fish", "apple" to "яблоко" to "exercise_apple", "duck" to "утка" to "exercise_duck", "elephant" to "слон" to "exercise_elephant")),
        exercise("sound-l-lamp", "Найди картинку, в названии которой есть звук «Л»", "exercise_sound_l", "lamp",
            listOf("lamp" to "лампа" to "exercise_lamp", "cat" to "кот" to "exercise_cat", "house" to "дом" to "exercise_house", "fish" to "рыба" to "exercise_fish")),
        exercise("sound-s-dog", "Найди картинку, в названии которой есть звук «С»", "exercise_sound_s", "dog",
            listOf("dog" to "собака" to "exercise_dog", "cat" to "кот" to "exercise_cat", "house" to "дом" to "exercise_house", "fish" to "рыба" to "exercise_fish")),
        exercise("sound-sh-ball", "Найди картинку, в названии которой есть звук «Ш»", "exercise_sound_sh", "ball",
            listOf("ball" to "шар" to "exercise_ball", "cat" to "кот" to "exercise_cat", "house" to "дом" to "exercise_house", "fox" to "лиса" to "exercise_fox"))
    )

    private fun exercise(
        id: String, instruction: String, audio: String, correct: String,
        options: List<Pair<Pair<String, String>, String>>
    ) = ExerciseRecord(
        id, instruction, audio, correct, true, Instant.EPOCH,
        options.mapIndexed { index, option -> ExerciseOptionRecord(option.first.first, id, option.first.second, option.second, index + 1) }
    )
}
