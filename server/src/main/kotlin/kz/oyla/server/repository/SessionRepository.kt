package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.SessionStatus

data class SessionRecord(
    val id: UUID,
    val connectionCode: String,
    val childName: String,
    val status: SessionStatus,
    val specialistDeviceId: String,
    val specialistToken: String,
    val childDeviceId: String?,
    val childToken: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val connectedAt: Instant?,
    val completedAt: Instant?,
    /** Null for legacy code-based sessions; required by the managed SaaS flow. */
    val centerId: UUID? = null,
    val specialistId: UUID? = null,
    val specialistDeviceUuid: UUID? = null,
    val startedAt: Instant? = null,
    val isManaged: Boolean = false,
    val lessonTemplateId: UUID? = null,
    val templateNameSnapshot: String? = null
)

data class LessonParticipantRecord(
    val id: UUID,
    val sessionId: UUID,
    val childId: UUID,
    val deviceId: UUID,
    val createdAt: Instant
)

data class ManagedLessonRecord(val session: SessionRecord, val participant: LessonParticipantRecord, val exerciseCount: Int = 0)

data class LessonExerciseHistoryRecord(
    val position: Int,
    val exerciseId: String,
    val instructionText: String,
    val status: kz.oyla.server.model.ExerciseStatus,
    val attemptCount: Int,
    val incorrectAttempts: Int,
    val timeToCorrectMs: Long?,
    val activityType: kz.oyla.server.model.ActivityType = kz.oyla.server.model.ActivityType.SINGLE_CHOICE,
    val durationMs: Long? = null,
    val strokeCount: Int? = null,
    val childStrokeCount: Int? = null,
    val specialistStrokeCount: Int? = null
)

data class LessonDetailRecord(val lesson: ManagedLessonRecord, val exercises: List<LessonExerciseHistoryRecord>)

sealed interface ChildConnectionResult {
    data class Connected(val session: SessionRecord) : ChildConnectionResult
    data object NotFound : ChildConnectionResult
    data object Expired : ChildConnectionResult
    data object ConnectedToAnotherDevice : ChildConnectionResult
}

interface SessionRepository {
    suspend fun isConnectionCodeActive(code: String, now: Instant): Boolean
    /** Returns false when another active session claimed the generated code first. */
    suspend fun createSession(session: SessionRecord): Boolean
    /** Creates a managed session, its participant and all immutable snapshot rows atomically. */
    suspend fun createManagedSession(session: SessionRecord, participant: LessonParticipantRecord, exercises: List<SessionExerciseRecord>): Boolean
    suspend fun findById(id: UUID): SessionRecord?
    suspend fun connectChild(
        code: String,
        childDeviceId: String,
        childToken: String,
        now: Instant
    ): ChildConnectionResult

    suspend fun expireIfNecessary(id: UUID, now: Instant): SessionRecord?
    suspend fun cancel(id: UUID, now: Instant): SessionRecord?
    suspend fun complete(id: UUID, now: Instant): SessionRecord?
    suspend fun updateDeviceConnection(
        session: SessionRecord,
        role: DeviceRole,
        connected: Boolean,
        now: Instant
    )
    suspend fun isManagedDeviceBusy(deviceId: UUID): Boolean
    suspend fun currentChildAssignment(deviceId: UUID): ManagedLessonRecord?
    suspend fun currentSpecialistLesson(deviceId: UUID): ManagedLessonRecord?
    suspend fun listManagedLessons(centerId: UUID, childId: UUID? = null, specialistId: UUID? = null, status: SessionStatus? = null): List<ManagedLessonRecord>
    suspend fun getManagedLesson(centerId: UUID, sessionId: UUID): LessonDetailRecord?
}
