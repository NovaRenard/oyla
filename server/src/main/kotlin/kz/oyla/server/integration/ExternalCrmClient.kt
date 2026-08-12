package kz.oyla.server.integration

import java.io.InputStream
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kz.oyla.server.model.ChildStatus
import kz.oyla.server.model.ExternalCrmChild
import kz.oyla.server.model.dto.CrmConnectionTestStatus
import kz.oyla.server.model.dto.ExternalCrmChildDto
import kz.oyla.server.model.dto.ExternalCrmHealthResponse
import java.time.Instant
import java.time.LocalDate

sealed interface ExternalCrmResult<out T> {
    data class Success<T>(val value: T, val httpStatus: Int, val durationMillis: Long) : ExternalCrmResult<T>
    data class Failure(val status: CrmConnectionTestStatus) : ExternalCrmResult<Nothing>
}

interface ExternalCrmClient {
    suspend fun checkConnection(baseUrl: String, apiKey: String): ExternalCrmResult<ExternalCrmHealthResponse>
    suspend fun listChildren(baseUrl: String, apiKey: String): ExternalCrmResult<List<ExternalCrmChild>>
}

/** Server-only client. Redirects are rejected; Authorization is never logged or exposed. */
class SecureExternalCrmClient(
    private val policy: OutboundUrlPolicy,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val requestTimeout: Duration = Duration.ofSeconds(15),
    private val maxAttempts: Int = MaxAttempts
) : ExternalCrmClient {
    override suspend fun checkConnection(baseUrl: String, apiKey: String): ExternalCrmResult<ExternalCrmHealthResponse> = request(baseUrl, apiKey, HealthPath) { payload ->
        val element = json.parseToJsonElement(payload)
        if (element !is JsonObject) throw CrmPayloadException
        ExternalCrmHealthResponse()
    }

    override suspend fun listChildren(baseUrl: String, apiKey: String): ExternalCrmResult<List<ExternalCrmChild>> = request(baseUrl, apiKey, ChildrenPath) { payload ->
        val element = json.parseToJsonElement(payload)
        val array = element as? JsonArray ?: throw CrmPayloadException
        val children = array.map { item ->
            val obj = item as? JsonObject ?: throw CrmPayloadException
            val dto = json.decodeFromJsonElement(ExternalCrmChildDto.serializer(), obj)
            dto.toValidatedChild()
        }
        if (children.size > MaxChildren) throw CrmPayloadException
        if (children.map { it.externalId }.toSet().size != children.size) throw CrmPayloadException
        children
    }

    private suspend fun <T> request(baseUrl: String, apiKey: String, path: String, decode: (String) -> T): ExternalCrmResult<T> {
        if (apiKey.isBlank() || apiKey.length > MaxCredentialLength) return ExternalCrmResult.Failure(CrmConnectionTestStatus.AUTH_FAILED)
        val base = try { policy.validateBaseUrl(baseUrl).uri } catch (error: OutboundUrlException) { return ExternalCrmResult.Failure(error.toConnectionStatus()) }
        val uri = policy.endpoint(base, path)
        val attempts = maxAttempts.coerceIn(1, MaxAttempts)
        repeat(attempts) { attempt ->
            val started = System.nanoTime()
            val response = try {
                policy.verifyResolvedHost(uri.host)
                withContext(Dispatchers.IO) {
                    client.send(HttpRequest.newBuilder(uri).GET().timeout(requestTimeout)
                        .header("Accept", "application/json").header("Authorization", "Bearer $apiKey").build(), HttpResponse.BodyHandlers.ofInputStream())
                }
            } catch (error: Throwable) {
                val status = error.toConnectionStatus()
                if (attempt + 1 < attempts && status in retryableFailures) { delay(200L * (attempt + 1)); return@repeat }
                return ExternalCrmResult.Failure(status)
            }
            val duration = (System.nanoTime() - started) / 1_000_000
            if (response.statusCode() in 401..403) { response.body().close(); return ExternalCrmResult.Failure(CrmConnectionTestStatus.AUTH_FAILED) }
            if (response.statusCode() !in 200..299) {
                response.body().close()
                if (response.statusCode() >= 500 && attempt + 1 < attempts) { delay(200L * (attempt + 1)); return@repeat }
                return ExternalCrmResult.Failure(if (response.statusCode() >= 500) CrmConnectionTestStatus.UNREACHABLE else CrmConnectionTestStatus.INVALID_RESPONSE)
            }
            if (!response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim().equals("application/json", ignoreCase = true)) { response.body().close(); return ExternalCrmResult.Failure(CrmConnectionTestStatus.INVALID_RESPONSE) }
            val payload = try { response.body().readUtf8Limited(MaxBodyBytes) } catch (_: OversizedResponseException) { return ExternalCrmResult.Failure(CrmConnectionTestStatus.INVALID_RESPONSE) }
            catch (_: Exception) { return ExternalCrmResult.Failure(CrmConnectionTestStatus.INVALID_RESPONSE) }
            return try { ExternalCrmResult.Success(decode(payload), response.statusCode(), duration) } catch (_: Exception) { ExternalCrmResult.Failure(CrmConnectionTestStatus.INVALID_RESPONSE) }
        }
        return ExternalCrmResult.Failure(CrmConnectionTestStatus.UNREACHABLE)
    }

    private fun InputStream.readUtf8Limited(limit: Int): String = use { input ->
        val bytes = ByteArray(8192); val output = java.io.ByteArrayOutputStream()
        while (true) { val count = input.read(bytes); if (count < 0) break; if (output.size() + count > limit) throw OversizedResponseException; output.write(bytes, 0, count) }
        output.toString(Charsets.UTF_8)
    }

    private fun ExternalCrmChildDto.toValidatedChild(): ExternalCrmChild {
        val id = id.trim().takeIf { it.isNotEmpty() && it.length <= 255 } ?: throw CrmPayloadException
        val first = firstName.trim().takeIf { it.isNotEmpty() && it.length <= 100 } ?: throw CrmPayloadException
        val last = lastName.trim().takeIf { it.isNotEmpty() && it.length <= 100 } ?: throw CrmPayloadException
        val date = runCatching { LocalDate.parse(birthDate) }.getOrElse { throw CrmPayloadException }
        val updated = runCatching { Instant.parse(updatedAt) }.getOrElse { throw CrmPayloadException }
        val mappedStatus = when (status.uppercase()) { "ACTIVE" -> ChildStatus.ACTIVE; "ARCHIVED", "INACTIVE" -> ChildStatus.ARCHIVED; else -> throw CrmPayloadException }
        return ExternalCrmChild(id, first, last, date, mappedStatus, updated)
    }

    private fun Throwable.toConnectionStatus(): CrmConnectionTestStatus = when (this) {
        is OutboundUrlException.Invalid, is OutboundUrlException.Blocked -> CrmConnectionTestStatus.INVALID_RESPONSE
        is OutboundUrlException.Unreachable, is ConnectException, is java.net.UnknownHostException -> CrmConnectionTestStatus.UNREACHABLE
        is HttpTimeoutException, is java.net.SocketTimeoutException -> CrmConnectionTestStatus.TIMEOUT
        is SSLException -> CrmConnectionTestStatus.TLS_ERROR
        else -> cause?.toConnectionStatus() ?: CrmConnectionTestStatus.UNREACHABLE
    }
    private fun OutboundUrlException.toConnectionStatus() = when (this) { OutboundUrlException.Unreachable -> CrmConnectionTestStatus.UNREACHABLE; else -> CrmConnectionTestStatus.INVALID_RESPONSE }

    private companion object {
        const val HealthPath = "/api/integrations/oyla/v1/health"
        const val ChildrenPath = "/api/integrations/oyla/v1/children"
        const val MaxBodyBytes = 1_048_576
        const val MaxChildren = 10_000
        const val MaxCredentialLength = 4096
        const val MaxAttempts = 2
        val retryableFailures = setOf(CrmConnectionTestStatus.UNREACHABLE, CrmConnectionTestStatus.TIMEOUT)
    }
}

private data object CrmPayloadException : RuntimeException()
private data object OversizedResponseException : RuntimeException()
