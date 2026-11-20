package kz.oyla.app.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kz.oyla.app.ui.session.ExerciseUiState
import kz.oyla.app.ui.session.ExerciseUiStatus

/**
 * One exercise as the child sees it: its own scene, Ойлик with the task, the game itself and
 * the celebration. The specialist's live preview renders the very same stage, scaled down.
 */
@Composable
fun ExerciseStage(
    state: ExerciseUiState,
    specialistView: Boolean,
    mediaToken: String?,
    onChoice: (String) -> Unit = {},
    onMove: (String, String?) -> Unit = { _, _ -> },
    onListen: () -> Unit = {},
    board: BoardActions? = null,
    modifier: Modifier = Modifier,
    calm: Boolean = false
) {
    val exercise = state.exercise
    val type = exercise?.activityType
    val interactive = !specialistView && state.exerciseStatus == ExerciseUiStatus.RUNNING && !state.isAnswerPending
    // Any touch (seen before the games handle it) hides the hint and restarts its idle timer.
    var touchTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val watchTouches = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) touchTick++
            }
        }
    }
    PlayCanvas(modifier.then(watchTouches), calm) {
        SceneBackground(sceneFor(type))
        if (exercise != null) {
            when (type) {
                "MEMORY" -> MemoryGame(state, interactive, specialistView, mediaToken, onMove)
                "REMEMBER" -> RememberGame(state, interactive, specialistView, mediaToken, onChoice)
                "SORT" -> SortGame(state, interactive, specialistView, mediaToken, onMove)
                "MAZE" -> MazeGame(state, interactive, specialistView, onMove)
                "WORD_BUILD" -> WordGame(state, interactive, specialistView, mediaToken, onMove, onListen)
                "WHITEBOARD" -> if (board != null) ChildBoard(state, mediaToken, board)
                "POP" -> PopGame(state, interactive, specialistView, mediaToken, onMove)
                "SYLLABLES" -> DrumGame(state, interactive, specialistView, mediaToken, onChoice)
                "SHADOW" -> ShadowGame(state, interactive, specialistView, mediaToken, onMove)
                "COUNT" -> CountGame(state, interactive, specialistView, mediaToken, onChoice)
                else -> ChoiceGame(state, interactive, specialistView, mediaToken, onChoice)
            }
            if (!specialistView) {
                val plan = androidx.compose.runtime.remember(state.sessionExerciseId, exercise) { hintPlan(state) }
                HintHand(plan, active = interactive && state.exerciseStatus == ExerciseUiStatus.RUNNING, touchTick = touchTick, key = state.sessionExerciseId)
            }
            InstructionBubble(
                text = bubbleText(state),
                mood = when {
                    state.exerciseStatus == ExerciseUiStatus.COMPLETED -> MascotMood.HAPPY
                    state.latestAnswer?.isCorrect == false -> MascotMood.THINKING
                    state.exerciseStatus == ExerciseUiStatus.SHOWN -> MascotMood.TALKING
                    else -> MascotMood.NORMAL
                },
                compact = type == "WHITEBOARD",
                onListen = onListen,
                listenEnabled = !specialistView
            )
        }
        Box(Modifier.offset(x = 760.u, y = 26.u).size(420.u, 60.u), contentAlignment = Alignment.CenterEnd) {
            ProgressBeads(state.totalExercises, state.currentPosition, state.exerciseStatus == ExerciseUiStatus.COMPLETED, onDark = type !in setOf("REMEMBER", "WORD_BUILD", "SORT", "WHITEBOARD", "ODD_ONE", "MAZE", "SYLLABLES", "SHADOW", "COUNT"))
        }
        if (!specialistView && state.exerciseStatus == ExerciseUiStatus.SHOWN) {
            Box(Modifier.offset(0.u, 700.u).size(1280.u, 80.u), contentAlignment = Alignment.Center) {
                Text("Слушай Ойлика, сейчас начнём!", color = Play.Ink, fontSize = 30.us, fontWeight = FontWeight.Black,
                    modifier = Modifier.pulsing(1f, 1.04f).clip(RoundedCornerShape(30.u)).background(Color.White).padding(horizontal = 28.u, vertical = 10.u))
            }
        }
        // Let the game's own success animation play before the celebration covers it.
        var celebrate by androidx.compose.runtime.remember(state.sessionExerciseId) { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(state.sessionExerciseId, state.exerciseStatus) {
            celebrate = false
            if (state.exerciseStatus == ExerciseUiStatus.COMPLETED) { kotlinx.coroutines.delay(1600); celebrate = true }
        }
        if (!specialistView && celebrate && !state.planCompleted) {
            Celebration(state.sessionExerciseId ?: "", state.currentPosition == state.totalExercises)
        }
    }
}

private fun bubbleText(state: ExerciseUiState): String {
    val exercise = state.exercise ?: return ""
    return when (exercise.activityType) {
        "MEMORY" -> exercise.instructionText.ifBlank { "Найди пары" }
        else -> exercise.instructionText
    }
}

@Composable
private fun InstructionBubble(text: String, mood: MascotMood, compact: Boolean, onListen: () -> Unit, listenEnabled: Boolean) {
    Row(
        Modifier.offset(x = if (compact) 150.u else 26.u, y = 18.u).widthIn(max = 760.u),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.u)
    ) {
        Mascot(Modifier.size(if (compact) 96.u else 124.u).floating(amplitude = 6f, periodMs = 2400), mood = mood)
        Row(
            Modifier.clip(RoundedCornerShape(30.u)).background(Color.White).padding(start = 26.u, end = 12.u, top = 12.u, bottom = 12.u),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.u)
        ) {
            Text(
                text, Modifier.widthIn(max = 470.u), color = Play.Ink,
                fontSize = (if (text.length > 34) 26 else 32).us, fontWeight = FontWeight.Black, lineHeight = (if (text.length > 34) 32 else 38).us
            )
            ChunkyButton(onListen, Play.Coral, Play.CoralDeep, Modifier.size(70.u), CircleShape, depth = 5, enabled = listenEnabled, description = "Послушать задание ещё раз") {
                SpeakerGlyph(Modifier.size(38.u))
            }
        }
    }
}

@Composable
private fun Celebration(key: String, last: Boolean) {
    Box(Modifier.fillMaxSize().background(Color(0x33000000)))
    StarBurst(key, Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.popIn(key).clip(RoundedCornerShape(56.u)).background(Color.White).padding(horizontal = 60.u, vertical = 32.u),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.u)
        ) {
            Mascot(Modifier.size(180.u).floating(amplitude = 22f, periodMs = 1200), mood = MascotMood.HAPPY)
            Text("Молодец!", color = Play.Ink, fontSize = 72.us, fontWeight = FontWeight.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(8.u)) { repeat(3) { StarIcon(Play.Sun, Modifier.size(44.u).popIn(key, delayMs = 200 + it * 150)) } }
            Text(if (last) "Это было последнее задание" else "Скоро следующее задание", color = Color(0xFF3A4766), fontSize = 26.us, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}
