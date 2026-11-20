package kz.oyla.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kz.oyla.app.data.remote.SocketConnectionState
import kz.oyla.app.ui.play.AdultMenuDialog
import kz.oyla.app.ui.play.ChildWelcomeScene

/** Greeting scene while the specialist prepares the lesson; adults reach the menu by a 3-second hold. */
@Composable
fun ChildWaitingScreen(
    viewModel: ChildSessionViewModel,
    onExerciseShown: () -> Unit,
    onCancelled: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    var showAdultMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.restoreActiveSession() }
    LaunchedEffect(state.exercise.exerciseStatus) {
        if (state.exercise.exerciseStatus in setOf(ExerciseUiStatus.SHOWN, ExerciseUiStatus.RUNNING, ExerciseUiStatus.COMPLETED)) {
            onExerciseShown()
        }
    }
    LaunchedEffect(state.sessionEndedId) {
        if (state.sessionEndedId != null) {
            viewModel.consumeSessionEnd()
            onCancelled()
        }
    }
    BackHandler(enabled = true) { }
    ChildWelcomeScene(
        childName = state.session?.childName,
        connected = state.socketState == SocketConnectionState.CONNECTED,
        onAdult = { showAdultMenu = true }
    )
    if (showAdultMenu) AdultMenuDialog(
        onDismiss = { showAdultMenu = false },
        onSettings = onOpenSettings,
        onCancelLesson = { viewModel.cancelSession(onCancelled) }
    )
}
