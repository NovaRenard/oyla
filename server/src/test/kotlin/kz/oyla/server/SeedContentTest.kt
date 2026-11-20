package kz.oyla.server

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.CenterMembershipRecord
import kz.oyla.server.model.CenterRecord
import kz.oyla.server.model.CenterStatus
import kz.oyla.server.model.ContentExerciseRecord
import kz.oyla.server.model.ContentOwnership
import kz.oyla.server.model.ContentStatus
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.ExerciseSnapshot
import kz.oyla.server.model.ExerciseStatus
import kz.oyla.server.model.MembershipRole
import kz.oyla.server.model.MembershipStatus
import kz.oyla.server.model.SessionStatus
import kz.oyla.server.model.UserRecord
import kz.oyla.server.model.UserStatus
import kz.oyla.server.model.WhiteboardColor
import kz.oyla.server.model.WhiteboardExerciseConfig
import kz.oyla.server.model.dto.CreateExerciseRequest
import kz.oyla.server.model.dto.UpdateExerciseRequest
import kz.oyla.server.model.dto.WhiteboardExerciseConfigDto
import kz.oyla.server.repository.ContentExerciseFilter
import kz.oyla.server.repository.InMemoryContentRepository
import kz.oyla.server.repository.InMemoryExerciseRepository
import kz.oyla.server.repository.InMemorySaasRepository
import kz.oyla.server.repository.LessonTemplateFilter
import kz.oyla.server.repository.SessionExerciseRecord
import kz.oyla.server.repository.SessionRecord
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.AuthorizedSession
import kz.oyla.server.service.CenterContext
import kz.oyla.server.service.ContentService
import kz.oyla.server.service.ExerciseService
import kz.oyla.server.service.GameGenerator
import kz.oyla.server.service.PicturePool
import kotlin.random.Random
import kz.oyla.server.storage.LocalMediaStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SYSTEM seed content (V8, V11, V12, V13 mirrored in memory) and whiteboard trace templates. */
class SeedContentTest {
    private val repository = InMemoryContentRepository()
    private val service = ContentService(repository, InMemorySaasRepository(), LocalMediaStorage("build/test-media-seeds"))
    private val center: UUID = UUID.randomUUID()

    // ---------- seeds ----------

    @Test fun `every SYSTEM exercise passes the type-specific validation rules`() = runBlocking {
        val system = systemExercises()
        assertEquals(94, system.size) // V8 5 + V11 6 + V12 4 + V13 44 + V14 35
        system.forEach { exercise ->
            val error = runCatching { service.validateDefinition(exercise.activityType, exercise.options, exercise.whiteboardConfig, exercise.gameConfig) }.exceptionOrNull()
            assertNull("${exercise.id} «${exercise.title}» is invalid: ${(error as? ApiException)?.clientMessage}", error)
            assertEquals(ContentStatus.ACTIVE, exercise.status)
            assertEquals(exercise.options.indices.map { it + 1 }, exercise.options.map { it.sortOrder })
        }
    }

    @Test fun `V13 seeds the documented number of exercises per type`() = runBlocking {
        val v13 = systemExercises().filter { it.id.toString().takeLast(3).toInt() in 601..699 }
        val expected = mapOf(
            ActivityType.SINGLE_CHOICE to 10, ActivityType.ODD_ONE to 4, ActivityType.MEMORY to 2, ActivityType.POP to 2, ActivityType.COUNT to 3,
            ActivityType.SHADOW to 2, ActivityType.SORT to 2, ActivityType.WORD_BUILD to 4, ActivityType.SYLLABLES to 4, ActivityType.REMEMBER to 2,
            ActivityType.MAZE to 2, ActivityType.WHITEBOARD to 7
        )
        assertEquals(expected, v13.groupingBy { it.activityType }.eachCount())
        assertEquals((601..644).map { id(it) }, v13.map { it.id }.sorted())
        val sounds = v13.filter { it.activityType == ActivityType.SINGLE_CHOICE }.groupingBy { it.localAudioAssetKey }.eachCount()
        listOf("exercise_sound_r", "exercise_sound_l", "exercise_sound_s", "exercise_sound_sh").forEach { assertTrue("$it needs 2+ exercises", (sounds[it] ?: 0) >= 2) }
        assertEquals(setOf("zigzag", "spiral"), v13.filter { it.activityType == ActivityType.MAZE }.mapNotNull { it.gameConfig?.mazeLayout }.toSet())
    }

