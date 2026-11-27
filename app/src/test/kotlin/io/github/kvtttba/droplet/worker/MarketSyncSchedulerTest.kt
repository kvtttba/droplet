package io.github.kvtttba.droplet.worker

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.kvtttba.droplet.core.security.MarketSyncInterval
import io.github.kvtttba.droplet.core.security.MarketSyncPreferences
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MarketSyncSchedulerTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: MarketSyncScheduler

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = MarketSyncScheduler(workManager)
    }

    @Test
    fun syncSchedule_enqueuesUpdatesConstraintsAndCancelsOnManualOnly() {
        // 1. Default 12h + CONNECTED
        scheduler.syncSchedule(
            MarketSyncPreferences(
                refreshInterval = MarketSyncInterval.HOURS_12,
                wifiOnly = false,
            ),
        )
        val initialInfos = workManager.getWorkInfosForUniqueWork(MarketSyncScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, initialInfos.size)
        val initialInfo = initialInfos.single()
        assertEquals(WorkInfo.State.ENQUEUED, initialInfo.state)
        assertEquals(NetworkType.CONNECTED, initialInfo.constraints.requiredNetworkType)
        assertEquals(
            TimeUnit.HOURS.toMillis(12L),
            initialInfo.periodicityInfo?.repeatIntervalMillis,
        )

        // 2. Update to 6h + UNMETERED (Wi-Fi only)
        scheduler.syncSchedule(
            MarketSyncPreferences(
                refreshInterval = MarketSyncInterval.HOURS_6,
                wifiOnly = true,
            ),
        )
        val updatedInfos = workManager.getWorkInfosForUniqueWork(MarketSyncScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, updatedInfos.size)
        val updatedInfo = updatedInfos.single()
        assertEquals(WorkInfo.State.ENQUEUED, updatedInfo.state)
        assertEquals(NetworkType.UNMETERED, updatedInfo.constraints.requiredNetworkType)
        assertEquals(
            TimeUnit.HOURS.toMillis(6L),
            updatedInfo.periodicityInfo?.repeatIntervalMillis,
        )

        // 3. Switch to MANUAL_ONLY cancels unique periodic work
        assertNull(
            scheduler.buildPeriodicWorkRequest(
                MarketSyncPreferences(refreshInterval = MarketSyncInterval.MANUAL_ONLY),
            ),
        )
        scheduler.syncSchedule(
            MarketSyncPreferences(
                refreshInterval = MarketSyncInterval.MANUAL_ONLY,
                wifiOnly = true,
            ),
        )
        val cancelledInfos = workManager.getWorkInfosForUniqueWork(MarketSyncScheduler.UNIQUE_WORK_NAME).get()
        assertTrue(cancelledInfos.all { it.state == WorkInfo.State.CANCELLED })
    }
}
