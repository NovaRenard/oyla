package kz.oyla.app.data.session

import kz.oyla.app.data.local.ActiveSession
import kz.oyla.app.data.local.SessionStorage
import kz.oyla.app.data.remote.NetworkResult
import kz.oyla.app.data.remote.OylaApi
import kz.oyla.app.data.remote.dto.ConnectSessionRequest
import kz.oyla.app.data.remote.dto.ConnectSessionResponse
import kz.oyla.app.data.remote.dto.CreateSessionRequest
import kz.oyla.app.data.remote.dto.CreateSessionResponse
import kz.oyla.app.data.remote.dto.SessionStateResponse
import kz.oyla.app.data.remote.dto.ExerciseDto
import kz.oyla.app.data.remote.dto.ExerciseStateResponse
import kz.oyla.app.data.remote.dto.SpecialistExerciseDto
import kz.oyla.app.data.remote.dto.ShowExerciseRequest
import kz.oyla.app.data.remote.dto.ShowExerciseResponse
import kz.oyla.app.data.remote.dto.StartExerciseRequest
import kz.oyla.app.data.remote.dto.StartExerciseResponse
import kz.oyla.app.data.remote.dto.AnswerExerciseRequest
import kz.oyla.app.data.remote.dto.AnswerExerciseResponse
import kz.oyla.app.data.remote.dto.NextExerciseRequest
import kz.oyla.app.data.remote.dto.SessionSummaryResponse
import kz.oyla.app.data.remote.dto.DeviceLessonResponse
import kz.oyla.app.data.remote.dto.ChildLessonAssignmentResponse
import kz.oyla.app.data.remote.dto.CompleteWhiteboardExerciseRequest
import kz.oyla.app.domain.model.DeviceRole

data class SessionDetails(
    val sessionId: String,
    val childName: String,
    val connectionCode: String?,
    val status: String,
    val childConnected: Boolean,
    val token: String,
    val role: DeviceRole,
    val specialistName: String? = null
)

sealed interface SessionActionResult<out T> {
    data class Success<T>(val value: T) : SessionActionResult<T>
    data class Failure(val error: SessionUserError) : SessionActionResult<Nothing>
}

enum class SessionUserError(val message: String) {
    NETWORK("Не удалось подключиться к серверу"),
    INVALID_CODE("Код не найден"),
    EXPIRED("Срок действия кода истёк"),
    ALREADY_CONNECTED("К этому занятию уже подключено другое устройство"),
    INVALID_DATA("Проверьте введённые данные"),
    UNKNOWN("Не удалось выполнить действие. Попробуйте ещё раз")
}

sealed interface ExerciseActionResult<out T> {
    data class Success<T>(val value: T) : ExerciseActionResult<T>
    data class Failure(val message: String) : ExerciseActionResult<Nothing>
}

