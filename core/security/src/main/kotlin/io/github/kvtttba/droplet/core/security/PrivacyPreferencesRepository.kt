package io.github.kvtttba.droplet.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Persisted repository for Droplet security, privacy, and appearance preferences.
 */
interface PrivacyPreferencesRepository {
    val preferences: Flow<PrivacyPreferences>

    suspend fun setBiometricLockEnabled(enabled: Boolean)

    suspend fun setRelockTimeout(timeout: RelockTimeout)

    suspend fun setFlagSecureEnabled(enabled: Boolean)

    suspend fun setAmountsHidden(hidden: Boolean)

    suspend fun toggleAmountsHidden(): Boolean

    suspend fun setThemeMode(themeMode: AppThemePreference)

    suspend fun setDynamicColor(enabled: Boolean)
}

class DataStorePrivacyPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : PrivacyPreferencesRepository {

    override val preferences: Flow<PrivacyPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs ->
            PrivacyPreferences(
                biometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK_ENABLED] ?: false,
                relockTimeout = RelockTimeout.fromStorageKey(prefs[Keys.RELOCK_TIMEOUT]),
                flagSecureEnabled = prefs[Keys.FLAG_SECURE_ENABLED] ?: false,
                amountsHidden = prefs[Keys.AMOUNTS_HIDDEN] ?: false,
                themeMode = AppThemePreference.fromStorageKey(prefs[Keys.THEME_MODE]),
                dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            )
        }
        .distinctUntilChanged()

    override suspend fun setBiometricLockEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.BIOMETRIC_LOCK_ENABLED] = enabled
        }
    }

    override suspend fun setRelockTimeout(timeout: RelockTimeout) {
        dataStore.edit { prefs ->
            prefs[Keys.RELOCK_TIMEOUT] = timeout.storageKey
        }
    }

    override suspend fun setFlagSecureEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.FLAG_SECURE_ENABLED] = enabled
        }
    }

    override suspend fun setAmountsHidden(hidden: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AMOUNTS_HIDDEN] = hidden
        }
    }

    override suspend fun toggleAmountsHidden(): Boolean {
        var updatedValue = false
        dataStore.edit { prefs ->
            val current = prefs[Keys.AMOUNTS_HIDDEN] ?: false
            updatedValue = !current
            prefs[Keys.AMOUNTS_HIDDEN] = updatedValue
        }
        return updatedValue
    }

    override suspend fun setThemeMode(themeMode: AppThemePreference) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = themeMode.storageKey
        }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DYNAMIC_COLOR] = enabled
        }
    }

    internal object Keys {
        val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        val RELOCK_TIMEOUT = stringPreferencesKey("relock_timeout")
        val FLAG_SECURE_ENABLED = booleanPreferencesKey("flag_secure_enabled")
        val AMOUNTS_HIDDEN = booleanPreferencesKey("amounts_hidden")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }
}
