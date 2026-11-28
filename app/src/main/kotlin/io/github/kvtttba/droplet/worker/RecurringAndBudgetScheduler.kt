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
 * Coordinates daily offline WorkManager scheduling for [RecurringAndBudgetWorker].
 *
 * Requires no network connectivity; automatically cancels the periodic job when neither
 * active budgets nor unpaused recurring rules exist.
 */
class RecurringAndBudgetScheduler(
    private val workManager: WorkManager,
) {

    fun syncSchedule(shouldSchedule: Boolean) {
        if (!shouldSchedule) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            buildPeriodicWorkRequest(),
        )
    }

    fun enqueueImmediateScan() {
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            buildImmediateWorkRequest(),
        )
    }

    fun buildPeriodicWorkRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<RecurringAndBudgetWorker>(
            REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .addTag(WORK_TAG)
            .build()

    fun buildImmediateWorkRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<RecurringAndBudgetWorker>()
            .addTag(WORK_TAG)
            .build()

    companion object {
        const val UNIQUE_WORK_NAME: String = "droplet_daily_budget_recurring_scan"
        const val IMMEDIATE_WORK_NAME: String = "droplet_immediate_budget_recurring_scan"
        const val WORK_TAG: String = "droplet_budget_recurring_automation"
        const val REPEAT_INTERVAL_HOURS: Long = 24L
    }
}
