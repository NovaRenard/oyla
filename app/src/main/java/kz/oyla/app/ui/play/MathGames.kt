package kz.oyla.app.ui.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import kz.oyla.app.ui.session.ExerciseUiState
import kz.oyla.app.ui.session.ExerciseUiStatus
import kz.oyla.app.ui.session.exerciseDrawableFor
import kz.oyla.app.ui.session.hasLocalExerciseDrawable

/** Normalises the operator sent by the server ("*", "x", "×", "−"…). */
fun mathOpSymbol(op: String?): String = when (op?.trim()) {
    "-", "−", "–" -> "−"
    "*", "x", "X", "×", "·" -> "×"
    else -> "+"
}

/** MATH: "A op B = ?" acted out with pictures; tap the number bubble. */
@Composable
fun MathGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, onChoice: (String) -> Unit) {
    val exercise = state.exercise ?: return
    val config = exercise.gameConfig
    val a = (config?.operandA ?: 0).coerceIn(0, 30)
    val b = (config?.operandB ?: 0).coerceIn(0, 30)
    val op = mathOpSymbol(config?.mathOp)
    val key = config?.imageAssetKey ?: "exercise_apple"
    val solvedAnswer = state.latestAnswer?.takeIf { it.isCorrect }
    val solved = solvedAnswer?.let { answer -> exercise.options.firstOrNull { it.id == answer.selectedOptionId }?.label ?: answer.selectedOptionLabel.takeIf(String::isNotBlank) }
    val shownAnswer = solved ?: if (specialistView) exercise.options.firstOrNull { it.id == exercise.correctOptionId }?.label else null
    Box(Modifier.fillMaxSize()) {
        when (op) {
            "−" -> {
                At(140, 160, 1000, 290) {
                    PictureCard(Modifier.fillMaxSize(), radius = 40, lip = Color(0x2E8C5A1E)) {
                        ItemGrid(a, key, 960f, 260f, crossedFrom = a - b)
                    }
                }
            }
            "×" -> {
                val rows = if (a > 5) 2 else 1
                val perRow = (a + rows - 1) / rows.coerceAtLeast(1)
                val plateW = minOf(220f, (1140f - (perRow - 1) * 20f) / perRow.coerceAtLeast(1))
                val plateH = if (rows == 2) 135f else 260f
                repeat(a) { i ->
                    val row = i / perRow
                    val inRow = if (row == rows - 1) a - row * perRow else perRow
                    val used = inRow * plateW + (inRow - 1) * 20f
                    val x = 70f + (1140f - used) / 2f + (i % perRow) * (plateW + 20f)
                    val y = 160f + row * (plateH + 20f)
                    At(x.toInt(), y.toInt(), plateW.toInt(), plateH.toInt()) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawOval(Color(0x268C5A1E), Offset(0f, size.height * .12f), Size(size.width, size.height * .9f))
                            drawOval(Color.White, Offset(0f, 0f), Size(size.width, size.height * .9f))
                            drawOval(Color(0xFFE3ECF7), Offset(size.width * .1f, size.height * .1f), Size(size.width * .8f, size.height * .7f))
                        }
                        Box(Modifier.fillMaxSize().padding(horizontal = (plateW * .14f).toInt().u, vertical = (plateH * .12f).toInt().u)) {
                            ItemGrid(b, key, plateW * .72f, plateH * .66f)
                        }
                    }
                }
            }
            else -> {
                At(60, 160, 520, 290) { PictureCard(Modifier.fillMaxSize(), radius = 40, lip = Color(0x2E8C5A1E)) { ItemGrid(a, key, 490f, 260f) } }
                At(700, 160, 520, 290) { PictureCard(Modifier.fillMaxSize(), radius = 40, lip = Color(0x2E8C5A1E)) { ItemGrid(b, key, 490f, 260f) } }
                At(590, 255, 100, 100) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(Play.Coral), contentAlignment = Alignment.Center) {
                        Text("+", color = Color.White, fontSize = 76.us, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        // The example itself, in big digits.
        At(240, 470, 800, 120) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.clip(RoundedCornerShape(40.u)).background(Color.White).padding(horizontal = 40.u, vertical = 4.u),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.u)
                ) {
                    Text("$a", color = Play.Blue, fontSize = 84.us, fontWeight = FontWeight.Black)
                    Text(op, color = Play.CoralDeep, fontSize = 84.us, fontWeight = FontWeight.Black)
                    Text("$b", color = Play.Blue, fontSize = 84.us, fontWeight = FontWeight.Black)
                    Text("=", color = Play.Ink, fontSize = 84.us, fontWeight = FontWeight.Black)
                    if (shownAnswer != null) {
                        Text(shownAnswer, Modifier.popIn(shownAnswer), color = if (solved != null) Play.SunDeep else Play.GrassDeep, fontSize = 84.us, fontWeight = FontWeight.Black)
                    } else {
                        Text("?", Modifier.pulsing(1f, 1.12f), color = Play.SunDeep, fontSize = 84.us, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        NumberBubbles(state, interactive, specialistView, onChoice)
        if (solved != null && !specialistView) StarBurst(state.sessionExerciseId ?: "math", Modifier.fillMaxSize())
    }
}

/** [count] copies of a picture fitted into a box; copies from [crossedFrom] on are crossed out. */
@Composable
private fun ItemGrid(count: Int, key: String, width: Float, height: Float, crossedFrom: Int = Int.MAX_VALUE) {
    if (count <= 0) return
    val fit = fitGrid(count, width, height, 10f, maxSide = 130f)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.u)) {
            for (row in 0 until fit.rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.u)) {
                    for (col in 0 until fit.cols) {
                        val i = row * fit.cols + col
                        if (i >= count) break
                        Box(Modifier.size(fit.side.u)) {
                            AssetPicture(key, Modifier.fillMaxSize())
                            if (i >= crossedFrom) {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawCircle(Color(0xB3FFFFFF), size.minDimension / 2f)
                                    val w = size.minDimension * .1f
                                    drawLine(Color(0xFF5A6A8A), Offset(size.width * .12f, size.height * .12f), Offset(size.width * .88f, size.height * .88f), w, StrokeCap.Round)
                                    drawLine(Color(0xFF5A6A8A), Offset(size.width * .88f, size.height * .12f), Offset(size.width * .12f, size.height * .88f), w, StrokeCap.Round)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val GlassColors = listOf(Color(0xFFFF6B6B), Color(0xFF3E8EDE), Color(0xFF2EAD5B), Color(0xFFFFB648), Color(0xFF9B5DE5))
private val PlateColors = listOf(Color(0xFFFF8FA3), Color(0xFF22C3C9), Color(0xFFFFD43B), Color(0xFF7B4FC9), Color(0xFFF08C00))

/** Geometry of the PAIRS game shared with the hint hand: centres of glasses and plates. */
data class PairsLayout(val glassW: Float, val plateW: Float, val glassX: List<Float>, val plateX: List<Float>) {
    companion object {
        const val GLASS_TOP = 170f
        const val GLASS_H = 160f
        const val PLATE_TOP = 420f
        const val PLATE_H = 100f
    }
}

fun pairsLayout(glasses: Int, plates: Int): PairsLayout {
    fun row(n: Int, maxW: Float, gap: Float): Pair<Float, List<Float>> {
        val w = minOf(maxW, (1000f - (n - 1) * gap) / n.coerceAtLeast(1))
        val used = n * w + (n - 1) * gap
        return w to List(n) { 140f + (1000f - used) / 2f + it * (w + gap) }
    }
    val (gw, gx) = row(glasses, 130f, 50f)
    val (pw, px) = row(plates, 180f, 40f)
    return PairsLayout(gw, pw, gx, px)
}

/** PAIRS: A glasses and B plates; connect pairs with lines (just to count), then pick the number. */
@Composable
fun PairsGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, onChoice: (String) -> Unit) {
    val exercise = state.exercise ?: return
    val glasses = (exercise.gameConfig?.operandA ?: 2).coerceIn(1, 6)
    val plates = (exercise.gameConfig?.operandB ?: 2).coerceIn(1, 6)
    val layout = pairsLayout(glasses, plates)
    val pairs = remember(state.sessionExerciseId) { mutableStateListOf<Pair<Int, Int>>() }
    var selected by remember(state.sessionExerciseId) { mutableStateOf<Int?>(null) }
    var repeatShake by remember(state.sessionExerciseId) { mutableIntStateOf(0) }
    var repeatPlate by remember(state.sessionExerciseId) { mutableIntStateOf(-1) }
    val solved = state.latestAnswer?.isCorrect == true || state.exerciseStatus == ExerciseUiStatus.COMPLETED
    val canConnect = interactive && !solved
    val shownPairs = if (specialistView) (0 until glasses).flatMap { g -> (0 until plates).map { g to it } } else pairs
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / 1280f
            shownPairs.forEach { (g, p) ->
                val from = Offset((layout.glassX[g] + layout.glassW / 2f) * k, (PairsLayout.GLASS_TOP + PairsLayout.GLASS_H) * k)
                val to = Offset((layout.plateX[p] + layout.plateW / 2f) * k, (PairsLayout.PLATE_TOP + 20f) * k)
                drawLine(GlassColors[g % GlassColors.size], from, to, 9f * k, StrokeCap.Round)
            }
        }
        repeat(glasses) { g ->
            val isSelected = selected == g
            At(layout.glassX[g].toInt(), PairsLayout.GLASS_TOP.toInt(), layout.glassW.toInt(), PairsLayout.GLASS_H.toInt(),
                Modifier.offset(y = if (isSelected) (-14).u else 0.u)) {
                Box(
                    Modifier.fillMaxSize()
                        .semantics { contentDescription = "стакан ${g + 1}" }
                        .clickable(interactionSource = null, indication = null, enabled = canConnect, role = Role.Button) { selected = if (isSelected) null else g }
                ) {
                    GlassGlyph(GlassColors[g % GlassColors.size], isSelected, Modifier.fillMaxSize())
                }
            }
        }
        repeat(plates) { p ->
            At(layout.plateX[p].toInt(), PairsLayout.PLATE_TOP.toInt(), layout.plateW.toInt(), PairsLayout.PLATE_H.toInt(),
                Modifier.wiggle(if (repeatPlate == p) repeatShake else 0)) {
                Box(
                    Modifier.fillMaxSize()
                        .semantics { contentDescription = "тарелка ${p + 1}" }
                        .clickable(interactionSource = null, indication = null, enabled = canConnect && selected != null, role = Role.Button) {
                            val g = selected ?: return@clickable
                            if ((g to p) in pairs) { repeatPlate = p; repeatShake++ } else pairs += g to p
                            selected = null
                        }
                ) { PlateGlyph(PlateColors[p % PlateColors.size], Modifier.fillMaxSize()) }
            }
        }
        At(440, 535, 400, 70) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val count = shownPairs.size
                Pill(if (specialistView) "Всего пар: $count" else "Пар: $count", Modifier.popIn(count), size = 34)
            }
        }
        NumberBubbles(state, interactive, specialistView, onChoice, top = 630, side = 120)
    }
}

