package kz.oyla.server.util

import java.security.SecureRandom
import java.util.Base64

class CodeGenerator(private val random: SecureRandom = SecureRandom()) {
    fun nextConnectionCode(): String = "%04d".format(random.nextInt(10_000))

    fun nextAccessToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
