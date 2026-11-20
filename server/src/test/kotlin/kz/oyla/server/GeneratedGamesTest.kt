package kz.oyla.server

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.CenterMembershipRecord
import kz.oyla.server.model.CenterRecord
import kz.oyla.server.model.CenterStatus
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.ExerciseSnapshot
import kz.oyla.server.model.ExerciseSnapshotOption
import kz.oyla.server.model.ExerciseStatus
import kz.oyla.server.model.MembershipRole
import kz.oyla.server.model.MembershipStatus
import kz.oyla.server.model.SessionStatus
import kz.oyla.server.model.UserRecord
import kz.oyla.server.model.UserStatus
import kz.oyla.server.model.dto.AnswerExerciseRequest
import kz.oyla.server.model.dto.AnswerExerciseResponse
import kz.oyla.server.model.dto.BasketNeedDto
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generated variants in sessions: snapshots, DTOs, judging, progress and journal labels of the new game types. */
class GeneratedGamesTest {

    // ---------- snapshots ----------

    @Test fun `generated content gets a fresh variant per lesson and keeps its constraints`() = runBlocking {
        val fixture = Fixture()
        val template = fixture.template(611, 307, 310)
        val first = fixture.content.snapshotTemplate(fixture.center, template).exercises
        val second = fixture.content.snapshotTemplate(fixture.center, template).exercises
        assertNotEquals(first.map { it.options.map { option -> option.id } }, second.map { it.options.map { option -> option.id } })
        val oddOne = first[0]
        assertEquals("animal", oddOne.gameConfig?.category); assertEquals("Животные", oddOne.gameConfig?.categoryLabel); assertTrue(oddOne.gameConfig!!.generate)
        assertEquals(1, oddOne.options.count { it.id == oddOne.correctOptionId })
        assertTrue(oddOne.options.none { it.id.startsWith("00000000-0000-0000-0000-") })
        assertEquals("Р", first[1].gameConfig?.sound)
        first[1].options.forEach { assertEquals(it.label!!.uppercase().contains("Р"), it.isTarget) }
        assertEquals("exercise_fish", first[2].gameConfig?.imageAssetKey)
        assertEquals(first[2].gameConfig?.countValue.toString(), first[2].options.single { it.id == first[2].correctOptionId }.label)
    }

    @Test fun `session DTO carries the new fields to both tablets`() = runBlocking {
        val fixture = Fixture(); fixture.load(792, 781, 771, 761)
        fixture.startCurrent()
        val arrows = checkNotNull(fixture.state(DeviceRole.CHILD).exercise)
        assertEquals("ARROWS", arrows.activityType)
        assertEquals(5, arrows.gameConfig?.gridSize); assertEquals(0, arrows.gameConfig?.startCell); assertEquals(2, arrows.gameConfig?.blockedCells?.size)
        assertEquals(true, arrows.gameConfig?.diagonals); assertEquals(true, arrows.gameConfig?.generate); assertEquals(2, arrows.gameConfig?.level)
        assertTrue(arrows.options.all { it.cellIndex != null && it.isTarget == null })
        assertNull(fixture.state(DeviceRole.SPECIALIST).correctOptionId)
        fixture.solveCurrent(); fixture.next(); fixture.startCurrent()

        val missing = checkNotNull(fixture.state(DeviceRole.CHILD).exercise)
        assertEquals(1, missing.options.count { it.isTarget == true }); assertTrue(missing.options.all { it.isTarget != null && it.cellIndex != null })
        fixture.solveCurrent(); fixture.next(); fixture.startCurrent()

        val house = checkNotNull(fixture.state(DeviceRole.SPECIALIST).exercise)
        assertNotNull(house.gameConfig?.category); assertNotNull(house.gameConfig?.categoryLabel)
        assertEquals(4, house.options.count { it.isTarget == true })
        fixture.solveCurrent(); fixture.next(); fixture.startCurrent()

        val basket = checkNotNull(fixture.state(DeviceRole.CHILD).exercise)
        assertEquals("basket", basket.gameConfig?.container); assertEquals(2, basket.gameConfig?.requirements?.size)
        assertTrue(basket.options.all { it.isTarget == null && it.imageAssetKey.isNotEmpty() })
    }

