package kz.oyla.app.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kz.oyla.app.data.remote.OylaWebSocketClient
import kz.oyla.app.data.remote.SocketConnectionState
import kz.oyla.app.data.remote.SocketEvent
import kz.oyla.app.data.remote.dto.SessionSummaryResponse
import kz.oyla.app.data.remote.dto.SessionWebSocketEvent
import kz.oyla.app.data.remote.dto.WhiteboardPointDto
import kz.oyla.app.data.remote.dto.WhiteboardTool
import kz.oyla.app.data.remote.dto.WhiteboardColor
import kz.oyla.app.data.remote.dto.WhiteboardBrushSize
import kz.oyla.app.data.remote.dto.WhiteboardClientEvent
import kz.oyla.app.data.session.ExerciseActionResult
import kz.oyla.app.data.session.SessionActionResult
import kz.oyla.app.data.session.SessionDetails
import kz.oyla.app.data.session.SessionRepository
import kz.oyla.app.data.remote.dto.DeviceLessonResponse
import kz.oyla.app.domain.model.DeviceRole

data class SpecialistSessionUiState(
    val session: SessionDetails? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val socketState: SocketConnectionState = SocketConnectionState.DISCONNECTED,
    val creationSuccessId: String? = null,
    val sessionEndedId: String? = null,
    val summary: SessionSummaryResponse? = null,
    val exercise: ExerciseUiState = ExerciseUiState()
)

