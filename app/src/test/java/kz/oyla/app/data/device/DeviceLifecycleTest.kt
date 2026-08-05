package kz.oyla.app.data.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kz.oyla.app.data.local.DeviceIdentity
import kz.oyla.app.data.local.DeviceIdentityStorage
import kz.oyla.app.data.remote.DeviceAuthGateway
import kz.oyla.app.data.remote.NetworkResult
import kz.oyla.app.data.remote.dto.ActivateDeviceRequest
import kz.oyla.app.data.remote.dto.ActivateDeviceResponse
import kz.oyla.app.data.remote.dto.DeviceAuthMeResponse
import kz.oyla.app.data.remote.dto.DeviceHeartbeatRequest
import kz.oyla.app.domain.model.DeviceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLifecycleTest {
    private val runtime = DeviceRuntimeInfo("1.0", "14", "Test tablet")

    @Test fun `without token startup requires activation`() = runBlocking {
        assertEquals(DeviceStartupResult.ActivationRequired, DeviceLifecycle(FakeStorage(), FakeGateway(), runtime).validateStartup())
    }

    @Test fun `valid activation stores device identity`() = runBlocking {
        val storage = FakeStorage()
        val result = DeviceLifecycle(storage, FakeGateway(activation = NetworkResult.Success(activated(DeviceRole.CHILD))), runtime).activate(" abcd-2345 ")
        assertTrue(result is DeviceActivationResult.Success)
        assertEquals("device-token", storage.identity?.deviceToken)
        assertEquals("ABCD2345", FakeGateway.lastActivation?.activationCode)
    }

    @Test fun `child identity opens child-ready state`() = runBlocking {
        val identity = identity(DeviceRole.CHILD)
        val outcome = DeviceLifecycle(FakeStorage(identity), FakeGateway(me = NetworkResult.Success(me(DeviceRole.CHILD))), runtime).validateStartup()
        assertEquals(DeviceRole.CHILD, (outcome as DeviceStartupResult.Ready).identity.deviceRole)
    }

    @Test fun `specialist identity opens specialist-ready state`() = runBlocking {
        val identity = identity(DeviceRole.SPECIALIST)
        val outcome = DeviceLifecycle(FakeStorage(identity), FakeGateway(me = NetworkResult.Success(me(DeviceRole.SPECIALIST))), runtime).validateStartup()
        assertEquals(DeviceRole.SPECIALIST, (outcome as DeviceStartupResult.Ready).identity.deviceRole)
    }

    @Test fun `invalid activation code shows safe error`() = runBlocking {
        val result = DeviceLifecycle(FakeStorage(), FakeGateway(activation = NetworkResult.HttpError(400, "INVALID_ACTIVATION_CODE")), runtime).activate("ABCD2345")
        assertEquals("Код неверный или срок его действия закончился", (result as DeviceActivationResult.Failure).message)
    }

    @Test fun `already activated tablet explains required unlink`() = runBlocking {
        val result = DeviceLifecycle(FakeStorage(), FakeGateway(activation = NetworkResult.HttpError(409, "DEVICE_ALREADY_ACTIVATED")), runtime).activate("ABCD2345")
        assertEquals("Этот планшет уже подключён к центру. Сначала отвяжите его в текущем кабинете", (result as DeviceActivationResult.Failure).message)
    }

    @Test fun `blocked tablet activation has a safe message`() = runBlocking {
        val result = DeviceLifecycle(FakeStorage(), FakeGateway(activation = NetworkResult.HttpError(403, "DEVICE_BLOCKED")), runtime).activate("ABCD2345")
        assertEquals("Этот планшет заблокирован. Обратитесь к администратору текущего центра", (result as DeviceActivationResult.Failure).message)
    }

    @Test fun `network failure does not remove identity`() = runBlocking {
        val storage = FakeStorage(identity())
        val outcome = DeviceLifecycle(storage, FakeGateway(me = NetworkResult.NetworkError), runtime).validateStartup()
        assertTrue(outcome is DeviceStartupResult.Offline)
        assertTrue(storage.identity != null)
    }

    @Test fun `blocked device opens blocked state`() = runBlocking {
        val outcome = DeviceLifecycle(FakeStorage(identity()), FakeGateway(me = NetworkResult.HttpError(403, "DEVICE_BLOCKED")), runtime).validateStartup()
        assertTrue(outcome is DeviceStartupResult.Blocked)
    }

    @Test fun `unlinked device clears identity`() = runBlocking {
        val storage = FakeStorage(identity())
        val outcome = DeviceLifecycle(storage, FakeGateway(me = NetworkResult.HttpError(403, "DEVICE_UNLINKED")), runtime).validateStartup()
        assertEquals(DeviceStartupResult.ActivationRequired, outcome)
        assertEquals(null, storage.identity)
    }

    @Test fun `heartbeat start does not create two loops`() = runBlocking {
        val storage = FakeStorage(identity())
        val gateway = FakeGateway(heartbeat = NetworkResult.NetworkError)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val heartbeat = HeartbeatController(scope, storage, gateway, runtime) { }
        heartbeat.start(checkNotNull(storage.identity)); heartbeat.start(checkNotNull(storage.identity))
        delay(60)
        assertEquals(1, gateway.heartbeatCalls)
        heartbeat.stop()
    }

    @Test fun `identity string never exposes token`() {
        assertFalse(identity().toString().contains("device-token"))
    }

    @Test fun `stored identity avoids another activation after restart`() = runBlocking {
        val storage = FakeStorage(identity())
        val outcome = DeviceLifecycle(storage, FakeGateway(me = NetworkResult.Success(me())), runtime).validateStartup()
        assertTrue(outcome is DeviceStartupResult.Ready)
    }

    private class FakeStorage(var identity: DeviceIdentity? = null) : DeviceIdentityStorage {
        override suspend fun getOrCreateDeviceUid() = "installation-uuid"
        override suspend fun getDeviceIdentity() = identity
        override suspend fun saveDeviceIdentity(identity: DeviceIdentity) { this.identity = identity }
        override suspend fun clearDeviceIdentity() { identity = null }
    }

    private class FakeGateway(
        private val activation: NetworkResult<ActivateDeviceResponse> = NetworkResult.HttpError(500),
        private val me: NetworkResult<DeviceAuthMeResponse> = NetworkResult.HttpError(500),
        private val heartbeat: NetworkResult<DeviceAuthMeResponse> = NetworkResult.HttpError(500)
    ) : DeviceAuthGateway {
        var heartbeatCalls = 0
        override suspend fun activate(request: ActivateDeviceRequest): NetworkResult<ActivateDeviceResponse> { lastActivation = request; return activation }
        override suspend fun me(deviceToken: String): NetworkResult<DeviceAuthMeResponse> = me
        override suspend fun heartbeat(deviceToken: String, request: DeviceHeartbeatRequest): NetworkResult<DeviceAuthMeResponse> { heartbeatCalls++; return heartbeat }
        companion object { var lastActivation: ActivateDeviceRequest? = null }
    }

    private fun activated(role: DeviceRole) = ActivateDeviceResponse("d1", "c1", "Центр", "Планшет", role, "device-token", "2026-01-01T00:00:00Z")
    private fun me(role: DeviceRole = DeviceRole.CHILD) = DeviceAuthMeResponse("d1", "c1", "Центр", "Планшет", role, "ACTIVE", "2026-01-01T00:00:00Z")
    private fun identity(role: DeviceRole = DeviceRole.CHILD) = DeviceIdentity("d1", "c1", "Центр", "Планшет", role, "device-token", "installation-uuid")
}
