package kz.oyla.app.ui.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import kz.oyla.app.ui.session.ExerciseUiState
import kz.oyla.app.ui.session.exerciseDrawableFor
import kz.oyla.app.ui.session.hasLocalExerciseDrawable
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

private val BasketA = Rect(150f, 190f, 480f, 490f)
private val BasketB = Rect(800f, 190f, 1130f, 490f)

/** SORT: drag every picture into the basket of its sound. */
@Composable
fun SortGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onMove: (String, String?) -> Unit) {
    val exercise = state.exercise ?: return
    val items = rememberShuffled(state, exercise.options)
    val labels = exercise.gameConfig?.groupLabels.orEmpty()
    val placedLocal = remember(state.sessionExerciseId) { mutableStateListOf<String>() }
    val placed = (state.solvedOptionIds + placedLocal).distinct()
    val wrong = remember(state.sessionExerciseId) { mutableStateMapOf<String, Int>() }
    val unitPx = LocalPlay.current.unit * LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val spacing = 1040f / items.size.coerceAtLeast(1)
    val itemSize = minOf(180f, spacing - 24f)
    Box(Modifier.fillMaxSize()) {
        Basket(BasketA, labels.getOrNull(0) ?: "А", Play.Coral, Color(0xFFE3A15E), Color(0xFFA0602F), Play.CoralDeep)
        Basket(BasketB, labels.getOrNull(1) ?: "Б", Color(0xFF3E7CB1), Color(0xFF7FB7E6), Color(0xFF3E7CB1), Color(0xFF275A85))
        items.forEachIndexed { index, option ->
            val isPlaced = option.id in placed
            val groupOrder = placed.filter { id -> items.firstOrNull { it.id == id }?.groupKey == option.groupKey }.indexOf(option.id)
            val basket = if (option.groupKey == "B") BasketB else BasketA
            val home = if (isPlaced) Offset(basket.left + 30f + (groupOrder % 3) * 98f, basket.top + 140f + (groupOrder / 3) * 98f)
            else Offset(120f + index * spacing + (spacing - itemSize) / 2f, 545f)
            val sizeNow by animateFloatAsState(if (isPlaced) 92f else itemSize, spring(dampingRatio = .6f), label = "size")
            val position = remember(state.sessionExerciseId, option.id) { Animatable(home, Offset.VectorConverter) }
            var dragging by remember(state.sessionExerciseId, option.id) { mutableStateOf(false) }
            LaunchedEffect(home, dragging) { if (!dragging) position.animateTo(home, spring(dampingRatio = .55f, stiffness = Spring.StiffnessLow)) }
            Box(
                Modifier
                    .offset { IntOffset((position.value.x * unitPx).roundToInt(), (position.value.y * unitPx).roundToInt()) }
                    .size(sizeNow.u)
                    .graphicsLayer { if (dragging) { scaleX = 1.08f; scaleY = 1.08f } }
                    .wiggle(wrong[option.id] ?: 0)
                    .semantics { contentDescription = option.label }
                    .pointerInput(interactive, isPlaced) {
                        if (!interactive || isPlaced) return@pointerInput
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDrag = { change, drag -> change.consume(); scope.launch { position.snapTo(position.value + drag / unitPx) } },
                            onDragEnd = {
                                val center = position.value + Offset(sizeNow / 2f, sizeNow / 2f)
                                val group = when { BasketA.contains(center) -> "A"; BasketB.contains(center) -> "B"; else -> null }
                                if (group != null) {
                                    onMove(option.id, group)
                                    if (group == option.groupKey) placedLocal += option.id else wrong[option.id] = (wrong[option.id] ?: 0) + 1
                                }
                                dragging = false
                            },
                            onDragCancel = { dragging = false }
                        )
                    }
            ) {
                PictureCard(Modifier.fillMaxSize(), radius = 36, lip = Color(0x4D784614)) {
                    OptionPicture(option, mediaToken, Modifier.fillMaxSize().padding(18.u), textSize = 80)
                    if (specialistView && !isPlaced) SpecialistTag("${option.label} → ${if (option.groupKey == "B") labels.getOrNull(1) else labels.getOrNull(0)}")
                }
            }
        }
    }
}

