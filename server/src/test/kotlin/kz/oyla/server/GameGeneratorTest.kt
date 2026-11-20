package kz.oyla.server

import java.util.UUID
import kotlin.random.Random
import kz.oyla.server.model.ActivityType
import kz.oyla.server.model.BasketNeed
import kz.oyla.server.model.ContentExerciseOptionRecord
import kz.oyla.server.model.GameExerciseConfig
import kz.oyla.server.repository.InMemoryContentRepository
import kz.oyla.server.repository.InMemorySaasRepository
import kz.oyla.server.service.ApiException
import kz.oyla.server.service.ContentService
import kz.oyla.server.service.GameGenerator
import kz.oyla.server.service.GeneratedExercise
import kz.oyla.server.service.GenerationException
import kz.oyla.server.storage.LocalMediaStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kz.oyla.server.service.PicturePool

/** GameGenerator: every type × level over many seeds keeps the contract's invariants. */
class GameGeneratorTest {
    private val seeds = 0 until 60
    private val content = ContentService(InMemoryContentRepository(), InMemorySaasRepository(), LocalMediaStorage("build/test-media-generator"))
    private val poolKeys = PicturePool.Pictures.map { it.key }.toSet()
    private val choiceLike = setOf(ActivityType.ODD_ONE, ActivityType.REMEMBER, ActivityType.COUNT, ActivityType.MATH, ActivityType.PAIRS, ActivityType.PARTS)

    private fun forAll(type: ActivityType, config: GameExerciseConfig = GameExerciseConfig(), check: (level: Int, GeneratedExercise) -> Unit) {
        for (level in 1..3) for (seed in seeds) {
            val generated = GameGenerator.generate(type, config.copy(generate = true, level = level), Random(seed * 31 + level))
            try {
                commonInvariants(type, level, generated)
                check(level, generated)
            } catch (error: AssertionError) {
                throw AssertionError("$type level $level seed $seed: ${error.message}\n$generated", error)
            }
        }
    }

    private fun commonInvariants(type: ActivityType, level: Int, generated: GeneratedExercise) {
        val options = generated.options
        assertTrue(generated.gameConfig.generate); assertEquals(level, generated.gameConfig.level)
        assertEquals(options.size, options.map { it.id }.distinct().size)
        options.forEach { assertEquals(4, UUID.fromString(it.id).version()) }
        assertEquals((1..options.size).toList(), options.map { it.position })
        options.mapNotNull { it.localImageAssetKey }.forEach { key ->
            assertTrue("$key is not an APK picture", key in poolKeys || key.startsWith("shape_") || key == GameGenerator.CandyKey)
        }
        if (type in choiceLike) {
            assertNotNull(generated.correctOptionId)
            assertEquals(1, options.count { it.id == generated.correctOptionId })
        } else assertNull(generated.correctOptionId)
    }

    /** Old types must still pass the hand-made content rules that the tablets were built against. */
    private fun assertValidAsContent(type: ActivityType, generated: GeneratedExercise) {
        val exerciseId = UUID.randomUUID()
        val records = generated.options.map { option ->
            ContentExerciseOptionRecord(UUID.fromString(option.id), exerciseId, option.label, null, option.localImageAssetKey, option.position,
                option.id == generated.correctOptionId || option.isTarget == true, option.groupKey, option.sequenceIndex)
        }
        val error = runCatching { content.validateDefinition(type, records, null, generated.gameConfig.copy(generate = false)) }.exceptionOrNull()
        assertNull("invalid as content: ${(error as? ApiException)?.clientMessage}", error)
    }

    private fun answerLabel(generated: GeneratedExercise) = generated.options.single { it.id == generated.correctOptionId }.label!!

    // ---------- existing types ----------

