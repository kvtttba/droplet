package io.github.kvtttba.droplet.worker

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.kvtttba.droplet.core.security.WebDavSyncPreferences
import io.github.kvtttba.droplet.core.sync.BackupTrigger
import io.github.kvtttba.droplet.core.sync.SyncContract
import java.util.concurrent.TimeUnit

/**
 * Coordinates WorkManager scheduling for WebDAV encrypted snapshot backups:
 * 1. Debounced one-time upload (default 30s delay with `ExistingWorkPolicy.REPLACE`) triggered after
 *    local ledger mutations so bursts of rapid edits coalesce into a single upload;
 * 2. Daily periodic backup (24h interval with `ExistingPeriodicWorkPolicy.UPDATE`) when enabled.
 */
class WebDavSyncScheduler(
    private val workManager: WorkManager,
) {

    fun scheduleDebouncedBackup(preferences: WebDavSyncPreferences) {
        if (!preferences.autoSyncEnabled || !preferences.isReadyForBackup) {
            workManager.cancelUniqueWork(DEBOUNCED_UNIQUE_WORK_NAME)
            return
        }
        val request = buildDebouncedBackupRequest(preferences)
        workManager.enqueueUniqueWork(
            DEBOUNCED_UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelDebouncedBackup() {
        workManager.cancelUniqueWork(DEBOUNCED_UNIQUE_WORK_NAME)
    }

    fun syncDailySchedule(preferences: WebDavSyncPreferences) {
        if (!preferences.autoSyncEnabled || !preferences.isReadyForBackup) {
            workManager.cancelUniqueWork(DEBOUNCED_UNIQUE_WORK_NAME)
        }
        if (!preferences.dailyBackupEnabled || !preferences.isReadyForBackup) {
            workManager.cancelUniqueWork(DAILY_UNIQUE_WORK_NAME)
            return
        }
        val request = buildDailyBackupRequest(preferences)
        workManager.enqueueUniquePeriodicWork(
            DAILY_UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun buildDebouncedBackupRequest(preferences: WebDavSyncPreferences): OneTimeWorkRequest {
        val constraints = buildNetworkConstraints(preferences.wifiOnly)
        return OneTimeWorkRequestBuilder<WebDavBackupWorker>()
            .setInitialDelay(SyncContract.DEFAULT_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .setInputData(
                workDataOf(WebDavBackupWorker.KEY_TRIGGER to BackupTrigger.DEBOUNCED_AUTO.name),
            )
            .addTag(DEBOUNCED_WORK_TAG)
            .build()
    }

    fun buildDailyBackupRequest(preferences: WebDavSyncPreferences): PeriodicWorkRequest {
        val constraints = buildNetworkConstraints(preferences.wifiOnly)
        return PeriodicWorkRequestBuilder<WebDavBackupWorker>(
            DAILY_REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setInputData(
                workDataOf(WebDavBackupWorker.KEY_TRIGGER to BackupTrigger.DAILY_PERIODIC.name),
            )
            .addTag(DAILY_WORK_TAG)
            .build()
    }

    private fun buildNetworkConstraints(wifiOnly: Boolean): Constraints {
        val networkType = if (wifiOnly) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()
    }

    companion object {
        const val DEBOUNCED_UNIQUE_WORK_NAME: String = "droplet_webdav_debounced_backup"
        const val DAILY_UNIQUE_WORK_NAME: String = "droplet_webdav_daily_backup"
        const val DEBOUNCED_WORK_TAG: String = "droplet_webdav_debounced"
        const val DAILY_WORK_TAG: String = "droplet_webdav_daily"
        const val DAILY_REPEAT_INTERVAL_HOURS: Long = 24L
    }
}
