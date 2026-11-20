package kz.oyla.server

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.CenterMembershipRecord
import kz.oyla.server.model.CenterRecord
import kz.oyla.server.model.CenterStatus
import kz.oyla.server.model.ContentOwnership
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.ExerciseSnapshot
import kz.oyla.server.model.ExerciseStatus
import kz.oyla.server.model.MediaAssetRecord
import kz.oyla.server.model.MediaType
import kz.oyla.server.model.MembershipRole
import kz.oyla.server.model.MembershipStatus
import kz.oyla.server.model.SessionStatus
import kz.oyla.server.model.UserRecord
import kz.oyla.server.model.UserStatus
import kz.oyla.server.model.dto.AnswerExerciseRequest
import kz.oyla.server.model.dto.AnswerExerciseResponse
import kz.oyla.server.model.dto.CreateExerciseRequest
import kz.oyla.server.model.dto.CreateLessonTemplateRequest
import kz.oyla.server.model.dto.ExerciseOptionInput
import kz.oyla.server.model.dto.GameExerciseConfigDto
import kz.oyla.server.model.dto.LessonTemplateItemInput
import kz.oyla.server.model.dto.NextExerciseRequest
import kz.oyla.server.model.dto.ShowExerciseRequest
import kz.oyla.server.model.dto.StartExerciseRequest
import kz.oyla.server.model.dto.UpdateExerciseRequest
import kz.oyla.server.repository.InMemoryContentRepository
import kz.oyla.server.repository.InMemoryExerciseRepository
import kz.oyla.server.repository.InMemorySaasRepository
import kz.oyla.server.repository.SessionExerciseRecord
import kz.oyla.server.repository.SessionRecord
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.AuthorizedSession
import kz.oyla.server.service.CenterContext
import kz.oyla.server.service.ContentService
import kz.oyla.server.service.ExerciseService
import kz.oyla.server.storage.LocalMediaStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameActivitiesTest {
    private val gamesTemplate = "00000000-0000-0000-0000-000000000501"
    private val soundsAndGamesTemplate = "00000000-0000-0000-0000-000000000502"
    private val funGamesTemplate = "00000000-0000-0000-0000-000000000503"

    // ---------- seed ----------

    @Test fun `system game templates are seeded in the documented order`() = runBlocking {
        val fixture = Fixture()
        val games = fixture.content.snapshotTemplate(fixture.center, UUID.fromString(gamesTemplate))
        assertEquals("Игры Ойлика", games.name)
        assertEquals(listOf(ActivityType.MEMORY, ActivityType.REMEMBER, ActivityType.ODD_ONE, ActivityType.SORT, ActivityType.MAZE, ActivityType.WORD_BUILD), games.exercises.map { it.activityType })
        val mixed = fixture.content.snapshotTemplate(fixture.center, UUID.fromString(soundsAndGamesTemplate))
        assertEquals(listOf(ActivityType.SINGLE_CHOICE, ActivityType.SORT, ActivityType.MEMORY, ActivityType.ODD_ONE, ActivityType.WORD_BUILD), mixed.exercises.map { it.activityType })
        assertEquals("00000000-0000-0000-0000-000000000101", mixed.exercises.first().sourceExerciseId)

        val base = fixture.content.snapshotTemplate(fixture.center, UUID.fromString("00000000-0000-0000-0000-000000000201"))
        assertEquals(5, base.exercises.size)
        assertTrue(base.exercises.all { it.activityType == ActivityType.SINGLE_CHOICE })

        val remember = games.exercises[1]
        assertEquals("00000000-0000-0000-0000-000000000411", remember.correctOptionId)
        assertNull(games.exercises[0].correctOptionId) // MEMORY
        assertNull(games.exercises[3].correctOptionId) // SORT
        assertEquals(listOf("Р", "Л"), games.exercises[3].gameConfig?.groupLabels)
        assertEquals(listOf(1, 2, 3, null, null), games.exercises[5].options.map { it.sequenceIndex })
        assertEquals("wave", games.exercises[4].gameConfig?.mazeLayout)
    }

    // ---------- validation ----------

    @Test fun `game definitions are validated per type`() = runBlocking {
        val fixture = Fixture()
        fun opt(label: String, correct: Boolean = false, group: String? = null, sequence: Int? = null) =
            ExerciseOptionInput(label = label, isCorrect = correct, groupKey = group, sequenceIndex = sequence)

        // ODD_ONE
        assertCreated(fixture, ActivityType.ODD_ONE, listOf(opt("а"), opt("б"), opt("в", true)))
        assertRejected(fixture, ActivityType.ODD_ONE, listOf(opt("а"), opt("б", true)))
        assertRejected(fixture, ActivityType.ODD_ONE, listOf(opt("а"), opt("б"), opt("в")))
        // REMEMBER
        assertCreated(fixture, ActivityType.REMEMBER, listOf(opt("а", true), opt("б"), opt("в")), GameExerciseConfigDto(showTargetMs = 2000))
        assertRejected(fixture, ActivityType.REMEMBER, listOf(opt("а", true), opt("б"), opt("в")), GameExerciseConfigDto(showTargetMs = 500))
        assertRejected(fixture, ActivityType.REMEMBER, (1..10).map { opt("$it", it == 1) })
        // MEMORY stores isCorrect = false
        val memory = assertCreated(fixture, ActivityType.MEMORY, listOf(opt("а", true), opt("б")))
        assertTrue(memory.options.none { it.isCorrect })
        assertRejected(fixture, ActivityType.MEMORY, listOf(opt("а")))
        // SORT
        val sortConfig = GameExerciseConfigDto(groupLabels = listOf("Р", "Л"))
        val sort = assertCreated(fixture, ActivityType.SORT, listOf(opt("ракета", group = "A"), opt("лампа", group = "B")), sortConfig)
        assertEquals(listOf("A", "B"), sort.options.map { it.groupKey })
        assertEquals(listOf("Р", "Л"), sort.gameConfig?.groupLabels)
        assertRejected(fixture, ActivityType.SORT, listOf(opt("ракета", group = "A"), opt("рыба", group = "A")), sortConfig)
        assertRejected(fixture, ActivityType.SORT, listOf(opt("ракета", group = "A"), opt("лампа", group = "C")), sortConfig)
        assertRejected(fixture, ActivityType.SORT, listOf(opt("ракета", group = "A"), opt("лампа", group = "B")), GameExerciseConfigDto(groupLabels = listOf("Р")))
        assertRejected(fixture, ActivityType.SORT, listOf(opt("ракета", group = "A"), opt("лампа", group = "B")), GameExerciseConfigDto(groupLabels = listOf("Р", "очень длинная подпись группы")))
        // WORD_BUILD
        val word = GameExerciseConfigDto(imageAssetKey = "exercise_cat")
        assertCreated(fixture, ActivityType.WORD_BUILD, listOf(opt("К", sequence = 1), opt("О", sequence = 2), opt("Т", sequence = 3), opt("А")), word)
        assertRejected(fixture, ActivityType.WORD_BUILD, listOf(opt("К", sequence = 1), opt("О", sequence = 3)), word)
        assertRejected(fixture, ActivityType.WORD_BUILD, listOf(opt("К", sequence = 1), opt("О", sequence = 1)), word)
        assertRejected(fixture, ActivityType.WORD_BUILD, listOf(opt("К", sequence = 1), opt("А")), word)
        assertRejected(fixture, ActivityType.WORD_BUILD, listOf(opt("КОТ", sequence = 1), opt("О", sequence = 2)), word)
        assertRejected(fixture, ActivityType.WORD_BUILD, listOf(opt("К", sequence = 1), opt("О", sequence = 2)), GameExerciseConfigDto())
        assertRejected(fixture, ActivityType.WORD_BUILD, listOf(opt("К", sequence = 1), opt("О", sequence = 2)), GameExerciseConfigDto(imageAssetId = UUID.randomUUID().toString()))
        val image = UUID.randomUUID()
        fixture.repository.insertMedia(MediaAssetRecord(image, fixture.center, ContentOwnership.CENTER, MediaType.IMAGE, "cat.png", "cat.png", "image/png", 1, Instant.EPOCH))
        val uploaded = assertCreated(fixture, ActivityType.WORD_BUILD, listOf(opt("К", sequence = 1), opt("О", sequence = 2)), GameExerciseConfigDto(imageAssetId = image.toString()))
        assertEquals("/api/v1/media/$image", uploaded.gameConfig?.imageUrl)
        // MAZE
        assertCreated(fixture, ActivityType.MAZE, emptyList(), GameExerciseConfigDto(mazeLayout = "spiral"))
        assertRejected(fixture, ActivityType.MAZE, emptyList(), GameExerciseConfigDto(mazeLayout = "circle"))
        assertRejected(fixture, ActivityType.MAZE, listOf(opt("а")), GameExerciseConfigDto(mazeLayout = "wave"))
        // SINGLE_CHOICE keeps its rules
        assertRejected(fixture, ActivityType.SINGLE_CHOICE, listOf(opt("а", true), opt("б", true)))
    }

    @Test fun `game exercise update keeps config and revalidates it`() = runBlocking {
        val fixture = Fixture()
        val created = fixture.content.createExercise(fixture.context, CreateExerciseRequest("Лабиринт", "Проведи", ActivityType.MAZE, gameConfig = GameExerciseConfigDto(mazeLayout = "wave")), null)
        val renamed = fixture.content.updateExercise(fixture.context, UUID.fromString(created.id), UpdateExerciseRequest(title = "Лабиринт 2"), null)
        assertEquals("wave", renamed.gameConfig?.mazeLayout)
        val changed = fixture.content.updateExercise(fixture.context, UUID.fromString(created.id), UpdateExerciseRequest(gameConfig = GameExerciseConfigDto(mazeLayout = "zigzag", targetOptionId = "x")), null)
        assertEquals("zigzag", changed.gameConfig?.mazeLayout)
        assertNull(changed.gameConfig?.targetOptionId)
        val error = runCatching { fixture.content.updateExercise(fixture.context, UUID.fromString(created.id), UpdateExerciseRequest(gameConfig = GameExerciseConfigDto(mazeLayout = "bad")), null) }.exceptionOrNull()
        assertEquals("VALIDATION_ERROR", (error as ApiException).errorCode)
    }

    // ---------- answering ----------

    @Test fun `MEMORY mismatch, match, already matched and completion`() = runBlocking {
        val fixture = Fixture(); fixture.loadSingle(gamesTemplate, 0)
        val options = fixture.optionIds()
        fixture.startCurrent()

        val mismatch = fixture.answer(options[0], options[1])
        assertFalse(mismatch.isCorrect); assertEquals(0, mismatch.progressDone); assertEquals(6, mismatch.progressTotal)
        assertEquals(options[1], mismatch.secondaryValue); assertEquals("рыба", mismatch.selectedOptionLabel)

        val match = fixture.answer(options[0], options[0])
        assertTrue(match.isCorrect); assertEquals(1, match.progressDone); assertEquals(ExerciseStatus.RUNNING, match.exerciseStatus)

        val again = fixture.answer(options[0], options[0])
        assertFalse(again.isCorrect); assertEquals(1, again.progressDone)

        assertApiError(400) { fixture.answer(options[0], "foreign") }
        assertApiError(400) { fixture.answer(options[0], null) }

        val state = fixture.state(DeviceRole.CHILD)
        assertEquals(listOf(options[0]), state.solvedOptionIds); assertEquals(1, state.progressDone); assertEquals(6, state.progressTotal)
        assertNull(fixture.state(DeviceRole.SPECIALIST).correctOptionId)

        var last: AnswerExerciseResponse? = null
        options.drop(1).forEach { last = fixture.answer(it, it) }
        assertEquals(ExerciseStatus.COMPLETED, last?.exerciseStatus); assertEquals(6, last?.progressDone)
        assertEquals(options, fixture.state(DeviceRole.CHILD).solvedOptionIds)
    }

    @Test fun `SORT wrong group, correct, conflict on repeat and completion`() = runBlocking {
        val fixture = Fixture(); fixture.loadSingle(gamesTemplate, 3)
        val options = fixture.optionIds() // ракета A, рыба A, лампа B, лодка B
        fixture.startCurrent()

        val wrong = fixture.answer(options[0], "B")
        assertFalse(wrong.isCorrect); assertEquals("ракета → Л", wrong.selectedOptionLabel); assertEquals(0, wrong.progressDone); assertEquals(4, wrong.progressTotal)
        val right = fixture.answer(options[0], "A")
        assertTrue(right.isCorrect); assertEquals("ракета → Р", right.selectedOptionLabel); assertEquals(1, right.progressDone)
        assertApiError(409) { fixture.answer(options[0], "A") }
        assertApiError(400) { fixture.answer(options[1], "C") }

        fixture.answer(options[1], "A"); fixture.answer(options[2], "B")
        val last = fixture.answer(options[3], "B")
        assertEquals(ExerciseStatus.COMPLETED, last.exerciseStatus); assertEquals(4, last.progressDone)
        val state = fixture.state(DeviceRole.SPECIALIST)
        assertEquals(options, state.solvedOptionIds); assertNull(state.correctOptionId)
    }

    @Test fun `WORD_BUILD wrong letter, right letter and completion`() = runBlocking {
        val fixture = Fixture(); fixture.loadSingle(gamesTemplate, 5)
        val options = fixture.optionIds() // К1 О2 Т3 А М
        fixture.startCurrent()
        val dto = checkNotNull(fixture.state(DeviceRole.CHILD).exercise)
        assertEquals("exercise_cat", dto.gameConfig?.imageAssetKey)
        assertEquals(listOf(1, 2, 3, null, null), dto.options.map { it.sequenceIndex })

        val wrong = fixture.answer(options[3], "ignored")
        assertFalse(wrong.isCorrect); assertEquals("А", wrong.selectedOptionLabel); assertNull(wrong.secondaryValue); assertEquals(3, wrong.progressTotal)
        assertTrue(fixture.answer(options[0]).isCorrect)
        assertFalse(fixture.answer(options[2]).isCorrect)
        assertEquals(2, fixture.answer(options[1]).progressDone)
        val done = fixture.answer(options[2])
        assertEquals(ExerciseStatus.COMPLETED, done.exerciseStatus); assertEquals(3, done.progressDone)
        assertEquals(listOf(options[0], options[1], options[2]), fixture.state(DeviceRole.CHILD).solvedOptionIds)
    }

    @Test fun `WORD_BUILD accepts any tile with the expected letter but never reuses a tile`() = runBlocking {
        val fixture = Fixture()
        val mama = fixture.content.createExercise(fixture.context, CreateExerciseRequest("МАМА", "Собери слово", ActivityType.WORD_BUILD,
            options = listOf("М" to 1, "А" to 2, "м" to 3, "А" to 4, "О" to null).map { ExerciseOptionInput(label = it.first, sequenceIndex = it.second) },
            gameConfig = GameExerciseConfigDto(imageAssetKey = "exercise_mama")), null)
        val template = fixture.content.createTemplate(fixture.context, CreateLessonTemplateRequest("Слова", items = listOf(LessonTemplateItemInput(mama.id))), null)
        fixture.loadSingle(template.id, 0)
        val o = fixture.optionIds() // М1 А2 м3 А4 О
        fixture.startCurrent()

        assertTrue("second М tile is a valid first letter", fixture.answer(o[2]).isCorrect)
        assertTrue(fixture.answer(o[3]).isCorrect)
        assertFalse("used tile cannot be tapped again", fixture.answer(o[2]).isCorrect)
        assertTrue(fixture.answer(o[0]).isCorrect)
        val done = fixture.answer(o[1])
        assertTrue(done.isCorrect); assertEquals(ExerciseStatus.COMPLETED, done.exerciseStatus); assertEquals(4, done.progressDone); assertEquals(4, done.progressTotal)
    }

    @Test fun `MAZE off path stays running and finish completes`() = runBlocking {
        val fixture = Fixture(); fixture.loadSingle(gamesTemplate, 4)
        fixture.startCurrent()
        assertEquals("wave", fixture.state(DeviceRole.CHILD).exercise?.gameConfig?.mazeLayout)
        assertApiError(400) { fixture.answer("teleport") }
        val off = fixture.answer("off_path")
        assertFalse(off.isCorrect); assertEquals("Сошёл с дорожки", off.selectedOptionLabel); assertEquals(ExerciseStatus.RUNNING, off.exerciseStatus)
        assertEquals(0, off.progressDone); assertEquals(1, off.progressTotal)
        val finish = fixture.answer("finish")
        assertTrue(finish.isCorrect); assertEquals("Финиш", finish.selectedOptionLabel); assertEquals(ExerciseStatus.COMPLETED, finish.exerciseStatus); assertEquals(1, finish.progressDone)
    }

    @Test fun `REMEMBER and ODD_ONE behave like single choice and REMEMBER exposes its target`() = runBlocking {
        val remember = Fixture(); remember.loadSingle(gamesTemplate, 1)
        val target = "00000000-0000-0000-0000-000000000411"
        remember.startCurrent()
        val childDto = checkNotNull(remember.state(DeviceRole.CHILD).exercise)
        assertEquals(target, childDto.gameConfig?.targetOptionId)
        assertEquals(3000, childDto.gameConfig?.showTargetMs)
        assertNull(remember.state(DeviceRole.CHILD).correctOptionId)
        assertEquals(target, remember.state(DeviceRole.SPECIALIST).correctOptionId)
        val wrong = remember.answer("00000000-0000-0000-0000-000000000412")
        assertFalse(wrong.isCorrect); assertEquals(0, wrong.progressDone); assertEquals(1, wrong.progressTotal)
        assertApiError(400) { remember.answer("foreign") }
        val right = remember.answer(target)
        assertTrue(right.isCorrect); assertEquals(ExerciseStatus.COMPLETED, right.exerciseStatus); assertEquals(1, right.progressDone)

        val odd = Fixture(); odd.loadSingle(gamesTemplate, 2)
        odd.startCurrent()
        assertNull(odd.state(DeviceRole.CHILD).exercise?.gameConfig?.targetOptionId)
        assertEquals("00000000-0000-0000-0000-000000000423", odd.state(DeviceRole.SPECIALIST).correctOptionId)
        assertFalse(odd.answer("00000000-0000-0000-0000-000000000421").isCorrect)
        val oddRight = odd.answer("00000000-0000-0000-0000-000000000423")
        assertTrue(oddRight.isCorrect); assertEquals("машина", oddRight.selectedOptionLabel); assertEquals(ExerciseStatus.COMPLETED, oddRight.exerciseStatus)
    }

    @Test fun `idempotent replay must match secondary value and returns the original response`() = runBlocking {
        val fixture = Fixture(); fixture.loadSingle(gamesTemplate, 0)
        val options = fixture.optionIds()
        fixture.startCurrent()
        val event = UUID.randomUUID()
        val first = fixture.answer(options[0], options[0], event)
        fixture.answer(options[1], options[1])
        val replay = fixture.answer(options[0], options[0], event)
        assertEquals(first.attemptNumber, replay.attemptNumber)
        assertEquals(first.progressDone, replay.progressDone)
        assertEquals(options[0], replay.secondaryValue)
        assertEquals(2, fixture.state(DeviceRole.CHILD).attemptCount)
        assertApiError(400) { fixture.answer(options[0], options[1], event) }
    }

    @Test fun `specialist cannot answer games`() = runBlocking {
        val fixture = Fixture(); fixture.loadSingle(gamesTemplate, 4)
        fixture.startCurrent()
        assertApiError(403) { fixture.service.answer(fixture.specialist, AnswerExerciseRequest(fixture.current().id.toString(), "finish", UUID.randomUUID().toString())) }
    }

    @Test fun `full games lesson produces a summary covering every new type`() = runBlocking {
        val fixture = Fixture(); fixture.loadTemplate(gamesTemplate)
        // MEMORY
        fixture.startCurrent(); fixture.clock.advanceSeconds(4)
        val memory = fixture.optionIds(); fixture.answer(memory[0], memory[1]); memory.forEach { fixture.answer(it, it) }
        fixture.next()
        // REMEMBER
        fixture.startCurrent(); fixture.answer("00000000-0000-0000-0000-000000000411"); fixture.next()
        // ODD_ONE
        fixture.startCurrent(); fixture.answer("00000000-0000-0000-0000-000000000421"); fixture.answer("00000000-0000-0000-0000-000000000423"); fixture.next()
        // SORT
        fixture.startCurrent(); fixture.optionIds().zip(listOf("A", "A", "B", "B")).forEach { (id, group) -> fixture.answer(id, group) }; fixture.next()
        // MAZE
        fixture.startCurrent(); fixture.answer("finish"); fixture.next()
        // WORD_BUILD
        fixture.startCurrent(); fixture.optionIds().take(3).forEach { fixture.answer(it) }

        val summary = fixture.service.summary(fixture.specialist)
        assertEquals(6, summary.completedExercises)
        assertEquals(listOf(ActivityType.MEMORY, ActivityType.REMEMBER, ActivityType.ODD_ONE, ActivityType.SORT, ActivityType.MAZE, ActivityType.WORD_BUILD), summary.exercises.map { it.activityType })
        val memoryItem = summary.exercises[0]
        assertEquals("Найди пару", memoryItem.correctOptionLabel); assertEquals(7, memoryItem.attemptCount); assertEquals(1, memoryItem.incorrectAttempts)
        assertFalse(memoryItem.firstAttemptCorrect); assertEquals(4_000, memoryItem.timeToCorrectMs); assertEquals(4_000L, memoryItem.durationMs)
        assertEquals("синяя звезда", summary.exercises[1].correctOptionLabel); assertTrue(summary.exercises[1].firstAttemptCorrect)
        assertEquals("машина", summary.exercises[2].correctOptionLabel); assertEquals(1, summary.exercises[2].incorrectAttempts)
        assertEquals("Собери слово КОТ", summary.exercises[5].correctOptionLabel)
        assertEquals(4, summary.firstAttemptCorrectCount)
    }

    @Test fun `game DTOs serialize the documented contract`() = runBlocking {
        val fixture = Fixture(); fixture.loadSingle(gamesTemplate, 3)
        fixture.startCurrent()
        val answer = fixture.answer(fixture.optionIds()[0], "A")
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val answerJson = json.encodeToString(answer)
        assertTrue(answerJson.contains("\"secondaryValue\":\"A\"")); assertTrue(answerJson.contains("\"progressDone\":1")); assertTrue(answerJson.contains("\"progressTotal\":4"))
        val stateJson = json.encodeToString(fixture.state(DeviceRole.CHILD))
        assertTrue(stateJson.contains("\"groupLabels\":[\"Р\",\"Л\"]")); assertTrue(stateJson.contains("\"groupKey\":\"A\""))
        assertTrue(stateJson.contains("\"solvedOptionIds\":[")); assertTrue(stateJson.contains("\"activityType\":\"SORT\""))
        // Old snapshots without the new fields still decode.
        val legacy = Json.decodeFromString<ExerciseSnapshot>("""{"sourceExerciseId":"x","activityType":"SINGLE_CHOICE","title":"t","instructionText":"i","options":[{"id":"a","position":1}]}""")
        assertNull(legacy.gameConfig); assertNull(legacy.options.single().groupKey)
    }

    // ---------- V12 games: POP, SYLLABLES, SHADOW, COUNT ----------

    @Test fun `fun games template is seeded with the V12 exercises`() = runBlocking {
        val fixture = Fixture()
        val fun_ = fixture.content.snapshotTemplate(fixture.center, UUID.fromString(funGamesTemplate))
        assertEquals("Весёлые игры", fun_.name); assertEquals("Шарики, слоги, тени и счёт", fun_.description)
        assertEquals(listOf(ActivityType.POP, ActivityType.SYLLABLES, ActivityType.SHADOW, ActivityType.COUNT, ActivityType.MEMORY, ActivityType.ODD_ONE), fun_.exercises.map { it.activityType })
        assertEquals((307..310).map { "00000000-0000-0000-0000-000000000$it" } + listOf("00000000-0000-0000-0000-000000000301", "00000000-0000-0000-0000-000000000303"), fun_.exercises.map { it.sourceExerciseId })
        val pop = fun_.exercises[0]
        assertEquals((451..456).map { "00000000-0000-0000-0000-000000000$it" }, pop.options.map { it.id })
        assertEquals(listOf(true, false, true, false, true, false), pop.options.map { it.isTarget })
        assertNull(pop.correctOptionId)
        assertEquals(listOf("РА", "КЕ", "ТА"), fun_.exercises[1].gameConfig?.syllables)
        assertEquals("exercise_rocket", fun_.exercises[1].gameConfig?.imageAssetKey)
        assertTrue(fun_.exercises[1].options.isEmpty())
        assertEquals(listOf("рыба", "утка", "машина"), fun_.exercises[2].options.map { it.label })
        assertNull(fun_.exercises[2].correctOptionId)
        assertTrue(fun_.exercises[2].options.all { it.isTarget == null })
        val count = fun_.exercises[3]
        assertEquals(4, count.gameConfig?.countValue); assertEquals("exercise_fish", count.gameConfig?.imageAssetKey)
        assertEquals("00000000-0000-0000-0000-000000000474", count.correctOptionId)
        assertEquals(listOf("1", "2", "3", "4", "5"), count.options.map { it.label })
        assertTrue(count.options.all { it.localImageAssetKey == null && it.imageAssetId == null })
    }

    @Test fun `V12 game definitions are validated per type`() = runBlocking {
        val fixture = Fixture()
        fun opt(label: String?, correct: Boolean = false, image: String? = null) = ExerciseOptionInput(label = label, isCorrect = correct, imageAssetId = image)
        val image = UUID.randomUUID().toString()
        fixture.repository.insertMedia(MediaAssetRecord(UUID.fromString(image), fixture.center, ContentOwnership.CENTER, MediaType.IMAGE, "p.png", "p.png", "image/png", 1, Instant.EPOCH))

        // POP: 3..9, at least one target and one non-target, label or image
        val pop = assertCreated(fixture, ActivityType.POP, listOf(opt("ракета", true), opt("кот"), opt(null, true, image)))
        assertEquals(listOf(true, false, true), pop.options.map { it.isCorrect })
        assertRejected(fixture, ActivityType.POP, listOf(opt("а", true), opt("б")))
        assertRejected(fixture, ActivityType.POP, (1..10).map { opt("$it", it == 1) })
        assertRejected(fixture, ActivityType.POP, listOf(opt("а"), opt("б"), opt("в")))
        assertRejected(fixture, ActivityType.POP, listOf(opt("а", true), opt("б", true), opt("в", true)))
        assertRejected(fixture, ActivityType.POP, listOf(opt("а", true), opt("б"), opt(" ")))

        // SYLLABLES: no options, 2..6 syllables up to 4 chars, picture required
        val syllables = assertCreated(fixture, ActivityType.SYLLABLES, emptyList(), GameExerciseConfigDto(syllables = listOf(" РА", "КЕ", "ТА "), imageAssetKey = "exercise_rocket", countValue = 3, mazeLayout = "wave"))
        assertEquals(listOf("РА", "КЕ", "ТА"), syllables.gameConfig?.syllables)
        assertNull(syllables.gameConfig?.countValue); assertNull(syllables.gameConfig?.mazeLayout)
        assertCreated(fixture, ActivityType.SYLLABLES, emptyList(), GameExerciseConfigDto(syllables = listOf("МА", "МА"), imageAssetId = image))
        assertRejected(fixture, ActivityType.SYLLABLES, emptyList(), GameExerciseConfigDto(syllables = listOf("РА"), imageAssetKey = "exercise_rocket"))
        assertRejected(fixture, ActivityType.SYLLABLES, emptyList(), GameExerciseConfigDto(syllables = (1..7).map { "ТА" }, imageAssetKey = "exercise_rocket"))
        assertRejected(fixture, ActivityType.SYLLABLES, emptyList(), GameExerciseConfigDto(syllables = listOf("РА", " "), imageAssetKey = "exercise_rocket"))
        assertRejected(fixture, ActivityType.SYLLABLES, emptyList(), GameExerciseConfigDto(syllables = listOf("РА", "КЕТАА"), imageAssetKey = "exercise_rocket"))
        assertRejected(fixture, ActivityType.SYLLABLES, emptyList(), GameExerciseConfigDto(syllables = listOf("РА", "КЕ")))
        assertRejected(fixture, ActivityType.SYLLABLES, emptyList(), GameExerciseConfigDto(syllables = listOf("РА", "КЕ"), imageAssetKey = "Bad-Key"))
        assertRejected(fixture, ActivityType.SYLLABLES, emptyList(), GameExerciseConfigDto(syllables = listOf("РА", "КЕ"), imageAssetId = UUID.randomUUID().toString()))
        assertRejected(fixture, ActivityType.SYLLABLES, listOf(opt("а")), GameExerciseConfigDto(syllables = listOf("РА", "КЕ"), imageAssetKey = "exercise_rocket"))

        // SHADOW: 2..5 pictures, isCorrect ignored
        val shadow = assertCreated(fixture, ActivityType.SHADOW, listOf(opt("рыба", true, image), opt("утка", image = image)))
        assertTrue(shadow.options.none { it.isCorrect })
        assertRejected(fixture, ActivityType.SHADOW, listOf(opt("рыба", image = image)))
        assertRejected(fixture, ActivityType.SHADOW, (1..6).map { opt("$it", image = image) })
        assertRejected(fixture, ActivityType.SHADOW, listOf(opt("рыба", image = image), opt("утка")))

        // COUNT: 2..6 distinct numbers 1..10, one correct equal to countValue, picture required
        val countConfig = GameExerciseConfigDto(countValue = 3, imageAssetKey = "exercise_fish")
        val count = assertCreated(fixture, ActivityType.COUNT, listOf(opt("2"), opt("3", true), opt("4")), countConfig)
        assertEquals(3, count.gameConfig?.countValue); assertTrue(count.gameConfig?.syllables.orEmpty().isEmpty())
        assertCreated(fixture, ActivityType.COUNT, listOf(opt("10", true), opt("1")), GameExerciseConfigDto(countValue = 10, imageAssetId = image))
        assertRejected(fixture, ActivityType.COUNT, listOf(opt("3", true)), countConfig)
        assertRejected(fixture, ActivityType.COUNT, (1..7).map { opt("$it", it == 3) }, countConfig)
        assertRejected(fixture, ActivityType.COUNT, listOf(opt("2"), opt("3", true), opt("2")), countConfig)
        assertRejected(fixture, ActivityType.COUNT, listOf(opt("два"), opt("3", true)), countConfig)
        assertRejected(fixture, ActivityType.COUNT, listOf(opt("0"), opt("3", true)), countConfig)
        assertRejected(fixture, ActivityType.COUNT, listOf(opt("11"), opt("3", true)), countConfig)
        assertRejected(fixture, ActivityType.COUNT, listOf(opt("+2"), opt("3", true)), countConfig)
        assertRejected(fixture, ActivityType.COUNT, listOf(opt("2", true), opt("3", true)), countConfig)
        assertRejected(fixture, ActivityType.COUNT, listOf(opt("2"), opt("3")), countConfig)
        assertRejected(fixture, ActivityType.COUNT, listOf(opt("2", true), opt("3")), countConfig)
        assertRejected(fixture, ActivityType.COUNT, listOf(opt("2"), opt("3", true)), GameExerciseConfigDto(imageAssetKey = "exercise_fish"))
        assertRejected(fixture, ActivityType.COUNT, listOf(opt("2"), opt("11", true)), GameExerciseConfigDto(countValue = 11, imageAssetKey = "exercise_fish"))
        assertRejected(fixture, ActivityType.COUNT, listOf(opt("2"), opt("3", true)), GameExerciseConfigDto(countValue = 3))
    }

    @Test fun `POP pops targets, rejects repeats and exposes isTarget only on POP`() = runBlocking {
        val fixture = Fixture(); fixture.loadSingle(funGamesTemplate, 0)
        val o = fixture.optionIds() // ракета* кот рыба* дом груша* утка
        fixture.startCurrent()
        for (role in listOf(DeviceRole.CHILD, DeviceRole.SPECIALIST)) {
            val state = fixture.state(role)
            assertEquals(listOf(true, false, true, false, true, false), state.exercise?.options?.map { it.isTarget })
            assertNull(state.correctOptionId)
            assertEquals(0, state.progressDone); assertEquals(3, state.progressTotal)
        }
        val miss = fixture.answer(o[1])
        assertFalse(miss.isCorrect); assertEquals("кот", miss.selectedOptionLabel); assertEquals(0, miss.progressDone); assertEquals(3, miss.progressTotal)
        assertFalse("a non-target can be tapped again", fixture.answer(o[1]).isCorrect)
        val hit = fixture.answer(o[0], "ignored")
        assertTrue(hit.isCorrect); assertNull(hit.secondaryValue); assertEquals(1, hit.progressDone); assertEquals(ExerciseStatus.RUNNING, hit.exerciseStatus)
        assertApiError(409) { fixture.answer(o[0]) }
        assertApiError(400) { fixture.answer("foreign") }
        fixture.answer(o[2])
        assertEquals(listOf(o[0], o[2]), fixture.state(DeviceRole.CHILD).solvedOptionIds)
        val done = fixture.answer(o[4])
        assertTrue(done.isCorrect); assertEquals(ExerciseStatus.COMPLETED, done.exerciseStatus); assertEquals(3, done.progressDone)
        val state = fixture.state(DeviceRole.SPECIALIST)
        assertEquals(listOf(o[0], o[2], o[4]), state.solvedOptionIds); assertEquals(3, state.progressDone)

        // isTarget stays null for every other type, also in JSON.
        val other = Fixture(); other.loadSingle(funGamesTemplate, 3)
        other.startCurrent()
        assertTrue(checkNotNull(other.state(DeviceRole.SPECIALIST).exercise).options.all { it.isTarget == null })
        val json = Json { encodeDefaults = true; explicitNulls = false }
        assertFalse(json.encodeToString(other.state(DeviceRole.CHILD)).contains("isTarget"))
        assertTrue(json.encodeToString(fixture.state(DeviceRole.CHILD)).contains("\"isTarget\":false"))
    }

    @Test fun `SYLLABLES wrong tap count then right count`() = runBlocking {
        val fixture = Fixture(); fixture.loadSingle(funGamesTemplate, 1)
        fixture.startCurrent()
        val dto = checkNotNull(fixture.state(DeviceRole.CHILD).exercise)
        assertEquals("SYLLABLES", dto.activityType); assertTrue(dto.options.isEmpty())
        assertEquals(listOf("РА", "КЕ", "ТА"), dto.gameConfig?.syllables); assertEquals("exercise_rocket", dto.gameConfig?.imageAssetKey)
        assertNull(fixture.state(DeviceRole.SPECIALIST).correctOptionId)

        listOf("0", "13", "abc", "-1", "", "2.5").forEach { assertApiError(400) { fixture.answer(it) } }
        val wrong = fixture.answer("2")
        assertFalse(wrong.isCorrect); assertEquals("2 удара", wrong.selectedOptionLabel); assertEquals(0, wrong.progressDone); assertEquals(1, wrong.progressTotal)
        assertEquals("5 ударов", fixture.answer("5").selectedOptionLabel)
        assertEquals("1 удар", fixture.answer("1").selectedOptionLabel)
        val right = fixture.answer("3")
        assertTrue(right.isCorrect); assertEquals("3 удара", right.selectedOptionLabel); assertEquals(ExerciseStatus.COMPLETED, right.exerciseStatus); assertEquals(1, right.progressDone)
        assertTrue(fixture.state(DeviceRole.CHILD).solvedOptionIds.isEmpty())
    }

    @Test fun `SHADOW mismatch, match, conflict, completion and idempotent replay`() = runBlocking {
        val fixture = Fixture(); fixture.loadSingle(funGamesTemplate, 2)
        val o = fixture.optionIds() // рыба утка машина
        fixture.startCurrent()
        assertNull(fixture.state(DeviceRole.SPECIALIST).correctOptionId)

        val mismatch = fixture.answer(o[0], o[1])
        assertFalse(mismatch.isCorrect); assertEquals("рыба → утка", mismatch.selectedOptionLabel); assertEquals(o[1], mismatch.secondaryValue)
        assertEquals(0, mismatch.progressDone); assertEquals(3, mismatch.progressTotal)
        val event = UUID.randomUUID()
        val match = fixture.answer(o[0], o[0], event)
        assertTrue(match.isCorrect); assertEquals("рыба → рыба", match.selectedOptionLabel); assertEquals(1, match.progressDone); assertEquals(ExerciseStatus.RUNNING, match.exerciseStatus)
        assertApiError(409) { fixture.answer(o[0], o[0]) }
        assertApiError(400) { fixture.answer(o[1], "foreign") }
        assertApiError(400) { fixture.answer(o[1], null) }
        assertApiError(400) { fixture.answer("foreign", o[1]) }

        fixture.answer(o[1], o[1])
        val replay = fixture.answer(o[0], o[0], event)
        assertEquals(match.attemptNumber, replay.attemptNumber); assertEquals(1, replay.progressDone); assertEquals(o[0], replay.secondaryValue)
        assertApiError(400) { fixture.answer(o[0], o[1], event) }

        val done = fixture.answer(o[2], o[2])
        assertTrue(done.isCorrect); assertEquals(ExerciseStatus.COMPLETED, done.exerciseStatus); assertEquals(3, done.progressDone)
        assertEquals(o, fixture.state(DeviceRole.CHILD).solvedOptionIds)
        assertEquals(4, fixture.state(DeviceRole.CHILD).attemptCount)
    }

    @Test fun `COUNT behaves like single choice and exposes countValue`() = runBlocking {
        val fixture = Fixture(); fixture.loadSingle(funGamesTemplate, 3)
        val correct = "00000000-0000-0000-0000-000000000474"
        fixture.startCurrent()
        val dto = checkNotNull(fixture.state(DeviceRole.CHILD).exercise)
        assertEquals(4, dto.gameConfig?.countValue); assertEquals("exercise_fish", dto.gameConfig?.imageAssetKey)
        assertEquals(listOf("1", "2", "3", "4", "5"), dto.options.map { it.label }); assertTrue(dto.options.all { it.imageAssetKey.isEmpty() })
        assertNull(fixture.state(DeviceRole.CHILD).correctOptionId)
        assertEquals(correct, fixture.state(DeviceRole.SPECIALIST).correctOptionId)
        assertEquals(correct, fixture.service.specialistExercise(fixture.specialist).correctOptionId)
        assertApiError(400) { fixture.answer("foreign") }
        val wrong = fixture.answer("00000000-0000-0000-0000-000000000473")
        assertFalse(wrong.isCorrect); assertEquals("3", wrong.selectedOptionLabel); assertEquals(0, wrong.progressDone); assertEquals(1, wrong.progressTotal)
        val right = fixture.answer(correct)
        assertTrue(right.isCorrect); assertEquals("4", right.selectedOptionLabel); assertEquals(ExerciseStatus.COMPLETED, right.exerciseStatus); assertEquals(1, right.progressDone)
    }

    @Test fun `fun games lesson produces a summary covering the V12 types`() = runBlocking {
        val fixture = Fixture(); fixture.loadTemplate(funGamesTemplate)
        // POP
        fixture.startCurrent(); fixture.clock.advanceSeconds(3)
        val pop = fixture.optionIds(); fixture.answer(pop[1]); listOf(0, 2, 4).forEach { fixture.answer(pop[it]) }; fixture.next()
        // SYLLABLES
        fixture.startCurrent(); fixture.answer("3"); fixture.next()
        // SHADOW
        fixture.startCurrent(); fixture.optionIds().forEach { fixture.answer(it, it) }; fixture.next()
        // COUNT
        fixture.startCurrent(); fixture.answer("00000000-0000-0000-0000-000000000475"); fixture.answer("00000000-0000-0000-0000-000000000474"); fixture.next()
        // MEMORY
        fixture.startCurrent(); fixture.optionIds().forEach { fixture.answer(it, it) }; fixture.next()
        // ODD_ONE
        fixture.startCurrent(); fixture.answer("00000000-0000-0000-0000-000000000423")

        val summary = fixture.service.summary(fixture.specialist)
        assertEquals(6, summary.completedExercises)
        assertEquals(listOf(ActivityType.POP, ActivityType.SYLLABLES, ActivityType.SHADOW, ActivityType.COUNT, ActivityType.MEMORY, ActivityType.ODD_ONE), summary.exercises.map { it.activityType })
        val popItem = summary.exercises[0]
        assertEquals("Лопни шарики со звуком Р", popItem.correctOptionLabel); assertEquals(4, popItem.attemptCount); assertEquals(1, popItem.incorrectAttempts)
        assertFalse(popItem.firstAttemptCorrect); assertEquals(3_000, popItem.timeToCorrectMs)
        assertEquals("Слоги: ракета", summary.exercises[1].correctOptionLabel); assertTrue(summary.exercises[1].firstAttemptCorrect)
        assertEquals("Найди тень", summary.exercises[2].correctOptionLabel); assertEquals(3, summary.exercises[2].attemptCount)
        assertEquals("4", summary.exercises[3].correctOptionLabel); assertEquals(1, summary.exercises[3].incorrectAttempts)
        assertEquals(4, summary.firstAttemptCorrectCount)
    }

    // ---------- helpers ----------

    private suspend fun assertCreated(fixture: Fixture, type: ActivityType, options: List<ExerciseOptionInput>, config: GameExerciseConfigDto? = null) =
        fixture.content.createExercise(fixture.context, CreateExerciseRequest("Игра", "Инструкция", type, options = options, gameConfig = config), null)

    private suspend fun assertRejected(fixture: Fixture, type: ActivityType, options: List<ExerciseOptionInput>, config: GameExerciseConfigDto? = null) {
        val error = runCatching { assertCreated(fixture, type, options, config) }.exceptionOrNull()
        assertTrue("$type $options $config must be rejected", error is ApiException)
        assertEquals("VALIDATION_ERROR", (error as ApiException).errorCode)
    }

    private suspend fun assertApiError(code: Int, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected ApiException $code but was $error", error is ApiException)
        assertEquals(code, (error as ApiException).httpCode)
    }

    private class Fixture {
        val clock = MutableClock(Instant.parse("2026-11-20T09:00:00Z"))
        val repository = InMemoryContentRepository()
        val content = ContentService(repository, InMemorySaasRepository(), LocalMediaStorage("build/test-media-games"), clock)
        val exercises = InMemoryExerciseRepository()
        val service = ExerciseService(exercises, clock)
        val center: UUID = UUID.randomUUID()
        val context = context(center)
        private val sessionId = UUID.randomUUID()
        private val session = SessionRecord(sessionId, "1234", "Ребёнок", SessionStatus.READY, "specialist", "token", "child", "child-token", Instant.EPOCH, Instant.MAX, Instant.EPOCH, null, isManaged = true)
        val specialist = AuthorizedSession(session, DeviceRole.SPECIALIST)
        val child = AuthorizedSession(session, DeviceRole.CHILD)

        init {
            // These tests pin the hand-made (non-generated) behaviour of the seeded games, so V14's generate flag is switched off.
            runBlocking {
                repository.listExercises(center, kz.oyla.server.repository.ContentExerciseFilter()).filter { it.ownership == ContentOwnership.SYSTEM && it.gameConfig?.generate == true }
                    .forEach { exercise ->
                        // V14 dropped the hand-made COUNT number and SYLLABLES split; restore them from the stored options and the picture pool.
                        val config = checkNotNull(exercise.gameConfig)
                        val countValue = if (exercise.activityType == ActivityType.COUNT) exercise.options.single { it.isCorrect }.label!!.toInt() else config.countValue
                        val syllables = if (exercise.activityType == ActivityType.SYLLABLES) kz.oyla.server.service.PicturePool.byKey(config.imageAssetKey)!!.syllables else config.syllables
                        repository.insertExercise(exercise.copy(gameConfig = config.copy(generate = false, countValue = countValue, syllables = syllables)))
                    }
            }
        }

        suspend fun loadTemplate(templateId: String) = plan(content.snapshotTemplate(center, UUID.fromString(templateId)).exercises)
        suspend fun loadSingle(templateId: String, index: Int) = plan(listOf(content.snapshotTemplate(center, UUID.fromString(templateId)).exercises[index]))
        private suspend fun plan(snapshots: List<ExerciseSnapshot>) {
            service.ensureSnapshotPlan(snapshots.mapIndexed { index, snapshot ->
                SessionExerciseRecord(UUID.randomUUID(), sessionId, snapshot.sourceExerciseId, ExerciseStatus.PENDING, null, null, null, Instant.EPOCH, index + 1, index == 0, snapshot)
            })
        }
        suspend fun current() = checkNotNull(exercises.findCurrentSessionExercise(sessionId))
        suspend fun optionIds() = checkNotNull(current().snapshot).options.sortedBy { it.position }.map { it.id }
        suspend fun startCurrent() {
            val record = current()
            service.show(specialist, ShowExerciseRequest(record.exerciseId))
            service.start(specialist, StartExerciseRequest(record.id.toString()))
        }
        suspend fun answer(selected: String, secondary: String? = null, event: UUID = UUID.randomUUID()) =
            service.answer(child, AnswerExerciseRequest(current().id.toString(), selected, event.toString(), secondary))
        suspend fun next() = service.next(specialist, NextExerciseRequest(current().id.toString()))
        suspend fun state(role: DeviceRole) = service.stateFor(AuthorizedSession(session, role))

        private fun context(centerId: UUID): CenterContext {
            val now = Instant.EPOCH; val userId = UUID.randomUUID()
            return CenterContext(
                UserRecord(userId, "owner@example.com", "hash", "Owner", null, UserStatus.ACTIVE, now, now, null),
                CenterRecord(centerId, "Центр", "center-$centerId", CenterStatus.ACTIVE, "Asia/Almaty", now, now),
                CenterMembershipRecord(UUID.randomUUID(), centerId, userId, MembershipRole.OWNER, MembershipStatus.ACTIVE, now, now)
            )
        }
    }

    private class MutableClock(private var current: Instant) : Clock() {
        override fun instant(): Instant = current
        override fun withZone(zone: ZoneId): Clock = this
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        fun advanceSeconds(seconds: Long) { current = current.plusSeconds(seconds) }
    }
}
