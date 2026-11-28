package io.github.kvtttba.droplet.worker

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.kvtttba.droplet.core.security.WebDavConfigSaveInput
import io.github.kvtttba.droplet.core.security.WebDavResolvedCredentials
import io.github.kvtttba.droplet.core.security.WebDavSecretUpdateAction
import io.github.kvtttba.droplet.core.security.WebDavSyncPreferences
import io.github.kvtttba.droplet.core.security.WebDavSyncPreferencesRepository
import io.github.kvtttba.droplet.core.sync.BackupExecutionResult
import io.github.kvtttba.droplet.core.sync.BackupTrigger
import io.github.kvtttba.droplet.core.sync.LocalRollbackSnapshot
import io.github.kvtttba.droplet.core.sync.RemoteBackupSnapshot
import io.github.kvtttba.droplet.core.sync.RestoreOutcome
import io.github.kvtttba.droplet.core.sync.SyncContract
import io.github.kvtttba.droplet.core.sync.WebDavBackupOrchestrator
import io.github.kvtttba.droplet.core.sync.WebDavConnectionParams
import io.github.kvtttba.droplet.core.sync.WebDavConnectionTestResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WebDavBackupWorkerAndSchedulerTest {

    private lateinit var context: Context

    private class NoOpWorker(
        appContext: Context,
        params: WorkerParameters,
    ) : Worker(appContext, params) {
        override fun doWork(): Result = Result.success()
    }

    private class FakePrefsRepo(
        initial: WebDavSyncPreferences,
    ) : WebDavSyncPreferencesRepository {
        val state = MutableStateFlow(initial)
        override val preferences: Flow<WebDavSyncPreferences> = state
        override suspend fun currentPreferences(): WebDavSyncPreferences = state.value
        override suspend fun saveConnectionConfig(input: WebDavConfigSaveInput) = Unit
        override suspend fun saveBackupPassphrase(action: WebDavSecretUpdateAction) = Unit
        override suspend fun setEncryptionEnabled(enabled: Boolean) = Unit
        override suspend fun setAutoSyncEnabled(enabled: Boolean) = Unit
        override suspend fun setDailyBackupEnabled(enabled: Boolean) = Unit
        override suspend fun setWifiOnly(wifiOnly: Boolean) = Unit
        override suspend fun setSyncAttachments(enabled: Boolean) = Unit
        override suspend fun setRetentionCount(count: Int) = Unit
        override suspend fun recordBackupOutcome(timestampMs: Long, success: Boolean, message: String, fileName: String?) = Unit
        override suspend fun recordRestoreSuccess(timestampMs: Long) = Unit
        override suspend fun resolveCredentials(): WebDavResolvedCredentials? = null
        override suspend fun decryptPassword(): String? = null
        override suspend fun decryptBackupPassphrase(): String? = null
        override suspend fun clearPassword() = Unit
        override suspend fun clearBackupPassphrase() = Unit
        override suspend fun clearAll() = Unit
    }

    private class StubOrchestrator(
        var nextResult: BackupExecutionResult,
    ) : WebDavBackupOrchestrator {
        val triggeredWith = mutableListOf<BackupTrigger>()
        override suspend fun testConnection(overrideParams: WebDavConnectionParams?): WebDavConnectionTestResult =
            WebDavConnectionTestResult(true, "", 207, 10L, "ok")
        override suspend fun backupNow(trigger: BackupTrigger): BackupExecutionResult {
            triggeredWith += trigger
            return nextResult
        }
        override suspend fun listRemoteSnapshots(): Result<List<RemoteBackupSnapshot>> = Result.success(emptyList())
        override suspend fun restoreRemoteSnapshot(fileName: String, passphraseOverride: String?): RestoreOutcome =
            RestoreOutcome(false, message = "stub")
        override suspend fun deleteRemoteSnapshot(fileName: String): Result<Unit> = Result.success(Unit)
        override suspend fun createLocalSafetySnapshot(): Result<LocalRollbackSnapshot> =
            Result.failure(UnsupportedOperationException())
        override suspend fun listLocalRollbackSnapshots(): List<LocalRollbackSnapshot> = emptyList()
        override suspend fun restoreLocalRollbackSnapshot(fileName: String): RestoreOutcome =
            RestoreOutcome(false, message = "stub")
        override suspend fun deleteLocalRollbackSnapshot(fileName: String): Boolean = false
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        WebDavBackupWorker.resetDependenciesResolver()
    }

    private fun readyPrefs(
        autoSync: Boolean = true,
        daily: Boolean = true,
        wifiOnly: Boolean = false,
    ): WebDavSyncPreferences = WebDavSyncPreferences(
        serverUrl = "https://webdav.example.invalid/dav",
        username = "demo-user",
        hasPassword = true,
        encryptionEnabled = true,
        hasPassphrase = true,
        autoSyncEnabled = autoSync,
        dailyBackupEnabled = daily,
        wifiOnly = wifiOnly,
    )

    @Test
    fun workerSkipsWhenDisabledUploadsWhenReadyRetriesOnTransientAndFailsOnAuthError() = runTest {
        val orchestrator = StubOrchestrator(
            BackupExecutionResult(
                success = true,
                fileName = "droplet-backup-20261127-120000-000.drp",
                prunedFileNames = listOf("old-1.drp"),
                message = "自动防抖上传成功",
            ),
        )
        val prefsRepo = FakePrefsRepo(readyPrefs(autoSync = false, daily = false))
        WebDavBackupWorker.dependenciesResolver = {
            WebDavBackupWorkerDependencies(prefsRepo, orchestrator)
        }

        // 1. Disabled -> skipped cleanly
        val skipped = TestListenableWorkerBuilder<WebDavBackupWorker>(context)
            .setInputData(workDataOf(WebDavBackupWorker.KEY_TRIGGER to BackupTrigger.DEBOUNCED_AUTO.name))
            .build()
            .doWork()
        assertTrue(skipped is ListenableWorker.Result.Success)
        assertEquals(
            WebDavBackupWorker.STATUS_SKIPPED,
            (skipped as ListenableWorker.Result.Success).outputData.getString(WebDavBackupWorker.KEY_STATUS),
        )
        assertTrue(orchestrator.triggeredWith.isEmpty())

        // 2. Enabled -> uploads & reports file name and pruned count
        prefsRepo.state.value = readyPrefs(autoSync = true, daily = true)
        val uploaded = TestListenableWorkerBuilder<WebDavBackupWorker>(context)
            .setInputData(workDataOf(WebDavBackupWorker.KEY_TRIGGER to BackupTrigger.DEBOUNCED_AUTO.name))
            .build()
            .doWork()
        assertTrue(uploaded is ListenableWorker.Result.Success)
        val uploadData = (uploaded as ListenableWorker.Result.Success).outputData
        assertEquals(WebDavBackupWorker.STATUS_UPLOADED, uploadData.getString(WebDavBackupWorker.KEY_STATUS))
        assertEquals("droplet-backup-20261127-120000-000.drp", uploadData.getString(WebDavBackupWorker.KEY_FILE_NAME))
        assertEquals(1, uploadData.getInt(WebDavBackupWorker.KEY_PRUNED_COUNT, -1))
        assertEquals(listOf(BackupTrigger.DEBOUNCED_AUTO), orchestrator.triggeredWith)

        // 3. Transient network error -> retry
        orchestrator.nextResult = BackupExecutionResult(
            success = false,
            isTransientFailure = true,
            message = "网络连接超时",
        )
        val retried = TestListenableWorkerBuilder<WebDavBackupWorker>(context)
            .setInputData(workDataOf(WebDavBackupWorker.KEY_TRIGGER to BackupTrigger.DAILY_PERIODIC.name))
            .build()
            .doWork()
        assertTrue(retried is ListenableWorker.Result.Retry)

        // 4. Non-transient auth error -> immediate failure without pointless retry
        orchestrator.nextResult = BackupExecutionResult(
            success = false,
            isTransientFailure = false,
            message = "认证失败（HTTP 401）",
        )
        val failed = TestListenableWorkerBuilder<WebDavBackupWorker>(context)
            .setInputData(workDataOf(WebDavBackupWorker.KEY_TRIGGER to BackupTrigger.DAILY_PERIODIC.name))
            .build()
            .doWork()
        assertTrue(failed is ListenableWorker.Result.Failure)
    }

    @Test
    fun schedulerEnqueuesDebounced30sAndDaily24hWithProperNetworkConstraintsAndCancels() {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = NoOpWorker(appContext, workerParameters)
                },
            )
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val workManager = WorkManager.getInstance(context)
        val scheduler = WebDavSyncScheduler(workManager)

        // Debounced 30s auto upload (CONNECTED)
        val debouncedPrefs = readyPrefs(autoSync = true, daily = false, wifiOnly = false)
        scheduler.scheduleDebouncedBackup(debouncedPrefs)
        val debouncedInfos = workManager.getWorkInfosForUniqueWork(WebDavSyncScheduler.DEBOUNCED_UNIQUE_WORK_NAME).get()
        assertEquals(1, debouncedInfos.size)
        val debouncedInfo = debouncedInfos.single()
        assertEquals(WorkInfo.State.ENQUEUED, debouncedInfo.state)
        assertEquals(NetworkType.CONNECTED, debouncedInfo.constraints.requiredNetworkType)
        assertEquals(
            TimeUnit.SECONDS.toMillis(SyncContract.DEFAULT_DEBOUNCE_SECONDS),
            debouncedInfo.initialDelayMillis,
        )
        assertTrue(WebDavSyncScheduler.DEBOUNCED_WORK_TAG in debouncedInfo.tags)

        // Daily periodic backup (wifiOnly = true -> UNMETERED)
        val dailyPrefs = readyPrefs(autoSync = true, daily = true, wifiOnly = true)
        scheduler.syncDailySchedule(dailyPrefs)
        val dailyInfos = workManager.getWorkInfosForUniqueWork(WebDavSyncScheduler.DAILY_UNIQUE_WORK_NAME).get()
        assertEquals(1, dailyInfos.size)
        val dailyInfo = dailyInfos.single()
        assertEquals(WorkInfo.State.ENQUEUED, dailyInfo.state)
        assertEquals(NetworkType.UNMETERED, dailyInfo.constraints.requiredNetworkType)
        assertEquals(
            TimeUnit.HOURS.toMillis(WebDavSyncScheduler.DAILY_REPEAT_INTERVAL_HOURS),
            dailyInfo.periodicityInfo?.repeatIntervalMillis,
        )
        assertTrue(WebDavSyncScheduler.DAILY_WORK_TAG in dailyInfo.tags)

        // Disabling both cancels both unique works cleanly
        val disabled = readyPrefs(autoSync = false, daily = false)
        scheduler.syncDailySchedule(disabled)
        assertTrue(
            workManager.getWorkInfosForUniqueWork(WebDavSyncScheduler.DEBOUNCED_UNIQUE_WORK_NAME).get()
                .all { it.state == WorkInfo.State.CANCELLED },
        )
        assertTrue(
            workManager.getWorkInfosForUniqueWork(WebDavSyncScheduler.DAILY_UNIQUE_WORK_NAME).get()
                .all { it.state == WorkInfo.State.CANCELLED },
        )
    }
}
