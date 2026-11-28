package io.github.kvtttba.droplet.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Local DataStore persistence for WebDAV sync settings, sync status metadata,
 * and Android Keystore-encrypted credentials (WebDAV password & independent backup passphrase).
 */
interface WebDavSyncPreferencesRepository {
    val preferences: Flow<WebDavSyncPreferences>

    suspend fun currentPreferences(): WebDavSyncPreferences

    suspend fun saveConnectionConfig(input: WebDavConfigSaveInput)

    suspend fun saveBackupPassphrase(action: WebDavSecretUpdateAction)

    suspend fun setEncryptionEnabled(enabled: Boolean)

    suspend fun setAutoSyncEnabled(enabled: Boolean)

    suspend fun setDailyBackupEnabled(enabled: Boolean)

    suspend fun setWifiOnly(wifiOnly: Boolean)

    suspend fun setSyncAttachments(enabled: Boolean)

    suspend fun setRetentionCount(count: Int)

    suspend fun recordBackupOutcome(
        timestampMs: Long,
        success: Boolean,
        message: String,
        fileName: String? = null,
    )

    suspend fun recordRestoreSuccess(timestampMs: Long)

    suspend fun resolveCredentials(): WebDavResolvedCredentials?

    suspend fun decryptPassword(): String?

    suspend fun decryptBackupPassphrase(): String?

    suspend fun clearPassword()

    suspend fun clearBackupPassphrase()

    suspend fun clearAll()
}

class DataStoreWebDavSyncPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
    private val secretCipher: SecretCipher,
) : WebDavSyncPreferencesRepository {

    override val preferences: Flow<WebDavSyncPreferences> = rawPreferencesFlow()
        .map(::decodePreferences)
        .distinctUntilChanged()

    override suspend fun currentPreferences(): WebDavSyncPreferences = preferences.first()

    override suspend fun saveConnectionConfig(input: WebDavConfigSaveInput) {
        val trimmedUrl = input.serverUrl.trim()
        val normalizedUrl = if (trimmedUrl.isEmpty()) {
            ""
        } else {
            WebDavUrlValidator.validateServerUrl(trimmedUrl).getOrThrow()
        }
        val normalizedUsername = input.username.trim()
        val normalizedDir = WebDavUrlValidator.normalizeRemoteDirectory(input.remoteDirectory).getOrThrow()
        val encryptedPassword: String? = when (val action = input.passwordAction) {
            WebDavSecretUpdateAction.KeepExisting -> null
            WebDavSecretUpdateAction.Clear -> null
            is WebDavSecretUpdateAction.Replace -> {
                val rawValue = action.plaintextValue
                if (rawValue.isBlank()) {
                    null
                } else {
                    secretCipher.encrypt(
                        plaintext = rawValue,
                        associatedData = AAD_WEBDAV_PASSWORD,
                    )
                }
            }
        }

        dataStore.edit { prefs ->
            if (normalizedUrl.isEmpty()) {
                prefs.remove(Keys.SERVER_URL)
            } else {
                prefs[Keys.SERVER_URL] = normalizedUrl
            }
            if (normalizedUsername.isEmpty()) {
                prefs.remove(Keys.USERNAME)
            } else {
                prefs[Keys.USERNAME] = normalizedUsername
            }
            prefs[Keys.REMOTE_DIRECTORY] = normalizedDir

            when (input.passwordAction) {
                WebDavSecretUpdateAction.KeepExisting -> Unit
                WebDavSecretUpdateAction.Clear -> prefs.remove(Keys.ENCRYPTED_PASSWORD)
                is WebDavSecretUpdateAction.Replace -> {
                    if (encryptedPassword == null) {
                        prefs.remove(Keys.ENCRYPTED_PASSWORD)
                    } else {
                        prefs[Keys.ENCRYPTED_PASSWORD] = encryptedPassword
                    }
                }
            }
        }
    }

    override suspend fun saveBackupPassphrase(action: WebDavSecretUpdateAction) {
        val encryptedPassphrase: String? = when (action) {
            WebDavSecretUpdateAction.KeepExisting -> return
            WebDavSecretUpdateAction.Clear -> null
            is WebDavSecretUpdateAction.Replace -> {
                val rawValue = action.plaintextValue
                if (rawValue.isBlank()) {
                    null
                } else {
                    secretCipher.encrypt(
                        plaintext = rawValue,
                        associatedData = AAD_WEBDAV_BACKUP_PASSPHRASE,
                    )
                }
            }
        }

        dataStore.edit { prefs ->
            if (encryptedPassphrase == null) {
                prefs.remove(Keys.ENCRYPTED_PASSPHRASE)
            } else {
                prefs[Keys.ENCRYPTED_PASSPHRASE] = encryptedPassphrase
            }
        }
    }

    override suspend fun setEncryptionEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.ENCRYPTION_ENABLED] = enabled
        }
    }

    override suspend fun setAutoSyncEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTO_SYNC_ENABLED] = enabled
        }
    }

    override suspend fun setDailyBackupEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DAILY_BACKUP_ENABLED] = enabled
        }
    }

    override suspend fun setWifiOnly(wifiOnly: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.WIFI_ONLY] = wifiOnly
        }
    }

    override suspend fun setSyncAttachments(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SYNC_ATTACHMENTS] = enabled
        }
    }

    override suspend fun setRetentionCount(count: Int) {
        val safeCount = WebDavUrlValidator.sanitizeRetentionCount(count)
        dataStore.edit { prefs ->
            prefs[Keys.RETENTION_COUNT] = safeCount
        }
    }

    override suspend fun recordBackupOutcome(
        timestampMs: Long,
        success: Boolean,
        message: String,
        fileName: String?,
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_BACKUP_AT] = timestampMs
            prefs[Keys.LAST_BACKUP_SUCCESS] = success
            prefs[Keys.LAST_BACKUP_MESSAGE] = message.take(MAX_STATUS_MESSAGE_LEN)
            if (success && !fileName.isNullOrBlank()) {
                prefs[Keys.LAST_BACKUP_FILE_NAME] = fileName
            }
        }
    }

    override suspend fun recordRestoreSuccess(timestampMs: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_RESTORE_AT] = timestampMs
        }
    }

    override suspend fun resolveCredentials(): WebDavResolvedCredentials? {
        val prefs = rawPreferencesFlow().first()
        val decoded = decodePreferences(prefs)
        if (!decoded.isServerConfigured) return null
        val password = decryptSecret(prefs[Keys.ENCRYPTED_PASSWORD], AAD_WEBDAV_PASSWORD)
            ?.takeIf { it.isNotBlank() } ?: return null
        val passphrase = decryptSecret(prefs[Keys.ENCRYPTED_PASSPHRASE], AAD_WEBDAV_BACKUP_PASSPHRASE)
            ?.takeIf { it.isNotBlank() }
        return WebDavResolvedCredentials(
            serverUrl = decoded.serverUrl,
            username = decoded.username,
            password = password,
            remoteDirectory = decoded.remoteDirectory,
            encryptionEnabled = decoded.encryptionEnabled,
            backupPassphrase = passphrase,
            syncAttachments = decoded.syncAttachments,
            retentionCount = decoded.retentionCount,
        )
    }

    override suspend fun decryptPassword(): String? {
        val prefs = rawPreferencesFlow().first()
        return decryptSecret(prefs[Keys.ENCRYPTED_PASSWORD], AAD_WEBDAV_PASSWORD)
            ?.takeIf { it.isNotBlank() }
    }

    override suspend fun decryptBackupPassphrase(): String? {
        val prefs = rawPreferencesFlow().first()
        return decryptSecret(prefs[Keys.ENCRYPTED_PASSPHRASE], AAD_WEBDAV_BACKUP_PASSPHRASE)
            ?.takeIf { it.isNotBlank() }
    }

    override suspend fun clearPassword() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.ENCRYPTED_PASSWORD)
        }
    }

    override suspend fun clearBackupPassphrase() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.ENCRYPTED_PASSPHRASE)
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private fun rawPreferencesFlow(): Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    private fun decodePreferences(prefs: Preferences): WebDavSyncPreferences {
        val rawUrl = prefs[Keys.SERVER_URL]?.trim().orEmpty()
        val safeUrl = if (rawUrl.isEmpty()) {
            ""
        } else {
            WebDavUrlValidator.validateServerUrl(rawUrl).getOrDefault("")
        }
        val username = prefs[Keys.USERNAME]?.trim().orEmpty()
        val remoteDir = WebDavUrlValidator.normalizeRemoteDirectory(
            prefs[Keys.REMOTE_DIRECTORY] ?: WebDavUrlValidator.DEFAULT_REMOTE_DIRECTORY,
        ).getOrDefault(WebDavUrlValidator.DEFAULT_REMOTE_DIRECTORY)

        val passwordState = decodeSecretMetadata(
            encrypted = prefs[Keys.ENCRYPTED_PASSWORD],
            aad = AAD_WEBDAV_PASSWORD,
        )
        val passphraseState = decodeSecretMetadata(
            encrypted = prefs[Keys.ENCRYPTED_PASSPHRASE],
            aad = AAD_WEBDAV_BACKUP_PASSPHRASE,
        )

        val retention = WebDavUrlValidator.sanitizeRetentionCount(
            prefs[Keys.RETENTION_COUNT] ?: WebDavUrlValidator.DEFAULT_RETENTION_COUNT,
        )

        return WebDavSyncPreferences(
            serverUrl = safeUrl,
            username = username,
            remoteDirectory = remoteDir,
            hasPassword = passwordState.hasValue,
            passwordMasked = passwordState.maskedPreview,
            isPasswordCorrupted = passwordState.isCorrupted,
            encryptionEnabled = prefs[Keys.ENCRYPTION_ENABLED] ?: true,
            hasPassphrase = passphraseState.hasValue,
            passphraseMasked = passphraseState.maskedPreview,
            isPassphraseCorrupted = passphraseState.isCorrupted,
            autoSyncEnabled = prefs[Keys.AUTO_SYNC_ENABLED] ?: false,
            dailyBackupEnabled = prefs[Keys.DAILY_BACKUP_ENABLED] ?: false,
            wifiOnly = prefs[Keys.WIFI_ONLY] ?: false,
            syncAttachments = prefs[Keys.SYNC_ATTACHMENTS] ?: false,
            retentionCount = retention,
            lastBackupAt = prefs[Keys.LAST_BACKUP_AT],
            lastBackupSuccess = prefs[Keys.LAST_BACKUP_SUCCESS],
            lastBackupMessage = prefs[Keys.LAST_BACKUP_MESSAGE],
            lastBackupFileName = prefs[Keys.LAST_BACKUP_FILE_NAME],
            lastRestoreAt = prefs[Keys.LAST_RESTORE_AT],
        )
    }

    private data class DecodedSecretState(
        val hasValue: Boolean,
        val maskedPreview: String,
        val isCorrupted: Boolean,
    )

    private fun decodeSecretMetadata(
        encrypted: String?,
        aad: String,
    ): DecodedSecretState {
        val trimmed = encrypted?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return DecodedSecretState(hasValue = false, maskedPreview = "", isCorrupted = false)
        }
        return try {
            val decrypted = secretCipher.decrypt(trimmed, associatedData = aad)
            if (decrypted.isBlank()) {
                DecodedSecretState(hasValue = false, maskedPreview = "", isCorrupted = false)
            } else {
                DecodedSecretState(
                    hasValue = true,
                    maskedPreview = AiSecretMasker.mask(decrypted),
                    isCorrupted = false,
                )
            }
        } catch (_: SecretCipherException) {
            DecodedSecretState(hasValue = false, maskedPreview = "", isCorrupted = true)
        }
    }

    private fun decryptSecret(
        encrypted: String?,
        aad: String,
    ): String? {
        val trimmed = encrypted?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            secretCipher.decrypt(trimmed, associatedData = aad)
        } catch (_: SecretCipherException) {
            null
        }
    }

    internal object Keys {
        val SERVER_URL = stringPreferencesKey("webdav_server_url")
        val USERNAME = stringPreferencesKey("webdav_username")
        val REMOTE_DIRECTORY = stringPreferencesKey("webdav_remote_directory")
        val ENCRYPTED_PASSWORD = stringPreferencesKey("webdav_password_enc")
        val ENCRYPTION_ENABLED = booleanPreferencesKey("webdav_encryption_enabled")
        val ENCRYPTED_PASSPHRASE = stringPreferencesKey("webdav_backup_passphrase_enc")
        val AUTO_SYNC_ENABLED = booleanPreferencesKey("webdav_auto_sync_enabled")
        val DAILY_BACKUP_ENABLED = booleanPreferencesKey("webdav_daily_backup_enabled")
        val WIFI_ONLY = booleanPreferencesKey("webdav_wifi_only")
        val SYNC_ATTACHMENTS = booleanPreferencesKey("webdav_sync_attachments")
        val RETENTION_COUNT = intPreferencesKey("webdav_retention_count")
        val LAST_BACKUP_AT = longPreferencesKey("webdav_last_backup_at")
        val LAST_BACKUP_SUCCESS = booleanPreferencesKey("webdav_last_backup_success")
        val LAST_BACKUP_MESSAGE = stringPreferencesKey("webdav_last_backup_message")
        val LAST_BACKUP_FILE_NAME = stringPreferencesKey("webdav_last_backup_file_name")
        val LAST_RESTORE_AT = longPreferencesKey("webdav_last_restore_at")
    }

    companion object {
        const val AAD_WEBDAV_PASSWORD: String = "webdav_password"
        const val AAD_WEBDAV_BACKUP_PASSPHRASE: String = "webdav_backup_passphrase"
        private const val MAX_STATUS_MESSAGE_LEN: Int = 240
    }
}
