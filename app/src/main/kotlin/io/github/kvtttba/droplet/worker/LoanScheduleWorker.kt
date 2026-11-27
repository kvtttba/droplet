package io.github.kvtttba.droplet.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import io.github.kvtttba.droplet.core.database.repository.LoanRepository
import io.github.kvtttba.droplet.core.domain.loan.LoanScheduleCalculator
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.LoanReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.di.LoanScheduleEntryPoint
import io.github.kvtttba.droplet.notification.LoanReminderNotifier
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal data class LoanScheduleWorkerDependencies(
    val loanRepository: LoanRepository,
    val loanReminderPreferencesRepository: LoanReminderPreferencesRepository,
    val privacyPreferencesRepository: PrivacyPreferencesRepository,
    val notifier: LoanReminderNotifier,
    val clock: NetworkClock,
    val zoneId: ZoneId = LoanScheduleCalculator.DEFAULT_ZONE_ID,
)

/**
 * Daily offline WorkManager worker handling installment loan automation:
 * 1. Idempotently auto-posts all due `PENDING` (`transactionId == null`) installments on active
 *    plans with `autoPost = true` via [LoanRepository.autoPostDueInstallments].
 * 2. Scans remaining active `PENDING` installments inside each plan's `remindDaysBefore` window
 *    (or overdue), dispatching local notifications with strict same-day per-installment deduplication
 *    and respecting global privacy amount masking.
 *
 * Resolves singletons via Hilt [EntryPointAccessors] against [LoanScheduleEntryPoint] so no
 * `hilt-work` dependency is required.
 */
class LoanScheduleWorker(
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

            // 1. Idempotent auto-post for due PENDING installments on autoPost=true plans
            val autoPostReport = deps.loanRepository.autoPostDueInstallments(asOfEpochMs = nowMs)
            val reminderPrefs = deps.loanReminderPreferencesRepository.currentPreferences()
            if (reminderPrefs.remindersEnabled) {
                for (posted in autoPostReport.postedItems) {
                    deps.notifier.publishAutoPostedNotice(
                        plan = posted.plan,
                        installment = posted.installment,
                        hideAmounts = hideAmounts,
                    )
                }
            }

            // 2. Scan post-auto-post active loan summaries for upcoming/overdue reminders
            val summaries = deps.loanRepository.getLoanSummariesSnapshot(
                includeArchived = false,
                asOfEpochMs = nowMs,
            )

            var reminderCandidateCount = 0
            var skippedDuplicates = 0
            var permissionBlockedCount = 0
            val notifiedInstallmentIds = mutableListOf<String>()

            if (reminderPrefs.remindersEnabled) {
                for (summary in summaries) {
                    val candidate = summary.reminderCandidateInstallment ?: continue
                    if (!summary.isInReminderWindow) continue
                    reminderCandidateCount++

                    if (reminderPrefs.wasNotifiedOn(candidate.id, todayIso)) {
                        skippedDuplicates++
                        continue
                    }

                    val posted = deps.notifier.publishDueReminder(
                        summary = summary,
                        installment = candidate,
                        hideAmounts = hideAmounts,
                    )
                    if (posted) {
                        notifiedInstallmentIds += candidate.id
                    } else {
                        permissionBlockedCount++
                    }
                }

                if (notifiedInstallmentIds.isNotEmpty()) {
                    deps.loanReminderPreferencesRepository.markNotifiedOnDate(
                        installmentIds = notifiedInstallmentIds,
                        localDateIso = todayIso,
                    )
                }
            }

            val status = when {
                autoPostReport.postedCount > 0 && notifiedInstallmentIds.isNotEmpty() ->
                    STATUS_AUTO_POSTED_AND_NOTIFIED
                autoPostReport.postedCount > 0 -> STATUS_AUTO_POSTED
                notifiedInstallmentIds.isNotEmpty() -> STATUS_NOTIFIED
                permissionBlockedCount > 0 -> STATUS_PERMISSION_DENIED
                skippedDuplicates > 0 -> STATUS_ALREADY_NOTIFIED_TODAY
                else -> STATUS_NO_ACTION_NEEDED
            }

            Result.success(
                workDataOf(
                    KEY_STATUS to status,
                    KEY_SCANNED_PLANS_COUNT to summaries.size,
                    KEY_AUTO_POSTED_COUNT to autoPostReport.postedCount,
                    KEY_AUTO_POST_FAILED_COUNT to autoPostReport.failures.size,
                    KEY_REMINDER_CANDIDATE_COUNT to reminderCandidateCount,
                    KEY_NOTIFIED_COUNT to notifiedInstallmentIds.size,
                    KEY_SKIPPED_DUPLICATE_COUNT to skippedDuplicates,
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
        const val KEY_STATUS: String = "loan_schedule_status"
        const val KEY_SCANNED_PLANS_COUNT: String = "scanned_plans_count"
        const val KEY_AUTO_POSTED_COUNT: String = "auto_posted_count"
        const val KEY_AUTO_POST_FAILED_COUNT: String = "auto_post_failed_count"
        const val KEY_REMINDER_CANDIDATE_COUNT: String = "reminder_candidate_count"
        const val KEY_NOTIFIED_COUNT: String = "notified_count"
        const val KEY_SKIPPED_DUPLICATE_COUNT: String = "skipped_duplicate_count"
        const val KEY_SCAN_DATE: String = "scan_date"

        const val STATUS_NO_ACTION_NEEDED: String = "NO_ACTION_NEEDED"
        const val STATUS_AUTO_POSTED: String = "AUTO_POSTED"
        const val STATUS_NOTIFIED: String = "NOTIFIED"
        const val STATUS_AUTO_POSTED_AND_NOTIFIED: String = "AUTO_POSTED_AND_NOTIFIED"
        const val STATUS_ALREADY_NOTIFIED_TODAY: String = "ALREADY_NOTIFIED_TODAY"
        const val STATUS_PERMISSION_DENIED: String = "PERMISSION_DENIED"

        internal var dependenciesResolver: (Context) -> LoanScheduleWorkerDependencies =
            ::defaultDependenciesResolver

        private fun defaultDependenciesResolver(context: Context): LoanScheduleWorkerDependencies {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                LoanScheduleEntryPoint::class.java,
            )
            return LoanScheduleWorkerDependencies(
                loanRepository = entryPoint.loanRepository(),
                loanReminderPreferencesRepository = entryPoint.loanReminderPreferencesRepository(),
                privacyPreferencesRepository = entryPoint.privacyPreferencesRepository(),
                notifier = entryPoint.loanReminderNotifier(),
                clock = entryPoint.networkClock(),
            )
        }

        internal fun resetDependenciesResolver() {
            dependenciesResolver = ::defaultDependenciesResolver
        }
    }
}
