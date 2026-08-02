package kz.oyla.server.service

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kz.oyla.server.model.DeviceRole
import kz.oyla.server.model.dto.ChildConnectedEvent
import kz.oyla.server.model.dto.ExerciseCompletedEvent
import kz.oyla.server.model.dto.ExerciseShownEvent
import kz.oyla.server.model.dto.ExerciseStartedEvent
import kz.oyla.server.model.dto.AnswerExerciseResponse
import kz.oyla.server.model.dto.AnswerReceivedEvent
import kz.oyla.server.model.dto.ExerciseStateResponse
import kz.oyla.server.model.dto.ExerciseChangedEvent
import kz.oyla.server.model.dto.ExercisePlanCompletedEvent
import kz.oyla.server.model.dto.SessionCancelledEvent
import kz.oyla.server.model.dto.SessionCompletedEvent

class SessionEventHub(private val json: Json) {
    private data class Connection(
        val role: DeviceRole,
        val socket: DefaultWebSocketServerSession
    )

    private val connections = ConcurrentHashMap<UUID, MutableSet<Connection>>()

    fun register(sessionId: UUID, role: DeviceRole, socket: DefaultWebSocketServerSession) {
        val connection = Connection(role, socket)
        connections.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }.add(connection)
    }

    fun unregister(sessionId: UUID, socket: DefaultWebSocketServerSession) {
        connections[sessionId]?.let { set ->
            set.removeIf { it.socket === socket }
            if (set.isEmpty()) connections.remove(sessionId, set)
        }
    }

    suspend fun publishChildConnected(sessionId: UUID) {
        publishToRole(
            sessionId,
            DeviceRole.SPECIALIST,
            json.encodeToString(ChildConnectedEvent(sessionId = sessionId.toString(), status = kz.oyla.server.model.SessionStatus.READY))
        )
    }

    suspend fun publishSessionCancelled(sessionId: UUID) {
        publishToAll(sessionId, json.encodeToString(SessionCancelledEvent(sessionId = sessionId.toString())))
    }

    suspend fun publishSessionCompleted(sessionId: UUID) {
        publishToAll(sessionId, json.encodeToString(SessionCompletedEvent(sessionId = sessionId.toString())))
    }

    suspend fun publishExerciseShown(sessionId: UUID, state: ExerciseStateResponse) {
        val exercise = state.exercise ?: return
        val event = ExerciseShownEvent(
            sessionId = sessionId.toString(), sessionExerciseId = checkNotNull(state.sessionExerciseId),
            exercise = exercise, exerciseStatus = state.exerciseStatus,
            currentPosition = state.currentPosition, totalExercises = state.totalExercises
        )
        publishToRole(sessionId, DeviceRole.CHILD, json.encodeToString(event))
        publishToRole(sessionId, DeviceRole.SPECIALIST, json.encodeToString(event.copy(correctOptionId = state.correctOptionId)))
    }

    suspend fun publishExerciseStarted(sessionId: UUID, state: ExerciseStateResponse) {
        val event = ExerciseStartedEvent(
            sessionId = sessionId.toString(), sessionExerciseId = checkNotNull(state.sessionExerciseId),
            exerciseStatus = state.exerciseStatus, startedAt = checkNotNull(state.startedAt),
            currentPosition = state.currentPosition, totalExercises = state.totalExercises
        )
        publishToAll(sessionId, json.encodeToString(event))
    }

    suspend fun publishAnswer(sessionId: UUID, answer: AnswerExerciseResponse) {
        val received = AnswerReceivedEvent(
            sessionId = sessionId.toString(), sessionExerciseId = answer.sessionExerciseId,
            selectedOptionId = answer.selectedOptionId, selectedOptionLabel = answer.selectedOptionLabel,
            isCorrect = answer.isCorrect, attemptNumber = answer.attemptNumber,
            responseTimeMs = answer.responseTimeMs, exerciseStatus = answer.exerciseStatus
        )
        publishToAll(sessionId, json.encodeToString(received))
        if (answer.exerciseStatus == kz.oyla.server.model.ExerciseStatus.COMPLETED) {
            publishToAll(sessionId, json.encodeToString(ExerciseCompletedEvent(
                sessionId = sessionId.toString(), sessionExerciseId = answer.sessionExerciseId,
                selectedOptionId = answer.selectedOptionId, attemptNumber = answer.attemptNumber,
                responseTimeMs = answer.responseTimeMs, exerciseStatus = answer.exerciseStatus
            )))
        }
    }

    suspend fun publishExerciseChanged(sessionId: UUID, specialistState: ExerciseStateResponse) {
        val event = ExerciseChangedEvent(
            sessionId = sessionId.toString(), sessionExerciseId = checkNotNull(specialistState.sessionExerciseId),
            exerciseStatus = specialistState.exerciseStatus, exercise = specialistState.exercise,
            correctOptionId = specialistState.correctOptionId, currentPosition = specialistState.currentPosition,
            totalExercises = specialistState.totalExercises, attemptCount = specialistState.attemptCount,
            latestAnswer = specialistState.latestAnswer, startedAt = specialistState.startedAt,
            planCompleted = specialistState.planCompleted
        )
        publishToRole(sessionId, DeviceRole.SPECIALIST, json.encodeToString(event))
        publishToRole(sessionId, DeviceRole.CHILD, json.encodeToString(event.copy(exercise = null, correctOptionId = null)))
    }

    suspend fun publishExercisePlanCompleted(sessionId: UUID, state: ExerciseStateResponse) {
        publishToAll(sessionId, json.encodeToString(ExercisePlanCompletedEvent(
            sessionId = sessionId.toString(), currentPosition = state.currentPosition,
            totalExercises = state.totalExercises, planCompleted = true
        )))
    }

    private suspend fun publishToRole(sessionId: UUID, role: DeviceRole, payload: String) {
        connections[sessionId]
            ?.filter { it.role == role }
            ?.forEach { connection -> runCatching { connection.socket.send(Frame.Text(payload)) } }
    }

    private suspend fun publishToAll(sessionId: UUID, payload: String) {
        connections[sessionId]?.forEach { connection ->
            runCatching { connection.socket.send(Frame.Text(payload)) }
        }
    }
}
