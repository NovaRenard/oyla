package kz.oyla.app.ui.lesson

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.oyla.app.R
import kz.oyla.app.data.remote.dto.DeviceLessonResponse
import kz.oyla.app.ui.components.OylaBackground
import kz.oyla.app.ui.components.OylaLogo
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaTextMuted

@Composable
fun SpecialistSelectionScreen(viewModel: ManagedLessonLaunchViewModel, onNext: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState(); LaunchedEffect(Unit) { viewModel.loadCatalog() }
    var search by remember { mutableStateOf("") }
    SelectionScaffold(title = "Кто проводит занятие?", subtitle = "Выберите специалиста этого центра", loading = state.isLoading, error = state.errorMessage, onBack = onBack) {
        TextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Поиск специалиста") })
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            items(state.specialists.filter { "${it.firstName} ${it.lastName.orEmpty()} ${it.specialization.orEmpty()}".contains(search, ignoreCase = true) }, key = { it.id }) { person -> SelectionCard("${person.firstName} ${person.lastName.orEmpty()}", person.specialization ?: "Специалист", person.id == state.specialistId) { viewModel.selectSpecialist(person.id) } }
        }
        ContinueButton(enabled = state.specialistId != null, onClick = onNext)
    }
}

@Composable
fun ChildSelectionScreen(viewModel: ManagedLessonLaunchViewModel, onNext: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState(); LaunchedEffect(Unit) { viewModel.loadCatalog() }
    SelectionScaffold(title = "С кем занимаемся?", subtitle = "Выберите ребёнка этого центра", loading = state.isLoading, error = state.errorMessage, onBack = onBack) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            items(state.children, key = { it.id }) { child -> SelectionCard("${child.firstName} ${child.lastName.orEmpty()}", child.birthDate ?: "Дата рождения не указана", child.id == state.childId) { viewModel.selectChild(child.id) } }
        }
        ContinueButton(enabled = state.childId != null, onClick = onNext)
    }
}

@Composable
fun ChildDeviceSelectionScreen(viewModel: ManagedLessonLaunchViewModel, onNext: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState(); LaunchedEffect(Unit) { viewModel.loadAvailableChildDevices() }
    SelectionScaffold(title = "Какой планшет ребёнка?", subtitle = "Показываем только активные планшеты, доступные сейчас", loading = state.isLoading, error = state.errorMessage, onBack = onBack) {
        if (!state.isLoading && state.childDevices.isEmpty()) {
            Text("Нет свободных детских планшетов. Проверьте подключение и доступ устройства.", color = OylaTextMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = viewModel::loadAvailableChildDevices, modifier = Modifier.fillMaxWidth()) { Text("Обновить") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            items(state.childDevices, key = { it.id }) { device -> SelectionCard(device.name, "Подключён · готов к занятию", device.id == state.childDeviceId) { viewModel.selectChildDevice(device.id) } }
        }
        ContinueButton(enabled = state.childDeviceId != null, onClick = onNext)
    }
}

@Composable
fun LessonConfirmationScreen(viewModel: ManagedLessonLaunchViewModel, onBack: () -> Unit, onStarted: (DeviceLessonResponse) -> Unit) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state.createdLesson?.sessionId) { state.createdLesson?.let { lesson -> viewModel.consumeCreatedLesson(); onStarted(lesson) } }
    SelectionScaffold(title = "Проверьте занятие", subtitle = "Никаких кодов: выбранный детский планшет получит приглашение автоматически", loading = state.isLoading, error = state.errorMessage, onBack = onBack) {
        Card(modifier = Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(20.dp)) {
            Text("Специалист: ${state.specialist?.firstName.orEmpty()} ${state.specialist?.lastName.orEmpty()}")
            state.specialist?.specialization?.let { Text(it, color = OylaTextMuted) }
            Text("Ребёнок: ${state.child?.firstName.orEmpty()} ${state.child?.lastName.orEmpty()}")
            Text("Планшет: ${state.childDevice?.name.orEmpty()}")
            Text("Занятие: Базовое занятие Oyla · 5 упражнений", fontWeight = FontWeight.Bold)
        } }
        Spacer(Modifier.height(8.dp))
        Button(enabled = !state.isLoading && state.specialistId != null && state.childId != null && state.childDeviceId != null, onClick = viewModel::createLesson, modifier = Modifier.fillMaxWidth()) { Text(if (state.isLoading) "Запускаем…" else "Начать занятие") }
    }
}

@Composable
fun ChildIdleScreen(viewModel: ChildAssignmentViewModel, onAssigned: (kz.oyla.app.data.remote.dto.ChildLessonAssignmentResponse) -> Unit) {
    val state by viewModel.uiState.collectAsState(); LaunchedEffect(Unit) { viewModel.pollAssignments() }
    DisposableEffect(Unit) { onDispose(viewModel::stopPolling) }
    LaunchedEffect(state.assignment?.sessionId) { state.assignment?.let { assignment -> viewModel.consumeAssignment(); onAssigned(assignment) } }
    BackHandler(enabled = true) { }
    Box(modifier = Modifier.fillMaxSize()) { OylaBackground(R.drawable.bg_child_connect); Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.align(Alignment.Center).safeDrawingPadding().padding(32.dp).widthIn(max = 620.dp)) {
        OylaLogo(modifier = Modifier.fillMaxWidth(0.35f))
        Text("OYLA Планшет готов к занятию", color = OylaNavy, fontWeight = FontWeight.Bold, fontSize = 38.sp, textAlign = TextAlign.Center)
        Text("Ожидайте специалиста", color = OylaTextMuted, fontSize = 26.sp, textAlign = TextAlign.Center)
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text("● Подключено", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
        state.errorMessage?.let { Text(it, color = OylaTextMuted, textAlign = TextAlign.Center) }
    } }
}

@Composable
private fun SelectionScaffold(title: String, subtitle: String, loading: Boolean, error: String?, onBack: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) { OylaBackground(R.drawable.bg_specialist_home); Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.align(Alignment.Center).safeDrawingPadding().padding(28.dp).widthIn(max = 720.dp)) {
        OylaLogo(modifier = Modifier.fillMaxWidth(0.24f)); Text(title, color = OylaNavy, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center); Text(subtitle, color = OylaTextMuted, textAlign = TextAlign.Center); if (loading) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary); error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }; content(); OutlinedButton(onClick = onBack, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
    } }
}

@Composable
private fun SelectionCard(title: String, caption: String, selected: Boolean, onClick: () -> Unit) { OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal); Text(if (selected) "Выбрано · $caption" else caption, color = OylaTextMuted, fontSize = 13.sp) } } }

@Composable
private fun ContinueButton(enabled: Boolean, onClick: () -> Unit) { Button(enabled = enabled, onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("Продолжить") } }
