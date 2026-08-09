package kz.oyla.server.service

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.AuditActorType
import kz.oyla.server.model.AuditLogRecord
import kz.oyla.server.model.CenterStatus
import kz.oyla.server.model.ChildStatus
import kz.oyla.server.model.DeviceRecord
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.DeviceStatus
import kz.oyla.server.model.SessionStatus
import kz.oyla.server.model.SpecialistStatus
import kz.oyla.server.model.dto.ChildLessonAssignmentResponse
import kz.oyla.server.model.dto.CreateDeviceLessonRequest
import kz.oyla.server.model.dto.DeviceLessonResponse
import kz.oyla.server.model.dto.DeviceDto
import kz.oyla.server.model.dto.LessonDetailsDto
import kz.oyla.server.model.dto.LessonDto
import kz.oyla.server.model.dto.LessonExerciseDto
import kz.oyla.server.repository.ChildListFilter
import kz.oyla.server.repository.LessonParticipantRecord
import kz.oyla.server.repository.ManagedLessonRecord
import kz.oyla.server.repository.SaasRepository
import kz.oyla.server.repository.SessionRecord
import kz.oyla.server.repository.SessionRepository
import kz.oyla.server.repository.SpecialistListFilter
import kz.oyla.server.util.CodeGenerator

/** Managed SaaS lesson identity and history. Exercise execution remains in ExerciseService. */
class LessonService(
    private val tenants: SaasRepository,
    private val sessions: SessionRepository,
    private val exercises: ExerciseService,
    private val config: SaasConfig,
    private val codes: CodeGenerator = CodeGenerator(),
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun create(device: DeviceRecord, request: CreateDeviceLessonRequest): DeviceLessonResponse {
        val specialistDevice = requireActiveDevice(device, DeviceRole.SPECIALIST)
        val center = tenants.findCenter(specialistDevice.centerId)?.takeIf { it.status == CenterStatus.ACTIVE } ?: throw ApiException.forbidden()
        val specialistId = request.specialistId.uuid("Специалист")
        val childId = request.childId.uuid("Ребёнок")
        val childDeviceId = request.childDeviceId.uuid("Детский планшет")
        val specialist = tenants.findSpecialist(center.id, specialistId)?.takeIf { it.status == SpecialistStatus.ACTIVE } ?: throw ApiException.specialistNotFound()
        val child = tenants.findChild(center.id, childId)?.takeIf { it.status == ChildStatus.ACTIVE } ?: throw ApiException.childNotFound()
        val childDevice = tenants.findDevice(center.id, childDeviceId)
            ?.takeIf { it.role == DeviceRole.CHILD && it.status == DeviceStatus.ACTIVE && it.lastSeenAt?.isAfter(clock.instant().minus(config.onlineWindow)) == true }
            ?: throw ApiException.conflict("Детский планшет недоступен")
        if (sessions.isManagedDeviceBusy(specialistDevice.id) || sessions.isManagedDeviceBusy(childDevice.id)) {
            throw ApiException.conflict("Один из планшетов уже участвует в активном занятии")
        }
        val now = clock.instant()
        repeat(50) {
            val session = SessionRecord(
                id = UUID.randomUUID(), connectionCode = codes.nextConnectionCode(), childName = child.fullName(), status = SessionStatus.READY,
                specialistDeviceId = specialistDevice.id.toString(), specialistToken = codes.nextAccessToken(), childDeviceId = childDevice.id.toString(),
                childToken = codes.nextAccessToken(), createdAt = now, expiresAt = now.plus(Duration.ofDays(365)), connectedAt = now, completedAt = null,
                centerId = center.id, specialistId = specialist.id, specialistDeviceUuid = specialistDevice.id, startedAt = now, isManaged = true
            )
            val participant = LessonParticipantRecord(UUID.randomUUID(), session.id, child.id, childDevice.id, now)
            if (sessions.createManagedSession(session, participant, ExerciseService.defaultPlan)) {
                exercises.ensureDefaultExercisePlan(session.id)
                tenants.recordAudit(AuditLogRecord(UUID.randomUUID(), center.id, AuditActorType.DEVICE, specialistDevice.id, "LESSON_CREATED", "LESSON", session.id, "{}", null, now))
                tenants.recordAudit(AuditLogRecord(UUID.randomUUID(), center.id, AuditActorType.DEVICE, specialistDevice.id, "LESSON_STARTED", "LESSON", session.id, "{}", null, now))
                return session.toDeviceResponse(specialist.fullName(), child.id, child.fullName())
            }
        }
        throw ApiException.conflict("Не удалось создать занятие")
    }

    suspend fun childAssignment(device: DeviceRecord): ChildLessonAssignmentResponse? {
        val active = requireActiveDevice(device, DeviceRole.CHILD)
        val lesson = sessions.currentChildAssignment(active.id) ?: return null
        if (lesson.session.centerId != active.centerId || lesson.participant.deviceId != active.id) return null
        val child = tenants.findChild(active.centerId, lesson.participant.childId) ?: return null
        return ChildLessonAssignmentResponse(lesson.session.id.toString(), child.id.toString(), child.fullName(), checkNotNull(lesson.session.childToken), lesson.session.status, checkNotNull(lesson.session.startedAt).toString())
    }

    suspend fun currentSpecialistLesson(device: DeviceRecord): DeviceLessonResponse? {
        val active = requireActiveDevice(device, DeviceRole.SPECIALIST)
        val lesson = sessions.currentSpecialistLesson(active.id) ?: return null
        if (lesson.session.centerId != active.centerId) return null
        val specialist = tenants.findSpecialist(active.centerId, checkNotNull(lesson.session.specialistId)) ?: return null
        val child = tenants.findChild(active.centerId, lesson.participant.childId) ?: return null
        return lesson.session.toDeviceResponse(specialist.fullName(), child.id, child.fullName())
    }

    suspend fun availableChildDevices(device: DeviceRecord): List<DeviceDto> {
        val active = requireActiveDevice(device, DeviceRole.SPECIALIST)
        val now = clock.instant()
        return tenants.listDevices(active.centerId, kz.oyla.server.repository.DeviceListFilter(DeviceRole.CHILD, DeviceStatus.ACTIVE, now.minus(config.onlineWindow), true))
            .filterNot { sessions.isManagedDeviceBusy(it.id) }
            .map { it.toDto(now) }
    }

    suspend fun listForWeb(context: CenterContext, childId: UUID? = null, specialistId: UUID? = null, status: SessionStatus? = null): List<LessonDto> =
        sessions.listManagedLessons(context.center.id, childId, specialistId, status).map { lessonDto(context.center.id, it) }

    suspend fun detailsForWeb(context: CenterContext, sessionId: UUID): LessonDetailsDto {
        val record = sessions.getManagedLesson(context.center.id, sessionId) ?: throw ApiException.notFound("Занятие не найдено")
        val lesson = lessonDto(context.center.id, record.lesson)
        val specialistDevice = tenants.findDevice(context.center.id, checkNotNull(record.lesson.session.specialistDeviceUuid)) ?: throw ApiException.notFound("Планшет не найден")
        val childDevice = tenants.findDevice(context.center.id, record.lesson.participant.deviceId) ?: throw ApiException.notFound("Планшет не найден")
        return LessonDetailsDto(lesson, specialistDevice.id.toString(), specialistDevice.name, childDevice.id.toString(), childDevice.name,
            record.exercises.map { LessonExerciseDto(it.position, it.exerciseId, it.instructionText, it.status, it.attemptCount, it.incorrectAttempts, it.timeToCorrectMs) })
    }

    suspend fun ensureChildInCenter(context: CenterContext, childId: UUID) {
        tenants.findChild(context.center.id, childId) ?: throw ApiException.childNotFound()
    }
    suspend fun ensureSpecialistInCenter(context: CenterContext, specialistId: UUID) {
        tenants.findSpecialist(context.center.id, specialistId) ?: throw ApiException.specialistNotFound()
    }

    suspend fun recordCompletion(session: SessionRecord) {
        if (!session.isManaged || session.centerId == null) return
        tenants.recordAudit(AuditLogRecord(UUID.randomUUID(), session.centerId, AuditActorType.DEVICE, session.specialistDeviceUuid, "LESSON_COMPLETED", "LESSON", session.id, "{}", null, clock.instant()))
    }
    suspend fun recordCancellation(session: SessionRecord) {
        if (!session.isManaged || session.centerId == null) return
        tenants.recordAudit(AuditLogRecord(UUID.randomUUID(), session.centerId, AuditActorType.DEVICE, session.specialistDeviceUuid, "LESSON_CANCELLED", "LESSON", session.id, "{}", null, clock.instant()))
    }

    private suspend fun requireActiveDevice(principal: DeviceRecord, requiredRole: DeviceRole): DeviceRecord {
        val device = tenants.findDevice(principal.centerId, principal.id) ?: throw ApiException.deviceUnlinked()
        if (device.status == DeviceStatus.BLOCKED) throw ApiException.deviceBlocked()
        if (device.status != DeviceStatus.ACTIVE || device.tokenRevokedAt != null) throw ApiException.deviceUnlinked()
        if (device.role != requiredRole) throw ApiException.forbidden()
        if (tenants.findCenter(device.centerId)?.status != CenterStatus.ACTIVE) throw ApiException.forbidden()
        return device
    }
    private suspend fun lessonDto(centerId: UUID, record: ManagedLessonRecord): LessonDto {
        val specialist = tenants.findSpecialist(centerId, checkNotNull(record.session.specialistId)) ?: throw ApiException.notFound("Специалист не найден")
        val child = tenants.findChild(centerId, record.participant.childId) ?: throw ApiException.notFound("Ребёнок не найден")
        val started = checkNotNull(record.session.startedAt)
        val duration = record.session.completedAt?.let { Duration.between(started, it).toMillis().coerceAtLeast(0) }
        return LessonDto(record.session.id.toString(), child.id.toString(), child.fullName(), specialist.id.toString(), specialist.fullName(), record.session.status, started.toString(), record.session.completedAt?.toString(), duration)
    }
    private fun SessionRecord.toDeviceResponse(specialistName: String, childId: UUID, childName: String) = DeviceLessonResponse(
        sessionId = id.toString(),
        specialistId = checkNotNull(specialistId).toString(),
        specialistName = specialistName,
        childId = childId.toString(),
        childName = childName,
        childDeviceId = checkNotNull(childDeviceId),
        status = status,
        sessionToken = checkNotNull(specialistToken),
        startedAt = checkNotNull(startedAt).toString()
    )
    private fun DeviceRecord.toDto(now: Instant) = DeviceDto(id.toString(), name, role, status, appVersion, androidVersion, model, lastSeenAt?.toString(), activatedAt?.toString(), lastSeenAt?.isAfter(now.minus(config.onlineWindow)) == true)
    private fun kz.oyla.server.model.ChildRecord.fullName() = listOf(firstName, lastName).filterNotNull().joinToString(" ")
    private fun kz.oyla.server.model.SpecialistRecord.fullName() = listOf(firstName, lastName).filterNotNull().joinToString(" ")
    private fun String.uuid(label: String): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw ApiException.validation("Некорректный идентификатор: $label") }
}
