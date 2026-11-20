package kz.oyla.server.repository

import java.time.Instant
import java.util.UUID
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.ContentExerciseOptionRecord
import kz.oyla.server.model.ContentExerciseRecord
import kz.oyla.server.model.ContentOwnership
import kz.oyla.server.model.ContentStatus
import kz.oyla.server.model.GameExerciseConfig
import kz.oyla.server.model.LessonTemplateItemRecord
import kz.oyla.server.model.LessonTemplateRecord
import kz.oyla.server.model.MediaAssetRecord
import kz.oyla.server.model.MediaType
import kz.oyla.server.model.WhiteboardBrushSize
import kz.oyla.server.model.WhiteboardColor
import kz.oyla.server.model.WhiteboardExerciseConfig

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
            (if (exercise.whiteboardConfig?.backgroundAssetId == id.toString()) 1 else 0) +
            if (exercise.gameConfig?.imageAssetId == id.toString()) 1 else 0
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
        seedSystemGames(now)
    }

    /** Mirrors V11__add_game_activity_types.sql, V12__add_more_game_types.sql and V13__more_exercise_variations.sql with the same deterministic ids. */
    private fun seedSystemGames(now: Instant) {
        fun id(suffix: Int) = UUID.fromString("00000000-0000-0000-0000-%012d".format(suffix))
        data class Seed(val label: String, val key: String? = null, val correct: Boolean = false, val group: String? = null, val sequence: Int? = null)
        fun game(exerciseId: Int, type: ActivityType, title: String, instruction: String, config: GameExerciseConfig?, firstOptionId: Int, options: List<Seed>, audio: String? = null) {
            val exercise = id(exerciseId)
            exercises[exercise] = ContentExerciseRecord(exercise, null, ContentOwnership.SYSTEM, type, title, instruction, null, audio, ContentStatus.ACTIVE, now, now,
                options.mapIndexed { index, option -> ContentExerciseOptionRecord(id(firstOptionId + index), exercise, option.label, null, option.key, index + 1, option.correct, option.group, option.sequence) },
                gameConfig = config)
        }
        game(301, ActivityType.MEMORY, "Найди пару", "Открой две карточки и найди одинаковые", null, 401, listOf(
            Seed("рыба", "exercise_fish"), Seed("яблоко", "exercise_apple"), Seed("дом", "exercise_house"),
            Seed("утка", "exercise_duck"), Seed("мяч", "exercise_ball"), Seed("кот", "exercise_cat")))
        game(302, ActivityType.REMEMBER, "Запомни и найди", "Запомни картинку и найди её", GameExerciseConfig(showTargetMs = 3000), 411, listOf(
            Seed("синяя звезда", "shape_star_blue", correct = true), Seed("красная звезда", "shape_star_red"), Seed("синий круг", "shape_circle_blue"),
            Seed("зелёная звезда", "shape_star_green"), Seed("синий треугольник", "shape_triangle_blue"), Seed("красный квадрат", "shape_square_red")))
        game(303, ActivityType.ODD_ONE, "Что лишнее?", "Найди, что здесь лишнее", null, 421, listOf(
            Seed("яблоко", "exercise_apple"), Seed("груша", "exercise_pear"), Seed("машина", "exercise_car", correct = true), Seed("банан", "exercise_banana")))
        game(304, ActivityType.SORT, "Разложи по корзинам: Р и Л", "Где звук Р — в оранжевую корзину, где звук Л — в синюю", GameExerciseConfig(groupLabels = listOf("Р", "Л")), 431, listOf(
            Seed("ракета", "exercise_rocket", group = "A"), Seed("рыба", "exercise_fish", group = "A"),
            Seed("лампа", "exercise_lamp", group = "B"), Seed("лодка", "exercise_boat", group = "B")))
        game(305, ActivityType.MAZE, "Проведи пчёлку к цветку", "Проведи пчёлку по дорожке к цветку", GameExerciseConfig(mazeLayout = "wave"), 0, emptyList())
        game(306, ActivityType.WORD_BUILD, "Собери слово КОТ", "Собери слово из букв", GameExerciseConfig(imageAssetKey = "exercise_cat"), 441, listOf(
            Seed("К", sequence = 1), Seed("О", sequence = 2), Seed("Т", sequence = 3), Seed("А"), Seed("М")))
        // Mirrors V12__add_more_game_types.sql.
        game(307, ActivityType.POP, "Лопни шарики со звуком Р", "Лопни шарики, где есть звук Р", null, 451, listOf(
            Seed("ракета", "exercise_rocket", correct = true), Seed("кот", "exercise_cat"), Seed("рыба", "exercise_fish", correct = true),
            Seed("дом", "exercise_house"), Seed("груша", "exercise_pear", correct = true), Seed("утка", "exercise_duck")))
        game(308, ActivityType.SYLLABLES, "Слоги: ракета", "Постучи по барабану на каждый слог",
            GameExerciseConfig(syllables = listOf("РА", "КЕ", "ТА"), imageAssetKey = "exercise_rocket"), 0, emptyList())
        game(309, ActivityType.SHADOW, "Найди тень", "Найди, чья это тень", null, 461, listOf(
            Seed("рыба", "exercise_fish"), Seed("утка", "exercise_duck"), Seed("машина", "exercise_car")))
        game(310, ActivityType.COUNT, "Сколько рыбок?", "Сколько рыбок в аквариуме?", GameExerciseConfig(countValue = 4, imageAssetKey = "exercise_fish"), 471, listOf(
            Seed("1"), Seed("2"), Seed("3"), Seed("4", correct = true), Seed("5")))

        fun template(templateId: Int, name: String, description: String?, firstItemId: Int, exerciseIds: List<Int>) {
            val tid = id(templateId)
            templates[tid] = LessonTemplateRecord(tid, null, ContentOwnership.SYSTEM, name, description, ContentStatus.ACTIVE, now, now,
                exerciseIds.mapIndexed { index, exerciseId -> LessonTemplateItemRecord(id(firstItemId + index), tid, id(exerciseId), index + 1) })
        }
        template(501, "Игры Ойлика", "Мышление, внимание, моторика и грамота", 511, listOf(301, 302, 303, 304, 305, 306))
        template(502, "Звуки и игры", null, 521, listOf(101, 304, 301, 303, 306))
        template(503, "Весёлые игры", "Шарики, слоги, тени и счёт", 531, listOf(307, 308, 309, 310, 301, 303))

        // Mirrors V13__more_exercise_variations.sql.
        fun board(exerciseId: Int, title: String, instruction: String, traceTemplate: String?, color: WhiteboardColor) {
            val exercise = id(exerciseId)
            exercises[exercise] = ContentExerciseRecord(exercise, null, ContentOwnership.SYSTEM, ActivityType.WHITEBOARD, title, instruction, null, null, ContentStatus.ACTIVE, now, now,
                whiteboardConfig = WhiteboardExerciseConfig(childDrawingInitiallyEnabled = true, availableColors = WhiteboardColor.entries.toList(), defaultColor = color,
                    defaultBrushSize = WhiteboardBrushSize.MEDIUM, allowEraser = true, allowClear = true, traceTemplate = traceTemplate))
        }
        game(601, ActivityType.SINGLE_CHOICE, "Звук «Р»: корова", "Найди картинку, в названии которой есть звук «Р»", null, 701, listOf(
            Seed("утка", "exercise_duck"), Seed("корова", "exercise_cow", correct = true), Seed("мяч", "exercise_ball"), Seed("банан", "exercise_banana")), audio = "exercise_sound_r")
        game(602, ActivityType.SINGLE_CHOICE, "Звук «Р»: торт", "Найди картинку, в названии которой есть звук «Р»", null, 705, listOf(
            Seed("кот", "exercise_cat"), Seed("сок", "exercise_juice"), Seed("торт", "exercise_cake", correct = true), Seed("луна", "exercise_moon")), audio = "exercise_sound_r")
        game(603, ActivityType.SINGLE_CHOICE, "Звук «Р»: морковь", "Найди картинку, в названии которой есть звук «Р»", null, 709, listOf(
            Seed("лук", "exercise_onion"), Seed("дом", "exercise_house"), Seed("медведь", "exercise_bear"), Seed("морковь", "exercise_carrot", correct = true)), audio = "exercise_sound_r")
        game(604, ActivityType.SINGLE_CHOICE, "Звук «Л»: луна", "Найди картинку, в названии которой есть звук «Л»", null, 713, listOf(
            Seed("луна", "exercise_moon", correct = true), Seed("кот", "exercise_cat"), Seed("мяч", "exercise_ball"), Seed("сыр", "exercise_cheese")), audio = "exercise_sound_l")
        game(605, ActivityType.SINGLE_CHOICE, "Звук «Л»: ключ", "Найди картинку, в названии которой есть звук «Л»", null, 717, listOf(
            Seed("дом", "exercise_house"), Seed("ключ", "exercise_key", correct = true), Seed("утка", "exercise_duck"), Seed("торт", "exercise_cake")), audio = "exercise_sound_l")
        game(606, ActivityType.SINGLE_CHOICE, "Звук «Л»: лук", "Найди картинку, в названии которой есть звук «Л»", null, 721, listOf(
            Seed("банан", "exercise_banana"), Seed("сова", "exercise_owl"), Seed("лук", "exercise_onion", correct = true), Seed("рак", "exercise_crayfish")), audio = "exercise_sound_l")
        game(607, ActivityType.SINGLE_CHOICE, "Звук «С»: сова", "Найди картинку, в названии которой есть звук «С»", null, 725, listOf(
            Seed("утка", "exercise_duck"), Seed("дом", "exercise_house"), Seed("мяч", "exercise_ball"), Seed("сова", "exercise_owl", correct = true)), audio = "exercise_sound_s")
        game(608, ActivityType.SINGLE_CHOICE, "Звук «С»: сок", "Найди картинку, в названии которой есть звук «С»", null, 729, listOf(
            Seed("сок", "exercise_juice", correct = true), Seed("кот", "exercise_cat"), Seed("банан", "exercise_banana"), Seed("рыба", "exercise_fish")), audio = "exercise_sound_s")
        game(609, ActivityType.SINGLE_CHOICE, "Звук «Ш»: шапка", "Найди картинку, в названии которой есть звук «Ш»", null, 733, listOf(
            Seed("кот", "exercise_cat"), Seed("шапка", "exercise_hat", correct = true), Seed("луна", "exercise_moon"), Seed("сыр", "exercise_cheese")), audio = "exercise_sound_sh")
        game(610, ActivityType.SINGLE_CHOICE, "Звук «Ш»: мышь", "Найди картинку, в названии которой есть звук «Ш»", null, 737, listOf(
            Seed("дом", "exercise_house"), Seed("утка", "exercise_duck"), Seed("мышь", "exercise_mouse", correct = true), Seed("торт", "exercise_cake")), audio = "exercise_sound_sh")
        game(611, ActivityType.ODD_ONE, "Что лишнее: животные", "Найди, что здесь лишнее", null, 741, listOf(
            Seed("кот", "exercise_cat"), Seed("автобус", "exercise_bus", correct = true), Seed("собака", "exercise_dog"), Seed("корова", "exercise_cow")))
        game(612, ActivityType.ODD_ONE, "Что лишнее: фрукты", "Найди, что здесь лишнее", null, 745, listOf(
            Seed("яблоко", "exercise_apple"), Seed("виноград", "exercise_grapes"), Seed("морковь", "exercise_carrot", correct = true), Seed("банан", "exercise_banana")))
        game(613, ActivityType.ODD_ONE, "Что лишнее: транспорт", "Найди, что здесь лишнее", null, 749, listOf(
            Seed("медведь", "exercise_bear", correct = true), Seed("машина", "exercise_car"), Seed("самолёт", "exercise_plane"), Seed("лодка", "exercise_boat")))
        game(614, ActivityType.ODD_ONE, "Что лишнее: овощи", "Найди, что здесь лишнее", null, 753, listOf(
            Seed("морковь", "exercise_carrot"), Seed("помидор", "exercise_tomato"), Seed("лук", "exercise_onion"), Seed("мяч", "exercise_ball", correct = true)))
        game(615, ActivityType.MEMORY, "Найди пару: животные", "Открой две карточки и найди одинаковые", null, 757, listOf(
            Seed("корова", "exercise_cow"), Seed("лягушка", "exercise_frog"), Seed("медведь", "exercise_bear"), Seed("заяц", "exercise_rabbit"), Seed("сова", "exercise_owl"), Seed("лиса", "exercise_fox")))
        game(616, ActivityType.MEMORY, "Найди пару: вкусности", "Открой две карточки и найди одинаковые", null, 763, listOf(
            Seed("торт", "exercise_cake"), Seed("сыр", "exercise_cheese"), Seed("сок", "exercise_juice"), Seed("виноград", "exercise_grapes"), Seed("помидор", "exercise_tomato"), Seed("морковь", "exercise_carrot")))
        game(617, ActivityType.POP, "Лопни шарики со звуком Л", "Лопни шарики, где есть звук Л", null, 769, listOf(
            Seed("луна", "exercise_moon", correct = true), Seed("кот", "exercise_cat"), Seed("лук", "exercise_onion", correct = true), Seed("сыр", "exercise_cheese"), Seed("ключ", "exercise_key", correct = true), Seed("торт", "exercise_cake")))
        game(618, ActivityType.POP, "Лопни шарики со звуком С", "Лопни шарики, где есть звук С", null, 775, listOf(
            Seed("сова", "exercise_owl", correct = true), Seed("шапка", "exercise_hat"), Seed("сок", "exercise_juice", correct = true), Seed("дом", "exercise_house"), Seed("автобус", "exercise_bus", correct = true), Seed("утка", "exercise_duck")))
        game(619, ActivityType.COUNT, "Сколько яблок?", "Сколько яблок на тарелке?", GameExerciseConfig(imageAssetKey = "exercise_apple", countValue = 3), 781, listOf(
            Seed("1"), Seed("2"), Seed("3", correct = true), Seed("4"), Seed("5")))
        game(620, ActivityType.COUNT, "Сколько уточек?", "Сколько уточек плавает в пруду?", GameExerciseConfig(imageAssetKey = "exercise_duck", countValue = 5), 786, listOf(
            Seed("2"), Seed("3"), Seed("4"), Seed("5", correct = true), Seed("6")))
        game(621, ActivityType.COUNT, "Сколько звёздочек?", "Сколько звёздочек на небе?", GameExerciseConfig(imageAssetKey = "shape_star_yellow", countValue = 2), 791, listOf(
            Seed("1"), Seed("2", correct = true), Seed("3"), Seed("4"), Seed("5")))
        game(622, ActivityType.SHADOW, "Найди тень: животные", "Найди, чья это тень", null, 801, listOf(
            Seed("кот", "exercise_cat"), Seed("собака", "exercise_dog"), Seed("заяц", "exercise_rabbit"), Seed("медведь", "exercise_bear")))
        game(623, ActivityType.SHADOW, "Найди тень: транспорт", "Найди, чья это тень", null, 805, listOf(
            Seed("автобус", "exercise_bus"), Seed("самолёт", "exercise_plane"), Seed("лодка", "exercise_boat")))
        game(624, ActivityType.SORT, "Разложи по корзинам: С и Ш", "Где звук С — в оранжевую корзину, где звук Ш — в синюю", GameExerciseConfig(groupLabels = listOf("С", "Ш")), 811, listOf(
            Seed("сова", "exercise_owl", group = "A"), Seed("шапка", "exercise_hat", group = "B"), Seed("сок", "exercise_juice", group = "A"), Seed("мышь", "exercise_mouse", group = "B"), Seed("автобус", "exercise_bus", group = "A"), Seed("чашка", "exercise_cup", group = "B")))
        game(625, ActivityType.SORT, "Разложи по корзинам: фрукты и овощи", "Фрукты — в оранжевую корзину, овощи — в синюю", GameExerciseConfig(groupLabels = listOf("Фрукты", "Овощи")), 817, listOf(
            Seed("яблоко", "exercise_apple", group = "A"), Seed("морковь", "exercise_carrot", group = "B"), Seed("груша", "exercise_pear", group = "A"), Seed("помидор", "exercise_tomato", group = "B"), Seed("банан", "exercise_banana", group = "A"), Seed("лук", "exercise_onion", group = "B")))
        game(626, ActivityType.WORD_BUILD, "Собери слово СОК", "Собери слово из букв", GameExerciseConfig(imageAssetKey = "exercise_juice"), 831, listOf(
            Seed("С", sequence = 1), Seed("О", sequence = 2), Seed("К", sequence = 3), Seed("Ш"), Seed("М")))
        game(627, ActivityType.WORD_BUILD, "Собери слово ДОМ", "Собери слово из букв", GameExerciseConfig(imageAssetKey = "exercise_house"), 836, listOf(
            Seed("Д", sequence = 1), Seed("О", sequence = 2), Seed("М", sequence = 3), Seed("Т"), Seed("Л")))
        game(628, ActivityType.WORD_BUILD, "Собери слово ЛУК", "Собери слово из букв", GameExerciseConfig(imageAssetKey = "exercise_onion"), 841, listOf(
            Seed("Л", sequence = 1), Seed("У", sequence = 2), Seed("К", sequence = 3), Seed("Р"), Seed("А")))
        game(629, ActivityType.WORD_BUILD, "Собери слово СЫР", "Собери слово из букв", GameExerciseConfig(imageAssetKey = "exercise_cheese"), 846, listOf(
            Seed("С", sequence = 1), Seed("Ы", sequence = 2), Seed("Р", sequence = 3), Seed("Л"), Seed("О")))
        game(630, ActivityType.SYLLABLES, "Слоги: барабан", "Постучи по барабану на каждый слог", GameExerciseConfig(syllables = listOf("БА", "РА", "БАН"), imageAssetKey = "exercise_drum"), 0, emptyList())
        game(631, ActivityType.SYLLABLES, "Слоги: машина", "Постучи по барабану на каждый слог", GameExerciseConfig(syllables = listOf("МА", "ШИ", "НА"), imageAssetKey = "exercise_car"), 0, emptyList())
        game(632, ActivityType.SYLLABLES, "Слоги: собака", "Постучи по барабану на каждый слог", GameExerciseConfig(syllables = listOf("СО", "БА", "КА"), imageAssetKey = "exercise_dog"), 0, emptyList())
        game(633, ActivityType.SYLLABLES, "Слоги: луна", "Постучи по барабану на каждый слог", GameExerciseConfig(syllables = listOf("ЛУ", "НА"), imageAssetKey = "exercise_moon"), 0, emptyList())
        game(634, ActivityType.REMEMBER, "Запомни и найди: круг", "Запомни картинку и найди её", GameExerciseConfig(showTargetMs = 3000), 861, listOf(
            Seed("синий круг", "shape_circle_blue"), Seed("красный квадрат", "shape_square_red"), Seed("красный круг", "shape_circle_red", correct = true), Seed("жёлтый круг", "shape_circle_yellow"), Seed("красный треугольник", "shape_triangle_red"), Seed("зелёный круг", "shape_circle_green")))
        game(635, ActivityType.REMEMBER, "Запомни и найди: треугольник", "Запомни картинку и найди её", GameExerciseConfig(showTargetMs = 3000), 867, listOf(
            Seed("синий треугольник", "shape_triangle_blue"), Seed("фиолетовый квадрат", "shape_square_purple"), Seed("фиолетовая звезда", "shape_star_purple"), Seed("фиолетовый треугольник", "shape_triangle_purple", correct = true), Seed("красный треугольник", "shape_triangle_red")))
        game(636, ActivityType.MAZE, "Пчёлка по зигзагу", "Проведи пчёлку по зигзагу к цветку", GameExerciseConfig(mazeLayout = "zigzag"), 0, emptyList())
        game(637, ActivityType.MAZE, "Пчёлка по спирали", "Проведи пчёлку по спирали к цветку", GameExerciseConfig(mazeLayout = "spiral"), 0, emptyList())
        board(638, "Обведи солнышко", "Обведи солнышко по точкам", "sun", WhiteboardColor.ORANGE)
        board(639, "Обведи домик", "Обведи домик по точкам", "house", WhiteboardColor.RED)
        board(640, "Обведи рыбку", "Обведи рыбку по точкам", "fish", WhiteboardColor.BLUE)
        board(641, "Обведи звезду", "Обведи звезду по точкам", "star", WhiteboardColor.ORANGE)
        board(642, "Нарисуй цветок", "Нарисуй цветок: сначала обведи по точкам", "flower", WhiteboardColor.PURPLE)
        board(643, "Нарисуй сердечко", "Нарисуй сердечко: сначала обведи по точкам", "heart", WhiteboardColor.RED)
        board(644, "Рисуем что хочешь", "Нарисуй всё, что хочешь", null, WhiteboardColor.BLACK)
        template(901, "Звуки Р и Л", "Найди звук, шарики и корзины со звуками Р и Л", 911, listOf(601, 604, 602, 605, 617, 307, 304))
        template(902, "Звуки С и Ш", "Найди звук, шарики и корзины со звуками С и Ш", 921, listOf(607, 609, 608, 610, 618, 624, 104))
        template(903, "Мышление и внимание", "Лишнее, пары, тени и запоминание", 931, listOf(611, 612, 615, 622, 634, 613, 302))
        template(904, "Моторика и рисование", "Лабиринты и рисование по точкам", 941, listOf(636, 638, 640, 637, 641, 643, 644))
        template(905, "Счёт и слоги", "Считаем предметы и стучим слоги", 951, listOf(619, 630, 620, 631, 621, 633, 310))
    }
    private fun legacyInstruction(key: String) = when {
        key.startsWith("sound-r") -> "Найди картинку, в названии которой есть звук «Р»"
        key.startsWith("sound-l") -> "Найди картинку, в названии которой есть звук «Л»"
        // "sound-sh" also starts with "sound-s"; V8 seeds «Ш» for it.
        key.startsWith("sound-s") && !key.startsWith("sound-sh") -> "Найди картинку, в названии которой есть звук «С»"
        else -> "Найди картинку, в названии которой есть звук «Ш»"
    }
}
