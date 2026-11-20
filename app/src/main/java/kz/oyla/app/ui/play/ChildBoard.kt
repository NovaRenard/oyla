package kz.oyla.app.ui.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kz.oyla.app.data.remote.SocketConnectionState
import kz.oyla.app.data.remote.dto.WhiteboardBrushSize
import kz.oyla.app.data.remote.dto.WhiteboardColor
import kz.oyla.app.data.remote.dto.WhiteboardPointDto
import kz.oyla.app.data.remote.dto.WhiteboardTool
import kz.oyla.app.ui.session.ExerciseUiState
import kz.oyla.app.ui.session.ExerciseUiStatus
import kz.oyla.app.ui.session.WhiteboardUiState
import kz.oyla.app.ui.session.normalizeWhiteboardPoint
import kz.oyla.app.ui.session.normalizedWidth
import kz.oyla.app.ui.session.toComposeColor

class BoardActions(
    val onSelectTool: (WhiteboardTool) -> Unit,
    val onSelectColor: (WhiteboardColor) -> Unit,
    val onSelectBrush: (WhiteboardBrushSize) -> Unit,
    val onStrokeStart: (WhiteboardPointDto) -> String?,
    val onStrokePoint: (String, WhiteboardPointDto) -> Unit,
    val onStrokeEnd: (String) -> Unit,
    val onUndo: () -> Unit
)

private enum class KidTool { PENCIL, BRUSH, ERASER }

/** Full-screen drawing board for the child: big tools on the left, paint blobs at the bottom. */
@Composable
fun ChildBoard(state: ExerciseUiState, mediaToken: String?, actions: BoardActions) {
    val config = state.exercise?.whiteboardConfig ?: return
    val board = state.whiteboard ?: WhiteboardUiState(selectedColor = config.defaultColor, selectedBrushSize = config.defaultBrushSize, childDrawingEnabled = config.childDrawingInitiallyEnabled)
    val canDraw = state.exerciseStatus == ExerciseUiStatus.RUNNING && state.connectionState == SocketConnectionState.CONNECTED && board.childDrawingEnabled
    val tool = when {
        board.selectedTool == WhiteboardTool.ERASER -> KidTool.ERASER
        board.selectedBrushSize == WhiteboardBrushSize.THICK -> KidTool.BRUSH
        else -> KidTool.PENCIL
    }
    Box(Modifier.fillMaxSize()) {
        // Drawing surface: everything right of the toolbar.
        Box(Modifier.offset(130.u, 0.u).size(1150.u, 800.u)) {
            config.backgroundUrl?.let { url ->
                val request = ImageRequest.Builder(LocalContext.current).data(url).apply { mediaToken?.takeIf(String::isNotBlank)?.let { addHeader("Authorization", "Bearer $it") } }.build()
                AsyncImage(request, "Фон доски", Modifier.fillMaxSize().padding(40.u), contentScale = ContentScale.Fit)
            }
            kz.oyla.app.ui.session.TraceTemplateLayer(config.traceTemplate, Modifier.fillMaxSize())
            var strokeId by remember(board.boardRevision, state.sessionExerciseId) { mutableStateOf<String?>(null) }
            Canvas(
                Modifier.fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .semantics { contentDescription = "Поле для рисования" }
                    .pointerInput(canDraw, board.selectedTool, board.selectedColor, board.selectedBrushSize) {
                        if (canDraw) detectDragGestures(
                            onDragStart = { o -> strokeId = actions.onStrokeStart(normalizeWhiteboardPoint(o.x, o.y, size.width.toFloat(), size.height.toFloat())) },
                            onDrag = { change, _ -> strokeId?.let { actions.onStrokePoint(it, normalizeWhiteboardPoint(change.position.x, change.position.y, size.width.toFloat(), size.height.toFloat())) }; change.consume() },
                            onDragEnd = { strokeId?.let(actions.onStrokeEnd); strokeId = null },
                            onDragCancel = { strokeId?.let(actions.onStrokeEnd); strokeId = null }
                        )
                    }
            ) {
                (board.strokes + board.inProgress.values).forEach { stroke ->
                    val color = stroke.color?.toComposeColor() ?: Color.Transparent
                    val width = minOf(size.width, size.height) * stroke.brushSize.normalizedWidth()
                    val blend = if (stroke.tool == WhiteboardTool.ERASER) BlendMode.Clear else BlendMode.SrcOver
                    val points = stroke.points.map { Offset(it.x * size.width, it.y * size.height) }
                    if (points.size == 1) drawCircle(color, width / 2f, points[0], blendMode = blend)
                    else if (points.size > 1) drawPath(
                        Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } },
                        color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = blend
                    )
                }
            }
        }

        // Toolbar.
        Column(
            Modifier.width(130.u).fillMaxHeight().background(Color.White).padding(top = 150.u, bottom = 24.u),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.u)
        ) {
            ToolTile(tool == KidTool.PENCIL, "Карандаш", canDraw, onClick = { actions.onSelectTool(WhiteboardTool.PEN); actions.onSelectBrush(WhiteboardBrushSize.MEDIUM) }) { PencilGlyph(Modifier.size(54.u)) }
            ToolTile(tool == KidTool.BRUSH, "Толстая кисть", canDraw, onClick = { actions.onSelectTool(WhiteboardTool.PEN); actions.onSelectBrush(WhiteboardBrushSize.THICK) }) { BrushGlyph(Modifier.size(54.u)) }
            if (config.allowEraser) ToolTile(tool == KidTool.ERASER, "Ластик", canDraw, onClick = { actions.onSelectTool(WhiteboardTool.ERASER); actions.onSelectBrush(WhiteboardBrushSize.THICK) }) { EraserGlyph(Modifier.size(54.u)) }
            Spacer(Modifier.weight(1f))
            ToolTile(false, "Отменить", canDraw, onClick = actions.onUndo) { UndoGlyph(Modifier.size(50.u)) }
        }

        // Palette.
        Box(Modifier.offset(130.u, 690.u).size(1150.u, 100.u), contentAlignment = Alignment.Center) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 24.u, vertical = 14.u),
                horizontalArrangement = Arrangement.spacedBy(18.u), verticalAlignment = Alignment.CenterVertically
            ) {
                config.availableColors.forEach { color ->
                    val selected = board.selectedTool == WhiteboardTool.PEN && board.selectedColor == color
                    Box(
                        Modifier.size(if (selected) 74.u else 64.u).clip(CircleShape).background(color.toComposeColor())
                            .border(5.u, if (selected) Play.Ink else Color.White, CircleShape)
                            .semantics { contentDescription = color.label() }
                            .clickable(interactionSource = null, indication = null, enabled = canDraw, role = Role.Button) { actions.onSelectColor(color) }
                    )
                }
            }
        }
        if (!board.childDrawingEnabled) {
            Box(Modifier.offset(130.u, 600.u).size(1150.u, 70.u), contentAlignment = Alignment.Center) {
                Text("Смотри, как рисует взрослый", color = Play.Ink, fontSize = 30.us, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(30.u)).background(Color(0xFFFFE7A0)).padding(horizontal = 26.u, vertical = 8.u))
            }
        }
    }
}

