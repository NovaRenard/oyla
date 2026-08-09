package kz.oyla.app.data.session

import kotlinx.coroutines.runBlocking
import kz.oyla.app.data.local.ActiveSession
import kz.oyla.app.data.local.SessionStorage
import kz.oyla.app.data.remote.NetworkResult
import kz.oyla.app.data.remote.OylaApi
import kz.oyla.app.data.remote.dto.ConnectSessionRequest
import kz.oyla.app.data.remote.dto.ConnectSessionResponse
import kz.oyla.app.data.remote.dto.CreateSessionRequest
import kz.oyla.app.data.remote.dto.CreateSessionResponse
import kz.oyla.app.data.remote.dto.SessionStateResponse
import kz.oyla.app.data.remote.dto.ShowExerciseRequest
import kz.oyla.app.data.remote.dto.ShowExerciseResponse
import kz.oyla.app.data.remote.dto.ChildLessonAssignmentResponse
import kz.oyla.app.data.remote.dto.DeviceLessonResponse
import kz.oyla.app.domain.model.DeviceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {
    @Test
    fun `successful session creation persists specialist session`() = runBlocking {
        val storage = FakeStorage()
        val api = FakeApi(createResult = NetworkResult.Success(
            CreateSessionResponse("session-1", "4821", "WAITING_FOR_CHILD", "specialist-token", "2026-08-02T10:00:00Z")
        ))

        val result = SessionRepository(api, storage).createSession(" Алина ")

        assertTrue(result is SessionActionResult.Success)
        val success = result as SessionActionResult.Success
        assertEquals("4821", success.value.connectionCode)
        assertEquals("Алина", api.lastCreateRequest?.childName)
        assertEquals(DeviceRole.SPECIALIST, storage.active?.role)
    }

    @Test
    fun `network error maps to Russian connection error`() = runBlocking {
        val result = SessionRepository(FakeApi(createResult = NetworkResult.NetworkError), FakeStorage())
            .createSession("Алина")

        assertEquals(SessionUserError.NETWORK, (result as SessionActionResult.Failure).error)
    }

    @Test
    fun `successful child connection persists child token`() = runBlocking {
        val storage = FakeStorage()
        val api = FakeApi(connectResult = NetworkResult.Success(
            ConnectSessionResponse("session-1", "Алина", "READY", "child-token")
        ))

        val result = SessionRepository(api, storage).connectSession("4821")

        assertTrue(result is SessionActionResult.Success)
        assertEquals("4821", api.lastConnectRequest?.connectionCode)
        assertEquals("child-token", storage.active?.sessionToken)
        assertEquals(DeviceRole.CHILD, storage.active?.role)
    }

    @Test
    fun `API conflict maps to already connected state`() = runBlocking {
        val result = SessionRepository(
            FakeApi(connectResult = NetworkResult.HttpError(409, "ALREADY_CONNECTED")),
            FakeStorage()
        ).connectSession("4821")

        assertEquals(SessionUserError.ALREADY_CONNECTED, (result as SessionActionResult.Failure).error)
    }

    @Test
    fun `exercise API error maps to a safe Russian message`() = runBlocking {
        val api = FakeApi(showResult = NetworkResult.NetworkError)
        val result = SessionRepository(api, FakeStorage()).showExercise(
            SessionDetails("session", "Алина", null, "READY", true, "specialist-token", DeviceRole.SPECIALIST),
            "sound-r-rocket"
        )
        assertEquals("Соединение потеряно. Переподключаемся…", (result as ExerciseActionResult.Failure).message)
    }

    @Test
    fun `server-assigned SaaS lesson persists only the role-specific token without a connection code`() = runBlocking {
        val storage = FakeStorage()
        val repository = SessionRepository(FakeApi(), storage)
        val specialist = repository.adoptManagedSpecialistLesson(
            DeviceLessonResponse("lesson-1", "specialist-1", "Анна", "child-1", "Алихан", "tablet-child", "READY", "specialist-token", "2026-08-09T10:00:00Z")
        )
        assertEquals(DeviceRole.SPECIALIST, specialist.role)
        assertEquals(null, specialist.connectionCode)
        assertEquals("specialist-token", storage.active?.sessionToken)

        val child = repository.adoptManagedChildAssignment(
            ChildLessonAssignmentResponse("lesson-1", "child-1", "Алихан", "child-token", "READY", "2026-08-09T10:00:00Z")
        )
        assertEquals(DeviceRole.CHILD, child.role)
        assertEquals(null, child.connectionCode)
        assertEquals("child-token", storage.active?.sessionToken)
    }

    private class FakeStorage : SessionStorage {
        var active: ActiveSession? = null
        override suspend fun getOrCreateDeviceId() = "device-1"
        override suspend fun saveActiveSession(session: ActiveSession) { active = session }
        override suspend fun getActiveSession(): ActiveSession? = active
        override suspend fun clearActiveSession() { active = null }
    }

    private class FakeApi(
        private val createResult: NetworkResult<CreateSessionResponse> = NetworkResult.HttpError(500),
        private val connectResult: NetworkResult<ConnectSessionResponse> = NetworkResult.HttpError(500),
        private val showResult: NetworkResult<ShowExerciseResponse> = NetworkResult.HttpError(500)
    ) : OylaApi {
        var lastCreateRequest: CreateSessionRequest? = null
        var lastConnectRequest: ConnectSessionRequest? = null

        override suspend fun createSession(request: CreateSessionRequest): NetworkResult<CreateSessionResponse> {
            lastCreateRequest = request
            return createResult
        }

        override suspend fun connectSession(request: ConnectSessionRequest): NetworkResult<ConnectSessionResponse> {
            lastConnectRequest = request
            return connectResult
        }

        override suspend fun getSessionState(sessionId: String, token: String): NetworkResult<SessionStateResponse> =
            NetworkResult.HttpError(500)

        override suspend fun cancelSession(sessionId: String, token: String): NetworkResult<Unit> =
            NetworkResult.HttpError(500)

        override suspend fun showExercise(
            sessionId: String,
            token: String,
            request: ShowExerciseRequest
        ): NetworkResult<ShowExerciseResponse> = showResult
    }
}
