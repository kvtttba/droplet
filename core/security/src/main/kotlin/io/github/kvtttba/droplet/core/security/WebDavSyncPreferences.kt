package io.github.kvtttba.droplet.core.security

import java.net.URI

/**
 * Validation & normalization helpers for WebDAV server URLs and remote backup directory paths.
 * Strictly blocks embedded credentials in URLs and path traversal sequences.
 */
object WebDavUrlValidator {
    const val DEFAULT_REMOTE_DIRECTORY: String = "droplet-backups"
    const val DEFAULT_RETENTION_COUNT: Int = 10
    const val MIN_RETENTION_COUNT: Int = 1
    const val MAX_RETENTION_COUNT: Int = 50
    val RETENTION_PRESETS: List<Int> = listOf(5, 10, 20, 30)

    fun validateServerUrl(raw: String): Result<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入 WebDAV 服务器地址"))
        }
        if (trimmed.any { it.isWhitespace() }) {
            return Result.failure(IllegalArgumentException("WebDAV 服务器地址不能包含空白字符"))
        }
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("WebDAV 服务器地址格式无效"))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.failure(IllegalArgumentException("WebDAV 地址必须以 https:// 或 http:// 开头"))
        }
        if (!uri.userInfo.isNullOrEmpty() || trimmed.substringAfter("://").substringBefore("/").contains("@")) {
            return Result.failure(IllegalArgumentException("请勿在服务器地址中内嵌账号密码，请在下方表单单独填写"))
        }
        if (uri.host.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("WebDAV 服务器地址缺少有效主机名"))
        }
        if (!uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) {
            return Result.failure(IllegalArgumentException("WebDAV 服务器地址无需包含查询参数或锚点"))
        }
        return Result.success(trimmed.trimEnd('/'))
    }

    fun normalizeRemoteDirectory(raw: String): Result<String> {
        val trimmed = raw.trim().replace('\\', '/')
        if (trimmed.isEmpty()) {
            return Result.success(DEFAULT_REMOTE_DIRECTORY)
        }
        if (trimmed.any { it.code < 0x20 || it == '\u007F' }) {
            return Result.failure(IllegalArgumentException("远程备份目录包含非法控制字符"))
        }
        val segments = trimmed.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return Result.success(DEFAULT_REMOTE_DIRECTORY)
        }
        for (segment in segments) {
            if (segment == "." || segment == ".." || segment.contains("..")) {
                return Result.failure(IllegalArgumentException("远程备份目录禁止包含路径穿越段（..）"))
            }
            if (segment.any { it == '?' || it == '#' || it == '%' || it == ':' }) {
                return Result.failure(IllegalArgumentException("远程备份目录名称包含不支持的字符"))
            }
        }
        return Result.success(segments.joinToString("/"))
    }

    fun sanitizeRetentionCount(raw: Int): Int =
        raw.coerceIn(MIN_RETENTION_COUNT, MAX_RETENTION_COUNT)
}

/**
 * Explicit update action for locally Keystore-encrypted WebDAV password or backup passphrase.
 * [toString] is strictly redacted.
 */
sealed interface WebDavSecretUpdateAction {
    data object KeepExisting : WebDavSecretUpdateAction

    data object Clear : WebDavSecretUpdateAction

    class Replace(val plaintextValue: String) : WebDavSecretUpdateAction {
        override fun equals(other: Any?): Boolean =
            other is Replace && plaintextValue == other.plaintextValue

        override fun hashCode(): Int = plaintextValue.hashCode()

        override fun toString(): String = "Replace(plaintextValue=[REDACTED])"
    }
}

/**
 * Input payload for saving WebDAV server connection settings.
 */
data class WebDavConfigSaveInput(
    val serverUrl: String,
    val username: String,
    val remoteDirectory: String = WebDavUrlValidator.DEFAULT_REMOTE_DIRECTORY,
    val passwordAction: WebDavSecretUpdateAction = WebDavSecretUpdateAction.KeepExisting,
) {
    override fun toString(): String =
        "WebDavConfigSaveInput(serverUrl=$serverUrl, username=$username, " +
            "remoteDirectory=$remoteDirectory, passwordAction=$passwordAction)"
}

/**
 * Public, redacted metadata view of WebDAV backup & sync preferences.
 * Plaintext passwords and encryption passphrases never enter this object.
 */
