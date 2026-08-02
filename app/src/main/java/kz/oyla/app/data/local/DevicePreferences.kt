package kz.oyla.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.SecureRandom
import java.util.Base64
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

sealed interface PinVerificationResult {
    data object Success : PinVerificationResult
    data object Incorrect : PinVerificationResult
    data class Locked(val remainingMillis: Long) : PinVerificationResult
}

class DevicePreferences(context: Context) {
    private val dataStore = context.applicationContext.deviceDataStore

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
    }
}
