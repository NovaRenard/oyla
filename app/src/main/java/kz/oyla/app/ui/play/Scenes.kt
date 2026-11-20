package kz.oyla.app.ui.play

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.sin

/** Every exercise lives in its own full-screen world instead of a window on a shared background. */
enum class SceneKind { SKY, SEA, ISLANDS, NIGHT, PINK, GARDEN, MEADOW, KITCHEN, PAPER, BOARD, PARTY, BALLOONS, WARM, LAVENDER, CREAM }

fun sceneFor(activityType: String?): SceneKind = when (activityType) {
    "SINGLE_CHOICE" -> SceneKind.SEA
    "MEMORY" -> SceneKind.NIGHT
    "REMEMBER" -> SceneKind.PINK
    "ODD_ONE" -> SceneKind.GARDEN
    "MAZE" -> SceneKind.MEADOW
    "SORT" -> SceneKind.KITCHEN
    "WORD_BUILD" -> SceneKind.PAPER
    "WHITEBOARD" -> SceneKind.BOARD
    "POP" -> SceneKind.BALLOONS
    "SYLLABLES" -> SceneKind.WARM
    "SHADOW" -> SceneKind.LAVENDER
    "COUNT" -> SceneKind.CREAM
    else -> SceneKind.SKY
}

@Composable
fun SceneBackground(kind: SceneKind, modifier: Modifier = Modifier.fillMaxSize()) {
    val calm = LocalPlay.current.calm
    val transition = rememberInfiniteTransition(label = "scene")
    val slow by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(40_000, easing = LinearEasing)), label = "slow")
    val mid by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "mid")
    val sway by transition.animateFloat(-1f, 1f, infiniteRepeatable(tween(2_200), RepeatMode.Reverse), label = "sway")
    val t = if (calm) 0.3f else slow
    val m = if (calm) 0.3f else mid
    val w = if (calm) 0f else sway
    Canvas(modifier) {
        withTransform({ scale(size.width / 1280f, size.height / 800f, Offset.Zero) }) {
            when (kind) {
                SceneKind.SKY -> sky(t, m)
                SceneKind.SEA -> sea(m, w)
                SceneKind.ISLANDS -> islands(m)
                SceneKind.NIGHT -> night(m)
                SceneKind.PINK -> pink()
                SceneKind.GARDEN -> garden()
                SceneKind.MEADOW -> meadow()
                SceneKind.KITCHEN -> kitchen()
                SceneKind.PAPER -> paper()
                SceneKind.BOARD -> board()
                SceneKind.PARTY -> party(m)
                SceneKind.BALLOONS -> balloonSky(t)
                SceneKind.WARM -> { drawRect(Color(0xFFFFE9D6), size = Size(W, H)); drawCircle(Color(0xFFFFDCC0), 260f, Offset(160f, 140f)); drawRect(Color(0xFFF2B98C), Offset(0f, 560f), Size(W, 240f)) }
                SceneKind.LAVENDER -> drawRect(Color(0xFFEDE7FF), size = Size(W, H))
                SceneKind.CREAM -> drawRect(Color(0xFFFFF4DB), size = Size(W, H))
            }
        }
    }
}

private val W = 1280f
private val H = 800f

private fun DrawScope.cloud(x: Float, y: Float, scale: Float, alpha: Float) {
    val c = Color.White.copy(alpha = alpha)
    drawCircle(c, 30f * scale, Offset(x + 40f * scale, y + 50f * scale))
    drawCircle(c, 42f * scale, Offset(x + 92f * scale, y + 36f * scale))
    drawCircle(c, 34f * scale, Offset(x + 150f * scale, y + 46f * scale))
    drawRoundRect(c, Offset(x + 20f * scale, y + 46f * scale), Size(170f * scale, 34f * scale), androidx.compose.ui.geometry.CornerRadius(17f * scale))
}

