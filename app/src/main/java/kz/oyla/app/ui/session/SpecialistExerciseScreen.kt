package kz.oyla.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.oyla.app.R
import kz.oyla.app.data.remote.SocketConnectionState
import kz.oyla.app.ui.components.OylaBackground
import kz.oyla.app.ui.components.OylaLogo
import kz.oyla.app.ui.components.OylaPrimaryButton
import kz.oyla.app.ui.theme.OylaBlue
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaTextMuted

@Composable
fun SpecialistExerciseScreen(viewModel: SpecialistSessionViewModel, onCompleted: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val exercise = state.exercise
    var showConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.restoreActiveSession() }
    LaunchedEffect(state.session?.sessionId) { if (state.session != null) viewModel.loadFirstExercise() }
    BackHandler(enabled = true) { }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        OylaBackground(R.drawable.bg_specialist_home)
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = screenWidth * .025f, vertical = screenHeight * .035f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OylaLogo(Modifier.width(screenWidth * .12f))
                Spacer(Modifier.weight(1f))
                HeaderPill(
                    text = if (state.session?.childConnected == true) "●  Ребёнок подключён" else "○  Ожидание ребёнка",
                    accent = if (state.session?.childConnected == true) Color(0xFF31B96A) else OylaTextMuted
                )
                Spacer(Modifier.width(14.dp))
                HeaderPill(text = "Сервер: ${serverLabel(state.socketState)}", accent = serverColor(state.socketState))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Card(
                    shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.94f)),
                    modifier = Modifier.width(screenWidth * .27f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        Icon(Icons.Outlined.PersonOutline, null, tint = OylaBlue, modifier = Modifier.size(31.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Ребёнок: ${state.session?.childName ?: "—"}", color = OylaNavy, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                Card(
                    shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.95f)),
                    modifier = Modifier.weight(.65f).fillMaxHeight()
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                        Text("Задание 1", color = OylaNavy, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Text(exercise.exercise?.instructionText ?: "Загружаем задание…", color = OylaNavy, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                        exercise.exercise?.let {
                            ExerciseCardGrid(
                                exercise = it, selectedAnswer = exercise.latestAnswer, enabled = false,
                                showCorrectMarker = true
                            )
                        }
                    }
                }
                ResultsPanel(
                    exercise = exercise,
                    childConnected = state.session?.childConnected == true,
                    socketState = state.socketState,
                    onShow = viewModel::showExercise,
                    onStart = viewModel::startExercise,
                    onComplete = { showConfirmation = true },
                    modifier = Modifier.weight(.35f).fillMaxHeight()
                )
            }
        }
    }
    if (showConfirmation) AlertDialog(
        onDismissRequest = { showConfirmation = false },
        title = { Text("Завершить занятие?") },
        text = { Text("Упражнение будет завершено для обоих устройств.") },
        confirmButton = { Button(onClick = { showConfirmation = false; viewModel.completeSession(onCompleted) }) { Text("Завершить") } },
        dismissButton = { Button(onClick = { showConfirmation = false }) { Text("Отмена") } }
    )
}

@Composable
private fun ResultsPanel(
    exercise: ExerciseUiState,
    childConnected: Boolean,
    socketState: SocketConnectionState,
    onShow: () -> Unit,
    onStart: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier
) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.96f)), modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(22.dp)) {
            Text("Результаты в реальном времени", color = OylaNavy, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            ResultLine("Выбор ребёнка", exercise.latestAnswer?.selectedOptionLabel ?: "—")
            ResultLine("Правильность", exercise.latestAnswer?.let { if (it.isCorrect) "Правильно" else "Неправильно" } ?: "—")
            ResultLine("Попытка", exercise.attemptCount.toString())
            ResultLine("Время", formatTime(if (exercise.exerciseStatus == ExerciseUiStatus.COMPLETED) exercise.latestAnswer?.responseTimeMs ?: 0 else exercise.elapsedMillis))
            Spacer(Modifier.weight(1f))
            OylaPrimaryButton(
                text = if (exercise.exerciseStatus == ExerciseUiStatus.PENDING) "Показать ребёнку" else "Показано ребёнку",
                icon = Icons.Outlined.Visibility, iconDescription = "Показать ребёнку", onClick = onShow,
                enabled = childConnected && socketState == SocketConnectionState.CONNECTED && exercise.exerciseStatus == ExerciseUiStatus.PENDING && !exercise.isCommandLoading,
                textSize = 17.sp, minHeight = 58.dp, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OylaPrimaryButton(
                text = "Начать", icon = Icons.Outlined.PlayArrow, iconDescription = "Начать", onClick = onStart,
                enabled = exercise.exerciseStatus == ExerciseUiStatus.SHOWN && socketState == SocketConnectionState.CONNECTED && !exercise.isCommandLoading,
                textSize = 17.sp, minHeight = 58.dp, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            Text(if (childConnected) "● Упражнение доступно: ребёнок подключён" else "○ Ожидаем подключения ребёнка", color = if (childConnected) Color(0xFF31B96A) else OylaTextMuted, fontSize = 13.sp)
            exercise.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onComplete, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF04E35)),
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Outlined.StopCircle, null); Spacer(Modifier.width(8.dp)); Text("Завершить занятие", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable private fun ResultLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Icon(Icons.Outlined.CheckCircle, null, tint = OylaBlue, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp)); Text("$label:", color = OylaTextMuted, fontSize = 17.sp)
        Spacer(Modifier.weight(1f)); Text(value, color = OylaNavy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
    Divider(color = Color(0xFFE7ECF5))
}

@Composable private fun HeaderPill(text: String, accent: Color) = Card(
    shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.93f))
) { Text(text, color = if (text.startsWith("●")) accent else OylaNavy, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp)) }

internal fun formatTime(millis: Long): String {
    val seconds = (millis / 1_000).coerceAtLeast(0)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
private fun serverLabel(state: SocketConnectionState) = when (state) {
    SocketConnectionState.CONNECTED -> "online"
    SocketConnectionState.RECONNECTING, SocketConnectionState.CONNECTING -> "переподключение"
    SocketConnectionState.DISCONNECTED -> "нет связи"
}
private fun serverColor(state: SocketConnectionState) = if (state == SocketConnectionState.CONNECTED) Color(0xFF239A54) else OylaTextMuted