    // ---------- choice-like ----------

    @Test fun `MATH PAIRS and PARTS are judged like a single choice`() = runBlocking {
        val fixture = Fixture(); fixture.load(705, 711, 721)
        for (expected in listOf(ActivityType.MATH, ActivityType.PAIRS, ActivityType.PARTS)) {
            fixture.startCurrent()
            val snapshot = fixture.snapshot(); val dto = checkNotNull(fixture.state(DeviceRole.CHILD).exercise)
            assertEquals(expected.name, dto.activityType)
            assertNull(fixture.state(DeviceRole.CHILD).correctOptionId)
            assertEquals(snapshot.correctOptionId, fixture.state(DeviceRole.SPECIALIST).correctOptionId)
            val config = checkNotNull(dto.gameConfig)
            when (expected) {
                ActivityType.MATH -> { assertEquals("×", config.mathOp); assertEquals((config.operandA!! * config.operandB!!).toString(), fixture.correctLabel()) }
                ActivityType.PAIRS -> assertEquals((config.operandA!! * config.operandB!!).toString(), fixture.correctLabel())
                else -> assertEquals(config.imageAssetKey, snapshot.options.single { it.id == snapshot.correctOptionId }.localImageAssetKey)
            }
            assertApiError(400) { fixture.answer("foreign") }
            val wrong = fixture.answer(snapshot.options.first { it.id != snapshot.correctOptionId }.id)
            assertFalse(wrong.isCorrect); assertEquals(0, wrong.progressDone); assertEquals(1, wrong.progressTotal)
            val right = fixture.answer(checkNotNull(snapshot.correctOptionId))
            assertTrue(right.isCorrect); assertEquals(ExerciseStatus.COMPLETED, right.exerciseStatus); assertEquals(fixture.correctLabel(), right.selectedOptionLabel)
            if (expected != ActivityType.PARTS) fixture.next()
        }
        val summary = fixture.service.summary(fixture.specialist)
        assertEquals(listOf(ActivityType.MATH, ActivityType.PAIRS, ActivityType.PARTS), summary.exercises.map { it.activityType })
        assertTrue(summary.exercises.all { it.attemptCount == 2 && it.incorrectAttempts == 1 && !it.firstAttemptCorrect })
        assertTrue(summary.exercises[0].correctOptionLabel.toInt() > 0)
    }

    // ---------- multi-step ----------

    @Test fun `TYPE_WORD accepts the next letter and allows pressing the same key again`() = runBlocking {
        val fixture = Fixture()
        val exercise = fixture.create(ActivityType.TYPE_WORD, GameExerciseConfigDto(generate = true, level = 1, word = "мама", imageAssetKey = "exercise_cat"))
        assertEquals("МАМА", exercise.gameConfig?.word)
        fixture.loadCreated(exercise.id); fixture.startCurrent()
        val keys = fixture.snapshot().options.associate { it.label!! to it.id }
        assertEquals(8, keys.size); assertEquals("МАМА", fixture.state(DeviceRole.CHILD).exercise?.gameConfig?.word)
        val wrong = fixture.answer(keys.getValue("А"))
        assertFalse(wrong.isCorrect); assertEquals(0, wrong.progressDone); assertEquals(4, wrong.progressTotal); assertEquals("А", wrong.selectedOptionLabel)
        assertTrue(fixture.answer(keys.getValue("М")).isCorrect)
        assertTrue(fixture.answer(keys.getValue("А")).isCorrect)
        val again = fixture.answer(keys.getValue("М"))
        assertTrue(again.isCorrect); assertEquals(3, again.progressDone)
        assertApiError(400) { fixture.answer("foreign") }
        val done = fixture.answer(keys.getValue("А"))
        assertEquals(ExerciseStatus.COMPLETED, done.exerciseStatus); assertEquals(4, done.progressDone)
        assertEquals(listOf("М", "А", "М", "А").map { keys.getValue(it) }, fixture.state(DeviceRole.CHILD).solvedOptionIds)
    }

