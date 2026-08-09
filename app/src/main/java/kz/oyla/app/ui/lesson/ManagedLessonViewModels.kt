package kz.oyla.app.ui.lesson

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kz.oyla.app.data.remote.DeviceAuthGateway
import kz.oyla.app.data.remote.NetworkResult
import kz.oyla.app.data.remote.dto.AvailableChildDevice
import kz.oyla.app.data.remote.dto.ChildLessonAssignmentResponse
import kz.oyla.app.data.remote.dto.CreateDeviceLessonRequest
import kz.oyla.app.data.remote.dto.DeviceCatalogChild
import kz.oyla.app.data.remote.dto.DeviceCatalogSpecialist
import kz.oyla.app.data.remote.dto.DeviceLessonResponse
import kz.oyla.app.data.session.SessionRepository
import kz.oyla.app.data.local.LastSpecialistStorage

data class ManagedLessonLaunchState(
    val children: List<DeviceCatalogChild> = emptyList(),
    val specialists: List<DeviceCatalogSpecialist> = emptyList(),
    val childDevices: List<AvailableChildDevice> = emptyList(),
    val specialistId: String? = null,
    val childId: String? = null,
    val childDeviceId: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val createdLesson: DeviceLessonResponse? = null,
    val recoveredLesson: DeviceLessonResponse? = null
) {
    val specialist get() = specialists.firstOrNull { it.id == specialistId }
    val child get() = children.firstOrNull { it.id == childId }
    val childDevice get() = childDevices.firstOrNull { it.id == childDeviceId }
}

class ManagedLessonLaunchViewModel(
    private val gateway: DeviceAuthGateway,
    private val deviceToken: String,
    private val sessions: SessionRepository,
    private val lastSpecialists: LastSpecialistStorage
) : ViewModel() {
    private val _uiState = MutableStateFlow(ManagedLessonLaunchState())
    val uiState = _uiState.asStateFlow()

    fun loadCatalog() {
        if (_uiState.value.isLoading || (_uiState.value.children.isNotEmpty() && _uiState.value.specialists.isNotEmpty())) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val children = gateway.children(deviceToken)
            val specialists = gateway.specialists(deviceToken)
            if (children is NetworkResult.Success && specialists is NetworkResult.Success) {
                val lastSpecialist = lastSpecialists.getLastSpecialistId()
                _uiState.value = _uiState.value.copy(
                    children = children.data,
                    specialists = specialists.data,
                    specialistId = _uiState.value.specialistId ?: lastSpecialist?.takeIf { id -> specialists.data.any { it.id == id } },
                    isLoading = false
                )
            } else _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = errorMessage(children, specialists))
        }
    }

    fun selectSpecialist(id: String) {
        _uiState.value = _uiState.value.copy(specialistId = id, errorMessage = null)
        viewModelScope.launch { lastSpecialists.saveLastSpecialistId(id) }
    }
    fun selectChild(id: String) { _uiState.value = _uiState.value.copy(childId = id, childDeviceId = null, errorMessage = null) }
    fun selectChildDevice(id: String) { _uiState.value = _uiState.value.copy(childDeviceId = id, errorMessage = null) }

    fun loadAvailableChildDevices() {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = gateway.availableChildDevices(deviceToken)) {
                is NetworkResult.Success -> _uiState.value = _uiState.value.copy(childDevices = result.data, isLoading = false)
                else -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = errorMessage(result))
            }
        }
    }

    fun createLesson() {
        val state = _uiState.value
        val specialistId = state.specialistId ?: return
        val childId = state.childId ?: return
        val deviceId = state.childDeviceId ?: return
        if (state.isLoading) return
        _uiState.value = state.copy(isLoading = true, errorMessage = null, createdLesson = null)
        viewModelScope.launch {
            when (val result = gateway.createLesson(deviceToken, CreateDeviceLessonRequest(specialistId, childId, deviceId))) {
                is NetworkResult.Success -> _uiState.value = _uiState.value.copy(isLoading = false, createdLesson = result.data)
                else -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = errorMessage(result))
            }
        }
    }

    fun restoreCurrentLesson() {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = gateway.currentSpecialistLesson(deviceToken)) {
                is NetworkResult.Success -> {
                    if (result.data == null) sessions.clearActiveSession()
                    _uiState.value = _uiState.value.copy(isLoading = false, recoveredLesson = result.data)
                }
                else -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = errorMessage(result))
            }
        }
    }

    fun consumeCreatedLesson() { _uiState.value = _uiState.value.copy(createdLesson = null) }
    fun consumeRecoveredLesson() { _uiState.value = _uiState.value.copy(recoveredLesson = null) }

    private fun errorMessage(vararg results: NetworkResult<*>): String = results.firstOrNull { it !is NetworkResult.Success }.let(::errorMessage)
    private fun errorMessage(result: NetworkResult<*>?): String = when (result) {
        NetworkResult.NetworkError -> "Нет соединения с сервером. Повторите попытку."
        is NetworkResult.HttpError -> result.clientMessage ?: "Действие сейчас недоступно."
        else -> "Не удалось загрузить данные."
    }
}

data class ChildIdleState(val checking: Boolean = true, val errorMessage: String? = null, val assignment: ChildLessonAssignmentResponse? = null)

class ChildAssignmentViewModel(
    private val gateway: DeviceAuthGateway,
    private val deviceToken: String,
    private val sessions: SessionRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChildIdleState())
    val uiState = _uiState.asStateFlow()
    private var pollingJob: Job? = null

    fun pollAssignments() {
        if (pollingJob != null) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                when (val result = gateway.currentChildAssignment(deviceToken)) {
                    is NetworkResult.Success -> {
                        if (result.data == null) {
                            sessions.clearActiveSession()
                            _uiState.value = ChildIdleState(checking = false)
                        } else _uiState.value = ChildIdleState(checking = false, assignment = result.data)
                    }
                    NetworkResult.NetworkError -> _uiState.value = _uiState.value.copy(checking = false, errorMessage = "Нет соединения. Проверяем снова…")
                    is NetworkResult.HttpError -> _uiState.value = _uiState.value.copy(checking = false, errorMessage = result.clientMessage ?: "Не удалось проверить занятие")
                }
                delay(2_500)
            }
        }
    }

    fun consumeAssignment() { _uiState.value = _uiState.value.copy(assignment = null) }
    fun stopPolling() { pollingJob?.cancel(); pollingJob = null }
    override fun onCleared() { pollingJob?.cancel(); super.onCleared() }
}

class ManagedLessonLaunchViewModelFactory(private val gateway: DeviceAuthGateway, private val deviceToken: String, private val sessions: SessionRepository, private val lastSpecialists: LastSpecialistStorage) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ManagedLessonLaunchViewModel(gateway, deviceToken, sessions, lastSpecialists) as T
}
class ChildAssignmentViewModelFactory(private val gateway: DeviceAuthGateway, private val deviceToken: String, private val sessions: SessionRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ChildAssignmentViewModel(gateway, deviceToken, sessions) as T
}
