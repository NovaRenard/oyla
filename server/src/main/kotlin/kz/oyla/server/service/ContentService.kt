package kz.oyla.server.service

import java.io.BufferedInputStream
import java.io.InputStream
import java.time.Clock
import java.util.UUID
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.AuditActorType
import kz.oyla.server.model.AuditLogRecord
import kz.oyla.server.model.ContentExerciseOptionRecord
import kz.oyla.server.model.ContentExerciseRecord
import kz.oyla.server.model.ContentOwnership
import kz.oyla.server.model.ContentStatus
import kz.oyla.server.model.ExerciseSnapshot
import kz.oyla.server.model.ExerciseSnapshotOption
import kz.oyla.server.model.LessonTemplateItemRecord
import kz.oyla.server.model.LessonTemplateRecord
import kz.oyla.server.model.LessonTemplateSnapshot
import kz.oyla.server.model.MediaAssetRecord
import kz.oyla.server.model.MediaType
import kz.oyla.server.model.MembershipRole
import kz.oyla.server.model.WhiteboardExerciseConfig
import kz.oyla.server.model.dto.ContentExerciseDto
import kz.oyla.server.model.dto.CreateExerciseRequest
import kz.oyla.server.model.dto.CreateLessonTemplateRequest
import kz.oyla.server.model.dto.DeviceLessonTemplateDto
import kz.oyla.server.model.dto.ExerciseOptionContentDto
import kz.oyla.server.model.dto.ExerciseOptionInput
import kz.oyla.server.model.dto.LessonTemplateDto
import kz.oyla.server.model.dto.LessonTemplateItemDto
import kz.oyla.server.model.dto.LessonTemplateItemInput
import kz.oyla.server.model.dto.MediaAssetDto
import kz.oyla.server.model.dto.UpdateExerciseRequest
import kz.oyla.server.model.dto.UpdateLessonTemplateRequest
import kz.oyla.server.model.dto.WhiteboardExerciseConfigDto
import kz.oyla.server.repository.ContentExerciseFilter
import kz.oyla.server.repository.ContentRepository
import kz.oyla.server.repository.LessonTemplateFilter
import kz.oyla.server.repository.SaasRepository
import kz.oyla.server.storage.MediaStorage
import kz.oyla.server.storage.MediaTooLargeException