private fun DrawScope.sky(t: Float, m: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF6EC3FF), Color(0xFFB5E3FF), Color(0xFFE6F6FF)), 0f, H), size = Size(W, H))
    val glow = 1f + 0.08f * sin(m * 2 * PI).toFloat()
    drawCircle(Color(0x1FFFD54F), 130f * glow, Offset(1080f, 150f))
    drawCircle(Color(0x47FFD54F), 100f * glow, Offset(1080f, 150f))
    drawCircle(Color(0xFFFFD54F), 75f, Offset(1080f, 150f))
    fun drift(speed: Float, offset: Float) = ((t * speed + offset) % 1f) * (W + 400f) - 260f
    cloud(drift(1f, 0f), 90f, 1.1f, 1f)
    cloud(drift(0.75f, 0.45f), 230f, 0.8f, .85f)
    cloud(drift(0.9f, 0.8f), 40f, 0.6f, .7f)
    drawPath(Path().apply { moveTo(0f, 650f); quadraticTo(260f, 540f, 560f, 630f); quadraticTo(860f, 720f, W, 590f); lineTo(W, H); lineTo(0f, H); close() }, Color(0xFF9BE08F))
    drawPath(Path().apply { moveTo(0f, 720f); quadraticTo(340f, 630f, 700f, 710f); quadraticTo(1000f, 770f, W, 690f); lineTo(W, H); lineTo(0f, H); close() }, Color(0xFF6CCB6A))
    listOf(Triple(1080f, 650f, Color(0xFFFF8FA3)), Triple(1110f, 668f, Color(0xFFFFD54F)), Triple(190f, 696f, Color.White), Triple(230f, 714f, Color(0xFFFF8FA3)))
        .forEach { (x, y, c) -> drawCircle(c, 9f, Offset(x, y)) }
}

private fun DrawScope.sea(m: Float, sway: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF3FB4E6), Color(0xFF1F86C7), Color(0xFF16679F)), 0f, H), size = Size(W, H))
    listOf(Triple(120f, 0f, 13f), Triple(640f, .35f, 9f), Triple(1180f, .7f, 16f), Triple(900f, .15f, 7f)).forEach { (x, phase, r) ->
        val p = (m + phase) % 1f
        val y = H + 40f - p * 880f
        drawCircle(Color.White.copy(alpha = .6f * (1f - p)), r, Offset(x + 10f * sin(p * 12f), y), style = Stroke(4f))
    }
    drawPath(Path().apply { moveTo(0f, 740f); quadraticTo(320f, 690f, 640f, 740f); quadraticTo(960f, 790f, W, 730f); lineTo(W, H); lineTo(0f, H); close() }, Color(0xFFF2D38E))
    seaweed(70f, 760f, 200f, sway, Color(0xFF2FB36B))
    seaweed(1210f, 770f, 240f, -sway, Color(0xFF27A05E))
}

private fun DrawScope.seaweed(x: Float, base: Float, height: Float, sway: Float, color: Color) {
    val bend = 18f * sway
    drawPath(Path().apply {
        moveTo(x, base); quadraticTo(x - 24f + bend, base - height * .3f, x - 2f + bend, base - height * .5f)
        quadraticTo(x + 20f + bend * 1.5f, base - height * .75f, x - 6f + bend * 2f, base - height)
    }, color, style = Stroke(14f, cap = StrokeCap.Round))
}