    @Test fun `PHONETICS checks the colour, rejects unknown colours and repeats`() = runBlocking {
        val fixture = Fixture()
        val exercise = fixture.create(ActivityType.PHONETICS, GameExerciseConfigDto(generate = true, level = 2, word = "Кит"))
        fixture.loadCreated(exercise.id); fixture.startCurrent()
        val letters = fixture.snapshot().options.sortedBy { it.position }
        assertEquals(listOf("К", "И", "Т"), letters.map { it.label }); assertEquals(listOf("S", "V", "H"), letters.map { it.groupKey })
        assertEquals(listOf("V", "H", "S"), fixture.state(DeviceRole.CHILD).exercise?.gameConfig?.groupLabels)
        val wrong = fixture.answer(letters[0].id, "H")
        assertFalse(wrong.isCorrect); assertEquals("К → синий", wrong.selectedOptionLabel); assertEquals("H", wrong.secondaryValue); assertEquals(3, wrong.progressTotal)
        assertApiError(400) { fixture.answer(letters[0].id, "C") }
        assertApiError(400) { fixture.answer(letters[0].id, null) }
        val right = fixture.answer(letters[0].id, "S")
        assertTrue(right.isCorrect); assertEquals("К → зелёный", right.selectedOptionLabel); assertEquals(1, right.progressDone)
        assertApiError(409) { fixture.answer(letters[0].id, "S") }
        fixture.answer(letters[1].id, "V")
        val done = fixture.answer(letters[2].id, "H")
        assertEquals(ExerciseStatus.COMPLETED, done.exerciseStatus); assertEquals("Т → синий", done.selectedOptionLabel)
    }

    @Test fun `LABEL_SCENE matches words to objects`() = runBlocking {
        val fixture = Fixture(); fixture.load(751); fixture.startCurrent()
        val objects = fixture.snapshot().options
        assertEquals(3, objects.size)
        val wrong = fixture.answer(objects[0].id, objects[1].id)
        assertFalse(wrong.isCorrect); assertEquals("${objects[0].label} → ${objects[1].label}", wrong.selectedOptionLabel)
        assertApiError(400) { fixture.answer(objects[0].id, "foreign") }
        assertTrue(fixture.answer(objects[0].id, objects[0].id).isCorrect)
        assertApiError(409) { fixture.answer(objects[0].id, objects[0].id) }
        fixture.answer(objects[1].id, objects[1].id)
        val done = fixture.answer(objects[2].id, objects[2].id)
        assertEquals(ExerciseStatus.COMPLETED, done.exerciseStatus); assertEquals(3, done.progressDone); assertEquals(3, done.progressTotal)
    }

    @Test fun `BASKET accepts only needed food up to the required count`() = runBlocking {
        val fixture = Fixture()
        val exercise = fixture.create(ActivityType.BASKET, GameExerciseConfigDto(generate = true, level = 1,
            requirements = listOf(BasketNeedDto("exercise_banana", "", 2), BasketNeedDto("exercise_apple", "", 1))))
        assertEquals(listOf("банан", "яблоко"), exercise.gameConfig?.requirements?.map { it.label })
        fixture.loadCreated(exercise.id); fixture.startCurrent()
        val options = fixture.snapshot().options
        val bananas = options.filter { it.localImageAssetKey == "exercise_banana" }; val apples = options.filter { it.localImageAssetKey == "exercise_apple" }
        val distractor = options.first { it.localImageAssetKey != "exercise_banana" && it.localImageAssetKey != "exercise_apple" }
        val notNeeded = fixture.answer(distractor.id)
        assertFalse(notNeeded.isCorrect); assertEquals(0, notNeeded.progressDone); assertEquals(3, notNeeded.progressTotal)
        // Level 1 adds one surplus item of a needed kind: once that kind is complete, the extra one is refused.
        val (surplusKind, need) = if (bananas.size > 2) bananas to 2 else apples to 1
        surplusKind.take(need).forEach { assertTrue(fixture.answer(it.id).isCorrect) }
        assertApiError(409) { fixture.answer(surplusKind[0].id) }
        assertFalse(fixture.answer(surplusKind[need].id).isCorrect)
        val rest = if (surplusKind === bananas) apples.take(1) else bananas.take(2)
        var last: AnswerExerciseResponse? = null
        rest.forEach { last = fixture.answer(it.id) }
        assertEquals(ExerciseStatus.COMPLETED, last?.exerciseStatus); assertEquals(3, last?.progressDone)
    }

