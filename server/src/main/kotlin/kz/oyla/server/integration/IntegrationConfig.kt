package kz.oyla.server.integration

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class IntegrationConfig(
    val production: Boolean,
    val credentialEncryptionKey: String,
    val allowUnsafeDevelopmentOutbound: Boolean
) {
    companion object {
        fun fromEnvironment(): IntegrationConfig {
            val production = System.getenv("OYLA_ENV")?.trim()?.equals("production", ignoreCase = true) == true
            val key = System.getenv("INTEGRATION_CREDENTIAL_ENCRYPTION_KEY")?.takeIf { it.isNotBlank() }
                ?: if (production) throw IllegalStateException("INTEGRATION_CREDENTIAL_ENCRYPTION_KEY must be set when OYLA_ENV=production")
                else DevelopmentKey
            // Validate at startup so production does not discover a bad key after an admin enters a CRM key.
            CredentialCipher.fromSecret(key)
            val unsafe = !production && (System.getenv("INTEGRATION_ALLOW_UNSAFE_LOCALHOST")?.toBooleanStrictOrNull() == true)
            return IntegrationConfig(production, key, unsafe)
        }
        private val DevelopmentKey = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".toByteArray())
    }
}

/** Bounded in-memory per-center throttle for operations that cause outbound CRM calls. */
class IntegrationRateLimiter(private val clock: Clock = Clock.systemUTC()) {
    private val attempts = ConcurrentHashMap<String, ArrayDeque<Instant>>()
    fun allow(key: String, maxAttempts: Int, window: Duration = Duration.ofMinutes(1)): Boolean {
        val now = clock.instant(); val values = attempts.computeIfAbsent(key) { ArrayDeque() }
        synchronized(values) {
            while (values.firstOrNull()?.isBefore(now.minus(window)) == true) values.removeFirst()
            if (values.size >= maxAttempts) return false
            values.addLast(now); return true
        }
    }
}
