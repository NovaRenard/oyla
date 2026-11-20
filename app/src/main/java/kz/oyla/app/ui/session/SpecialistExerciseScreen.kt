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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import kz.oyla.app.ui.play.ExerciseStage

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
    val childConnected = state.session?.childConnected == true

    Column(Modifier.fillMaxSize().background(PultBackground).safeDrawingPadding()) {
        PultTopBar(
            childName = state.session?.childName ?: "—", exercise = exercise, childConnected = childConnected,
            socketState = state.socketState, onSettings = { showConnectionSettings = true }
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(Modifier.weight(.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Задание ${exercise.currentPosition} из ${exercise.totalExercises}",
                        color = PultMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(10.dp))
                    StatusChip(exercise.exerciseStatus)
                    Spacer(Modifier.weight(1f))
                    Text("Экран ребёнка", color = PultMuted, fontSize = 13.sp)
                }
                Text(
                    exercise.exercise?.title ?: exercise.exercise?.instructionText ?: "Загружаем задание…",
                    color = PultInk, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1
                )
                Box(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    if (exercise.exercise?.activityType == "WHITEBOARD") {
                        Box(Modifier.fillMaxSize().padding(10.dp)) {
                            ActivityRenderer(
                                exercise = exercise, isSpecialist = true, mediaToken = viewModel.mediaToken, selectedAnswer = exercise.latestAnswer,
                                onSelectTool = viewModel::selectWhiteboardTool, onSelectColor = viewModel::selectWhiteboardColor, onSelectBrush = viewModel::selectWhiteboardBrush,
                                onStrokeStart = viewModel::beginWhiteboardStroke, onStrokePoint = viewModel::appendWhiteboardPoint, onStrokeEnd = viewModel::endWhiteboardStroke,
                                onUndo = viewModel::whiteboardUndo, onClear = viewModel::whiteboardClear, onChildPermission = viewModel::setChildDrawingEnabled
                            )
                        }
                    } else if (exercise.exercise != null) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                            ExerciseStage(state = exercise, specialistView = true, mediaToken = viewModel.mediaToken)
                        }
                    } else {
                        Text("Задание появится здесь", color = PultMuted, fontSize = 16.sp)
                    }
                }
            }
            LivePanel(
                exercise = exercise, childConnected = childConnected, socketState = state.socketState,
                onShow = viewModel::launchExercise, onStart = viewModel::launchExercise, onCompleteExercise = viewModel::completeWhiteboardExercise,
                onNext = viewModel::nextExercise, onSummary = onOpenSummary, onComplete = { showConfirmation = true },
                modifier = Modifier.weight(.4f).fillMaxHeight()
            )
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
                ConnectionStatusLine("Ребёнок", if (childConnected) "подключён" else "ожидание подключения", if (childConnected) Color(0xFF31B96A) else OylaTextMuted)
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

private val PultBackground = Color(0xFFF3F5FA)
private val PultInk = Color(0xFF18213A)
private val PultMuted = Color(0xFF5B6680)
private val PultBlue = Color(0xFF1F4FD1)
private val PultGreen = Color(0xFF2EAD5B)
private val PultOrange = Color(0xFFF08C00)

@Composable
private fun PultTopBar(childName: String, exercise: ExerciseUiState, childConnected: Boolean, socketState: SocketConnectionState, onSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFFFB648)), contentAlignment = Alignment.Center) {
            Text(childName.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(childName, color = PultInk, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (childConnected && socketState == SocketConnectionState.CONNECTED) PultGreen else PultOrange))
                Spacer(Modifier.width(6.dp))
                Text(
                    (if (childConnected) "Ребёнок на связи" else "Ждём ребёнка") + " · " + formatTime(exercise.elapsedMillis),
                    color = PultMuted, fontSize = 13.sp
                )
            }
        }
        Spacer(Modifier.width(24.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..exercise.totalExercises.coerceAtLeast(1)).forEach { position ->
                val done = position < exercise.currentPosition || (position == exercise.currentPosition && exercise.exerciseStatus == ExerciseUiStatus.COMPLETED)
                val current = position == exercise.currentPosition && !done
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(when { done -> PultGreen; current -> PultBlue; else -> Color(0xFFD5DBE8) }))
            }
        }
        Spacer(Modifier.width(16.dp))
        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Настройки подключения", tint = PultInk) }
    }
}

