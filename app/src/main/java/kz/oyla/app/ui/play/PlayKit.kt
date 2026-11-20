package kz.oyla.app.ui.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Palette of the child world. Text colours keep at least 4.5:1 on white. */
object Play {
    val Ink = Color(0xFF1E2A4A)
    val Coral = Color(0xFFFF8A65)
    val CoralDeep = Color(0xFFC9471F)
    val Sun = Color(0xFFFFC23D)
    val SunDeep = Color(0xFFC98E0F)
    val Grass = Color(0xFF2EAD5B)
    val GrassDeep = Color(0xFF1D7A3E)
    val Sky = Color(0xFF6EC3FF)
    val Teal = Color(0xFF22C3C9)
    val TealDark = Color(0xFF1FB3BA)
    val TealBelly = Color(0xFFC8F5F3)
    val Blue = Color(0xFF3E8EDE)
    val BlueDeep = Color(0xFF275A85)
    val Pink = Color(0xFFFF9FB2)
    val White = Color.White
}

/**
 * Child screens are designed on a 1280×800 artboard (the mock-ups). [unit] converts one
 * artboard pixel to dp so the same layout fills any tablet and also shrinks into the
 * specialist's live preview.
 */
class PlayScale(val unit: Float, val calm: Boolean)

val LocalPlay = compositionLocalOf { PlayScale(1f, false) }

@Composable
fun PlayCanvas(modifier: Modifier = Modifier, calm: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val unit = minOf(maxWidth.value / 1280f, maxHeight.value / 800f)
        CompositionLocalProvider(LocalPlay provides PlayScale(unit, calm)) {
            Box(Modifier.fillMaxSize(), content = content)
        }
    }
}

val Int.u: Dp @Composable get() = (this * LocalPlay.current.unit).dp
val Float.u: Dp @Composable get() = (this * LocalPlay.current.unit).dp
val Int.us: TextUnit @Composable get() = with(LocalDensity.current) { (this@us * LocalPlay.current.unit).dp.toSp() }

/** Gentle up/down bobbing; disabled in calm mode. */
@Composable
fun Modifier.floating(amplitude: Float = 12f, periodMs: Int = 2600, delayMs: Int = 0): Modifier {
    if (LocalPlay.current.calm) return this
    val transition = rememberInfiniteTransition(label = "float")
    val t by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(periodMs, delayMillis = 0, easing = LinearEasing), RepeatMode.Restart,
            initialStartOffset = androidx.compose.animation.core.StartOffset(delayMs)), label = "floatT"
    )
    val unit = LocalPlay.current.unit
    val density = LocalDensity.current.density
    return graphicsLayer { translationY = -amplitude * unit * density * ((1 - cos(2 * PI * t)) / 2).toFloat() }
}

/** Soft breathing scale, used for the buttons the child should press next. */
@Composable
fun Modifier.pulsing(from: Float = 1f, to: Float = 1.06f, periodMs: Int = 1800): Modifier {
    if (LocalPlay.current.calm) return this
    val transition = rememberInfiniteTransition(label = "pulse")
    val s by transition.animateFloat(from, to, infiniteRepeatable(tween(periodMs / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulseS")
    return graphicsLayer { scaleX = s; scaleY = s }
}

/** Shakes the element each time [trigger] changes (wrong answer); never red, never a cross. */
@Composable
fun Modifier.wiggle(trigger: Int): Modifier {
    val offset = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        offset.snapTo(0f)
        offset.animateTo(0f, keyframes {
            durationMillis = 560
            -16f at 110; 14f at 220; -10f at 330; 6f at 440; 0f at 560
        })
    }
    val unit = LocalPlay.current.unit
    val density = LocalDensity.current.density
    return graphicsLayer { translationX = offset.value * unit * density; rotationZ = offset.value / 4f }
}

/** Springy entrance scale. */
@Composable
fun Modifier.popIn(key: Any? = Unit, delayMs: Int = 0): Modifier {
    val scale = remember(key) { Animatable(0.4f) }
    LaunchedEffect(key) {
        kotlinx.coroutines.delay(delayMs.toLong())
        scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
    }
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = ((scale.value - 0.4f) / 0.6f).coerceIn(0f, 1f) }
}

/**
 * The "chunky" toy button from the mock-ups: a solid face over a darker lip that sinks when
 * pressed. [depth] is in artboard pixels.
 */
@Composable
fun ChunkyButton(
    onClick: () -> Unit,
    color: Color,
    lipColor: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    depth: Int = 8,
    enabled: Boolean = true,
    description: String? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink = if (pressed) depth.u * 0.75f else 0.dp
    Box(
        modifier
            .padding(bottom = depth.u)
            .semantics { if (description != null) contentDescription = description }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
    ) {
        Box(Modifier.matchParentSize().offset(y = depth.u).clip(shape).background(lipColor))
        Box(Modifier.matchParentSize().offset(y = sink).clip(shape).background(color))
        Box(Modifier.matchParentSize().offset(y = sink), contentAlignment = Alignment.Center, content = content)
    }
}

/** A white rounded card with the soft bottom lip used for pictures. */
@Composable
fun PictureCard(
    modifier: Modifier = Modifier,
    radius: Int = 44,
    lip: Color = Color(0x40144A80),
    face: Color = Color.White,
    ring: Color? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius.u)
    Box(modifier) {
        Box(Modifier.matchParentSize().offset(y = 10.u).clip(shape).background(lip))
        Box(
            Modifier.matchParentSize().clip(shape).background(face)
                .then(if (ring != null) Modifier.border(10.u, ring, shape) else Modifier),
            contentAlignment = Alignment.Center, content = content
        )
    }
}

