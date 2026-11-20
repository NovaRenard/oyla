package kz.oyla.server.model

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/** The ownership boundary is deliberately explicit in every content table. */
@Serializable enum class ContentOwnership { SYSTEM, CENTER }
@Serializable enum class ActivityType {
    SINGLE_CHOICE, WHITEBOARD, ODD_ONE, REMEMBER, MEMORY, SORT, WORD_BUILD, MAZE, POP, SYLLABLES, SHADOW, COUNT,
    MATH, PAIRS, PARTS, TYPE_WORD, PHONETICS, LABEL_SCENE, BASKET, CATEGORY_HOUSE, MISSING, PARKING, ARROWS
}
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
    val allowClear: Boolean = true,
    /** Optional dotted outline the child traces; one of [TraceTemplates], null for a blank board. */
    val traceTemplate: String? = null
) {
    companion object {
        val TraceTemplates = setOf("sun", "house", "fish", "star", "flower", "heart")
    }
}

/**
 * Settings shared by the child-game activity types. Only the fields relevant to the
 * exercise's type are kept; [imageUrl] and [targetOptionId] are derived, never stored.
 */
@Serializable
data class GameExerciseConfig(
    /** SORT only: exactly two short labels; group A = index 0, group B = index 1. */
    val groupLabels: List<String> = emptyList(),
    /** REMEMBER only: 1000..10000 ms, 3000 when null. */
    val showTargetMs: Int? = null,
    /** MAZE only: one of [MazeLayouts]. */
    val mazeLayout: String? = null,
    /** WORD_BUILD, SYLLABLES, COUNT: picture shipped inside the APK. */
    val imageAssetKey: String? = null,
    /** WORD_BUILD, SYLLABLES, COUNT: uploaded IMAGE media id (alternative to [imageAssetKey]). */
    val imageAssetId: String? = null,
    val imageUrl: String? = null,
    /** REMEMBER only, session DTO only: the option the child must memorise. */
    val targetOptionId: String? = null,
    /** SYLLABLES only: 2..6 syllables of the pictured word, e.g. ["РА","КЕ","ТА"]. */
    val syllables: List<String> = emptyList(),
    /** COUNT only: 1..10, equals the label of the correct option. */
    val countValue: Int? = null,
    /** A concrete variant is generated at session start (see GameGenerator); set fields below act as constraints. */
    val generate: Boolean = false,
    /** 1..3 generator difficulty, 1 when null. */
    val level: Int? = null,
    /** MATH: "+", "-", "×"; content may also say "±" (mixed). Sessions always carry a concrete operator. */
    val mathOp: String? = null,
    /** MATH: example operands; PAIRS: how many cups (A) and plates (B). */
    val operandA: Int? = null,
    val operandB: Int? = null,
    /** TYPE_WORD, PHONETICS: the word in upper case. */
    val word: String? = null,
    /** Picture pool category key (see PicturePool.Categories). */
    val category: String? = null,
    /** Child-facing label of [category], e.g. «Фрукты». */
    val categoryLabel: String? = null,
    /** BASKET: what must be put into the container. */
    val requirements: List<BasketNeed> = emptyList(),
    /** BASKET: "basket" or "bottle". */
    val container: String? = null,
    /** ARROWS: side of the square board. */
    val gridSize: Int? = null,
    /** ARROWS: start cell index (row * gridSize + col). */
    val startCell: Int? = null,
    /** ARROWS: stones. */
    val blockedCells: List<Int> = emptyList(),
    /** ARROWS: diagonal arrows are available. */
    val diagonals: Boolean = false,
    /** POP generation: the sound (a single upper-case letter). */
    val sound: String? = null
) {
    companion object {
        val MazeLayouts = setOf("wave", "zigzag", "spiral")
        const val DefaultShowTargetMs = 3000
        val Containers = setOf("basket", "bottle")
        val ParkingColors = listOf("RED", "BLUE", "YELLOW", "GREEN", "ORANGE", "PURPLE", "PINK")
    }
}

/** BASKET: put [count] pictures [imageAssetKey] («[label]») into the container. */
@Serializable
data class BasketNeed(val imageAssetKey: String, val label: String, val count: Int)

/** Activity types the child answers through the attempts API. */
val ActivityType.isChoiceLike: Boolean get() = this == ActivityType.SINGLE_CHOICE || this == ActivityType.ODD_ONE || this == ActivityType.REMEMBER || this == ActivityType.COUNT ||
    this == ActivityType.MATH || this == ActivityType.PAIRS || this == ActivityType.PARTS
/** Types that exist only as generated content: centres must create them with generate = true. */
val ActivityType.isGeneratedOnly: Boolean get() = this in GeneratedOnlyTypes
private val GeneratedOnlyTypes = setOf(ActivityType.MATH, ActivityType.PAIRS, ActivityType.PARTS, ActivityType.TYPE_WORD, ActivityType.PHONETICS,
    ActivityType.LABEL_SCENE, ActivityType.BASKET, ActivityType.CATEGORY_HOUSE, ActivityType.MISSING, ActivityType.PARKING, ActivityType.ARROWS)
val ActivityType.isGame: Boolean get() = this != ActivityType.SINGLE_CHOICE && this != ActivityType.WHITEBOARD

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
    val isCorrect: Boolean,
    /** SORT only: "A" or "B". */
    val groupKey: String? = null,
    /** WORD_BUILD only: 1..n for letters of the word, null for distractors. */
    val sequenceIndex: Int? = null
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
    val legacyKey: String? = null,
    val gameConfig: GameExerciseConfig? = null
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
    val whiteboardConfig: WhiteboardExerciseConfig? = null,
    val gameConfig: GameExerciseConfig? = null
)

@Serializable
data class ExerciseSnapshotOption(
    val id: String,
    val label: String? = null,
    val imageAssetId: String? = null,
    val imageUrl: String? = null,
    val localImageAssetKey: String? = null,
    val position: Int,
    val groupKey: String? = null,
    val sequenceIndex: Int? = null,
    /** POP, CATEGORY_HOUSE, MISSING: a balloon to pop / an item of the category / a missing item; null otherwise. */
    val isTarget: Boolean? = null,
    /** LABEL_SCENE, MISSING, ARROWS: cell of the scene or board. */
    val cellIndex: Int? = null
)

data class LessonTemplateSnapshot(
    val id: UUID,
    val name: String,
    val description: String?,
    val exercises: List<ExerciseSnapshot>
)
