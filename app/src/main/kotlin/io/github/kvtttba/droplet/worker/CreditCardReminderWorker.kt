package io.github.kvtttba.droplet.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import io.github.kvtttba.droplet.core.database.repository.CreditCardRepository
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementCalculator
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.di.CreditCardReminderEntryPoint
import io.github.kvtttba.droplet.notification.CreditCardReminderNotifier
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal data class CreditCardReminderWorkerDependencies(
    val creditCardRepository: CreditCardRepository,
    val reminderPreferencesRepository: CreditCardReminderPreferencesRepository,
    val privacyPreferencesRepository: PrivacyPreferencesRepository,
    val notifier: CreditCardReminderNotifier,
    val clock: NetworkClock,
    val zoneId: ZoneId = CreditCardStatementCalculator.DEFAULT_ZONE_ID,
)

/**
 * Daily local WorkManager worker scanning active credit cards inside their advance reminder window
 * (or overdue) with unpaid statement balance, dispatching local notifications with strict same-day
 * per-card deduplication.
 *
 * Resolves singletons via Hilt [EntryPointAccessors] against [CreditCardReminderEntryPoint] so
 * no `hilt-work` dependency is required.
 */
class CreditCardReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val deps = dependenciesResolver(applicationContext)
            val reminderPrefs = deps.reminderPreferencesRepository.currentPreferences()
            if (!reminderPrefs.remindersEnabled) {
                return Result.success(
                    workDataOf(
                        KEY_STATUS to STATUS_DISABLED,
                        KEY_SCANNED_COUNT to 0,
                        KEY_DUE_CANDIDATE_COUNT to 0,
                        KEY_NOTIFIED_COUNT to 0,
                        KEY_SKIPPED_DUPLICATE_COUNT to 0,
                    ),
                )
            }

            val nowMs = deps.clock.nowEpochMs()
            val todayIso = Instant.ofEpochMilli(nowMs)
                .atZone(deps.zoneId)
                .toLocalDate()
                .toString()

            val summaries = deps.creditCardRepository.getCreditCardSummariesSnapshot(
                includeArchived = false,
                defaultRemindDaysBefore = reminderPrefs.defaultRemindDaysBefore,
                asOfEpochMs = nowMs,
            )

            val dueCandidates = summaries.filter { summary ->
                summary.isInReminderWindow && summary.remainingStatementDue.isPositive
            }

            val hideAmounts = deps.privacyPreferencesRepository.preferences.first().amountsHidden
            val notifiedIds = mutableListOf<String>()
            var skippedDuplicates = 0
            var permissionBlockedCount = 0

            for (candidate in dueCandidates) {
                if (reminderPrefs.wasNotifiedOn(candidate.account.id, todayIso)) {
                    skippedDuplicates++
                    continue
                }
                val posted = deps.notifier.publishReminder(
                    summary = candidate,
                    hideAmounts = hideAmounts,
                )
                if (posted) {
                    notifiedIds += candidate.account.id
                } else {
                    permissionBlockedCount++
                }
            }

            if (notifiedIds.isNotEmpty()) {
                deps.reminderPreferencesRepository.markNotifiedOnDate(
                    accountIds = notifiedIds,
                    localDateIso = todayIso,
                )
            }

            val status = when {
                dueCandidates.isEmpty() -> STATUS_NO_DUE_CARDS
                notifiedIds.isNotEmpty() -> STATUS_NOTIFIED
                permissionBlockedCount > 0 -> STATUS_PERMISSION_DENIED
                else -> STATUS_ALREADY_NOTIFIED_TODAY
            }

            Result.success(
                workDataOf(
                    KEY_STATUS to status,
                    KEY_SCANNED_COUNT to summaries.size,
                    KEY_DUE_CANDIDATE_COUNT to dueCandidates.size,
                    KEY_NOTIFIED_COUNT to notifiedIds.size,
                    KEY_SKIPPED_DUPLICATE_COUNT to skippedDuplicates,
                    KEY_NOTIFICATION_DATE to todayIso,
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
        const val KEY_STATUS: String = "reminder_status"
        const val KEY_SCANNED_COUNT: String = "scanned_count"
        const val KEY_DUE_CANDIDATE_COUNT: String = "due_candidate_count"
        const val KEY_NOTIFIED_COUNT: String = "notified_count"
        const val KEY_SKIPPED_DUPLICATE_COUNT: String = "skipped_duplicate_count"
        const val KEY_NOTIFICATION_DATE: String = "notification_date"

        const val STATUS_DISABLED: String = "DISABLED"
        const val STATUS_NO_DUE_CARDS: String = "NO_DUE_CARDS"
        const val STATUS_NOTIFIED: String = "NOTIFIED"
        const val STATUS_ALREADY_NOTIFIED_TODAY: String = "ALREADY_NOTIFIED_TODAY"
        const val STATUS_PERMISSION_DENIED: String = "PERMISSION_DENIED"

        internal var dependenciesResolver: (Context) -> CreditCardReminderWorkerDependencies =
            ::defaultDependenciesResolver

        private fun defaultDependenciesResolver(context: Context): CreditCardReminderWorkerDependencies {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                CreditCardReminderEntryPoint::class.java,
            )
            return CreditCardReminderWorkerDependencies(
                creditCardRepository = entryPoint.creditCardRepository(),
                reminderPreferencesRepository = entryPoint.creditCardReminderPreferencesRepository(),
                privacyPreferencesRepository = entryPoint.privacyPreferencesRepository(),
                notifier = entryPoint.creditCardReminderNotifier(),
                clock = entryPoint.networkClock(),
            )
        }

        internal fun resetDependenciesResolver() {
            dependenciesResolver = ::defaultDependenciesResolver
        }
    }
}