private fun WhiteboardColor.label() = when (this) {
    WhiteboardColor.BLACK -> "чёрный"; WhiteboardColor.BLUE -> "синий"; WhiteboardColor.GREEN -> "зелёный"
    WhiteboardColor.RED -> "красный"; WhiteboardColor.ORANGE -> "оранжевый"; WhiteboardColor.PURPLE -> "фиолетовый"
}

@Composable
private fun ToolTile(selected: Boolean, label: String, enabled: Boolean, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Box(
        Modifier.size(96.u).clip(RoundedCornerShape(30.u))
            .background(if (selected) Color(0xFFFFE7A0) else Color(0xFFF4F1EA))
            .semantics { contentDescription = label }
            .clickable(interactionSource = null, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else .5f },
        contentAlignment = Alignment.Center
    ) { glyph() }
}

@Composable
private fun PencilGlyph(modifier: Modifier) = Canvas(modifier) {
    val k = size.width / 24f
    val body = Path().apply { moveTo(4f * k, 20f * k); lineTo(5f * k, 15f * k); lineTo(16f * k, 4f * k); lineTo(20f * k, 8f * k); lineTo(9f * k, 19f * k); close() }
    drawPath(body, Color(0xFFFFC94D)); drawPath(body, Play.Ink, style = Stroke(1.6f * k, join = StrokeJoin.Round))
    drawPath(Path().apply { moveTo(4f * k, 20f * k); lineTo(5f * k, 15f * k); lineTo(9f * k, 19f * k); close() }, Play.Ink)
}

@Composable
private fun BrushGlyph(modifier: Modifier) = Canvas(modifier) {
    val k = size.width / 24f
    val handle = Path().apply { moveTo(14f * k, 3f * k); lineTo(21f * k, 10f * k); lineTo(13f * k, 13f * k); lineTo(11f * k, 11f * k); close() }
    drawPath(handle, Color(0xFFB5763F)); drawPath(handle, Play.Ink, style = Stroke(1.6f * k, join = StrokeJoin.Round))
    val tip = Path().apply { moveTo(11f * k, 11f * k); lineTo(13f * k, 13f * k); quadraticTo(12f * k, 20f * k, 4f * k, 21f * k); quadraticTo(5f * k, 13f * k, 11f * k, 11f * k); close() }
    drawPath(tip, Color(0xFFFF6B6B)); drawPath(tip, Play.Ink, style = Stroke(1.6f * k, join = StrokeJoin.Round))
}

@Composable
private fun EraserGlyph(modifier: Modifier) = Canvas(modifier) {
    val k = size.width / 24f
    val body = Path().apply { moveTo(3f * k, 15f * k); lineTo(13f * k, 5f * k); lineTo(20f * k, 12f * k); lineTo(13f * k, 19f * k); lineTo(7f * k, 19f * k); close() }
    drawPath(body, Color(0xFFFF9FB2)); drawPath(body, Play.Ink, style = Stroke(1.6f * k, join = StrokeJoin.Round))
    drawLine(Play.Ink, Offset(8f * k, 10f * k), Offset(15f * k, 17f * k), 1.6f * k)
}

@Composable
private fun UndoGlyph(modifier: Modifier) = Canvas(modifier) {
    val k = size.width / 24f
    drawPath(Path().apply {
        moveTo(9f * k, 5f * k); lineTo(4f * k, 10f * k); lineTo(9f * k, 15f * k)
        moveTo(4f * k, 10f * k); lineTo(14f * k, 10f * k); quadraticTo(20f * k, 10f * k, 20f * k, 15.5f * k); quadraticTo(20f * k, 20f * k, 14f * k, 20f * k); lineTo(10f * k, 20f * k)
    }, Play.Ink, style = Stroke(2.4f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