    @Test fun `ODD_ONE has one picture from another category`() {
        forAll(ActivityType.ODD_ONE) { level, g ->
            assertEquals(level + 3, g.options.size)
            val category = checkNotNull(g.gameConfig.category)
            assertEquals(PicturePool.categoryLabel(category), g.gameConfig.categoryLabel)
            g.options.forEach { option -> assertEquals(option.id == g.correctOptionId, PicturePool.byKey(option.localImageAssetKey)!!.category != category) }
            assertValidAsContent(ActivityType.ODD_ONE, g)
        }
        forAll(ActivityType.ODD_ONE, GameExerciseConfig(category = "food")) { _, g ->
            assertEquals("food", g.gameConfig.category); assertTrue(g.options.size in 4..5) // only 4 food pictures
        }
    }

    @Test fun `COUNT numbers follow the level and keep the content picture`() {
        forAll(ActivityType.COUNT, GameExerciseConfig(imageAssetKey = "exercise_fish")) { level, g ->
            val count = checkNotNull(g.gameConfig.countValue)
            assertTrue(count in 1..listOf(5, 8, 10)[level - 1])
            assertEquals(count.toString(), answerLabel(g)); assertEquals("exercise_fish", g.gameConfig.imageAssetKey)
            assertEquals(if (level == 1) 3 else 4, g.options.size)
            assertValidAsContent(ActivityType.COUNT, g)
        }
        forAll(ActivityType.COUNT) { _, g -> assertTrue(g.gameConfig.imageAssetKey in poolKeys) }
    }

    @Test fun `MEMORY SHADOW and REMEMBER sizes follow the level`() {
        forAll(ActivityType.MEMORY) { level, g -> assertEquals(listOf(3, 4, 6)[level - 1], g.options.size); assertValidAsContent(ActivityType.MEMORY, g) }
        forAll(ActivityType.MEMORY, GameExerciseConfig(category = "animal")) { _, g -> assertTrue(g.options.all { PicturePool.byKey(it.localImageAssetKey)!!.category == "animal" }) }
        forAll(ActivityType.SHADOW) { level, g ->
            assertEquals(listOf(3, 4, 4)[level - 1], g.options.size)
            if (level == 3) assertEquals(1, g.options.map { PicturePool.byKey(it.localImageAssetKey)!!.category }.distinct().size)
            assertValidAsContent(ActivityType.SHADOW, g)
        }
        forAll(ActivityType.REMEMBER) { level, g -> assertEquals(listOf(4, 6, 9)[level - 1], g.options.size); assertValidAsContent(ActivityType.REMEMBER, g) }
        forAll(ActivityType.REMEMBER, GameExerciseConfig(category = GameGenerator.ShapeCategory, showTargetMs = 3000)) { _, g ->
            assertTrue(g.options.all { it.localImageAssetKey!!.startsWith("shape_") }); assertEquals(3000, g.gameConfig.showTargetMs)
            assertEquals(g.options.size, g.options.map { it.localImageAssetKey }.distinct().size)
        }
    }

    @Test fun `SYLLABLES and WORD_BUILD words follow the level`() {
        forAll(ActivityType.SYLLABLES) { level, g ->
            val picture = checkNotNull(PicturePool.byKey(g.gameConfig.imageAssetKey))
            assertEquals(picture.syllables, g.gameConfig.syllables)
            assertTrue(g.gameConfig.syllables.size in listOf(1..2, 2..3, 3..6)[level - 1]); assertTrue(g.options.isEmpty())
        }
        forAll(ActivityType.SYLLABLES, GameExerciseConfig(imageAssetKey = "exercise_rocket")) { _, g -> assertEquals(listOf("РА", "КЕ", "ТА"), g.gameConfig.syllables) }
        forAll(ActivityType.WORD_BUILD) { level, g ->
            val word = PicturePool.byKey(g.gameConfig.imageAssetKey)!!.upperWord
            assertTrue(word.length in listOf(3..3, 4..5, 6..6)[level - 1])
            assertEquals(word, g.options.filter { it.sequenceIndex != null }.sortedBy { it.sequenceIndex }.joinToString("") { it.label!! })
            val distractors = g.options.filter { it.sequenceIndex == null }
            assertEquals(2, distractors.size); assertTrue(distractors.none { it.label!![0] in word })
            assertValidAsContent(ActivityType.WORD_BUILD, g)
        }
        forAll(ActivityType.WORD_BUILD, GameExerciseConfig(imageAssetKey = "exercise_cat")) { _, g ->
            assertEquals("КОТ", g.options.filter { it.sequenceIndex != null }.sortedBy { it.sequenceIndex }.joinToString("") { it.label!! })
        }
    }

