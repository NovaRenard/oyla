package kz.oyla.app.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kz.oyla.app.domain.model.DeviceRole
import kz.oyla.app.util.PinHasher

private const val PreferencesName = "oyla_device_preferences"
private const val PinLockDurationMillis = 30_000L
private const val MaxPinAttempts = 5

private val Context.deviceDataStore by preferencesDataStore(name = PreferencesName)

data class DeviceSetup(
    val role: DeviceRole?,
    val hasPin: Boolean
)

data class ActiveSession(
    val sessionId: String,
    val sessionToken: String,
    val role: DeviceRole,
    val connectionCode: String?,
    val specialistName: String? = null
)

interface SessionStorage {
    suspend fun getOrCreateDeviceId(): String
    suspend fun saveActiveSession(session: ActiveSession)
    suspend fun getActiveSession(): ActiveSession?
    suspend fun clearActiveSession()
}

interface LastSpecialistStorage {
    suspend fun getLastSpecialistId(): String?
    suspend fun saveLastSpecialistId(id: String)
}

sealed interface PinVerificationResult {
    data object Success : PinVerificationResult
    data object Incorrect : PinVerificationResult
    data class Locked(val remainingMillis: Long) : PinVerificationResult
}

class DevicePreferences(context: Context) : SessionStorage, DeviceIdentityStorage, LastSpecialistStorage {
    private val dataStore = context.applicationContext.deviceDataStore
    private val tokenCipher = DeviceTokenCipher()

    val setupFlow: Flow<DeviceSetup> = dataStore.data.map { preferences ->
        DeviceSetup(
            role = preferences[RoleKey]?.let(::parseRole),
            hasPin = preferences[PinHashKey] != null
        )
    }

    suspend fun saveRole(role: DeviceRole) {
        dataStore.edit { preferences -> preferences[RoleKey] = role.name }
    }

    suspend fun clearRole() {
        dataStore.edit { preferences -> preferences.remove(RoleKey) }
    }

    suspend fun hasPin(): Boolean = dataStore.data.first()[PinHashKey] != null

    suspend fun savePin(pin: String) {
        dataStore.edit { preferences ->
            val salt = preferences[PinSaltKey] ?: generateSalt().also { preferences[PinSaltKey] = it }
            preferences[PinHashKey] = PinHasher.hash(pin, salt)
            preferences.remove(FailedPinAttemptsKey)
            preferences.remove(PinLockedUntilKey)
        }
    }

    suspend fun verifyPin(pin: String, nowMillis: Long = System.currentTimeMillis()): PinVerificationResult {
        val current = dataStore.data.first()
        val lockedUntil = current[PinLockedUntilKey] ?: 0L
        if (lockedUntil > nowMillis) {
            return PinVerificationResult.Locked(lockedUntil - nowMillis)
        }

        val storedHash = current[PinHashKey]
        val salt = current[PinSaltKey]
        val matches = storedHash != null && salt != null && PinHasher.matches(pin, salt, storedHash)
        if (matches) {
            dataStore.edit { preferences ->
                preferences.remove(FailedPinAttemptsKey)
                preferences.remove(PinLockedUntilKey)
            }
            return PinVerificationResult.Success
        }

        var result: PinVerificationResult = PinVerificationResult.Incorrect
        dataStore.edit { preferences ->
            val attempts = (preferences[FailedPinAttemptsKey] ?: 0) + 1
            if (attempts >= MaxPinAttempts) {
                val lockEnd = nowMillis + PinLockDurationMillis
                preferences[PinLockedUntilKey] = lockEnd
                preferences[FailedPinAttemptsKey] = 0
                result = PinVerificationResult.Locked(PinLockDurationMillis)
            } else {
                preferences[FailedPinAttemptsKey] = attempts
            }
        }
        return result
    }

