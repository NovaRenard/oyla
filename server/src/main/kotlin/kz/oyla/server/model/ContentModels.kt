package kz.oyla.server.model

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/** The ownership boundary is deliberately explicit in every content table. */
@Serializable enum class ContentOwnership { SYSTEM, CENTER }
@Serializable enum class ActivityType { SINGLE_CHOICE }
@Serializable enum class ContentStatus { ACTIVE, ARCHIVED }
@Serializable enum class MediaType { IMAGE, AUDIO }

data class MediaAssetRecord(
    val id: UUID,
    val centerId: UUID?,
    val ownership: ContentOwnership,
    val type: MediaType,
    val storageKey: String,
    val originalFilename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Instant
)

data class ContentExerciseOptionRecord(
    val id: UUID,
    val exerciseId: UUID,
    val label: String?,
    val imageAssetId: UUID?,
    /** System seed assets that still ship inside the existing Android APK. */
    val localImageAssetKey: String?,
    val sortOrder: Int,
    val isCorrect: Boolean
)

data class ContentExerciseRecord(
    val id: UUID,
    val centerId: UUID?,
    val ownership: ContentOwnership,
    val activityType: ActivityType,
    val title: String,
    val instructionText: String,
    val instructionAudioAssetId: UUID?,
    val localAudioAssetKey: String?,
    val status: ContentStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val options: List<ContentExerciseOptionRecord> = emptyList(),
    /** Deterministic link used only to migrate the five pre-v8 exercises. */
    val legacyKey: String? = null
)

data class LessonTemplateItemRecord(
    val id: UUID,
    val templateId: UUID,
    val exerciseId: UUID,
    val position: Int
)

data class LessonTemplateRecord(
    val id: UUID,
    val centerId: UUID?,
    val ownership: ContentOwnership,
    val name: String,
    val description: String?,
    val status: ContentStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val items: List<LessonTemplateItemRecord> = emptyList()
)

/** Immutable definition stored with each managed session rather than looking up mutable content. */
@Serializable
data class ExerciseSnapshot(
    val sourceExerciseId: String,
    val activityType: ActivityType,
    val title: String,
    val instructionText: String,
    val audioAssetId: String? = null,
    val audioUrl: String? = null,
    val localAudioAssetKey: String? = null,
    val options: List<ExerciseSnapshotOption>,
    val correctOptionId: String
)

@Serializable
data class ExerciseSnapshotOption(
    val id: String,
    val label: String? = null,
    val imageAssetId: String? = null,
    val imageUrl: String? = null,
    val localImageAssetKey: String? = null,
    val position: Int
)

data class LessonTemplateSnapshot(
    val id: UUID,
    val name: String,
    val description: String?,
    val exercises: List<ExerciseSnapshot>
)