    @Test fun `POP targets are exactly the words with the sound`() {
        forAll(ActivityType.POP, GameExerciseConfig(sound = "Р")) { level, g ->
            assertEquals(listOf(6, 8, 9)[level - 1], g.options.size); assertEquals("Р", g.gameConfig.sound)
            g.options.forEach { assertEquals(it.label, it.isTarget, it.label!!.uppercase().contains("Р")) }
            assertValidAsContent(ActivityType.POP, g)
        }
        forAll(ActivityType.POP) { _, g ->
            val sound = checkNotNull(g.gameConfig.sound)
            g.options.forEach { assertEquals(it.isTarget, it.label!!.uppercase().contains(sound)) }
        }
    }

    @Test fun `SORT by sounds and by categories`() {
        forAll(ActivityType.SORT, GameExerciseConfig(groupLabels = listOf("С", "Ш"))) { level, g ->
            assertEquals(listOf(4, 6, 8)[level - 1], g.options.size)
            g.options.forEach { option ->
                val word = option.label!!.uppercase()
                val (own, other) = if (option.groupKey == "A") "С" to "Ш" else "Ш" to "С"
                assertTrue(word.contains(own) && !word.contains(other))
            }
            assertValidAsContent(ActivityType.SORT, g)
        }
        forAll(ActivityType.SORT, GameExerciseConfig(groupLabels = listOf("Фрукты", "Овощи"))) { _, g ->
            g.options.forEach { assertEquals(if (it.groupKey == "A") "fruit" else "vegetable", PicturePool.byKey(it.localImageAssetKey)!!.category) }
            assertEquals(listOf("Фрукты", "Овощи"), g.gameConfig.groupLabels)
        }
        forAll(ActivityType.SORT) { _, g -> assertEquals(2, g.gameConfig.groupLabels.size); assertValidAsContent(ActivityType.SORT, g) }
    }

    // ---------- new types ----------

    @Test fun `MATH examples follow the operator ranges`() {
        for (op in listOf("+", "-", "×", "±")) forAll(ActivityType.MATH, GameExerciseConfig(mathOp = op)) { level, g ->
            val config = g.gameConfig; val a = config.operandA!!; val b = config.operandB!!
            val concrete = config.mathOp!!
            if (op == "±") assertTrue(concrete in setOf("+", "-")) else assertEquals(op, concrete)
            val answer = when (concrete) { "+" -> a + b; "-" -> a - b; else -> a * b }
            when (concrete) {
                "+" -> assertTrue(a >= 1 && b >= 1 && a + b <= listOf(5, 10, 20)[level - 1])
                "-" -> assertTrue(a in 2..listOf(5, 10, 20)[level - 1] && b in 1 until a)
                else -> { assertTrue(a in 2..listOf(3, 5, 9)[level - 1]); assertTrue(b in 2..listOf(3, 5, 5)[level - 1]) }
            }
            assertEquals(answer.toString(), answerLabel(g)); assertEquals(4, g.options.size)
            assertEquals(4, g.options.map { it.label }.distinct().size)
            assertTrue(g.options.all { it.label!!.toInt() >= 0 }); assertTrue(config.imageAssetKey in poolKeys)
        }
        val fixed = GameGenerator.generate(ActivityType.MATH, GameExerciseConfig(generate = true, mathOp = "+", operandA = 2, operandB = 3, imageAssetKey = "exercise_apple"), Random(1))
        assertEquals("5", answerLabel(fixed)); assertEquals("exercise_apple", fixed.gameConfig.imageAssetKey)
    }

