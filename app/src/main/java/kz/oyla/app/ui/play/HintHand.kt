package kz.oyla.app.ui.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kz.oyla.app.ui.session.ExerciseOptionUiModel
import kz.oyla.app.ui.session.ExerciseUiState
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

/** One move of the demonstration hand, in artboard coordinates (1280×800). */
sealed interface HintStep {
    data class Tap(val x: Float, val y: Float, val times: Int = 1) : HintStep
    data class Drag(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float) : HintStep
    /** Hover sweep without pressing, e.g. "look at all the fish". */
    data class Sweep(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float) : HintStep
}

/* ---------- Layout facts shared by the games and their hints ---------- */

/** Stable per-task order (same on the child tablet and in the specialist preview). */
fun shuffledFor(sessionExerciseId: String?, options: List<ExerciseOptionUiModel>): List<ExerciseOptionUiModel> =
    options.shuffled(Random((sessionExerciseId ?: "").hashCode() xor 0x5f3759df))

fun wordTilesFor(sessionExerciseId: String?, exerciseId: String, options: List<ExerciseOptionUiModel>): List<ExerciseOptionUiModel> =
    options.shuffled(Random((sessionExerciseId ?: exerciseId).hashCode()))

/** Shadows in a different order from the pictures, never in the same row as their picture. */
fun shadowSlotsFor(sessionExerciseId: String?, pieces: List<ExerciseOptionUiModel>): List<ExerciseOptionUiModel> {
    var result = pieces.shuffled(Random((sessionExerciseId ?: "").hashCode() + 7))
    repeat(pieces.size) { if (result.indices.any { result[it].id == pieces[it].id }) result = result.drop(1) + result.first() }
    return result
}

data class ShadowLayout(val side: Int, val gap: Int) { fun rowY(i: Int) = 150 + i * (side + gap) }
fun shadowLayout(count: Int): ShadowLayout {
    val n = count.coerceAtLeast(1)
    val side = minOf(180, (600 - (n - 1) * 24) / n)
    return ShadowLayout(side, if (n > 1) (600 - n * side) / (n - 1) else 0)
}

private fun gridCenter(index: Int, count: Int, cols: Int, left: Int, top: Int, width: Int, height: Int, gap: Int, maxSide: Int): Offset {
    val rows = ceil(count / cols.toFloat()).toInt().coerceAtLeast(1)
    val cellW = ((width - (cols - 1) * gap) / cols).coerceAtMost(maxSide)
    val cellH = ((height - (rows - 1) * gap) / rows).coerceAtMost(maxSide)
    val usedW = cols * cellW + (cols - 1) * gap
    val usedH = rows * cellH + (rows - 1) * gap
    val row = index / cols
    val inRow = if (row == rows - 1) count - row * cols else cols
    val x = left + (width - usedW) / 2 + (cols - inRow) * (cellW + gap) / 2 + (index % cols) * (cellW + gap)
    val y = top + (height - usedH) / 2 + row * (cellH + gap)
    return Offset(x + cellW / 2f, y + cellH / 2f)
}

