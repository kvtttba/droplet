package io.github.kvtttba.droplet.core.sync

import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.security.BackupCryptoException
import io.github.kvtttba.droplet.core.security.WebDavResolvedCredentials
import io.github.kvtttba.droplet.core.security.WebDavSyncPreferencesRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BackupTrigger(val label: String) {
    MANUAL("手动备份"),
    DEBOUNCED_AUTO("自动防抖上传"),
    DAILY_PERIODIC("每日定时备份"),
}

data class BackupExecutionResult(
    val success: Boolean,
    val fileName: String? = null,
    val sizeBytes: Long = 0L,
    val encrypted: Boolean = true,
    val prunedFileNames: List<String> = emptyList(),
    val isTransientFailure: Boolean = false,
    val message: String,
)

/**
 * High-level orchestrator coordinating snapshot creation, DRP1 encryption, WebDAV upload/pruning,
 * remote snapshot discovery, and safe rollback-protected database restoration.
 */
interface WebDavBackupOrchestrator {
    suspend fun testConnection(overrideParams: WebDavConnectionParams? = null): WebDavConnectionTestResult

    suspend fun backupNow(trigger: BackupTrigger = BackupTrigger.MANUAL): BackupExecutionResult

    suspend fun listRemoteSnapshots(): Result<List<RemoteBackupSnapshot>>

    suspend fun restoreRemoteSnapshot(
        fileName: String,
        passphraseOverride: String? = null,
    ): RestoreOutcome

    suspend fun deleteRemoteSnapshot(fileName: String): Result<Unit>

    suspend fun createLocalSafetySnapshot(): Result<LocalRollbackSnapshot>

    suspend fun listLocalRollbackSnapshots(): List<LocalRollbackSnapshot>

    suspend fun restoreLocalRollbackSnapshot(fileName: String): RestoreOutcome

    suspend fun deleteLocalRollbackSnapshot(fileName: String): Boolean
}

