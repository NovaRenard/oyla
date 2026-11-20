package kz.oyla.app.ui.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.oyla.app.ui.session.ExerciseImage
import kz.oyla.app.ui.session.ExerciseOptionUiModel
import kz.oyla.app.ui.session.ExerciseUiState
import kz.oyla.app.ui.session.ExerciseUiStatus
import kz.oyla.app.ui.session.exerciseDrawableFor
import kotlin.math.ceil
import kotlin.random.Random

/** Picture of an option, or its label in big letters when the option has no picture. */
@Composable
fun OptionPicture(option: ExerciseOptionUiModel, mediaToken: String?, modifier: Modifier = Modifier, textSize: Int = 110, textColor: Color = Play.Ink) {
    if (!option.hasImage) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(option.label, color = textColor, fontSize = textSize.us, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        }
        return
    }
    when (val image = option.image) {
        is ExerciseImage.LocalAsset -> Image(painterResource(exerciseDrawableFor(image.key)), option.label, modifier, contentScale = ContentScale.Fit)
        is ExerciseImage.RemoteAsset -> {
            val context = LocalContext.current
            val request = ImageRequest.Builder(context).data(image.url).crossfade(true).apply {
                mediaToken?.takeIf { it.isNotBlank() }?.let { addHeader("Authorization", "Bearer $it") }
                placeholder(exerciseDrawableFor("")); error(exerciseDrawableFor(""))
            }.build()
            AsyncImage(request, option.label, modifier, contentScale = ContentScale.Fit)
        }
    }
}

/** Places [content] at artboard coordinates. */
@Composable
fun At(x: Int, y: Int, w: Int, h: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.offset(x.u, y.u).size(w.u, h.u)) { content() }
}

private data class Cell(val x: Int, val y: Int, val w: Int, val h: Int)

/** Centred grid of equal cells inside a box of the artboard. */
private fun gridCells(count: Int, cols: Int, left: Int, top: Int, width: Int, height: Int, gap: Int, maxSide: Int = 300): List<Cell> {
    val rows = ceil(count / cols.toFloat()).toInt().coerceAtLeast(1)
    val cellW = ((width - (cols - 1) * gap) / cols).coerceAtMost(maxSide)
    val cellH = ((height - (rows - 1) * gap) / rows).coerceAtMost(maxSide)
    val usedW = cols * cellW + (cols - 1) * gap
    val usedH = rows * cellH + (rows - 1) * gap
    val startX = left + (width - usedW) / 2
    val startY = top + (height - usedH) / 2
    return (0 until count).map { i ->
        val row = i / cols
        val inRow = if (row == rows - 1) count - row * cols else cols
        val rowOffset = (cols - inRow) * (cellW + gap) / 2
        Cell(startX + rowOffset + (i % cols) * (cellW + gap), startY + row * (cellH + gap), cellW, cellH)
    }
}

/**
 * Content stores the correct answer first; show options in a stable per-lesson order so the
 * answer isn't always in the same place, and both tablets see the same layout.
 */
@Composable
fun rememberShuffled(state: ExerciseUiState, options: List<ExerciseOptionUiModel>): List<ExerciseOptionUiModel> =
    remember(state.sessionExerciseId, options) { options.shuffled(Random((state.sessionExerciseId ?: "").hashCode() xor 0x5f3759df)) }

/** Remembers the wrong option the child just tapped so it can shake. */
@Composable
private fun rememberWrongShake(state: ExerciseUiState): Pair<String?, Int> {
    val answer = state.latestAnswer
    return if (answer != null && !answer.isCorrect) answer.selectedOptionId to answer.attemptNumber else null to 0
}