private fun DrawScope.waves(m: Float, color: Color, rows: List<Float>) {
    val shift = -m * 120f
    rows.forEach { y ->
        val path = Path().apply {
            moveTo(shift, y)
            var x = shift
            while (x < W + 120f) { quadraticTo(x + 30f, y - 14f, x + 60f, y); quadraticTo(x + 90f, y + 14f, x + 120f, y); x += 120f }
        }
        drawPath(path, color, style = Stroke(6f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.islands(m: Float) {
    drawRect(Color(0xFF5EC4F0), size = Size(W, H))
    waves(m, Color(0xFF8FD8F7), listOf(120f, 470f, 720f))
}

private fun DrawScope.night(m: Float) {
    drawRect(Color(0xFF2B2F6B), size = Size(W, H))
    listOf(Offset(90f, 120f), Offset(1180f, 90f), Offset(1120f, 640f), Offset(150f, 680f), Offset(640f, 60f), Offset(980f, 380f), Offset(260f, 400f))
        .forEachIndexed { i, p -> drawCircle(Color.White.copy(alpha = (.3f + .7f * ((sin((m + i * .17f) * 2 * PI) + 1) / 2)).toFloat()), 5f + i % 3, p) }
}

private fun DrawScope.pink() {
    drawRect(Color(0xFFFFE3EC), size = Size(W, H))
    drawCircle(Color(0xFFFFD0DE), 260f, Offset(140f, 100f))
    drawCircle(Color(0xFFFFD0DE), 310f, Offset(1290f, 900f))
}

private fun DrawScope.garden() {
    drawRect(Color(0xFFDDF4D8), size = Size(W, H))
    drawRect(Color(0xFFA9DE8E), Offset(0f, 550f), Size(W, 250f))
    drawRoundRect(Color(0xFFB5763F), Offset(150f, 520f), Size(980f, 40f), androidx.compose.ui.geometry.CornerRadius(20f))
    drawRect(Color(0xFF8D5A3B), Offset(180f, 550f), Size(24f, 250f))
    drawRect(Color(0xFF8D5A3B), Offset(1076f, 550f), Size(24f, 250f))
}

private fun DrawScope.meadow() {
    drawRect(Color(0xFFBDE8A8), size = Size(W, H))
    listOf(Triple(80f, 120f, Color.White), Triple(96f, 132f, Color(0xFFFF9FB2)), Triple(1200f, 700f, Color.White), Triple(640f, 60f, Color(0xFFFFD43B)), Triple(420f, 740f, Color(0xFFFF9FB2)), Triple(760f, 420f, Color.White))
        .forEach { (x, y, c) -> drawCircle(c, 9f, Offset(x, y)) }
}

private fun DrawScope.kitchen() {
    drawRect(Color(0xFFFFF1D2), size = Size(W, H))
    var x = 0f
    var odd = false
    while (x < W) { drawRect(if (odd) Color(0xFFFFEFC9) else Color(0xFFFFE7B3), Offset(x, 0f), Size(80f, 520f)); x += 80f; odd = !odd }
    drawRect(Color(0xFFD9965B), Offset(0f, 520f), Size(W, 280f))
    drawRect(Color(0xFFC07A40), Offset(0f, 520f), Size(W, 26f))
}

private fun DrawScope.paper() {
    drawRect(Color(0xFFFFF4DB), size = Size(W, H))
    var y = 58f
    while (y < 560f) { drawRect(Color(0xFFF5E3BA), Offset(0f, y), Size(W, 2f)); y += 60f }
    drawRect(Color(0xFFF2C48D), Offset(0f, 560f), Size(W, 240f))
}

private fun DrawScope.board() {
    drawRect(Color(0xFFFFFDF7), size = Size(W, H))
    var y = 18f
    while (y < H) { var x = 18f; while (x < W) { drawCircle(Color(0xFFE8E1D0), 2f, Offset(x, y)); x += 36f }; y += 36f }
}

private fun DrawScope.party(m: Float) {
    drawRect(Color(0xFF7A5CFA), size = Size(W, H))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF9C85FF), Color(0x007A5CFA)), Offset(640f, 360f), 450f), 450f, Offset(640f, 360f))
    val colors = listOf(Color(0xFFFFD43B), Color(0xFFFF8A65), Color(0xFF2EE6A8), Color.White, Color(0xFFFF9FB2))
    repeat(14) { i ->
        val p = (m * (0.8f + (i % 4) * .15f) + i * .137f) % 1f
        val x = 60f + i * 88f
        val y = -60f + p * 940f
        withTransform({ rotate(p * 540f, Offset(x, y)) }) {
            drawRoundRect(colors[i % colors.size], Offset(x - 9f, y - 14f), Size(18f, 28f), androidx.compose.ui.geometry.CornerRadius(5f))
        }
    }
}

private fun DrawScope.balloonSky(t: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF6EC3FF), Color(0xFFB5E3FF), Color(0xFFE6F6FF)), 0f, H), size = Size(W, H))
    fun drift(speed: Float, offset: Float) = ((t * speed + offset) % 1f) * (W + 400f) - 260f
    cloud(drift(.8f, .1f), 120f, 1.1f, 1f)
    cloud(drift(.6f, .6f), 330f, .8f, .8f)
}
