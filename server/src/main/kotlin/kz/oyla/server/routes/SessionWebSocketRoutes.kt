package kz.oyla.server.routes

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.nio.charset.StandardCharsets
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kz.oyla.server.model.dto.StateSnapshotEvent
import kz.oyla.server.model.dto.WhiteboardClientEvent
import kz.oyla.server.model.dto.WhiteboardStrokeStartedEvent
import kz.oyla.server.model.dto.WhiteboardStrokePointsEvent
import kz.oyla.server.model.dto.WhiteboardStrokeCompletedEvent
import kz.oyla.server.model.dto.WhiteboardClearEvent
import kz.oyla.server.model.dto.WhiteboardUndoEvent
import kz.oyla.server.model.dto.WhiteboardChildPermissionChangedEvent
import kz.oyla.server.service.SessionEventHub
import kz.oyla.server.service.ExerciseService
import kz.oyla.server.service.SessionService
import kz.oyla.server.service.WhiteboardService

fun Route.sessionWebSocketRoutes(service: SessionService, exercises: ExerciseService, whiteboards: WhiteboardService, eventHub: SessionEventHub, json: Json) {
    webSocket("/ws/sessions/{sessionId}") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val token = call.request.queryParameters["token"]
        val authorized = runCatching { service.authorize(sessionId, token) }.getOrElse {
            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return@webSocket
        }
        eventHub.register(authorized.session.id, authorized.role, this)
        service.markSocketPresence(authorized, connected = true)
        try {
            val snapshot = exercises.stateFor(authorized)
            send(
                Frame.Text(
                    json.encodeToString(
                        StateSnapshotEvent(
                            sessionId = authorized.session.id.toString(),
                            status = authorized.session.status,
                            childConnected = authorized.session.childDeviceId != null,
                            exerciseStatus = snapshot.exerciseStatus,
                            sessionExerciseId = snapshot.sessionExerciseId,
                            exercise = snapshot.exercise,
                            correctOptionId = snapshot.correctOptionId,
                            latestAnswer = snapshot.latestAnswer,
                            attemptCount = snapshot.attemptCount,
                            startedAt = snapshot.startedAt,
                            currentPosition = snapshot.currentPosition,
                            totalExercises = snapshot.totalExercises,
                            hasPrevious = snapshot.hasPrevious,
                            hasNext = snapshot.hasNext,
                            planCompleted = snapshot.planCompleted,
                            whiteboardState = snapshot.whiteboardState
                        )
                    )
                )
            )
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val raw = frame.readText()
                    service.markSocketPresence(authorized, connected = true)
                    if (raw.toByteArray(StandardCharsets.UTF_8).size > MaxWhiteboardEventBytes) continue
                    runCatching { json.decodeFromString<WhiteboardClientEvent>(raw) }.getOrNull()?.let { event ->
                        // A malformed or unauthorized drawing command must not tear down the whole session socket.
                        runCatching {
                        when (event.type) {
                            "WHITEBOARD_STROKE_STARTED" -> {
                                val started = whiteboards.startStroke(authorized, event.sessionExerciseId.required(), event.strokeId.required(), checkNotNull(event.tool), event.color, checkNotNull(event.brushSize))
                                eventHub.publishWhiteboardStrokeStarted(authorized.session.id, WhiteboardStrokeStartedEvent(sessionId = authorized.session.id.toString(), sessionExerciseId = started.sessionExerciseId, strokeId = started.strokeId, actorRole = started.actorRole.name, tool = started.tool.name, color = started.color?.name, brushSize = started.brushSize.name))
                            }
                            "WHITEBOARD_STROKE_POINTS" -> {
                                val batch = whiteboards.appendPoints(authorized, event.sessionExerciseId.required(), event.strokeId.required(), event.points)
                                eventHub.publishWhiteboardPoints(authorized.session.id, WhiteboardStrokePointsEvent(sessionId = authorized.session.id.toString(), sessionExerciseId = batch.sessionExerciseId, strokeId = batch.strokeId, points = batch.points))
                            }
                            "WHITEBOARD_STROKE_COMPLETED" -> {
                                val stroke = whiteboards.completeStroke(authorized, event.sessionExerciseId.required(), event.strokeId.required(), event.clientEventId.required())
                                eventHub.publishWhiteboardStrokeCompleted(authorized.session.id, WhiteboardStrokeCompletedEvent(sessionId = authorized.session.id.toString(), sessionExerciseId = stroke.sessionExerciseId, stroke = stroke))
                            }
                            "WHITEBOARD_CLEAR" -> {
                                val cleared = whiteboards.clear(authorized, event.sessionExerciseId.required())
                                eventHub.publishWhiteboardClear(authorized.session.id, WhiteboardClearEvent(sessionId = authorized.session.id.toString(), sessionExerciseId = cleared.sessionExerciseId, clearRevision = cleared.clearRevision, boardRevision = cleared.boardRevision))
                            }
                            "WHITEBOARD_UNDO" -> whiteboards.undo(authorized, event.sessionExerciseId.required())?.let { undone ->
                                eventHub.publishWhiteboardUndo(authorized.session.id, WhiteboardUndoEvent(sessionId = authorized.session.id.toString(), sessionExerciseId = undone.sessionExerciseId, strokeId = undone.strokeId, boardRevision = undone.boardRevision))
                            }
                            "WHITEBOARD_CHILD_PERMISSION_CHANGED" -> {
                                val permission = whiteboards.setChildDrawingEnabled(authorized, event.sessionExerciseId.required(), checkNotNull(event.childDrawingEnabled))
                                eventHub.publishWhiteboardPermission(authorized.session.id, WhiteboardChildPermissionChangedEvent(sessionId = authorized.session.id.toString(), sessionExerciseId = permission.sessionExerciseId, childDrawingEnabled = permission.childDrawingEnabled, boardRevision = permission.boardRevision))
                            }
                        }
                        }
                    }
                }
            }
        } finally {
            eventHub.unregister(authorized.session.id, this)
            service.markSocketPresence(authorized, connected = false)
        }
    }
}

private fun String?.required(): String = this?.takeIf { it.isNotBlank() } ?: throw kz.oyla.server.service.ApiException.validation("Не хватает параметра whiteboard event")
private const val MaxWhiteboardEventBytes = 32 * 1024