    @Test fun `SYSTEM content only uses picture keys shipped inside the APK`() = runBlocking {
        val keys = systemExercises().flatMap { exercise -> exercise.options.mapNotNull { it.localImageAssetKey } + listOfNotNull(exercise.gameConfig?.imageAssetKey) }
        assertTrue(keys.isNotEmpty())
        keys.forEach { assertTrue("$it is not shipped in the APK", it in ApkPictureKeys) }
        val audio = systemExercises().mapNotNull { it.localAudioAssetKey }.toSet()
        assertEquals(setOf("exercise_sound_r", "exercise_sound_l", "exercise_sound_s", "exercise_sound_sh"), audio)
    }

    @Test fun `sound exercises are phonetically correct`() = runBlocking {
        val exercises = systemExercises()
        // «Найди звук»: the answer contains the sound, every distractor does not.
        exercises.filter { it.activityType == ActivityType.SINGLE_CHOICE && it.localAudioAssetKey != null }.forEach { exercise ->
            val sound = SoundByAudio.getValue(exercise.localAudioAssetKey!!)
            assertTrue("${exercise.title}: ${exercise.instructionText}", exercise.instructionText.contains("«${sound.uppercase()}»"))
            exercise.options.forEach { option ->
                assertEquals("${exercise.title}: «${option.label}»", option.isCorrect, option.label!!.lowercase().contains(sound))
            }
        }
        // POP «со звуком X»: targets contain the sound, the other balloons do not.
        val pops = exercises.filter { it.activityType == ActivityType.POP }
        assertTrue("pops: ${pops.map { it.title }}", pops.size >= 3)
        pops.forEach { exercise ->
            val sound = exercise.title.substringAfter("со звуком ").trim().lowercase()
            assertEquals(1, sound.length)
            assertEquals(3, exercise.options.count { it.isCorrect })
            exercise.options.forEach { option -> assertEquals("${exercise.title}: «${option.label}»", option.isCorrect, option.label!!.lowercase().contains(sound)) }
        }
        // SORT by sounds: group A has only the first sound, group B only the second.
        val soundSorts = exercises.filter { it.activityType == ActivityType.SORT && it.gameConfig!!.groupLabels.all { label -> label.length == 1 } }
        assertEquals(2, soundSorts.size)
        soundSorts.forEach { exercise ->
            val (a, b) = exercise.gameConfig!!.groupLabels.map { it.lowercase() }
            exercise.options.forEach { option ->
                val word = option.label!!.lowercase()
                val (own, other) = if (option.groupKey == "A") a to b else b to a
                assertTrue("${exercise.title}: «$word» must contain $own", word.contains(own))
                assertTrue("${exercise.title}: «$word» must not contain $other", !word.contains(other))
            }
        }
    }

    @Test fun `V13 game definitions carry the expected settings`() = runBlocking {
        suspend fun exercise(n: Int) = checkNotNull(repository.findExercise(id(n)))
        // V14: SYLLABLES keep their picture as the generator constraint and take the syllables from the picture pool.
        mapOf(630 to listOf("БА", "РА", "БАН"), 631 to listOf("МА", "ШИ", "НА"), 632 to listOf("СО", "БА", "КА"), 633 to listOf("ЛУ", "НА")).forEach { (n, syllables) ->
            val config = checkNotNull(exercise(n).gameConfig)
            assertTrue(config.generate); assertTrue(config.syllables.isEmpty())
            assertEquals(syllables, kz.oyla.server.service.PicturePool.byKey(config.imageAssetKey)?.syllables)
        }
        assertEquals("exercise_drum", exercise(630).gameConfig?.imageAssetKey)
        mapOf(626 to "СОК", 627 to "ДОМ", 628 to "ЛУК", 629 to "СЫР").forEach { (n, word) ->
            val options = exercise(n).options
            assertEquals(word, options.filter { it.sequenceIndex != null }.sortedBy { it.sequenceIndex }.joinToString("") { it.label!! })
            assertEquals(2, options.count { it.sequenceIndex == null })
        }
        // V14: COUNT keeps the picture, the number is generated.
        mapOf(619 to 3, 620 to 5, 621 to 2).forEach { (n, count) ->
            assertNull(exercise(n).gameConfig?.countValue); assertTrue(exercise(n).gameConfig!!.generate)
            assertEquals(count.toString(), exercise(n).options.single { it.isCorrect }.label)
        }
        assertEquals(listOf("Фрукты", "Овощи"), exercise(625).gameConfig?.groupLabels)
        assertEquals(6, exercise(615).options.size); assertEquals(6, exercise(616).options.size)
        assertTrue(exercise(634).options.size in 5..6 && exercise(635).options.size in 5..6)
    }

