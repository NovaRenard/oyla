package kz.oyla.app.ui.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kz.oyla.app.ui.session.ExerciseOptionUiModel
import kz.oyla.app.ui.session.ExerciseUiState

private val LetterInks = listOf(Color(0xFF3E8EDE), Color(0xFFE5533D), Color(0xFF1E8A4C), Color(0xFFD9730D), Color(0xFF7B4FC9), Color(0xFF0E8C93))

/* ---------- TYPE_WORD ---------- */

/** Keyboard geometry (top-left corners of the keys) shared with the hint hand. */
data class KeyboardLayout(val side: Float, val corners: List<Offset>)

fun typeWordKeyboard(count: Int): KeyboardLayout {
    val perRow = if (count <= 6) count else (count + 1) / 2
    val rows = if (count <= 6) 1 else 2
    val side = minOf(124f, (1180f - (perRow - 1) * 18f) / perRow.coerceAtLeast(1))
    val top = if (rows == 1) 560f else 500f
    return KeyboardLayout(side, List(count) { i ->
        val row = i / perRow
        val inRow = if (row == rows - 1) count - row * perRow else perRow
        val used = inRow * side + (inRow - 1) * 18f
        Offset((1280f - used) / 2f + (i % perRow) * (side + 18f), top + row * (side + 18f))
    })
}

/** Keys in a stable, per-lesson order. */
fun typeWordKeys(sessionExerciseId: String?, options: List<ExerciseOptionUiModel>): List<ExerciseOptionUiModel> = shuffledFor(sessionExerciseId, options)