    @Test fun `PAIRS answer is cups times plates`() {
        forAll(ActivityType.PAIRS) { level, g ->
            val a = g.gameConfig.operandA!!; val b = g.gameConfig.operandB!!
            val range = listOf(2..3, 2..4, 3..5)[level - 1]
            assertTrue(a in range && b in range)
            assertEquals((a * b).toString(), answerLabel(g)); assertEquals(4, g.options.map { it.label }.distinct().size)
        }
    }

    @Test fun `PARTS picks distractors by level`() {
        forAll(ActivityType.PARTS) { level, g ->
            assertEquals(4, g.options.size)
            val target = g.options.single { it.id == g.correctOptionId }
            assertEquals(g.gameConfig.imageAssetKey, target.localImageAssetKey)
            val categories = g.options.map { PicturePool.byKey(it.localImageAssetKey)!!.category }
            if (level == 1) assertEquals(4, categories.distinct().size)
            if (level == 3) assertEquals(1, categories.distinct().size)
            assertEquals(4, g.options.map { it.localImageAssetKey }.distinct().size)
        }
    }

    @Test fun `TYPE_WORD keys are unique and contain every letter`() {
        forAll(ActivityType.TYPE_WORD) { level, g ->
            val word = checkNotNull(g.gameConfig.word)
            assertTrue(word.length in listOf(3..3, 4..5, 6..12)[level - 1])
            assertEquals(listOf(8, 10, 12)[level - 1], g.options.size)
            val keys = g.options.map { it.label!! }
            assertEquals(keys.size, keys.distinct().size); assertTrue(word.all { it.toString() in keys })
            assertEquals(word, PicturePool.byKey(g.gameConfig.imageAssetKey)?.upperWord)
        }
        val custom = GameGenerator.generate(ActivityType.TYPE_WORD, GameExerciseConfig(generate = true, level = 1, word = "мама"), Random(3))
        assertEquals("МАМА", custom.gameConfig.word); assertEquals(8, custom.options.size)
    }

    @Test fun `PHONETICS colours letters by the classification rule`() {
        assertEquals(listOf("S", "V", "H"), "КИТ".indices.map { GameGenerator.phoneticClass("КИТ", it, 2) })
        assertEquals(listOf("C", "V", "C"), "КИТ".indices.map { GameGenerator.phoneticClass("КИТ", it, 1) })
        assertEquals(listOf("S", "V", "S"), "МЯЧ".indices.map { GameGenerator.phoneticClass("МЯЧ", it, 2) })
        fun classes(word: String, level: Int = 2) = word.indices.map { GameGenerator.phoneticClass(word, it, level) }
        assertEquals(listOf("H", "V", "H", "V"), classes("ШИНА")) // Ш is always hard, even before И
        assertEquals(listOf("H", "V", "H", "H"), classes("ЦИРК", 3))
        assertEquals(listOf("H", "V", "S", "V"), "ЖИЛЕ".indices.map { GameGenerator.phoneticClass("ЖИЛЕ", it, 2) })
        assertEquals(listOf("S", "V", "S"), "ЩУЙ".indices.map { GameGenerator.phoneticClass("ЩУЙ", it, 2) })
        assertEquals(listOf("H", "V", "H", "V", "H"), "БАНАН".indices.map { GameGenerator.phoneticClass("БАНАН", it, 2) })
        assertEquals(listOf("S", "V", "H", "V", "H"), classes("ЛИМОН")) // М before О is hard

        forAll(ActivityType.PHONETICS) { level, g ->
            val word = checkNotNull(g.gameConfig.word)
            assertTrue(word.length in listOf(3..4, 4..5, 5..7)[level - 1]); assertFalse(word.contains('Ь') || word.contains('Ъ'))
            assertEquals(if (level == 1) listOf("V", "C") else listOf("V", "H", "S"), g.gameConfig.groupLabels)
            assertEquals(word, g.options.joinToString("") { it.label!! })
            g.options.forEachIndexed { index, option ->
                assertEquals(GameGenerator.phoneticClass(word, index, level), option.groupKey); assertTrue(option.groupKey in g.gameConfig.groupLabels)
            }
        }
        val mouse = runCatching { GameGenerator.generate(ActivityType.PHONETICS, GameExerciseConfig(generate = true, level = 2, word = "мышь"), Random(1)) }.exceptionOrNull()
        assertTrue(mouse is GenerationException)
    }

