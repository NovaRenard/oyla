package kz.oyla.app.ui.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kz.oyla.app.ui.session.ExerciseOptionUiModel
import kz.oyla.app.ui.session.ExerciseUiState
import kz.oyla.app.ui.session.exerciseDrawableFor
import kz.oyla.app.ui.session.hasLocalExerciseDrawable
import kotlin.math.roundToInt

/** APK picture by key (nothing when the key is unknown). */
@Composable
fun AssetPicture(key: String?, modifier: Modifier = Modifier, description: String? = null) {
    if (hasLocalExerciseDrawable(key)) {
        Image(painterResource(exerciseDrawableFor(key!!)), description, modifier, contentScale = ContentScale.Fit)
    } else {
        Box(modifier)
    }
}

/** White pill with a short message for the child, e.g. a gentle hint after a wrong move. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, size: Int = 32, color: Color = Play.Ink) {
    Text(
        text, modifier.clip(RoundedCornerShape(30.u)).background(Color.White).padding(horizontal = 26.u, vertical = 8.u),
        color = color, fontSize = size.us, fontWeight = FontWeight.Black
    )
}

/**
 * A piece the child drags somewhere. It rests at [home] (or [settled] once placed) in artboard
 * pixels, springs back when dropped elsewhere and is drawn above the others while dragged.
 * [onDrop] receives the piece's centre in artboard pixels.
 */
@Composable
fun DraggablePiece(
    key: Any,
    home: Offset,
    side: Float,
    enabled: Boolean,
    onDrop: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    settled: Offset? = null,
    settledSide: Float = side,
    shake: Int = 0,
    width: Float = side,
    settledWidth: Float = settledSide * width / side,
    description: String? = null,
    content: @Composable BoxScope.(dragging: Boolean) -> Unit
) {
    val unitPx = LocalPlay.current.unit * LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val target = settled ?: home
    val position = remember(key) { Animatable(target, Offset.VectorConverter) }
    var dragging by remember(key) { mutableStateOf(false) }
    val drop by rememberUpdatedState(onDrop)
    LaunchedEffect(target, dragging) {
        if (!dragging) position.animateTo(target, spring(dampingRatio = .6f, stiffness = Spring.StiffnessLow))
    }
    val w by animateFloatAsState(if (settled != null) settledWidth else width, spring(dampingRatio = .6f), label = "w")
    val h by animateFloatAsState(if (settled != null) settledSide else side, spring(dampingRatio = .6f), label = "h")
    val lift by animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = .45f), label = "lift")
    Box(
        modifier
            .zIndex(if (dragging) 10f else if (settled != null) 1f else 2f)
            .offset { IntOffset((position.value.x * unitPx).roundToInt(), (position.value.y * unitPx).roundToInt()) }
            .size(w.u, h.u)
            .graphicsLayer { val s = 1f + .1f * lift; scaleX = s; scaleY = s; rotationZ = -3f * lift }
            .wiggle(shake)
            .semantics { if (description != null) contentDescription = description }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDrag = { change, delta -> change.consume(); scope.launch { position.snapTo(position.value + delta / unitPx) } },
                    onDragEnd = {
                        drop(position.value + Offset(w / 2f, h / 2f))
                        dragging = false
                    },
                    onDragCancel = { dragging = false }
                )
            }
    ) { content(dragging) }
}

/** Round number bubbles for MATH and PAIRS; the solved one turns golden. */
@Composable
fun NumberBubbles(state: ExerciseUiState, interactive: Boolean, specialistView: Boolean, onChoice: (String) -> Unit, top: Int = 618, side: Int = 130) {
    val exercise = state.exercise ?: return
    val numbers = exercise.options.sortedBy { it.label.toIntOrNull() ?: 0 }
    val solvedId = state.latestAnswer?.takeIf { it.isCorrect }?.selectedOptionId
    val wrongId = state.latestAnswer?.takeIf { !it.isCorrect }?.selectedOptionId
    val wrongTick = state.latestAnswer?.takeIf { !it.isCorrect }?.attemptNumber ?: 0
    Row(Modifier.offset(0.u, top.u).size(1280.u, (side + 20).u), horizontalArrangement = Arrangement.spacedBy(34.u, Alignment.CenterHorizontally)) {
        numbers.forEachIndexed { i, option ->
            val golden = option.id == solvedId || (specialistView && option.id == exercise.correctOptionId)
            val grow by animateFloatAsState(if (option.id == solvedId) 1.15f else 1f, spring(dampingRatio = .4f), label = "grow")
            Box(
                Modifier.size(side.u).floating(amplitude = 8f, periodMs = 2800, delayMs = i * 400)
                    .wiggle(if (option.id == wrongId) wrongTick else 0)
                    .graphicsLayer { scaleX = grow; scaleY = grow }
                    .clip(CircleShape)
                    .background(
                        if (golden) Brush.radialGradient(listOf(Color(0xFFFFF6C8), Color(0xFFFFD54F), Color(0xFFFFB300)))
                        else Brush.radialGradient(listOf(Color.White, Color(0xFFD8F1FF), Color(0xFFA9DCF7)))
                    )
                    .semantics { contentDescription = option.label }
                    .clickable(interactionSource = null, indication = null, enabled = interactive && solvedId == null, role = Role.Button) { onChoice(option.id) },
                contentAlignment = Alignment.Center
            ) {
                Text(option.label, color = if (golden) Color(0xFF7A4B00) else Color(0xFF1E5A8A), fontSize = (side * .55f).toInt().us, fontWeight = FontWeight.Black)
            }
        }
    }
}

/** Options already done: confirmed by the server plus the ones judged right on this tablet. */
fun doneIds(state: ExerciseUiState, local: Collection<String>): Set<String> = (state.solvedOptionIds + local).toSet()

/** Option lookup by id. */
fun List<ExerciseOptionUiModel>.byId(id: String): ExerciseOptionUiModel? = firstOrNull { it.id == id }
