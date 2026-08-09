package kz.oyla.app.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kz.oyla.app.data.remote.SocketConnectionState
import kz.oyla.app.data.remote.dto.WhiteboardBrushSize
import kz.oyla.app.data.remote.dto.WhiteboardColor
import kz.oyla.app.data.remote.dto.WhiteboardPointDto
import kz.oyla.app.data.remote.dto.WhiteboardTool

/** The only activity-type dispatch point for the tablet lesson surface. */
@Composable
fun ActivityRenderer(
    exercise: ExerciseUiState,
    isSpecialist: Boolean,
    mediaToken: String?,
    selectedAnswer: AnswerUiModel? = null,
    onAnswer: (String) -> Unit = {},
    onSelectTool: (WhiteboardTool) -> Unit = {},
    onSelectColor: (WhiteboardColor) -> Unit = {},
    onSelectBrush: (WhiteboardBrushSize) -> Unit = {},
    onStrokeStart: (WhiteboardPointDto) -> String? = { null },
    onStrokePoint: (String, WhiteboardPointDto) -> Unit = { _, _ -> },
    onStrokeEnd: (String) -> Unit = {},
    onUndo: () -> Unit = {},
    onClear: () -> Unit = {},
    onChildPermission: (Boolean) -> Unit = {}
) {
    when (exercise.exercise?.activityType) {
        "WHITEBOARD" -> WhiteboardRenderer(
            state = exercise, isSpecialist = isSpecialist, mediaToken = mediaToken,
            onSelectTool = onSelectTool, onSelectColor = onSelectColor, onSelectBrush = onSelectBrush,
            onStrokeStart = onStrokeStart, onStrokePoint = onStrokePoint, onStrokeEnd = onStrokeEnd,
            onUndo = onUndo, onClear = onClear, onChildPermission = onChildPermission
        )
        else -> exercise.exercise?.let { ExerciseCardGrid(it, selectedAnswer, enabled = !isSpecialist && exercise.exerciseStatus == ExerciseUiStatus.RUNNING && !exercise.isAnswerPending, dimmed = exercise.exerciseStatus == ExerciseUiStatus.SHOWN, showCorrectMarker = isSpecialist, pendingOptionId = exercise.pendingOptionId, mediaToken = mediaToken, onOptionClick = onAnswer) }
    }
}