    @Test fun `LABEL_SCENE MISSING and CATEGORY_HOUSE layouts`() {
        forAll(ActivityType.LABEL_SCENE) { level, g ->
            assertEquals(listOf(3, 4, 6)[level - 1], g.options.size)
            assertTrue(g.options.all { it.cellIndex in 0..8 }); assertEquals(g.options.size, g.options.map { it.cellIndex }.distinct().size)
            assertEquals(g.options.size, g.options.map { it.label }.distinct().size)
        }
        forAll(ActivityType.MISSING) { level, g ->
            assertEquals(listOf(5, 6, 8)[level - 1], g.options.size); assertEquals(level, g.options.count { it.isTarget == true })
            assertTrue(g.options.all { it.isTarget != null && it.cellIndex in 0..8 }); assertEquals(g.options.size, g.options.map { it.cellIndex }.distinct().size)
        }
        forAll(ActivityType.CATEGORY_HOUSE) { level, g ->
            val category = checkNotNull(g.gameConfig.category); assertEquals(PicturePool.categoryLabel(category), g.gameConfig.categoryLabel)
            assertEquals(listOf(4, 6, 9)[level - 1], g.options.count { it.isTarget == true })
            assertEquals(listOf(4, 5, 5)[level - 1], g.options.count { it.isTarget == false })
            g.options.forEach { assertEquals(it.isTarget, PicturePool.byKey(it.localImageAssetKey)!!.category == category) }
            assertEquals(g.options.size, g.options.map { it.localImageAssetKey }.distinct().size)
        }
    }

    @Test fun `BASKET requirements can be met and distractors follow the level`() {
        for (container in listOf("basket", "bottle")) forAll(ActivityType.BASKET, GameExerciseConfig(container = container)) { level, g ->
            val needs = g.gameConfig.requirements
            assertEquals(container, g.gameConfig.container)
            assertEquals(listOf(2, 3, 4)[level - 1], needs.size); assertEquals(needs.size, needs.map { it.imageAssetKey }.distinct().size)
            needs.forEach { need ->
                assertTrue(need.count in 1..listOf(2, 3, 3)[level - 1])
                assertEquals(PicturePool.byKey(need.imageAssetKey)!!.word, need.label)
                assertTrue(g.options.count { it.localImageAssetKey == need.imageAssetKey } >= need.count)
            }
            val allowed = if (container == "bottle") setOf("fruit") else PicturePool.FoodCategories
            assertTrue(g.options.all { PicturePool.byKey(it.localImageAssetKey)!!.category in allowed })
            val distractorKinds = g.options.map { it.localImageAssetKey }.toSet() - needs.map { it.imageAssetKey }.toSet()
            when (level) { 1 -> assertEquals(1, distractorKinds.size); 2 -> assertEquals(2, distractorKinds.size); else -> assertTrue(distractorKinds.size in 3..4) }
            assertTrue(g.options.size <= 16)
        }
        val fixed = GameGenerator.generate(ActivityType.BASKET, GameExerciseConfig(generate = true, level = 1, requirements = listOf(BasketNeed("exercise_banana", "x", 2))), Random(5))
        assertEquals(listOf(BasketNeed("exercise_banana", "банан", 2)), fixed.gameConfig.requirements)
    }

    @Test fun `PARKING spots match the cars`() {
        forAll(ActivityType.PARKING) { level, g ->
            val cars = listOf(3, 5, 7)[level - 1]
            assertEquals(cars, g.options.size); assertEquals(cars, g.gameConfig.groupLabels.size)
            assertEquals(listOf(3, 4, 5)[level - 1], g.gameConfig.groupLabels.distinct().size)
            assertTrue(g.gameConfig.groupLabels.all { it in GameExerciseConfig.ParkingColors })
            assertEquals(g.gameConfig.groupLabels.sorted(), g.options.map { it.groupKey!! }.sorted())
            assertTrue(g.options.all { it.label!!.endsWith("машинка") })
        }
    }