class SessionRepository(
    private val api: OylaApi,
    private val storage: SessionStorage
) {
    suspend fun createSession(childName: String): SessionActionResult<SessionDetails> {
        val result = api.createSession(
            CreateSessionRequest(childName.trim(), storage.getOrCreateDeviceId())
        )
        return when (result) {
            is NetworkResult.Success -> result.data.toSpecialistDetails().also { storage.saveActiveSession(it.toActiveSession()) }
                .let { SessionActionResult.Success(it) }
            else -> SessionActionResult.Failure(result.toUserError())
        }
    }

    suspend fun connectSession(connectionCode: String): SessionActionResult<SessionDetails> {
        val result = api.connectSession(
            ConnectSessionRequest(connectionCode, storage.getOrCreateDeviceId())
        )
        return when (result) {
            is NetworkResult.Success -> result.data.toChildDetails(connectionCode).also { storage.saveActiveSession(it.toActiveSession()) }
                .let { SessionActionResult.Success(it) }
            else -> SessionActionResult.Failure(result.toUserError())
        }
    }

    suspend fun restoreActiveSession(role: DeviceRole): SessionActionResult<SessionDetails>? {
        val active = storage.getActiveSession()?.takeIf { it.role == role } ?: return null
        return when (val result = api.getSessionState(active.sessionId, active.sessionToken)) {
            is NetworkResult.Success -> SessionActionResult.Success(result.data.toDetails(active))
            is NetworkResult.HttpError -> {
                if (result.statusCode in setOf(401, 404, 410)) storage.clearActiveSession()
                SessionActionResult.Failure(result.toUserError())
            }
            NetworkResult.NetworkError -> SessionActionResult.Failure(SessionUserError.NETWORK)
        }
    }

    suspend fun cancelActiveSession(): SessionActionResult<Unit> {
        val active = storage.getActiveSession() ?: return SessionActionResult.Success(Unit)
        return when (val result = api.cancelSession(active.sessionId, active.sessionToken)) {
            is NetworkResult.Success -> {
                storage.clearActiveSession()
                SessionActionResult.Success(Unit)
            }
            else -> SessionActionResult.Failure(result.toUserError())
        }
    }

    suspend fun completeActiveSession(): SessionActionResult<Unit> {
        val active = storage.getActiveSession() ?: return SessionActionResult.Success(Unit)
        return when (val result = api.completeSession(active.sessionId, active.sessionToken)) {
            is NetworkResult.Success -> {
                storage.clearActiveSession()
                SessionActionResult.Success(Unit)
            }
            else -> SessionActionResult.Failure(result.toUserError())
        }
    }

    suspend fun getExercise(exerciseId: String): ExerciseActionResult<ExerciseDto> =
        api.getExercise(exerciseId).toExerciseResult("Не удалось загрузить задание")

    suspend fun getExerciseState(session: SessionDetails): ExerciseActionResult<ExerciseStateResponse> =
        api.getExerciseState(session.sessionId, session.token).toExerciseResult("Соединение потеряно. Переподключаемся…")

    suspend fun getSpecialistExercise(session: SessionDetails): ExerciseActionResult<SpecialistExerciseDto> =
        api.getSpecialistExercise(session.sessionId, session.token).toExerciseResult("Не удалось загрузить задание")

    suspend fun showExercise(session: SessionDetails, exerciseId: String): ExerciseActionResult<ShowExerciseResponse> =
        api.showExercise(session.sessionId, session.token, ShowExerciseRequest(exerciseId))
            .toExerciseResult("Не удалось показать задание")

    suspend fun startExercise(session: SessionDetails, sessionExerciseId: String): ExerciseActionResult<StartExerciseResponse> =
        api.startExercise(session.sessionId, session.token, StartExerciseRequest(sessionExerciseId))
            .toExerciseResult("Не удалось начать задание")

    suspend fun submitAnswer(
        session: SessionDetails,
        sessionExerciseId: String,
        optionId: String,
        clientEventId: String
    ): ExerciseActionResult<AnswerExerciseResponse> =
        api.answerExercise(session.sessionId, session.token, AnswerExerciseRequest(sessionExerciseId, optionId, clientEventId))
            .toExerciseResult("Ответ не отправлен. Попробуйте ещё раз")

    suspend fun nextExercise(session: SessionDetails, currentSessionExerciseId: String): ExerciseActionResult<ExerciseStateResponse> =
        api.nextExercise(session.sessionId, session.token, NextExerciseRequest(currentSessionExerciseId))
            .toExerciseResult("Не удалось открыть следующее задание")

    suspend fun completeWhiteboardExercise(session: SessionDetails, sessionExerciseId: String): ExerciseActionResult<ExerciseStateResponse> =
        api.completeWhiteboardExercise(session.sessionId, session.token, CompleteWhiteboardExerciseRequest(sessionExerciseId))
            .toExerciseResult("Не удалось завершить доску")

    suspend fun getSummary(session: SessionDetails): ExerciseActionResult<SessionSummaryResponse> =
        api.getSummary(session.sessionId, session.token).toExerciseResult("Не удалось загрузить итог занятия")

    suspend fun clearActiveSession() = storage.clearActiveSession()

    /** Stores a server-assigned SaaS lesson locally only after the device-auth response succeeds. */
    suspend fun adoptManagedSpecialistLesson(lesson: DeviceLessonResponse): SessionDetails = SessionDetails(
        sessionId = lesson.sessionId,
        childName = lesson.childName,
        connectionCode = null,
        status = lesson.status,
        childConnected = true,
        token = lesson.sessionToken,
        role = DeviceRole.SPECIALIST,
        specialistName = lesson.specialistName
    ).also { storage.saveActiveSession(it.toActiveSession()) }

    suspend fun adoptManagedChildAssignment(assignment: ChildLessonAssignmentResponse): SessionDetails = SessionDetails(
        sessionId = assignment.sessionId,
        childName = assignment.childName,
        connectionCode = null,
        status = assignment.status,
        childConnected = true,
        token = assignment.sessionToken,
        role = DeviceRole.CHILD
    ).also { storage.saveActiveSession(it.toActiveSession()) }

    private fun CreateSessionResponse.toSpecialistDetails() = SessionDetails(
        sessionId = sessionId,
        childName = "",
        connectionCode = connectionCode,
        status = status,
        childConnected = false,
        token = specialistToken,
        role = DeviceRole.SPECIALIST
    )

    private fun ConnectSessionResponse.toChildDetails(code: String) = SessionDetails(
        sessionId = sessionId,
        childName = childName,
        connectionCode = code,
        status = status,
        childConnected = true,
        token = childToken,
        role = DeviceRole.CHILD
    )

    private fun SessionStateResponse.toDetails(active: ActiveSession) = SessionDetails(
        sessionId = sessionId,
        childName = childName,
        connectionCode = if (active.role == DeviceRole.SPECIALIST) connectionCode else active.connectionCode,
        status = status,
        childConnected = childConnected,
        token = active.sessionToken,
        role = active.role,
        specialistName = active.specialistName
    )

    private fun SessionDetails.toActiveSession() = ActiveSession(
        sessionId = sessionId,
        sessionToken = token,
        role = role,
        connectionCode = if (role == DeviceRole.SPECIALIST) connectionCode else null,
        specialistName = specialistName
    )

    private fun NetworkResult<*>.toUserError(): SessionUserError = when (this) {
        NetworkResult.NetworkError -> SessionUserError.NETWORK
        is NetworkResult.HttpError -> when (statusCode) {
            400 -> SessionUserError.INVALID_DATA
            404 -> SessionUserError.INVALID_CODE
            409 -> SessionUserError.ALREADY_CONNECTED
            410 -> SessionUserError.EXPIRED
            else -> SessionUserError.UNKNOWN
        }
        is NetworkResult.Success -> SessionUserError.UNKNOWN
    }

    private fun <T> NetworkResult<T>.toExerciseResult(fallback: String): ExerciseActionResult<T> = when (this) {
        is NetworkResult.Success -> ExerciseActionResult.Success(data)
        NetworkResult.NetworkError -> ExerciseActionResult.Failure("Соединение потеряно. Переподключаемся…")
        is NetworkResult.HttpError -> ExerciseActionResult.Failure(
            when (statusCode) {
                409 -> when (errorCode) {
                    "ALREADY_CONNECTED" -> fallback
                    else -> fallback
                }
                else -> fallback
            }
        )
    }
}
