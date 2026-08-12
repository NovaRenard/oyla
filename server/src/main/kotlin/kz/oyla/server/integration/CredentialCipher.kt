package kz.oyla.server.integration

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

data class EncryptedCredential(val ciphertext: String, val nonce: String, val keyVersion: String = "v1")

/** AES-256-GCM envelope used only in the server process immediately before an outbound CRM request. */
class CredentialCipher private constructor(private val key: ByteArray, private val random: SecureRandom = SecureRandom()) {
    fun encrypt(plaintext: String): EncryptedCredential {
        require(plaintext.isNotBlank()) { "Integration credential must not be blank" }
        val nonce = ByteArray(NonceBytes).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), GCMParameterSpec(TagBits, nonce))
        return EncryptedCredential(Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))), Base64.getEncoder().encodeToString(nonce))
    }

    fun decrypt(encrypted: EncryptedCredential): String {
        val nonce = runCatching { Base64.getDecoder().decode(encrypted.nonce) }.getOrElse { throw CredentialDecryptionException() }
        val ciphertext = runCatching { Base64.getDecoder().decode(encrypted.ciphertext) }.getOrElse { throw CredentialDecryptionException() }
        if (nonce.size != NonceBytes) throw CredentialDecryptionException()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), GCMParameterSpec(TagBits, nonce))
            cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
        } catch (_: Exception) { throw CredentialDecryptionException() }
    }

    companion object {
        private const val NonceBytes = 12
        private const val TagBits = 128
        fun fromSecret(value: String): CredentialCipher {
            val trimmed = value.trim()
            val bytes = runCatching { Base64.getDecoder().decode(trimmed) }.getOrNull()?.takeIf { it.size == 32 }
                ?: trimmed.toByteArray(StandardCharsets.UTF_8).takeIf { it.size == 32 }
                ?: throw IllegalArgumentException("INTEGRATION_CREDENTIAL_ENCRYPTION_KEY must be a base64-encoded 32-byte AES key")
            return CredentialCipher(bytes.copyOf())
        }
    }
}

class CredentialDecryptionException : RuntimeException()
