package kz.oyla.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kz.oyla.app.data.remote.dto.ActivateDeviceRequest
import kz.oyla.app.data.remote.dto.ActivateDeviceResponse
import kz.oyla.app.data.remote.dto.DeviceAuthMeResponse
import kz.oyla.app.data.remote.dto.DeviceHeartbeatRequest
import kz.oyla.app.data.remote.dto.SaasErrorResponse

interface DeviceAuthGateway {
    suspend fun activate(request: ActivateDeviceRequest): NetworkResult<ActivateDeviceResponse>
    suspend fun me(deviceToken: String): NetworkResult<DeviceAuthMeResponse>
    suspend fun heartbeat(deviceToken: String, request: DeviceHeartbeatRequest): NetworkResult<DeviceAuthMeResponse>
}

/**
 * Dedicated device-only Ktor client. The DefaultRequest token interceptor is serialized so
 * a request never inherits a different tablet token. No Ktor logging plugin is installed.
 */
class DeviceAuthApiClient(
    private val baseUrl: String,
    httpClient: HttpClient? = null
) : DeviceAuthGateway {
    private val tokenMutex = Mutex()
    private var requestDeviceToken: String? = null
    private val client = httpClient ?: deviceHttpClient { requestDeviceToken }

    override suspend fun activate(request: ActivateDeviceRequest): NetworkResult<ActivateDeviceResponse> = requestJson(
        method = HttpMethod.Post,
        path = "/api/v1/device-auth/activate",
        body = request
    )

    override suspend fun me(deviceToken: String): NetworkResult<DeviceAuthMeResponse> = authenticated(deviceToken) {
        getWithRetry("/api/v1/device-auth/me")
    }

    override suspend fun heartbeat(deviceToken: String, request: DeviceHeartbeatRequest): NetworkResult<DeviceAuthMeResponse> = authenticated(deviceToken) {
        requestJson(HttpMethod.Post, "/api/v1/device-auth/heartbeat", request)
    }

    private suspend fun <T> authenticated(deviceToken: String, request: suspend () -> NetworkResult<T>): NetworkResult<T> =
        tokenMutex.withLock {
            requestDeviceToken = deviceToken
            try { request() } finally { requestDeviceToken = null }
        }

    private suspend inline fun <reified T, reified B> requestJson(method: HttpMethod, path: String, body: B): NetworkResult<T> = try {
        val response = client.request("${baseUrl.trimEnd('/')}$path") {
            this.method = method
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        response.toResult()
    } catch (_: Exception) { NetworkResult.NetworkError }

    private suspend inline fun <reified T> getWithRetry(path: String): NetworkResult<T> {
        repeat(2) { attempt ->
            val result = try { client.get("${baseUrl.trimEnd('/')}$path").toResult<T>() } catch (_: Exception) { NetworkResult.NetworkError }
            if (result !is NetworkResult.NetworkError && (result !is NetworkResult.HttpError || result.statusCode < 500)) return result
            if (attempt == 1) return result
            kotlinx.coroutines.delay(500L * (attempt + 1))
        }
        return NetworkResult.NetworkError
    }

    private suspend inline fun <reified T> HttpResponse.toResult(): NetworkResult<T> = if (status.value in 200..299) {
        NetworkResult.Success(body())
    } else {
        val error = runCatching { body<SaasErrorResponse>().error }.getOrNull()
        NetworkResult.HttpError(status.value, error?.code, error?.message)
    }

    private companion object {
        fun deviceHttpClient(tokenProvider: () -> String?) = HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 20_000; socketTimeoutMillis = 20_000 }
            defaultRequest {
                tokenProvider()?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
        }
    }
}
