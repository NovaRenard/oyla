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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kz.oyla.app.ui.session.ExerciseOptionUiModel
import kz.oyla.app.ui.session.ExerciseUiState

/** A short gentle message under the play area that hides itself after a moment. */
@Composable
private fun GentleNote(text: String?, tick: Int, y: Int = 700) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { if (tick > 0) { visible = true; delay(1800); visible = false } }
    if (visible && text != null) {
        At(290, y, 700, 70) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Pill(text, Modifier.popIn(tick)) } }
    }
}

/** Small green tick in the corner of a card, used in the specialist preview for the right items. */
@Composable
private fun TargetBadge() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Box(Modifier.size(36.u).clip(CircleShape).background(Play.Grass), contentAlignment = Alignment.Center) { CheckGlyph(Modifier.size(26.u)) }
    }
}

/* ---------- BASKET ---------- */

/** Where the container stands on the artboard. */
fun basketRect(container: String?): Rect = if (container == "bottle") Rect(530f, 270f, 750f, 660f) else Rect(410f, 300f, 870f, 650f)

private fun basketSlot(container: String?, k: Int): Offset = if (container == "bottle") {
    Offset(560f + (k % 3) * 56f, 590f - (k / 3) * 62f)
} else {
    Offset(450f + (k % 5) * 76f, 495f - (k / 5) * 72f)
}

