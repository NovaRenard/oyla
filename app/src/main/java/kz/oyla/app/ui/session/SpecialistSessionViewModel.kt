package kz.oyla.app.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Instant
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

data class SpecialistSessionUiState(
    val session: SessionDetails? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val socketState: SocketConnectionState = SocketConnectionState.DISCONNECTED,
    val creationSuccessId: String? = null,
    val exercise: ExerciseUiState = ExerciseUiState()
)

class SpecialistSessionViewModel(
    private val repository: SessionRepository,
    private val webSocketClient: OylaWebSocketClient
) : ViewModel() {
    private val _uiState = MutableStateFlow(SpecialistSessionUiState())
    val uiState = _uiState.asStateFlow()
    private var socketJob: Job? = null
    private var timerJob: Job? = null

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
                }
                is SessionActionResult.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
                null -> _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun consumeCreationSuccess() {
        _uiState.value = _uiState.value.copy(creationSuccessId = null)
    }

    fun loadFirstExercise() {
        val session = _uiState.value.session ?: return
        if (_uiState.value.exercise.exercise != null || _uiState.value.exercise.isCommandLoading) return
        setExercise { it.copy(isCommandLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = repository.getSpecialistExercise(session)) {
                is ExerciseActionResult.Success -> setExercise {
                    it.copy(exercise = result.value.exercise.toUi(result.value.correctOptionId), isCommandLoading = false)
                }
                is ExerciseActionResult.Failure -> setExercise { it.copy(isCommandLoading = false, errorMessage = result.message) }
            }
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

    private fun handleServerEvent(session: SessionDetails, event: kz.oyla.app.data.remote.dto.SessionWebSocketEvent) {
        when (event.type) {
            "STATE_SNAPSHOT", "CHILD_CONNECTED" -> {
                val current = _uiState.value.session ?: session
                _uiState.value = _uiState.value.copy(session = current.copy(
                    status = event.status ?: current.status, childConnected = event.childConnected ?: current.childConnected
                ))
                if (event.exercise != null || event.sessionExerciseId != null) applyExerciseEvent(event)
            }
            "EXERCISE_SHOWN", "EXERCISE_STARTED", "ANSWER_RECEIVED", "EXERCISE_COMPLETED" -> applyExerciseEvent(event)
            "SESSION_CANCELLED", "SESSION_COMPLETED" -> _uiState.value = _uiState.value.copy(errorMessage = "Занятие завершено")
        }
    }

    private fun applyExerciseEvent(event: kz.oyla.app.data.remote.dto.SessionWebSocketEvent) {
        setExercise { old ->
            val answer = event.latestAnswer?.toUi() ?: if (event.selectedOptionId != null && event.isCorrect != null) {
                kz.oyla.app.ui.session.AnswerUiModel(
                    event.selectedOptionId, event.selectedOptionLabel.orEmpty(), event.isCorrect,
                    event.attemptNumber ?: old.attemptCount + 1, event.responseTimeMs ?: 0
                )
            } else old.latestAnswer
            old.copy(
                sessionExerciseId = event.sessionExerciseId ?: old.sessionExerciseId,
                exercise = event.exercise?.toUi(event.correctOptionId ?: old.exercise?.correctOptionId) ?: old.exercise,
                exerciseStatus = (event.exerciseStatus ?: old.exerciseStatus.name).toExerciseUiStatus(),
                latestAnswer = answer, attemptCount = event.attemptCount ?: event.attemptNumber ?: old.attemptCount,
                startedAt = event.startedAt ?: old.startedAt
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

class SpecialistSessionViewModelFactory(
    private val repository: SessionRepository, private val webSocketClient: OylaWebSocketClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SpecialistSessionViewModel(repository, webSocketClient) as T
}
