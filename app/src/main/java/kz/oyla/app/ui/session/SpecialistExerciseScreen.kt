package kz.oyla.app.ui.session

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
fun SpecialistExerciseScreen(
    viewModel: SpecialistSessionViewModel,
    onCompleted: () -> Unit,
    onOpenSummary: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val exercise = state.exercise
    var showConfirmation by remember { mutableStateOf(false) }
    var showConnectionSettings by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.restoreActiveSession() }
    LaunchedEffect(state.sessionEndedId) {
        if (state.sessionEndedId != null) { viewModel.consumeSessionEnd(); onCompleted() }
    }
    LaunchedEffect(state.session?.sessionId) { if (state.session != null) viewModel.loadCurrentExercise() }
    BackHandler(enabled = true) { }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        OylaBackground(R.drawable.bg_specialist_home)
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = screenWidth * .025f, vertical = screenHeight * .025f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OylaLogo(Modifier.width(screenWidth * .12f))
                Spacer(Modifier.weight(1f))
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.93f))) {
                    IconButton(onClick = { showConnectionSettings = true }) {
                        Icon(Icons.Outlined.Settings, "Настройки подключения", tint = OylaNavy)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.94f))) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Icon(Icons.Outlined.PersonOutline, null, tint = OylaBlue, modifier = Modifier.size(27.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Ребёнок: ${state.session?.childName ?: "—"}", color = OylaNavy, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    }
                }
                Spacer(Modifier.width(18.dp))
                PlanProgress(exercise)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                Card(
                    shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.95f)),
                    modifier = Modifier.weight(.65f).fillMaxHeight()
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(22.dp)) {
                        Text("Задание ${exercise.currentPosition} из ${exercise.totalExercises}", color = OylaNavy, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(exercise.exercise?.instructionText ?: "Загружаем задание…", color = OylaNavy, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                        exercise.exercise?.let {
                            ExerciseCardGrid(exercise = it, selectedAnswer = exercise.latestAnswer, enabled = false, showCorrectMarker = true, mediaToken = viewModel.mediaToken)
                        }
                    }
                }
                ResultsPanel(
                    exercise = exercise, childConnected = state.session?.childConnected == true, socketState = state.socketState,
                    onShow = viewModel::showExercise, onStart = viewModel::startExercise, onNext = viewModel::nextExercise,
                    onSummary = onOpenSummary, onComplete = { showConfirmation = true }, modifier = Modifier.weight(.35f).fillMaxHeight()
                )
            }
        }
    }
    if (showConfirmation) AlertDialog(
        onDismissRequest = { showConfirmation = false }, title = { Text("Завершить занятие?") },
        text = { Text(if (exercise.planCompleted) "Занятие будет завершено для обоих устройств." else "Не все задания завершены. Завершить занятие?") },
        confirmButton = { Button(onClick = { showConfirmation = false; viewModel.completeSession(onCompleted) }) { Text("Завершить") } },
        dismissButton = { Button(onClick = { showConfirmation = false }) { Text("Отмена") } }
    )
    if (showConnectionSettings) AlertDialog(
        onDismissRequest = { showConnectionSettings = false }, title = { Text("Статус подключения") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ConnectionStatusLine("Сервер", serverLabel(state.socketState), serverColor(state.socketState))
                ConnectionStatusLine("Ребёнок", if (state.session?.childConnected == true) "подключён" else "ожидание подключения", if (state.session?.childConnected == true) Color(0xFF31B96A) else OylaTextMuted)
            }
        },
        confirmButton = { Button(onClick = { showConnectionSettings = false }) { Text("Закрыть") } },
        dismissButton = {
            Button(onClick = {
                showConnectionSettings = false
                onOpenSettings()
            }) { Text("Настройки устройства") }
        }
    )
}

