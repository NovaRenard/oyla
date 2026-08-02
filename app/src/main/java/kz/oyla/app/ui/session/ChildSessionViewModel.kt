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
import kz.oyla.app.data.remote.dto.SessionWebSocketEvent
import kz.oyla.app.data.session.ExerciseActionResult
import kz.oyla.app.data.session.SessionActionResult
import kz.oyla.app.data.session.SessionDetails
import kz.oyla.app.data.session.SessionRepository
import kz.oyla.app.domain.model.DeviceRole

data class ChildSessionUiState(
    val session: SessionDetails? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val socketState: SocketConnectionState = SocketConnectionState.DISCONNECTED,
    val connectionSuccessId: String? = null,
    val sessionEndedId: String? = null,
    val exercise: ExerciseUiState = ExerciseUiState()
)

class ChildSessionViewModel(
    private val repository: SessionRepository,
    private val webSocketClient: OylaWebSocketClient
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChildSessionUiState())
    val uiState = _uiState.asStateFlow()
    private var socketJob: Job? = null
    private var timerJob: Job? = null
    private val answerSubmissionGuard = AnswerSubmissionGuard()

    fun connect(connectionCode: String) {
        if (_uiState.value.isLoading || !connectionCode.matches(Regex("\\d{4}"))) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, connectionSuccessId = null)
        viewModelScope.launch {
            when (val result = repository.connectSession(connectionCode)) {
                is SessionActionResult.Success -> {
                    val session = result.value
                    _uiState.value = ChildSessionUiState(
                        session = session, connectionSuccessId = session.sessionId,
                        exercise = ExerciseUiState(sessionId = session.sessionId, childName = session.childName)
                    )
                    observeSocket(session)
                }
                is SessionActionResult.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
            }
        }
    }

    fun restoreActiveSession() {
        if (_uiState.value.isLoading || _uiState.value.session != null) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = repository.restoreActiveSession(DeviceRole.CHILD)) {
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

    fun consumeConnectionSuccess() { _uiState.value = _uiState.value.copy(connectionSuccessId = null) }
    fun consumeSessionEnd() { _uiState.value = _uiState.value.copy(sessionEndedId = null) }

    fun cancelSession(onCancelled: () -> Unit) {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = repository.cancelActiveSession()) {
                is SessionActionResult.Success -> {
                    socketJob?.cancel(); timerJob?.cancel(); _uiState.value = ChildSessionUiState(); onCancelled()
                }
                is SessionActionResult.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
            }
        }
    }

    fun submitAnswer(optionId: String) {
        val state = _uiState.value
        val session = state.session ?: return
        val exercise = state.exercise
        val id = exercise.sessionExerciseId ?: return
        if (exercise.exerciseStatus != ExerciseUiStatus.RUNNING || exercise.isAnswerPending || !answerSubmissionGuard.tryAcquire()) return
        setExercise { it.copy(isAnswerPending = true, pendingOptionId = optionId, feedbackMessage = null, errorMessage = null) }
        viewModelScope.launch {
            when (val result = repository.submitAnswer(session, id, optionId, UUID.randomUUID().toString())) {
                is ExerciseActionResult.Success -> {
                    val answer = result.value.toUi()
                    setExercise {
                        val completed = result.value.exerciseStatus == "COMPLETED"
                        it.copy(
                            latestAnswer = answer, attemptCount = answer.attemptNumber,
                            exerciseStatus = result.value.exerciseStatus.toExerciseUiStatus(),
                            isAnswerPending = !answer.isCorrect, pendingOptionId = if (answer.isCorrect) null else optionId,
                            feedbackMessage = when {
                                !answer.isCorrect -> "Попробуй ещё раз"
                                completed && it.currentPosition == it.totalExercises -> "Все задания выполнены!\nОтличная работа!"
                                completed -> "Отлично! Жди следующее задание"
                                else -> null
                            }
                        )
                    }
                    if (answer.isCorrect) timerJob?.cancel() else {
                        delay(850); answerSubmissionGuard.release()
                        setExercise { it.copy(isAnswerPending = false, pendingOptionId = null) }
                    }
                }
                is ExerciseActionResult.Failure -> setExercise {
                    answerSubmissionGuard.release(); it.copy(isAnswerPending = false, pendingOptionId = null, errorMessage = result.message)
                }
            }
        }
    }

    fun repeatInstruction() { setExercise { it.copy(playInstructionRequest = it.playInstructionRequest + 1) } }

    private fun loadCurrentExercise() {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            when (val result = repository.getExerciseState(session)) {
                is ExerciseActionResult.Success -> applyStateSnapshot(result.value)
                is ExerciseActionResult.Failure -> setExercise { it.copy(errorMessage = result.message) }
            }
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
            "EXERCISE_CHANGED" -> {
                answerSubmissionGuard.release()
                applyExerciseEvent(event, reset = true)
            }
            "EXERCISE_SHOWN" -> applyExerciseEvent(event)
            "EXERCISE_STARTED" -> applyExerciseEvent(event, requestAudio = true)
            "ANSWER_RECEIVED", "EXERCISE_COMPLETED" -> applyExerciseEvent(event)
            "EXERCISE_PLAN_COMPLETED" -> setExercise { it.copy(
                planCompleted = true, feedbackMessage = "Все задания выполнены!\nОтличная работа!", isAnswerPending = false, pendingOptionId = null
            ) }
            "SESSION_CANCELLED", "SESSION_COMPLETED" -> {
                socketJob?.cancel(); timerJob?.cancel()
                _uiState.value = _uiState.value.copy(errorMessage = "Занятие завершено", sessionEndedId = event.sessionId)
                viewModelScope.launch { repository.clearActiveSession() }
            }
        }
    }

    private fun applyStateSnapshot(state: kz.oyla.app.data.remote.dto.ExerciseStateResponse) {
        val old = _uiState.value.exercise
        setExercise { state.toUiState(old.sessionId, old.childName, old.connectionState) }
        state.startedAt?.takeIf { state.exerciseStatus == "RUNNING" }?.let(::startElapsedTicker)
        if (state.exerciseStatus == "COMPLETED") timerJob?.cancel()
    }

    private fun applyExerciseEvent(event: SessionWebSocketEvent, requestAudio: Boolean = false, reset: Boolean = false) {
        val old = _uiState.value.exercise
        val base = if (reset) old.resetForExerciseChange(
            newSessionExerciseId = event.sessionExerciseId,
            newExercise = event.exercise?.toUi(),
            newStatus = (event.exerciseStatus ?: "PENDING").toExerciseUiStatus(),
            newPosition = event.currentPosition ?: old.currentPosition,
            newTotal = event.totalExercises ?: old.totalExercises,
            newHasNext = event.hasNext ?: ((event.currentPosition ?: old.currentPosition) < (event.totalExercises ?: old.totalExercises)),
            newPlanCompleted = event.planCompleted ?: false
        ) else old
        val answer = event.latestAnswer?.toUi() ?: if (event.selectedOptionId != null && event.isCorrect != null) {
            AnswerUiModel(event.selectedOptionId, event.selectedOptionLabel.orEmpty(), event.isCorrect,
                event.attemptNumber ?: base.attemptCount + 1, event.responseTimeMs ?: 0)
        } else base.latestAnswer
        val completedFeedback = if (event.type == "EXERCISE_COMPLETED") {
            if ((event.currentPosition ?: base.currentPosition) == (event.totalExercises ?: base.totalExercises)) {
                "Все задания выполнены!\nОтличная работа!"
            } else "Отлично! Жди следующее задание"
        } else base.feedbackMessage
        setExercise {
            base.copy(
                sessionExerciseId = event.sessionExerciseId ?: base.sessionExerciseId,
                exercise = event.exercise?.toUi() ?: base.exercise,
                exerciseStatus = (event.exerciseStatus ?: base.exerciseStatus.name).toExerciseUiStatus(),
                latestAnswer = answer, attemptCount = event.attemptCount ?: event.attemptNumber ?: base.attemptCount,
                startedAt = event.startedAt ?: base.startedAt,
                currentPosition = event.currentPosition ?: base.currentPosition,
                totalExercises = event.totalExercises ?: base.totalExercises,
                hasNext = event.hasNext ?: base.hasNext,
                planCompleted = event.planCompleted ?: base.planCompleted,
                feedbackMessage = completedFeedback,
                playInstructionRequest = base.playInstructionRequest + if (requestAudio) 1 else 0
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
    override fun onCleared() { socketJob?.cancel(); timerJob?.cancel(); super.onCleared() }
}

class ChildSessionViewModelFactory(
    private val repository: SessionRepository, private val webSocketClient: OylaWebSocketClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ChildSessionViewModel(repository, webSocketClient) as T
}
