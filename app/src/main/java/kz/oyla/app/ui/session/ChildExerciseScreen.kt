package kz.oyla.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.oyla.app.R
import kz.oyla.app.ui.components.OylaBackground
import kz.oyla.app.ui.components.OylaLogo
import kz.oyla.app.ui.theme.OylaBlue
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaTextMuted
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos

@Composable
fun ChildExerciseScreen(
    viewModel: ChildSessionViewModel,
    onOpenSettings: () -> Unit,
    onSessionEnded: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val exerciseState = state.exercise
    val audio = rememberExerciseAudioPlayer()
    var showCancelConfirmation by remember { mutableStateOf(false) }
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        OylaBackground(R.drawable.bg_child_connect)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = screenWidth * .045f, vertical = screenHeight * .035f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OylaLogo(Modifier.width(screenWidth * .13f))
                Spacer(Modifier.weight(1f))
                Text(
                    "Задание ${exerciseState.currentPosition} из ${exerciseState.totalExercises}",
                    color = OylaNavy, fontWeight = FontWeight.Bold, fontSize = 20.sp
                )
                Spacer(Modifier.width(16.dp))
                ChildStatusPill(exerciseState.exerciseStatus)
                Spacer(Modifier.width(12.dp))
                HoldToOpenSettingsButton(onOpenSettings = onOpenSettings)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Card(
                    shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.95f)),
                    modifier = Modifier.weight(1f, fill = false).width(screenWidth * .51f)
                ) {
                    Text(
                        text = when {
                            exerciseState.planCompleted -> "Все задания выполнены!"
                            exerciseState.exerciseStatus == ExerciseUiStatus.PENDING -> "Следующее задание готовится"
                            else -> exerciseState.exercise?.instructionText ?: "Задание загружается…"
                        },
                        color = OylaNavy, fontWeight = FontWeight.Bold, fontSize = 31.sp,
                        textAlign = TextAlign.Center, lineHeight = 39.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 25.dp)
                    )
                }
                Spacer(Modifier.width(26.dp))
                Button(
                    onClick = viewModel::repeatInstruction,
                    enabled = exerciseState.exercise != null,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OylaBlue),
                    modifier = Modifier.height(68.dp)
                ) { Icon(Icons.Outlined.VolumeUp, null); Spacer(Modifier.width(10.dp)); Text("Повторить", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
            if (exerciseState.exerciseStatus == ExerciseUiStatus.SHOWN) {
                Text("Жди команды специалиста", color = OylaTextMuted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            val exercise = exerciseState.exercise
            if (exercise != null) Box(modifier = Modifier.fillMaxWidth(.59f).weight(1f)) {
                ExerciseCardGrid(
                    exercise = exercise,
                    selectedAnswer = exerciseState.latestAnswer?.takeIf { exerciseState.exerciseStatus == ExerciseUiStatus.COMPLETED },
                    enabled = exerciseState.exerciseStatus == ExerciseUiStatus.RUNNING && !exerciseState.isAnswerPending,
                    dimmed = exerciseState.exerciseStatus == ExerciseUiStatus.SHOWN,
                    pendingOptionId = exerciseState.pendingOptionId,
                    mediaToken = viewModel.mediaToken,
                    onOptionClick = viewModel::submitAnswer
                )
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth(.59f).weight(1f)) {
                    Text(
                        if (exerciseState.planCompleted) "Отличная работа!" else "Жди, пока специалист покажет задание",
                        color = OylaTextMuted, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
                    )
                }
            }
            AnimatedVisibility(exerciseState.feedbackMessage != null) {
                Text(
                    text = exerciseState.feedbackMessage.orEmpty(),
                    color = if (exerciseState.latestAnswer?.isCorrect == true) Color(0xFF249C53) else OylaNavy,
                    fontSize = 25.sp, fontWeight = FontWeight.Bold
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Icon(Icons.Outlined.TouchApp, null, tint = OylaBlue)
                Spacer(Modifier.width(12.dp))
                Text(
                    when {
                        exerciseState.planCompleted -> "Жди завершения занятия"
                        exerciseState.exerciseStatus == ExerciseUiStatus.PENDING -> "Следующее задание готовится"
                        exerciseState.exerciseStatus == ExerciseUiStatus.SHOWN -> "Жди команды специалиста"
                        exerciseState.exerciseStatus == ExerciseUiStatus.COMPLETED -> "Жди следующее задание"
                        else -> "Нажми на картинку"
                    },
                    color = OylaBlue, fontSize = 21.sp, fontWeight = FontWeight.Medium
                )
            }
            Button(
                onClick = { showCancelConfirmation = true },
                enabled = !state.isLoading,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD35A45)),
                modifier = Modifier.fillMaxWidth(.4f)
            ) {
                Icon(Icons.Outlined.Cancel, null)
                Spacer(Modifier.width(8.dp))
                Text("Отменить занятие", fontWeight = FontWeight.Bold)
            }
            exerciseState.errorMessage?.let { Text(it, color = Color(0xFFD35A45), fontSize = 15.sp) }
        }
    }
    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmation = false },
            title = { Text("Отменить занятие?") },
            text = { Text("Занятие завершится на обоих устройствах.") },
            confirmButton = {
                Button(onClick = {
                    showCancelConfirmation = false
                    viewModel.cancelSession(onSessionEnded)
                }) { Text("Отменить") }
            },
            dismissButton = { Button(onClick = { showCancelConfirmation = false }) { Text("Продолжить") } }
        )
    }
}

@Composable
private fun HoldToOpenSettingsButton(onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    var holdJob by remember { mutableStateOf<Job?>(null) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(56.dp)
            .width(56.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        holdJob?.cancel()
                        progress = 0f
                        holdJob = scope.launch {
                            val holdDurationNanos = 3_000_000_000L
                            val startedAt = withFrameNanos { it }
                            while (progress < 1f) {
                                val now = withFrameNanos { it }
                                progress = ((now - startedAt).toFloat() / holdDurationNanos).coerceIn(0f, 1f)
                            }
                            onOpenSettings()
                        }
                        tryAwaitRelease()
                        holdJob?.cancel()
                        progress = 0f
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = OylaTextMuted.copy(alpha = .35f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = OylaBlue,
                startAngle = -90f,
                sweepAngle = progress * 360f,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = "Удерживайте 3 секунды, чтобы открыть настройки",
            tint = OylaNavy
        )
    }
}

@Composable
private fun ChildStatusPill(status: ExerciseUiStatus) {
    val text = when (status) {
        ExerciseUiStatus.SHOWN -> "●  Задание готово"
        ExerciseUiStatus.RUNNING -> "●  Состояние: активное задание"
        ExerciseUiStatus.COMPLETED -> "●  Задание выполнено"
        ExerciseUiStatus.PENDING -> "○  Ожидание задания"
    }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.75f))) {
        Text(text, color = if (status == ExerciseUiStatus.PENDING) OylaTextMuted else OylaNavy, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp))
    }
}