@Composable
private fun GlassGlyph(color: Color, selected: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val glass = Path().apply { moveTo(w * .12f, h * .08f); lineTo(w * .88f, h * .08f); lineTo(w * .76f, h * .96f); lineTo(w * .24f, h * .96f); close() }
        val juice = Path().apply { moveTo(w * .17f, h * .4f); lineTo(w * .83f, h * .4f); lineTo(w * .76f, h * .96f); lineTo(w * .24f, h * .96f); close() }
        if (selected) drawCircle(Color(0x66FFC23D), w * .62f, Offset(w / 2f, h / 2f))
        drawPath(glass, Color(0xE6FFFFFF))
        drawPath(juice, color)
        drawPath(glass, Color(0xFF5A6A8A), style = Stroke(w * .05f))
        drawLine(Color(0x99FFFFFF), Offset(w * .3f, h * .18f), Offset(w * .34f, h * .8f), w * .06f, StrokeCap.Round)
    }
}

@Composable
private fun PlateGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawOval(Color(0x338C5A1E), Offset(0f, h * .22f), Size(w, h * .78f))
        drawOval(color, Offset(0f, h * .1f), Size(w, h * .78f))
        drawOval(Color.White, Offset(w * .16f, h * .24f), Size(w * .68f, h * .5f))
    }
}