/** SINGLE_CHOICE ("find the sound") and ODD_ONE ("what doesn't belong"). */
@Composable
fun ChoiceGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onChoice: (String) -> Unit) {
    val exercise = state.exercise ?: return
    val options = rememberShuffled(state, exercise.options)
    val (wrongId, wrongTrigger) = rememberWrongShake(state)
    val solvedId = state.latestAnswer?.takeIf { it.isCorrect }?.selectedOptionId
    val oddOne = exercise.activityType == "ODD_ONE"
    val cols = if (options.size <= 4) options.size else 3
    val cells = gridCells(options.size, cols, 110, if (oddOne) 250 else 270, 1060, if (oddOne) 270 else 420, 40, maxSide = 290)
    val unitPx = LocalPlay.current.unit * LocalDensity.current.density
    Box(Modifier.fillMaxSize()) {
        options.forEachIndexed { index, option ->
            val cell = cells[index]
            val isSolved = option.id == solvedId
            val faded = oddOne && solvedId != null && !isSolved
            // Odd one: hop, back up, then drive off while shrinking — it never leaves the stage
            // abruptly. The others cheer once it is gone. No alpha: it renders clipped on Android 9.
            val leave = remember(state.sessionExerciseId) { Animatable(0f) }
            val cheer = remember(state.sessionExerciseId) { Animatable(0f) }
            LaunchedEffect(isSolved, oddOne) { if (isSolved && oddOne) leave.animateTo(1f, tween(1500, easing = LinearEasing)) }
            LaunchedEffect(faded) {
                if (faded) {
                    kotlinx.coroutines.delay(1300L + index * 90L)
                    cheer.animateTo(1f, tween(900, easing = LinearEasing))
                }
            }
            At(cell.x, cell.y, cell.w, cell.h, Modifier.graphicsLayer {
                if (oddOne && isSolved) {
                    val t = leave.value
                    val (dx, dy, rot, s) = when {
                        t < .18f -> { val p = t / .18f; listOf(0f, -34f * p, -6f * p, 1f) }
                        t < .34f -> { val p = (t - .18f) / .16f; listOf(-26f * p, -34f * (1 - p), -6f * (1 - p), 1f) }
                        else -> { val p = (t - .34f) / .66f; listOf(-26f + 406f * p * p, 0f, 0f, 1f - .92f * p) }
                    }
                    translationX = dx * unitPx; translationY = dy * unitPx; rotationZ = rot; scaleX = s; scaleY = s
                } else if (faded) {
                    val c = cheer.value
                    val jump = when {
                        c < .25f -> 28f * (c / .25f)
                        c < .5f -> 28f * (1 - (c - .25f) / .25f)
                        c < .7f -> 14f * ((c - .5f) / .2f)
                        else -> 14f * (1 - (c - .7f) / .3f)
                    }
                    translationY = -jump * unitPx
                }
            }) {
                Box(Modifier.fillMaxSize().floating(amplitude = 12f, periodMs = 3200 + index * 300, delayMs = index * 500)) {
                    PictureCard(
                        Modifier.fillMaxSize()
                            .wiggle(if (option.id == wrongId) wrongTrigger else 0)
                            .semantics { contentDescription = option.label }
                            .clickable(interactionSource = null, indication = null, enabled = interactive && solvedId == null, role = Role.Button) { onChoice(option.id) },
                        ring = when {
                            isSolved && !oddOne -> Play.Sun
                            specialistView && option.id == exercise.correctOptionId -> Play.Grass
                            else -> null
                        }
                    ) {
                        OptionPicture(option, mediaToken, Modifier.fillMaxSize().padding(26.u))
                        if (specialistView) SpecialistTag(option.label, option.id == exercise.correctOptionId)
                    }
                }
            }
        }
        if (oddOne && solvedId != null && !specialistView) {
            val gone = options.firstOrNull { it.id == solvedId }?.label.orEmpty()
            At(250, 590, 780, 130) {
                Box(Modifier.fillMaxSize().popIn(solvedId), contentAlignment = Alignment.Center) {
                    Text("$gone — лишнее!", color = Play.Ink, fontSize = 52.us, fontWeight = FontWeight.Black,
                        modifier = Modifier.clip(RoundedCornerShape(36.u)).background(Color.White).padding(horizontal = 36.u, vertical = 14.u))
                }
            }
        }
    }
}

/** Small caption on cards in the specialist preview so the adult can read what the child sees. */
@Composable
fun SpecialistTag(text: String, highlight: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(10.u), contentAlignment = Alignment.BottomCenter) {
        Text(
            if (highlight) "✓ $text" else text,
            color = if (highlight) Color(0xFF1D7A3E) else Color(0xFF3A4766),
            fontSize = 24.us, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(12.u)).background(Color.White.copy(alpha = .9f)).padding(horizontal = 10.u, vertical = 2.u)
        )
    }
}

