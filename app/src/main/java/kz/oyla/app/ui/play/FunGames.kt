package kz.oyla.app.ui.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.oyla.app.ui.session.ExerciseUiModel
import kz.oyla.app.ui.session.ExerciseUiState
import kz.oyla.app.ui.session.ExerciseUiStatus
import kz.oyla.app.ui.session.exerciseDrawableFor
import kz.oyla.app.ui.session.hasLocalExerciseDrawable
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Picture from gameConfig (local key or uploaded image), used by SYLLABLES and COUNT. */
@Composable
private fun ConfigPicture(exercise: ExerciseUiModel, mediaToken: String?, modifier: Modifier, silhouette: Boolean = false) {
    val config = exercise.gameConfig
    val filter = if (silhouette) ColorFilter.tint(Color(0x4D1E1450)) else null
    when {
        config?.imageUrl != null -> AsyncImage(
            ImageRequest.Builder(LocalContext.current).data(config.imageUrl).apply { mediaToken?.takeIf { it.isNotBlank() }?.let { addHeader("Authorization", "Bearer $it") } }.build(),
            exercise.title, modifier, contentScale = ContentScale.Fit, colorFilter = filter
        )
        hasLocalExerciseDrawable(config?.imageAssetKey) ->
            Image(painterResource(exerciseDrawableFor(config!!.imageAssetKey!!)), exercise.title, modifier, contentScale = ContentScale.Fit, colorFilter = filter)
        else -> Box(modifier)
    }
}

private val BalloonColors = listOf(Color(0xFFFF6B6B), Color(0xFF9B5DE5), Color(0xFFFFB648), Color(0xFF2EAD5B), Color(0xFF3E8EDE), Color(0xFFFF8FA3), Color(0xFF22C3C9), Color(0xFFF08C00), Color(0xFF7B4FC9))