data class WebDavSyncPreferences(
    val serverUrl: String = "",
    val username: String = "",
    val remoteDirectory: String = WebDavUrlValidator.DEFAULT_REMOTE_DIRECTORY,
    val hasPassword: Boolean = false,
    val passwordMasked: String = "",
    val isPasswordCorrupted: Boolean = false,
    val encryptionEnabled: Boolean = true,
    val hasPassphrase: Boolean = false,
    val passphraseMasked: String = "",
    val isPassphraseCorrupted: Boolean = false,
    val autoSyncEnabled: Boolean = false,
    val dailyBackupEnabled: Boolean = false,
    val wifiOnly: Boolean = false,
    val syncAttachments: Boolean = false,
    val retentionCount: Int = WebDavUrlValidator.DEFAULT_RETENTION_COUNT,
    val lastBackupAt: Long? = null,
    val lastBackupSuccess: Boolean? = null,
    val lastBackupMessage: String? = null,
    val lastBackupFileName: String? = null,
    val lastRestoreAt: Long? = null,
) {
    val isServerConfigured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && hasPassword && !isPasswordCorrupted

    val isEncryptionReady: Boolean
        get() = !encryptionEnabled || (hasPassphrase && !isPassphraseCorrupted)

    val isReadyForBackup: Boolean
        get() = isServerConfigured && isEncryptionReady

    val isHttpWithoutTls: Boolean
        get() = serverUrl.startsWith("http://", ignoreCase = true)

    fun formatSummary(): String {
        if (!isServerConfigured) {
            return "未配置 · 点击设置加密快照备份"
        }
        val parts = mutableListOf<String>()
        parts += when {
            encryptionEnabled && hasPassphrase && !isPassphraseCorrupted -> "DRP1 加密"
            encryptionEnabled -> "待设加密口令"
            else -> "明文 ZIP"
        }
        when {
            autoSyncEnabled && dailyBackupEnabled -> parts += "自动+每日同步"
            autoSyncEnabled -> parts += "变更自动同步"
            dailyBackupEnabled -> parts += "每日定时备份"
            else -> parts += "手动备份"
        }
        parts += "保留 $retentionCount 份"
        return parts.joinToString(" · ")
    }

    override fun toString(): String =
        "WebDavSyncPreferences(serverUrl=$serverUrl, username=$username, remoteDirectory=$remoteDirectory, " +
            "hasPassword=$hasPassword, isPasswordCorrupted=$isPasswordCorrupted, " +
            "encryptionEnabled=$encryptionEnabled, hasPassphrase=$hasPassphrase, " +
            "isPassphraseCorrupted=$isPassphraseCorrupted, autoSyncEnabled=$autoSyncEnabled, " +
            "dailyBackupEnabled=$dailyBackupEnabled, wifiOnly=$wifiOnly, " +
            "syncAttachments=$syncAttachments, retentionCount=$retentionCount, " +
            "lastBackupAt=$lastBackupAt, lastBackupSuccess=$lastBackupSuccess, " +
            "lastBackupFileName=$lastBackupFileName, lastRestoreAt=$lastRestoreAt, secrets=[REDACTED])"
}

/**
 * In-memory decrypted credentials resolved strictly for an active WebDAV backup/restore call.
 * Never persisted or logged.
 */
class WebDavResolvedCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
    val remoteDirectory: String,
    val encryptionEnabled: Boolean,
    val backupPassphrase: String?,
    val syncAttachments: Boolean,
    val retentionCount: Int,
) {
    init {
        require(serverUrl.isNotBlank()) { "WebDAV serverUrl must not be blank" }
        require(username.isNotBlank()) { "WebDAV username must not be blank" }
        require(password.isNotBlank()) { "WebDAV password must not be blank" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebDavResolvedCredentials) return false
        return serverUrl == other.serverUrl &&
            username == other.username &&
            password == other.password &&
            remoteDirectory == other.remoteDirectory &&
            encryptionEnabled == other.encryptionEnabled &&
            backupPassphrase == other.backupPassphrase &&
            syncAttachments == other.syncAttachments &&
            retentionCount == other.retentionCount
    }

    override fun hashCode(): Int {
        var result = serverUrl.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + password.hashCode()
        result = 31 * result + remoteDirectory.hashCode()
        result = 31 * result + encryptionEnabled.hashCode()
        result = 31 * result + (backupPassphrase?.hashCode() ?: 0)
        result = 31 * result + syncAttachments.hashCode()
        result = 31 * result + retentionCount
        return result
    }

    override fun toString(): String =
        "WebDavResolvedCredentials(serverUrl=$serverUrl, username=$username, " +
            "remoteDirectory=$remoteDirectory, encryptionEnabled=$encryptionEnabled, " +
            "hasBackupPassphrase=${!backupPassphrase.isNullOrEmpty()}, syncAttachments=$syncAttachments, " +
            "retentionCount=$retentionCount, password=[REDACTED], backupPassphrase=[REDACTED])"
}
