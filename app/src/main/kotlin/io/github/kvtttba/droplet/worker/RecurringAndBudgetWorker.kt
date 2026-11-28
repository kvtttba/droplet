package io.github.kvtttba.droplet.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import io.github.kvtttba.droplet.core.database.repository.RecurringRuleRepository
import io.github.kvtttba.droplet.core.domain.automation.RecurringScheduleCalculator
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.BudgetAndRecurringPreferences
import io.github.kvtttba.droplet.core.security.BudgetAndRecurringPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.di.PlanningEntryPoint
import io.github.kvtttba.droplet.notification.PlanningAlertNotifier
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal data class RecurringAndBudgetWorkerDependencies(
    val recurringRuleRepository: RecurringRuleRepository,
    val budgetAlertEvaluator: BudgetAlertEvaluator,
    val alertPreferencesRepository: BudgetAndRecurringPreferencesRepository,
    val privacyPreferencesRepository: PrivacyPreferencesRepository,
    val notifier: PlanningAlertNotifier,
    val clock: NetworkClock,
    val zoneId: ZoneId = RecurringScheduleCalculator.DEFAULT_ZONE_ID,
)

/**
 * Daily offline WorkManager worker orchestrating M5.1 planning automation:
 * 1. Idempotently auto-posts due active `autoPost = true` recurring rules and notifies user.
 * 2. Scans due `autoPost = false` recurring rules awaiting confirmation and sends deduplicated
 *    same-day local reminders.
 * 3. Evaluates all enabled monthly/yearly overall & category budgets for 80%/100% threshold
 *    alerts with per-period deduplication and global privacy amount masking.
 */
class RecurringAndBudgetWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val deps = dependenciesResolver(applicationContext)
            val nowMs = deps.clock.nowEpochMs()
            val todayIso = Instant.ofEpochMilli(nowMs)
                .atZone(deps.zoneId)
                .toLocalDate()
                .toString()

            val hideAmounts = deps.privacyPreferencesRepository.preferences.first().amountsHidden
            val alertPrefs = deps.alertPreferencesRepository.currentPreferences()

            // 1. Idempotent auto-post for due recurring rules
            val autoPostReport = deps.recurringRuleRepository.processDueAutoPostRules(asOfEpochMs = nowMs)
            if (alertPrefs.recurringRemindersEnabled) {
                for (posted in autoPostReport.postedItems) {
                    deps.notifier.publishRecurringAutoPosted(
                        rule = posted.rule,
                        payload = posted.payload,
                        hideAmounts = hideAmounts,
                    )
                }
            }

            // 2. Scan due manual-confirmation recurring rules and dispatch same-day deduplicated reminders
            val allRules = deps.recurringRuleRepository.getRulesWithStatusSnapshot(asOfEpochMs = nowMs)
            val pendingConfirmRules = allRules.filter { it.isPendingConfirmation }
            val newlySentRecurringKeys = mutableListOf<String>()
            var recurringSkippedDupes = 0
            var recurringPermBlocked = 0

            if (alertPrefs.recurringRemindersEnabled) {
                for (item in pendingConfirmRules) {
                    val payload = item.payload ?: continue
                    val key = BudgetAndRecurringPreferences.recurringReminderKey(
                        ruleId = item.rule.id,
                        nextRunAt = item.rule.nextRunAt,
                        localDateIso = todayIso,
                    )
                    if (alertPrefs.hasSentRecurringReminder(key)) {
                        recurringSkippedDupes++
                        continue
                    }
                    val posted = deps.notifier.publishRecurringPendingConfirmation(
                        rule = item.rule,
                        payload = payload,
                        hideAmounts = hideAmounts,
                        zoneId = deps.zoneId,
                    )
                    if (posted) {
                        newlySentRecurringKeys += key
                    } else {
                        recurringPermBlocked++
                    }
                }
                if (newlySentRecurringKeys.isNotEmpty()) {
                    deps.alertPreferencesRepository.markRecurringRemindersSent(newlySentRecurringKeys)
                }
            }

            // 3. Evaluate all enabled budgets AFTER auto-posting recurring expenses
            val budgetResult = deps.budgetAlertEvaluator.evaluateAndNotify(asOfEpochMs = nowMs)

            val totalNotifications = newlySentRecurringKeys.size + budgetResult.notifiedCount
            val totalBlocked = recurringPermBlocked + budgetResult.permissionBlockedCount
            val totalSkipped = recurringSkippedDupes + budgetResult.skippedDuplicateCount

            val status = when {
                autoPostReport.postedCount > 0 && totalNotifications > 0 -> STATUS_AUTO_POSTED_AND_NOTIFIED
                autoPostReport.postedCount > 0 -> STATUS_AUTO_POSTED
                totalNotifications > 0 -> STATUS_NOTIFIED
                totalBlocked > 0 -> STATUS_PERMISSION_DENIED
                totalSkipped > 0 -> STATUS_ALREADY_NOTIFIED
                else -> STATUS_NO_ACTION_NEEDED
            }

            Result.success(
                workDataOf(
                    KEY_STATUS to status,
                    KEY_RECURRING_AUTO_POSTED_COUNT to autoPostReport.postedCount,
                    KEY_RECURRING_PENDING_REMINDED_COUNT to newlySentRecurringKeys.size,
                    KEY_BUDGET_ALERT_NOTIFIED_COUNT to budgetResult.notifiedCount,
                    KEY_SKIPPED_DUPLICATE_COUNT to totalSkipped,
                    KEY_SCAN_DATE to todayIso,
                ),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val MAX_RETRY_ATTEMPTS: Int = 3
        const val KEY_STATUS: String = "planning_worker_status"
        const val KEY_RECURRING_AUTO_POSTED_COUNT: String = "recurring_auto_posted_count"
        const val KEY_RECURRING_PENDING_REMINDED_COUNT: String = "recurring_pending_reminded_count"
        const val KEY_BUDGET_ALERT_NOTIFIED_COUNT: String = "budget_alert_notified_count"
        const val KEY_SKIPPED_DUPLICATE_COUNT: String = "skipped_duplicate_count"
        const val KEY_SCAN_DATE: String = "scan_date"

        const val STATUS_NO_ACTION_NEEDED: String = "NO_ACTION_NEEDED"
        const val STATUS_AUTO_POSTED: String = "AUTO_POSTED"
        const val STATUS_NOTIFIED: String = "NOTIFIED"
        const val STATUS_AUTO_POSTED_AND_NOTIFIED: String = "AUTO_POSTED_AND_NOTIFIED"
        const val STATUS_ALREADY_NOTIFIED: String = "ALREADY_NOTIFIED"
        const val STATUS_PERMISSION_DENIED: String = "PERMISSION_DENIED"

        internal var dependenciesResolver: (Context) -> RecurringAndBudgetWorkerDependencies =
            ::defaultDependenciesResolver

        private fun defaultDependenciesResolver(context: Context): RecurringAndBudgetWorkerDependencies {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                PlanningEntryPoint::class.java,
            )
            return RecurringAndBudgetWorkerDependencies(
                recurringRuleRepository = entryPoint.recurringRuleRepository(),
                budgetAlertEvaluator = entryPoint.budgetAlertEvaluator(),
                alertPreferencesRepository = entryPoint.budgetAndRecurringPreferencesRepository(),
                privacyPreferencesRepository = entryPoint.privacyPreferencesRepository(),
                notifier = entryPoint.planningAlertNotifier(),
                clock = entryPoint.networkClock(),
            )
        }

        internal fun resetDependenciesResolver() {
            dependenciesResolver = ::defaultDependenciesResolver
        }
    }
}
