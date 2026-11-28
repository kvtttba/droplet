package io.github.kvtttba.droplet.worker

import io.github.kvtttba.droplet.core.database.repository.BudgetRepository
import io.github.kvtttba.droplet.core.domain.automation.BudgetAlertLevel
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.BudgetAndRecurringPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.notification.PlanningAlertNotifier
import kotlinx.coroutines.flow.first

data class BudgetAlertEvaluationResult(
    val scannedBudgetCount: Int = 0,
    val candidateCount: Int = 0,
    val notifiedCount: Int = 0,
    val skippedDuplicateCount: Int = 0,
    val permissionBlockedCount: Int = 0,
)

/**
 * Evaluates all enabled budgets against real-time double-entry net spending and dispatches
 * deduplicated local alerts when a budget crosses its warning threshold or exceeds 100%.
 *
 * Shared by both [RecurringAndBudgetWorker] (daily offline scan) and post-commit ledger mutations
 * (immediate feedback after manual, template, or AI entries).
 */
class BudgetAlertEvaluator(
    private val budgetRepository: BudgetRepository,
    private val alertPreferencesRepository: BudgetAndRecurringPreferencesRepository,
    private val privacyPreferencesRepository: PrivacyPreferencesRepository,
    private val notifier: PlanningAlertNotifier,
    private val clock: NetworkClock,
) {

    suspend fun evaluateAndNotify(asOfEpochMs: Long? = null): BudgetAlertEvaluationResult {
        val alertPrefs = alertPreferencesRepository.currentPreferences()
        if (!alertPrefs.budgetAlertsEnabled) {
            return BudgetAlertEvaluationResult()
        }

        val nowMs = asOfEpochMs ?: clock.nowEpochMs()
        val overview = budgetRepository.getBudgetOverviewSnapshot(
            asOfEpochMs = nowMs,
            onlyEnabled = true,
        )
        val hideAmounts = privacyPreferencesRepository.preferences.first().amountsHidden

        var candidateCount = 0
        var skippedDuplicates = 0
        var permissionBlocked = 0
        val newlySentKeys = mutableListOf<String>()

        for (progress in overview.progresses) {
            if (progress.alertLevel == BudgetAlertLevel.NORMAL) continue
            candidateCount++
            val key = progress.alertDedupKey
            if (alertPrefs.hasSentBudgetAlert(key)) {
                skippedDuplicates++
                continue
            }
            val posted = notifier.publishBudgetAlert(
                progress = progress,
                hideAmounts = hideAmounts,
            )
            if (posted) {
                newlySentKeys += key
            } else {
                permissionBlocked++
            }
        }

        if (newlySentKeys.isNotEmpty()) {
            alertPreferencesRepository.markBudgetAlertsSent(newlySentKeys)
        }

        return BudgetAlertEvaluationResult(
            scannedBudgetCount = overview.progresses.size,
            candidateCount = candidateCount,
            notifiedCount = newlySentKeys.size,
            skippedDuplicateCount = skippedDuplicates,
            permissionBlockedCount = permissionBlocked,
        )
    }
}