@Composable
private fun StatusChip(status: ExerciseUiStatus) {
    val (label, bg, fg) = when (status) {
        ExerciseUiStatus.PENDING -> Triple("Не показано", Color(0xFFEEF1F6), PultMuted)
        ExerciseUiStatus.SHOWN -> Triple("Ребёнок видит", Color(0xFFE6EDFF), PultBlue)
        ExerciseUiStatus.RUNNING -> Triple("Идёт", Color(0xFFE8F6EE), Color(0xFF1E7A43))
        ExerciseUiStatus.COMPLETED -> Triple("Выполнено", Color(0xFFE8F6EE), Color(0xFF1E7A43))
    }
    Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
private fun LivePanel(
    exercise: ExerciseUiState, childConnected: Boolean, socketState: SocketConnectionState,
    onShow: () -> Unit, onStart: () -> Unit, onCompleteExercise: () -> Unit, onNext: () -> Unit, onSummary: () -> Unit,
    onComplete: () -> Unit, modifier: Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Что делает ребёнок", color = PultMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (exercise.progressTotal > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFEEF1F6))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((exercise.progressDone.toFloat() / exercise.progressTotal).coerceIn(0f, 1f)).clip(RoundedCornerShape(4.dp)).background(PultGreen))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${exercise.progressDone} из ${exercise.progressTotal}", color = PultInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            val log = exercise.answerLog.asReversed()
            if (log.isEmpty()) {
                Text(
                    when (exercise.exerciseStatus) {
                        ExerciseUiStatus.PENDING, ExerciseUiStatus.SHOWN -> "Нажмите «Начать задание»"
                        ExerciseUiStatus.RUNNING -> if (exercise.exercise?.activityType == "WHITEBOARD") "Ребёнок рисует" else "Ждём действий ребёнка…"
                        ExerciseUiStatus.COMPLETED -> "Задание выполнено"
                    },
                    color = PultMuted, fontSize = 14.sp
                )
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                log.forEach { answer ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(if (answer.isCorrect) PultGreen else PultOrange), contentAlignment = Alignment.Center) {
                            Text(if (answer.isCorrect) "✓" else "×", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(answerText(answer, exercise.exercise), color = PultInk, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
                        Text(formatSeconds(answer.responseTimeMs), color = PultMuted, fontSize = 12.sp)
                    }
                }
            }
            exercise.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        }
        val primary = Modifier.fillMaxWidth().height(52.dp)
        val enabledCommands = !exercise.isCommandLoading
        when (exercise.exerciseStatus) {
            // Show + start are one action for the specialist.
            ExerciseUiStatus.PENDING, ExerciseUiStatus.SHOWN -> PultButton("Начать задание", Icons.Outlined.PlayArrow, onStart, childConnected && socketState == SocketConnectionState.CONNECTED && enabledCommands, primary)
            ExerciseUiStatus.RUNNING -> if (exercise.exercise?.activityType == "WHITEBOARD") {
                PultButton("Завершить рисунок", Icons.Outlined.CheckCircle, onCompleteExercise, enabledCommands, primary)
            } else {
                PultButton("Ребёнок выполняет…", Icons.Outlined.PlayArrow, {}, false, primary)
            }
            ExerciseUiStatus.COMPLETED -> if (exercise.currentPosition < exercise.totalExercises) {
                PultButton("Следующее задание", Icons.Outlined.NavigateNext, onNext, exercise.canMoveToNext(), primary)
            } else {
                PultButton("Посмотреть итог", Icons.Outlined.CheckCircle, onSummary, exercise.canOpenSummary(), primary)
            }
        }
        Button(
            onClick = onComplete,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB42318)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
            Icon(Icons.Outlined.StopCircle, null); Spacer(Modifier.width(8.dp)); Text("Завершить занятие", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PultButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = PultBlue, contentColor = Color.White, disabledContainerColor = Color(0xFFD5DBE8), disabledContentColor = PultMuted)
    ) {
        Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

private fun answerText(answer: AnswerUiModel, exercise: ExerciseUiModel?): String {
    var label = answer.selectedOptionLabel.ifBlank { answer.selectedOptionId }
    if (exercise?.activityType == "MEMORY" && !answer.isCorrect) {
        val second = exercise.options.firstOrNull { it.id == answer.secondaryValue }?.label
        if (second != null) label = "$label + $second"
    }
    return if (answer.isCorrect) "$label — верно" else "$label — мимо"
}

private fun formatSeconds(millis: Long): String = "%.1f с".format(millis / 1000f)

@Composable
private fun ConnectionStatusLine(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = OylaTextMuted, fontSize = 16.sp); Spacer(Modifier.weight(1f)); Text("●  $value", color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

internal fun formatTime(millis: Long): String { val seconds = (millis / 1_000).coerceAtLeast(0); return "%02d:%02d".format(seconds / 60, seconds % 60) }
private fun serverLabel(state: SocketConnectionState) = when (state) { SocketConnectionState.CONNECTED -> "online"; SocketConnectionState.RECONNECTING, SocketConnectionState.CONNECTING -> "переподключение"; SocketConnectionState.DISCONNECTED -> "нет связи" }
private fun serverColor(state: SocketConnectionState) = if (state == SocketConnectionState.CONNECTED) Color(0xFF239A54) else OylaTextMuted