enum class MascotMood { NORMAL, HAPPY, THINKING, TALKING }

/** Ойлик, the guide character, drawn on a 200×200 grid. */
@Composable
fun Mascot(modifier: Modifier = Modifier, mood: MascotMood = MascotMood.NORMAL, waving: Boolean = false) {
    val calm = LocalPlay.current.calm
    val transition = rememberInfiniteTransition(label = "mascot")
    val wave by transition.animateFloat(-18f, 14f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wave")
    val talk by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(220), RepeatMode.Reverse), label = "talk")
    val blink by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "blink")
    Canvas(modifier.semantics { contentDescription = "Ойлик" }) {
        val k = size.minDimension / 200f
        withTransform({ translate((size.width - 200 * k) / 2, (size.height - 200 * k) / 2); scale(k, k, Offset.Zero) }) {
            drawOval(Color(0x1F000000), Offset(48f, 182f), Size(104f, 16f))
            drawPath(poly(62f to 52f, 50f to 16f, 86f to 42f), Play.TealDark)
            drawPath(poly(138f to 52f, 150f to 16f, 114f to 42f), Play.TealDark)
            val body = Path().apply {
                moveTo(38f, 112f); quadraticTo(38f, 40f, 100f, 40f); quadraticTo(162f, 40f, 162f, 112f)
                quadraticTo(162f, 180f, 100f, 180f); quadraticTo(38f, 180f, 38f, 112f); close()
            }
            drawPath(body, Play.Teal)
            drawOval(Play.TealBelly, Offset(60f, 108f), Size(80f, 64f))
            drawCircle(Play.Pink.copy(alpha = .7f), 7f, Offset(60f, 124f))
            drawCircle(Play.Pink.copy(alpha = .7f), 7f, Offset(140f, 124f))
            when (mood) {
                MascotMood.HAPPY -> {
                    val arc = Stroke(width = 7f, cap = StrokeCap.Round)
                    drawArc(Play.Ink, 200f, 140f, false, Offset(58f, 86f), Size(36f, 30f), style = arc)
                    drawArc(Play.Ink, 200f, 140f, false, Offset(106f, 86f), Size(36f, 30f), style = arc)
                    drawPath(Path().apply { moveTo(84f, 120f); quadraticTo(100f, 142f, 116f, 120f); close() }, Play.Ink)
                }
                else -> {
                    val closed = !calm && blink > 0.96f
                    val look = if (mood == MascotMood.THINKING) Offset(6f, -6f) else Offset(4f, 3f)
                    listOf(76f, 124f).forEach { x ->
                        if (closed) {
                            drawLine(Play.Ink, Offset(x - 16f, 96f), Offset(x + 16f, 96f), 6f, StrokeCap.Round)
                        } else {
                            drawCircle(Color.White, 23f, Offset(x, 96f))
                            drawCircle(Play.Ink, 11f, Offset(x, 96f) + look)
                            drawCircle(Color.White, 4f, Offset(x + 3f, 91f) + look)
                        }
                    }
                    if (mood == MascotMood.TALKING) {
                        val open = if (calm) .6f else talk
                        drawOval(Play.Ink, Offset(90f, 120f), Size(20f, 6f + 12f * open))
                    } else {
                        drawPath(poly(91f to 120f, 109f to 120f, 100f to 131f), Color(0xFFFFB648))
                    }
                }
            }
            if (waving) {
                rotate(if (calm) 0f else wave, Offset(160f, 118f)) {
                    drawOval(Play.Teal, Offset(152f, 70f), Size(30f, 56f))
                }
            }
        }
    }
}

private fun poly(vararg points: Pair<Float, Float>) = Path().apply {
    points.forEachIndexed { index, (x, y) -> if (index == 0) moveTo(x, y) else lineTo(x, y) }
    close()
}

fun starPath(center: Offset, outer: Float, inner: Float = outer * 0.46f): Path = Path().apply {
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val angle = -PI / 2 + i * PI / 5
        val p = Offset(center.x + (r * cos(angle)).toFloat(), center.y + (r * sin(angle)).toFloat())
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

@Composable
fun StarIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawPath(starPath(center, size.minDimension / 2f), color) }
}