class ContentService(
    private val repository: ContentRepository,
    private val tenants: SaasRepository,
    private val storage: MediaStorage,
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun listExercises(context: CenterContext, filter: ContentExerciseFilter): List<ContentExerciseDto> = buildList {
        for (exercise in repository.listExercises(context.center.id, filter)) add(exercise.toDto(repository.templateUsageCount(exercise.id)))
    }

    suspend fun exercise(context: CenterContext, id: UUID): ContentExerciseDto =
        (repository.findExerciseAccessible(context.center.id, id) ?: throw ApiException.notFound("Упражнение не найдено"))
            .toDto(repository.templateUsageCount(id))

    suspend fun createExercise(context: CenterContext, request: CreateExerciseRequest, ipAddress: String?): ContentExerciseDto {
        requireEditor(context)
        val now = clock.instant()
        val record = newExercise(context.center.id, request, now)
        repository.insertExercise(record)
        audit(context, "EXERCISE_CREATED", "EXERCISE", record.id, ipAddress)
        return record.toDto(0)
    }

    suspend fun updateExercise(context: CenterContext, id: UUID, request: UpdateExerciseRequest, ipAddress: String?): ContentExerciseDto {
        requireEditor(context)
        val current = repository.findExerciseAccessible(context.center.id, id) ?: throw ApiException.notFound("Упражнение не найдено")
        if (current.ownership != ContentOwnership.CENTER) throw ApiException.forbidden()
        val title = request.title?.required("Название", 160) ?: current.title
        val instruction = request.instructionText?.required("Инструкция", 1000) ?: current.instructionText
        val audio = request.instructionAudioAssetId?.uuidOrNull("Аудио") ?: current.instructionAudioAssetId
        validateMedia(context.center.id, audio, MediaType.AUDIO)
        val options = if (current.activityType == ActivityType.SINGLE_CHOICE) request.options?.let { options(context.center.id, id, it) } ?: current.options else emptyList()
        val whiteboard = if (current.activityType == ActivityType.WHITEBOARD) request.whiteboardConfig?.let { whiteboardConfig(context.center.id, it) } ?: current.whiteboardConfig else null
        validateDefinition(current.activityType, options, whiteboard)
        val updated = current.copy(title = title, instructionText = instruction, instructionAudioAssetId = audio, options = options, whiteboardConfig = whiteboard, updatedAt = clock.instant())
        if (!repository.updateExercise(updated)) throw ApiException.notFound("Упражнение не найдено")
        audit(context, "EXERCISE_UPDATED", "EXERCISE", id, ipAddress)
        return updated.toDto(repository.templateUsageCount(id))
    }

    suspend fun archiveExercise(context: CenterContext, id: UUID, restore: Boolean, ipAddress: String?): ContentExerciseDto {
        requireEditor(context)
        val current = repository.findExerciseAccessible(context.center.id, id) ?: throw ApiException.notFound("Упражнение не найдено")
        if (current.ownership != ContentOwnership.CENTER) throw ApiException.forbidden()
        if (!restore && repository.activeTemplateUsageCount(id) > 0) {
            throw ApiException.conflict("Упражнение используется в активных шаблонах. Сначала измените или архивируйте их.")
        }
        val updated = current.copy(status = if (restore) ContentStatus.ACTIVE else ContentStatus.ARCHIVED, updatedAt = clock.instant())
        repository.updateExercise(updated)
        audit(context, if (restore) "EXERCISE_RESTORED" else "EXERCISE_ARCHIVED", "EXERCISE", id, ipAddress)
        return updated.toDto(repository.templateUsageCount(id))
    }

    suspend fun duplicateExercise(context: CenterContext, id: UUID, ipAddress: String?): ContentExerciseDto {
        requireEditor(context)
        val source = repository.findExerciseAccessible(context.center.id, id) ?: throw ApiException.notFound("Упражнение не найдено")
        val now = clock.instant(); val copiedId = UUID.randomUUID()
        val copied = source.copy(id = copiedId, centerId = context.center.id, ownership = ContentOwnership.CENTER, title = "${source.title} — копия", createdAt = now, updatedAt = now,
            options = source.options.mapIndexed { index, option -> option.copy(id = UUID.randomUUID(), exerciseId = copiedId, sortOrder = index + 1) }, legacyKey = null)
        repository.insertExercise(copied); audit(context, "EXERCISE_DUPLICATED", "EXERCISE", copiedId, ipAddress)
        return copied.toDto(0)
    }

    suspend fun listTemplates(context: CenterContext, filter: LessonTemplateFilter): List<LessonTemplateDto> = buildList {
        for (template in repository.listTemplates(context.center.id, filter)) add(template.toDto(context.center.id))
    }
    suspend fun template(context: CenterContext, id: UUID): LessonTemplateDto =
        (repository.findTemplateAccessible(context.center.id, id) ?: throw ApiException.notFound("Шаблон занятия не найден")).toDto(context.center.id)

    suspend fun createTemplate(context: CenterContext, request: CreateLessonTemplateRequest, ipAddress: String?): LessonTemplateDto {
        requireEditor(context)
        val now = clock.instant(); val id = UUID.randomUUID(); val items = templateItems(context.center.id, id, request.items)
        val record = LessonTemplateRecord(id, context.center.id, ContentOwnership.CENTER, request.name.required("Название", 160), request.description?.optional(1000), ContentStatus.ACTIVE, now, now, items)
        repository.insertTemplate(record); audit(context, "LESSON_TEMPLATE_CREATED", "LESSON_TEMPLATE", id, ipAddress)
        return record.toDto(context.center.id)
    }

    suspend fun updateTemplate(context: CenterContext, id: UUID, request: UpdateLessonTemplateRequest, ipAddress: String?): LessonTemplateDto {
        requireEditor(context)
        val current = repository.findTemplateAccessible(context.center.id, id) ?: throw ApiException.notFound("Шаблон занятия не найден")
        if (current.ownership != ContentOwnership.CENTER) throw ApiException.forbidden()
        val itemIds = request.items?.let { validateTemplateExerciseIds(context.center.id, it) }
        val updated = current.copy(name = request.name?.required("Название",160) ?: current.name, description = request.description?.optional(1000) ?: current.description, updatedAt = clock.instant())
        if (!repository.updateTemplate(updated)) throw ApiException.notFound("Шаблон занятия не найден")
        if (itemIds != null && !repository.replaceTemplateItems(id, itemIds)) throw ApiException.notFound("Шаблон занятия не найден")
        audit(context, "LESSON_TEMPLATE_UPDATED", "LESSON_TEMPLATE", id, ipAddress)
        return (repository.findTemplateAccessible(context.center.id,id) ?: updated).toDto(context.center.id)
    }

    suspend fun replaceTemplateItems(context: CenterContext, id: UUID, items: List<LessonTemplateItemInput>, ipAddress: String?): LessonTemplateDto {
        requireEditor(context)
        val template = repository.findTemplateAccessible(context.center.id,id) ?: throw ApiException.notFound("Шаблон занятия не найден")
        if(template.ownership != ContentOwnership.CENTER) throw ApiException.forbidden()
        if(!repository.replaceTemplateItems(id,validateTemplateExerciseIds(context.center.id,items))) throw ApiException.notFound("Шаблон занятия не найден")
        audit(context,"LESSON_TEMPLATE_UPDATED","LESSON_TEMPLATE",id,ipAddress)
        return checkNotNull(repository.findTemplateAccessible(context.center.id,id)).toDto(context.center.id)
    }

    suspend fun archiveTemplate(context: CenterContext, id: UUID, restore: Boolean, ipAddress: String?): LessonTemplateDto {
        requireEditor(context); val current=repository.findTemplateAccessible(context.center.id,id) ?: throw ApiException.notFound("Шаблон занятия не найден")
        if(current.ownership != ContentOwnership.CENTER) throw ApiException.forbidden()
        val updated=current.copy(status=if(restore) ContentStatus.ACTIVE else ContentStatus.ARCHIVED,updatedAt=clock.instant())
        repository.updateTemplate(updated);audit(context,if(restore) "LESSON_TEMPLATE_RESTORED" else "LESSON_TEMPLATE_ARCHIVED","LESSON_TEMPLATE",id,ipAddress)
        return updated.toDto(context.center.id)
    }

    suspend fun duplicateTemplate(context: CenterContext,id:UUID,ipAddress:String?):LessonTemplateDto {
        requireEditor(context);val source=repository.findTemplateAccessible(context.center.id,id) ?: throw ApiException.notFound("Шаблон занятия не найден")
        val target=UUID.randomUUID();val now=clock.instant();val items=templateItems(context.center.id,target,source.items.map{LessonTemplateItemInput(it.exerciseId.toString())})
        val copied=source.copy(id=target,centerId=context.center.id,ownership=ContentOwnership.CENTER,name="${source.name} — копия",createdAt=now,updatedAt=now,items=items)
        repository.insertTemplate(copied);audit(context,"LESSON_TEMPLATE_DUPLICATED","LESSON_TEMPLATE",target,ipAddress);return copied.toDto(context.center.id)
    }

    suspend fun deviceTemplates(centerId: UUID): List<DeviceLessonTemplateDto> = repository.listTemplates(centerId, LessonTemplateFilter(status = ContentStatus.ACTIVE)).map {
        DeviceLessonTemplateDto(it.id.toString(),it.name,it.description,it.ownership,it.items.size)
    }

    /** Called before managed-session creation; all mutable definitions are copied here. */
    suspend fun snapshotTemplate(centerId: UUID, id: UUID): LessonTemplateSnapshot {
        val template=repository.findTemplateAccessible(centerId,id)?.takeIf { it.status==ContentStatus.ACTIVE } ?: throw ApiException.notFound("Шаблон занятия недоступен")
        if(template.items.isEmpty()) throw ApiException.validation("Шаблон должен содержать хотя бы одно упражнение")
        val exercises=buildList { for (item in template.items.sortedBy{it.position}) {
            val exercise=repository.findExerciseAccessible(centerId,item.exerciseId)?.takeIf { it.status==ContentStatus.ACTIVE } ?: throw ApiException.conflict("Шаблон содержит недоступное упражнение")
            add(exercise.toSnapshot())
        } }
        return LessonTemplateSnapshot(template.id,template.name,template.description,exercises)
    }

    suspend fun upload(context: CenterContext, type: MediaType, originalFilename: String, mimeType: String?, input: InputStream, ipAddress: String?): MediaAssetDto {
        requireEditor(context);val accepted=AllowedMedia[type]?.get(mimeType?.substringBefore(';')?.lowercase()) ?: throw ApiException.validation("Этот тип файла не поддерживается")
        val safeName=originalFilename.substringAfterLast('/').substringAfterLast('\\').take(255).ifBlank { "upload${accepted.extension}" }
        val buffered=BufferedInputStream(input);buffered.mark(32);val header=ByteArray(32);val read=buffered.read(header);buffered.reset()
        if(!matchesSignature(type,accepted.mime,header,read)) throw ApiException.validation("Содержимое файла не соответствует заявленному типу")
        val stored=try { storage.store(buffered,accepted.extension,accepted.maxBytes) } catch(_:MediaTooLargeException) { throw ApiException.validation("Файл превышает допустимый размер") }
        val asset=MediaAssetRecord(UUID.randomUUID(),context.center.id,ContentOwnership.CENTER,type,stored.storageKey,safeName,accepted.mime,stored.sizeBytes,clock.instant())
        try { repository.insertMedia(asset) } catch(exception:Exception) { storage.delete(stored.storageKey);throw exception }
        audit(context,"MEDIA_UPLOADED","MEDIA",asset.id,ipAddress);return asset.toDto()
    }

    suspend fun openMedia(centerId: UUID, id: UUID): Pair<MediaAssetRecord,InputStream> {
        val asset=repository.findMediaAccessible(centerId,id) ?: throw ApiException.notFound("Файл не найден")
        return asset to (storage.open(asset.storageKey) ?: throw ApiException.notFound("Файл не найден"))
    }

    private suspend fun newExercise(centerId:UUID,request:CreateExerciseRequest,now:java.time.Instant):ContentExerciseRecord {
        val id=UUID.randomUUID();val audio=request.instructionAudioAssetId?.uuidOrNull("Аудио");validateMedia(centerId,audio,MediaType.AUDIO)
        val options=if (request.activityType == ActivityType.SINGLE_CHOICE) options(centerId,id,request.options) else emptyList()
        val whiteboard=if (request.activityType == ActivityType.WHITEBOARD) request.whiteboardConfig?.let { whiteboardConfig(centerId,it) } else null
        validateDefinition(request.activityType, options, whiteboard)
        return ContentExerciseRecord(id,centerId,ContentOwnership.CENTER,request.activityType,request.title.required("Название",160),request.instructionText.required("Инструкция",1000),audio,null,ContentStatus.ACTIVE,now,now,options,whiteboard)
    }
    private suspend fun options(centerId:UUID,exerciseId:UUID,values:List<ExerciseOptionInput>):List<ContentExerciseOptionRecord> {
        if(values.mapNotNull{it.id}.let{it.size != it.toSet().size}) throw ApiException.validation("Варианты не должны повторяться")
        return buildList { values.forEachIndexed { index,value ->
            val image=value.imageAssetId?.uuidOrNull("Изображение");validateMedia(centerId,image,MediaType.IMAGE)
            add(ContentExerciseOptionRecord(value.id?.uuidOrNull("Вариант") ?: UUID.randomUUID(),exerciseId,value.label?.optional(160),image,null,index+1,value.isCorrect))
        } }
    }
    private fun validateDefinition(type: ActivityType, options:List<ContentExerciseOptionRecord>, whiteboard: WhiteboardExerciseConfig?) {
        when(type) {
            ActivityType.SINGLE_CHOICE -> {
                if(options.size !in 2..6) throw ApiException.validation("SINGLE_CHOICE требует от 2 до 6 вариантов")
                if(options.count{it.isCorrect} != 1) throw ApiException.validation("Нужен ровно один правильный вариант")
                if(options.any{it.label.isNullOrBlank() && it.imageAssetId==null && it.localImageAssetKey==null}) throw ApiException.validation("У варианта нужны название или изображение")
            }
            ActivityType.WHITEBOARD -> {
                val config = whiteboard ?: throw ApiException.validation("Для WHITEBOARD нужны настройки доски")
                if (config.availableColors.size !in 4..6 || config.availableColors.distinct().size != config.availableColors.size) throw ApiException.validation("Палитра доски должна содержать от 4 до 6 разных цветов")
                if (config.defaultColor !in config.availableColors) throw ApiException.validation("Основной цвет должен быть в палитре")
                if (options.isNotEmpty()) throw ApiException.validation("WHITEBOARD не использует варианты ответа")
            }
        }
    }
    private suspend fun whiteboardConfig(centerId: UUID, input: WhiteboardExerciseConfigDto): WhiteboardExerciseConfig {
        val background = input.backgroundAssetId?.uuidOrNull("Фон")
        validateMedia(centerId, background, MediaType.IMAGE)
        return WhiteboardExerciseConfig(background?.toString(), null, input.childDrawingInitiallyEnabled, input.availableColors, input.defaultColor, input.defaultBrushSize, input.allowEraser, input.allowClear)
    }
    private suspend fun templateItems(centerId:UUID,templateId:UUID,values:List<LessonTemplateItemInput>):List<LessonTemplateItemRecord> =
        validateTemplateExerciseIds(centerId,values).mapIndexed { index,id -> LessonTemplateItemRecord(UUID.randomUUID(),templateId,id,index+1) }
    private suspend fun validateTemplateExerciseIds(centerId:UUID,values:List<LessonTemplateItemInput>):List<UUID> {
        if(values.size !in 1..30) throw ApiException.validation("Шаблон должен содержать от 1 до 30 упражнений")
        return buildList { for (input in values) {
            val id=input.exerciseId.uuidOrNull("Упражнение")
            val exercise=repository.findExerciseAccessible(centerId,id) ?: throw ApiException.validation("Упражнение недоступно этому центру")
            if(exercise.status != ContentStatus.ACTIVE) throw ApiException.validation("Архивное упражнение нельзя добавить в шаблон")
            add(id)
        } }
    }
    private suspend fun validateMedia(centerId:UUID,id:UUID?,type:MediaType) { if(id != null && repository.findMediaAccessible(centerId,id,type)==null) throw ApiException.validation("Медиафайл недоступен") }
    private fun requireEditor(context:CenterContext) { if(context.membership.role !in setOf(MembershipRole.OWNER,MembershipRole.ADMIN,MembershipRole.METHODIST)) throw ApiException.forbidden() }
    private suspend fun audit(context:CenterContext,action:String,entity:String,id:UUID,ip:String?) { tenants.recordAudit(AuditLogRecord(UUID.randomUUID(),context.center.id,AuditActorType.USER,context.user.id,action,entity,id,"{}",ip,clock.instant())) }

    private fun ContentExerciseRecord.toDto(usage:Int)=ContentExerciseDto(id.toString(),ownership,activityType,title,instructionText,instructionAudioAssetId?.toString(),instructionAudioAssetId?.url(),localAudioAssetKey,status,
        options.sortedBy{it.sortOrder}.map { option -> ExerciseOptionContentDto(option.id.toString(),option.label,option.imageAssetId?.toString(),option.imageAssetId?.url(),option.localImageAssetKey,option.sortOrder,option.isCorrect) },whiteboardConfig?.toDto(),usage,createdAt.toString(),updatedAt.toString())
    private suspend fun LessonTemplateRecord.toDto(centerId:UUID):LessonTemplateDto {
        val itemDtos = buildList { for (item in items.sortedBy { it.position }) {
            val exercise=repository.findExerciseAccessible(centerId,item.exerciseId)
            add(LessonTemplateItemDto(item.id.toString(),item.exerciseId.toString(),item.position,exercise?.title,exercise?.activityType))
        } }
        return LessonTemplateDto(id.toString(),ownership,name,description,status,items.size,itemDtos,createdAt.toString(),updatedAt.toString())
    }
    private fun MediaAssetRecord.toDto()=MediaAssetDto(id.toString(),ownership,type,originalFilename,mimeType,sizeBytes,id.url(),createdAt.toString())
    private fun ContentExerciseRecord.toSnapshot()=ExerciseSnapshot(id.toString(),activityType,title,instructionText,instructionAudioAssetId?.toString(),instructionAudioAssetId?.url(),localAudioAssetKey,
        options.sortedBy{it.sortOrder}.map { ExerciseSnapshotOption(it.id.toString(),it.label,it.imageAssetId?.toString(),it.imageAssetId?.url(),it.localImageAssetKey,it.sortOrder) },options.singleOrNull{it.isCorrect}?.id?.toString(),whiteboardConfig?.copy(backgroundUrl = whiteboardConfig.backgroundAssetId?.let { "/api/v1/media/$it" }))
    private fun WhiteboardExerciseConfig.toDto() = WhiteboardExerciseConfigDto(backgroundAssetId, backgroundAssetId?.let { "/api/v1/media/$it" }, childDrawingInitiallyEnabled, availableColors, defaultColor, defaultBrushSize, allowEraser, allowClear)
    private fun UUID.url()="/api/v1/media/$this"
    private fun String.required(label:String,max:Int):String=trim().takeIf{it.isNotEmpty() && it.length<=max} ?: throw ApiException.validation("$label обязательно и не длиннее $max символов")
    private fun String.optional(max:Int):String?=trim().takeIf{it.isNotEmpty()}?.takeIf{it.length<=max} ?: if(trim().isEmpty()) null else throw ApiException.validation("Поле не длиннее $max символов")
    private fun String.uuidOrNull(label:String):UUID=runCatching{UUID.fromString(this)}.getOrElse{throw ApiException.validation("Некорректный идентификатор: $label")}

    private data class AcceptedMedia(val mime:String,val extension:String,val maxBytes:Long)
    private fun matchesSignature(type: MediaType, mime: String, bytes: ByteArray, count: Int): Boolean {
        fun starts(vararg expected: Int): Boolean = count >= expected.size && expected.indices.all { index ->
            (bytes[index].toInt() and 0xff) == expected[index]
        }
        fun hasFrameSync(mask: Int, expected: Int): Boolean = count >= 2 &&
            (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and mask) == expected
        return when (type) {
            MediaType.IMAGE -> when (mime) {
                "image/jpeg" -> starts(0xff, 0xd8, 0xff)
                "image/png" -> starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                "image/webp" -> starts(0x52, 0x49, 0x46, 0x46) && count >= 12 && String(bytes, 8, 4) == "WEBP"
                else -> false
            }
            MediaType.AUDIO -> when (mime) {
                "audio/mpeg" -> starts(0x49, 0x44, 0x33) || hasFrameSync(0xe0, 0xe0)
                "audio/mp4" -> count >= 12 && String(bytes, 4, 4) == "ftyp"
                "audio/aac" -> hasFrameSync(0xf6, 0xf0)
                "audio/ogg" -> starts(0x4f, 0x67, 0x67, 0x53)
                "audio/wav" -> starts(0x52, 0x49, 0x46, 0x46) && count >= 12 && String(bytes, 8, 4) == "WAVE"
                else -> false
            }
        }
    }
    private companion object {
        val AllowedMedia = mapOf(
            MediaType.IMAGE to mapOf(
                "image/jpeg" to AcceptedMedia("image/jpeg", ".jpg", 5L * 1024 * 1024),
                "image/png" to AcceptedMedia("image/png", ".png", 5L * 1024 * 1024),
                "image/webp" to AcceptedMedia("image/webp", ".webp", 5L * 1024 * 1024)
            ),
            MediaType.AUDIO to mapOf(
                "audio/mpeg" to AcceptedMedia("audio/mpeg", ".mp3", 10L * 1024 * 1024),
                "audio/mp4" to AcceptedMedia("audio/mp4", ".m4a", 10L * 1024 * 1024),
                "audio/aac" to AcceptedMedia("audio/aac", ".aac", 10L * 1024 * 1024),
                "audio/ogg" to AcceptedMedia("audio/ogg", ".ogg", 10L * 1024 * 1024),
                "audio/wav" to AcceptedMedia("audio/wav", ".wav", 10L * 1024 * 1024)
            )
        )
    }
}
