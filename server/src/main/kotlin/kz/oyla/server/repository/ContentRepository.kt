package kz.oyla.server.repository

import java.util.UUID
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.ContentExerciseRecord
import kz.oyla.server.model.ContentOwnership
import kz.oyla.server.model.ContentStatus
import kz.oyla.server.model.LessonTemplateRecord
import kz.oyla.server.model.MediaAssetRecord
import kz.oyla.server.model.MediaType

data class ContentExerciseFilter(
    val ownership: ContentOwnership? = null,
    val status: ContentStatus? = null,
    val activityType: ActivityType? = null,
    val search: String? = null
)
data class LessonTemplateFilter(val ownership: ContentOwnership? = null, val status: ContentStatus? = null, val search: String? = null)

interface ContentRepository {
    suspend fun listExercises(centerId: UUID, filter: ContentExerciseFilter = ContentExerciseFilter()): List<ContentExerciseRecord>
    suspend fun findExercise(id: UUID): ContentExerciseRecord?
    suspend fun findExerciseAccessible(centerId: UUID, id: UUID): ContentExerciseRecord?
    suspend fun insertExercise(record: ContentExerciseRecord): ContentExerciseRecord
    suspend fun updateExercise(record: ContentExerciseRecord): Boolean
    suspend fun templateUsageCount(exerciseId: UUID): Int
    suspend fun activeTemplateUsageCount(exerciseId: UUID): Int

    suspend fun listTemplates(centerId: UUID, filter: LessonTemplateFilter = LessonTemplateFilter()): List<LessonTemplateRecord>
    suspend fun findTemplate(id: UUID): LessonTemplateRecord?
    suspend fun findTemplateAccessible(centerId: UUID, id: UUID): LessonTemplateRecord?
    suspend fun insertTemplate(record: LessonTemplateRecord): LessonTemplateRecord
    suspend fun updateTemplate(record: LessonTemplateRecord): Boolean
    suspend fun replaceTemplateItems(templateId: UUID, items: List<UUID>): Boolean

    suspend fun insertMedia(asset: MediaAssetRecord): MediaAssetRecord
    suspend fun findMedia(id: UUID): MediaAssetRecord?
    suspend fun findMediaAccessible(centerId: UUID, id: UUID, type: MediaType? = null): MediaAssetRecord?
    suspend fun mediaUsageCount(id: UUID): Int
}
