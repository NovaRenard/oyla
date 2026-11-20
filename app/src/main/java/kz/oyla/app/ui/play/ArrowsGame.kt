package kz.oyla.app.ui.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.oyla.app.ui.session.ExerciseUiState
import kotlin.math.roundToInt

/** Board and palette geometry shared with the hint hand. */
object ArrowsBoard {
    const val LEFT = 150f
    const val TOP = 175f
    const val SIDE = 570f
    const val PALETTE_LEFT = 805f
    const val PALETTE_TOP = 235f
    const val BUTTON = 110f
    const val BUTTON_GAP = 14f
    const val GO_TOP = 625f

    fun cell(gridSize: Int): Float = SIDE / gridSize.coerceAtLeast(1)
    fun corner(index: Int, gridSize: Int): Offset = Offset(LEFT + (index % gridSize) * cell(gridSize), TOP + (index / gridSize) * cell(gridSize))
    fun center(index: Int, gridSize: Int): Offset = corner(index, gridSize) + Offset(cell(gridSize) / 2f, cell(gridSize) / 2f)

    /** Palette as a compass: the arrow sits in the direction it points; the middle is the eraser. */
    fun button(dir: ArrowDir?): Offset {
        val col = 1 + (dir?.dx ?: 0)
        val row = 1 + (dir?.dy ?: 0)
        return Offset(PALETTE_LEFT + col * (BUTTON + BUTTON_GAP), PALETTE_TOP + row * (BUTTON + BUTTON_GAP))
    }
}

/**
 * ARROWS: put arrows on the grid and press «Поехали»; Ойлик follows them cell by cell to the
 * next candy. A crash sends ("CRASH", null); reaching the candy sends (candyId, null).
 */
