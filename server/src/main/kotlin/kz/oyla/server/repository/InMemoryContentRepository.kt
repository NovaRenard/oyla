package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.ContentExerciseOptionRecord
import kz.oyla.server.model.ContentExerciseRecord
import kz.oyla.server.model.ContentOwnership
import kz.oyla.server.model.ContentStatus
import kz.oyla.server.model.LessonTemplateItemRecord
import kz.oyla.server.model.LessonTemplateRecord
import kz.oyla.server.model.MediaAssetRecord
import kz.oyla.server.model.MediaType

/** Route-test repository with the same ownership filtering as PostgreSQL. */
class InMemoryContentRepository : ContentRepository {
    private val exercises = linkedMapOf<UUID, ContentExerciseRecord>()
    private val templates = linkedMapOf<UUID, LessonTemplateRecord>()
    private val media = linkedMapOf<UUID, MediaAssetRecord>()

    init { seedSystemContent() }

    override suspend fun listExercises(centerId: UUID, filter: ContentExerciseFilter) = synchronized(this) {
        exercises.values.filter { it.ownership == ContentOwnership.SYSTEM || it.centerId == centerId }
            .filter { filter.ownership == null || it.ownership == filter.ownership }
            .filter { filter.status == null || it.status == filter.status }
            .filter { filter.activityType == null || it.activityType == filter.activityType }
            .filter { filter.search.isNullOrBlank() || "${it.title} ${it.instructionText}".contains(filter.search, true) }
            .sortedWith(compareByDescending<ContentExerciseRecord> { it.ownership == ContentOwnership.SYSTEM }.thenBy { it.title })
    }
    override suspend fun findExercise(id: UUID) = synchronized(this) { exercises[id] }
    override suspend fun findExerciseAccessible(centerId: UUID, id: UUID) = synchronized(this) { exercises[id]?.takeIf { it.ownership == ContentOwnership.SYSTEM || it.centerId == centerId } }
    override suspend fun insertExercise(record: ContentExerciseRecord) = synchronized(this) { exercises[record.id] = record; record }
    override suspend fun updateExercise(record: ContentExerciseRecord) = synchronized(this) {
        if (exercises[record.id]?.let { it.ownership == ContentOwnership.CENTER && it.centerId == record.centerId } != true) false else { exercises[record.id] = record; true }
    }
    override suspend fun templateUsageCount(exerciseId: UUID) = synchronized(this) { templates.values.sumOf { template -> template.items.count { it.exerciseId == exerciseId } } }
    override suspend fun activeTemplateUsageCount(exerciseId: UUID) = synchronized(this) { templates.values.filter { it.status == ContentStatus.ACTIVE }.sumOf { template -> template.items.count { it.exerciseId == exerciseId } } }

    override suspend fun listTemplates(centerId: UUID, filter: LessonTemplateFilter) = synchronized(this) {
        templates.values.filter { it.ownership == ContentOwnership.SYSTEM || it.centerId == centerId }
            .filter { filter.ownership == null || it.ownership == filter.ownership }.filter { filter.status == null || it.status == filter.status }
            .filter { filter.search.isNullOrBlank() || "${it.name} ${it.description.orEmpty()}".contains(filter.search, true) }
            .sortedWith(compareByDescending<LessonTemplateRecord> { it.ownership == ContentOwnership.SYSTEM }.thenBy { it.name })
    }
    override suspend fun findTemplate(id: UUID) = synchronized(this) { templates[id] }
    override suspend fun findTemplateAccessible(centerId: UUID, id: UUID) = synchronized(this) { templates[id]?.takeIf { it.ownership == ContentOwnership.SYSTEM || it.centerId == centerId } }
    override suspend fun insertTemplate(record: LessonTemplateRecord) = synchronized(this) { templates[record.id] = record; record }
    override suspend fun updateTemplate(record: LessonTemplateRecord) = synchronized(this) {
        if (templates[record.id]?.let { it.ownership == ContentOwnership.CENTER && it.centerId == record.centerId } != true) false else { templates[record.id] = record; true }
    }
    override suspend fun replaceTemplateItems(templateId: UUID, items: List<UUID>) = synchronized(this) {
        val current = templates[templateId]?.takeIf { it.ownership == ContentOwnership.CENTER } ?: return@synchronized false
        templates[templateId] = current.copy(items = items.mapIndexed { i, id -> LessonTemplateItemRecord(UUID.randomUUID(), templateId, id, i + 1) }); true
    }