/** POP: balloons with pictures float up; pop the ones whose word has the sound. */
@Composable
fun PopGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onMove: (String, String?) -> Unit) {
    val exercise = state.exercise ?: return
    val balloons = rememberShuffled(state, exercise.options)
    val poppedLocal = remember(state.sessionExerciseId) { mutableStateListOf<String>() }
    val popped = (state.solvedOptionIds + poppedLocal).toSet()
    val wobble = remember(state.sessionExerciseId) { mutableStateMapOf<String, Int>() }
    val targets = balloons.count { it.isTarget == true }
    val transition = rememberInfiniteTransition(label = "balloons")
    val clock by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "clock")
    val calm = LocalPlay.current.calm
    val unitPx = LocalPlay.current.unit * LocalDensity.current.density
    val spacing = 1080f / balloons.size.coerceAtLeast(1)
    Box(Modifier.fillMaxSize()) {
        balloons.forEachIndexed { index, option ->
            val isPopped = option.id in popped
            val color = BalloonColors[index % BalloonColors.size]
            // Each balloon rises on its own loop (10–13 s) with a gentle sway.
            val period = 10f + (index * 7 % 4)
            val phase = (index * 0.37f) % 1f
            val t = if (calm) .5f else ((clock * 60f / period) + phase) % 1f
            val y = if (specialistView) 260f + (index % 2) * 160f else 820f - t * 1080f
            val x = 100f + index * spacing + (spacing - 170f) / 2f + if (calm || specialistView) 0f else 14f * sin(clock * 60f / 3f * 2f * PI.toFloat() + index)
            val burst = remember(state.sessionExerciseId, option.id) { Animatable(0f) }
            LaunchedEffect(isPopped) { if (isPopped) burst.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
            Box(
                Modifier
                    .offset { IntOffset((x * unitPx).roundToInt(), (y * unitPx).roundToInt()) }
                    .size(170.u, 250.u)
            ) {
                Box(Modifier.fillMaxSize().wiggle(wobble[option.id] ?: 0).graphicsLayer {
                    val b = burst.value
                    val s = if (specialistView) (if (isPopped) .7f else 1f) else if (b < .35f) 1f + b else (1.35f * (1f - (b - .35f) / .65f)).coerceAtLeast(0f)
                    scaleX = s; scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, .4f)
                }) {
                    Box(
                        Modifier.fillMaxSize()
                            .semantics { contentDescription = "шарик: ${option.label}" }
                            .onTouchDown(interactive && !isPopped) {
                                onMove(option.id, null)
                                if (option.isTarget == true) poppedLocal += option.id else wobble[option.id] = (wobble[option.id] ?: 0) + 1
                            }
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val k = size.width / 170f
                            withTransform({ scale(k, k, Offset.Zero) }) {
                                drawPath(Path().apply { moveTo(85f, 196f); quadraticTo(70f, 216f, 88f, 232f); quadraticTo(102f, 244f, 84f, 250f) }, Color(0xFF8A94AD), style = Stroke(3f))
                                drawPath(Path().apply {
                                    moveTo(85f, 8f); cubicTo(30f, 8f, 8f, 60f, 14f, 104f); cubicTo(20f, 150f, 60f, 186f, 85f, 192f)
                                    cubicTo(110f, 186f, 150f, 150f, 156f, 104f); cubicTo(162f, 60f, 140f, 8f, 85f, 8f); close()
                                }, color)
                                drawPath(Path().apply { moveTo(78f, 190f); lineTo(92f, 190f); lineTo(88f, 200f); lineTo(82f, 200f); close() }, color)
                                drawOval(Color(0x59FFFFFF), Offset(40f, 30f), Size(26f, 46f))
                                drawCircle(Color(0xEBFFFFFF), 50f, Offset(85f, 100f))
                            }
                        }
                        Box(Modifier.offset(43.u, 58.u).size(84.u)) { OptionPicture(option, mediaToken, Modifier.fillMaxSize(), textSize = 40) }
                        if (specialistView) SpecialistTag(option.label, option.isTarget == true)
                    }
                }
                if (isPopped && !specialistView) StarBurst(option.id, Modifier.fillMaxSize())
            }
        }
        At(490, 715, 300, 66) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .88f)).padding(horizontal = 14.u, vertical = 9.u),
                horizontalArrangement = Arrangement.spacedBy(10.u)
            ) {
                repeat(targets) { i ->
                    Box(Modifier.size(40.u).clip(CircleShape).background(Color(0xFFE3ECF7)), contentAlignment = Alignment.Center) {
                        if (i < popped.count { id -> balloons.any { it.id == id && it.isTarget == true } }) StarIcon(Play.Sun, Modifier.size(30.u).popIn("pop$i"))
                    }
                }
            }
        }
    }
}

