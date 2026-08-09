package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.SessionStatus

/** Deterministic repository used by HTTP tests; production always uses PostgreSQL. */
class InMemorySessionRepository : SessionRepository {
    private val sessions = linkedMapOf<UUID, SessionRecord>()
    private val participants = linkedMapOf<UUID, LessonParticipantRecord>()
    private val managedExerciseCounts = linkedMapOf<UUID, Int>()

    override suspend fun isConnectionCodeActive(code: String, now: Instant): Boolean = synchronized(sessions) {
        sessions.values.any {
            it.connectionCode == code &&
                it.status in setOf(SessionStatus.WAITING_FOR_CHILD, SessionStatus.READY) &&
                it.expiresAt > now
        }
    }

    override suspend fun createSession(session: SessionRecord): Boolean = synchronized(sessions) {
        if (sessions.values.any {
                it.connectionCode == session.connectionCode &&
                    it.status in setOf(SessionStatus.WAITING_FOR_CHILD, SessionStatus.READY) &&
                    it.expiresAt > session.createdAt
            }
        ) {
            false
        } else {
            sessions[session.id] = session
            true
        }
    }

    override suspend fun findById(id: UUID): SessionRecord? = synchronized(sessions) { sessions[id] }

    override suspend fun connectChild(
        code: String,
        childDeviceId: String,
        childToken: String,
        now: Instant
    ): ChildConnectionResult = synchronized(sessions) {
        val session = sessions.values.firstOrNull { it.connectionCode == code }
            ?: return@synchronized ChildConnectionResult.NotFound
        if (session.expiresAt <= now || session.status in setOf(
                SessionStatus.CANCELLED,
                SessionStatus.COMPLETED,
                SessionStatus.EXPIRED
            )
        ) {
            if (session.status == SessionStatus.WAITING_FOR_CHILD && session.expiresAt <= now) {
                sessions[session.id] = session.copy(status = SessionStatus.EXPIRED)
            }
            return@synchronized ChildConnectionResult.Expired
        }
        if (session.childDeviceId != null && session.childDeviceId != childDeviceId) {
            return@synchronized ChildConnectionResult.ConnectedToAnotherDevice
        }
        val result = if (session.childDeviceId == null) {
            session.copy(
                childDeviceId = childDeviceId,
                childToken = childToken,
                status = SessionStatus.READY,
                connectedAt = now
            ).also { sessions[it.id] = it }
        } else {
            session
        }
        ChildConnectionResult.Connected(result)
    }

    override suspend fun expireIfNecessary(id: UUID, now: Instant): SessionRecord? = synchronized(sessions) {
        val session = sessions[id] ?: return@synchronized null
        if (session.status == SessionStatus.WAITING_FOR_CHILD && session.expiresAt <= now) {
            session.copy(status = SessionStatus.EXPIRED).also { sessions[id] = it }
        } else {
            session
        }
    }

    override suspend fun cancel(id: UUID, now: Instant): SessionRecord? = synchronized(sessions) {
        val session = sessions[id] ?: return@synchronized null
        if (session.status in setOf(SessionStatus.WAITING_FOR_CHILD, SessionStatus.READY)) {
            session.copy(status = SessionStatus.CANCELLED, completedAt = now).also { sessions[id] = it }
        } else {
            session
        }
    }

    override suspend fun createManagedSession(session: SessionRecord, participant: LessonParticipantRecord, exercises: List<SessionExerciseRecord>): Boolean = synchronized(sessions) {
        if (!session.isManaged || exercises.size !in 1..30 || exercises.any { it.snapshot == null } || sessions.values.any {
                it.connectionCode == session.connectionCode && it.status in setOf(SessionStatus.WAITING_FOR_CHILD, SessionStatus.READY) && it.expiresAt > session.createdAt
            }) return@synchronized false
        sessions[session.id] = session
        participants[participant.id] = participant
        managedExerciseCounts[session.id] = exercises.size
        true
    }

    override suspend fun complete(id: UUID, now: Instant): SessionRecord? = synchronized(sessions) {
        val session = sessions[id] ?: return@synchronized null
        if (session.status in setOf(SessionStatus.WAITING_FOR_CHILD, SessionStatus.READY)) {
            session.copy(status = SessionStatus.COMPLETED, completedAt = now).also { sessions[id] = it }
        } else session
    }

    override suspend fun updateDeviceConnection(
        session: SessionRecord,
        role: DeviceRole,
        connected: Boolean,
        now: Instant
    ) = Unit

    override suspend fun isManagedDeviceBusy(deviceId: UUID): Boolean = synchronized(sessions) {
        sessions.values.any { session ->
            session.isManaged && session.status in setOf(SessionStatus.WAITING_FOR_CHILD, SessionStatus.READY) &&
                (session.specialistDeviceUuid == deviceId || participants.values.any { it.sessionId == session.id && it.deviceId == deviceId })
        }
    }

    override suspend fun currentChildAssignment(deviceId: UUID): ManagedLessonRecord? = synchronized(sessions) {
        managedLessons().filter { it.participant.deviceId == deviceId && it.session.status in setOf(SessionStatus.WAITING_FOR_CHILD, SessionStatus.READY) }
            .maxByOrNull { it.session.startedAt ?: it.session.createdAt }
    }

    override suspend fun currentSpecialistLesson(deviceId: UUID): ManagedLessonRecord? = synchronized(sessions) {
        managedLessons().filter { it.session.specialistDeviceUuid == deviceId && it.session.status in setOf(SessionStatus.WAITING_FOR_CHILD, SessionStatus.READY) }
            .maxByOrNull { it.session.startedAt ?: it.session.createdAt }
    }

    override suspend fun listManagedLessons(centerId: UUID, childId: UUID?, specialistId: UUID?, status: SessionStatus?): List<ManagedLessonRecord> = synchronized(sessions) {
        managedLessons().filter { it.session.centerId == centerId && (childId == null || it.participant.childId == childId) && (specialistId == null || it.session.specialistId == specialistId) && (status == null || it.session.status == status) }
            .sortedByDescending { it.session.startedAt ?: it.session.createdAt }
    }

    override suspend fun getManagedLesson(centerId: UUID, sessionId: UUID): LessonDetailRecord? = synchronized(sessions) {
        managedLessons().firstOrNull { it.session.centerId == centerId && it.session.id == sessionId }?.let { LessonDetailRecord(it, emptyList()) }
    }

    private fun managedLessons(): List<ManagedLessonRecord> = sessions.values.filter { it.isManaged }.mapNotNull { session ->
        participants.values.singleOrNull { it.sessionId == session.id }?.let { ManagedLessonRecord(session, it, managedExerciseCounts[session.id] ?: 0) }
    }
}