    @Test fun `SYSTEM templates reference existing ACTIVE exercises and snapshot`() = runBlocking {
        val templates = repository.listTemplates(center, LessonTemplateFilter()).filter { it.ownership == ContentOwnership.SYSTEM }
        assertTrue(templates.size >= 9)
        templates.forEach { template ->
            assertEquals(ContentStatus.ACTIVE, template.status)
            assertEquals(template.items.indices.map { it + 1 }, template.items.map { it.position })
            template.items.forEach { item ->
                val exercise = repository.findExerciseAccessible(center, item.exerciseId)
                assertNotNull("${template.name} references a missing exercise ${item.exerciseId}", exercise)
                assertEquals(ContentStatus.ACTIVE, exercise!!.status)
            }
            assertEquals(template.items.size, service.snapshotTemplate(center, template.id).exercises.size)
        }
        val v13 = mapOf(901 to "Звуки Р и Л", 902 to "Звуки С и Ш", 903 to "Мышление и внимание", 904 to "Моторика и рисование", 905 to "Счёт и слоги")
        v13.forEach { (n, name) ->
            val template = checkNotNull(repository.findTemplate(id(n)))
            assertEquals(name, template.name)
            assertTrue("$name has ${template.items.size} items", template.items.size in 5..7)
        }
        val motor = service.snapshotTemplate(center, id(904)).exercises.map { it.activityType }.toSet()
        assertEquals(setOf(ActivityType.MAZE, ActivityType.WHITEBOARD), motor)
    }

    // ---------- V14 generated games ----------

    @Test fun `V14 seeds the new generated games and templates`() = runBlocking {
        val v14 = systemExercises().filter { it.id.toString().takeLast(3).toInt() in 701..799 }
        val expected = mapOf(
            ActivityType.MATH to 6, ActivityType.PAIRS to 2, ActivityType.PARTS to 2, ActivityType.TYPE_WORD to 3, ActivityType.PHONETICS to 3, ActivityType.LABEL_SCENE to 3,
            ActivityType.BASKET to 4, ActivityType.CATEGORY_HOUSE to 3, ActivityType.MISSING to 3, ActivityType.PARKING to 3, ActivityType.ARROWS to 3
        )
        assertEquals(expected, v14.groupingBy { it.activityType }.eachCount())
        v14.forEach { exercise ->
            val config = checkNotNull(exercise.gameConfig)
            assertTrue(exercise.title, config.generate); assertTrue(exercise.title, config.level in 1..3); assertTrue(exercise.options.isEmpty())
            assertTrue(exercise.instructionText.length <= 80)
        }
        mapOf(701 to ("+" to 1), 702 to ("+" to 2), 703 to ("-" to 2), 704 to ("±" to 3), 705 to ("×" to 2)).forEach { (n, value) ->
            val config = checkNotNull(repository.findExercise(id(n))?.gameConfig)
            assertEquals(value, config.mathOp to config.level)
        }
        assertEquals("Сложение до 5", repository.findExercise(id(701))?.title)
        assertEquals(listOf(1, 2, 3), (791..793).map { repository.findExercise(id(it))?.gameConfig?.level })
        // Every existing SYSTEM game of a generator-backed type now generates its variant.
        val generatedOld = systemExercises().filter { it.activityType in setOf(ActivityType.ODD_ONE, ActivityType.COUNT, ActivityType.MEMORY, ActivityType.SHADOW,
            ActivityType.REMEMBER, ActivityType.SYLLABLES, ActivityType.WORD_BUILD, ActivityType.POP, ActivityType.SORT) }
        assertEquals(34, generatedOld.size)
        assertTrue(generatedOld.all { it.gameConfig?.generate == true })
        assertEquals("animal", repository.findExercise(id(611))?.gameConfig?.category)
        assertEquals("exercise_fish", repository.findExercise(id(310))?.gameConfig?.imageAssetKey)
        assertEquals("Р", repository.findExercise(id(307))?.gameConfig?.sound)
        assertEquals(listOf("Фрукты", "Овощи"), repository.findExercise(id(625))?.gameConfig?.groupLabels)
        assertEquals("shape", repository.findExercise(id(302))?.gameConfig?.category)
        // Templates 906..909 mix new and existing generated games.
        val names = mapOf(906 to "Математика", 907 to "Буквы и слова", 908 to "Внимание и логика", 909 to "Перетаскивание")
        names.forEach { (n, name) ->
            val template = checkNotNull(repository.findTemplate(id(n)))
            assertEquals(name, template.name); assertTrue(template.items.size in 5..7)
            val types = template.items.map { checkNotNull(repository.findExercise(it.exerciseId)).activityType }
            assertTrue(name, types.any { it.name in NewTypeNames } && types.any { it.name !in NewTypeNames })
        }
    }

