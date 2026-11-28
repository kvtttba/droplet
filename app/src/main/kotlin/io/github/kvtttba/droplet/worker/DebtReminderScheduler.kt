package io.github.kvtttba.droplet.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Coordinates WorkManager daily offline scheduling for [DebtReminderWorker].
 *
 * Runs completely offline (no network constraint required) and cancels the unique periodic
 * job automatically when disabled or when no active debt profiles have an agreed due date.
 */
class DebtReminderScheduler(
    private val workManager: WorkManager,
) {

    fun syncSchedule(shouldSchedule: Boolean) {
        if (!shouldSchedule) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = buildPeriodicWorkRequest()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun enqueueImmediateScan() {
        val request = buildImmediateWorkRequest()
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun buildPeriodicWorkRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<DebtReminderWorker>(
            REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .addTag(WORK_TAG)
            .build()

    fun buildImmediateWorkRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DebtReminderWorker>()
            .addTag(WORK_TAG)
            .build()

    companion object {
        const val UNIQUE_WORK_NAME: String = "droplet_daily_debt_reminders"
        const val IMMEDIATE_WORK_NAME: String = "droplet_immediate_debt_reminder_scan"
        const val WORK_TAG: String = "droplet_debt_reminder"
        const val REPEAT_INTERVAL_HOURS: Long = 24L
    }
}
