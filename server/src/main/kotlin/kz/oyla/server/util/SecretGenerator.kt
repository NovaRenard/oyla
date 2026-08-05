package kz.oyla.server.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SecretGenerator(
    private val random: SecureRandom = SecureRandom(),
    private val pepper: String = System.getenv("OYLA_SECRET_PEPPER") ?: "development-only-change-me"
) {
    private val activationAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun nextOpaqueToken(bytesCount: Int = 48): String {
        val bytes = ByteArray(bytesCount)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun nextActivationCode(length: Int = 8): String = buildString(length) {
        repeat(length) { append(activationAlphabet[random.nextInt(activationAlphabet.length)]) }
    }

    fun normalizeActivationCode(value: String): String = value
        .uppercase()
        .filter { it in activationAlphabet }

    /** HMAC prevents offline enumeration of short activation codes if a database is leaked. */
    fun secretHash(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pepper.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(StandardCharsets.UTF_8), right.toByteArray(StandardCharsets.UTF_8))
}