    @Test fun `CATEGORY_HOUSE and MISSING behave like POP`() = runBlocking {
        val fixture = Fixture(); fixture.load(772, 782)
        for (expectedTargets in listOf(6, 2)) {
            fixture.startCurrent()
            val options = fixture.snapshot().options
            val targets = options.filter { it.isTarget == true }; val other = options.first { it.isTarget == false }
            assertEquals(expectedTargets, targets.size)
            val wrong = fixture.answer(other.id)
            assertFalse(wrong.isCorrect); assertEquals(other.label, wrong.selectedOptionLabel); assertEquals(expectedTargets, wrong.progressTotal)
            assertTrue(fixture.answer(targets[0].id).isCorrect)
            assertApiError(409) { fixture.answer(targets[0].id) }
            var last: AnswerExerciseResponse? = null
            targets.drop(1).forEach { last = fixture.answer(it.id) }
            assertEquals(ExerciseStatus.COMPLETED, (last ?: error("no answer")).exerciseStatus)
            assertEquals(targets.map { it.id }, fixture.state(DeviceRole.CHILD).solvedOptionIds)
            if (expectedTargets == 6) fixture.next()
        }
    }

    @Test fun `PARKING checks the spot colour`() = runBlocking {
        val fixture = Fixture(); fixture.load(787); fixture.startCurrent()
        val cars = fixture.snapshot().options; val palette = fixture.snapshot().gameConfig!!.groupLabels
        assertEquals(5, cars.size); assertEquals(5, palette.size)
        val car = cars[0]; val otherColor = palette.first { it != car.groupKey }
        val wrong = fixture.answer(car.id, otherColor)
        assertFalse(wrong.isCorrect); assertTrue(wrong.selectedOptionLabel.endsWith(" место")); assertTrue(wrong.selectedOptionLabel.startsWith("${car.label} → "))
        assertApiError(400) { fixture.answer(car.id, "BLACK") }
        val right = fixture.answer(car.id, car.groupKey)
        assertTrue(right.isCorrect); assertEquals(1, right.progressDone); assertEquals(5, right.progressTotal)
        assertApiError(409) { fixture.answer(car.id, car.groupKey) }
        var last: AnswerExerciseResponse? = null
        cars.drop(1).forEach { last = fixture.answer(it.id, it.groupKey) }
        assertEquals(ExerciseStatus.COMPLETED, last?.exerciseStatus)
    }

    @Test fun `PARKING journal label names the colours`() = runBlocking {
        val fixture = Fixture(); fixture.loadSnapshots(listOf(ExerciseSnapshot("x", ActivityType.PARKING, "Парковка", "Поставь", options = listOf(
            ExerciseSnapshotOption("car-red", "красная машинка", position = 1, groupKey = "RED"), ExerciseSnapshotOption("car-blue", "синяя машинка", position = 2, groupKey = "BLUE")),
            gameConfig = kz.oyla.server.model.GameExerciseConfig(generate = true, level = 1, groupLabels = listOf("BLUE", "RED")))))
        fixture.startCurrent()
        assertEquals("красная машинка → синее место", fixture.answer("car-red", "BLUE").selectedOptionLabel)
        assertEquals("красная машинка → красное место", fixture.answer("car-red", "RED").selectedOptionLabel)
    }

    @Test fun `ARROWS collects candies in order and counts crashes as mistakes`() = runBlocking {
        val fixture = Fixture(); fixture.load(793); fixture.startCurrent()
        val candies = fixture.snapshot().options.sortedBy { it.position }
        assertEquals(3, candies.size)
        val crash = fixture.answer("CRASH")
        assertFalse(crash.isCorrect); assertEquals("Врезался", crash.selectedOptionLabel); assertEquals(0, crash.progressDone); assertEquals(3, crash.progressTotal)
        assertFalse(fixture.answer(candies[1].id).isCorrect)
        val first = fixture.answer(candies[0].id)
        assertTrue(first.isCorrect); assertEquals("Конфета 1", first.selectedOptionLabel); assertEquals(1, first.progressDone)
        assertFalse(fixture.answer(candies[0].id).isCorrect)
        assertApiError(400) { fixture.answer("foreign") }
        assertTrue(fixture.answer(candies[1].id).isCorrect)
        val done = fixture.answer(candies[2].id)
        assertEquals(ExerciseStatus.COMPLETED, done.exerciseStatus); assertEquals(3, done.progressDone)
        assertEquals(candies.map { it.id }, fixture.state(DeviceRole.SPECIALIST).solvedOptionIds)
    }