/** REMEMBER: show the target for a few seconds, hide it, then find it among similar options. */
@Composable
fun RememberGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onChoice: (String) -> Unit) {
    val exercise = state.exercise ?: return
    val config = exercise.gameConfig
    val target = exercise.options.firstOrNull { it.id == config?.targetOptionId }
    val showMs = (config?.showTargetMs ?: 3000).coerceIn(1000, 10000)
    var replay by remember(state.sessionExerciseId) { mutableIntStateOf(0) }
    var showing by remember(state.sessionExerciseId) { mutableStateOf(target != null && !specialistView) }
    val countdown = remember(state.sessionExerciseId, replay) { Animatable(1f) }
    val running = state.exerciseStatus == ExerciseUiStatus.RUNNING
    LaunchedEffect(state.sessionExerciseId, replay, running) {
        if (target == null || specialistView) { showing = false; return@LaunchedEffect }
        showing = true
        if (!running) return@LaunchedEffect
        countdown.snapTo(1f)
        countdown.animateTo(0f, tween(showMs, easing = LinearEasing))
        showing = false
    }
    val (wrongId, wrongTrigger) = rememberWrongShake(state)
    val solvedId = state.latestAnswer?.takeIf { it.isCorrect }?.selectedOptionId
    if (showing && target != null) {
        Box(Modifier.fillMaxSize()) {
            At(0, 150, 1280, 70) { Text("Запомни!", Modifier.fillMaxWidth(), color = Play.Ink, fontSize = 52.us, fontWeight = FontWeight.Black, textAlign = TextAlign.Center) }
            val ring = 10.u
            At(465, 240, 350, 350, Modifier.popIn(replay)) {
                PictureCard(Modifier.fillMaxSize(), radius = 64, lip = Color(0x2EA02850)) {
                    OptionPicture(target, mediaToken, Modifier.fillMaxSize().padding(50.u), textSize = 230)
                }
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(Color(0xFFFF6F91), -90f, 360f * countdown.value, false, style = Stroke(ring.toPx(), cap = StrokeCap.Round))
                }
            }
        }
        return
    }
    val options = rememberShuffled(state, exercise.options)
    val cols = when (options.size) { in 0..4 -> options.size; 5, 6 -> 3; else -> 3 }
    val cells = gridCells(options.size, cols, 140, 230, 1000, 470, 26, maxSide = 260)
    Box(Modifier.fillMaxSize()) {
        if (specialistView && target != null) {
            At(40, 150, 170, 170) {
                PictureCard(Modifier.fillMaxSize(), radius = 30, ring = Play.Grass) { OptionPicture(target, mediaToken, Modifier.fillMaxSize().padding(26.u), textSize = 90) }
            }
            At(40, 330, 200, 40) { Text("Образец", color = Play.Ink, fontSize = 26.us, fontWeight = FontWeight.Bold) }
        }
        if (!specialistView && target != null) {
            At(1100, 140, 110, 110) {
                ChunkyButton({ replay++ }, Color.White, Color(0x2EA02850), Modifier.fillMaxSize(), CircleShape, depth = 6, enabled = interactive && solvedId == null, description = "Показать ещё раз") {
                    EyeGlyph(Modifier.size(58.u))
                }
            }
        }
        options.forEachIndexed { index, option ->
            val cell = cells[index]
            At(cell.x, cell.y, cell.w, cell.h, Modifier.popIn(option.id to replay, delayMs = index * 60)) {
                PictureCard(
                    Modifier.fillMaxSize().wiggle(if (option.id == wrongId) wrongTrigger else 0)
                        .semantics { contentDescription = option.label }
                        .clickable(interactionSource = null, indication = null, enabled = interactive && solvedId == null, role = Role.Button) { onChoice(option.id) },
                    radius = 36, lip = Color(0x2EA02850),
                    ring = when {
                        option.id == solvedId -> Play.Grass
                        specialistView && option.id == exercise.correctOptionId -> Play.Grass
                        else -> null
                    }
                ) {
                    OptionPicture(option, mediaToken, Modifier.fillMaxSize().padding(30.u), textSize = 120)
                }
            }
        }
    }
}

@Composable
private fun EyeGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * .08f, h * .5f); quadraticTo(w * .5f, h * .08f, w * .92f, h * .5f); quadraticTo(w * .5f, h * .92f, w * .08f, h * .5f); close()
        }
        drawPath(path, Play.Ink, style = Stroke(w * .08f))
        drawCircle(Play.Ink, w * .15f, center)
    }
}

/**
 * MEMORY: open two cards. A pair pops, sparkles and shrinks away in place; a wrong pair
 * shakes and turns back. Every motion has its own layer (position → shake → pop/vanish → flip)
 * so they never fight, and nothing uses alpha (clipped layers on Android 9).
 */
