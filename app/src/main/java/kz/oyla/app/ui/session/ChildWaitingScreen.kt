package kz.oyla.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.oyla.app.R
import kz.oyla.app.ui.components.OylaBackground
import kz.oyla.app.ui.components.OylaLogo
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaTextMuted

@Composable
fun ChildWaitingScreen(viewModel: ChildSessionViewModel, onExerciseShown: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.restoreActiveSession() }
    LaunchedEffect(state.exercise.exerciseStatus) {
        if (state.exercise.exerciseStatus in setOf(ExerciseUiStatus.SHOWN, ExerciseUiStatus.RUNNING, ExerciseUiStatus.COMPLETED)) {
            onExerciseShown()
        }
    }
    BackHandler(enabled = true) { }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        OylaBackground(R.drawable.bg_child_connect)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = maxWidth * 0.04f, vertical = maxHeight * 0.045f)
        ) {
            OylaLogo(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.17f)
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 650.dp)
                    .fillMaxWidth(0.62f)
            ) {
                Text(
                    text = stringResource(R.string.child_ready_title),
                    color = OylaNavy,
                    fontSize = 50.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                state.session?.childName?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = OylaTextMuted, fontSize = 26.sp)
                }
                Text(
                    text = stringResource(R.string.waiting_assignment),
                    color = OylaTextMuted,
                    fontSize = 27.sp,
                    textAlign = TextAlign.Center
                )
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                SocketStatus(state.socketState)
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
        }
    }
}