/** SYLLABLES: tap the drum once per syllable; the answer is sent after a short pause. */
@Composable
fun DrumGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onChoice: (String) -> Unit) {
    val exercise = state.exercise ?: return
    val syllables = exercise.gameConfig?.syllables.orEmpty()
    var taps by remember(state.sessionExerciseId) { mutableIntStateOf(0) }
    var hits by remember(state.sessionExerciseId) { mutableIntStateOf(0) }
    val squash = remember(state.sessionExerciseId) { Animatable(0f) }
    val notes = remember(state.sessionExerciseId) { mutableStateListOf<Int>() }
    val solved = state.latestAnswer?.isCorrect == true || state.exerciseStatus == ExerciseUiStatus.COMPLETED
    val wrongAttempt = state.latestAnswer?.takeIf { !it.isCorrect }?.attemptNumber ?: 0
    var shake by remember(state.sessionExerciseId) { mutableIntStateOf(0) }
    LaunchedEffect(wrongAttempt) { if (wrongAttempt > 0) { shake++; delay(500); taps = 0 } }
    // Ойлик first drums the word himself, syllable by syllable; then it is the child's turn.
    var demoShown by remember(state.sessionExerciseId) { mutableIntStateOf(0) }
    var demoDone by remember(state.sessionExerciseId) { mutableStateOf(specialistView || state.answerLog.isNotEmpty()) }
    var cue by remember(state.sessionExerciseId) { mutableStateOf<String?>(null) }
    val running = state.exerciseStatus == ExerciseUiStatus.RUNNING
    LaunchedEffect(state.sessionExerciseId, running) {
        if (demoDone || !running) return@LaunchedEffect
        delay(600)
        cue = "Слушай: " + syllables.joinToString("-") { it.lowercase() }
        syllables.indices.forEach { i -> delay(800); demoShown = i + 1; hits += 1; notes += hits; if (notes.size > 4) notes.removeAt(0) }
        delay(1200)
        demoShown = 0
        cue = "Теперь ты! Стучи на каждый слог"
        demoDone = true
        delay(3000)
        cue = null
    }
    LaunchedEffect(hits) {
        if (hits == 0 || solved) return@LaunchedEffect
        squash.snapTo(0f); squash.animateTo(1f, tween(340, easing = LinearEasing))
    }
    LaunchedEffect(taps, hits) {
        if (taps == 0 || solved || specialistView || !demoDone) return@LaunchedEffect
        // Generous pause: children with motor difficulties drum slowly.
        delay(2200)
        onChoice(taps.toString())
    }
    val shown = when {
        specialistView || solved -> syllables.size
        !demoDone -> demoShown
        else -> taps.coerceAtMost(syllables.size)
    }
    val chipColors = listOf(Color(0xFF3E8EDE), Color(0xFFF26B3A), Color(0xFF1E8A4C), Color(0xFF7B4FC9), Color(0xFF0E8C93), Color(0xFFD9730D))
    Box(Modifier.fillMaxSize()) {
        At(100, 180, 300, 300, Modifier.floating(amplitude = 8f, periodMs = 3000)) {
            PictureCard(Modifier.fillMaxSize(), radius = 56, lip = Color(0x2E8C4614)) {
                ConfigPicture(exercise, mediaToken, Modifier.fillMaxSize().padding(36.u))
            }
        }
        Row(Modifier.offset(470.u, 200.u).wiggle(shake), horizontalArrangement = Arrangement.spacedBy(18.u)) {
            syllables.forEachIndexed { i, text ->
                if (i < shown) {
                    Box(
                        Modifier.size(150.u, 130.u).popIn("chip$i-$taps").clip(RoundedCornerShape(32.u)).background(chipColors[i % chipColors.size]),
                        contentAlignment = Alignment.Center
                    ) { Text(text.uppercase(), color = Color.White, fontSize = 60.us, fontWeight = FontWeight.Black) }
                } else {
                    Box(Modifier.size(150.u, 130.u).clip(RoundedCornerShape(32.u)).border(6.u, Color(0xFFF2B98C), RoundedCornerShape(32.u)))
                }
            }
        }
        cue?.let { text ->
            At(440, 360, 820, 80) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    Text(text, color = Play.Ink, fontSize = 32.us, fontWeight = FontWeight.Black,
                        modifier = Modifier.popIn(text).clip(RoundedCornerShape(30.u)).background(Color.White).padding(horizontal = 26.u, vertical = 10.u))
                }
            }
        }
        if (!demoDone && !specialistView) {
            At(990, 430, 170, 170, Modifier.floating(amplitude = 14f, periodMs = 700)) { Mascot(Modifier.fillMaxSize(), mood = MascotMood.TALKING) }
        }
        // The drum squashes on every hit and notes fly up.
        At(560, 380, 420, 380) {
            Box(
                Modifier.fillMaxSize()
                    .semantics { contentDescription = "Барабан" }
                    .onTouchDown(interactive && !solved && demoDone) {
                        taps += 1; hits += 1; notes += hits
                        if (notes.size > 4) notes.removeAt(0)
                    }
                    .graphicsLayer {
                        val v = squash.value
                        val sx = if (v < .3f) 1f + .1f * (v / .3f) else if (v < .6f) 1.1f - .14f * ((v - .3f) / .3f) else .96f + .04f * ((v - .6f) / .4f)
                        val sy = if (v < .3f) 1f - .14f * (v / .3f) else if (v < .6f) .86f + .19f * ((v - .3f) / .3f) else 1.05f - .05f * ((v - .6f) / .4f)
                        scaleX = if (hits == 0) 1f else sx; scaleY = if (hits == 0) 1f else sy
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, .85f)
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val k = size.width / 420f
                    withTransform({ scale(k, k, Offset.Zero) }) {
                        drawOval(Color(0x2E783C0A), Offset(40f, 308f), Size(340f, 44f))
                        val body = Path().apply { moveTo(50f, 150f); lineTo(50f, 290f); quadraticTo(210f, 360f, 370f, 290f); lineTo(370f, 150f); close() }
                        drawPath(body, Color(0xFFE5533D))
                        drawPath(Path().apply { moveTo(50f, 170f); lineTo(130f, 300f); lineTo(210f, 170f); lineTo(290f, 310f); lineTo(370f, 170f) }, Color(0xFFFFD27D), style = Stroke(10f, join = StrokeJoin.Round))
                        drawOval(Color(0xFFFFF6E5), Offset(50f, 94f), Size(320f, 112f))
                        drawOval(Color(0xFFB83A26), Offset(50f, 94f), Size(320f, 112f), style = Stroke(8f))
                        drawOval(Color(0x80FFFFFF), Offset(100f, 114f), Size(220f, 72f))
                    }
                }
            }
            notes.forEach { id -> FlyingNote(id) }
        }
    }
}