    @Test fun `every generated SYSTEM exercise generates valid variants with APK pictures only`() = runBlocking {
        val generated = systemExercises().filter { it.gameConfig?.generate == true }
        assertEquals(69, generated.size)
        generated.forEach { exercise ->
            repeat(25) { seed ->
                val variant = GameGenerator.generate(exercise.activityType, exercise.gameConfig!!, Random(seed))
                val keys = variant.options.mapNotNull { it.localImageAssetKey } + listOfNotNull(variant.gameConfig.imageAssetKey) + variant.gameConfig.requirements.map { it.imageAssetKey }
                keys.forEach { assertTrue("${exercise.title}: $it is not shipped in the APK", it in ApkPictureKeys || it == GameGenerator.CandyKey) }
                if (exercise.activityType == ActivityType.COUNT || exercise.activityType == ActivityType.ODD_ONE || exercise.activityType == ActivityType.REMEMBER || exercise.activityType.name in NewTypeNames.take(3))
                    assertEquals(exercise.title, 1, variant.options.count { it.id == variant.correctOptionId })
            }
        }
        PicturePool.Pictures.forEach { assertTrue(it.key, it.key in ApkPictureKeys) }
        assertEquals(48, PicturePool.Pictures.size); assertEquals(PicturePool.Pictures.size, PicturePool.Pictures.map { it.key }.distinct().size)
        PicturePool.Pictures.forEach { assertEquals(it.word, it.upperWord, it.syllables.joinToString("")) }
    }

    // ---------- whiteboard trace templates ----------

    @Test fun `seeded whiteboards carry trace templates through snapshot and session DTO`() = runBlocking {
        val expected = mapOf(638 to "sun", 639 to "house", 640 to "fish", 641 to "star", 642 to "flower", 643 to "heart", 644 to null)
        expected.forEach { (n, template) ->
            val config = checkNotNull(repository.findExercise(id(n))?.whiteboardConfig)
            assertEquals(template, config.traceTemplate)
            assertEquals(WhiteboardColor.entries.toList(), config.availableColors)
            assertTrue(config.childDrawingInitiallyEnabled && config.allowEraser)
        }
        assertEquals(WhiteboardColor.ORANGE, repository.findExercise(id(638))?.whiteboardConfig?.defaultColor)
        assertEquals("sun", service.exercise(context(), id(638)).whiteboardConfig?.traceTemplate)

        val snapshot = service.snapshotTemplate(center, id(904)).exercises.first { it.sourceExerciseId == id(638).toString() }
        assertEquals("sun", snapshot.whiteboardConfig?.traceTemplate)
        val exercises = InMemoryExerciseRepository(); val sessionId = UUID.randomUUID()
        val exerciseService = ExerciseService(exercises)
        exerciseService.ensureSnapshotPlan(listOf(SessionExerciseRecord(UUID.randomUUID(), sessionId, snapshot.sourceExerciseId, ExerciseStatus.PENDING, null, null, null, Instant.EPOCH, 1, true, snapshot)))
        val session = SessionRecord(sessionId, "1234", "Ребёнок", SessionStatus.READY, "specialist", "token", "child", "child-token", Instant.EPOCH, Instant.MAX, Instant.EPOCH, null, isManaged = true)
        assertEquals("sun", exerciseService.stateFor(AuthorizedSession(session, DeviceRole.SPECIALIST)).exercise?.whiteboardConfig?.traceTemplate)
    }

    @Test fun `trace template round-trips through create and update and is normalised`() = runBlocking {
        val created = service.createExercise(context(), board("Star"), null)
        assertEquals("star", created.whiteboardConfig?.traceTemplate)
        assertEquals("star", repository.findExercise(UUID.fromString(created.id))?.whiteboardConfig?.traceTemplate)

        val updated = service.updateExercise(context(), UUID.fromString(created.id), UpdateExerciseRequest(whiteboardConfig = config("heart")), null)
        assertEquals("heart", updated.whiteboardConfig?.traceTemplate)
        val cleared = service.updateExercise(context(), UUID.fromString(created.id), UpdateExerciseRequest(whiteboardConfig = config(null)), null)
        assertNull(cleared.whiteboardConfig?.traceTemplate)
        val blank = service.createExercise(context(), board("  "), null)
        assertNull(blank.whiteboardConfig?.traceTemplate)
    }