/** Focal points of the four zoomed fragments (fractions of the picture). */
private val PartFocus = listOf(0.25f to 0.25f, 0.75f to 0.3f, 0.28f to 0.75f, 0.72f to 0.72f)
private const val PART_ZOOM = 2.2f

/** PARTS: four zoomed-in fragments of one picture; guess the whole picture. */
@Composable
fun PartsGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onChoice: (String) -> Unit) {
    val exercise = state.exercise ?: return
    val key = exercise.gameConfig?.imageAssetKey
        ?: exercise.options.firstOrNull { it.id == exercise.correctOptionId }?.imageAssetKey
    val options = rememberShuffled(state, exercise.options)
    val solvedId = state.latestAnswer?.takeIf { it.isCorrect }?.selectedOptionId
    val wrongId = state.latestAnswer?.takeIf { !it.isCorrect }?.selectedOptionId
    val wrongTick = state.latestAnswer?.takeIf { !it.isCorrect }?.attemptNumber ?: 0
    Box(Modifier.fillMaxSize()) {
        if (solvedId != null && !specialistView) {
            At(490, 150, 300, 300, Modifier.popIn(solvedId)) {
                PictureCard(Modifier.fillMaxSize(), radius = 48, ring = Play.Sun) { AssetPicture(key, Modifier.fillMaxSize().padding(30.u)) }
                StarBurst(solvedId, Modifier.fillMaxSize())
            }
        } else {
            PartFocus.forEachIndexed { i, (fx, fy) ->
                // Static cards: a scaled, clipped child layer must not sit inside an animated layer on Android 9.
                At(150 + i * 250, 165, 220, 220) {
                    PictureCard(Modifier.fillMaxSize(), radius = 110, lip = Color(0x33503C78), ring = Color(0xFFFFE08A)) {
                        Box(Modifier.fillMaxSize().clip(CircleShape)) {
                            if (hasLocalExerciseDrawable(key)) {
                                Image(
                                    painterResource(exerciseDrawableFor(key!!)), null,
                                    Modifier.fillMaxSize().graphicsLayer {
                                        transformOrigin = TransformOrigin(0f, 0f)
                                        scaleX = PART_ZOOM; scaleY = PART_ZOOM
                                        translationX = size.width * (.5f - fx * PART_ZOOM)
                                        translationY = size.height * (.5f - fy * PART_ZOOM)
                                    },
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }
            }
            if (specialistView) {
                At(1150, 180, 110, 110) { PictureCard(Modifier.fillMaxSize(), radius = 24) { AssetPicture(key, Modifier.fillMaxSize().padding(10.u)) } }
            }
        }
        val side = 200
        val gap = 50
        val left = (1280 - options.size * side - (options.size - 1) * gap) / 2
        options.forEachIndexed { i, option ->
            val isSolved = option.id == solvedId
            At(left + i * (side + gap), 490, side, side) {
                PictureCard(
                    Modifier.fillMaxSize()
                        .wiggle(if (option.id == wrongId) wrongTick else 0)
                        .semantics { contentDescription = option.label }
                        .clickable(interactionSource = null, indication = null, enabled = interactive && solvedId == null, role = Role.Button) { onChoice(option.id) },
                    radius = 40,
                    ring = when {
                        isSolved -> Play.Sun
                        specialistView && option.id == exercise.correctOptionId -> Play.Grass
                        else -> null
                    }
                ) {
                    OptionPicture(option, mediaToken, Modifier.fillMaxSize().padding(24.u), textSize = 60)
                    if (specialistView) SpecialistTag(option.label, option.id == exercise.correctOptionId)
                }
            }
        }
    }
}
