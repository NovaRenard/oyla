package kz.oyla.app.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kz.oyla.app.data.remote.OylaWebSocketClient
import kz.oyla.app.data.remote.SocketConnectionState
import kz.oyla.app.data.remote.SocketEvent
import kz.oyla.app.data.session.SessionActionResult
import kz.oyla.app.data.session.SessionDetails
import kz.oyla.app.data.session.SessionRepository
import kz.oyla.app.domain.model.DeviceRole

data class SpecialistSessionUiState(
    val session: SessionDetails? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val socketState: SocketConnectionState = SocketConnectionState.DISCONNECTED,
    val creationSuccessId: String? = null
)

class SpecialistSessionViewModel(
    private val repository: SessionRepository,
    private val webSocketClient: OylaWebSocketClient
) : ViewModel() {
    private val _uiState = MutableStateFlow(SpecialistSessionUiState())
    val uiState = _uiState.asStateFlow()
    private var socketJob: Job? = null

    fun createSession(childName: String) {
        if (_uiState.value.isLoading || childName.trim().isEmpty()) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, creationSuccessId = null)
        viewModelScope.launch {
            when (val result = repository.createSession(childName)) {
                is SessionActionResult.Success -> {
                    val session = result.value.copy(childName = childName.trim())
                    _uiState.value = SpecialistSessionUiState(
                        session = session,
                        creationSuccessId = session.sessionId
                    )
                    observeSocket(session)
                }
                is SessionActionResult.Failure -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
                }
            }
        }
    }

    fun restoreActiveSession() {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = repository.restoreActiveSession(DeviceRole.SPECIALIST)) {
                is SessionActionResult.Success -> {
                    _uiState.value = _uiState.value.copy(session = result.value, isLoading = false)
                    observeSocket(result.value)
                }
                is SessionActionResult.Failure -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
                }
                null -> _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun consumeCreationSuccess() {
        _uiState.value = _uiState.value.copy(creationSuccessId = null)
    }

    fun cancelSession(onCancelled: () -> Unit) {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = repository.cancelActiveSession()) {
                is SessionActionResult.Success -> {
                    socketJob?.cancel()
                    _uiState.value = SpecialistSessionUiState()
                    onCancelled()
                }
                is SessionActionResult.Failure -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.error.message)
                }
            }
        }
    }

    private fun observeSocket(session: SessionDetails) {
        socketJob?.cancel()
        socketJob = viewModelScope.launch {
            webSocketClient.observeSession(session.sessionId, session.token).collect { event ->
                when (event) {
                    is SocketEvent.ConnectionState -> _uiState.value = _uiState.value.copy(socketState = event.state)
                    is SocketEvent.ServerEvent -> when (event.event.type) {
                        "STATE_SNAPSHOT", "CHILD_CONNECTED" -> {
                            val current = _uiState.value.session ?: session
                            _uiState.value = _uiState.value.copy(
                                session = current.copy(
                                    status = event.event.status ?: current.status,
                                    childConnected = event.event.childConnected ?: current.childConnected
                                )
                            )
                        }
                        "SESSION_CANCELLED" -> {
                            _uiState.value = _uiState.value.copy(errorMessage = "Занятие отменено")
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        socketJob?.cancel()
        super.onCleared()
    }
}

class SpecialistSessionViewModelFactory(
    private val repository: SessionRepository,
    private val webSocketClient: OylaWebSocketClient
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SpecialistSessionViewModel(repository, webSocketClient) as T
}
