package kz.oyla.app.ui.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt

/** Children are greeted by first name only ("Амир", not "Амир Б."). */
private fun firstName(full: String?): String? = full?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotEmpty() }

/** Corner control for adults; children don't notice a faint ring. */
@Composable
fun AdultCorner(onOpen: () -> Unit, tint: Color = Color(0xFF8A94AD)) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        HoldToTrigger(onOpen, Modifier.padding(top = 26.u, end = 24.u), tint = tint)
    }
}

@Composable
fun AdultMenuDialog(onDismiss: () -> Unit, onSettings: () -> Unit, onCancelLesson: (() -> Unit)?) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Меню взрослого") },
        text = { Text("Настройки устройства или отмена занятия. Занятие отменится на обоих устройствах.") },
        confirmButton = { TextButton(onClick = { onDismiss(); onSettings() }) { Text("Настройки") } },
        dismissButton = {
            Row {
                if (onCancelLesson != null) TextButton(onClick = { onDismiss(); onCancelLesson() }) { Text("Отменить занятие", color = Color(0xFFB42318)) }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        }
    )
}

/** Greeting while the specialist prepares the first task. */
@Composable
fun ChildWelcomeScene(childName: String?, connected: Boolean, onAdult: () -> Unit) {
    PlayCanvas {
        SceneBackground(SceneKind.SKY)
        Row(
            Modifier.offset(32.u, 28.u).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .92f)).padding(start = 8.u, end = 22.u, top = 8.u, bottom = 8.u),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.u)
        ) {
            Box(Modifier.size(56.u).clip(CircleShape).background(Color(0xFFFFB648)), contentAlignment = Alignment.Center) {
                Text(childName?.trim()?.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 28.us, fontWeight = FontWeight.Black)
            }
            Text(childName?.ifBlank { null } ?: "Привет", color = Play.Ink, fontSize = 26.us, fontWeight = FontWeight.ExtraBold)
        }
        Row(
            Modifier.offset(850.u, 36.u).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .9f)).padding(horizontal = 20.u, vertical = 12.u),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.u)
        ) {
            Box(Modifier.size(12.u).clip(CircleShape).background(if (connected) Play.Grass else Color(0xFFF08C00)))
            Text(if (connected) "Специалист на связи" else "Подключаемся…", color = Color(0xFF3A4766), fontSize = 18.us, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.offset(150.u, 200.u), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(64.u)) {
            Mascot(Modifier.size(330.u).floating(amplitude = 18f), mood = MascotMood.NORMAL, waving = true)
            Column(Modifier.width(600.u), verticalArrangement = Arrangement.spacedBy(22.u)) {
                Text(
                    "Привет${firstName(childName)?.let { ", $it" } ?: ""}!", color = Play.Ink,
                    fontSize = 84.us, fontWeight = FontWeight.Black, lineHeight = 88.us, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text("Сегодня поиграем вместе с Ойликом", color = Color(0xFF2F4A7A), fontSize = 34.us, fontWeight = FontWeight.Bold)
                Row(
                    Modifier.padding(top = 18.u).pulsing(1f, 1.04f).clip(RoundedCornerShape(50)).background(Play.Coral).padding(horizontal = 40.u, vertical = 22.u),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.u)
                ) {
                    WaitingDots()
                    Text("Скоро начнём", color = Color.White, fontSize = 40.us, fontWeight = FontWeight.Black)
                }
            }
        }
        AdultCorner(onAdult, tint = Play.Ink)
    }
}

@Composable
private fun WaitingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    val t by transition.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dotsT")
    Row(horizontalArrangement = Arrangement.spacedBy(8.u)) {
        repeat(3) { i -> Box(Modifier.size(14.u).graphicsLayer { alpha = if (t.toInt() == i) 1f else .45f }.clip(CircleShape).background(Color.White)) }
    }
}

