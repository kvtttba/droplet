package io.github.kvtttba.droplet.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.kvtttba.droplet.core.database.repository.CreditCardDetailSnapshot
import io.github.kvtttba.droplet.core.database.repository.CreditCardRepaymentRequest
import io.github.kvtttba.droplet.core.database.repository.CreditCardRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerTransactionRecord
import io.github.kvtttba.droplet.core.database.repository.UpdateCreditCardParametersRequest
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementCalculator
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementSummary
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.AppThemePreference
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferences
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferences
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.core.security.RelockTimeout
import io.github.kvtttba.droplet.notification.CreditCardReminderNotifier
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CreditCardReminderWorkerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var context: Context

    private class FakeCreditCardRepository(
        var summariesProvider: (Int, Long?) -> List<CreditCardStatementSummary> = { _, _ -> emptyList() },
        var throwError: Exception? = null,
    ) : CreditCardRepository {
        override fun observeCreditCardSummaries(
            includeArchived: Boolean,
            defaultRemindDaysBefore: Int,
            asOfEpochMs: Long?,
        ): Flow<List<CreditCardStatementSummary>> = flowOf(summariesProvider(defaultRemindDaysBefore, asOfEpochMs))

        override fun observeCreditCardDetail(
            accountId: String,
            defaultRemindDaysBefore: Int,
            asOfEpochMs: Long?,
        ): Flow<CreditCardDetailSnapshot?> = flowOf(null)

        override fun observeAccountTransactions(
            accountId: String,
            status: TransactionStatus,
        ): Flow<List<LedgerTransactionRecord>> = flowOf(emptyList())

        override suspend fun getCreditCardSummariesSnapshot(
            includeArchived: Boolean,
            defaultRemindDaysBefore: Int,
            asOfEpochMs: Long?,
        ): List<CreditCardStatementSummary> {
            throwError?.let { throw it }
            return summariesProvider(defaultRemindDaysBefore, asOfEpochMs)
        }

        override suspend fun recordRepayment(request: CreditCardRepaymentRequest): Transaction = error("unused")
        override suspend fun updateCreditCardParameters(request: UpdateCreditCardParametersRequest): Account = error("unused")
    }

    private class FakeReminderPrefsRepository(
        initial: CreditCardReminderPreferences = CreditCardReminderPreferences(),
    ) : CreditCardReminderPreferencesRepository {
        private val state = MutableStateFlow(initial)
        override val preferences: Flow<CreditCardReminderPreferences> = state
        override suspend fun currentPreferences(): CreditCardReminderPreferences = state.value
        override suspend fun setRemindersEnabled(enabled: Boolean) {
            state.value = state.value.copy(remindersEnabled = enabled)
        }

        override suspend fun setDefaultRemindDaysBefore(days: Int) {
            state.value = state.value.copy(defaultRemindDaysBefore = days)
        }

        override suspend fun markNotifiedOnDate(accountIds: Collection<String>, localDateIso: String) {
            val map = state.value.lastNotifiedDateByAccountId.toMutableMap()
            accountIds.forEach { map[it] = localDateIso }
            state.value = state.value.copy(lastNotifiedDateByAccountId = map)
        }

        override suspend fun clearNotificationHistory(accountId: String?) {
            state.value = if (accountId == null) {
                state.value.copy(lastNotifiedDateByAccountId = emptyMap())
            } else {
                state.value.copy(lastNotifiedDateByAccountId = state.value.lastNotifiedDateByAccountId - accountId)
            }
        }
    }

    private class FakePrivacyPrefsRepository(
        initial: PrivacyPreferences = PrivacyPreferences(),
    ) : PrivacyPreferencesRepository {
        private val state = MutableStateFlow(initial)
        override val preferences: Flow<PrivacyPreferences> = state
        override suspend fun setBiometricLockEnabled(enabled: Boolean) = Unit
        override suspend fun setRelockTimeout(timeout: RelockTimeout) = Unit
        override suspend fun setFlagSecureEnabled(enabled: Boolean) = Unit
        override suspend fun setAmountsHidden(hidden: Boolean) {
            state.value = state.value.copy(amountsHidden = hidden)
        }

        override suspend fun toggleAmountsHidden(): Boolean {
            val next = !state.value.amountsHidden
            state.value = state.value.copy(amountsHidden = next)
            return next
        }

        override suspend fun setThemeMode(themeMode: AppThemePreference) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
    }

    private class RecordingNotifier(
        var allowPost: Boolean = true,
    ) : CreditCardReminderNotifier {
        val posted = mutableListOf<Pair<CreditCardStatementSummary, Boolean>>()
        override fun ensureNotificationChannel() = Unit
        override fun canPostNotifications(): Boolean = allowPost
        override fun publishReminder(summary: CreditCardStatementSummary, hideAmounts: Boolean): Boolean {
            if (!allowPost) return false
            posted += summary to hideAmounts
            return true
        }
    }

    private fun epochOn(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    private fun buildCardSummary(
        id: String,
        name: String,
        dueAmountDecimal: String?,
        asOfDate: LocalDate,
        billingDay: Int = 5,
        dueDay: Int = 25,
        remindDaysBefore: Int = 3,
    ): CreditCardStatementSummary {
        val card = Account(
            id = id,
            name = name,
            type = AccountType.LIABILITY,
            subType = AccountSubType.CREDIT_CARD,
            currency = Currency.CNY,
            creditLimitMinor = Money.fromDecimalString("20000.00", Currency.CNY).amountMinor,
            billingDay = billingDay,
            dueDay = dueDay,
            remindDaysBefore = remindDaysBefore,
        )
        val engine = LedgerEngine(baseCurrency = Currency.CNY)
        val txs = if (dueAmountDecimal != null) {
            listOf(
                engine.buildExpense(
                    meta = TransactionMeta(occurredAt = epochOn(2026, 10, 18)),
                    expenseCategoryAccountId = "cat:food",
                    paymentAccountId = card.id,
                    amount = Money.fromDecimalString(dueAmountDecimal, Currency.CNY),
                ),
            )
        } else {
            emptyList()
        }
        return CreditCardStatementCalculator.calculate(
            account = card,
            transactions = txs,
            asOfDate = asOfDate,
            zoneId = zone,
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        CreditCardReminderWorker.resetDependenciesResolver()
    }

    @Test
    fun doWork_notifiesDueCardsOncePerDayAndRenotifiesNextDay() = runTest {
        var currentDay = LocalDate.of(2026, 11, 23) // 2 days before Nov 25 due date
        val dueCard = buildCardSummary("card:due", "招行信用卡", "1800.00", currentDay)
        val paidCard = buildCardSummary("card:paid", "建行信用卡", null, currentDay)
        val outsideWindowCard = buildCardSummary(
            id = "card:later",
            name = "工行信用卡",
            dueAmountDecimal = "500.00",
            asOfDate = currentDay,
            billingDay = 20,
            dueDay = 10,
            remindDaysBefore = 3,
        )

        val repo = FakeCreditCardRepository(
            summariesProvider = { _, _ -> listOf(dueCard, paidCard, outsideWindowCard) },
        )
        val reminderPrefs = FakeReminderPrefsRepository()
        val privacyPrefs = FakePrivacyPrefsRepository(PrivacyPreferences(amountsHidden = true))
        val notifier = RecordingNotifier(allowPost = true)

        CreditCardReminderWorker.dependenciesResolver = {
            CreditCardReminderWorkerDependencies(
                creditCardRepository = repo,
                reminderPreferencesRepository = reminderPrefs,
                privacyPreferencesRepository = privacyPrefs,
                notifier = notifier,
                clock = NetworkClock { currentDay.atTime(9, 0).atZone(zone).toInstant().toEpochMilli() },
                zoneId = zone,
            )
        }

        // First run on 2026-11-23: notifies only card:due and propagates hideAmounts=true
        val run1 = TestListenableWorkerBuilder<CreditCardReminderWorker>(context).build().doWork()
        assertTrue(run1 is ListenableWorker.Result.Success)
        val data1 = (run1 as ListenableWorker.Result.Success).outputData
        assertEquals(CreditCardReminderWorker.STATUS_NOTIFIED, data1.getString(CreditCardReminderWorker.KEY_STATUS))
        assertEquals(3, data1.getInt(CreditCardReminderWorker.KEY_SCANNED_COUNT, -1))
        assertEquals(1, data1.getInt(CreditCardReminderWorker.KEY_DUE_CANDIDATE_COUNT, -1))
        assertEquals(1, data1.getInt(CreditCardReminderWorker.KEY_NOTIFIED_COUNT, -1))
        assertEquals(0, data1.getInt(CreditCardReminderWorker.KEY_SKIPPED_DUPLICATE_COUNT, -1))
        assertEquals(1, notifier.posted.size)
        assertEquals("card:due", notifier.posted.single().first.account.id)
        assertTrue(notifier.posted.single().second)
        assertTrue(reminderPrefs.currentPreferences().wasNotifiedOn("card:due", "2026-11-23"))

        // Second run on SAME day 2026-11-23: deduplicated, zero new notifications
        val run2 = TestListenableWorkerBuilder<CreditCardReminderWorker>(context).build().doWork()
        assertTrue(run2 is ListenableWorker.Result.Success)
        val data2 = (run2 as ListenableWorker.Result.Success).outputData
        assertEquals(
            CreditCardReminderWorker.STATUS_ALREADY_NOTIFIED_TODAY,
            data2.getString(CreditCardReminderWorker.KEY_STATUS),
        )
        assertEquals(0, data2.getInt(CreditCardReminderWorker.KEY_NOTIFIED_COUNT, -1))
        assertEquals(1, data2.getInt(CreditCardReminderWorker.KEY_SKIPPED_DUPLICATE_COUNT, -1))
        assertEquals(1, notifier.posted.size)

        // Next day 2026-11-24: still unpaid -> notifies again
        currentDay = LocalDate.of(2026, 11, 24)
        val run3 = TestListenableWorkerBuilder<CreditCardReminderWorker>(context).build().doWork()
        val data3 = (run3 as ListenableWorker.Result.Success).outputData
        assertEquals(CreditCardReminderWorker.STATUS_NOTIFIED, data3.getString(CreditCardReminderWorker.KEY_STATUS))
        assertEquals(1, data3.getInt(CreditCardReminderWorker.KEY_NOTIFIED_COUNT, -1))
        assertEquals(2, notifier.posted.size)
        assertTrue(reminderPrefs.currentPreferences().wasNotifiedOn("card:due", "2026-11-24"))
    }

    @Test
    fun doWork_whenDisabledOrPermissionDenied_skipsSafelyWithoutMarkingNotified() = runTest {
        val today = LocalDate.of(2026, 11, 24)
        val dueCard = buildCardSummary("card:due", "招行信用卡", "800.00", today)
        val repo = FakeCreditCardRepository(summariesProvider = { _, _ -> listOf(dueCard) })
        val reminderPrefs = FakeReminderPrefsRepository(CreditCardReminderPreferences(remindersEnabled = false))
        val privacyPrefs = FakePrivacyPrefsRepository()
        val notifier = RecordingNotifier(allowPost = true)

        CreditCardReminderWorker.dependenciesResolver = {
            CreditCardReminderWorkerDependencies(
                creditCardRepository = repo,
                reminderPreferencesRepository = reminderPrefs,
                privacyPreferencesRepository = privacyPrefs,
                notifier = notifier,
                clock = NetworkClock { today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli() },
                zoneId = zone,
            )
        }

        val disabledResult = TestListenableWorkerBuilder<CreditCardReminderWorker>(context).build().doWork()
        val disabledData = (disabledResult as ListenableWorker.Result.Success).outputData
        assertEquals(CreditCardReminderWorker.STATUS_DISABLED, disabledData.getString(CreditCardReminderWorker.KEY_STATUS))
        assertTrue(notifier.posted.isEmpty())

        // Enable reminders, deny notification permission -> does NOT stamp dedup date
        reminderPrefs.setRemindersEnabled(true)
        notifier.allowPost = false
        val deniedResult = TestListenableWorkerBuilder<CreditCardReminderWorker>(context).build().doWork()
        val deniedData = (deniedResult as ListenableWorker.Result.Success).outputData
        assertEquals(
            CreditCardReminderWorker.STATUS_PERMISSION_DENIED,
            deniedData.getString(CreditCardReminderWorker.KEY_STATUS),
        )
        assertEquals(0, deniedData.getInt(CreditCardReminderWorker.KEY_NOTIFIED_COUNT, -1))
        assertFalse(reminderPrefs.currentPreferences().wasNotifiedOn("card:due", "2026-11-24"))
    }

    @Test
    fun doWork_onUnexpectedException_retriesThenFailsAfterMaxAttempts() = runTest {
        val repo = FakeCreditCardRepository(throwError = IllegalStateException("DB read error"))
        CreditCardReminderWorker.dependenciesResolver = {
            CreditCardReminderWorkerDependencies(
                creditCardRepository = repo,
                reminderPreferencesRepository = FakeReminderPrefsRepository(),
                privacyPreferencesRepository = FakePrivacyPrefsRepository(),
                notifier = RecordingNotifier(),
                clock = NetworkClock { epochOn(2026, 11, 24) },
                zoneId = zone,
            )
        }

        val retryRun = TestListenableWorkerBuilder<CreditCardReminderWorker>(context)
            .setRunAttemptCount(1)
            .build()
            .doWork()
        assertEquals(ListenableWorker.Result.retry(), retryRun)

        val finalRun = TestListenableWorkerBuilder<CreditCardReminderWorker>(context)
            .setRunAttemptCount(CreditCardReminderWorker.MAX_RETRY_ATTEMPTS)
            .build()
            .doWork()
        assertTrue(finalRun is ListenableWorker.Result.Failure)
    }
}