@Composable
private fun Basket(rect: Rect, label: String, badge: Color, body: Color, handle: Color, badgeLip: Color) {
    At(rect.left.roundToInt(), rect.top.roundToInt(), rect.width.roundToInt(), rect.height.roundToInt(), Modifier.floating(amplitude = 4f, periodMs = 3400)) {
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / 330f
            withTransform({ scale(k, k, Offset.Zero) }) {
                drawPath(Path().apply { moveTo(40f, 130f); quadraticTo(165f, -10f, 290f, 130f) }, handle, style = Stroke(14f))
                drawPath(Path().apply { moveTo(20f, 130f); lineTo(310f, 130f); lineTo(280f, 290f); lineTo(50f, 290f); close() }, body)
                listOf(170f, 210f, 250f).forEach { y -> drawLine(handle.copy(alpha = .45f), Offset(36f + (y - 130f) * .1f, y), Offset(294f - (y - 130f) * .1f, y), 8f) }
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.offset(y = (-52).u).size(100.u)) {
                Box(Modifier.matchParentSize().offset(y = 6.u).clip(CircleShape).background(badgeLip))
                Box(Modifier.matchParentSize().clip(CircleShape).background(badge), contentAlignment = Alignment.Center) {
                    Text(label, color = Color.White, fontSize = 60.us, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

/** Road layouts on the 1280×800 artboard: start is the bee, end is the flower. */
private fun mazePath(layout: String?): Path = Path().apply {
    when (layout) {
        "zigzag" -> { moveTo(150f, 640f); lineTo(400f, 240f); lineTo(640f, 600f); lineTo(880f, 240f); lineTo(1130f, 560f) }
        "spiral" -> { moveTo(160f, 640f); cubicTo(160f, 300f, 520f, 180f, 820f, 220f); cubicTo(1120f, 260f, 1160f, 480f, 900f, 600f); cubicTo(640f, 720f, 540f, 520f, 620f, 400f) }
        else -> { moveTo(150f, 620f); cubicTo(350f, 620f, 300f, 250f, 520f, 250f); cubicTo(740f, 250f, 700f, 600f, 880f, 560f); cubicTo(1060f, 520f, 1000f, 200f, 1130f, 220f) }
    }
}

/** MAZE: drag the bee along the road to the flower. */
@Composable
fun MazeGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, onMove: (String, String?) -> Unit) {
    val exercise = state.exercise ?: return
    val layout = exercise.gameConfig?.mazeLayout
    val samples = remember(layout) {
        val path = mazePath(layout)
        val measure = PathMeasure().apply { setPath(path, false) }
        val count = 240
        (0..count).map { measure.getPosition(measure.length * it / count) }
    }
    val road = remember(layout) { mazePath(layout) }
    val completed = state.solvedOptionIds.isNotEmpty() || state.latestAnswer?.isCorrect == true
    var progress by remember(state.sessionExerciseId) { mutableIntStateOf(if (completed) samples.lastIndex else 0) }
    var off by remember(state.sessionExerciseId) { mutableStateOf(false) }
    var finished by remember(state.sessionExerciseId) { mutableStateOf(completed) }
    val unitPx = LocalPlay.current.unit * LocalDensity.current.density
    val bee = samples[if (completed) samples.lastIndex else progress]
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            withTransform({ scale(size.width / 1280f, size.height / 800f, Offset.Zero) }) {
                drawPath(road, Color(0xFFE9D3A2), style = Stroke(130f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(road, Color(0xFFF6E6BF), style = Stroke(104f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(road, Color(0xFFD8BE84), style = Stroke(6f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 26f))))
                if (progress > 0) {
                    val trail = Path().apply { moveTo(samples[0].x, samples[0].y); for (i in 1..progress) lineTo(samples[i].x, samples[i].y) }
                    drawPath(trail, Color(0xFFFFB648), style = Stroke(12f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 20f))))
                }
                val end = samples.last()
                listOf(Offset(0f, -26f), Offset(25f, -8f), Offset(16f, 22f), Offset(-16f, 22f), Offset(-25f, -8f)).forEach { drawCircle(Color(0xFFFF8FA3), 24f, end + it) }
                drawCircle(Color(0xFFFFD43B), 21f, end)
            }
        }
        val beeSize = 120
        Box(
            Modifier
                .offset { IntOffset(((bee.x - beeSize / 2f) * unitPx).roundToInt(), ((bee.y - beeSize / 2f) * unitPx).roundToInt()) }
                .size(beeSize.u)
                .floating(amplitude = 5f, periodMs = 500)
        ) { BeeGlyph(Modifier.fillMaxSize()) }
        if (interactive && !finished) {
            Box(
                Modifier.fillMaxSize().semantics { contentDescription = "Поле лабиринта" }.pointerInput(samples) {
                    var active = false
                    detectDragGestures(
                        onDragStart = { start ->
                            val p = start / unitPx
                            active = hypot(p.x - samples[progress].x, p.y - samples[progress].y) < 110f
                        },
                        onDrag = { change, _ ->
                            if (!active || finished) return@detectDragGestures
                            change.consume()
                            val p = change.position / unitPx
                            val from = (progress - 12).coerceAtLeast(0)
                            val to = (progress + 30).coerceAtMost(samples.lastIndex)
                            var best = progress; var bestDistance = Float.MAX_VALUE
                            for (i in from..to) {
                                val d = hypot(p.x - samples[i].x, p.y - samples[i].y)
                                if (d < bestDistance) { bestDistance = d; best = i }
                            }
                            if (bestDistance <= 75f) {
                                off = false
                                progress = best
                                if (progress >= samples.lastIndex - 3) {
                                    progress = samples.lastIndex; finished = true; active = false
                                    onMove("finish", null)
                                }
                            } else if (!off) {
                                off = true
                                onMove("off_path", null)
                            }
                        },
                        onDragEnd = { active = false },
                        onDragCancel = { active = false }
                    )
                }
            )
        }
        if (off && !finished) {
            At(390, 700, 500, 70) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Вернись на дорожку", color = Play.Ink, fontSize = 32.us, fontWeight = FontWeight.Black,
                        modifier = Modifier.clip(RoundedCornerShape(30.u)).background(Color.White).padding(horizontal = 26.u, vertical = 8.u))
                }
            }
        }
    }
}