/** Shown between tasks: the islands of today's plan; nothing to choose. */
@Composable
fun ChildPathScene(total: Int, position: Int, onAdult: () -> Unit) {
    val stations = total.coerceAtLeast(1)
    val route = remember { Path().apply { moveTo(170f, 600f); cubicTo(330f, 430f, 400f, 640f, 480f, 470f); cubicTo(560f, 300f, 660f, 300f, 720f, 420f); cubicTo(800f, 600f, 900f, 600f, 960f, 440f); cubicTo(1010f, 300f, 1060f, 240f, 1120f, 260f) } }
    val points = remember(stations) {
        val measure = PathMeasure().apply { setPath(route, false) }
        (0 until stations).map { i -> measure.getPosition(if (stations == 1) measure.length / 2 else measure.length * i / (stations - 1)) }
    }
    val hop = remember(position) { Animatable(0f) }
    LaunchedEffect(position) { hop.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    PlayCanvas {
        SceneBackground(SceneKind.ISLANDS)
        Canvas(Modifier.fillMaxSize()) {
            withTransform({ scale(size.width / 1280f, size.height / 800f, Offset.Zero) }) {
                drawPath(route, Color.White, style = Stroke(10f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 22f))))
                points.forEach { p ->
                    drawOval(Color(0xFFF7D98B), Offset(p.x - 80f, p.y - 20f), Size(160f, 76f))
                    drawOval(Color(0xFF7DD07A), Offset(p.x - 66f, p.y - 22f), Size(132f, 56f))
                }
            }
        }
        points.forEachIndexed { index, p ->
            val number = index + 1
            val done = number < position
            val current = number == position
            Box(Modifier.offset((p.x - 32).roundToInt().u, (p.y - 70).roundToInt().u).size(64.u), contentAlignment = Alignment.Center) {
                when {
                    done -> Box(Modifier.size(58.u).clip(CircleShape).background(Play.Grass), contentAlignment = Alignment.Center) { CheckGlyph(Modifier.size(34.u)) }
                    current -> Box(Modifier.size(64.u).pulsing().clip(CircleShape).background(Play.Coral), contentAlignment = Alignment.Center) { StarIcon(Color.White, Modifier.size(34.u)) }
                    number == stations -> Box(Modifier.size(58.u).clip(CircleShape).background(Play.Sun), contentAlignment = Alignment.Center) { StarIcon(Color.White, Modifier.size(32.u)) }
                    else -> Box(Modifier.size(54.u).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        Text(number.toString(), color = Color(0xFF55627F), fontSize = 28.us, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        val from = points.getOrElse(position - 2) { points.first() }
        val to = points.getOrElse(position - 1) { points.last() }
        val x = from.x + (to.x - from.x) * hop.value
        val y = from.y + (to.y - from.y) * hop.value - 120f * 4f * hop.value * (1f - hop.value)
        Box(Modifier.offset((x - 70).roundToInt().u, (y - 190).roundToInt().u).size(120.u)) { Mascot(Modifier.fillMaxSize(), mood = MascotMood.HAPPY) }
        Box(Modifier.offset(0.u, 700.u).size(1280.u, 80.u), contentAlignment = Alignment.Center) {
            Text(
                if (position <= 1) "Начинаем путешествие!" else "Задание $position из $total",
                color = Play.Ink, fontSize = 30.us, fontWeight = FontWeight.Black,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .92f)).padding(horizontal = 30.u, vertical = 12.u)
            )
        }
        AdultCorner(onAdult)
    }
}

/** End of the lesson: confetti, stars and a new sticker. */
@Composable
fun ChildFinishScene(childName: String?, stars: Int, onAdult: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "dance")
    val dance by transition.animateFloat(-8f, 8f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "danceA")
    PlayCanvas {
        SceneBackground(SceneKind.PARTY)
        Text(
            "Ты молодец${firstName(childName)?.let { ", $it" } ?: ""}!",
            Modifier.offset(0.u, 50.u).fillMaxWidth(), color = Color.White, fontSize = 80.us, fontWeight = FontWeight.Black, textAlign = TextAlign.Center
        )
        Row(Modifier.offset(0.u, 170.u).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.u, Alignment.CenterHorizontally)) {
            repeat(stars.coerceIn(1, 8)) { StarIcon(Color(0xFFFFD43B), Modifier.size(70.u).popIn("star", delayMs = 300 + it * 200)) }
        }
        Box(Modifier.offset(480.u, 280.u).size(320.u).popIn("sticker", delayMs = 600)) {
            Column(
                Modifier.fillMaxSize().clip(RoundedCornerShape(56.u)).background(Color.White),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.size(200.u).clip(CircleShape).background(Color(0xFFE7F7FF)), contentAlignment = Alignment.Center) {
                    Mascot(Modifier.size(170.u), mood = MascotMood.HAPPY)
                }
                Text("Новая наклейка!", color = Play.Ink, fontSize = 28.us, fontWeight = FontWeight.Black)
            }
        }
        Box(Modifier.offset(150.u, 400.u).size(220.u).graphicsLayer { rotationZ = dance; translationY = -dance.coerceAtLeast(0f) * 3f * density }) {
            Mascot(Modifier.fillMaxSize(), mood = MascotMood.HAPPY, waving = true)
        }
        Box(Modifier.offset(0.u, 650.u).size(1280.u, 100.u), contentAlignment = Alignment.Center) {
            Text("Занятие закончено", color = Color(0xFF3A2A00), fontSize = 36.us, fontWeight = FontWeight.Black,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFFD43B)).padding(horizontal = 40.u, vertical = 16.u))
        }
        AdultCorner(onAdult)
    }
}
