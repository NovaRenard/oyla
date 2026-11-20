package kz.oyla.app.ui.lesson

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tablet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kz.oyla.app.data.remote.dto.DeviceCatalogChild
import kz.oyla.app.data.remote.dto.DeviceLessonResponse
import kz.oyla.app.data.remote.dto.DeviceLessonTemplate
import kz.oyla.app.ui.components.OylaLogo
import kz.oyla.app.ui.theme.OylaBlue
import kz.oyla.app.ui.theme.OylaNavy
import kz.oyla.app.ui.theme.OylaTextMuted

private val Page = Color(0xFFF3F5FA)
private val Line = Color(0xFFE2E7F0)
private val Green = Color(0xFF2EAE5B)
private val Palette = listOf(
    Color(0xFFFF8A5B), Color(0xFF4C9BFF), Color(0xFF9B6BFF), Color(0xFF2EBD8C),
    Color(0xFFFFB938), Color(0xFFFF6B9A), Color(0xFF3CC4D6), Color(0xFF7A8CFF)
)

private fun colorFor(key: String) = Palette[(key.hashCode() and 0x7fffffff) % Palette.size]

/** Short, child-friendly name of each game type, shown inside lesson cards. */
private fun gameLabel(type: String) = when (type) {
    "SINGLE_CHOICE" -> "Выбор"
    "ODD_ONE" -> "Лишнее"
    "REMEMBER" -> "Запомни"
    "MEMORY" -> "Пары"
    "SORT" -> "Разложи"
    "WORD_BUILD" -> "Слово"
    "MAZE" -> "Лабиринт"
    "WHITEBOARD" -> "Рисуем"
    "POP" -> "Шарики"
    "SYLLABLES" -> "Барабан"
    "SHADOW" -> "Тени"
    "COUNT" -> "Счёт"
    else -> "Игра"
}

private fun gameColor(type: String) = when (type) {
    "SINGLE_CHOICE", "ODD_ONE" -> Color(0xFF4C9BFF)
    "REMEMBER", "MEMORY" -> Color(0xFF9B6BFF)
    "SORT", "SHADOW" -> Color(0xFF2EBD8C)
    "WORD_BUILD", "SYLLABLES" -> Color(0xFFFF8A5B)
    "MAZE", "WHITEBOARD" -> Color(0xFFFF6B9A)
    "POP", "COUNT" -> Color(0xFFFFA726)
    else -> Color(0xFF7A8CFF)
}

/**
 * One screen to start a lesson: pick the child on the left, the lesson on the right, press
 * «Начать». The specialist and the child tablet are filled in automatically and can be changed
 * from the top bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewLessonScreen(
    viewModel: ManagedLessonLaunchViewModel,
    centerName: String,
    onOpenSettings: () -> Unit,
    onStarted: (DeviceLessonResponse) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadCatalog(force = true); viewModel.watchChildDevices() }
    DisposableEffect(Unit) { onDispose(viewModel::stopWatchingChildDevices) }
    LaunchedEffect(state.createdLesson?.sessionId) { state.createdLesson?.let { viewModel.consumeCreatedLesson(); onStarted(it) } }

    Column(Modifier.fillMaxSize().background(Page).safeDrawingPadding()) {
        // Top bar: who leads, which tablet, settings.
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OylaLogo(Modifier.width(120.dp))
            Column(Modifier.weight(1f)) {
                Text("Новое занятие", color = OylaNavy, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(centerName, color = OylaTextMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Picker(
                icon = null,
                avatarText = state.specialist?.firstName?.take(1),
                title = state.specialist?.let { "${it.firstName} ${it.lastName.orEmpty()}".trim() } ?: "Выберите специалиста",
                caption = state.specialist?.specialization ?: "Ведёт занятие",
                options = state.specialists.map { it.id to "${it.firstName} ${it.lastName.orEmpty()}".trim() },
                onSelect = viewModel::selectSpecialist
            )
            Picker(
                icon = Icons.Outlined.Tablet,
                avatarText = null,
                title = state.childDevice?.name ?: if (state.devicesLoaded) "Нет планшета" else "Ищем планшет…",
                caption = if (state.childDevice != null) "● на связи" else "Включите детский планшет",
                captionColor = if (state.childDevice != null) Green else Color(0xFFD0582A),
                options = state.childDevices.map { it.id to it.name },
                onSelect = viewModel::selectChildDevice
            )
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Page).clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Settings, "Настройки", tint = OylaNavy) }
        }

        Row(Modifier.weight(1f).fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // Children.
            Column(Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("С кем занимаемся")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    items(state.children, key = { it.id }) { child -> ChildRow(child, child.id == state.childId) { viewModel.selectChild(child.id) } }
                }
            }
            // Lessons.
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Что делаем · ${state.templates.size} занятий")
                if (state.catalogLoading && state.templates.isEmpty()) CircularProgressIndicator(color = OylaBlue)
                state.errorMessage?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(message, color = Color(0xFFC62828), modifier = Modifier.weight(1f))
                        Button(onClick = viewModel::retryCatalog) { Text("Повторить") }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 250.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(sortedTemplates(state.templates), key = { it.id }) { template ->
                        LessonCard(template, template.id == state.lessonTemplateId) { viewModel.selectTemplate(template.id) }
                    }
                }
            }
        }

        // Start bar.
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val missing = when {
                state.specialistId == null -> "Выберите, кто ведёт занятие"
                state.childId == null -> "Выберите ребёнка"
                state.lessonTemplateId == null -> "Выберите занятие"
                state.childDeviceId == null -> "Включите детский планшет — он появится сам"
                else -> null
            }
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(state.child?.firstName?.let { "${it} ${state.child?.lastName.orEmpty()}".trim() }, state.template?.name).joinToString(" · ").ifBlank { "Занятие ещё не выбрано" },
                    color = OylaNavy, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(missing ?: "Всё готово — планшет ребёнка откроет занятие сам", color = if (missing == null) Green else OylaTextMuted, fontSize = 15.sp)
            }
            Button(
                onClick = viewModel::createLesson,
                enabled = missing == null && !state.isLoading,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OylaBlue),
                modifier = Modifier.heightIn(min = 64.dp).widthIn(min = 280.dp)
            ) {
                if (state.isLoading && state.templates.isNotEmpty()) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                else {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Начать занятие", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Centre lessons first (the specialist made them), then Oyla ones, alphabetically. */
