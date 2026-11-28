package io.github.kvtttba.droplet.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import io.github.kvtttba.droplet.core.database.repository.DebtRepository
import io.github.kvtttba.droplet.core.domain.debt.DebtSummaryCalculator
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.DebtReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.di.DebtReminderEntryPoint
import io.github.kvtttba.droplet.notification.DebtReminderNotifier
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal data class DebtReminderWorkerDependencies(
    val debtRepository: DebtRepository,
    val debtReminderPreferencesRepository: DebtReminderPreferencesRepository,
    val privacyPreferencesRepository: PrivacyPreferencesRepository,
    val notifier: DebtReminderNotifier,
    val clock: NetworkClock,
    val zoneId: ZoneId = DebtSummaryCalculator.DEFAULT_ZONE_ID,
)

/**
 * Daily offline WorkManager worker scanning counterparty debt profiles whose agreed due date
 * falls within `defaultRemindDaysBefore` (or is overdue) while still carrying an outstanding
 * balance, dispatching local reminders with strict per-profile same-day deduplication and
 * respecting global privacy amount masking.
 */
class DebtReminderWorker(
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

            val reminderPrefs = deps.debtReminderPreferencesRepository.currentPreferences()
            if (!reminderPrefs.remindersEnabled) {
                return Result.success(
                    workDataOf(
                        KEY_STATUS to STATUS_DISABLED,
                        KEY_SCANNED_COUNT to 0,
                        KEY_NOTIFIED_COUNT to 0,
                        KEY_SKIPPED_DUPLICATE_COUNT to 0,
                        KEY_SCAN_DATE to todayIso,
                    ),
                )
            }

            val hideAmounts = deps.privacyPreferencesRepository.preferences.first().amountsHidden
            val summaries = deps.debtRepository.getDebtSummariesSnapshot(
                remindDaysBefore = reminderPrefs.defaultRemindDaysBefore,
                asOfEpochMs = nowMs,
            )

            var candidateCount = 0
            var skippedDuplicates = 0
            var permissionBlockedCount = 0
            val notifiedProfileIds = mutableListOf<String>()

            for (summary in summaries) {
                if (!summary.hasOutstandingBalance || !summary.isInReminderWindow) continue
                candidateCount++
                val profileId = summary.profile.id
                if (reminderPrefs.wasNotifiedOn(profileId, todayIso)) {
                    skippedDuplicates++
                    continue
                }
                val posted = deps.notifier.publishDueReminder(
                    summary = summary,
                    hideAmounts = hideAmounts,
                )
                if (posted) {
                    notifiedProfileIds += profileId
                } else {
                    permissionBlockedCount++
                }
            }

            if (notifiedProfileIds.isNotEmpty()) {
                deps.debtReminderPreferencesRepository.markNotifiedOnDate(
                    profileIds = notifiedProfileIds,
                    localDateIso = todayIso,
                )
            }

            val status = when {
                notifiedProfileIds.isNotEmpty() -> STATUS_NOTIFIED
                permissionBlockedCount > 0 -> STATUS_PERMISSION_DENIED
                skippedDuplicates > 0 -> STATUS_ALREADY_NOTIFIED_TODAY
                else -> STATUS_NO_REMINDERS_DUE
            }

            Result.success(
                workDataOf(
                    KEY_STATUS to status,
                    KEY_SCANNED_COUNT to summaries.size,
                    KEY_CANDIDATE_COUNT to candidateCount,
                    KEY_NOTIFIED_COUNT to notifiedProfileIds.size,
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
        const val KEY_STATUS: String = "debt_reminder_status"
        const val KEY_SCANNED_COUNT: String = "scanned_count"
        const val KEY_CANDIDATE_COUNT: String = "candidate_count"
        const val KEY_NOTIFIED_COUNT: String = "notified_count"
        const val KEY_SKIPPED_DUPLICATE_COUNT: String = "skipped_duplicate_count"
        const val KEY_SCAN_DATE: String = "scan_date"

        const val STATUS_DISABLED: String = "DISABLED"
        const val STATUS_NO_REMINDERS_DUE: String = "NO_REMINDERS_DUE"
        const val STATUS_NOTIFIED: String = "NOTIFIED"
        const val STATUS_ALREADY_NOTIFIED_TODAY: String = "ALREADY_NOTIFIED_TODAY"
        const val STATUS_PERMISSION_DENIED: String = "PERMISSION_DENIED"

        internal var dependenciesResolver: (Context) -> DebtReminderWorkerDependencies =
            ::defaultDependenciesResolver

        private fun defaultDependenciesResolver(context: Context): DebtReminderWorkerDependencies {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                DebtReminderEntryPoint::class.java,
            )
            return DebtReminderWorkerDependencies(
                debtRepository = entryPoint.debtRepository(),
                debtReminderPreferencesRepository = entryPoint.debtReminderPreferencesRepository(),
                privacyPreferencesRepository = entryPoint.privacyPreferencesRepository(),
                notifier = entryPoint.debtReminderNotifier(),
                clock = entryPoint.networkClock(),
            )
        }

        internal fun resetDependenciesResolver() {
            dependenciesResolver = ::defaultDependenciesResolver
        }
    }
}