/** What the hand shows for each game: the gesture, not the answer (except the very first move of a sequence game). */
fun hintPlan(state: ExerciseUiState): List<HintStep> {
    val exercise = state.exercise ?: return emptyList()
    val options = exercise.options
    newGameHintPlan(state)?.let { return it }
    return when (exercise.activityType) {
        "MEMORY" -> {
            val deck = options.size * 2
            val cols = when (deck) { 4 -> 4; 6 -> 3; 8 -> 4; 10 -> 5; 12 -> 4; 14 -> 7; else -> 6 }
            val a = gridCenter(0, deck, cols, 140, 160, 1000, 565, 24, 200)
            val b = gridCenter(1, deck, cols, 140, 160, 1000, 565, 24, 200)
            listOf(HintStep.Tap(a.x, a.y), HintStep.Tap(b.x, b.y))
        }
        "SORT" -> {
            val items = shuffledFor(state.sessionExerciseId, options)
            val first = items.firstOrNull() ?: return emptyList()
            val spacing = 1040f / items.size
            val size = minOf(180f, spacing - 24f)
            val x = 120f + (spacing - size) / 2f + size / 2f
            listOf(HintStep.Drag(x, 545f + size / 2f, if (first.groupKey == "B") 965f else 315f, 340f))
        }
        "MAZE" -> listOf(
            when (exercise.gameConfig?.mazeLayout) {
                "zigzag" -> HintStep.Drag(150f, 640f, 275f, 440f)
                "spiral" -> HintStep.Drag(160f, 640f, 175f, 450f)
                else -> HintStep.Drag(150f, 620f, 330f, 430f)
            }
        )
        "WORD_BUILD" -> {
            val tiles = wordTilesFor(state.sessionExerciseId, exercise.id, options)
            val first = options.filter { it.sequenceIndex != null }.minByOrNull { it.sequenceIndex!! } ?: return emptyList()
            val index = tiles.indexOfFirst { it.label.equals(first.label, ignoreCase = true) }.coerceAtLeast(0)
            val tile = minOf(150, (1100 - (tiles.size - 1) * 26) / tiles.size.coerceAtLeast(1))
            val total = tiles.size * tile + (tiles.size - 1) * 26
            val x = (1280 - total) / 2f + index * (tile + 26) + tile / 2f
            listOf(HintStep.Tap(x, 600f + tile / 2f))
        }
        "POP" -> listOf(HintStep.Tap(640f, 430f, times = 2))
        "SHADOW" -> {
            val layout = shadowLayout(options.size)
            val slots = shadowSlotsFor(state.sessionExerciseId, options)
            val first = options.firstOrNull() ?: return emptyList()
            val target = slots.indexOfFirst { it.id == first.id }
            val half = layout.side / 2f
            listOf(HintStep.Drag(220f + half, layout.rowY(0) + half, 880f + half, layout.rowY(target) + half))
        }
        "COUNT" -> listOf(HintStep.Sweep(260f, 260f, 1020f, 470f))
        "WHITEBOARD" -> listOf(HintStep.Drag(520f, 380f, 820f, 460f))
        "REMEMBER", "SYLLABLES" -> emptyList() // these games demonstrate themselves
        else -> {
            val n = options.size
            val cols = if (n <= 4) n else 3
            val oddOne = exercise.activityType == "ODD_ONE"
            val c = gridCenter(minOf(1, n - 1), n, cols.coerceAtLeast(1), 110, if (oddOne) 250 else 270, 1060, if (oddOne) 270 else 420, 40, 290)
            listOf(HintStep.Tap(c.x, c.y))
        }
    }
}

/**
 * A friendly pointing hand that acts out the gesture twice, then hides. It comes back when the
 * child has not touched the screen for [idleMs]. It never intercepts touches.
 */
