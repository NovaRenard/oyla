package kz.oyla.server.model.dto

import kotlinx.serialization.Serializable
import kz.oyla.server.model.ExerciseStatus
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.WhiteboardColor
import kz.oyla.server.model.WhiteboardTool
import kz.oyla.server.model.WhiteboardBrushSize

@Serializable
data class ExerciseOptionDto(
    val id: String,
    val label: String,
    val imageAssetKey: String,
    val position: Int,
    val imageUrl: String? = null,
    /** SORT only: "A" or "B". */
    val groupKey: String? = null,
    /** WORD_BUILD only: 1..n for letters of the word, null for distractor letters. */
    val sequenceIndex: Int? = null,
    /** POP, CATEGORY_HOUSE, MISSING (both roles): balloon to pop / item of the category / missing item; null for every other type. */
    val isTarget: Boolean? = null,
    /** LABEL_SCENE, MISSING, ARROWS: cell of the scene or board. */
    val cellIndex: Int? = null
)

@Serializable
data class ExerciseDto(
    val id: String,
    val instructionText: String,
    val audioAssetKey: String? = null,
    val options: List<ExerciseOptionDto> = emptyList(),
    val title: String? = null,
    val activityType: String = "SINGLE_CHOICE",
    val audioUrl: String? = null,
    val whiteboardConfig: WhiteboardExerciseConfigDto? = null,
    val gameConfig: GameExerciseConfigDto? = null
)

/** Settings for every child game type (see docs/game-generation-contract.md). */
@Serializable
data class GameExerciseConfigDto(
    val groupLabels: List<String> = emptyList(),
    val showTargetMs: Int? = null,
    val mazeLayout: String? = null,
    val imageAssetKey: String? = null,
    val imageAssetId: String? = null,
    val imageUrl: String? = null,
    /** Filled only in session DTOs of REMEMBER exercises; ignored on content input. */
    val targetOptionId: String? = null,
    /** SYLLABLES only: 2..6 non-blank syllables, each up to 4 characters. */
    val syllables: List<String> = emptyList(),
    /** COUNT only: 1..10, must equal the correct option's label. */
    val countValue: Int? = null,
    /** The variant is generated at session start; the fields below are constraints in content and concrete values in sessions. */
    val generate: Boolean = false,
    /** 1..3 difficulty. */
    val level: Int? = null,
    /** MATH: "+", "-", "×" ("±" in content only). */
    val mathOp: String? = null,
    /** MATH: example operands; PAIRS: cups (A) and plates (B). */
    val operandA: Int? = null,
    val operandB: Int? = null,
    /** TYPE_WORD, PHONETICS: the word in upper case. */
    val word: String? = null,
    /** Picture category key (ODD_ONE, SHADOW, CATEGORY_HOUSE…). */
    val category: String? = null,
    /** Child-facing category label («Фрукты»). */
    val categoryLabel: String? = null,
    /** BASKET: what to put into the container. */
    val requirements: List<BasketNeedDto> = emptyList(),
    /** BASKET: "basket" or "bottle". */
    val container: String? = null,
    /** ARROWS: board side, start cell (row * gridSize + col), stones and diagonal arrows. */
    val gridSize: Int? = null,
    val startCell: Int? = null,
    val blockedCells: List<Int> = emptyList(),
    val diagonals: Boolean = false,
    /** POP/SORT generation: the sound (a letter). */
    val sound: String? = null
)

@Serializable
data class BasketNeedDto(val imageAssetKey: String, val label: String, val count: Int)

@Serializable data class WhiteboardPointDto(val x: Float, val y: Float)

@Serializable data class WhiteboardStrokeDto(
    val id: String,
    val sessionExerciseId: String,
    val actorRole: String,
    val actorDeviceId: String,
    val sequenceNumber: Int,
    val tool: WhiteboardTool,
    val color: WhiteboardColor? = null,
    val brushSize: WhiteboardBrushSize,
    val points: List<WhiteboardPointDto>,
    val createdAt: String
)