    suspend fun remainingPinLockMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val lockedUntil = dataStore.data.first()[PinLockedUntilKey] ?: return 0L
        return (lockedUntil - nowMillis).coerceAtLeast(0L)
    }

    override suspend fun getOrCreateDeviceId(): String = getDeviceIdentity()?.deviceId ?: getOrCreateDeviceUid()

    override suspend fun getOrCreateDeviceUid(): String {
        var deviceUid = ""
        dataStore.edit { preferences ->
            deviceUid = preferences[DeviceUidKey] ?: preferences[LegacyDeviceIdKey] ?: UUID.randomUUID().toString().also {
                preferences[DeviceUidKey] = it
            }
        }
        return deviceUid
    }

    override suspend fun getDeviceIdentity(): DeviceIdentity? {
        val values = dataStore.data.first()
        val token = tokenCipher.decrypt(values[DeviceTokenCiphertextKey], values[DeviceTokenIvKey]) ?: return null
        val deviceId = values[BackendDeviceIdKey] ?: return null
        val centerId = values[CenterIdKey] ?: return null
        val centerName = values[CenterNameKey] ?: return null
        val deviceName = values[ActivatedDeviceNameKey] ?: return null
        val role = values[ActivatedDeviceRoleKey]?.let(::parseRole) ?: return null
        val uid = values[DeviceUidKey] ?: values[LegacyDeviceIdKey] ?: return null
        val activationState = values[ActivationStateKey]?.let { runCatching { DeviceActivationState.valueOf(it) }.getOrNull() }
            ?: DeviceActivationState.ACTIVATED
        return DeviceIdentity(deviceId, centerId, centerName, deviceName, role, token, uid, activationState)
    }

    override suspend fun saveDeviceIdentity(identity: DeviceIdentity) {
        val encrypted = tokenCipher.encrypt(identity.deviceToken)
        dataStore.edit { preferences ->
            preferences[BackendDeviceIdKey] = identity.deviceId
            preferences[CenterIdKey] = identity.centerId
            preferences[CenterNameKey] = identity.centerName
            preferences[ActivatedDeviceNameKey] = identity.deviceName
            preferences[ActivatedDeviceRoleKey] = identity.deviceRole.name
            preferences[DeviceUidKey] = identity.deviceUid
            preferences[DeviceTokenCiphertextKey] = encrypted.ciphertext
            preferences[DeviceTokenIvKey] = encrypted.iv
            preferences[ActivationStateKey] = identity.activationState.name
            // Existing session screens use this setup value. It is only a compatibility mirror;
            // server-confirmed DeviceIdentity remains the source of truth for startup.
            preferences[RoleKey] = identity.deviceRole.name
        }
    }

    override suspend fun clearDeviceIdentity() {
        dataStore.edit { preferences ->
            preferences.remove(BackendDeviceIdKey)
            preferences.remove(CenterIdKey)
            preferences.remove(CenterNameKey)
            preferences.remove(ActivatedDeviceNameKey)
            preferences.remove(ActivatedDeviceRoleKey)
            preferences.remove(DeviceTokenCiphertextKey)
            preferences.remove(DeviceTokenIvKey)
            preferences.remove(ActivationStateKey)
            preferences.remove(RoleKey)
            preferences.remove(ActiveSessionIdKey)
            preferences.remove(ActiveSessionTokenKey)
            preferences.remove(ActiveSessionRoleKey)
            preferences.remove(ActiveSessionCodeKey)
            preferences.remove(ActiveSessionSpecialistNameKey)
            preferences.remove(LastSpecialistIdKey)
        }
    }

    override suspend fun saveActiveSession(session: ActiveSession) {
        dataStore.edit { preferences ->
            preferences[ActiveSessionIdKey] = session.sessionId
            preferences[ActiveSessionTokenKey] = session.sessionToken
            preferences[ActiveSessionRoleKey] = session.role.name
            if (session.specialistName == null) preferences.remove(ActiveSessionSpecialistNameKey) else preferences[ActiveSessionSpecialistNameKey] = session.specialistName
            if (session.connectionCode == null) {
                preferences.remove(ActiveSessionCodeKey)
            } else {
                preferences[ActiveSessionCodeKey] = session.connectionCode
            }
        }
    }

    override suspend fun getActiveSession(): ActiveSession? {
        val values = dataStore.data.first()
        val sessionId = values[ActiveSessionIdKey] ?: return null
        val token = values[ActiveSessionTokenKey] ?: return null
        val role = values[ActiveSessionRoleKey]?.let(::parseRole) ?: return null
        return ActiveSession(
            sessionId = sessionId,
            sessionToken = token,
            role = role,
            connectionCode = values[ActiveSessionCodeKey],
            specialistName = values[ActiveSessionSpecialistNameKey]
        )
    }

    override suspend fun clearActiveSession() {
        dataStore.edit { preferences ->
            preferences.remove(ActiveSessionIdKey)
            preferences.remove(ActiveSessionTokenKey)
            preferences.remove(ActiveSessionRoleKey)
            preferences.remove(ActiveSessionCodeKey)
            preferences.remove(ActiveSessionSpecialistNameKey)
        }
    }

    override suspend fun getLastSpecialistId(): String? = dataStore.data.first()[LastSpecialistIdKey]

    override suspend fun saveLastSpecialistId(id: String) {
        dataStore.edit { preferences -> preferences[LastSpecialistIdKey] = id }
    }

    private fun parseRole(value: String): DeviceRole? = runCatching {
        DeviceRole.valueOf(value)
    }.getOrNull()

    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        val RoleKey: Preferences.Key<String> = stringPreferencesKey("device_role")
        val PinHashKey: Preferences.Key<String> = stringPreferencesKey("specialist_pin_hash")
        val PinSaltKey: Preferences.Key<String> = stringPreferencesKey("specialist_pin_salt")
        val FailedPinAttemptsKey: Preferences.Key<Int> = intPreferencesKey("failed_pin_attempts")
        val PinLockedUntilKey: Preferences.Key<Long> = longPreferencesKey("pin_locked_until")
        val LegacyDeviceIdKey: Preferences.Key<String> = stringPreferencesKey("device_id")
        val DeviceUidKey: Preferences.Key<String> = stringPreferencesKey("device_uid")
        val BackendDeviceIdKey: Preferences.Key<String> = stringPreferencesKey("activated_device_id")
        val CenterIdKey: Preferences.Key<String> = stringPreferencesKey("center_id")
        val CenterNameKey: Preferences.Key<String> = stringPreferencesKey("center_name")
        val ActivatedDeviceNameKey: Preferences.Key<String> = stringPreferencesKey("activated_device_name")
        val ActivatedDeviceRoleKey: Preferences.Key<String> = stringPreferencesKey("activated_device_role")
        val DeviceTokenCiphertextKey: Preferences.Key<String> = stringPreferencesKey("device_token_ciphertext")
        val DeviceTokenIvKey: Preferences.Key<String> = stringPreferencesKey("device_token_iv")
        val ActivationStateKey: Preferences.Key<String> = stringPreferencesKey("activation_state")
        val ActiveSessionIdKey: Preferences.Key<String> = stringPreferencesKey("active_session_id")
        val ActiveSessionTokenKey: Preferences.Key<String> = stringPreferencesKey("active_session_token")
        val ActiveSessionRoleKey: Preferences.Key<String> = stringPreferencesKey("active_session_role")
        val ActiveSessionCodeKey: Preferences.Key<String> = stringPreferencesKey("active_session_connection_code")
        val ActiveSessionSpecialistNameKey: Preferences.Key<String> = stringPreferencesKey("active_session_specialist_name")
        val LastSpecialistIdKey: Preferences.Key<String> = stringPreferencesKey("last_specialist_id")
    }
}

private data class EncryptedToken(val ciphertext: String, val iv: String)

/** Uses Android Keystore; DataStore only ever receives AES-GCM ciphertext and IV. */
private class DeviceTokenCipher {
    fun encrypt(value: String): EncryptedToken {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return EncryptedToken(
            ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8))),
            iv = Base64.getEncoder().encodeToString(cipher.iv)
        )
    }

    fun decrypt(ciphertext: String?, iv: String?): String? = runCatching {
        if (ciphertext.isNullOrBlank() || iv.isNullOrBlank()) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
        String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KeyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KeyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object { const val KeyAlias = "oyla_device_token_v1" }
}
