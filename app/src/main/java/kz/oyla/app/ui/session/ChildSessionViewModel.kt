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
                }
                is SessionActionResult.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
                null -> _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun consumeConnectionSuccess() { _uiState.value = _uiState.value.copy(connectionSuccessId = null) }

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
                        it.copy(
                            latestAnswer = answer, attemptCount = answer.attemptNumber,
                            exerciseStatus = result.value.exerciseStatus.toExerciseUiStatus(),
                            isAnswerPending = !answer.isCorrect,
                            pendingOptionId = if (answer.isCorrect) null else optionId,
                            feedbackMessage = if (answer.isCorrect) "Отлично!" else "Попробуй ещё раз"
                        )
                    }
                    if (answer.isCorrect) timerJob?.cancel() else {
                        delay(850)
                        answerSubmissionGuard.release()
                        setExercise { it.copy(isAnswerPending = false, pendingOptionId = null) }
                    }
                }
                is ExerciseActionResult.Failure -> setExercise {
                    answerSubmissionGuard.release()
                    it.copy(isAnswerPending = false, pendingOptionId = null, errorMessage = result.message)
                }
            }
        }
    }

    fun repeatInstruction() { setExercise { it.copy(playInstructionRequest = it.playInstructionRequest + 1) } }

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

    private fun handleServerEvent(session: SessionDetails, event: kz.oyla.app.data.remote.dto.SessionWebSocketEvent) {
        when (event.type) {
            "STATE_SNAPSHOT", "CHILD_CONNECTED" -> {
                val current = _uiState.value.session ?: session
                _uiState.value = _uiState.value.copy(session = current.copy(
                    status = event.status ?: current.status, childConnected = event.childConnected ?: current.childConnected
                ))
                if (event.exercise != null || event.sessionExerciseId != null) applyExerciseEvent(event, false)
            }
            "EXERCISE_SHOWN" -> applyExerciseEvent(event, false)
            "EXERCISE_STARTED" -> applyExerciseEvent(event, true)
            "ANSWER_RECEIVED", "EXERCISE_COMPLETED" -> applyExerciseEvent(event, false)
            "SESSION_CANCELLED", "SESSION_COMPLETED" -> {
                _uiState.value = _uiState.value.copy(errorMessage = "Занятие завершено")
                viewModelScope.launch { repository.clearActiveSession() }
            }
        }
    }

    private fun applyExerciseEvent(event: kz.oyla.app.data.remote.dto.SessionWebSocketEvent, requestAudio: Boolean) {
        setExercise { old ->
            val answer = event.latestAnswer?.toUi() ?: if (event.selectedOptionId != null && event.isCorrect != null) {
                AnswerUiModel(event.selectedOptionId, event.selectedOptionLabel.orEmpty(), event.isCorrect,
                    event.attemptNumber ?: old.attemptCount + 1, event.responseTimeMs ?: 0)
            } else old.latestAnswer
            old.copy(
                sessionExerciseId = event.sessionExerciseId ?: old.sessionExerciseId,
                exercise = event.exercise?.toUi() ?: old.exercise,
                exerciseStatus = (event.exerciseStatus ?: old.exerciseStatus.name).toExerciseUiStatus(),
                latestAnswer = answer, attemptCount = event.attemptCount ?: event.attemptNumber ?: old.attemptCount,
                startedAt = event.startedAt ?: old.startedAt,
                feedbackMessage = if (event.type == "EXERCISE_COMPLETED") "Отлично!" else old.feedbackMessage,
                playInstructionRequest = old.playInstructionRequest + if (requestAudio) 1 else 0
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