/** TYPE_WORD: picture, empty letter cells and a big keyboard of the given keys. */
@Composable
fun TypeWordGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, onMove: (String, String?) -> Unit, onListen: () -> Unit) {
    val exercise = state.exercise ?: return
    val word = (exercise.gameConfig?.word ?: "").trim().uppercase()
    val keys = remember(state.sessionExerciseId, exercise.options) { typeWordKeys(state.sessionExerciseId, exercise.options) }
    var localTyped by remember(state.sessionExerciseId) { mutableIntStateOf(0) }
    val typed = maxOf(localTyped, state.progressDone).coerceAtMost(word.length)
    val wrong = remember(state.sessionExerciseId) { mutableStateMapOf<String, Int>() }
    val done = word.isNotEmpty() && typed >= word.length
    val keyboard = typeWordKeyboard(keys.size)
    Box(Modifier.fillMaxSize()) {
        At(80, 160, 300, 300, if (done) Modifier.pulsing(1f, 1.04f, 1000) else Modifier.floating(amplitude = 8f, periodMs = 3000)) {
            PictureCard(Modifier.fillMaxSize(), radius = 56, lip = Color(0x338C5A1E)) {
                AssetPicture(exercise.gameConfig?.imageAssetKey, Modifier.fillMaxSize().padding(34.u), exercise.title)
            }
        }
        At(310, 395, 86, 86) {
            ChunkyButton(onListen, Play.Coral, Play.CoralDeep, Modifier.fillMaxSize(), CircleShape, depth = 6, enabled = !specialistView, description = "Послушать слово") { SpeakerGlyph(Modifier.size(46.u)) }
        }
        val n = word.length.coerceAtLeast(1)
        val cellW = minOf(130f, (800f - (n - 1) * 14f) / n)
        Row(Modifier.offset(440.u, 210.u).size(800.u, (cellW * 1.15f + 10).u), horizontalArrangement = Arrangement.spacedBy(14.u, Alignment.CenterHorizontally)) {
            word.forEachIndexed { i, letter ->
                val filled = i < typed
                Box(
                    Modifier.size(cellW.u, (cellW * 1.15f).u).clip(RoundedCornerShape(28.u))
                        .background(if (filled) Color.White else Color(0x80FFFFFF))
                        .border(6.u, when { filled -> Color.White; i == typed -> Color(0xFFFFB648); else -> Color(0xFFE5CFA0) }, RoundedCornerShape(28.u)),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        filled -> Text(letter.toString(), Modifier.popIn("t$i"), color = LetterInks[i % LetterInks.size], fontSize = (cellW * .72f).toInt().us, fontWeight = FontWeight.Black)
                        specialistView -> Text(letter.toString(), color = Color(0xFFCBBE9F), fontSize = (cellW * .72f).toInt().us, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        if (done && !specialistView) {
            At(440, 380, 800, 90) {
                Box(Modifier.fillMaxSize().popIn("done"), contentAlignment = Alignment.Center) {
                    Text("$word!", color = Play.GrassDeep, fontSize = 64.us, fontWeight = FontWeight.Black)
                }
            }
            StarBurst(state.sessionExerciseId ?: "word", Modifier.fillMaxSize())
        }
        keys.forEachIndexed { i, key ->
            val corner = keyboard.corners[i]
            val isNext = specialistView && !done && key.label.equals(word.getOrNull(typed)?.toString(), ignoreCase = true)
            At(corner.x.toInt(), corner.y.toInt(), keyboard.side.toInt(), keyboard.side.toInt(), Modifier.wiggle(wrong[key.id] ?: 0)) {
                ChunkyButton(
                    onClick = {
                        if (done) return@ChunkyButton
                        onMove(key.id, null)
                        if (typeWordAccepts(word, typed, key.label)) localTyped = typed + 1
                        else wrong[key.id] = (wrong[key.id] ?: 0) + 1
                    },
                    color = if (isNext) Color(0xFFFFF1B8) else Color.White, lipColor = Color(0x598C5A1E),
                    modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(30.u), depth = 8,
                    enabled = interactive && !done, description = "буква ${key.label}"
                ) {
                    Text(key.label.uppercase(), color = LetterInks[i % LetterInks.size], fontSize = (keyboard.side * .58f).toInt().us, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/* ---------- PHONETICS ---------- */

/** Classic sound scheme: vowel red, (hard) consonant blue, soft consonant green. */
fun soundColor(key: String): Color = when (key) {
    "V" -> Color(0xFFE5533D)
    "S" -> Color(0xFF2EAD5B)
    "C", "H" -> Color(0xFF3E7CD6)
    else -> Color(0xFFB8C0D0)
}

private fun soundLegend(key: String): String = when (key) {
    "V" -> "гласный"
    "C" -> "согласный"
    "H" -> "твёрдый"
    "S" -> "мягкий"
    else -> key
}

/** Letter cells and palette geometry shared with the hint hand. */
data class PhoneticsLayout(val cellW: Float, val cellX: List<Float>, val paletteX: List<Float>) {
    companion object {
        const val CELL_TOP = 215f
        const val PALETTE_TOP = 520f
        const val PALETTE_SIDE = 130f
    }
    val cellH: Float get() = cellW * 1.2f
}

fun phoneticsLayout(letters: Int, colors: Int): PhoneticsLayout {
    val n = letters.coerceAtLeast(1)
    val cellW = minOf(150f, (1100f - (n - 1) * 18f) / n)
    val used = n * cellW + (n - 1) * 18f
    val cells = List(n) { (1280f - used) / 2f + it * (cellW + 18f) }
    val m = colors.coerceAtLeast(1)
    val pUsed = m * PhoneticsLayout.PALETTE_SIDE + (m - 1) * 110f
    val palette = List(m) { (1280f - pUsed) / 2f + it * (PhoneticsLayout.PALETTE_SIDE + 110f) }
    return PhoneticsLayout(cellW, cells, palette)
}

/** PHONETICS: colour every letter by its sound — pick a colour, then tap (or drag onto) the letter. */
@Composable
fun PhoneticsGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, onMove: (String, String?) -> Unit) {
    val exercise = state.exercise ?: return
    val letters = exercise.options.sortedBy { it.position }
    val palette = exercise.gameConfig?.groupLabels.orEmpty().ifEmpty { listOf("V", "C") }
    val coloredLocal = remember(state.sessionExerciseId) { mutableStateListOf<String>() }
    val colored = doneIds(state, coloredLocal)
    var selected by remember(state.sessionExerciseId) { mutableStateOf<String?>(null) }
    val wrong = remember(state.sessionExerciseId) { mutableStateMapOf<String, Int>() }
    val layout = phoneticsLayout(letters.size, palette.size)
    val paintable = letters.filter { it.groupKey in palette }
    val done = paintable.isNotEmpty() && paintable.all { it.id in colored }
    fun paint(letter: ExerciseOptionUiModel, color: String) {
        if (letter.id in colored || letter.groupKey !in palette) return
        onMove(letter.id, color)
        if (colorMoveAccepted(letter, color, palette, colored)) coloredLocal += letter.id
        else wrong[letter.id] = (wrong[letter.id] ?: 0) + 1
    }
    Box(Modifier.fillMaxSize()) {
        letters.forEachIndexed { i, letter ->
            val isColored = letter.id in colored
            val paintableLetter = letter.groupKey in palette
            val fill = when {
                !paintableLetter -> Color(0xFFE6E9F0)
                isColored -> soundColor(letter.groupKey.orEmpty())
                else -> Color.White
            }
            At(layout.cellX[i].toInt(), PhoneticsLayout.CELL_TOP.toInt(), layout.cellW.toInt(), layout.cellH.toInt(), Modifier.wiggle(wrong[letter.id] ?: 0)) {
                Box(
                    Modifier.fillMaxSize().then(if (isColored) Modifier.popIn("c${letter.id}") else Modifier)
                        .clip(RoundedCornerShape(30.u)).background(fill)
                        .border(6.u, if (specialistView && paintableLetter) soundColor(letter.groupKey.orEmpty()) else Color(0x33503C78), RoundedCornerShape(30.u))
                        .semantics { contentDescription = "буква ${letter.label}" }
                        .clickable(interactionSource = null, indication = null, enabled = interactive && !isColored && paintableLetter && selected != null, role = Role.Button) {
                            selected?.let { paint(letter, it) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(letter.label.uppercase(), color = if (isColored) Color.White else Play.Ink, fontSize = (layout.cellW * .62f).toInt().us, fontWeight = FontWeight.Black)
                }
            }
        }
        if (done && !specialistView) StarBurst(state.sessionExerciseId ?: "phon", Modifier.fillMaxSize())
        // Palette: tap to pick a colour, or drag the paint drop straight onto a letter.
        palette.forEachIndexed { i, key ->
            val x = layout.paletteX[i]
            val isSelected = selected == key
            At(x.toInt() - 30, (PhoneticsLayout.PALETTE_TOP + PhoneticsLayout.PALETTE_SIDE + 14f).toInt(), PhoneticsLayout.PALETTE_SIDE.toInt() + 60, 50) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(soundLegend(key), color = Play.Ink, fontSize = 28.us, fontWeight = FontWeight.Black)
                }
            }
            if (isSelected) {
                At(x.toInt() - 14, PhoneticsLayout.PALETTE_TOP.toInt() - 14, PhoneticsLayout.PALETTE_SIDE.toInt() + 28, PhoneticsLayout.PALETTE_SIDE.toInt() + 28) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0x80FFC23D)))
                }
            }
            DraggablePiece(
                key = "${state.sessionExerciseId}-paint-$key",
                home = Offset(x, PhoneticsLayout.PALETTE_TOP),
                side = PhoneticsLayout.PALETTE_SIDE,
                enabled = interactive && !done,
                description = "краска: ${soundLegend(key)}",
                onDrop = { center ->
                    val index = layout.cellX.indexOfFirst { cx ->
                        center.x in (cx - 10f)..(cx + layout.cellW + 10f) && center.y in (PhoneticsLayout.CELL_TOP - 30f)..(PhoneticsLayout.CELL_TOP + layout.cellH + 30f)
                    }
                    letters.getOrNull(index)?.let { paint(it, key) }
                    selected = key
                }
            ) {
                Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(soundColor(key))
                        .border(8.u, Color.White, CircleShape)
                        .clickable(interactionSource = null, indication = null, enabled = interactive && !done, role = Role.Button) { selected = if (isSelected) null else key }
                ) {
                    Canvas(Modifier.fillMaxSize()) { drawCircle(Color(0x59FFFFFF), size.minDimension * .14f, Offset(size.width * .34f, size.height * .32f)) }
                }
            }
        }
    }
}

/* ---------- LABEL_SCENE ---------- */

/** 3×3 scene geometry shared with the hint hand. */
object LabelScene {
    const val LEFT = 170f
    const val TOP = 140f
    const val CELL_W = 313f
    const val CELL_H = 155f
    const val PICTURE = 100f
    const val CHIP_W = 180f
    const val CHIP_H = 80f
    const val CHIP_TOP = 640f
    const val SLOT_W = 190f
    const val SLOT_H = 46f

    fun cellOf(option: ExerciseOptionUiModel, index: Int): Int = (option.cellIndex ?: index).coerceIn(0, 8)
    fun cellRect(cell: Int): Rect = Rect(LEFT + (cell % 3) * CELL_W, TOP + (cell / 3) * CELL_H, LEFT + (cell % 3 + 1) * CELL_W, TOP + (cell / 3 + 1) * CELL_H)
    fun slot(cell: Int): Offset = cellRect(cell).let { Offset(it.center.x - SLOT_W / 2f, it.top + PICTURE + 4f) }
    fun chipHome(i: Int, count: Int): Offset {
        val gap = 20f
        val used = count * CHIP_W + (count - 1) * gap
        return Offset((1280f - used) / 2f + i * (CHIP_W + gap), CHIP_TOP)
    }
}

/** LABEL_SCENE: a scene of pictures; drag every word onto its picture. */
@Composable
fun LabelSceneGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onMove: (String, String?) -> Unit) {
    val exercise = state.exercise ?: return
    val objects = exercise.options.sortedBy { it.position }
    val chips = rememberShuffled(state, exercise.options)
    val labelledLocal = remember(state.sessionExerciseId) { mutableStateListOf<String>() }
    val labelled = doneIds(state, labelledLocal)
    val shake = remember(state.sessionExerciseId) { mutableStateMapOf<String, Int>() }
    val done = objects.isNotEmpty() && objects.all { it.id in labelled }
    Box(Modifier.fillMaxSize()) {
        At(LabelScene.LEFT.toInt() - 20, LabelScene.TOP.toInt() - 10, (LabelScene.CELL_W * 3).toInt() + 40, (LabelScene.CELL_H * 3).toInt() + 20) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(44.u)).background(Color(0xCCFFFFFF)))
        }
        objects.forEachIndexed { i, obj ->
            val rect = LabelScene.cellRect(LabelScene.cellOf(obj, i))
            At((rect.center.x - LabelScene.PICTURE / 2f).toInt(), rect.top.toInt(), LabelScene.PICTURE.toInt(), LabelScene.PICTURE.toInt(),
                Modifier.wiggle(shake[obj.id] ?: 0).floating(amplitude = 4f, periodMs = 3000 + i * 250, delayMs = i * 300)) {
                OptionPicture(obj, mediaToken, Modifier.fillMaxSize(), textSize = 40)
            }
            val slot = LabelScene.slot(LabelScene.cellOf(obj, i))
            if (obj.id !in labelled) {
                At(slot.x.toInt(), slot.y.toInt(), LabelScene.SLOT_W.toInt(), LabelScene.SLOT_H.toInt()) {
                    if (specialistView) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(obj.label.uppercase(), color = Color(0xFF8A94AD), fontSize = 24.us, fontWeight = FontWeight.Bold)
                        }
                    }
                    Canvas(Modifier.fillMaxSize()) {
                        drawRoundRect(Color(0xFFB9A57A), cornerRadius = CornerRadius(size.height / 2f),
                            style = Stroke(size.height * .07f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(size.height * .25f, size.height * .18f))))
                    }
                }
            }
        }
        chips.forEachIndexed { i, chip ->
            val isLabelled = chip.id in labelled
            val home = LabelScene.chipHome(i, chips.size)
            val cell = LabelScene.cellOf(chip, objects.indexOfFirst { it.id == chip.id }.coerceAtLeast(0))
            DraggablePiece(
                key = "${state.sessionExerciseId}-${chip.id}",
                home = home, side = LabelScene.CHIP_H, width = LabelScene.CHIP_W,
                settled = if (isLabelled) LabelScene.slot(cell) else null,
                settledSide = LabelScene.SLOT_H, settledWidth = LabelScene.SLOT_W,
                enabled = interactive && !isLabelled,
                description = chip.label,
                onDrop = { center ->
                    val target = objects.withIndex().firstOrNull { (k, obj) -> LabelScene.cellRect(LabelScene.cellOf(obj, k)).contains(center) }?.value
                    if (target != null && target.id !in labelled) {
                        onMove(chip.id, target.id)
                        if (labelAccepted(chip.id, target.id, labelled)) labelledLocal += chip.id
                        else shake[target.id] = (shake[target.id] ?: 0) + 1
                    }
                }
            ) { dragging ->
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(if (isLabelled) Play.Grass else Color.White)
                        .border(if (dragging) 5.u else 3.u, if (dragging) Play.Sun else Color(0x33503C78), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(chip.label.uppercase(), color = if (isLabelled) Color.White else LetterInks[i % LetterInks.size],
                        fontSize = (if (isLabelled) 26 else 36).us, fontWeight = FontWeight.Black, maxLines = 1)
                }
                if (isLabelled && !specialistView) StarBurst(chip.id, Modifier.fillMaxSize())
            }
        }
        if (done && !specialistView) StarBurst(state.sessionExerciseId ?: "label", Modifier.fillMaxSize())
    }
}