    @Test fun `unknown trace template is rejected`() = runBlocking {
        listOf("dragon", "circle", "sun2").forEach { template ->
            val error = runCatching { service.createExercise(context(), board(template), null) }.exceptionOrNull()
            assertTrue("$template must be rejected", error is ApiException)
            assertEquals("VALIDATION_ERROR", (error as ApiException).errorCode)
            assertEquals("Неизвестный шаблон доски", error.clientMessage)
        }
        val created = service.createExercise(context(), board(null), null)
        val error = runCatching { service.updateExercise(context(), UUID.fromString(created.id), UpdateExerciseRequest(whiteboardConfig = config("dragon")), null) }.exceptionOrNull()
        assertEquals("Неизвестный шаблон доски", (error as ApiException).clientMessage)
    }

    @Test fun `trace template JSON contract is backward compatible`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals("fish", json.decodeFromString<WhiteboardExerciseConfigDto>("""{"availableColors":["BLACK"],"traceTemplate":"fish"}""").traceTemplate)
        assertNull(json.decodeFromString<WhiteboardExerciseConfigDto>("""{"availableColors":["BLACK"]}""").traceTemplate)
        assertTrue(Json.encodeToString(WhiteboardExerciseConfigDto(traceTemplate = "house")).contains("\"traceTemplate\":\"house\""))
        // Snapshots stored before V13 have no traceTemplate and still decode.
        val old = json.decodeFromString<ExerciseSnapshot>("""{"sourceExerciseId":"x","activityType":"WHITEBOARD","title":"Доска","instructionText":"Нарисуй","whiteboardConfig":{"defaultColor":"BLUE"}}""")
        assertNull(old.whiteboardConfig?.traceTemplate)
        // Positional construction (without the new last field) still compiles and defaults to null.
        assertNull(WhiteboardExerciseConfig(null, null, true, WhiteboardColor.entries.toList(), WhiteboardColor.BLACK).traceTemplate)
    }

    // ---------- helpers ----------

    private suspend fun systemExercises(): List<ContentExerciseRecord> =
        repository.listExercises(center, ContentExerciseFilter()).filter { it.ownership == ContentOwnership.SYSTEM }

    private fun id(suffix: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(suffix))
    private fun config(template: String?) = WhiteboardExerciseConfigDto(availableColors = WhiteboardColor.entries.toList(), defaultColor = WhiteboardColor.ORANGE, traceTemplate = template)
    private fun board(template: String?) = CreateExerciseRequest("Обведи", "Обведи по точкам", ActivityType.WHITEBOARD, whiteboardConfig = config(template))
    private fun context(): CenterContext {
        val now = Instant.EPOCH; val userId = UUID.randomUUID()
        return CenterContext(
            UserRecord(userId, "owner@example.com", "hash", "Owner", null, UserStatus.ACTIVE, now, now, null),
            CenterRecord(center, "Центр", "center-$center", CenterStatus.ACTIVE, "Asia/Almaty", now, now),
            CenterMembershipRecord(UUID.randomUUID(), center, userId, MembershipRole.OWNER, MembershipStatus.ACTIVE, now, now)
        )
    }

    private companion object {
        val NewTypeNames = listOf("MATH", "PAIRS", "PARTS", "TYPE_WORD", "PHONETICS", "LABEL_SCENE", "BASKET", "CATEGORY_HOUSE", "MISSING", "PARKING", "ARROWS")
        val SoundByAudio = mapOf("exercise_sound_r" to "р", "exercise_sound_l" to "л", "exercise_sound_s" to "с", "exercise_sound_sh" to "ш")
        val ApkPictureKeys = listOf(
            "fish", "apple", "ball", "duck", "lamp", "dog", "elephant", "pear", "banana", "car", "boat", "cat", "fox", "house", "rocket", "sun", "moon", "tree",
            "flower", "rose", "bus", "plane", "cow", "frog", "bear", "rabbit", "mouse", "owl", "crayfish", "carrot", "tomato", "grapes", "cheese", "onion", "cake",
            "drum", "spoon", "cup", "juice", "hat", "umbrella", "key"
        ).map { "exercise_$it" }.toSet() + kz.oyla.server.service.PicturePool.NewKeys + listOf("star", "circle", "square", "triangle").flatMap { shape ->
            listOf("blue", "red", "green", "yellow", "purple").map { color -> "shape_${shape}_$color" }
        }
    }
}