private fun sortedTemplates(templates: List<DeviceLessonTemplate>) =
    templates.sortedWith(compareBy<DeviceLessonTemplate> { it.ownership == "SYSTEM" }.thenBy { it.name })

@Composable
private fun SectionTitle(text: String) = Text(text, color = OylaNavy, fontSize = 18.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Picker(
    icon: ImageVector?,
    avatarText: String?,
    title: String,
    caption: String,
    captionColor: Color = OylaTextMuted,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(18.dp)).background(Page).clickable(enabled = options.size > 1) { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 280.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (icon != null) Color.White else colorFor(title)), contentAlignment = Alignment.Center) {
                if (icon != null) Icon(icon, null, tint = OylaNavy) else Text(avatarText ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(title, color = OylaNavy, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(caption, color = captionColor, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (options.size > 1) Icon(Icons.Outlined.ExpandMore, "Сменить", tint = OylaTextMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(id) }) }
        }
    }
}

@Composable
private fun ChildRow(child: DeviceCatalogChild, selected: Boolean, onClick: () -> Unit) {
    val name = "${child.firstName} ${child.lastName.orEmpty()}".trim()
    val bg by animateColorAsState(if (selected) Color(0xFFE7F0FF) else Color.White, label = "child")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg)
            .border(2.dp, if (selected) OylaBlue else Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(colorFor(name)), contentAlignment = Alignment.Center) {
            Text(child.firstName.take(1), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.weight(1f)) {
            Text(name, color = OylaNavy, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            child.birthDate?.let { Text(it, color = OylaTextMuted, fontSize = 13.sp) }
        }
        if (selected) Icon(Icons.Outlined.CheckCircle, "Выбран", tint = OylaBlue)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LessonCard(template: DeviceLessonTemplate, selected: Boolean, onClick: () -> Unit) {
    val accent = colorFor(template.name)
    Column(
        Modifier.fillMaxWidth().height(196.dp).clip(RoundedCornerShape(24.dp)).background(Color.White)
            .border(if (selected) 3.dp else 1.dp, if (selected) OylaBlue else Line, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().height(10.dp).background(accent))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(template.name, color = OylaNavy, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Outlined.CheckCircle, "Выбрано", tint = OylaBlue, modifier = Modifier.size(28.dp))
            }
            Text(
                "${template.exerciseCount} ${tasksWord(template.exerciseCount)} · ${if (template.ownership == "SYSTEM") "Oyla" else "Ваш центр"}",
                color = OylaTextMuted, fontSize = 13.sp
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxLines = 2) {
                template.exercises.map { it.activityType }.distinct().forEach { type ->
                    Text(
                        gameLabel(type), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(gameColor(type)).padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

private fun tasksWord(n: Int): String {
    val mod10 = n % 10; val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "задание"
        mod10 in 2..4 && mod100 !in 12..14 -> "задания"
        else -> "заданий"
    }
}