@Composable
private fun FlyingNote(id: Int) {
    val p = remember(id) { Animatable(0f) }
    LaunchedEffect(id) { p.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val dir = if (id % 2 == 0) 1f else -1f
    val unitPx = LocalPlay.current.unit * LocalDensity.current.density
    if (p.value < 1f) Text(
        "♪", Modifier.offset(190.u, 90.u).graphicsLayer {
            translationX = dir * (40f + id * 17 % 60) * p.value * unitPx
            translationY = -140f * p.value * unitPx
            rotationZ = 25f * p.value
            val s = 1f - p.value * .5f; scaleX = s; scaleY = s
        },
        color = Color.White, fontSize = 52.us, fontWeight = FontWeight.Black
    )
}

/** SHADOW: drag each picture onto its silhouette; a wrong shadow shakes and the picture slides back. */
@Composable
fun ShadowGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onMove: (String, String?) -> Unit) {
    val exercise = state.exercise ?: return
    val pieces = exercise.options
    val slots = remember(state.sessionExerciseId, pieces) { shadowSlotsFor(state.sessionExerciseId, pieces) }
    val placedLocal = remember(state.sessionExerciseId) { mutableStateListOf<String>() }
    val placed = (state.solvedOptionIds + placedLocal).toSet()
    val slotShake = remember(state.sessionExerciseId) { mutableStateMapOf<String, Int>() }
    val unitPx = LocalPlay.current.unit * LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val layout = shadowLayout(pieces.size)
    val side = layout.side
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.offset(640.u, 0.u).size(640.u, 800.u).background(Color(0xFFDCD2FF)))
        slots.forEachIndexed { i, slot ->
            val filled = slot.id in placed
            At(880, layout.rowY(i), side, side, Modifier.wiggle(slotShake[slot.id] ?: 0)) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(36.u)).background(Color.White.copy(alpha = .45f))
                        .border(5.u, Color(0x405040A0), RoundedCornerShape(36.u))
                        .semantics { contentDescription = "тень: ${slot.label}" },
                    contentAlignment = Alignment.Center
                ) {
                    if (!filled) Box(Modifier.fillMaxSize().padding(22.u)) { SilhouetteOf(slot, mediaToken) }
                }
            }
        }
        pieces.forEachIndexed { i, piece ->
            val target = slots.indexOfFirst { it.id == piece.id }
            val isPlaced = piece.id in placed
            val home = Offset(220f, layout.rowY(i).toFloat())
            val dest = Offset(880f, layout.rowY(target).toFloat())
            val pos = remember(state.sessionExerciseId, piece.id) { Animatable(if (isPlaced) dest else home, Offset.VectorConverter) }
            var dragging by remember(state.sessionExerciseId, piece.id) { mutableStateOf(false) }
            LaunchedEffect(isPlaced, dragging) {
                if (!dragging) pos.animateTo(if (isPlaced) dest else home, spring(dampingRatio = .6f, stiffness = Spring.StiffnessLow))
            }
            val lift by animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = .45f), label = "lift")
            Box(
                Modifier.offset { IntOffset((pos.value.x * unitPx).roundToInt(), (pos.value.y * unitPx).roundToInt()) }
                    .size(side.u)
                    .graphicsLayer { val s = 1f + .08f * lift; scaleX = s; scaleY = s; rotationZ = -3f * lift }
                    .semantics { contentDescription = piece.label }
                    .pointerInput(interactive, isPlaced) {
                        if (!interactive || isPlaced) return@pointerInput
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDrag = { change, drag -> change.consume(); scope.launch { pos.snapTo(pos.value + drag / unitPx) } },
                            onDragEnd = {
                                val center = pos.value + Offset(side / 2f, side / 2f)
                                val hit = slots.indices.firstOrNull { k ->
                                    center.x in 860f..(900f + side) && center.y in (layout.rowY(k) - 20f)..(layout.rowY(k) + side + 20f)
                                }?.let { slots[it] }
                                if (hit != null && hit.id !in placed) {
                                    onMove(piece.id, hit.id)
                                    if (hit.id == piece.id) placedLocal += piece.id else slotShake[hit.id] = (slotShake[hit.id] ?: 0) + 1
                                }
                                dragging = false
                            },
                            onDragCancel = { dragging = false }
                        )
                    }
            ) {
                PictureCard(Modifier.fillMaxSize(), radius = 36, lip = Color(0x33503C78), ring = if (dragging) Play.Sun else null) {
                    OptionPicture(piece, mediaToken, Modifier.fillMaxSize().padding(22.u), textSize = 80)
                }
                if (isPlaced && !specialistView) StarBurst(piece.id, Modifier.fillMaxSize())
            }
        }
    }
}