    @Test fun `a lesson of every new type completes and summarises`() = runBlocking {
        val fixture = Fixture(); val ids = listOf(701, 712, 722, 732, 743, 753, 763, 773, 783, 788, 791)
        fixture.load(*ids.toIntArray())
        ids.forEachIndexed { index, _ ->
            fixture.startCurrent(); fixture.solveCurrent()
            assertEquals(ExerciseStatus.COMPLETED, fixture.current().status)
            if (index < ids.lastIndex) fixture.next()
        }
        val summary = fixture.service.summary(fixture.specialist)
        assertEquals(ids.size, summary.completedExercises); assertEquals(ids.size, summary.firstAttemptCorrectCount)
        assertEquals(listOf(ActivityType.MATH, ActivityType.PAIRS, ActivityType.PARTS, ActivityType.TYPE_WORD, ActivityType.PHONETICS, ActivityType.LABEL_SCENE,
            ActivityType.BASKET, ActivityType.CATEGORY_HOUSE, ActivityType.MISSING, ActivityType.PARKING, ActivityType.ARROWS), summary.exercises.map { it.activityType })
    }

    // ---------- content API ----------

    @Test fun `new types must be generated with a level and no options`() = runBlocking {
        val fixture = Fixture()
        assertRejected(fixture, ActivityType.MATH, GameExerciseConfigDto(), "Это упражнение составляется автоматически: включите генерацию и выберите уровень от 1 до 3")
        assertRejected(fixture, ActivityType.MATH, GameExerciseConfigDto(generate = true), "Выберите уровень сложности от 1 до 3")
        assertRejected(fixture, ActivityType.ARROWS, GameExerciseConfigDto(generate = true, level = 4), "Уровень сложности должен быть от 1 до 3")
        assertRejected(fixture, ActivityType.PARKING, GameExerciseConfigDto(generate = true, level = 1), "Варианты этого упражнения составляются автоматически", listOf(ExerciseOptionInput(label = "машинка")))
        assertRejected(fixture, ActivityType.MAZE, GameExerciseConfigDto(generate = true, level = 1, mazeLayout = "wave"), "Для этого вида упражнения генерация недоступна")
        assertRejected(fixture, ActivityType.CATEGORY_HOUSE, GameExerciseConfigDto(generate = true, level = 1, category = "динозавры"), "Неизвестная категория картинок")
        assertRejected(fixture, ActivityType.POP, GameExerciseConfigDto(generate = true, level = 1, sound = "Щ"), "Нет картинок со звуком Щ")
        assertRejected(fixture, ActivityType.PHONETICS, GameExerciseConfigDto(generate = true, level = 2, word = "мышь"), "Для раскраски звуков нужно слово без Ь и Ъ")
        assertRejected(fixture, ActivityType.MATH, GameExerciseConfigDto(generate = true, level = 1, mathOp = "÷"), "Действие должно быть +, -, × или ±")
        ActivityType.entries.filter { it.name in NewTypes }.forEach { type ->
            val created = fixture.create(type, GameExerciseConfigDto(generate = true, level = 2))
            assertEquals(type, created.activityType); assertEquals(true, created.gameConfig?.generate); assertEquals(2, created.gameConfig?.level)
        }
    }

