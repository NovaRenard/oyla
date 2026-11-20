package kz.oyla.app.ui.play

import kz.oyla.app.data.remote.dto.BasketNeedDto
import kz.oyla.app.ui.session.ExerciseOptionUiModel
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Pure rules of the generated games (no Compose). The tablet judges every move with these for
 * instant feedback; the server re-checks the same rules (docs/game-generation-contract.md).
 */

/* ---------- TYPE_WORD ---------- */

/** The key is right when it is the next letter of [word] after [typed] correct presses. */
fun typeWordAccepts(word: String, typed: Int, keyLabel: String): Boolean {
    val expected = word.getOrNull(typed) ?: return false
    return keyLabel.trim().equals(expected.toString(), ignoreCase = true)
}

/* ---------- PHONETICS / PARKING ---------- */

/** A letter (or car) takes [colorKey] when the colour is on the palette and is its own colour. */
fun colorMoveAccepted(item: ExerciseOptionUiModel, colorKey: String, palette: List<String>, done: Set<String>): Boolean =
    item.id !in done && colorKey in palette && item.groupKey == colorKey

/* ---------- LABEL_SCENE ---------- */

fun labelAccepted(wordId: String, objectId: String, labelled: Set<String>): Boolean = wordId == objectId && objectId !in labelled

/* ---------- POP-like: CATEGORY_HOUSE, MISSING ---------- */

fun targetAccepted(item: ExerciseOptionUiModel, done: Set<String>): Boolean = item.isTarget == true && item.id !in done

/* ---------- BASKET ---------- */

/** The item fits when its kind is asked for and fewer of that kind than needed are already inside. */
fun basketAccepts(item: ExerciseOptionUiModel, requirements: List<BasketNeedDto>, placed: List<ExerciseOptionUiModel>): Boolean {
    if (placed.any { it.id == item.id }) return false
    val need = requirements.firstOrNull { it.imageAssetKey == item.imageAssetKey } ?: return false
    return placed.count { it.imageAssetKey == item.imageAssetKey } < need.count
}

/* ---------- PARKING ---------- */

/** One parking bay of a colour; a colour gets as many bays as it has cars (at least one). */
data class ParkingBay(val color: String, val index: Int)

fun parkingBays(palette: List<String>, cars: List<ExerciseOptionUiModel>): List<ParkingBay> {
    val colors = palette.distinct()
    val result = mutableListOf<ParkingBay>()
    colors.forEach { color ->
        val count = maxOf(1, palette.count { it == color }, cars.count { it.groupKey == color })
        repeat(count) { result += ParkingBay(color, result.size) }
    }
    return result
}

/* ---------- ARROWS ---------- */

enum class ArrowDir(val dx: Int, val dy: Int, val diagonal: Boolean, val degrees: Float) {
    UP(0, -1, false, -90f), RIGHT(1, 0, false, 0f), DOWN(0, 1, false, 90f), LEFT(-1, 0, false, 180f),
    UP_RIGHT(1, -1, true, -45f), DOWN_RIGHT(1, 1, true, 45f), DOWN_LEFT(-1, 1, true, 135f), UP_LEFT(-1, -1, true, -135f)
}

enum class ArrowOutcome { REACHED, NO_ARROW, OFF_GRID, STONE, LOOP, TOO_LONG }

/** Cells the mascot visits (start first) and why it stopped; [bump] is the move that failed. */
data class ArrowRun(val cells: List<Int>, val outcome: ArrowOutcome, val bump: ArrowDir? = null) {
    val crashed: Boolean get() = outcome != ArrowOutcome.REACHED
}

const val ARROWS_MAX_STEPS = 40

/**
 * Walks from [start] following the arrows until the mascot reaches [target], steps off the
 * grid or onto a stone, lands on a cell without an arrow, comes back to a visited cell or
 * walks [maxSteps] cells.
 */
fun simulateArrows(
    gridSize: Int, start: Int, target: Int, arrows: Map<Int, ArrowDir>, blocked: Set<Int>, maxSteps: Int = ARROWS_MAX_STEPS
): ArrowRun {
    val n = gridSize.coerceAtLeast(1)
    val path = mutableListOf(start)
    if (start == target) return ArrowRun(path, ArrowOutcome.REACHED)
    val seen = mutableSetOf(start)
    var cell = start
    while (true) {
        val dir = arrows[cell] ?: return ArrowRun(path, ArrowOutcome.NO_ARROW)
        val row = cell / n + dir.dy
        val col = cell % n + dir.dx
        if (row !in 0 until n || col !in 0 until n) return ArrowRun(path, ArrowOutcome.OFF_GRID, dir)
        val next = row * n + col
        if (next in blocked) return ArrowRun(path, ArrowOutcome.STONE, dir)
        if (path.size > maxSteps) return ArrowRun(path, ArrowOutcome.TOO_LONG)
        path += next
        if (next == target) return ArrowRun(path, ArrowOutcome.REACHED)
        if (!seen.add(next)) return ArrowRun(path, ArrowOutcome.LOOP)
        cell = next
    }
}

