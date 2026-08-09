package kz.oyla.server.model

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/** The ownership boundary is deliberately explicit in every content table. */
@Serializable enum class ContentOwnership { SYSTEM, CENTER }
@Serializable enum class ActivityType { SINGLE_CHOICE, WHITEBOARD }
@Serializable enum class ContentStatus { ACTIVE, ARCHIVED }
@Serializable enum class MediaType { IMAGE, AUDIO }
@Serializable enum class WhiteboardColor { BLACK, BLUE, GREEN, RED, ORANGE, PURPLE }
@Serializable enum class WhiteboardBrushSize { THIN, MEDIUM, THICK }
@Serializable enum class WhiteboardTool { PEN, ERASER }

/**
 * Whiteboard-specific content. It deliberately uses a bounded palette and brush enum so a
 * client cannot smuggle arbitrary colours or pixel widths into a lesson session.
 */
@Serializable
data class WhiteboardExerciseConfig(
    val backgroundAssetId: String? = null,
    val backgroundUrl: String? = null,
    val childDrawingInitiallyEnabled: Boolean = true,
    val availableColors: List<WhiteboardColor> = listOf(WhiteboardColor.BLACK, WhiteboardColor.BLUE, WhiteboardColor.GREEN, WhiteboardColor.RED),
    val defaultColor: WhiteboardColor = WhiteboardColor.BLACK,
    val defaultBrushSize: WhiteboardBrushSize = WhiteboardBrushSize.MEDIUM,
    val allowEraser: Boolean = true,
    val allowClear: Boolean = true
)

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
    val whiteboardConfig: WhiteboardExerciseConfig? = null,
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
    val options: List<ExerciseSnapshotOption> = emptyList(),
    val correctOptionId: String? = null,
    val whiteboardConfig: WhiteboardExerciseConfig? = null
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
