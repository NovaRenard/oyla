package kz.oyla.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.oyla.app.R
import kz.oyla.app.data.remote.SocketConnectionState
import kz.oyla.app.ui.components.OylaBackground
import kz.oyla.app.ui.components.OylaLogo
import kz.oyla.app.ui.components.OylaPrimaryButton
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaTextMuted

@Composable
fun SpecialistWaitingScreen(
    viewModel: SpecialistSessionViewModel,
    onOpenExercise: () -> Unit,
    onOpenSummary: () -> Unit,
    onCancelled: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.restoreActiveSession() }
    LaunchedEffect(state.exercise.exerciseStatus, state.exercise.planCompleted) {
        if (state.exercise.planCompleted) {
            onOpenSummary()
        } else if (state.exercise.exerciseStatus in setOf(ExerciseUiStatus.SHOWN, ExerciseUiStatus.RUNNING, ExerciseUiStatus.COMPLETED)) {
            onOpenExercise()
        }
    }
    LaunchedEffect(state.sessionEndedId) {
        if (state.sessionEndedId != null) {
            viewModel.consumeSessionEnd()
            onCancelled()
        }
    }
    BackHandler(enabled = true) { }
    val session = state.session
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        OylaBackground(R.drawable.bg_specialist_home)
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 690.dp)
                    .fillMaxWidth(0.62f)
            ) {
                Text(
                    text = session?.childName.orEmpty(),
                    color = OylaTextMuted,
                    fontSize = 24.sp
                )
                Text(
                    text = stringResource(R.string.connection_code_title),
                    color = OylaNavy,
                    fontSize = 43.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = session?.connectionCode ?: "— — — —",
                    color = OylaNavy,
                    fontSize = 72.sp,
                    letterSpacing = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(28.dp))
                        .padding(horizontal = 44.dp, vertical = 12.dp)
                )
                val connected = session?.childConnected == true
                Text(
                    text = stringResource(if (connected) R.string.child_connected else R.string.waiting_child),
                    color = if (connected) MaterialTheme.colorScheme.primary else OylaTextMuted,
                    fontSize = 27.sp,
                    fontWeight = if (connected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
                if (!connected) Text(
                    text = stringResource(R.string.waiting_child_detail),
                    color = OylaTextMuted,
                    fontSize = 18.sp
                )
                SocketStatus(state.socketState)
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.isLoading && session == null) CircularProgressIndicator(color = OylaNavy)
                if (connected) {
                    OylaPrimaryButton(
                        text = stringResource(R.string.go_to_tasks_later),
                        icon = Icons.Outlined.PlayArrow,
                        iconDescription = stringResource(R.string.go_to_tasks_later),
                        enabled = !state.isLoading,
                        onClick = onOpenExercise,
                        textSize = 20.sp,
                        minHeight = 62.dp,
                        modifier = Modifier.fillMaxWidth(0.78f)
                    )
                }
                OylaPrimaryButton(
                    text = stringResource(R.string.cancel_session),
                    icon = Icons.Outlined.Cancel,
                    iconDescription = stringResource(R.string.cancel_session),
                    enabled = !state.isLoading,
                    onClick = { viewModel.cancelSession(onCancelled) },
                    textSize = 20.sp,
                    minHeight = 60.dp,
                    modifier = Modifier.fillMaxWidth(0.62f)
                )
            }
        }
    }
}

@Composable
internal fun SocketStatus(state: SocketConnectionState) {
    val text = when (state) {
        SocketConnectionState.CONNECTING -> stringResource(R.string.connecting_to_server)
        SocketConnectionState.CONNECTED -> stringResource(R.string.connection_active)
        SocketConnectionState.RECONNECTING -> stringResource(R.string.reconnecting)
        SocketConnectionState.DISCONNECTED -> stringResource(R.string.connection_disconnected)
    }
    Text(text = text, color = OylaTextMuted, fontSize = 16.sp, textAlign = TextAlign.Center)
}