/** BASKET: drag exactly what the task card asks for into the basket (or bottle). */
@Composable
fun BasketGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onMove: (String, String?) -> Unit) {
    val exercise = state.exercise ?: return
    val config = exercise.gameConfig
    val needs = config?.requirements.orEmpty()
    val container = config?.container
    val items = rememberShuffled(state, exercise.options)
    val placedLocal = remember(state.sessionExerciseId) { mutableStateListOf<String>() }
    val placedIds = doneIds(state, placedLocal).toList()
    val placed = placedIds.mapNotNull { exercise.options.byId(it) }
    val shake = remember(state.sessionExerciseId) { mutableStateMapOf<String, Int>() }
    var note by remember(state.sessionExerciseId) { mutableStateOf<String?>(null) }
    var noteTick by remember(state.sessionExerciseId) { mutableIntStateOf(0) }
    val side = scatterSide(items.size)
    val slots = remember(state.sessionExerciseId, items.size) { scatterSlots(items.size, state.sessionExerciseId, side) }
    val rect = basketRect(container)
    val done = needs.isNotEmpty() && needs.all { need -> placed.count { it.imageAssetKey == need.imageAssetKey } >= need.count }
    Box(Modifier.fillMaxSize()) {
        // Task card: what to put in, with live counters.
        val cardW = needs.size * 170 + 30
        At((1280 - cardW) / 2, 150, cardW, 106) {
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(36.u)).background(Color.White).padding(horizontal = 15.u),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                needs.forEach { need ->
                    val have = placed.count { it.imageAssetKey == need.imageAssetKey }.coerceAtMost(need.count)
                    val full = have >= need.count
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.u)) {
                        AssetPicture(need.imageAssetKey, Modifier.size(72.u), need.label)
                        Box(
                            Modifier.size(70.u).clip(CircleShape).background(if (full) Play.Grass else Color(0xFFFFF1D2)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (full) CheckGlyph(Modifier.size(44.u).popIn(need.imageAssetKey))
                            else Text("${need.count - have}", color = Play.CoralDeep, fontSize = 46.us, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
        At(rect.left.toInt(), rect.top.toInt(), rect.width.toInt(), rect.height.toInt()) {
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / rect.width
                withTransform({ scale(k, k, Offset.Zero) }) {
                    if (container == "bottle") drawBottle(rect.width, rect.height) else drawBasketBack(rect.width, rect.height)
                }
            }
        }
        items.forEachIndexed { i, item ->
            val order = placedIds.indexOf(item.id)
            val (hx, hy) = slots[i]
            DraggablePiece(
                key = "${state.sessionExerciseId}-${item.id}",
                home = Offset(hx, hy), side = side,
                settled = if (order >= 0) basketSlot(container, order) else null,
                settledSide = if (container == "bottle") 54f else 70f,
                enabled = interactive && order < 0 && !done,
                shake = shake[item.id] ?: 0,
                description = item.label,
                onDrop = { center ->
                    if (rect.inflate(30f).contains(center)) {
                        onMove(item.id, null)
                        if (basketAccepts(item, needs, placed)) placedLocal += item.id
                        else {
                            shake[item.id] = (shake[item.id] ?: 0) + 1
                            note = if (needs.any { it.imageAssetKey == item.imageAssetKey }) "Таких уже хватит" else "Это не нужно"
                            noteTick++
                        }
                    }
                }
            ) { dragging ->
                PictureCard(Modifier.fillMaxSize(), radius = 30, lip = Color(0x33503C78), ring = if (dragging) Play.Sun else null) {
                    OptionPicture(item, mediaToken, Modifier.fillMaxSize().padding(12.u), textSize = 30)
                    if (specialistView && order < 0 && needs.any { it.imageAssetKey == item.imageAssetKey }) TargetBadge()
                }
            }
        }
        if (container != "bottle") {
            // Front of the basket over the items inside.
            At(rect.left.toInt(), (rect.top + rect.height * .78f).toInt(), rect.width.toInt(), (rect.height * .22f).toInt(), Modifier.zIndex(3f)) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    drawPath(Path().apply { moveTo(w * .055f, 0f); lineTo(w * .945f, 0f); lineTo(w * .92f, h); lineTo(w * .08f, h); close() }, Color(0xFFE3A15E))
                    listOf(.3f, .62f).forEach { y -> drawLine(Color(0x73A0602F), Offset(w * .04f, h * y), Offset(w * .96f, h * y), h * .06f) }
                    drawLine(Color(0xFFA0602F), Offset(w * .055f, 0f), Offset(w * .945f, 0f), h * .08f, StrokeCap.Round)
                }
            }
        }
        if (done && !specialistView) StarBurst(state.sessionExerciseId ?: "basket", Modifier.fillMaxSize())
        GentleNote(note, noteTick, y = 270)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBasketBack(w: Float, h: Float) {
    drawPath(Path().apply { moveTo(w * .1f, h * .36f); quadraticTo(w * .5f, -h * .2f, w * .9f, h * .36f) }, Color(0xFFA0602F), style = Stroke(w * .04f, cap = StrokeCap.Round))
    drawPath(Path().apply { moveTo(0f, h * .3f); lineTo(w, h * .3f); lineTo(w * .92f, h); lineTo(w * .08f, h); close() }, Color(0xFFC9854A))
    drawOval(Color(0xFF8E5326), Offset(0f, h * .24f), Size(w, h * .14f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBottle(w: Float, h: Float) {
    val glass = Path().apply {
        moveTo(w * .38f, 0f); lineTo(w * .62f, 0f); lineTo(w * .62f, h * .14f)
        quadraticTo(w, h * .22f, w, h * .38f); lineTo(w, h * .94f); quadraticTo(w, h, w * .9f, h)
        lineTo(w * .1f, h); quadraticTo(0f, h, 0f, h * .94f); lineTo(0f, h * .38f); quadraticTo(0f, h * .22f, w * .38f, h * .14f); close()
    }
    drawPath(glass, Color(0x99D8F1FF))
    drawPath(glass, Color(0xFF5A9BD5), style = Stroke(w * .03f, join = StrokeJoin.Round))
    drawRoundRect(Color(0xFFFF8A65), Offset(w * .34f, -h * .03f), Size(w * .32f, h * .06f), CornerRadius(w * .04f))
    drawLine(Color(0x99FFFFFF), Offset(w * .14f, h * .42f), Offset(w * .14f, h * .86f), w * .05f, StrokeCap.Round)
}

/* ---------- CATEGORY_HOUSE ---------- */

val HouseRect = Rect(420f, 140f, 860f, 665f)
private val HouseBody = Rect(440f, 300f, 840f, 665f)

private fun houseCells(count: Int): List<Pair<Offset, Float>> {
    val fit = fitGrid(count, 340f, 320f, 14f, maxSide = 150f)
    val usedW = fit.cols * fit.side + (fit.cols - 1) * 14f
    val usedH = fit.rows * fit.side + (fit.rows - 1) * 14f
    val left = HouseBody.left + (HouseBody.width - usedW) / 2f
    val top = HouseBody.top + 20f + (HouseBody.height - 40f - usedH) / 2f
    return List(count) { i -> Offset(left + (i % fit.cols) * (fit.side + 14f), top + (i / fit.cols) * (fit.side + 14f)) to fit.side }
}

/** CATEGORY_HOUSE: a house with a category on the roof; move only the matching pictures inside. */
@Composable
fun CategoryHouseGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onMove: (String, String?) -> Unit) {
    val exercise = state.exercise ?: return
    val config = exercise.gameConfig
    val items = rememberShuffled(state, exercise.options)
    val targets = exercise.options.count { it.isTarget == true }
    val placedLocal = remember(state.sessionExerciseId) { mutableStateListOf<String>() }
    val placedIds = doneIds(state, placedLocal).toList()
    val shake = remember(state.sessionExerciseId) { mutableStateMapOf<String, Int>() }
    var noteTick by remember(state.sessionExerciseId) { mutableIntStateOf(0) }
    val side = scatterSide(items.size)
    val slots = remember(state.sessionExerciseId, items.size) { scatterSlots(items.size, state.sessionExerciseId, side) }
    val cells = houseCells(targets.coerceAtLeast(1))
    val done = targets > 0 && placedIds.size >= targets
    Box(Modifier.fillMaxSize()) {
        At(HouseRect.left.toInt(), HouseRect.top.toInt(), HouseRect.width.toInt(), HouseRect.height.toInt()) {
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / HouseRect.width
                withTransform({ scale(k, k, Offset.Zero) }) {
                    val bx = HouseBody.left - HouseRect.left
                    val by = HouseBody.top - HouseRect.top
                    drawRect(Color(0x268C5A1E), Offset(bx + 10f, by + 14f), Size(HouseBody.width, HouseBody.height - 10f))
                    drawRect(Color(0xFFFFE7B3), Offset(bx, by), Size(HouseBody.width, HouseBody.height - 14f))
                    drawRect(Color(0xFFB83A26), Offset(HouseRect.width * .72f, 30f), Size(38f, 90f))
                    drawPath(Path().apply { moveTo(0f, by + 6f); lineTo(HouseRect.width / 2f, 0f); lineTo(HouseRect.width, by + 6f); close() }, Color(0xFFE5533D))
                    drawPath(Path().apply { moveTo(0f, by + 6f); lineTo(HouseRect.width / 2f, 0f); lineTo(HouseRect.width, by + 6f) }, Color(0xFFB83A26), style = Stroke(14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            cells.forEach { (corner, s) ->
                Box(
                    Modifier.offset((corner.x - HouseRect.left).u, (corner.y - HouseRect.top).u).size(s.u)
                        .clip(RoundedCornerShape(24.u)).background(Color(0xFFFFF6E0))
                        .border(4.u, Color(0xFFE5CFA0), RoundedCornerShape(24.u))
                )
            }
            Box(Modifier.offset(0.u, 92.u).size(HouseRect.width.u, 80.u), contentAlignment = Alignment.Center) {
                Text(
                    (config?.categoryLabel ?: "").uppercase(), color = Play.Ink, fontSize = 38.us, fontWeight = FontWeight.Black, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(30.u)).background(Color.White).padding(horizontal = 22.u, vertical = 4.u)
                )
            }
        }
        items.forEachIndexed { i, item ->
            val order = placedIds.indexOf(item.id)
            val (hx, hy) = slots[i]
            val cell = cells.getOrNull(order.coerceAtMost(cells.size - 1))
            DraggablePiece(
                key = "${state.sessionExerciseId}-${item.id}",
                home = Offset(hx, hy), side = side,
                settled = if (order >= 0 && cell != null) cell.first else null,
                settledSide = cell?.second ?: side,
                enabled = interactive && order < 0 && !done,
                shake = shake[item.id] ?: 0,
                description = item.label,
                onDrop = { center ->
                    if (HouseRect.contains(center)) {
                        onMove(item.id, null)
                        if (targetAccepted(item, placedIds.toSet())) placedLocal += item.id
                        else { shake[item.id] = (shake[item.id] ?: 0) + 1; noteTick++ }
                    }
                }
            ) { dragging ->
                PictureCard(Modifier.fillMaxSize(), radius = 28, lip = Color(0x33503C78), ring = if (dragging) Play.Sun else null) {
                    OptionPicture(item, mediaToken, Modifier.fillMaxSize().padding(12.u), textSize = 30)
                    if (specialistView && order < 0 && item.isTarget == true) TargetBadge()
                }
                if (order >= 0 && !specialistView) StarBurst(item.id, Modifier.fillMaxSize())
            }
        }
        if (done && !specialistView) StarBurst(state.sessionExerciseId ?: "house", Modifier.fillMaxSize())
        GentleNote("Это живёт в другом домике", noteTick, y = 690)
    }
}

/* ---------- MISSING ---------- */

private const val MISSING_TOP = 180f
private const val MISSING_W = 540f
private const val MISSING_H = 310f
private val MissingLeft = listOf(70f, 670f)

private fun missingCell(panel: Int, cell: Int): Offset =
    Offset(MissingLeft[panel] + 60f + (cell % 3) * 170f, MISSING_TOP + 28f + (cell / 3) * 92f)

/** Answer row geometry shared with the hint hand. */
fun missingAnswerRow(count: Int): Pair<Float, List<Float>> {
    val side = minOf(150f, (1180f - (count - 1) * 20f) / count.coerceAtLeast(1))
    val used = count * side + (count - 1) * 20f
    return side to List(count) { (1280f - used) / 2f + it * (side + 20f) }
}

const val MISSING_ROW_TOP = 530f

/** MISSING: "before" and "after" scenes; tap every picture that disappeared. */
@Composable
fun MissingGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, mediaToken: String?, onMove: (String, String?) -> Unit) {
    val exercise = state.exercise ?: return
    val items = exercise.options.sortedBy { it.position }
    val answers = rememberShuffled(state, exercise.options)
    val foundLocal = remember(state.sessionExerciseId) { mutableStateListOf<String>() }
    val found = doneIds(state, foundLocal)
    val shake = remember(state.sessionExerciseId) { mutableStateMapOf<String, Int>() }
    val targets = items.filter { it.isTarget == true }
    val done = targets.isNotEmpty() && targets.all { it.id in found }
    Box(Modifier.fillMaxSize()) {
        listOf("Было", "Стало").forEachIndexed { panel, title ->
            At(MissingLeft[panel].toInt(), MISSING_TOP.toInt(), MISSING_W.toInt(), MISSING_H.toInt()) {
                PictureCard(Modifier.fillMaxSize(), radius = 40, lip = Color(0x33503C78), face = if (panel == 0) Color.White else Color(0xFFFFFBF0)) {}
            }
            At(MissingLeft[panel].toInt() + 190, MISSING_TOP.toInt() - 34, 160, 56) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.u)).background(if (panel == 0) Play.Blue else Play.Coral), contentAlignment = Alignment.Center) {
                    Text(title, color = Color.White, fontSize = 30.us, fontWeight = FontWeight.Black)
                }
            }
            items.forEachIndexed { i, item ->
                val cell = (item.cellIndex ?: i).coerceIn(0, 8)
                val p = missingCell(panel, cell)
                val gone = panel == 1 && item.isTarget == true
                val back = gone && item.id in found
                At(p.x.toInt(), p.y.toInt(), 90, 90) {
                    when {
                        !gone || back -> Box(Modifier.fillMaxSize().then(if (back) Modifier.popIn(item.id) else Modifier)) {
                            OptionPicture(item, mediaToken, Modifier.fillMaxSize(), textSize = 30)
                            if (back && !specialistView) StarBurst(item.id, Modifier.fillMaxSize())
                        }
                        specialistView -> Canvas(Modifier.fillMaxSize()) {
                            drawRoundRect(Play.Grass, cornerRadius = CornerRadius(20f), style = Stroke(5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
                        }
                    }
                }
            }
        }
        val (side, xs) = missingAnswerRow(answers.size)
        answers.forEachIndexed { i, option ->
            val isFound = option.id in found
            At(xs[i].toInt(), MISSING_ROW_TOP.toInt(), side.toInt(), side.toInt(), Modifier.floating(amplitude = 6f, periodMs = 3000 + i * 200, delayMs = i * 300)) {
                PictureCard(
                    Modifier.fillMaxSize()
                        .wiggle(shake[option.id] ?: 0)
                        .semantics { contentDescription = option.label }
                        .clickable(interactionSource = null, indication = null, enabled = interactive && !isFound && !done, role = Role.Button) {
                            onMove(option.id, null)
                            if (targetAccepted(option, found)) foundLocal += option.id
                            else shake[option.id] = (shake[option.id] ?: 0) + 1
                        },
                    radius = 32,
                    ring = when {
                        isFound -> Play.Sun
                        specialistView && option.isTarget == true -> Play.Grass
                        else -> null
                    }
                ) {
                    OptionPicture(option, mediaToken, Modifier.fillMaxSize().padding(16.u), textSize = 30)
                    if (isFound) TargetBadge()
                }
            }
        }
        if (done && !specialistView) StarBurst(state.sessionExerciseId ?: "missing", Modifier.fillMaxSize())
    }
}

/* ---------- PARKING ---------- */

fun parkingColor(key: String?): Color = when (key) {
    "RED" -> Color(0xFFE5533D)
    "BLUE" -> Color(0xFF3E7CD6)
    "YELLOW" -> Color(0xFFFFC23D)
    "GREEN" -> Color(0xFF2EAD5B)
    "ORANGE" -> Color(0xFFFF8A3D)
    "PURPLE" -> Color(0xFF8E5BD9)
    "PINK" -> Color(0xFFFF7FAF)
    else -> Color(0xFF9AA5B8)
}

/** Parking lot geometry shared with the hint hand. */
object ParkingLot {
    val Lot = Rect(90f, 150f, 1190f, 480f)
    const val BAY_TOP = 180f
    const val BAY_H = 270f
    const val CAR_W = 110f
    const val CAR_H = 170f
    const val CAR_TOP = 575f

    fun bayX(i: Int, count: Int): Pair<Float, Float> {
        val w = minOf(150f, (1060f - (count - 1) * 12f) / count.coerceAtLeast(1))
        val used = count * w + (count - 1) * 12f
        return (640f - used / 2f + i * (w + 12f)) to w
    }

    fun carHome(i: Int, count: Int): Offset {
        val gap = minOf(30f, (1200f - count * CAR_W) / (count - 1).coerceAtLeast(1))
        val used = count * CAR_W + (count - 1) * gap
        return Offset(640f - used / 2f + i * (CAR_W + gap), CAR_TOP)
    }
}

/** PARKING: drag every car onto a parking bay of its colour. */
@Composable
fun ParkingGame(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, onMove: (String, String?) -> Unit) {
    val exercise = state.exercise ?: return
    val palette = exercise.gameConfig?.groupLabels.orEmpty()
    val cars = rememberShuffled(state, exercise.options)
    val bays = remember(palette, exercise.options) { parkingBays(palette, exercise.options) }
    val parkedLocal = remember(state.sessionExerciseId) { mutableStateListOf<String>() }
    val parkedIds = doneIds(state, parkedLocal).toList()
    val shake = remember(state.sessionExerciseId) { mutableStateMapOf<String, Int>() }
    val done = cars.isNotEmpty() && cars.all { it.id in parkedIds }
    /** Bay index where a parked car stands: the k-th bay of its colour. */
    fun bayOf(car: ExerciseOptionUiModel): Int? {
        val k = parkedIds.filter { id -> exercise.options.byId(id)?.groupKey == car.groupKey }.indexOf(car.id)
        if (k < 0) return null
        val ofColor = bays.filter { it.color == car.groupKey }
        return (ofColor.getOrNull(k) ?: ofColor.lastOrNull())?.index
    }
    Box(Modifier.fillMaxSize()) {
        At(ParkingLot.Lot.left.toInt(), ParkingLot.Lot.top.toInt(), ParkingLot.Lot.width.toInt(), ParkingLot.Lot.height.toInt()) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(40.u)).background(Color(0xFF6B7489)))
        }
        bays.forEach { bay ->
            val (x, w) = ParkingLot.bayX(bay.index, bays.size)
            val color = parkingColor(bay.color)
            At(x.toInt(), ParkingLot.BAY_TOP.toInt(), w.toInt(), ParkingLot.BAY_H.toInt()) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRoundRect(color.copy(alpha = .28f), cornerRadius = CornerRadius(18f))
                    drawRoundRect(color, cornerRadius = CornerRadius(18f), style = Stroke(size.width * .07f))
                    drawRoundRect(color, Offset(0f, 0f), Size(size.width, size.height * .12f), CornerRadius(18f))
                    drawCircle(Color.White.copy(alpha = .85f), size.width * .22f, Offset(size.width / 2f, size.height * .62f))
                    drawCircle(color, size.width * .16f, Offset(size.width / 2f, size.height * .62f))
                }
            }
        }
        cars.forEachIndexed { i, car ->
            val bay = bayOf(car)
            val settled = bay?.let { b ->
                val (x, w) = ParkingLot.bayX(b, bays.size)
                val cw = minOf(ParkingLot.CAR_W, w - 16f)
                Offset(x + (w - cw) / 2f, ParkingLot.BAY_TOP + (ParkingLot.BAY_H - ParkingLot.CAR_H * cw / ParkingLot.CAR_W) / 2f + 10f) to cw
            }
            DraggablePiece(
                key = "${state.sessionExerciseId}-${car.id}",
                home = ParkingLot.carHome(i, cars.size), side = ParkingLot.CAR_H, width = ParkingLot.CAR_W,
                settled = settled?.first,
                settledSide = ParkingLot.CAR_H * (settled?.second ?: ParkingLot.CAR_W) / ParkingLot.CAR_W,
                settledWidth = settled?.second ?: ParkingLot.CAR_W,
                enabled = interactive && bay == null && !done,
                shake = shake[car.id] ?: 0,
                description = "машинка ${car.label}",
                onDrop = { center ->
                    val target = bays.firstOrNull { b ->
                        val (x, w) = ParkingLot.bayX(b.index, bays.size)
                        center.x in x..(x + w) && center.y in (ParkingLot.BAY_TOP - 20f)..(ParkingLot.BAY_TOP + ParkingLot.BAY_H + 20f)
                    }
                    if (target != null) {
                        onMove(car.id, target.color)
                        if (colorMoveAccepted(car, target.color, palette, parkedIds.toSet())) parkedLocal += car.id
                        else shake[car.id] = (shake[car.id] ?: 0) + 1
                    }
                }
            ) {
                CarGlyph(parkingColor(car.groupKey), Modifier.fillMaxSize())
                if (bay != null && !specialistView) StarBurst(car.id, Modifier.fillMaxSize())
            }
        }
        if (done && !specialistView) StarBurst(state.sessionExerciseId ?: "parking", Modifier.fillMaxSize())
        if (specialistView) {
            At(90, 495, 1100, 60) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("Каждая машинка — на место своего цвета", color = Play.Ink, fontSize = 26.us, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** A toy car seen from above, pointing up. */
@Composable
fun CarGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val wheel = Color(0xFF2B2F3A)
        listOf(.2f, .76f).forEach { y ->
            drawRoundRect(wheel, Offset(0f, h * y), Size(w * .2f, h * .16f), CornerRadius(w * .06f))
            drawRoundRect(wheel, Offset(w * .8f, h * y), Size(w * .2f, h * .16f), CornerRadius(w * .06f))
        }
        drawRoundRect(Color(0x33000000), Offset(w * .1f, h * .04f), Size(w * .84f, h * .96f), CornerRadius(w * .3f))
        drawRoundRect(color, Offset(w * .08f, 0f), Size(w * .84f, h * .96f), CornerRadius(w * .3f))
        drawRoundRect(Color(0xFFBFE6FF), Offset(w * .2f, h * .2f), Size(w * .6f, h * .16f), CornerRadius(w * .1f))
        drawRoundRect(Color(0xFFBFE6FF), Offset(w * .22f, h * .66f), Size(w * .56f, h * .12f), CornerRadius(w * .1f))
        drawRoundRect(Color.White.copy(alpha = .35f), Offset(w * .22f, h * .4f), Size(w * .56f, h * .22f), CornerRadius(w * .1f))
        drawCircle(Color(0xFFFFE066), w * .07f, Offset(w * .26f, h * .06f))
        drawCircle(Color(0xFFFFE066), w * .07f, Offset(w * .74f, h * .06f))
    }
}
