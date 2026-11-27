package io.github.kvtttba.droplet.worker

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.kvtttba.droplet.core.security.MarketSyncPreferences
import java.util.concurrent.TimeUnit

/**
 * Coordinates WorkManager periodic background scheduling for [MarketRefreshWorker]
 * based on the user's persisted [MarketSyncPreferences].
 */
class MarketSyncScheduler(
    private val workManager: WorkManager,
) {

    fun syncSchedule(preferences: MarketSyncPreferences) {
        val request = buildPeriodicWorkRequest(preferences)
        if (request == null) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun buildPeriodicWorkRequest(preferences: MarketSyncPreferences): PeriodicWorkRequest? {
        val repeatHours = preferences.refreshInterval.repeatIntervalHours ?: return null
        val networkType = if (preferences.wifiOnly) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        return PeriodicWorkRequestBuilder<MarketRefreshWorker>(
            repeatHours,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build()
    }

    companion object {
        const val UNIQUE_WORK_NAME: String = "droplet_periodic_market_refresh"
        const val WORK_TAG: String = "droplet_market_refresh"
    }
}