    override suspend fun insertMedia(asset: MediaAssetRecord) = synchronized(this) { media[asset.id] = asset; asset }
    override suspend fun findMedia(id: UUID) = synchronized(this) { media[id] }
    override suspend fun findMediaAccessible(centerId: UUID, id: UUID, type: MediaType?) = synchronized(this) { media[id]?.takeIf { (it.ownership == ContentOwnership.SYSTEM || it.centerId == centerId) && (type == null || it.type == type) } }
    override suspend fun mediaUsageCount(id: UUID) = synchronized(this) { exercises.values.sumOf { exercise ->
        (if (exercise.instructionAudioAssetId == id) 1 else 0) + exercise.options.count { it.imageAssetId == id } +
            if (exercise.whiteboardConfig?.backgroundAssetId == id.toString()) 1 else 0
    } }

    private fun seedSystemContent() {
        val now = Instant.EPOCH
        val source = listOf(
            listOf("00000000-0000-0000-0000-000000000101", "Звук «Р»: ракета", "sound-r-rocket", "exercise_sound_r", "rocket", listOf("rocket" to "ракета" to "exercise_rocket", "cat" to "кот" to "exercise_cat", "house" to "дом" to "exercise_house", "fox" to "лиса" to "exercise_fox")),
            listOf("00000000-0000-0000-0000-000000000102", "Звук «Р»: рыба", "sound-r-fish", "exercise_sound_r", "fish", listOf("fish" to "рыба" to "exercise_fish", "apple" to "яблоко" to "exercise_apple", "duck" to "утка" to "exercise_duck", "elephant" to "слон" to "exercise_elephant")),
            listOf("00000000-0000-0000-0000-000000000103", "Звук «Л»: лампа", "sound-l-lamp", "exercise_sound_l", "lamp", listOf("lamp" to "лампа" to "exercise_lamp", "cat" to "кот" to "exercise_cat", "house" to "дом" to "exercise_house", "fish" to "рыба" to "exercise_fish")),
            listOf("00000000-0000-0000-0000-000000000104", "Звук «С»: собака", "sound-s-dog", "exercise_sound_s", "dog", listOf("dog" to "собака" to "exercise_dog", "cat" to "кот" to "exercise_cat", "house" to "дом" to "exercise_house", "fish" to "рыба" to "exercise_fish")),
            listOf("00000000-0000-0000-0000-000000000105", "Звук «Ш»: шар", "sound-sh-ball", "exercise_sound_sh", "ball", listOf("ball" to "шар" to "exercise_ball", "cat" to "кот" to "exercise_cat", "house" to "дом" to "exercise_house", "fox" to "лиса" to "exercise_fox"))
        )
        source.forEach { row ->
            @Suppress("UNCHECKED_CAST") val choices = row[5] as List<Pair<Pair<String, String>, String>>
            val id = UUID.fromString(row[0] as String); val legacyKey = row[2] as String; val correct = row[4] as String
            exercises[id] = ContentExerciseRecord(id, null, ContentOwnership.SYSTEM, ActivityType.SINGLE_CHOICE, row[1] as String,
                legacyInstruction(legacyKey), null, row[3] as String, ContentStatus.ACTIVE, now, now,
                choices.mapIndexed { index, option -> ContentExerciseOptionRecord(UUID.randomUUID(), id, option.first.second, null, option.second, index + 1, option.first.first == correct) }, null, legacyKey)
        }
        val templateId = UUID.fromString("00000000-0000-0000-0000-000000000201")
        templates[templateId] = LessonTemplateRecord(templateId, null, ContentOwnership.SYSTEM, "Базовое занятие Oyla", "Пять исходных упражнений Oyla", ContentStatus.ACTIVE, now, now,
            exercises.keys.sorted().mapIndexed { i, exerciseId -> LessonTemplateItemRecord(UUID.randomUUID(), templateId, exerciseId, i + 1) })
    }
    private fun legacyInstruction(key: String) = when {
        key.startsWith("sound-r") -> "Найди картинку, в названии которой есть звук «Р»"
        key.startsWith("sound-l") -> "Найди картинку, в названии которой есть звук «Л»"
        key.startsWith("sound-s") -> "Найди картинку, в названии которой есть звук «С»"
        else -> "Найди картинку, в названии которой есть звук «Ш»"
    }
}