/** Shortest route from [start] to [target] (both included) around the stones, or null. */
fun arrowsShortestPath(gridSize: Int, start: Int, target: Int, blocked: Set<Int>, diagonals: Boolean): List<Int>? {
    val n = gridSize.coerceAtLeast(1)
    if (start !in 0 until n * n || target !in 0 until n * n) return null
    val dirs = ArrowDir.entries.filter { diagonals || !it.diagonal }
    val previous = HashMap<Int, Int>()
    val queue = ArrayDeque(listOf(start))
    val seen = mutableSetOf(start)
    while (queue.isNotEmpty()) {
        val cell = queue.removeFirst()
        if (cell == target) {
            val route = mutableListOf(target)
            while (route.last() != start) route += previous.getValue(route.last())
            return route.reversed()
        }
        for (dir in dirs) {
            val row = cell / n + dir.dy
            val col = cell % n + dir.dx
            if (row !in 0 until n || col !in 0 until n) continue
            val next = row * n + col
            if (next in blocked || !seen.add(next)) continue
            previous[next] = cell
            queue.addLast(next)
        }
    }
    return null
}

/** The arrow that leads from [from] to the neighbouring cell [to]. */
fun arrowBetween(gridSize: Int, from: Int, to: Int): ArrowDir? {
    val dx = to % gridSize - from % gridSize
    val dy = to / gridSize - from / gridSize
    return ArrowDir.entries.firstOrNull { it.dx == dx && it.dy == dy }
}

/** Candies still to collect, in order. */
fun remainingCandies(candies: List<ExerciseOptionUiModel>, collected: Set<String>): List<ExerciseOptionUiModel> =
    candies.sortedBy { it.position }.filter { it.id !in collected }

/** Where the mascot stands: on the last collected candy, or on the start cell. */
fun arrowsStartCell(candies: List<ExerciseOptionUiModel>, collected: Set<String>, startCell: Int): Int =
    candies.sortedBy { it.position }.lastOrNull { it.id in collected }?.cellIndex ?: startCell

/* ---------- Layout helpers ---------- */

/** Columns, rows and square side to fit [count] pictures into a [width]×[height] box. */
data class GridFit(val cols: Int, val rows: Int, val side: Float)

fun fitGrid(count: Int, width: Float, height: Float, gap: Float, maxSide: Float = 200f): GridFit {
    val n = count.coerceAtLeast(1)
    var best = GridFit(1, n, 0f)
    for (cols in 1..n) {
        val rows = ceil(n / cols.toFloat()).toInt()
        val side = minOf((width - (cols - 1) * gap) / cols, (height - (rows - 1) * gap) / rows, maxSide)
        if (side > best.side) best = GridFit(cols, rows, side)
    }
    return best
}

/** Top-left corners for [count] pieces of [side] around a central prop, in a stable order. */
fun scatterSlots(count: Int, seed: String?, side: Float = 110f): List<Pair<Float, Float>> {
    val step = side + 14f
    val slots = mutableListOf<Pair<Float, Float>>()
    // Left and right columns beside the prop, then a strip along the bottom.
    for (row in 0 until 4) {
        val y = 270f + row * step
        if (y + side > 790f) break
        slots += 24f to y; slots += (1256f - side) to y
        slots += (24f + step) to y; slots += (1256f - side - step) to y
    }
    var x = 24f + 2 * step
    while (x + side <= 1256f - 2 * step) { slots += x to (790f - side); x += step }
    val ordered = slots.shuffled(Random((seed ?: "").hashCode() + 31))
    return List(count) { i ->
        if (i < ordered.size) ordered[i]
        else ordered[i % ordered.size].let { (sx, sy) -> (sx + 26f).coerceAtMost(1256f - side) to (sy - 26f) }
    }
}

/** Slight variety for the size of a square scatter piece when there are many of them. */
fun scatterSide(count: Int): Float = when {
    count <= 16 -> 110f
    count <= 20 -> 100f
    else -> 90f
}

internal fun sqrtCeil(n: Int): Int = ceil(sqrt(n.toDouble())).toInt()