/** Speaker glyph for "listen again". */
@Composable
fun SpeakerGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawPath(Path().apply {
            moveTo(w * .14f, h * .38f); lineTo(w * .32f, h * .38f); lineTo(w * .54f, h * .2f)
            lineTo(w * .54f, h * .8f); lineTo(w * .32f, h * .62f); lineTo(w * .14f, h * .62f); close()
        }, color)
        val stroke = Stroke(width = w * .08f, cap = StrokeCap.Round)
        drawArc(color, -45f, 90f, false, Offset(w * .44f, h * .32f), Size(w * .26f, h * .36f), style = stroke)
        drawArc(color, -50f, 100f, false, Offset(w * .4f, h * .18f), Size(w * .48f, h * .64f), style = stroke)
    }
}

@Composable
fun ArrowGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val stroke = Stroke(width = w * .13f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        drawPath(Path().apply { moveTo(w * .2f, h * .5f); lineTo(w * .76f, h * .5f); moveTo(w * .5f, h * .24f); lineTo(w * .76f, h * .5f); lineTo(w * .5f, h * .76f) }, color, style = stroke)
    }
}

@Composable
fun CheckGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawPath(Path().apply { moveTo(w * .2f, h * .52f); lineTo(w * .42f, h * .74f); lineTo(w * .8f, h * .28f) }, color,
            style = Stroke(width = w * .14f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** Plan progress as beads; finished beads hold a star. */
@Composable
fun ProgressBeads(total: Int, current: Int, currentDone: Boolean, modifier: Modifier = Modifier, onDark: Boolean = true) {
    if (total <= 0) return
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (onDark) Color.White.copy(alpha = .22f) else Color.White.copy(alpha = .85f))
            .padding(horizontal = 18.u, vertical = 12.u),
        horizontalArrangement = Arrangement.spacedBy(12.u),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            val position = index + 1
            val done = position < current || (position == current && currentDone)
            val isCurrent = position == current && !done
            Box(
                Modifier.size(26.u).clip(CircleShape).background(
                    when {
                        done -> Play.Sun
                        isCurrent -> Play.Sun
                        else -> if (onDark) Color.White.copy(alpha = .35f) else Color(0xFFD5DBE8)
                    }
                ),
                contentAlignment = Alignment.Center
            ) {
                if (done) StarIcon(Color.White, Modifier.size(17.u))
                if (isCurrent) Box(Modifier.size(12.u).clip(CircleShape).background(Color.White))
            }
        }
    }
}

/** Stars flying outwards from the centre, replayed whenever [trigger] changes. */
@Composable
fun StarBurst(trigger: Any, modifier: Modifier = Modifier) {
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    val colors = listOf(Color(0xFFFFE066), Color.White, Play.Pink, Color(0xFFB9F3E4), Play.Sun)
    Canvas(modifier) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        val reach = size.minDimension * .55f
        repeat(12) { i ->
            val angle = i * (2 * PI / 12) + 0.3
            val r = reach * p
            val c = center + Offset((cos(angle) * r).toFloat(), (sin(angle) * r).toFloat())
            val s = size.minDimension * (0.035f + 0.02f * (i % 3))
            drawStarFaded(c, s * (0.4f + p), colors[i % colors.size], 1f - p)
        }
    }
}

private fun DrawScope.drawStarFaded(c: Offset, r: Float, color: Color, alpha: Float) {
    drawPath(starPath(c, r), color.copy(alpha = alpha.coerceIn(0f, 1f)))
}

/** Hidden adult control: hold for [holdMs] to trigger. Shows only a faint ring. */
@Composable
fun HoldToTrigger(onTriggered: () -> Unit, modifier: Modifier = Modifier, holdMs: Long = 3000, tint: Color = Color.White) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .size(56.u)
            .semantics { contentDescription = "Удерживайте 3 секунды: меню взрослого" }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdMs.toInt(), easing = LinearEasing))
                        onTriggered()
                        progress.snapTo(0f)
                    }
                    tryAwaitRelease()
                    job.cancel()
                    scope.launch { progress.snapTo(0f) }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            drawArc(tint.copy(alpha = .35f), -90f, 360f, false, style = stroke)
            drawArc(tint, -90f, progress.value * 360f, false, style = stroke)
            drawCircle(tint.copy(alpha = .5f), size.minDimension * .08f)
        }
    }
}

/**
 * Fires on finger-down, every time, independent of recompositions. Used for drums and
 * moving targets where a child taps fast and repeatedly.
 */
@Composable
fun Modifier.onTouchDown(enabled: Boolean, action: () -> Unit): Modifier {
    val enabledState = androidx.compose.runtime.rememberUpdatedState(enabled)
    val actionState = androidx.compose.runtime.rememberUpdatedState(action)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            if (enabledState.value) actionState.value()
        }
    }
}