class DefaultWebDavBackupOrchestrator(
    private val database: DropletDatabase,
    private val filesRootDir: File,
    private val preferencesRepository: WebDavSyncPreferencesRepository,
    private val webDavClient: WebDavClient,
    private val archiveCodec: BackupArchiveCodec,
    private val restoreManager: SafeDatabaseRestoreManager,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WebDavBackupOrchestrator {

    override suspend fun testConnection(
        overrideParams: WebDavConnectionParams?,
    ): WebDavConnectionTestResult {
        val params = overrideParams ?: resolveConnectionParams()
            ?: return WebDavConnectionTestResult(
                success = false,
                normalizedEndpointUrl = "",
                message = "请先填写完整的 WebDAV 服务器地址、用户名和密码",
            )
        return webDavClient.testConnection(params)
    }

    override suspend fun backupNow(trigger: BackupTrigger): BackupExecutionResult = withContext(ioDispatcher) {
        val nowMs = clock()
        val creds = preferencesRepository.resolveCredentials()
        if (creds == null) {
            val msg = "WebDAV 服务器或凭据未配置完成，无法执行备份"
            preferencesRepository.recordBackupOutcome(
                timestampMs = nowMs,
                success = false,
                message = msg,
            )
            return@withContext BackupExecutionResult(
                success = false,
                isTransientFailure = false,
                message = msg,
            )
        }
        if (creds.encryptionEnabled && creds.backupPassphrase.isNullOrBlank()) {
            val msg = "已启用端到端加密，但尚未设置备份加密口令"
            preferencesRepository.recordBackupOutcome(
                timestampMs = nowMs,
                success = false,
                message = msg,
            )
            return@withContext BackupExecutionResult(
                success = false,
                isTransientFailure = false,
                message = msg,
            )
        }

        val params = creds.toConnectionParams()
        val tempDir = File(filesRootDir, ".webdav-upload-${UUID.randomUUID().toString().replace("-", "")}")
        try {
            val artifact = archiveCodec.packBackup(
                database = database,
                filesRootDir = filesRootDir,
                outputDir = tempDir,
                appVersion = appVersion,
                exportedAtEpochMs = nowMs,
                includeAttachments = creds.syncAttachments,
                encrypt = creds.encryptionEnabled,
                passphrase = creds.backupPassphrase,
            )

            webDavClient.ensureRemoteDirectory(params)
            webDavClient.uploadSnapshot(
                params = params,
                fileName = artifact.fileName,
                content = artifact.file.readBytes(),
            )
            val pruned = runCatching {
                webDavClient.pruneOldSnapshots(params, creds.retentionCount)
            }.getOrDefault(emptyList())

            val formatLabel = if (artifact.encrypted) "DRP1 加密" else "明文 ZIP"
            val msg = buildString {
                append("${trigger.label}成功（$formatLabel · ${formatReadableSize(artifact.sizeBytes)}）")
                if (pruned.isNotEmpty()) {
                    append("，已清理 ${pruned.size} 份过期旧包")
                }
            }
            preferencesRepository.recordBackupOutcome(
                timestampMs = nowMs,
                success = true,
                message = msg,
                fileName = artifact.fileName,
            )

            BackupExecutionResult(
                success = true,
                fileName = artifact.fileName,
                sizeBytes = artifact.sizeBytes,
                encrypted = artifact.encrypted,
                prunedFileNames = pruned,
                isTransientFailure = false,
                message = msg,
            )
        } catch (t: Throwable) {
            val sanitizedMsg = WebDavErrorSanitizer.sanitizeThrowable(
                t,
                creds.password,
                creds.backupPassphrase,
            )
            val transient = t is WebDavException && t.isTransientNetworkError
            preferencesRepository.recordBackupOutcome(
                timestampMs = nowMs,
                success = false,
                message = sanitizedMsg,
            )
            BackupExecutionResult(
                success = false,
                isTransientFailure = transient,
                message = sanitizedMsg,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    override suspend fun listRemoteSnapshots(): Result<List<RemoteBackupSnapshot>> = withContext(ioDispatcher) {
        val creds = preferencesRepository.resolveCredentials()
            ?: return@withContext Result.failure(IllegalStateException("请先保存 WebDAV 服务器与账号配置"))
        runCatching {
            webDavClient.listSnapshots(creds.toConnectionParams())
        }.recoverCatching { err ->
            throw WebDavException(
                WebDavErrorSanitizer.sanitizeThrowable(err, creds.password, creds.backupPassphrase),
            )
        }
    }

    override suspend fun restoreRemoteSnapshot(
        fileName: String,
        passphraseOverride: String?,
    ): RestoreOutcome = withContext(ioDispatcher) {
        val creds = preferencesRepository.resolveCredentials()
            ?: return@withContext RestoreOutcome(
                success = false,
                message = "请先保存 WebDAV 服务器与账号配置后再恢复",
            )
        val archiveBytes = try {
            webDavClient.downloadSnapshot(creds.toConnectionParams(), fileName)
        } catch (t: Throwable) {
            return@withContext RestoreOutcome(
                success = false,
                message = WebDavErrorSanitizer.sanitizeThrowable(t, creds.password, creds.backupPassphrase),
            )
        }

        val effectivePassphrase = passphraseOverride?.takeIf { it.isNotBlank() }
            ?: creds.backupPassphrase

        val outcome = try {
            restoreManager.restoreFromArchiveBytes(
                archiveBytes = archiveBytes,
                passphrase = effectivePassphrase,
                createRollbackBackup = true,
            )
        } catch (e: BackupCryptoException) {
            RestoreOutcome(success = false, message = e.message ?: "解密口令错误或备份已损坏")
        }

        if (outcome.success) {
            preferencesRepository.recordRestoreSuccess(clock())
        }
        outcome
    }

    override suspend fun deleteRemoteSnapshot(fileName: String): Result<Unit> = withContext(ioDispatcher) {
        val creds = preferencesRepository.resolveCredentials()
            ?: return@withContext Result.failure(IllegalStateException("WebDAV 配置不完整"))
        runCatching {
            webDavClient.deleteSnapshot(creds.toConnectionParams(), fileName)
        }.recoverCatching { err ->
            throw WebDavException(
                WebDavErrorSanitizer.sanitizeThrowable(err, creds.password, creds.backupPassphrase),
            )
        }
    }

    override suspend fun createLocalSafetySnapshot(): Result<LocalRollbackSnapshot> = runCatching {
        restoreManager.createLocalRollbackSnapshot(
            prefix = SafeDatabaseRestoreManager.MANUAL_SNAPSHOT_PREFIX,
        )
    }

    override suspend fun listLocalRollbackSnapshots(): List<LocalRollbackSnapshot> =
        restoreManager.listLocalRollbackSnapshots()

    override suspend fun restoreLocalRollbackSnapshot(fileName: String): RestoreOutcome {
        val outcome = restoreManager.restoreFromLocalRollback(fileName)
        if (outcome.success) {
            preferencesRepository.recordRestoreSuccess(clock())
        }
        return outcome
    }

    override suspend fun deleteLocalRollbackSnapshot(fileName: String): Boolean =
        restoreManager.deleteLocalRollbackSnapshot(fileName)

    private suspend fun resolveConnectionParams(): WebDavConnectionParams? =
        preferencesRepository.resolveCredentials()?.toConnectionParams()

    private fun WebDavResolvedCredentials.toConnectionParams(): WebDavConnectionParams =
        WebDavConnectionParams(
            serverUrl = serverUrl,
            username = username,
            password = password,
            remoteDirectory = remoteDirectory,
        )

    companion object {
        fun formatReadableSize(bytes: Long): String = when {
            bytes < 1024L -> "${bytes} B"
            bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