    @Test fun `ARROWS candies are always reachable`() {
        forAll(ActivityType.ARROWS) { level, g ->
            val config = g.gameConfig; val size = config.gridSize!!; val start = config.startCell!!
            assertEquals(listOf(4, 5, 6)[level - 1], size); assertEquals(0, start)
            assertEquals(listOf(1, 2, 3)[level - 1], g.options.size); assertEquals(listOf(0, 2, 4)[level - 1], config.blockedCells.size)
            assertEquals(level >= 2, config.diagonals)
            val cells = g.options.map { it.cellIndex!! }
            assertEquals(cells.size, cells.distinct().size)
            assertTrue(cells.none { it == start || it in config.blockedCells || it !in 0 until size * size })
            assertTrue(start !in config.blockedCells)
            val reachable = GameGenerator.reachableCells(size, start, config.blockedCells.toSet(), config.diagonals)
            assertTrue("unreachable candy", cells.all { it in reachable })
            assertEquals((1..g.options.size).map { "Конфета $it" }, g.options.map { it.label })
        }
        // A wall of stones that cuts the board is rejected rather than producing an unwinnable board.
        val wall = runCatching { GameGenerator.generate(ActivityType.ARROWS, GameExerciseConfig(generate = true, level = 1, blockedCells = listOf(1, 4, 5)), Random(1)) }.exceptionOrNull()
        assertTrue(wall is GenerationException)
        assertEquals(setOf(0, 1, 2, 3), GameGenerator.reachableCells(4, 0, setOf(4, 5, 6, 7), diagonals = true).let { it })
        // No corner cutting between two stones.
        assertFalse(5 in GameGenerator.reachableCells(4, 0, setOf(1, 4), diagonals = true))
    }

    @Test fun `generation is deterministic for a seed and varies between seeds`() {
        for (type in GameGenerator.Supported) {
            val config = GameExerciseConfig(generate = true, level = 2)
            val a = GameGenerator.generate(type, config, Random(42)); val b = GameGenerator.generate(type, config, Random(42))
            assertEquals(type.name, a, b)
            if (type != ActivityType.SYLLABLES) {
                val variants = (0 until 20).map { seed -> GameGenerator.generate(type, config, Random(seed)).let { g -> g.options.map { it.copy(id = "") } to g.gameConfig } }.toSet()
                assertTrue("$type always generates the same variant", variants.size > 1)
            }
        }
    }

    @Test fun `impossible constraints fail with a Russian message`() {
        listOf(
            ActivityType.POP to GameExerciseConfig(sound = "Щ"),
            ActivityType.SORT to GameExerciseConfig(groupLabels = listOf("Р", "Р")),
            ActivityType.SORT to GameExerciseConfig(groupLabels = listOf("Кошки", "Собаки")),
            ActivityType.MATH to GameExerciseConfig(mathOp = "/"),
            ActivityType.ODD_ONE to GameExerciseConfig(category = "dinosaurs"),
            ActivityType.BASKET to GameExerciseConfig(container = "box"),
            ActivityType.ARROWS to GameExerciseConfig(gridSize = 12),
            ActivityType.WORD_BUILD to GameExerciseConfig(imageAssetKey = "exercise_mama"),
            ActivityType.MAZE to GameExerciseConfig()
        ).forEach { (type, config) ->
            val error = runCatching { GameGenerator.generate(type, config.copy(generate = true, level = 1), Random(1)) }.exceptionOrNull()
            assertTrue("$type $config must fail", error is GenerationException)
            assertTrue(error!!.message!!.any { it in 'А'..'я' })
        }
        assertTrue(runCatching { GameGenerator.generate(ActivityType.MATH, GameExerciseConfig(generate = true, level = 4), Random(1)) }.exceptionOrNull() is GenerationException)
    }
}