@Composable
fun HintHand(plan: List<HintStep>, active: Boolean, touchTick: Int, key: Any?, idleMs: Long = 8000) {
    if (plan.isEmpty()) return
    val unitPx = LocalPlay.current.unit * LocalDensity.current.density
    var pos by remember(key) { mutableStateOf(Offset(640f, 900f)) }
    var visible by remember(key) { mutableStateOf(false) }
    var shown by remember(key) { mutableStateOf(false) }
    val press = remember(key) { Animatable(0f) }
    val ripple = remember(key) { Animatable(0f) }
    val appear = remember(key) { Animatable(0f) }
    LaunchedEffect(key, active, touchTick) {
        visible = false
        appear.snapTo(0f)
        if (!active) return@LaunchedEffect
        delay(if (!shown) 700 else idleMs)
        shown = true
        val start = when (val first = plan.first()) {
            is HintStep.Tap -> Offset(first.x + 140f, first.y + 160f)
            is HintStep.Drag -> Offset(first.fromX + 140f, first.fromY + 160f)
            is HintStep.Sweep -> Offset(first.fromX, first.fromY + 160f)
        }
        pos = start
        visible = true
        appear.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        repeat(2) {
            for (step in plan) {
                when (step) {
                    is HintStep.Tap -> {
                        glide(pos, Offset(step.x, step.y), 520) { pos = it }
                        repeat(step.times) {
                            press.animateTo(1f, tween(140)); ripple.snapTo(0f)
                            press.animateTo(0f, tween(160)); ripple.animateTo(1f, tween(420, easing = LinearEasing))
                        }
                    }
                    is HintStep.Drag -> {
                        glide(pos, Offset(step.fromX, step.fromY), 520) { pos = it }
                        press.animateTo(1f, tween(160))
                        glide(pos, Offset(step.toX, step.toY), 1000) { pos = it }
                        press.animateTo(0f, tween(160))
                        ripple.snapTo(0f); ripple.animateTo(1f, tween(420))
                    }
                    is HintStep.Sweep -> {
                        glide(pos, Offset(step.fromX, step.fromY), 520) { pos = it }
                        glide(pos, Offset(step.toX, step.toY), 1500) { pos = it }
                    }
                }
                delay(250)
            }
            delay(400)
        }
        appear.animateTo(0f, tween(250))
        visible = false
    }
    if (!visible) return
    val rippleSize = 150
    Box(Modifier.fillMaxSize()) {
        if (ripple.value in 0.01f..0.99f) {
            Box(
                Modifier.offset { IntOffset(((pos.x - rippleSize / 2f) * unitPx).roundToInt(), ((pos.y - rippleSize / 2f) * unitPx).roundToInt()) }
                    .size(rippleSize.u)
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2f * (0.3f + 0.7f * ripple.value)
                    drawCircle(Color.White.copy(alpha = (1f - ripple.value) * .9f), r, style = Stroke(size.minDimension * .05f))
                }
            }
        }
        // The fingertip sits at the top-left of the glyph so pos is exactly where it "touches".
        Box(
            Modifier.offset { IntOffset(((pos.x - 22f) * unitPx).roundToInt(), ((pos.y - 6f) * unitPx).roundToInt()) }
                .size(110.u, 130.u)
                .graphicsLayer {
                    val s = appear.value * (1f - .12f * press.value)
                    scaleX = s; scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.2f, .05f)
                    rotationZ = -12f + 6f * press.value
                }
        ) { PointingHand(Modifier.fillMaxSize()) }
    }
}

private suspend fun glide(from: Offset, to: Offset, ms: Int, onFrame: (Offset) -> Unit) {
    val t = Animatable(0f)
    t.animateTo(1f, tween(ms, easing = FastOutSlowInEasing)) { onFrame(from + (to - from) * value) }
}

@Composable
private fun PointingHand(modifier: Modifier) {
    Canvas(modifier) {
        val k = size.width / 110f
        val ink = Color(0xFF1E2A4A)
        val glove = Color.White
        val outline = Stroke(5f * k, join = StrokeJoin.Round)
        // Pointing finger.
        val finger = Path().apply {
            moveTo(10f * k, 14f * k); quadraticTo(22f * k, 0f, 34f * k, 12f * k)
            lineTo(44f * k, 62f * k); lineTo(24f * k, 68f * k); close()
        }
        // Palm with folded fingers.
        val palm = Path().apply {
            moveTo(22f * k, 58f * k); quadraticTo(36f * k, 48f * k, 52f * k, 52f * k)
            quadraticTo(70f * k, 48f * k, 80f * k, 58f * k); quadraticTo(96f * k, 60f * k, 98f * k, 78f * k)
            quadraticTo(100f * k, 112f * k, 66f * k, 124f * k); quadraticTo(36f * k, 128f * k, 26f * k, 104f * k)
            quadraticTo(14f * k, 84f * k, 22f * k, 58f * k); close()
        }
        drawPath(palm, Color(0x33000000), alpha = 1f)
        drawPath(finger, glove); drawPath(finger, ink, style = outline)
        drawPath(palm, glove); drawPath(palm, ink, style = outline)
        drawLine(ink, Offset(52f * k, 60f * k), Offset(56f * k, 84f * k), 4f * k)
        drawLine(ink, Offset(70f * k, 62f * k), Offset(74f * k, 86f * k), 4f * k)
        drawOval(Color(0xFFFFC23D), Offset(56f * k, 98f * k), Size(26f * k, 12f * k))
    }
}