@Composable
private fun WhiteboardRenderer(
    state: ExerciseUiState,
    isSpecialist: Boolean,
    mediaToken: String?,
    onSelectTool: (WhiteboardTool) -> Unit,
    onSelectColor: (WhiteboardColor) -> Unit,
    onSelectBrush: (WhiteboardBrushSize) -> Unit,
    onStrokeStart: (WhiteboardPointDto) -> String?,
    onStrokePoint: (String, WhiteboardPointDto) -> Unit,
    onStrokeEnd: (String) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onChildPermission: (Boolean) -> Unit
) {
    val config = state.exercise?.whiteboardConfig ?: return
    val board = state.whiteboard ?: WhiteboardUiState(selectedColor = config.defaultColor, selectedBrushSize = config.defaultBrushSize, childDrawingEnabled = config.childDrawingInitiallyEnabled)
    val canDraw = state.exerciseStatus == ExerciseUiStatus.RUNNING && state.connectionState == SocketConnectionState.CONNECTED && (isSpecialist || board.childDrawingEnabled)
    var backgroundFailed by remember(config.backgroundUrl) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        WhiteboardToolbar(board, config.availableColors, config.allowEraser, isSpecialist, config.allowClear, canDraw,
            onSelectTool, onSelectColor, onSelectBrush, onUndo, onClear, onChildPermission)
        if (!isSpecialist && !board.childDrawingEnabled) Text("Смотрите на доску", color = Color(0xFF59677B))
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color.White)) {
                val context = LocalContext.current
                config.backgroundUrl?.let { url ->
                    val request = ImageRequest.Builder(context).data(url).apply { mediaToken?.takeIf(String::isNotBlank)?.let { addHeader("Authorization", "Bearer $it") } }.build()
                    AsyncImage(model = request, contentDescription = "Фон доски", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(), onError = { backgroundFailed = true })
                }
                if (isSpecialist && backgroundFailed) Text("Фон недоступен — используется пустая доска", color = Color(0xFF8A4B00), modifier = Modifier.align(Alignment.TopCenter).padding(8.dp))
                var currentStrokeId by remember(board.boardRevision, state.sessionExerciseId) { mutableStateOf<String?>(null) }
                Canvas(
                    modifier = Modifier.fillMaxSize().pointerInput(canDraw, board.selectedTool, board.selectedColor, board.selectedBrushSize) {
                        if (canDraw) detectDragGestures(
                            onDragStart = { offset -> currentStrokeId = onStrokeStart(normalizeWhiteboardPoint(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())) },
                            onDrag = { change, _ -> currentStrokeId?.let { onStrokePoint(it, normalizeWhiteboardPoint(change.position.x, change.position.y, size.width.toFloat(), size.height.toFloat())) }; change.consume() },
                            onDragEnd = { currentStrokeId?.let(onStrokeEnd); currentStrokeId = null },
                            onDragCancel = { currentStrokeId?.let(onStrokeEnd); currentStrokeId = null }
                        )
                    }
                ) {
                    (board.strokes + board.inProgress.values).forEach { stroke ->
                        val color = stroke.color?.toComposeColor() ?: Color.Transparent
                        val width = minOf(size.width, size.height) * stroke.brushSize.normalizedWidth()
                        val blend = if (stroke.tool == WhiteboardTool.ERASER) BlendMode.Clear else BlendMode.SrcOver
                        if (stroke.points.size == 1) drawCircle(color = color, radius = width / 2f, center = androidx.compose.ui.geometry.Offset(stroke.points[0].x * size.width, stroke.points[0].y * size.height), blendMode = blend)
                        else stroke.points.zipWithNext().forEach { (first, second) -> drawLine(color, androidx.compose.ui.geometry.Offset(first.x * size.width, first.y * size.height), androidx.compose.ui.geometry.Offset(second.x * size.width, second.y * size.height), strokeWidth = width, cap = StrokeCap.Round, blendMode = blend) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WhiteboardToolbar(
    board: WhiteboardUiState, colors: List<WhiteboardColor>, allowEraser: Boolean, isSpecialist: Boolean,
    allowClear: Boolean, canDraw: Boolean, onSelectTool: (WhiteboardTool) -> Unit, onSelectColor: (WhiteboardColor) -> Unit,
    onSelectBrush: (WhiteboardBrushSize) -> Unit, onUndo: () -> Unit, onClear: () -> Unit, onChildPermission: (Boolean) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)) {
        ToolButton("Перо", board.selectedTool == WhiteboardTool.PEN, canDraw) { onSelectTool(WhiteboardTool.PEN) }
        if (allowEraser) ToolButton("Ластик", board.selectedTool == WhiteboardTool.ERASER, canDraw) { onSelectTool(WhiteboardTool.ERASER) }
        colors.forEach { color -> Button(onClick = { onSelectColor(color) }, enabled = canDraw, colors = ButtonDefaults.buttonColors(containerColor = color.toComposeColor()), modifier = Modifier.size(34.dp)) {} }
        WhiteboardBrushSize.entries.forEach { size -> ToolButton(size.name.take(1), board.selectedBrushSize == size, canDraw) { onSelectBrush(size) } }
        ToolButton("↶", false, canDraw) { onUndo() }
        if (isSpecialist && allowClear) ToolButton("Очистить", false, canDraw) { onClear() }
        if (isSpecialist) ToolButton(if (board.childDrawingEnabled) "Ребёнок рисует" else "Ребёнок смотрит", board.childDrawingEnabled, true) { onChildPermission(!board.childDrawingEnabled) }
    }
}

@Composable private fun ToolButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) = Button(onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = if (selected) Color(0xFF2B73C9) else Color(0xFFE7EEF8), contentColor = if (selected) Color.White else Color(0xFF193758)), modifier = Modifier.heightIn(min = 34.dp)) { Text(label) }
private fun WhiteboardColor.toComposeColor() = when (this) { WhiteboardColor.BLACK -> Color.Black; WhiteboardColor.BLUE -> Color(0xFF246BCE); WhiteboardColor.GREEN -> Color(0xFF2F9C57); WhiteboardColor.RED -> Color(0xFFD64242); WhiteboardColor.ORANGE -> Color(0xFFF28A2A); WhiteboardColor.PURPLE -> Color(0xFF824CB3) }
private fun WhiteboardBrushSize.normalizedWidth() = when (this) { WhiteboardBrushSize.THIN -> .006f; WhiteboardBrushSize.MEDIUM -> .012f; WhiteboardBrushSize.THICK -> .022f }

/** Converts a Compose pointer to the stable canvas coordinate system shared by both tablets. */
internal fun normalizeWhiteboardPoint(x: Float, y: Float, width: Float, height: Float): WhiteboardPointDto =
    WhiteboardPointDto((x / width.takeIf { it > 0f }.orElse(1f)).coerceIn(0f, 1f), (y / height.takeIf { it > 0f }.orElse(1f)).coerceIn(0f, 1f))

private fun Float?.orElse(fallback: Float) = this ?: fallback