@Composable
private fun PlanProgress(exercise: ExerciseUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        (1..exercise.totalExercises).forEach { position ->
            val completed = position < exercise.currentPosition ||
                (position == exercise.currentPosition && exercise.exerciseStatus == ExerciseUiStatus.COMPLETED)
            val current = position == exercise.currentPosition && !completed
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = when { completed -> Color(0xFF36A969); current -> OylaBlue; else -> Color.White.copy(.82f) }),
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    if (completed) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(19.dp))
                    else Text(position.toString(), color = if (current) Color.White else OylaTextMuted, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ResultsPanel(
    exercise: ExerciseUiState, childConnected: Boolean, socketState: SocketConnectionState,
    onShow: () -> Unit, onStart: () -> Unit, onNext: () -> Unit, onSummary: () -> Unit,
    onComplete: () -> Unit, modifier: Modifier
) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.96f)), modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            val commandButtonModifier = Modifier
                .widthIn(max = 300.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Результаты в реальном времени", color = OylaNavy, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                ResultLine("Выбор ребёнка", exercise.latestAnswer?.selectedOptionLabel ?: "—")
                ResultLine("Правильность", exercise.latestAnswer?.let { if (it.isCorrect) "Правильно" else "Неправильно" } ?: "—")
                ResultLine("Попытка", exercise.attemptCount.toString())
                ResultLine("Время", formatTime(if (exercise.exerciseStatus == ExerciseUiStatus.COMPLETED) exercise.latestAnswer?.responseTimeMs ?: 0 else exercise.elapsedMillis))
                Spacer(Modifier.height(12.dp))
                Text(if (childConnected) "● Ребёнок подключён" else "○ Ожидаем ребёнка", color = if (childConnected) Color(0xFF31B96A) else OylaTextMuted, fontSize = 13.sp)
                exercise.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            when (exercise.exerciseStatus) {
                ExerciseUiStatus.PENDING -> OylaPrimaryButton("Показать ребёнку", Icons.Outlined.Visibility, "Показать ребёнку", onShow,
                    enabled = childConnected && !exercise.isCommandLoading, textSize = 16.sp, minHeight = 54.dp, modifier = commandButtonModifier)
                ExerciseUiStatus.SHOWN -> {
                    OylaPrimaryButton("Показано ребёнку", Icons.Outlined.Visibility, "Показано ребёнку", {}, enabled = false, textSize = 16.sp, minHeight = 50.dp, modifier = commandButtonModifier)
                    Spacer(Modifier.height(8.dp))
                    OylaPrimaryButton("Начать", Icons.Outlined.PlayArrow, "Начать", onStart, enabled = socketState == SocketConnectionState.CONNECTED && !exercise.isCommandLoading, textSize = 16.sp, minHeight = 54.dp, modifier = commandButtonModifier)
                }
                ExerciseUiStatus.RUNNING -> OylaPrimaryButton("Задание выполняется", Icons.Outlined.PlayArrow, "Задание выполняется", {}, enabled = false, textSize = 16.sp, minHeight = 54.dp, modifier = Modifier.fillMaxWidth())
                ExerciseUiStatus.COMPLETED -> if (exercise.currentPosition < exercise.totalExercises) {
                    OylaPrimaryButton("Следующее задание", Icons.Outlined.NavigateNext, "Следующее задание", onNext, enabled = exercise.canMoveToNext(), textSize = 16.sp, minHeight = 54.dp, modifier = Modifier.fillMaxWidth())
                } else {
                    OylaPrimaryButton("Посмотреть итог", Icons.Outlined.CheckCircle, "Посмотреть итог", onSummary, enabled = exercise.canOpenSummary(), textSize = 16.sp, minHeight = 54.dp, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onComplete, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF04E35)), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.StopCircle, null); Spacer(Modifier.width(8.dp)); Text("Завершить занятие", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ConnectionStatusLine(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = OylaTextMuted, fontSize = 16.sp); Spacer(Modifier.weight(1f)); Text("●  $value", color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
@Composable private fun ResultLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Icon(Icons.Outlined.CheckCircle, null, tint = OylaBlue, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("$label:", color = OylaTextMuted, fontSize = 16.sp); Spacer(Modifier.weight(1f)); Text(value, color = OylaNavy, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
    Divider(color = Color(0xFFE7ECF5))
}

internal fun formatTime(millis: Long): String { val seconds = (millis / 1_000).coerceAtLeast(0); return "%02d:%02d".format(seconds / 60, seconds % 60) }
private fun serverLabel(state: SocketConnectionState) = when (state) { SocketConnectionState.CONNECTED -> "online"; SocketConnectionState.RECONNECTING, SocketConnectionState.CONNECTING -> "переподключение"; SocketConnectionState.DISCONNECTED -> "нет связи" }
private fun serverColor(state: SocketConnectionState) = if (state == SocketConnectionState.CONNECTED) Color(0xFF239A54) else OylaTextMuted
