package kz.oyla.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.oyla.app.R
import kz.oyla.app.data.remote.dto.ExerciseSummaryItemDto
import kz.oyla.app.data.remote.dto.SessionSummaryResponse
import kz.oyla.app.ui.components.OylaBackground
import kz.oyla.app.ui.components.OylaLogo
import kz.oyla.app.ui.components.OylaPrimaryButton
import kz.oyla.app.ui.theme.OylaBlue
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaTextMuted

@Composable
fun SpecialistSummaryScreen(viewModel: SpecialistSessionViewModel, onCompleted: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.restoreActiveSession() }
    LaunchedEffect(state.session?.sessionId) { if (state.session != null) viewModel.loadSummary() }
    LaunchedEffect(state.sessionEndedId) {
        if (state.sessionEndedId != null) { viewModel.consumeSessionEnd(); onCompleted() }
    }
    BackHandler(enabled = true) { }
    Box(Modifier.fillMaxSize()) {
        OylaBackground(R.drawable.bg_specialist_home)
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp, vertical = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OylaLogo(Modifier.width(150.dp))
                Spacer(Modifier.weight(1f))
                Text("Итог занятия", color = OylaNavy, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            val summary = state.summary
            if (summary == null) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (state.isLoading) CircularProgressIndicator(color = OylaBlue) else Text(state.errorMessage ?: "Загружаем итог…", color = OylaTextMuted, fontSize = 20.sp)
                }
            } else {
                SummaryContent(summary, state.session?.specialistName, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            OylaPrimaryButton(
                text = "Завершить занятие", icon = Icons.Outlined.CheckCircle, iconDescription = "Завершить занятие",
                onClick = { viewModel.completeSession(onCompleted) }, enabled = !state.isLoading && summary != null,
                textSize = 18.sp, minHeight = 56.dp, modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SummaryContent(summary: SessionSummaryResponse, specialistName: String?, modifier: Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier.verticalScroll(rememberScrollState())) {
        Text("Ребёнок: ${summary.childName}", color = OylaNavy, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        specialistName?.let { Text("Специалист: $it", color = OylaNavy, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SummaryMetric("Выполнено", "${summary.completedExercises} из ${summary.totalExercises}", Modifier.weight(1f))
            SummaryMetric("Общее время", formatTime(summary.activeDurationMs), Modifier.weight(1f))
            SummaryMetric("Всего попыток", summary.totalAttempts.toString(), Modifier.weight(1f))
            SummaryMetric("Ошибок", summary.incorrectAttempts.toString(), Modifier.weight(1f))
            SummaryMetric("С первой попытки", "${summary.firstAttemptCorrectCount} (${summary.firstAttemptCorrectPercent}%)", Modifier.weight(1f))
        }
        summary.exercises.sortedBy { it.position }.forEach { SummaryExerciseRow(it) }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.94f)), modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, color = OylaTextMuted, fontSize = 13.sp)
            Text(value, color = OylaNavy, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SummaryExerciseRow(item: ExerciseSummaryItemDto) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(.95f)), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Column(modifier = Modifier.weight(1.25f)) {
                Text("Задание ${item.position}", color = OylaNavy, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(soundTitle(item.exerciseId), color = OylaBlue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            SummaryValue("Правильный ответ", item.correctOptionLabel, Modifier.weight(1.35f))
            SummaryValue("Попыток", item.attemptCount.toString(), Modifier.weight(.65f))
            SummaryValue("Ошибок", item.incorrectAttempts.toString(), Modifier.weight(.65f))
            SummaryValue("Время", formatTime(item.timeToCorrectMs), Modifier.weight(.7f))
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier.padding(horizontal = 5.dp)) {
        Text(label, color = OylaTextMuted, fontSize = 12.sp)
        Text(value, color = OylaNavy, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun soundTitle(exerciseId: String) = when {
    exerciseId.startsWith("sound-sh-") -> "Звук «Ш»"
    exerciseId.startsWith("sound-r-") -> "Звук «Р»"
    exerciseId.startsWith("sound-l-") -> "Звук «Л»"
    exerciseId.startsWith("sound-s-") -> "Звук «С»"
    else -> "Упражнение"
}
