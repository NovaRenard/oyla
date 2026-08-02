package kz.oyla.app.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kz.oyla.app.data.remote.dto.ConnectSessionRequest
import kz.oyla.app.data.remote.dto.ConnectSessionResponse
import kz.oyla.app.data.remote.dto.CreateSessionRequest
import kz.oyla.app.data.remote.dto.CreateSessionResponse
import kz.oyla.app.data.remote.dto.ErrorResponse
import kz.oyla.app.data.remote.dto.SessionStateResponse

interface OylaApi {
    suspend fun createSession(request: CreateSessionRequest): NetworkResult<CreateSessionResponse>
    suspend fun connectSession(request: ConnectSessionRequest): NetworkResult<ConnectSessionResponse>
    suspend fun getSessionState(sessionId: String, token: String): NetworkResult<SessionStateResponse>
    suspend fun cancelSession(sessionId: String, token: String): NetworkResult<Unit>
}

class OylaApiClient(
    private val baseUrl: String,
    private val client: HttpClient = defaultHttpClient()
) : OylaApi {
    override suspend fun createSession(request: CreateSessionRequest): NetworkResult<CreateSessionResponse> =
        requestJson<CreateSessionResponse, CreateSessionRequest>("/api/v1/sessions", request)

    override suspend fun connectSession(request: ConnectSessionRequest): NetworkResult<ConnectSessionResponse> =
        requestJson<ConnectSessionResponse, ConnectSessionRequest>("/api/v1/sessions/connect", request)

    override suspend fun getSessionState(sessionId: String, token: String): NetworkResult<SessionStateResponse> =
        request {
            client.get("${baseUrl.trimEnd('/')}/api/v1/sessions/$sessionId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }

    override suspend fun cancelSession(sessionId: String, token: String): NetworkResult<Unit> = try {
        val response = client.post("${baseUrl.trimEnd('/')}/api/v1/sessions/$sessionId/cancel") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (response.status == HttpStatusCode.NoContent) NetworkResult.Success(Unit) else response.toError()
    } catch (_: Exception) {
        NetworkResult.NetworkError
    }

    private suspend inline fun <reified T, reified B> requestJson(path: String, body: B): NetworkResult<T> =
        request {
            client.post("${baseUrl.trimEnd('/')}$path") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend inline fun <reified T> request(
        block: suspend () -> io.ktor.client.statement.HttpResponse
    ): NetworkResult<T> = try {
        val response = block()
        if (response.status.value in 200..299) {
            NetworkResult.Success(response.body())
        } else {
            response.toError()
        }
    } catch (_: Exception) {
        NetworkResult.NetworkError
    }

    private suspend fun io.ktor.client.statement.HttpResponse.toError(): NetworkResult.HttpError {
        val error = runCatching { body<ErrorResponse>() }.getOrNull()
        return NetworkResult.HttpError(status.value, error?.code)
    }

    companion object {
        private fun defaultHttpClient() = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }
    }
}
