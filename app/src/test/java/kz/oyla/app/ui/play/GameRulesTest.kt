package kz.oyla.app.ui.play

import kz.oyla.app.data.remote.dto.BasketNeedDto
import kz.oyla.app.data.remote.dto.ExerciseDto
import kz.oyla.app.data.remote.dto.ExerciseOptionDto
import kz.oyla.app.data.remote.dto.GameExerciseConfigDto
import kz.oyla.app.ui.session.ExerciseOptionUiModel
import kz.oyla.app.ui.session.toUi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRulesTest {
    private fun option(id: String, key: String = "", group: String? = null, target: Boolean? = null, cell: Int? = null, position: Int = 1) =
        ExerciseOptionUiModel(id, id, key, position, groupKey = group, isTarget = target, cellIndex = cell)

    @Test fun `type word accepts the next letter only and allows repeated letters`() {
        assertTrue(typeWordAccepts("МАМА", 0, "м"))
        assertFalse(typeWordAccepts("МАМА", 1, "М"))
        assertTrue(typeWordAccepts("МАМА", 2, "М"))
        assertFalse(typeWordAccepts("КОТ", 3, "Т"))
    }

    @Test fun `colour moves need the letter's own colour from the palette`() {
        val b = option("b", group = "H")
        assertTrue(colorMoveAccepted(b, "H", listOf("V", "H", "S"), emptySet()))
        assertFalse(colorMoveAccepted(b, "S", listOf("V", "H", "S"), emptySet()))
        assertFalse(colorMoveAccepted(b, "H", listOf("V", "C"), emptySet()))
        assertFalse(colorMoveAccepted(b, "H", listOf("V", "H", "S"), setOf("b")))
    }

    @Test fun `basket takes only needed kinds up to the count`() {
        val needs = listOf(BasketNeedDto("exercise_banana", "банан", 2), BasketNeedDto("exercise_apple", "яблоко", 1))
        val b1 = option("b1", "exercise_banana"); val b2 = option("b2", "exercise_banana"); val b3 = option("b3", "exercise_banana")
        val a1 = option("a1", "exercise_apple"); val cake = option("c", "exercise_cake")
        assertTrue(basketAccepts(b1, needs, emptyList()))
        assertTrue(basketAccepts(b2, needs, listOf(b1)))
        assertFalse(basketAccepts(b3, needs, listOf(b1, b2)))
        assertFalse(basketAccepts(b1, needs, listOf(b1)))
        assertTrue(basketAccepts(a1, needs, listOf(b1, b2)))
        assertFalse(basketAccepts(cake, needs, emptyList()))
    }

    @Test fun `label scene and target rules`() {
        assertTrue(labelAccepted("x", "x", emptySet()))
        assertFalse(labelAccepted("x", "y", emptySet()))
        assertFalse(labelAccepted("x", "x", setOf("x")))
        assertTrue(targetAccepted(option("t", target = true), emptySet()))
        assertFalse(targetAccepted(option("t", target = true), setOf("t")))
        assertFalse(targetAccepted(option("d", target = false), emptySet()))
    }

    @Test fun `parking gives every colour enough bays for its cars`() {
        val cars = listOf(option("1", group = "RED"), option("2", group = "RED"), option("3", group = "BLUE"))
        val bays = parkingBays(listOf("RED", "BLUE", "GREEN"), cars)
        assertEquals(listOf("RED", "RED", "BLUE", "GREEN"), bays.map { it.color })
        assertEquals(listOf(0, 1, 2, 3), bays.map { it.index })
    }

    @Test fun `arrows reach the candy following the arrows`() {
        // 4×4, start 0, candy at 6 (row 1, col 2): right, right, down.
        val run = simulateArrows(4, 0, 6, mapOf(0 to ArrowDir.RIGHT, 1 to ArrowDir.RIGHT, 2 to ArrowDir.DOWN), emptySet())
        assertEquals(ArrowOutcome.REACHED, run.outcome)
        assertEquals(listOf(0, 1, 2, 6), run.cells)
        assertFalse(run.crashed)
    }

    @Test fun `arrows crash off the grid, on stones, without arrows and in loops`() {
        val off = simulateArrows(4, 0, 15, mapOf(0 to ArrowDir.UP), emptySet())
        assertEquals(ArrowOutcome.OFF_GRID, off.outcome); assertEquals(ArrowDir.UP, off.bump)
        val stone = simulateArrows(4, 0, 15, mapOf(0 to ArrowDir.RIGHT), setOf(1))
        assertEquals(ArrowOutcome.STONE, stone.outcome); assertEquals(listOf(0), stone.cells)
        val gap = simulateArrows(4, 0, 15, mapOf(0 to ArrowDir.RIGHT), emptySet())
        assertEquals(ArrowOutcome.NO_ARROW, gap.outcome); assertEquals(listOf(0, 1), gap.cells)
        val loop = simulateArrows(4, 0, 15, mapOf(0 to ArrowDir.RIGHT, 1 to ArrowDir.DOWN, 5 to ArrowDir.LEFT, 4 to ArrowDir.UP), emptySet())
        assertEquals(ArrowOutcome.LOOP, loop.outcome); assertEquals(listOf(0, 1, 5, 4, 0), loop.cells)
        val wrap = simulateArrows(4, 3, 15, mapOf(3 to ArrowDir.RIGHT), emptySet())
        assertEquals("no wrapping to the next row", ArrowOutcome.OFF_GRID, wrap.outcome)
        assertTrue(simulateArrows(4, 0, 15, emptyMap(), emptySet()).crashed)
    }

    @Test fun `arrows handle diagonals and step limits`() {
        val run = simulateArrows(4, 0, 15, mapOf(0 to ArrowDir.DOWN_RIGHT, 5 to ArrowDir.DOWN_RIGHT, 10 to ArrowDir.DOWN_RIGHT), emptySet())
        assertEquals(listOf(0, 5, 10, 15), run.cells)
        val long = simulateArrows(8, 0, 63, (0 until 7).associateWith { ArrowDir.RIGHT } + (7 to ArrowDir.DOWN), emptySet(), maxSteps = 3)
        assertEquals(ArrowOutcome.TOO_LONG, long.outcome)
    }

    @Test fun `shortest path goes around stones`() {
        val path = arrowsShortestPath(3, 0, 2, setOf(1), diagonals = false)!!
        assertEquals(0, path.first()); assertEquals(2, path.last()); assertEquals(5, path.size)
        assertEquals(listOf(0, 4, 2), arrowsShortestPath(3, 0, 2, setOf(1), diagonals = true))
        assertNull(arrowsShortestPath(3, 0, 8, setOf(1, 3, 4), diagonals = true))
        assertEquals(ArrowDir.DOWN_LEFT, arrowBetween(4, 1, 4))
    }

    @Test fun `mascot starts on the last collected candy`() {
        val candies = listOf(option("c2", position = 2, cell = 9), option("c1", position = 1, cell = 5))
        assertEquals(0, arrowsStartCell(candies, emptySet(), 0))
        assertEquals(5, arrowsStartCell(candies, setOf("c1"), 0))
        assertEquals(listOf("c2"), remainingCandies(candies, setOf("c1")).map { it.id })
    }

    @Test fun `layout helpers fit everything`() {
        val fit = fitGrid(12, 500f, 260f, 10f, maxSide = 130f)
        assertTrue(fit.cols * fit.rows >= 12)
        assertTrue(fit.cols * fit.side + (fit.cols - 1) * 10f <= 500.01f)
        val slots = scatterSlots(24, "s", scatterSide(24))
        assertEquals(24, slots.size)
        assertEquals(slots, scatterSlots(24, "s", scatterSide(24)))
        assertEquals("×", mathOpSymbol("*")); assertEquals("−", mathOpSymbol("-")); assertEquals("+", mathOpSymbol(null))
    }

    @Test fun `old payloads parse and new fields reach the ui model`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(GameExerciseConfigDto.serializer(), """{"countValue":3}""")
        assertFalse(old.generate); assertTrue(old.requirements.isEmpty()); assertTrue(old.blockedCells.isEmpty())
        val config = json.decodeFromString(GameExerciseConfigDto.serializer(),
            """{"generate":true,"level":2,"gridSize":5,"startCell":0,"blockedCells":[3,7],"diagonals":true,"requirements":[{"imageAssetKey":"exercise_apple","label":"яблоко","count":2}],"container":"basket","word":"КОТ","mathOp":"+","operandA":2,"operandB":3}""")
        assertEquals(listOf(3, 7), config.blockedCells); assertEquals(2, config.requirements.single().count); assertEquals("КОТ", config.word)
        val ui = ExerciseDto("e", "Собери", options = listOf(ExerciseOptionDto("c", "Конфета", "exercise_candy", 1, cellIndex = 12)), activityType = "ARROWS", gameConfig = config).toUi()
        assertEquals(12, ui.options.single().cellIndex)
    }
}