class SpecialistSessionViewModel(
    private val repository: SessionRepository,
    private val webSocketClient: OylaWebSocketClient,
    val mediaToken: String? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(SpecialistSessionUiState())
    val uiState = _uiState.asStateFlow()
    private var socketJob: Job? = null
    private var timerJob: Job? = null
    private val nextExerciseGuard = NextExerciseGuard()
    private data class StrokeBuffer(val points: MutableList<WhiteboardPointDto> = mutableListOf(), var flushJob: Job? = null)
    private val strokeBuffers = mutableMapOf<String, StrokeBuffer>()
    private val localStrokeIds = mutableSetOf<String>()

    fun createSession(childName: String) {
        if (_uiState.value.isLoading || childName.trim().isEmpty()) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, creationSuccessId = null)
        viewModelScope.launch {
            when (val result = repository.createSession(childName)) {
                is SessionActionResult.Success -> {
                    val session = result.value.copy(childName = childName.trim())
                    _uiState.value = SpecialistSessionUiState(
                        session = session, creationSuccessId = session.sessionId,
                        exercise = ExerciseUiState(sessionId = session.sessionId, childName = session.childName)
                    )
                    observeSocket(session)
                }
                is SessionActionResult.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
            }
        }
    }

    fun startManagedLesson(lesson: DeviceLessonResponse) {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val session = repository.adoptManagedSpecialistLesson(lesson)
            _uiState.value = SpecialistSessionUiState(
                session = session,
                creationSuccessId = session.sessionId,
                exercise = ExerciseUiState(sessionId = session.sessionId, childName = session.childName)
            )
            observeSocket(session)
            loadCurrentExercise()
        }
    }

    fun restoreActiveSession() {
        if (_uiState.value.isLoading || _uiState.value.session != null) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = repository.restoreActiveSession(DeviceRole.SPECIALIST)) {
                is SessionActionResult.Success -> {
                    val session = result.value
                    _uiState.value = _uiState.value.copy(
                        session = session, isLoading = false,
                        exercise = _uiState.value.exercise.copy(sessionId = session.sessionId, childName = session.childName)
                    )
                    observeSocket(session)
                    loadCurrentExercise()
                }
                is SessionActionResult.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
                null -> _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun consumeCreationSuccess() { _uiState.value = _uiState.value.copy(creationSuccessId = null) }
    fun consumeSessionEnd() { _uiState.value = _uiState.value.copy(sessionEndedId = null) }

    /** Kept under the old name for the current screen; it now loads any current plan position. */
    fun loadFirstExercise() = loadCurrentExercise()

    fun loadCurrentExercise() {
        val session = _uiState.value.session ?: return
        if (_uiState.value.exercise.isCommandLoading) return
        setExercise { it.copy(isCommandLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = repository.getExerciseState(session)) {
                is ExerciseActionResult.Success -> applyStateSnapshot(result.value)
                is ExerciseActionResult.Failure -> setExercise { it.copy(errorMessage = result.message) }
            }
            setExercise { it.copy(isCommandLoading = false) }
        }
    }

    fun showExercise() = command("Не удалось показать задание") { session, state ->
        val exercise = state.exercise.exercise ?: return@command null
        repository.showExercise(session, exercise.id).also { result ->
            if (result is ExerciseActionResult.Success) setExercise {
                it.copy(sessionExerciseId = result.value.sessionExerciseId, exerciseStatus = result.value.status.toExerciseUiStatus())
            }
        }
    }

    fun startExercise() = command("Не удалось начать задание") { session, state ->
        val id = state.exercise.sessionExerciseId ?: return@command null
        repository.startExercise(session, id).also { result ->
            if (result is ExerciseActionResult.Success) setExercise {
                it.copy(exerciseStatus = result.value.status.toExerciseUiStatus(), startedAt = result.value.startedAt)
            }.also { startElapsedTicker(result.value.startedAt) }
        }
    }

    fun nextExercise() {
        val state = _uiState.value
        val session = state.session ?: return
        val id = state.exercise.sessionExerciseId ?: return
        if (!state.exercise.canMoveToNext() || !nextExerciseGuard.tryAcquire()) return
        setExercise { it.copy(isCommandLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = repository.nextExercise(session, id)) {
                is ExerciseActionResult.Success -> applyStateSnapshot(result.value)
                is ExerciseActionResult.Failure -> setExercise { it.copy(errorMessage = result.message.ifBlank { "Не удалось открыть следующее задание" }) }
            }
            nextExerciseGuard.release()
            setExercise { it.copy(isCommandLoading = false) }
        }
    }

    fun completeWhiteboardExercise() = command("Не удалось завершить доску") { session, state ->
        val id = state.exercise.sessionExerciseId ?: return@command null
        repository.completeWhiteboardExercise(session, id).also { if (it is ExerciseActionResult.Success) applyStateSnapshot(it.value) }
    }

    fun selectWhiteboardTool(tool: WhiteboardTool) = setExercise { state ->
        val config = state.exercise?.whiteboardConfig ?: return@setExercise state
        if (tool == WhiteboardTool.ERASER && !config.allowEraser) state else state.copy(whiteboard = (state.whiteboard ?: defaultWhiteboard(config)).copy(selectedTool = tool))
    }
    fun selectWhiteboardColor(color: WhiteboardColor) = setExercise { state ->
        val config = state.exercise?.whiteboardConfig ?: return@setExercise state
        if (color !in config.availableColors) state else state.copy(whiteboard = (state.whiteboard ?: defaultWhiteboard(config)).copy(selectedTool = WhiteboardTool.PEN, selectedColor = color))
    }
    fun selectWhiteboardBrush(size: WhiteboardBrushSize) = setExercise { state ->
        val config = state.exercise?.whiteboardConfig ?: return@setExercise state
        state.copy(whiteboard = (state.whiteboard ?: defaultWhiteboard(config)).copy(selectedBrushSize = size))
    }
    fun beginWhiteboardStroke(point: WhiteboardPointDto): String? {
        val state = _uiState.value.exercise; val session = _uiState.value.session ?: return null; val config = state.exercise?.whiteboardConfig ?: return null
        val board = state.whiteboard ?: defaultWhiteboard(config); val id = UUID.randomUUID().toString()
        if (state.exerciseStatus != ExerciseUiStatus.RUNNING || state.connectionState != SocketConnectionState.CONNECTED) return null
        val stroke = WhiteboardStrokeUi(id, "SPECIALIST", board.selectedTool, board.selectedTool.takeIf { it == WhiteboardTool.PEN }?.let { board.selectedColor }, board.selectedBrushSize, emptyList())
        if (!webSocketClient.sendWhiteboardEvent(session.sessionId, WhiteboardClientEvent("WHITEBOARD_STROKE_STARTED", state.sessionExerciseId, id, stroke.tool, stroke.color, stroke.brushSize))) return null
        localStrokeIds += id; strokeBuffers[id] = StrokeBuffer()
        setExercise { it.copy(whiteboard = board.copy(inProgress = board.inProgress + (id to stroke))) }
        appendWhiteboardPoint(id, point)
        return id
    }
    fun appendWhiteboardPoint(strokeId: String, point: WhiteboardPointDto) {
        setExercise { state -> val board = state.whiteboard ?: return@setExercise state; val stroke = board.inProgress[strokeId] ?: return@setExercise state
            state.copy(whiteboard = board.copy(inProgress = board.inProgress + (strokeId to stroke.copy(points = stroke.points + point)))) }
        val buffer = strokeBuffers[strokeId] ?: return; buffer.points += point
        if (buffer.points.size >= 12) flushWhiteboardPoints(strokeId) else if (buffer.flushJob == null) buffer.flushJob = viewModelScope.launch { delay(32); flushWhiteboardPoints(strokeId) }
    }
    fun endWhiteboardStroke(strokeId: String) {
        flushWhiteboardPoints(strokeId); val session = _uiState.value.session ?: return; val state = _uiState.value.exercise
        if (webSocketClient.sendWhiteboardEvent(session.sessionId, WhiteboardClientEvent("WHITEBOARD_STROKE_COMPLETED", state.sessionExerciseId, strokeId, clientEventId = UUID.randomUUID().toString()))) strokeBuffers.remove(strokeId)?.flushJob?.cancel()
    }
    fun whiteboardUndo() = sendWhiteboardSimple("WHITEBOARD_UNDO")
    fun whiteboardClear() = sendWhiteboardSimple("WHITEBOARD_CLEAR")
    fun setChildDrawingEnabled(enabled: Boolean) = sendWhiteboardSimple("WHITEBOARD_CHILD_PERMISSION_CHANGED", enabled)

    fun loadSummary() {
        val session = _uiState.value.session ?: return
        if (_uiState.value.isLoading || _uiState.value.summary != null) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = repository.getSummary(session)) {
                is ExerciseActionResult.Success -> _uiState.value = _uiState.value.copy(summary = result.value, isLoading = false)
                is ExerciseActionResult.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    fun completeSession(onCompleted: () -> Unit) {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = repository.completeActiveSession()) {
                is SessionActionResult.Success -> {
                    socketJob?.cancel(); timerJob?.cancel(); _uiState.value = SpecialistSessionUiState(); onCompleted()
                }
                is SessionActionResult.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
            }
        }
    }

    fun cancelSession(onCancelled: () -> Unit) {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = repository.cancelActiveSession()) {
                is SessionActionResult.Success -> {
                    socketJob?.cancel(); timerJob?.cancel(); _uiState.value = SpecialistSessionUiState(); onCancelled()
                }
                is SessionActionResult.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
            }
        }
    }

    private fun command(fallback: String, block: suspend (SessionDetails, SpecialistSessionUiState) -> ExerciseActionResult<*>?) {
        val state = _uiState.value
        val session = state.session ?: return
        if (state.exercise.isCommandLoading) return
        setExercise { it.copy(isCommandLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = block(session, _uiState.value)
            if (result is ExerciseActionResult.Failure) setExercise { it.copy(errorMessage = result.message.ifBlank { fallback }) }
            setExercise { it.copy(isCommandLoading = false) }
        }
    }

    private fun observeSocket(session: SessionDetails) {
        socketJob?.cancel()
        socketJob = viewModelScope.launch {
            webSocketClient.observeSession(session.sessionId, session.token).collect { event ->
                when (event) {
                    is SocketEvent.ConnectionState -> {
                        _uiState.value = _uiState.value.copy(socketState = event.state)
                        setExercise { it.copy(connectionState = event.state) }
                    }
                    is SocketEvent.ServerEvent -> handleServerEvent(session, event.event)
                }
            }
        }
    }

    private fun handleServerEvent(session: SessionDetails, event: SessionWebSocketEvent) {
        when (event.type) {
            "STATE_SNAPSHOT", "CHILD_CONNECTED" -> {
                val current = _uiState.value.session ?: session
                _uiState.value = _uiState.value.copy(session = current.copy(
                    status = event.status ?: current.status, childConnected = event.childConnected ?: current.childConnected
                ))
                if (event.type == "STATE_SNAPSHOT" && event.sessionExerciseId != null) applyExerciseEvent(event, reset = true)
            }
            "EXERCISE_CHANGED" -> applyExerciseEvent(event, reset = true)
            "EXERCISE_SHOWN", "EXERCISE_STARTED", "ANSWER_RECEIVED", "EXERCISE_COMPLETED" -> applyExerciseEvent(event)
            "WHITEBOARD_STROKE_STARTED", "WHITEBOARD_STROKE_POINTS", "WHITEBOARD_STROKE_COMPLETED", "WHITEBOARD_CLEARED", "WHITEBOARD_UNDONE", "WHITEBOARD_CHILD_PERMISSION_CHANGED" -> applyWhiteboardEvent(event)
            "EXERCISE_PLAN_COMPLETED" -> setExercise { it.copy(planCompleted = true) }
            "SESSION_CANCELLED", "SESSION_COMPLETED" -> {
                socketJob?.cancel(); timerJob?.cancel()
                _uiState.value = _uiState.value.copy(errorMessage = "Занятие завершено", sessionEndedId = event.sessionId)
                viewModelScope.launch { repository.clearActiveSession() }
            }
        }
    }

    private fun applyStateSnapshot(state: kz.oyla.app.data.remote.dto.ExerciseStateResponse) {
        val old = _uiState.value.exercise
        val restored = state.toUiState(old.sessionId, old.childName, old.connectionState)
        setExercise { restored.copy(isCommandLoading = old.isCommandLoading) }
        state.startedAt?.takeIf { state.exerciseStatus == "RUNNING" }?.let(::startElapsedTicker)
        if (state.exerciseStatus == "COMPLETED") timerJob?.cancel()
    }

    private fun applyExerciseEvent(event: SessionWebSocketEvent, reset: Boolean = false) {
        val old = _uiState.value.exercise
        val base = if (reset) old.resetForExerciseChange(
            newSessionExerciseId = event.sessionExerciseId,
            newExercise = event.exercise?.toUi(event.correctOptionId),
            newStatus = (event.exerciseStatus ?: "PENDING").toExerciseUiStatus(),
            newPosition = event.currentPosition ?: old.currentPosition,
            newTotal = event.totalExercises ?: old.totalExercises,
            newHasNext = event.hasNext ?: ((event.currentPosition ?: old.currentPosition) < (event.totalExercises ?: old.totalExercises)),
            newPlanCompleted = event.planCompleted ?: false,
            newWhiteboard = event.whiteboardState?.toUi()
        ) else old
        val answer = event.latestAnswer?.toUi() ?: if (event.selectedOptionId != null && event.isCorrect != null) {
            AnswerUiModel(event.selectedOptionId, event.selectedOptionLabel.orEmpty(), event.isCorrect,
                event.attemptNumber ?: base.attemptCount + 1, event.responseTimeMs ?: 0)
        } else base.latestAnswer
        setExercise {
            base.copy(
                sessionExerciseId = event.sessionExerciseId ?: base.sessionExerciseId,
                exercise = event.exercise?.toUi(event.correctOptionId ?: base.exercise?.correctOptionId) ?: base.exercise,
                exerciseStatus = (event.exerciseStatus ?: base.exerciseStatus.name).toExerciseUiStatus(),
                latestAnswer = answer, attemptCount = event.attemptCount ?: event.attemptNumber ?: base.attemptCount,
                startedAt = event.startedAt ?: base.startedAt,
                currentPosition = event.currentPosition ?: base.currentPosition,
                totalExercises = event.totalExercises ?: base.totalExercises,
                hasNext = event.hasNext ?: base.hasNext,
                planCompleted = event.planCompleted ?: base.planCompleted,
                whiteboard = event.whiteboardState?.toUi() ?: base.whiteboard
            )
        }
        event.startedAt?.let(::startElapsedTicker)
        if (event.exerciseStatus == "COMPLETED") timerJob?.cancel()
    }

    private fun startElapsedTicker(startedAt: String) {
        timerJob?.cancel()
        val started = runCatching { Instant.parse(startedAt) }.getOrNull() ?: return
        timerJob = viewModelScope.launch {
            while (isActive && _uiState.value.exercise.exerciseStatus == ExerciseUiStatus.RUNNING) {
                setExercise { it.copy(elapsedMillis = (Instant.now().toEpochMilli() - started.toEpochMilli()).coerceAtLeast(0)) }
                delay(1_000)
            }
        }
    }

    private fun setExercise(update: (ExerciseUiState) -> ExerciseUiState) {
        _uiState.value = _uiState.value.copy(exercise = update(_uiState.value.exercise))
    }
    private fun flushWhiteboardPoints(strokeId: String) {
        val buffer = strokeBuffers[strokeId] ?: return; buffer.flushJob?.cancel(); buffer.flushJob = null
        val points = buffer.points.toList(); buffer.points.clear(); if (points.isEmpty()) return
        val session = _uiState.value.session ?: return; val state = _uiState.value.exercise
        webSocketClient.sendWhiteboardEvent(session.sessionId, WhiteboardClientEvent("WHITEBOARD_STROKE_POINTS", state.sessionExerciseId, strokeId, points = points))
    }
    private fun sendWhiteboardSimple(type: String, enabled: Boolean? = null) {
        val session = _uiState.value.session ?: return; val state = _uiState.value.exercise
        webSocketClient.sendWhiteboardEvent(session.sessionId, WhiteboardClientEvent(type, state.sessionExerciseId, childDrawingEnabled = enabled))
    }
    private fun applyWhiteboardEvent(event: SessionWebSocketEvent) = setExercise { state ->
        val board = state.whiteboard ?: state.exercise?.whiteboardConfig?.let(::defaultWhiteboard) ?: return@setExercise state
        when (event.type) {
            "WHITEBOARD_STROKE_STARTED" -> {
                val id = event.strokeId ?: return@setExercise state
                if (id in board.inProgress) state else state.copy(whiteboard = board.copy(inProgress = board.inProgress + (id to WhiteboardStrokeUi(id, event.actorRole.orEmpty(), checkNotNull(event.tool), event.color, checkNotNull(event.brushSize), emptyList()))))
            }
            "WHITEBOARD_STROKE_POINTS" -> {
                val id = event.strokeId ?: return@setExercise state
                if (id in localStrokeIds) state else board.inProgress[id]?.let { stroke -> state.copy(whiteboard = board.copy(inProgress = board.inProgress + (id to stroke.copy(points = stroke.points + event.points)))) } ?: state
            }
            "WHITEBOARD_STROKE_COMPLETED" -> event.stroke?.toUi()?.let { stroke -> localStrokeIds.remove(stroke.id); state.copy(whiteboard = board.copy(strokes = (board.strokes.filterNot { it.id == stroke.id } + stroke).sortedBy { it.sequenceNumber }, inProgress = board.inProgress - stroke.id)) } ?: state
            "WHITEBOARD_CLEARED" -> state.copy(whiteboard = board.copy(strokes = emptyList(), inProgress = emptyMap(), clearRevision = event.clearRevision ?: board.clearRevision, boardRevision = event.boardRevision ?: board.boardRevision))
            "WHITEBOARD_UNDONE" -> state.copy(whiteboard = board.copy(strokes = board.strokes.filterNot { it.id == event.strokeId }, boardRevision = event.boardRevision ?: board.boardRevision))
            "WHITEBOARD_CHILD_PERMISSION_CHANGED" -> {
                val enabled = event.childDrawingEnabled ?: board.childDrawingEnabled
                state.copy(whiteboard = board.copy(
                    childDrawingEnabled = enabled,
                    inProgress = if (enabled) board.inProgress else board.inProgress.filterValues { it.actorRole != "CHILD" },
                    boardRevision = event.boardRevision ?: board.boardRevision
                ))
            }
            else -> state
        }
    }
    private fun defaultWhiteboard(config: kz.oyla.app.data.remote.dto.WhiteboardExerciseConfigDto) = WhiteboardUiState(selectedColor = config.defaultColor, selectedBrushSize = config.defaultBrushSize, childDrawingEnabled = config.childDrawingInitiallyEnabled)
    override fun onCleared() { socketJob?.cancel(); timerJob?.cancel(); super.onCleared() }
}

class SpecialistSessionViewModelFactory(
    private val repository: SessionRepository, private val webSocketClient: OylaWebSocketClient, private val mediaToken: String? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SpecialistSessionViewModel(repository, webSocketClient, mediaToken) as T
}
