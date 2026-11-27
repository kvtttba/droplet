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
 * Coordinates WorkManager daily offline scheduling for [LoanScheduleWorker].
 *
 * Runs completely offline (no network constraint required) to process due auto-post loan
 * installments and local advance reminders; cancels the unique periodic job automatically
 * when no active loans require auto-posting or reminders.
 */
class LoanScheduleScheduler(
    private val workManager: WorkManager,
) {

    fun syncSchedule(hasScheduledLoans: Boolean) {
        if (!hasScheduledLoans) {
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
        PeriodicWorkRequestBuilder<LoanScheduleWorker>(
            REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .addTag(WORK_TAG)
            .build()

    fun buildImmediateWorkRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<LoanScheduleWorker>()
            .addTag(WORK_TAG)
            .build()

    companion object {
        const val UNIQUE_WORK_NAME: String = "droplet_daily_loan_schedule"
        const val IMMEDIATE_WORK_NAME: String = "droplet_immediate_loan_schedule_scan"
        const val WORK_TAG: String = "droplet_loan_schedule"
        const val REPEAT_INTERVAL_HOURS: Long = 24L
    }
}
