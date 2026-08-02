package kz.oyla.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VolumeUp
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

@Composable
fun ChildExerciseScreen(viewModel: ChildSessionViewModel) {
    val state by viewModel.uiState.collectAsState()
    val exerciseState = state.exercise
    val audio = rememberExerciseAudioPlayer()
    LaunchedEffect(Unit) { viewModel.restoreActiveSession() }
    LaunchedEffect(exerciseState.playInstructionRequest) {
        if (exerciseState.playInstructionRequest > 0) {
            exerciseState.exercise?.let { audio.play(it.audioAssetKey, it.instructionText) }
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
                ChildStatusPill(exerciseState.exerciseStatus)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Card(
                    shape = RoundedCornerShape(30.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.95f)),
                    modifier = Modifier.weight(1f, fill = false).width(screenWidth * .51f)
                ) {
                    Text(
                        text = exerciseState.exercise?.instructionText ?: "Задание загружается…",
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
                    onOptionClick = viewModel::submitAnswer
                )
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
                    if (exerciseState.exerciseStatus == ExerciseUiStatus.SHOWN) "Жди команды специалиста" else "Нажми на картинку",
                    color = OylaBlue, fontSize = 21.sp, fontWeight = FontWeight.Medium
                )
            }
            exerciseState.errorMessage?.let { Text(it, color = Color(0xFFD35A45), fontSize = 15.sp) }
        }
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