@Composable
private fun BeeGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val k = size.minDimension / 100f
        withTransform({ scale(k, k, Offset.Zero) }) {
            drawOval(Color(0xD9FFFFFF), Offset(22f, 10f), Size(32f, 40f))
            drawOval(Color(0xD9FFFFFF), Offset(44f, 8f), Size(32f, 40f))
            drawOval(Color(0xFFFFD43B), Offset(16f, 32f), Size(68f, 52f))
            drawPath(Path().apply { moveTo(38f, 34f); quadraticTo(34f, 58f, 38f, 82f); moveTo(54f, 32f); quadraticTo(50f, 58f, 54f, 84f) }, Play.Ink, style = Stroke(8f))
            drawCircle(Play.Ink, 6f, Offset(74f, 52f))
            drawCircle(Color.White, 2f, Offset(76f, 50f))
            drawPath(Path().apply { moveTo(70f, 64f); quadraticTo(76f, 68f, 82f, 62f) }, Play.Ink, style = Stroke(3f, cap = StrokeCap.Round))
            drawLine(Play.Ink, Offset(16f, 58f), Offset(6f, 58f), 4f, StrokeCap.Round)
        }
    }
}

/** WORD_BUILD: tap the letters of the word in order. */
@Composable
fun WordGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onMove: (String, String?) -> Unit, onListen: () -> Unit) {
    val exercise = state.exercise ?: return
    val word = exercise.options.filter { it.sequenceIndex != null }.sortedBy { it.sequenceIndex }
    val tiles = remember(state.sessionExerciseId, exercise.options) {
        exercise.options.shuffled(Random((state.sessionExerciseId ?: exercise.id).hashCode()))
    }
    val usedLocal = remember(state.sessionExerciseId) { mutableStateListOf<String>() }
    val used = (state.solvedOptionIds + usedLocal).distinct()
    val filled = used.size.coerceAtMost(word.size)
    val wrong = remember(state.sessionExerciseId) { mutableStateMapOf<String, Int>() }
    val done = filled >= word.size && word.isNotEmpty()
    val letterColors = listOf(Color(0xFF3E8EDE), Color(0xFFE5533D), Color(0xFF1E8A4C), Color(0xFFD9730D), Color(0xFF7B4FC9), Color(0xFF0E8C93))
    Box(Modifier.fillMaxSize()) {
        At(90, 150, 330, 330, Modifier.then(if (done) Modifier.pulsing(1f, 1.04f, 1000) else Modifier.floating(amplitude = 8f, periodMs = 3000))) {
            PictureCard(Modifier.fillMaxSize(), radius = 60, lip = Color(0x338C5A1E)) {
                val config = exercise.gameConfig
                val context = LocalContext.current
                when {
                    config?.imageUrl != null -> AsyncImage(
                        ImageRequest.Builder(context).data(config.imageUrl).apply { mediaToken?.takeIf { it.isNotBlank() }?.let { addHeader("Authorization", "Bearer $it") } }.build(),
                        exercise.title, Modifier.fillMaxSize().padding(36.u), contentScale = ContentScale.Fit
                    )
                    hasLocalExerciseDrawable(config?.imageAssetKey) -> Image(painterResource(exerciseDrawableFor(config!!.imageAssetKey!!)), exercise.title, Modifier.fillMaxSize().padding(36.u), contentScale = ContentScale.Fit)
                    else -> Text(word.joinToString("") { it.label }, color = Play.Ink, fontSize = 80.us, fontWeight = FontWeight.Black)
                }
            }
        }
        At(355, 420, 90, 90) {
            ChunkyButton(onListen, Play.Coral, Play.CoralDeep, Modifier.fillMaxSize(), CircleShape, depth = 6, description = "Послушать слово") { SpeakerGlyph(Modifier.size(48.u)) }
        }
        val slotW = minOf(190, (700 - (word.size - 1) * 22) / word.size.coerceAtLeast(1))
        Row(Modifier.offset(510.u, 220.u), horizontalArrangement = Arrangement.spacedBy(22.u)) {
            word.forEachIndexed { i, letter ->
                val isFilled = i < filled
                Box(
                    Modifier.size(slotW.u, (slotW * 1.1f).u).clip(RoundedCornerShape(34.u))
                        .background(if (isFilled) Color.White else Color.White.copy(alpha = .5f))
                        .border(6.u, when { isFilled -> Color.White; i == filled -> Color(0xFFFFB648); else -> Color(0xFFE5CFA0) }, RoundedCornerShape(34.u)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isFilled || specialistView) Text(
                        letter.label.uppercase(), Modifier.then(if (isFilled) Modifier.popIn(letter.id) else Modifier.graphicsLayer { alpha = .35f }),
                        color = letterColors[i % letterColors.size], fontSize = (slotW * .78f).toInt().us, fontWeight = FontWeight.Black
                    )
                }
            }
        }
        if (done && !specialistView) {
            At(510, 450, 700, 90) {
                Box(Modifier.fillMaxSize().popIn("done"), contentAlignment = Alignment.CenterStart) {
                    Text("${word.joinToString("") { it.label }.uppercase()}!", color = Play.GrassDeep, fontSize = 64.us, fontWeight = FontWeight.Black)
                }
            }
        }
        val tileSize = minOf(150, (1100 - (tiles.size - 1) * 26) / tiles.size.coerceAtLeast(1))
        Row(Modifier.offset(0.u, 600.u).size(1280.u, tileSize.u), horizontalArrangement = Arrangement.spacedBy(26.u, Alignment.CenterHorizontally)) {
            tiles.forEachIndexed { i, tile ->
                val gone = tile.id in used
                val vanish by animateFloatAsState(if (gone) 1f else 0f, tween(300), label = "tile")
                PictureCard(
                    Modifier.size(tileSize.u)
                        .graphicsLayer { alpha = 1f - vanish; scaleX = 1f - vanish * .5f; scaleY = 1f - vanish * .5f }
                        .wiggle(wrong[tile.id] ?: 0)
                        .semantics { contentDescription = "буква ${tile.label}" }
                        .clickable(interactionSource = null, indication = null, enabled = interactive && !gone && !done, role = Role.Button) {
                            val expected = word.getOrNull(used.size) ?: return@clickable
                            onMove(tile.id, null)
                            if (tile.label.equals(expected.label, ignoreCase = true)) usedLocal += tile.id
                            else wrong[tile.id] = (wrong[tile.id] ?: 0) + 1
                        },
                    radius = 32, lip = Color(0x408C5A1E)
                ) {
                    Text(tile.label.uppercase(), color = letterColors[i % letterColors.size], fontSize = (tileSize * .66f).toInt().us, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