/** Dark, translucent copy of the picture: its "shadow". */
@Composable
private fun SilhouetteOf(option: kz.oyla.app.ui.session.ExerciseOptionUiModel, mediaToken: String?) {
    val filter = ColorFilter.tint(Color(0x521E1450))
    when (val image = option.image) {
        is kz.oyla.app.ui.session.ExerciseImage.LocalAsset -> Image(painterResource(exerciseDrawableFor(image.key)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = filter)
        is kz.oyla.app.ui.session.ExerciseImage.RemoteAsset -> AsyncImage(
            ImageRequest.Builder(LocalContext.current).data(image.url).apply { mediaToken?.takeIf { it.isNotBlank() }?.let { addHeader("Authorization", "Bearer $it") } }.build(),
            null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = filter
        )
    }
}

/** COUNT: fish swim in the aquarium; tap the number bubble. */
@Composable
fun CountGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onChoice: (String) -> Unit) {
    val exercise = state.exercise ?: return
    val count = (exercise.gameConfig?.countValue ?: 3).coerceIn(1, 10)
    val numbers = exercise.options.sortedBy { it.label.toIntOrNull() ?: 0 }
    val solvedId = state.latestAnswer?.takeIf { it.isCorrect }?.selectedOptionId
    val wrongId = state.latestAnswer?.takeIf { !it.isCorrect }?.selectedOptionId
    val wrongTick = state.latestAnswer?.takeIf { !it.isCorrect }?.attemptNumber ?: 0
    val transition = rememberInfiniteTransition(label = "aquarium")
    val clock by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "aq")
    val calm = LocalPlay.current.calm
    val unitPx = LocalPlay.current.unit * LocalDensity.current.density
    Box(Modifier.fillMaxSize()) {
        At(120, 140, 1040, 460) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(48.u))
                    .background(Brush.verticalGradient(listOf(Color(0xFF7FD3F7), Color(0xFF3FA7E0))))
                    .border(14.u, Color(0x99FFFFFF), RoundedCornerShape(48.u))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val k = size.width / 1040f
                    withTransform({ scale(k, k, Offset.Zero) }) {
                        drawPath(Path().apply { moveTo(0f, 420f); quadraticTo(260f, 380f, 520f, 420f); quadraticTo(780f, 460f, 1040f, 410f); lineTo(1040f, 460f); lineTo(0f, 460f); close() }, Color(0xFFF2D38E))
                    }
                }
                repeat(count) { i ->
                    val lane = 40f + (i % 4) * 85f + (i / 4) * 30f
                    val span = 820f - (i % 3) * 120f
                    val period = 8f + (i * 3 % 5)
                    val t = if (calm || specialistView) (i * .23f) % 1f else ((clock * 60f / period) + i * .29f) % 1f
                    val forward = t < .5f
                    val p = if (forward) t * 2f else 2f - t * 2f
                    val x = 30f + p * (span - 150f) * 1f
                    val hop = remember(state.sessionExerciseId, i) { Animatable(0f) }
                    LaunchedEffect(solvedId) {
                        if (solvedId != null) { delay(i * 350L); hop.animateTo(1f, tween(800, easing = LinearEasing)) }
                    }
                    val h = hop.value
                    val jump = when { h <= 0f -> 0f; h < .3f -> 50f * h / .3f; h < .6f -> 50f * (1 - (h - .3f) / .3f); h < .8f -> 16f * (h - .6f) / .2f; else -> 16f * (1 - (h - .8f) / .2f) }
                    Box(
                        Modifier.offset { IntOffset((x * unitPx).roundToInt(), ((lane - jump) * unitPx).roundToInt()) }.size(150.u, 110.u)
                    ) {
                        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = if (forward) -1f else 1f }) {
                            ConfigPicture(exercise, mediaToken, Modifier.fillMaxSize())
                        }
                        if (solvedId != null && h > 0f) {
                            Box(Modifier.offset(50.u, (-30).u).size(50.u).popIn("n$i").clip(CircleShape).background(Play.Sun), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", color = Color.White, fontSize = 30.us, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.offset(0.u, 628.u).size(1280.u, 140.u), horizontalArrangement = Arrangement.spacedBy(34.u, Alignment.CenterHorizontally)) {
            numbers.forEachIndexed { i, option ->
                val isSolved = option.id == solvedId
                val grow by animateFloatAsState(if (isSolved) 1.15f else 1f, spring(dampingRatio = .4f), label = "grow")
                Box(
                    Modifier.size(130.u).floating(amplitude = 10f, periodMs = 2800, delayMs = i * 400)
                        .wiggle(if (option.id == wrongId) wrongTick else 0)
                        .graphicsLayer { scaleX = grow; scaleY = grow }
                        .clip(CircleShape)
                        .background(
                            if (isSolved || (specialistView && option.id == exercise.correctOptionId)) Brush.radialGradient(listOf(Color(0xFFFFF6C8), Color(0xFFFFD54F), Color(0xFFFFB300)))
                            else Brush.radialGradient(listOf(Color.White, Color(0xFFD8F1FF), Color(0xFFA9DCF7)))
                        )
                        .semantics { contentDescription = option.label }
                        .clickable(interactionSource = null, indication = null, enabled = interactive && solvedId == null, role = Role.Button) { onChoice(option.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(option.label, color = if (isSolved) Color(0xFF7A4B00) else Color(0xFF1E5A8A), fontSize = 72.us, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}