@Serializable data class WhiteboardStateSnapshotDto(
    val sessionExerciseId: String,
    val childDrawingEnabled: Boolean,
    val boardRevision: Int,
    val clearRevision: Int,
    val strokes: List<WhiteboardStrokeDto>
)

@Serializable data class CompleteWhiteboardExerciseRequest(val sessionExerciseId: String)

@Serializable
data class SpecialistExerciseDto(val exercise: ExerciseDto, val correctOptionId: String? = null)

@Serializable
data class ShowExerciseRequest(val exerciseId: String)

@Serializable
data class ShowExerciseResponse(
    val sessionExerciseId: String,
    val exerciseId: String,
    val status: ExerciseStatus
)

@Serializable
data class StartExerciseRequest(val sessionExerciseId: String)

@Serializable
data class StartExerciseResponse(
    val sessionExerciseId: String,
    val status: ExerciseStatus,
    val startedAt: String
)

@Serializable
data class AnswerExerciseRequest(
    val sessionExerciseId: String,
    val selectedOptionId: String,
    val clientEventId: String,
    /**
     * MEMORY: second card option id; SORT: "A" or "B"; SHADOW: shadow (slot) option id; PHONETICS: colour key;
     * LABEL_SCENE: object option id; PARKING: spot colour; ignored otherwise.
     */
    val secondaryValue: String? = null
)

@Serializable
data class AnswerExerciseResponse(
    val sessionExerciseId: String,
    val selectedOptionId: String,
    val selectedOptionLabel: String,
    val isCorrect: Boolean,
    val attemptNumber: Int,
    val responseTimeMs: Long,
    val exerciseStatus: ExerciseStatus,
    val secondaryValue: String? = null,
    val progressDone: Int = 0,
    val progressTotal: Int = 0
)

@Serializable
data class NextExerciseRequest(val currentSessionExerciseId: String)

@Serializable
data class ExerciseStateResponse(
    val sessionExerciseId: String? = null,
    val exerciseStatus: ExerciseStatus = ExerciseStatus.PENDING,
    val exercise: ExerciseDto? = null,
    val correctOptionId: String? = null,
    val latestAnswer: AnswerExerciseResponse? = null,
    val attemptCount: Int = 0,
    val startedAt: String? = null,
    val currentPosition: Int = 1,
    val totalExercises: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = true,
    val planCompleted: Boolean = false
    ,val whiteboardState: WhiteboardStateSnapshotDto? = null,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    /** MEMORY: matched ids; SORT: correctly placed ids; WORD_BUILD: used ids in order; POP: popped ids; SHADOW: placed ids. */
    val solvedOptionIds: List<String> = emptyList()
)

@Serializable
data class ExerciseSummaryItemDto(
    val position: Int,
    val exerciseId: String,
    val activityType: ActivityType = ActivityType.SINGLE_CHOICE,
    val instructionText: String,
    val correctOptionLabel: String = "",
    val attemptCount: Int,
    val incorrectAttempts: Int,
    val firstAttemptCorrect: Boolean,
    val timeToCorrectMs: Long = 0,
    val startedAt: String,
    val completedAt: String,
    val durationMs: Long? = null,
    val strokeCount: Int? = null,
    val childStrokeCount: Int? = null,
    val specialistStrokeCount: Int? = null
)

@Serializable
data class SessionSummaryResponse(
    val sessionId: String,
    val childName: String,
    val completedExercises: Int,
    val totalExercises: Int,
    val totalAttempts: Int,
    val incorrectAttempts: Int,
    val firstAttemptCorrectCount: Int,
    val firstAttemptCorrectPercent: Int,
    val activeDurationMs: Long,
    val startedAt: String,
    val completedAt: String,
    val exercises: List<ExerciseSummaryItemDto>
)
