package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.ExerciseStatus

data class ExerciseRecord(
    val id: String,
    val instructionText: String,
    val audioAssetKey: String?,
    val correctOptionId: String,
    val isActive: Boolean,
    val createdAt: Instant,
    val options: List<ExerciseOptionRecord>
)

data class ExerciseOptionRecord(
    val id: String,
    val exerciseId: String,
    val label: String,
    val imageAssetKey: String,
    val position: Int
)

data class SessionExerciseRecord(
    val id: UUID,
    val sessionId: UUID,
    val exerciseId: String,
    val status: ExerciseStatus,
    val shownAt: Instant?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val position: Int,
    val isCurrent: Boolean
)

data class ExerciseAttemptRecord(
    val id: UUID,
    val sessionExerciseId: UUID,
    val selectedOptionId: String,
    val attemptNumber: Int,
    val isCorrect: Boolean,
    val responseTimeMs: Long,
    val clientEventId: UUID,
    val createdAt: Instant
)

interface ExerciseRepository {
    suspend fun findExercise(id: String): ExerciseRecord?
    suspend fun findSessionExercises(sessionId: UUID): List<SessionExerciseRecord>
    suspend fun findCurrentSessionExercise(sessionId: UUID): SessionExerciseRecord?
    suspend fun findSessionExerciseById(id: UUID): SessionExerciseRecord?
    suspend fun findSessionExerciseByPosition(sessionId: UUID, position: Int): SessionExerciseRecord?
    suspend fun createSessionExercises(records: List<SessionExerciseRecord>): Int
    suspend fun updateSessionExercise(record: SessionExerciseRecord): Boolean
    suspend fun setCurrentExercise(sessionId: UUID, sessionExerciseId: UUID): Boolean
    suspend fun countSessionExercises(sessionId: UUID): Int
    suspend fun countCompletedSessionExercises(sessionId: UUID): Int
    suspend fun findAttemptByClientEventId(clientEventId: UUID): ExerciseAttemptRecord?
    suspend fun findLatestAttempt(sessionExerciseId: UUID): ExerciseAttemptRecord?
    suspend fun findAttempts(sessionExerciseId: UUID): List<ExerciseAttemptRecord>
    suspend fun countAttempts(sessionExerciseId: UUID): Int
    suspend fun createAttempt(record: ExerciseAttemptRecord): Boolean
}
