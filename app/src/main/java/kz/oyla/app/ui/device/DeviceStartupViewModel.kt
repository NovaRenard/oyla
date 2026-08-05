package kz.oyla.app.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kz.oyla.app.data.device.DeviceActivationResult
import kz.oyla.app.data.device.DeviceLifecycle
import kz.oyla.app.data.device.DeviceRuntimeInfo
import kz.oyla.app.data.device.DeviceStartupResult
import kz.oyla.app.data.device.HeartbeatController
import kz.oyla.app.data.local.DeviceIdentity
import kz.oyla.app.data.local.DeviceIdentityStorage
import kz.oyla.app.data.remote.DeviceAuthGateway

sealed interface DeviceStartupUiState {
    data object Loading : DeviceStartupUiState
    data class Activation(val error: String? = null, val isSubmitting: Boolean = false) : DeviceStartupUiState
    data class Ready(val identity: DeviceIdentity) : DeviceStartupUiState
    data class Blocked(val identity: DeviceIdentity) : DeviceStartupUiState
    data class Offline(val identity: DeviceIdentity) : DeviceStartupUiState
}

class DeviceStartupViewModel(
    storage: DeviceIdentityStorage,
    gateway: DeviceAuthGateway,
    runtimeInfo: DeviceRuntimeInfo
) : ViewModel() {
    private val lifecycle = DeviceLifecycle(storage, gateway, runtimeInfo)
    private val heartbeat = HeartbeatController(viewModelScope, storage, gateway, runtimeInfo, ::showResult)
    private val _uiState = MutableStateFlow<DeviceStartupUiState>(DeviceStartupUiState.Loading)
    val uiState: StateFlow<DeviceStartupUiState> = _uiState.asStateFlow()

    init { validate() }

    fun validate() = viewModelScope.launch {
        heartbeat.stop()
        _uiState.value = DeviceStartupUiState.Loading
        showResult(lifecycle.validateStartup())
    }

    fun activate(code: String) = viewModelScope.launch {
        _uiState.value = DeviceStartupUiState.Activation(isSubmitting = true)
        when (val result = lifecycle.activate(code)) {
            is DeviceActivationResult.Success -> showResult(DeviceStartupResult.Ready(result.identity))
            is DeviceActivationResult.Failure -> _uiState.value = DeviceStartupUiState.Activation(error = result.message)
        }
    }

    private suspend fun showResult(result: DeviceStartupResult) {
        _uiState.value = when (result) {
            DeviceStartupResult.ActivationRequired -> DeviceStartupUiState.Activation()
            is DeviceStartupResult.Ready -> { heartbeat.start(result.identity); DeviceStartupUiState.Ready(result.identity) }
            is DeviceStartupResult.Blocked -> DeviceStartupUiState.Blocked(result.identity)
            is DeviceStartupResult.Offline -> DeviceStartupUiState.Offline(result.identity)
        }
    }

    override fun onCleared() { heartbeat.stop(); super.onCleared() }
}

class DeviceStartupViewModelFactory(
    private val storage: DeviceIdentityStorage,
    private val gateway: DeviceAuthGateway,
    private val runtimeInfo: DeviceRuntimeInfo
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DeviceStartupViewModel(storage, gateway, runtimeInfo) as T
}
