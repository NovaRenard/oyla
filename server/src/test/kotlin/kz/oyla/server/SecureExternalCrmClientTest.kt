package kz.oyla.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kz.oyla.server.integration.CredentialCipher
import kz.oyla.server.integration.EncryptedCredential
import kz.oyla.server.integration.ExternalCrmResult
import kz.oyla.server.integration.OutboundUrlException
import kz.oyla.server.integration.OutboundUrlPolicy
import kz.oyla.server.integration.SecureExternalCrmClient
import kz.oyla.server.model.dto.CrmConnectionTestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureExternalCrmClientTest {
    @Test fun `production URL policy blocks SSRF schemes localhost private and non https`() {
        val policy = OutboundUrlPolicy(production = true, resolver = { host -> when (host) { "public.example" -> arrayOf(InetAddress.getByName("8.8.8.8")); else -> arrayOf(InetAddress.getByName("127.0.0.1")) } })
        assertEquals("https://public.example", policy.validateBaseUrl("https://public.example/").normalized)
        listOf("http://public.example", "file:///tmp/x", "https://user:pass@public.example", "https://public.example/#fragment", "https://localhost", "https://127.0.0.1", "https://10.0.0.1", "https://169.254.1.1", "https://192.168.1.5").forEach { value ->
            assertThrows(OutboundUrlException::class.java) { policy.validateBaseUrl(value) }
        }
    }

    @Test fun `development override explicitly permits local mock only outside production`() {
        val development = OutboundUrlPolicy(production = false, allowUnsafeDevelopmentOutbound = true)
        assertEquals("http://localhost:8089", development.validateBaseUrl("http://localhost:8089/").normalized)
        assertThrows(IllegalArgumentException::class.java) { OutboundUrlPolicy(production = true, allowUnsafeDevelopmentOutbound = true) }
    }

    @Test fun `AES GCM ciphertext needs the correct key and nonce`() {
        val cipher = CredentialCipher.fromSecret(TestKey); val encrypted = cipher.encrypt("crm-secret")
        assertTrue(encrypted.ciphertext != "crm-secret"); assertEquals("crm-secret", cipher.decrypt(encrypted))
        assertThrows(RuntimeException::class.java) { CredentialCipher.fromSecret(OtherKey).decrypt(encrypted) }
        assertThrows(RuntimeException::class.java) { cipher.decrypt(encrypted.copy(nonce = "bad")) }
    }

    @Test fun `HTTP client sends server side bearer handles bad bodies timeouts and never follows redirects`() = runBlocking {
        val targetHits = AtomicInteger(); val target = startServer { exchange -> targetHits.incrementAndGet(); exchange.respond(200, "{}") }
        val server = startServer { exchange ->
            val mode = exchange.requestURI.path.trim('/').substringBefore('/')
            when (exchange.requestURI.path.substringAfterLast('/')) {
                "health" -> when (exchange.requestURI.query) {
                    else -> when (mode) {
                    "redirect" -> { exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/private"); exchange.sendResponseHeaders(302, -1); exchange.close() }
                    "slow" -> { Thread.sleep(300); exchange.respond(200, "{}") }
                    "auth" -> { exchange.sendResponseHeaders(401, -1); exchange.close() }
                    "server-error" -> exchange.respond(500, "{}")
                    else -> { assertEquals("Bearer key-1", exchange.requestHeaders.getFirst("Authorization")); exchange.respond(200, "{}") }
                    }
                }
                "children" -> when (exchange.requestURI.query) {
                    else -> when (mode) {
                    "bad" -> exchange.respond(200, "not-json")
                    "large" -> exchange.respond(200, "[" + " ".repeat(1_100_000) + "]")
                    else -> exchange.respond(200, """[{"id":"e1","firstName":"Алихан","lastName":"Сарсенов","birthDate":"2019-04-16","status":"ACTIVE","updatedAt":"2026-08-12T07:30:00Z"}]""")
                    }
                }
                else -> exchange.respond(404, "{}")
            }
        }
        try {
            val base = "http://localhost:${server.address.port}"; val client = SecureExternalCrmClient(OutboundUrlPolicy(false, true), requestTimeout = Duration.ofMillis(100), maxAttempts = 1)
            assertTrue(client.checkConnection(base, "key-1") is ExternalCrmResult.Success)
            val children = client.listChildren(base, "key-1") as ExternalCrmResult.Success
            assertEquals(1, children.value.size)
            val redirected = client.checkConnection("$base/redirect", "key-1") as ExternalCrmResult.Failure
            assertEquals(CrmConnectionTestStatus.INVALID_RESPONSE, redirected.status); assertEquals(0, targetHits.get())
            val unauthorized = client.checkConnection("$base/auth", "key-1") as ExternalCrmResult.Failure
            assertEquals(CrmConnectionTestStatus.AUTH_FAILED, unauthorized.status)
            val serverError = client.checkConnection("$base/server-error", "key-1") as ExternalCrmResult.Failure
            assertEquals(CrmConnectionTestStatus.UNREACHABLE, serverError.status)
            val bad = client.listChildren("$base/bad", "key-1") as ExternalCrmResult.Failure
            assertEquals(CrmConnectionTestStatus.INVALID_RESPONSE, bad.status)
            val large = client.listChildren("$base/large", "key-1") as ExternalCrmResult.Failure
            assertEquals(CrmConnectionTestStatus.INVALID_RESPONSE, large.status)
            val slow = client.checkConnection("$base/slow", "key-1") as ExternalCrmResult.Failure
            assertEquals(CrmConnectionTestStatus.TIMEOUT, slow.status)
        } finally { server.stop(0); target.stop(0) }
    }

    private fun startServer(handler: (HttpExchange) -> Unit): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { createContext("/") { handler(it) }; executor = Executors.newCachedThreadPool(); start() }
    private fun HttpExchange.respond(status: Int, body: String) { val bytes = body.toByteArray(); responseHeaders.add("Content-Type", "application/json"); sendResponseHeaders(status, bytes.size.toLong()); responseBody.use { it.write(bytes) } }
    private companion object { const val TestKey = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="; const val OtherKey = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=" }
}