@Composable
fun ArrowsGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, onMove: (String, String?) -> Unit) {
    val exercise = state.exercise ?: return
    val config = exercise.gameConfig
    val n = (config?.gridSize ?: 4).coerceIn(3, 8)
    val blocked = config?.blockedCells.orEmpty().toSet()
    val diagonals = config?.diagonals == true
    val candies = exercise.options.sortedBy { it.position }
    val collectedLocal = remember(state.sessionExerciseId) { mutableStateListOf<String>() }
    val collected = doneIds(state, collectedLocal)
    val remaining = remainingCandies(candies, collected)
    val next = remaining.firstOrNull()
    val restCell = arrowsStartCell(candies, collected, (config?.startCell ?: 0).coerceIn(0, n * n - 1))
    val arrows = remember(state.sessionExerciseId) { mutableStateMapOf<Int, ArrowDir>() }
    var selected by remember(state.sessionExerciseId) { mutableStateOf<ArrowDir?>(null) }
    var erasing by remember(state.sessionExerciseId) { mutableStateOf(false) }
    var running by remember(state.sessionExerciseId) { mutableStateOf(false) }
    var crashTick by remember(state.sessionExerciseId) { mutableIntStateOf(0) }
    var startShake by remember(state.sessionExerciseId) { mutableIntStateOf(0) }
    var mood by remember(state.sessionExerciseId) { mutableStateOf(MascotMood.NORMAL) }
    val cell = ArrowsBoard.cell(n)
    val unitPx = LocalPlay.current.unit * LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val mascot = remember(state.sessionExerciseId) { Animatable(ArrowsBoard.center(restCell, n), Offset.VectorConverter) }
    LaunchedEffect(restCell, running) { if (!running) mascot.animateTo(ArrowsBoard.center(restCell, n), spring(dampingRatio = .7f, stiffness = Spring.StiffnessLow)) }
    val done = candies.isNotEmpty() && remaining.isEmpty()
    val canEdit = interactive && !running && !done
    val route = if (specialistView && next?.cellIndex != null) arrowsShortestPath(n, restCell, next.cellIndex, blocked, diagonals) else null

    fun go() {
        val target = next ?: return
        val targetCell = target.cellIndex ?: return
        val run = simulateArrows(n, restCell, targetCell, arrows.toMap(), blocked)
        if (run.outcome == ArrowOutcome.NO_ARROW && run.cells.size == 1) { startShake++; return }
        running = true
        scope.launch {
            mood = MascotMood.TALKING
            for (c in run.cells.drop(1)) mascot.animateTo(ArrowsBoard.center(c, n), tween(330, easing = FastOutSlowInEasing))
            if (!run.crashed) {
                mood = MascotMood.HAPPY
                onMove(target.id, null)
                collectedLocal += target.id
                arrows.clear()
                delay(900)
            } else {
                mood = MascotMood.THINKING
                run.bump?.let { dir ->
                    val here = mascot.value
                    mascot.animateTo(here + Offset(dir.dx * cell * .35f, dir.dy * cell * .35f), tween(160))
                    mascot.animateTo(here, tween(220))
                }
                crashTick++
                onMove("CRASH", null)
                delay(900)
            }
            mood = MascotMood.NORMAL
            running = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        At((ArrowsBoard.LEFT - 16).toInt(), (ArrowsBoard.TOP - 16).toInt(), (ArrowsBoard.SIDE + 32).toInt(), (ArrowsBoard.SIDE + 32).toInt()) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(36.u)).background(Color(0xFF8CCB74)))
        }
        for (index in 0 until n * n) {
            val corner = ArrowsBoard.corner(index, n)
            val isStone = index in blocked
            val arrow = arrows[index]
            At(corner.x.toInt(), corner.y.toInt(), cell.toInt(), cell.toInt(), if (index == restCell) Modifier.wiggle(startShake) else Modifier) {
                Box(
                    Modifier.fillMaxSize().padding(4.u).clip(RoundedCornerShape(18.u))
                        .background(if ((index / n + index % n) % 2 == 0) Color(0xFFF4FBE9) else Color(0xFFE6F5D6))
                        .semantics { contentDescription = "клетка ${index / n + 1}-${index % n + 1}" }
                        .clickable(interactionSource = null, indication = null, enabled = canEdit && !isStone, role = Role.Button) {
                            when {
                                erasing -> arrows.remove(index)
                                selected == null -> arrows.remove(index)
                                arrow == selected -> arrows.remove(index)
                                else -> arrows[index] = selected!!
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isStone -> StoneGlyph(Modifier.fillMaxSize().padding((cell * .1f).toInt().u))
                        arrow != null -> ArrowTile(arrow, Modifier.fillMaxSize().padding((cell * .12f).toInt().u).popIn("a$index$arrow"))
                    }
                }
            }
        }
        route?.let { path ->
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / 1280f
                val line = Path().apply {
                    path.forEachIndexed { i, c -> val p = ArrowsBoard.center(c, n) * k; if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                }
                drawPath(line, Play.Coral, style = Stroke(10f * k, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * k, 22f * k))))
            }
        }
        candies.forEach { candy ->
            val c = candy.cellIndex ?: return@forEach
            if (candy.id in collected) return@forEach
            val corner = ArrowsBoard.corner(c, n)
            val isNext = candy.id == next?.id
            At(corner.x.toInt(), corner.y.toInt(), cell.toInt(), cell.toInt()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (isNext) {
                        AssetPicture("exercise_candy", Modifier.size((cell * .8f).toInt().u).pulsing(1f, 1.1f, 1200), "конфета")
                    } else {
                        Box(Modifier.size((cell * .34f).toInt().u).clip(CircleShape).background(Color(0xFFFFD0E0)), contentAlignment = Alignment.Center) {
                            Text("${candy.position}", color = Color(0xFFC2185B), fontSize = (cell * .2f).toInt().us, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
        val mascotSide = cell * .86f
        Box(
            Modifier.zIndex(5f)
                .offset { IntOffset(((mascot.value.x - mascotSide / 2f) * unitPx).roundToInt(), ((mascot.value.y - mascotSide / 2f) * unitPx).roundToInt()) }
                .size(mascotSide.u)
                .wiggle(crashTick)
        ) {
            Mascot(Modifier.fillMaxSize(), mood = mood)
        }
        collectedLocal.lastOrNull()?.let { id -> At(0, 0, 1280, 800) { StarBurst(id, Modifier.fillMaxSize()) } }

        // Collected candies.
        At(ArrowsBoard.PALETTE_LEFT.toInt() - 20, 160, 400, 60) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.u, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                candies.forEach { candy ->
                    Box(Modifier.size(56.u).clip(CircleShape).background(Color.White.copy(alpha = .85f)), contentAlignment = Alignment.Center) {
                        if (candy.id in collected) AssetPicture("exercise_candy", Modifier.size(46.u).popIn(candy.id))
                    }
                }
            }
        }
        // Palette.
        val dirs = ArrowDir.entries.filter { diagonals || !it.diagonal }
        (dirs + listOf<ArrowDir?>(null)).forEach { dir ->
            val p = ArrowsBoard.button(dir)
            val isSelected = if (dir == null) erasing else selected == dir && !erasing
            At(p.x.toInt(), p.y.toInt(), ArrowsBoard.BUTTON.toInt(), ArrowsBoard.BUTTON.toInt(), if (isSelected) Modifier.pulsing(1f, 1.06f, 1000) else Modifier) {
                ChunkyButton(
                    onClick = { if (dir == null) { erasing = true; selected = null } else { erasing = false; selected = dir } },
                    color = if (isSelected) Play.Sun else Color.White, lipColor = if (isSelected) Play.SunDeep else Color(0x59503C78),
                    modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(28.u), depth = 7, enabled = canEdit,
                    description = if (dir == null) "стереть стрелку" else "стрелка"
                ) {
                    if (dir == null) EraserGlyph(Modifier.size(64.u)) else ArrowTile(dir, Modifier.size(78.u))
                }
            }
        }
        At(ArrowsBoard.PALETTE_LEFT.toInt(), ArrowsBoard.GO_TOP.toInt(), 358, 110, if (canEdit && arrows.isNotEmpty()) Modifier.pulsing(1f, 1.04f, 1400) else Modifier) {
            ChunkyButton({ go() }, Play.Grass, Play.GrassDeep, Modifier.fillMaxSize(), RoundedCornerShape(40.u), depth = 9, enabled = canEdit, description = "Поехали") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.u)) {
                    Text("Поехали", color = Color.White, fontSize = 42.us, fontWeight = FontWeight.Black)
                    ArrowGlyph(Modifier.size(48.u))
                }
            }
        }
        if (specialistView) {
            At(ArrowsBoard.PALETTE_LEFT.toInt() - 20, 745, 400, 46) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Пунктир — короткий путь", color = Play.Ink, fontSize = 24.us, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** A chunky arrow drawn pointing in [dir] (rotation happens inside the canvas, not in a layer). */
@Composable
fun ArrowTile(dir: ArrowDir, modifier: Modifier = Modifier, color: Color = Play.Blue) {
    Canvas(modifier) {
        val w = size.minDimension
        rotate(dir.degrees) {
            val c = center
            val path = Path().apply {
                moveTo(c.x - w * .38f, c.y - w * .12f); lineTo(c.x + w * .02f, c.y - w * .12f); lineTo(c.x + w * .02f, c.y - w * .3f)
                lineTo(c.x + w * .4f, c.y); lineTo(c.x + w * .02f, c.y + w * .3f); lineTo(c.x + w * .02f, c.y + w * .12f)
                lineTo(c.x - w * .38f, c.y + w * .12f); close()
            }
            drawPath(path, color)
            drawPath(path, Color(0xFF1D4F8A), style = Stroke(w * .04f, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun StoneGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawOval(Color(0x33000000), Offset(w * .08f, h * .7f), Size(w * .84f, h * .2f))
        drawOval(Color(0xFF8A94A6), Offset(w * .06f, h * .3f), Size(w * .62f, h * .56f))
        drawOval(Color(0xFFA7B0C0), Offset(w * .4f, h * .2f), Size(w * .54f, h * .6f))
        drawOval(Color(0x66FFFFFF), Offset(w * .52f, h * .28f), Size(w * .16f, h * .1f))
    }
}

@Composable
private fun EraserGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        rotate(-35f) {
            drawRoundRect(Play.Pink, Offset(w * .12f, h * .34f), Size(w * .76f, h * .32f), androidx.compose.ui.geometry.CornerRadius(w * .08f))
            drawRoundRect(Play.Blue, Offset(w * .12f, h * .34f), Size(w * .3f, h * .32f), androidx.compose.ui.geometry.CornerRadius(w * .08f))
        }
    }
}