@Composable
fun MemoryGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onMove: (String, String?) -> Unit) {
    val exercise = state.exercise ?: return
    val deck = remember(state.sessionExerciseId, exercise.options) {
        val random = Random((state.sessionExerciseId ?: exercise.id).hashCode())
        exercise.options.flatMap { listOf(it, it) }.shuffled(random)
    }
    val open = remember(state.sessionExerciseId) { mutableStateListOf<Int>() }
    val matchedLocal = remember(state.sessionExerciseId) { mutableStateListOf<String>() }
    val celebrating = remember(state.sessionExerciseId) { mutableStateListOf<String>() }
    val shakes = remember(state.sessionExerciseId) { mutableStateMapOf<Int, Int>() }
    var locked by remember(state.sessionExerciseId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val matched = (state.solvedOptionIds + matchedLocal).toSet()
    val found = exercise.options.count { it.id in matched || it.id in celebrating }
    val cols = when (deck.size) { 4 -> 4; 6 -> 3; 8 -> 4; 10 -> 5; 12 -> 4; 14 -> 7; else -> 6 }
    val cells = gridCells(deck.size, cols, 140, 160, 1000, 565, 24, maxSide = 200)
    Box(Modifier.fillMaxSize()) {
        At(440, 735, 400, 50) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.u, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                exercise.options.indices.forEach { i ->
                    Box(Modifier.size(32.u).clip(CircleShape).background(Color.White.copy(alpha = .22f)), contentAlignment = Alignment.Center) {
                        if (i < found) StarIcon(Play.Sun, Modifier.size(26.u).popIn("star$i"))
                    }
                }
            }
        }
        deck.forEachIndexed { index, option ->
            val cell = cells[index]
            val isCelebrating = option.id in celebrating
            val isGone = option.id in matched && !isCelebrating
            val faceUp = specialistView || index in open || isCelebrating
            // 0 = face up, 1 = face down; a horizontal squash reads as a flip.
            val flip by animateFloatAsState(if (faceUp) 0f else 1f, tween(320, easing = FastOutSlowInEasing), label = "flip")
            val pop by animateFloatAsState(if (isCelebrating) 1.14f else 1f, spring(dampingRatio = .42f, stiffness = Spring.StiffnessMediumLow), label = "pop")
            val vanish by animateFloatAsState(if (isGone) 1f else 0f, tween(380, easing = FastOutSlowInEasing), label = "vanish")
            At(cell.x, cell.y, cell.w, cell.h) {
                Box(Modifier.fillMaxSize().wiggle(shakes[index] ?: 0)) {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        val s = pop * (1f - vanish * (if (specialistView) .15f else 1f))
                        scaleX = s; scaleY = s
                        rotationZ = if (isCelebrating) (pop - 1f) * -20f else 0f
                    }) {
                        val clickable = Modifier.clickable(interactionSource = null, indication = null, enabled = interactive && !locked && !isGone && index !in open, role = Role.Button) {
                            open += index
                            if (open.size == 2) {
                                val ia = open[0]; val ib = open[1]
                                val a = deck[ia]; val b = deck[ib]
                                locked = true
                                onMove(a.id, b.id)
                                scope.launch {
                                    if (a.id == b.id) {
                                        delay(420); celebrating += a.id
                                        delay(820); matchedLocal += a.id; celebrating -= a.id
                                    } else {
                                        delay(650)
                                        shakes[ia] = (shakes[ia] ?: 0) + 1; shakes[ib] = (shakes[ib] ?: 0) + 1
                                        delay(520)
                                    }
                                    open.clear(); locked = false
                                }
                            }
                        }.semantics { contentDescription = if (faceUp) option.label else "закрытая карточка" }
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            scaleX = kotlin.math.abs(kotlin.math.cos(Math.PI * flip)).toFloat().coerceAtLeast(0.02f)
                        }) {
                            if (flip < .5f) {
                                PictureCard(Modifier.fillMaxSize().then(clickable), radius = 32, lip = Color(0x55000000),
                                    ring = if (isCelebrating || (specialistView && isGone)) Play.Sun else null) {
                                    OptionPicture(option, mediaToken, Modifier.fillMaxSize().padding(22.u), textSize = 90)
                                }
                            } else {
                                Box(Modifier.fillMaxSize().then(clickable)) {
                                    Box(Modifier.matchParentSize().offset(y = 8.u).clip(RoundedCornerShape(32.u)).background(Color(0xFF3A3DA8)))
                                    Box(Modifier.matchParentSize().clip(RoundedCornerShape(32.u)).background(Color(0xFF5B5FD9)), contentAlignment = Alignment.Center) {
                                        Box(Modifier.size((cell.w * .52f).u).clip(CircleShape).background(Color(0xFF6E72E6)), contentAlignment = Alignment.Center) {
                                            StarIcon(Color(0xFF8E92F0), Modifier.size((cell.w * .3f).u))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (isCelebrating) StarBurst(option.id + index, Modifier.fillMaxSize())
            }
        }
    }
}
