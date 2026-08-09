package kz.oyla.server.model.dto

import kotlinx.serialization.Serializable
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.ContentOwnership
import kz.oyla.server.model.ContentStatus
import kz.oyla.server.model.MediaType
import kz.oyla.server.model.WhiteboardBrushSize
import kz.oyla.server.model.WhiteboardColor

@Serializable data class MediaAssetDto(
    val id: String,
    val ownership: ContentOwnership,
    val type: MediaType,
    val originalFilename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val url: String,
    val createdAt: String
)

@Serializable data class ExerciseOptionContentDto(
    val id: String,
    val label: String? = null,
    val imageAssetId: String? = null,
    val imageUrl: String? = null,
    val localImageAssetKey: String? = null,
    val sortOrder: Int,
    val isCorrect: Boolean
)

@Serializable data class ContentExerciseDto(
    val id: String,
    val ownership: ContentOwnership,
    val activityType: ActivityType,
    val title: String,
    val instructionText: String,
    val instructionAudioAssetId: String? = null,
    val audioUrl: String? = null,
    val localAudioAssetKey: String? = null,
    val status: ContentStatus,
    val options: List<ExerciseOptionContentDto>,
    val whiteboardConfig: WhiteboardExerciseConfigDto? = null,
    val templateUsageCount: Int = 0,
    val createdAt: String,
    val updatedAt: String
)

@Serializable data class WhiteboardExerciseConfigDto(
    val backgroundAssetId: String? = null,
    val backgroundUrl: String? = null,
    val childDrawingInitiallyEnabled: Boolean = true,
    val availableColors: List<WhiteboardColor> = emptyList(),
    val defaultColor: WhiteboardColor = WhiteboardColor.BLACK,
    val defaultBrushSize: WhiteboardBrushSize = WhiteboardBrushSize.MEDIUM,
    val allowEraser: Boolean = true,
    val allowClear: Boolean = true
)

@Serializable data class ExerciseOptionInput(
    val id: String? = null,
    val label: String? = null,
    val imageAssetId: String? = null,
    val sortOrder: Int? = null,
    val isCorrect: Boolean = false
)

@Serializable data class CreateExerciseRequest(
    val title: String,
    val instructionText: String,
    val activityType: ActivityType = ActivityType.SINGLE_CHOICE,
    val instructionAudioAssetId: String? = null,
    val options: List<ExerciseOptionInput> = emptyList(),
    val whiteboardConfig: WhiteboardExerciseConfigDto? = null
)

/** PATCH is a complete type-specific definition to keep validation and ordering atomic. */
@Serializable data class UpdateExerciseRequest(
    val title: String? = null,
    val instructionText: String? = null,
    val instructionAudioAssetId: String? = null,
    val options: List<ExerciseOptionInput>? = null,
    val whiteboardConfig: WhiteboardExerciseConfigDto? = null
)

@Serializable data class LessonTemplateItemDto(
    val id: String,
    val exerciseId: String,
    val position: Int,
    val exerciseTitle: String? = null,
    val activityType: ActivityType? = null
)

@Serializable data class LessonTemplateDto(
    val id: String,
    val ownership: ContentOwnership,
    val name: String,
    val description: String? = null,
    val status: ContentStatus,
    val exerciseCount: Int,
    val items: List<LessonTemplateItemDto> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

@Serializable data class LessonTemplateItemInput(val exerciseId: String)
@Serializable data class CreateLessonTemplateRequest(val name: String, val description: String? = null, val items: List<LessonTemplateItemInput>)
@Serializable data class UpdateLessonTemplateRequest(val name: String? = null, val description: String? = null, val items: List<LessonTemplateItemInput>? = null)
@Serializable data class ReplaceLessonTemplateItemsRequest(val items: List<LessonTemplateItemInput>)

@Serializable data class DeviceLessonTemplateDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val ownership: ContentOwnership,
    val exerciseCount: Int
)
