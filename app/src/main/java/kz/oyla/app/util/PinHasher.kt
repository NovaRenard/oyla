package kz.oyla.app.util

import java.security.MessageDigest

object PinHasher {
    private const val Algorithm = "SHA-256"

    fun hash(pin: String, salt: String): String {
        val bytes = MessageDigest.getInstance(Algorithm)
            .digest("$salt:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun matches(pin: String, salt: String, expectedHash: String): Boolean =
        MessageDigest.isEqual(
            hash(pin, salt).toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8)
        )
}
