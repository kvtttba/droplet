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
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferences
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CreditCardReminderSchedulerTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: CreditCardReminderScheduler

    private class NoOpWorker(
        appContext: Context,
        params: WorkerParameters,
    ) : Worker(appContext, params) {
        override fun doWork(): Result = Result.success()
    }

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
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
        workManager = WorkManager.getInstance(context)
        scheduler = CreditCardReminderScheduler(workManager)
    }

    @Test
    fun syncSchedule_enqueuesDailyOfflinePeriodicWorkAndCancelsWhenDisabled() {
        // Enabled -> enqueues 24-hour periodic local check with no network required
        scheduler.syncSchedule(CreditCardReminderPreferences(remindersEnabled = true))
        val enqueued = workManager.getWorkInfosForUniqueWork(CreditCardReminderScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, enqueued.size)
        val info = enqueued.single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.NOT_REQUIRED, info.constraints.requiredNetworkType)
        assertEquals(
            TimeUnit.HOURS.toMillis(CreditCardReminderScheduler.REPEAT_INTERVAL_HOURS),
            info.periodicityInfo?.repeatIntervalMillis,
        )
        assertTrue(CreditCardReminderScheduler.WORK_TAG in info.tags)

        // Disabled -> cancels unique periodic work
        scheduler.syncSchedule(CreditCardReminderPreferences(remindersEnabled = false))
        val cancelled = workManager.getWorkInfosForUniqueWork(CreditCardReminderScheduler.UNIQUE_WORK_NAME).get()
        assertTrue(cancelled.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun buildImmediateWorkRequest_tagsCreditCardReminderWorker() {
        val request = scheduler.buildImmediateWorkRequest()
        assertTrue(CreditCardReminderScheduler.WORK_TAG in request.tags)
    }
}