    @Test fun `existing types may be generated from constraints and keep their old rules otherwise`() = runBlocking {
        val fixture = Fixture()
        val odd = fixture.create(ActivityType.ODD_ONE, GameExerciseConfigDto(generate = true, level = 2, category = "Фрукты", word = "лишнее"))
        assertEquals("fruit", odd.gameConfig?.category); assertEquals("Фрукты", odd.gameConfig?.categoryLabel); assertNull(odd.gameConfig?.word); assertTrue(odd.options.isEmpty())
        val count = fixture.create(ActivityType.COUNT, GameExerciseConfigDto(generate = true, imageAssetKey = "exercise_duck"))
        assertNull(count.gameConfig?.level)
        fixture.loadCreated(count.id)
        val snapshot = fixture.snapshot()
        assertEquals("exercise_duck", snapshot.gameConfig?.imageAssetKey); assertTrue(snapshot.gameConfig!!.countValue!! in 1..5)
        // Without generate the hand-made rules still apply.
        val error = runCatching { fixture.create(ActivityType.ODD_ONE, GameExerciseConfigDto()) }.exceptionOrNull()
        assertEquals("«Что лишнее» требует от 3 до 6 вариантов", (error as ApiException).clientMessage)
        // Irrelevant fields are dropped and updates are revalidated.
        val parking = fixture.create(ActivityType.PARKING, GameExerciseConfigDto(generate = true, level = 1, word = "КОТ", gridSize = 5))
        assertNull(parking.gameConfig?.word); assertNull(parking.gameConfig?.gridSize)
        val arrows = fixture.create(ActivityType.ARROWS, GameExerciseConfigDto(generate = true, level = 1, gridSize = 6, diagonals = true))
        assertEquals(6, arrows.gameConfig?.gridSize); assertEquals(true, arrows.gameConfig?.diagonals)
        val updated = fixture.content.updateExercise(fixture.context, UUID.fromString(arrows.id), UpdateExerciseRequest(gameConfig = GameExerciseConfigDto(generate = true, level = 3)), null)
        assertEquals(3, updated.gameConfig?.level); assertNull(updated.gameConfig?.gridSize)
        val rejected = runCatching { fixture.content.updateExercise(fixture.context, UUID.fromString(arrows.id), UpdateExerciseRequest(gameConfig = GameExerciseConfigDto(level = 1)), null) }.exceptionOrNull()
        assertEquals("VALIDATION_ERROR", (rejected as ApiException).errorCode)
    }

    // ---------- helpers ----------

    private suspend fun assertRejected(fixture: Fixture, type: ActivityType, config: GameExerciseConfigDto, message: String, options: List<ExerciseOptionInput> = emptyList()) {
        val error = runCatching { fixture.create(type, config, options) }.exceptionOrNull()
        assertTrue("$type $config must be rejected but was $error", error is ApiException)
        assertEquals("VALIDATION_ERROR", (error as ApiException).errorCode)
        assertEquals(message, error.clientMessage)
    }

    private suspend fun assertApiError(code: Int, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected ApiException $code but was $error", error is ApiException)
        assertEquals(code, (error as ApiException).httpCode)
    }

    private class Fixture(seed: Int = 11) {
        val clock = FixedClock(Instant.parse("2026-11-20T09:00:00Z"))
        val repository = InMemoryContentRepository()
        val content = ContentService(repository, InMemorySaasRepository(), LocalMediaStorage("build/test-media-generated"), clock, Random(seed))
        val exercises = InMemoryExerciseRepository()
        val service = ExerciseService(exercises, clock)
        val center: UUID = UUID.randomUUID()
        val context = context(center)
        private val sessionId = UUID.randomUUID()
        private val session = SessionRecord(sessionId, "1234", "Ребёнок", SessionStatus.READY, "specialist", "token", "child", "child-token", Instant.EPOCH, Instant.MAX, Instant.EPOCH, null, isManaged = true)
        val specialist = AuthorizedSession(session, DeviceRole.SPECIALIST)
        val child = AuthorizedSession(session, DeviceRole.CHILD)

