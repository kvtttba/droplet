package io.github.kvtttba.droplet.feature.settings

import io.github.kvtttba.droplet.core.security.AiSecretMasker
import io.github.kvtttba.droplet.core.security.WebDavConfigSaveInput
import io.github.kvtttba.droplet.core.security.WebDavResolvedCredentials
import io.github.kvtttba.droplet.core.security.WebDavSecretUpdateAction
import io.github.kvtttba.droplet.core.security.WebDavSyncPreferences
import io.github.kvtttba.droplet.core.security.WebDavSyncPreferencesRepository
import io.github.kvtttba.droplet.core.security.WebDavUrlValidator
import io.github.kvtttba.droplet.core.sync.BackupExecutionResult
import io.github.kvtttba.droplet.core.sync.BackupTrigger
import io.github.kvtttba.droplet.core.sync.LocalRollbackSnapshot
import io.github.kvtttba.droplet.core.sync.RemoteBackupSnapshot
import io.github.kvtttba.droplet.core.sync.RestoreOutcome
import io.github.kvtttba.droplet.core.sync.WebDavBackupOrchestrator
import io.github.kvtttba.droplet.core.sync.WebDavConnectionParams
import io.github.kvtttba.droplet.core.sync.WebDavConnectionTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebDavSyncViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeWebDavPrefsRepository : WebDavSyncPreferencesRepository {
        val state = MutableStateFlow(WebDavSyncPreferences())
        var savedPassword: String? = null
        var savedPassphrase: String? = null

        override val preferences: Flow<WebDavSyncPreferences> = state

        override suspend fun currentPreferences(): WebDavSyncPreferences = state.value

        override suspend fun saveConnectionConfig(input: WebDavConfigSaveInput) {
            val url = WebDavUrlValidator.validateServerUrl(input.serverUrl).getOrThrow()
            val dir = WebDavUrlValidator.normalizeRemoteDirectory(input.remoteDirectory).getOrThrow()
            when (val act = input.passwordAction) {
                WebDavSecretUpdateAction.KeepExisting -> Unit
                WebDavSecretUpdateAction.Clear -> savedPassword = null
                is WebDavSecretUpdateAction.Replace -> savedPassword = act.plaintextValue
            }
            state.update {
                it.copy(
                    serverUrl = url,
                    username = input.username.trim(),
                    remoteDirectory = dir,
                    hasPassword = !savedPassword.isNullOrBlank(),
                    passwordMasked = savedPassword?.let(AiSecretMasker::mask).orEmpty(),
                )
            }
        }

        override suspend fun saveBackupPassphrase(action: WebDavSecretUpdateAction) {
            when (action) {
                WebDavSecretUpdateAction.KeepExisting -> return
                WebDavSecretUpdateAction.Clear -> savedPassphrase = null
                is WebDavSecretUpdateAction.Replace -> savedPassphrase = action.plaintextValue
            }
            state.update {
                it.copy(
                    hasPassphrase = !savedPassphrase.isNullOrBlank(),
                    passphraseMasked = savedPassphrase?.let(AiSecretMasker::mask).orEmpty(),
                )
            }
        }

        override suspend fun setEncryptionEnabled(enabled: Boolean) {
            state.update { it.copy(encryptionEnabled = enabled) }
        }

        override suspend fun setAutoSyncEnabled(enabled: Boolean) {
            state.update { it.copy(autoSyncEnabled = enabled) }
        }

        override suspend fun setDailyBackupEnabled(enabled: Boolean) {
            state.update { it.copy(dailyBackupEnabled = enabled) }
        }

        override suspend fun setWifiOnly(wifiOnly: Boolean) {
            state.update { it.copy(wifiOnly = wifiOnly) }
        }

        override suspend fun setSyncAttachments(enabled: Boolean) {
            state.update { it.copy(syncAttachments = enabled) }
        }

        override suspend fun setRetentionCount(count: Int) {
            state.update { it.copy(retentionCount = WebDavUrlValidator.sanitizeRetentionCount(count)) }
        }

        override suspend fun recordBackupOutcome(
            timestampMs: Long,
            success: Boolean,
            message: String,
            fileName: String?,
        ) {
            state.update {
                it.copy(
                    lastBackupAt = timestampMs,
                    lastBackupSuccess = success,
                    lastBackupMessage = message,
                    lastBackupFileName = fileName ?: it.lastBackupFileName,
                )
            }
        }

        override suspend fun recordRestoreSuccess(timestampMs: Long) {
            state.update { it.copy(lastRestoreAt = timestampMs) }
        }

        override suspend fun resolveCredentials(): WebDavResolvedCredentials? {
            val prefs = state.value
            val pass = savedPassword ?: return null
            if (!prefs.isServerConfigured) return null
            return WebDavResolvedCredentials(
                serverUrl = prefs.serverUrl,
                username = prefs.username,
                password = pass,
                remoteDirectory = prefs.remoteDirectory,
                encryptionEnabled = prefs.encryptionEnabled,
                backupPassphrase = savedPassphrase,
                syncAttachments = prefs.syncAttachments,
                retentionCount = prefs.retentionCount,
            )
        }

        override suspend fun decryptPassword(): String? = savedPassword

        override suspend fun decryptBackupPassphrase(): String? = savedPassphrase

        override suspend fun clearPassword() {
            savedPassword = null
            state.update { it.copy(hasPassword = false, passwordMasked = "") }
        }

        override suspend fun clearBackupPassphrase() {
            savedPassphrase = null
            state.update { it.copy(hasPassphrase = false, passphraseMasked = "") }
        }

        override suspend fun clearAll() {
            savedPassword = null
            savedPassphrase = null
            state.value = WebDavSyncPreferences()
        }
    }

    private class FakeWebDavBackupOrchestrator : WebDavBackupOrchestrator {
        var lastTestParams: WebDavConnectionParams? = null
        val remoteSnapshots = mutableListOf(
            RemoteBackupSnapshot(
                fileName = "droplet-backup-20261127-120000-000.drp",
                remotePath = "/dav/droplet-backups/droplet-backup-20261127-120000-000.drp",
                sizeBytes = 64_000L,
                lastModifiedEpochMs = 1_764_216_000_000L,
                isEncrypted = true,
            ),
        )
        val localRollbacks = mutableListOf<LocalRollbackSnapshot>()

        override suspend fun testConnection(overrideParams: WebDavConnectionParams?): WebDavConnectionTestResult {
            lastTestParams = overrideParams
            return WebDavConnectionTestResult(
                success = true,
                normalizedEndpointUrl = overrideParams?.serverUrl.orEmpty(),
                httpStatusCode = 207,
                latencyMs = 45L,
                message = "连接成功，WebDAV 服务与账号鉴权均已通过",
            )
        }

        override suspend fun backupNow(trigger: BackupTrigger): BackupExecutionResult {
            val name = "droplet-backup-20261127-130000-000.drp"
            remoteSnapshots.add(
                0,
                RemoteBackupSnapshot(
                    fileName = name,
                    remotePath = "/dav/droplet-backups/$name",
                    sizeBytes = 72_000L,
                    lastModifiedEpochMs = 1_764_219_600_000L,
                    isEncrypted = true,
                ),
            )
            return BackupExecutionResult(
                success = true,
                fileName = name,
                sizeBytes = 72_000L,
                encrypted = true,
                message = "手动备份成功",
            )
        }

        override suspend fun listRemoteSnapshots(): Result<List<RemoteBackupSnapshot>> =
            Result.success(remoteSnapshots.toList())

        override suspend fun restoreRemoteSnapshot(
            fileName: String,
            passphraseOverride: String?,
        ): RestoreOutcome {
            val rollback = LocalRollbackSnapshot(
                fileName = "pre-restore-20261127-130500-000.zip",
                filePath = "/tmp/pre-restore-20261127-130500-000.zip",
                sizeBytes = 50_000L,
                createdAtEpochMs = 1_764_219_900_000L,
                isPreRestoreAuto = true,
            )
            localRollbacks.add(0, rollback)
            return RestoreOutcome(
                success = true,
                rollbackSnapshot = rollback,
                restoredTableRowCount = 88,
                message = "安全恢复成功",
            )
        }

        override suspend fun deleteRemoteSnapshot(fileName: String): Result<Unit> {
            remoteSnapshots.removeAll { it.fileName == fileName }
            return Result.success(Unit)
        }

        override suspend fun createLocalSafetySnapshot(): Result<LocalRollbackSnapshot> {
            val snap = LocalRollbackSnapshot(
                fileName = "manual-snapshot-20261127-131000-000.zip",
                filePath = "/tmp/manual-snapshot-20261127-131000-000.zip",
                sizeBytes = 48_000L,
                createdAtEpochMs = 1_764_220_200_000L,
                isPreRestoreAuto = false,
            )
            localRollbacks.add(0, snap)
            return Result.success(snap)
        }

        override suspend fun listLocalRollbackSnapshots(): List<LocalRollbackSnapshot> =
            localRollbacks.toList()

        override suspend fun restoreLocalRollbackSnapshot(fileName: String): RestoreOutcome =
            RestoreOutcome(success = true, restoredTableRowCount = 88, message = "回滚恢复成功")

        override suspend fun deleteLocalRollbackSnapshot(fileName: String): Boolean =
            localRollbacks.removeAll { it.fileName == fileName }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun saveConfigTestConnectionBackupRestoreAndPolicyTogglesWork() = runTest {
        val prefsRepo = FakeWebDavPrefsRepository()
        val orchestrator = FakeWebDavBackupOrchestrator()
        val viewModel = WebDavSyncViewModel(prefsRepo, orchestrator)

        val collectJob = launch { viewModel.uiState.collect {} }
        try {
            advanceUntilIdle()

            // Validation blocks invalid URL
            viewModel.onServerUrlChange("ftp://invalid.example")
            viewModel.saveServerConfig()
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.serverValidationError)

            // Save valid server config
            viewModel.onServerUrlChange("https://webdav.example.invalid/dav/")
            viewModel.onUsernameChange("demo-user")
            viewModel.onPasswordInputChange("demo-dav-pass-2026")
            viewModel.onRemoteDirectoryChange("droplet-backups")
            viewModel.saveServerConfig()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.preferences.isServerConfigured)
            assertEquals("••••2026", viewModel.uiState.value.preferences.passwordMasked)
            assertEquals("", viewModel.uiState.value.passwordInput)
            assertFalse(viewModel.uiState.value.toString().contains("demo-dav-pass-2026"))

            // Short passphrase rejected, valid passphrase saved
            viewModel.onPassphraseInputChange("123")
            viewModel.saveBackupPassphrase()
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.passphraseValidationError)

            viewModel.onPassphraseInputChange("demo-phrase-8899")
            viewModel.saveBackupPassphrase()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.preferences.isReadyForBackup)
            assertEquals("••••8899", viewModel.uiState.value.preferences.passphraseMasked)

            // Test connection uses saved password seamlessly
            viewModel.testConnection()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.connectionTestResult?.success == true)
            assertEquals("demo-dav-pass-2026", orchestrator.lastTestParams?.password)

            // Policy switches & retention
            viewModel.onToggleAutoSync(true)
            viewModel.onToggleDailyBackup(true)
            viewModel.onToggleWifiOnly(true)
            viewModel.onToggleSyncAttachments(true)
            viewModel.onSelectRetentionCount(20)
            advanceUntilIdle()
            val prefs = viewModel.uiState.value.preferences
            assertTrue(prefs.autoSyncEnabled)
            assertTrue(prefs.dailyBackupEnabled)
            assertTrue(prefs.wifiOnly)
            assertTrue(prefs.syncAttachments)
            assertEquals(20, prefs.retentionCount)

            // Manual backup refreshes remote snapshots automatically
            viewModel.backupNow()
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.remoteSnapshots.size)

            // Restore remote snapshot updates local rollback snapshots list
            viewModel.restoreRemoteSnapshot(viewModel.uiState.value.remoteSnapshots.first().fileName)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.lastRestoreOutcome?.success == true)
            assertEquals(1, viewModel.uiState.value.localRollbackSnapshots.size)

            // Create and delete local safety snapshot
            viewModel.createLocalSafetySnapshot()
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.localRollbackSnapshots.size)

            viewModel.deleteLocalRollbackSnapshot(viewModel.uiState.value.localRollbackSnapshots.first().fileName)
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.localRollbackSnapshots.size)
        } finally {
            collectJob.cancel()
        }
    }
}
