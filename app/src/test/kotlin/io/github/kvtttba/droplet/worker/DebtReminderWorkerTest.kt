package io.github.kvtttba.droplet.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.kvtttba.droplet.core.database.repository.CreateDebtProfileRequest
import io.github.kvtttba.droplet.core.database.repository.DebtBadDebtWriteOffRequest
import io.github.kvtttba.droplet.core.database.repository.DebtBorrowRequest
import io.github.kvtttba.droplet.core.database.repository.DebtCollectRequest
import io.github.kvtttba.droplet.core.database.repository.DebtDetailSnapshot
import io.github.kvtttba.droplet.core.database.repository.DebtLendRequest
import io.github.kvtttba.droplet.core.database.repository.DebtProfileCreatedResult
import io.github.kvtttba.droplet.core.database.repository.DebtRepayRequest
import io.github.kvtttba.droplet.core.database.repository.DebtRepository
import io.github.kvtttba.droplet.core.database.repository.UpdateDebtProfileRequest
import io.github.kvtttba.droplet.core.domain.debt.DebtProfileSummary
import io.github.kvtttba.droplet.core.domain.debt.DebtSummaryCalculator
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DebtProfile
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.AppThemePreference
import io.github.kvtttba.droplet.core.security.DebtReminderPreferences
import io.github.kvtttba.droplet.core.security.DebtReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferences
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.core.security.RelockTimeout
import io.github.kvtttba.droplet.notification.DebtReminderNotifier
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DebtReminderWorkerTest {

    private val zoneId = DebtSummaryCalculator.DEFAULT_ZONE_ID
    private val todayMs = LocalDate.of(2026, 11, 28).atStartOfDay(zoneId).toInstant().toEpochMilli()

    private class FakeDebtRepository(
        private val summaries: List<DebtProfileSummary>,
    ) : DebtRepository {
        override fun observeDebtSummaries(remindDaysBefore: Int, asOfEpochMs: Long?): Flow<List<DebtProfileSummary>> =
            MutableStateFlow(summaries)
        override fun observeDebtDetail(profileId: String, remindDaysBefore: Int, asOfEpochMs: Long?): Flow<DebtDetailSnapshot?> =
            MutableStateFlow(null)
        override suspend fun getDebtSummariesSnapshot(remindDaysBefore: Int, asOfEpochMs: Long?): List<DebtProfileSummary> =
            summaries
        override suspend fun createDebtProfile(request: CreateDebtProfileRequest): DebtProfileCreatedResult = error("unused")
        override suspend fun updateDebtProfile(request: UpdateDebtProfileRequest): DebtProfile = error("unused")
        override suspend fun recordLend(request: DebtLendRequest): Transaction = error("unused")
        override suspend fun recordBorrow(request: DebtBorrowRequest): Transaction = error("unused")
        override suspend fun recordCollect(request: DebtCollectRequest): Transaction = error("unused")
        override suspend fun recordRepay(request: DebtRepayRequest): Transaction = error("unused")
        override suspend fun recordBadDebtWriteOff(request: DebtBadDebtWriteOffRequest): Transaction = error("unused")
        override suspend fun deleteDebtProfileIfUnused(profileId: String): Result<Unit> = Result.success(Unit)
    }

    private class FakeDebtReminderPrefsRepo(
        initial: DebtReminderPreferences = DebtReminderPreferences(),
    ) : DebtReminderPreferencesRepository {
        private val state = MutableStateFlow(initial)
        override val preferences: Flow<DebtReminderPreferences> = state
        override suspend fun currentPreferences(): DebtReminderPreferences = state.value
        override suspend fun setRemindersEnabled(enabled: Boolean) {
            state.value = state.value.copy(remindersEnabled = enabled)
        }
        override suspend fun setDefaultRemindDaysBefore(days: Int) {
            state.value = state.value.copy(defaultRemindDaysBefore = days)
        }
        override suspend fun markNotifiedOnDate(profileIds: Collection<String>, localDateIso: String) {
            val next = state.value.lastNotifiedDateByProfileId.toMutableMap()
            profileIds.forEach { next[it] = localDateIso }
            state.value = state.value.copy(lastNotifiedDateByProfileId = next)
        }
        override suspend fun clearNotificationHistory(profileId: String?) = Unit
    }

    private class FakePrivacyPrefsRepo(
        amountsHidden: Boolean,
    ) : PrivacyPreferencesRepository {
        private val flow = MutableStateFlow(PrivacyPreferences(amountsHidden = amountsHidden))
        override val preferences: Flow<PrivacyPreferences> = flow
        override suspend fun setBiometricLockEnabled(enabled: Boolean) = Unit
        override suspend fun setRelockTimeout(timeout: RelockTimeout) = Unit
        override suspend fun setFlagSecureEnabled(enabled: Boolean) = Unit
        override suspend fun setAmountsHidden(hidden: Boolean) = Unit
        override suspend fun toggleAmountsHidden(): Boolean = flow.value.amountsHidden
        override suspend fun setThemeMode(mode: AppThemePreference) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
    }

    private class RecordingNotifier(
        private val allowPost: Boolean = true,
    ) : DebtReminderNotifier {
        val posted = mutableListOf<Pair<String, Boolean>>()
        override fun ensureNotificationChannel() = Unit
        override fun canPostNotifications(): Boolean = allowPost
        override fun publishDueReminder(summary: DebtProfileSummary, hideAmounts: Boolean): Boolean {
            if (!allowPost) return false
            posted += summary.profile.id to hideAmounts
            return true
        }
    }

    @After
    fun tearDown() {
        DebtReminderWorker.resetDependenciesResolver()
    }

    private fun dueSoonSummary(): DebtProfileSummary {
        val recv = Account(
            id = "debt:recv:1",
            name = "张三 · 应收",
            type = AccountType.ASSET,
            subType = AccountSubType.RECEIVABLE,
            currency = Currency.CNY,
        )
        val pay = Account(
            id = "debt:pay:1",
            name = "张三 · 应付",
            type = AccountType.LIABILITY,
            subType = AccountSubType.PAYABLE,
            currency = Currency.CNY,
        )
        val debit = Account(
            id = "acct:debit",
            name = "储蓄卡",
            type = AccountType.ASSET,
            subType = AccountSubType.DEBIT_CARD,
            currency = Currency.CNY,
        )
        val dueMs = LocalDate.of(2026, 11, 30).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val profile = DebtProfile("debt:1", "张三", recv.id, pay.id, defaultDueAt = dueMs)
        val tx = LedgerEngine(Currency.CNY).buildDebtLend(
            meta = TransactionMeta(occurredAt = todayMs - 86_400_000L),
            receivableAccountId = recv.id,
            fromAccountId = debit.id,
            amount = Money.fromDecimalString("800.00", Currency.CNY),
        )
        return DebtSummaryCalculator.summarize(
            profile = profile,
            receivableAccount = recv,
            payableAccount = pay,
            transactions = listOf(tx),
            accountsById = mapOf(recv.id to recv, pay.id to pay, debit.id to debit),
            asOfEpochMs = todayMs,
            remindDaysBefore = 3,
            zoneId = zoneId,
        )
    }

    @Test
    fun doWork_notifiesOncePerDayRespectsPrivacyAndDeduplicatesSameDay() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefsRepo = FakeDebtReminderPrefsRepo()
        val notifier = RecordingNotifier(allowPost = true)
        val summary = dueSoonSummary()

        DebtReminderWorker.dependenciesResolver = {
            DebtReminderWorkerDependencies(
                debtRepository = FakeDebtRepository(listOf(summary)),
                debtReminderPreferencesRepository = prefsRepo,
                privacyPreferencesRepository = FakePrivacyPrefsRepo(amountsHidden = true),
                notifier = notifier,
                clock = NetworkClock { todayMs },
                zoneId = zoneId,
            )
        }

        val worker1 = TestListenableWorkerBuilder<DebtReminderWorker>(context).build()
        val result1 = worker1.doWork()
        assertTrue(result1 is ListenableWorker.Result.Success)
        val data1 = (result1 as ListenableWorker.Result.Success).outputData
        assertEquals(DebtReminderWorker.STATUS_NOTIFIED, data1.getString(DebtReminderWorker.KEY_STATUS))
        assertEquals(1, data1.getInt(DebtReminderWorker.KEY_NOTIFIED_COUNT, -1))
        assertEquals(listOf("debt:1" to true), notifier.posted)

        // Second run on the same day must deduplicate cleanly
        val worker2 = TestListenableWorkerBuilder<DebtReminderWorker>(context).build()
        val result2 = worker2.doWork()
        val data2 = (result2 as ListenableWorker.Result.Success).outputData
        assertEquals(DebtReminderWorker.STATUS_ALREADY_NOTIFIED_TODAY, data2.getString(DebtReminderWorker.KEY_STATUS))
        assertEquals(0, data2.getInt(DebtReminderWorker.KEY_NOTIFIED_COUNT, -1))
        assertEquals(1, data2.getInt(DebtReminderWorker.KEY_SKIPPED_DUPLICATE_COUNT, -1))
        assertEquals(1, notifier.posted.size)
    }
}
