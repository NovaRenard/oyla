package kz.oyla.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kz.oyla.app.ui.play.AdultCorner
import kz.oyla.app.ui.play.AdultMenuDialog
import kz.oyla.app.ui.play.BoardActions
import kz.oyla.app.ui.play.ChildFinishScene
import kz.oyla.app.ui.play.ChildPathScene
import kz.oyla.app.ui.play.ExerciseStage
import kz.oyla.app.ui.play.PlayCanvas
import kz.oyla.app.ui.play.u
import kz.oyla.app.ui.play.us

/**
 * The child's lesson surface: the path between tasks, each task as a full-screen scene and
 * the finish party. The child never chooses anything; the specialist drives the plan.
 */
@Composable
fun ChildExerciseScreen(
    viewModel: ChildSessionViewModel,
    onOpenSettings: () -> Unit,
    onSessionEnded: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val exerciseState = state.exercise
    val audio = rememberExerciseAudioPlayer()
    var showAdultMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.restoreActiveSession() }
    LaunchedEffect(state.sessionEndedId) {
        if (state.sessionEndedId != null) {
            viewModel.consumeSessionEnd()
            onSessionEnded()
        }
    }
    LaunchedEffect(exerciseState.playInstructionRequest) {
        if (exerciseState.playInstructionRequest > 0) {
            exerciseState.exercise?.let { audio.play(it.audioAssetKey, it.audioUrl, viewModel.mediaToken, it.instructionText) }
        }
    }
    BackHandler(enabled = true) { }
    val openAdult = { showAdultMenu = true }
    // Each new task first shows the island path for a moment (the specialist starts tasks
    // instantly now, so the PENDING state alone would only flash).
    var pathShowing by remember { mutableStateOf(false) }
    LaunchedEffect(exerciseState.sessionExerciseId) {
        if (exerciseState.sessionExerciseId != null) { pathShowing = true; kotlinx.coroutines.delay(2600); pathShowing = false }
    }
    // The last task's success animation plays out before the finish party replaces it.
    var showFinish by remember { mutableStateOf(false) }
    LaunchedEffect(exerciseState.planCompleted) {
        if (exerciseState.planCompleted) { kotlinx.coroutines.delay(2200); showFinish = true } else showFinish = false
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF5EC4F0))) {
        when {
            exerciseState.planCompleted && (showFinish || exerciseState.exercise == null) -> ChildFinishScene(state.session?.childName ?: exerciseState.childName, exerciseState.totalExercises, openAdult)
            exerciseState.exercise == null || exerciseState.exerciseStatus == ExerciseUiStatus.PENDING || (pathShowing && !exerciseState.planCompleted) ->
                ChildPathScene(exerciseState.totalExercises, exerciseState.currentPosition, openAdult)
            else -> {
                ExerciseStage(
                    state = exerciseState,
                    specialistView = false,
                    mediaToken = viewModel.mediaToken,
                    onChoice = viewModel::submitAnswer,
                    onMove = viewModel::submitGameAnswer,
                    onListen = viewModel::repeatInstruction,
                    board = BoardActions(
                        onSelectTool = viewModel::selectWhiteboardTool, onSelectColor = viewModel::selectWhiteboardColor,
                        onSelectBrush = viewModel::selectWhiteboardBrush, onStrokeStart = viewModel::beginWhiteboardStroke,
                        onStrokePoint = viewModel::appendWhiteboardPoint, onStrokeEnd = viewModel::endWhiteboardStroke,
                        onUndo = viewModel::whiteboardUndo
                    )
                )
                PlayCanvas { AdultCorner(openAdult) }
            }
        }
        exerciseState.errorMessage?.let { message ->
            PlayCanvas {
                Box(Modifier.offset(0.u, 640.u).size(1280.u, 60.u), contentAlignment = Alignment.Center) {
                    Text(message, color = Color(0xFF8A2A12), fontSize = 22.us, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(24.u)).background(Color(0xFFFFE9E2)).padding(horizontal = 22.u, vertical = 8.u))
                }
            }
        }
    }
    if (showAdultMenu) AdultMenuDialog(
        onDismiss = { showAdultMenu = false },
        onSettings = onOpenSettings,
        onCancelLesson = { viewModel.cancelSession(onSessionEnded) }
    )
}
