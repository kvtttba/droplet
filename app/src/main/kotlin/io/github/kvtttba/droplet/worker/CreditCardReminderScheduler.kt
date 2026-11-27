package io.github.kvtttba.droplet.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferences
import java.util.concurrent.TimeUnit

/**
 * Coordinates WorkManager daily local scheduling for [CreditCardReminderWorker].
 *
 * Runs completely offline (no network constraint required) and cancels the unique periodic job
 * immediately when reminders are disabled.
 */
class CreditCardReminderScheduler(
    private val workManager: WorkManager,
) {

    fun syncSchedule(preferences: CreditCardReminderPreferences) {
        syncSchedule(remindersEnabled = preferences.remindersEnabled)
    }

    fun syncSchedule(remindersEnabled: Boolean) {
        if (!remindersEnabled) {
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

    fun enqueueImmediateCheck() {
        val request = buildImmediateWorkRequest()
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun buildPeriodicWorkRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<CreditCardReminderWorker>(
            REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .addTag(WORK_TAG)
            .build()

    fun buildImmediateWorkRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<CreditCardReminderWorker>()
            .addTag(WORK_TAG)
            .build()

    companion object {
        const val UNIQUE_WORK_NAME: String = "droplet_daily_credit_card_reminders"
        const val IMMEDIATE_WORK_NAME: String = "droplet_immediate_credit_card_reminder_check"
        const val WORK_TAG: String = "droplet_credit_card_reminders"
        const val REPEAT_INTERVAL_HOURS: Long = 24L
    }
}