        suspend fun template(vararg exercises: Int): UUID = UUID.fromString(content.createTemplate(context,
            CreateLessonTemplateRequest("Тест", null, exercises.map { LessonTemplateItemInput(id(it).toString()) }), null).id)
        suspend fun load(vararg exercises: Int) = loadSnapshots(content.snapshotTemplate(center, template(*exercises)).exercises)
        suspend fun loadCreated(exerciseId: String) = loadSnapshots(content.snapshotTemplate(center, UUID.fromString(content.createTemplate(context,
            CreateLessonTemplateRequest("Тест", null, listOf(LessonTemplateItemInput(exerciseId))), null).id)).exercises)
        suspend fun loadSnapshots(snapshots: List<ExerciseSnapshot>) {
            service.ensureSnapshotPlan(snapshots.mapIndexed { index, snapshot ->
                SessionExerciseRecord(UUID.randomUUID(), sessionId, snapshot.sourceExerciseId, ExerciseStatus.PENDING, null, null, null, Instant.EPOCH, index + 1, index == 0, snapshot)
            })
        }
        suspend fun create(type: ActivityType, config: GameExerciseConfigDto, options: List<ExerciseOptionInput> = emptyList()) =
            content.createExercise(context, CreateExerciseRequest("Игра", "Инструкция", type, options = options, gameConfig = config), null)
        suspend fun current() = checkNotNull(exercises.findCurrentSessionExercise(sessionId))
        suspend fun snapshot() = checkNotNull(current().snapshot)
        suspend fun correctLabel() = snapshot().let { s -> s.options.single { it.id == s.correctOptionId }.label!! }
        suspend fun startCurrent() {
            val record = current()
            service.show(specialist, ShowExerciseRequest(record.exerciseId))
            service.start(specialist, StartExerciseRequest(record.id.toString()))
        }
        suspend fun answer(selected: String, secondary: String? = null) =
            service.answer(child, AnswerExerciseRequest(current().id.toString(), selected, UUID.randomUUID().toString(), secondary))
        suspend fun next() = service.next(specialist, NextExerciseRequest(current().id.toString()))
        suspend fun state(role: DeviceRole) = service.stateFor(AuthorizedSession(session, role))

        /** Plays the current generated exercise perfectly, the way the tablet would. */
        suspend fun solveCurrent() {
            val snapshot = snapshot(); val options = snapshot.options.sortedBy { it.position }; val config = snapshot.gameConfig
            when (snapshot.activityType) {
                ActivityType.MATH, ActivityType.PAIRS, ActivityType.PARTS -> answer(checkNotNull(snapshot.correctOptionId))
                ActivityType.TYPE_WORD -> config!!.word!!.forEach { letter -> answer(options.first { it.label == letter.toString() }.id) }
                ActivityType.PHONETICS -> options.forEach { answer(it.id, it.groupKey) }
                ActivityType.LABEL_SCENE -> options.forEach { answer(it.id, it.id) }
                ActivityType.PARKING -> options.forEach { answer(it.id, it.groupKey) }
                ActivityType.CATEGORY_HOUSE, ActivityType.MISSING -> options.filter { it.isTarget == true }.forEach { answer(it.id) }
                ActivityType.ARROWS -> options.forEach { answer(it.id) }
                ActivityType.BASKET -> config!!.requirements.forEach { need -> options.filter { it.localImageAssetKey == need.imageAssetKey }.take(need.count).forEach { answer(it.id) } }
                else -> error("not a new type")
            }
        }

        private fun context(centerId: UUID): CenterContext {
            val now = Instant.EPOCH; val userId = UUID.randomUUID()
            return CenterContext(
                UserRecord(userId, "owner@example.com", "hash", "Owner", null, UserStatus.ACTIVE, now, now, null),
                CenterRecord(centerId, "Центр", "center-$centerId", CenterStatus.ACTIVE, "Asia/Almaty", now, now),
                CenterMembershipRecord(UUID.randomUUID(), centerId, userId, MembershipRole.OWNER, MembershipStatus.ACTIVE, now, now)
            )
        }
    }

    private class FixedClock(private val current: Instant) : Clock() {
        override fun instant(): Instant = current
        override fun withZone(zone: ZoneId): Clock = this
        override fun getZone(): ZoneId = ZoneId.of("UTC")
    }

    private companion object {
        val NewTypes = setOf("MATH", "PAIRS", "PARTS", "TYPE_WORD", "PHONETICS", "LABEL_SCENE", "BASKET", "CATEGORY_HOUSE", "MISSING", "PARKING", "ARROWS")
        fun id(suffix: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(suffix))
    }
}
