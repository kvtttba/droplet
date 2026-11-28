package io.github.kvtttba.droplet.feature.assets

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.repository.CreateDebtProfileRequest
import io.github.kvtttba.droplet.core.database.repository.LocalDebtRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LocalReimbursementAndRefundRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.debt.DebtProfileStatus
import io.github.kvtttba.droplet.core.domain.debt.DebtSummaryCalculator
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.DebtReminderPreferences
import io.github.kvtttba.droplet.core.security.DebtReminderPreferencesRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DebtAndReimbursementViewModelsTest {

    private lateinit var database: DropletDatabase
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var debtRepository: LocalDebtRepository
    private lateinit var reimburseRefundRepository: LocalReimbursementAndRefundRepository
    private lateinit var engine: LedgerEngine
    private val testDispatcher = UnconfinedTestDispatcher()
    private val zoneId = DebtSummaryCalculator.DEFAULT_ZONE_ID
    private val fixedNowMs = LocalDate.of(2026, 11, 28)
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()

    private class InMemoryDebtReminderPrefsRepo : DebtReminderPreferencesRepository {
        private val flow = MutableStateFlow(DebtReminderPreferences())
        override val preferences: Flow<DebtReminderPreferences> = flow
        override suspend fun currentPreferences(): DebtReminderPreferences = flow.value
        override suspend fun setRemindersEnabled(enabled: Boolean) {
            flow.value = flow.value.copy(remindersEnabled = enabled)
        }
        override suspend fun setDefaultRemindDaysBefore(days: Int) {
            flow.value = flow.value.copy(defaultRemindDaysBefore = days)
        }
        override suspend fun markNotifiedOnDate(profileIds: Collection<String>, localDateIso: String) = Unit
        override suspend fun clearNotificationHistory(profileId: String?) = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        engine = LedgerEngine(baseCurrency = Currency.CNY)
        val writer = LedgerTransactionWriter(database)
        val seeder = DefaultLedgerSeeder(database)
        val clock = NetworkClock { fixedNowMs }

        ledgerRepository = LocalLedgerRepository(database, writer, seeder, engine)
        debtRepository = LocalDebtRepository(database, writer, seeder, engine, clock, zoneId)
        reimburseRefundRepository = LocalReimbursementAndRefundRepository(database, writer, seeder, engine, clock)
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun debtDetailViewModel_handlesLendCollectWithInterestAndBadDebtWriteOff() = runBlocking {
        ledgerRepository.ensureSeeded(fixedNowMs)
        val created = debtRepository.createDebtProfile(
            CreateDebtProfileRequest(name = "测试张三", currency = Currency.CNY),
        )
        val vm = DebtDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(DebtDetailViewModel.NAV_ARG_PROFILE_ID to created.profile.id)),
            debtRepository = debtRepository,
            debtReminderPreferencesRepository = InMemoryDebtReminderPrefsRepo(),
            ledgerRepository = ledgerRepository,
            clock = NetworkClock { fixedNowMs },
        )

        val loaded = vm.uiState.awaitFirst { it.summary != null && it.fundingAccounts.isNotEmpty() }
        assertEquals("测试张三", loaded.summary?.profile?.name)

        var err: String? = "unset"
        vm.recordLend(DefaultSeedCatalog.AccountIds.DEBIT_CARD, "1000", "借款周转") { err = it }
        val afterLend = vm.uiState.awaitFirst {
            it.summary?.receivableBalance == Money.fromDecimalString("1000.00", Currency.CNY)
        }
        assertNull(err)
        assertEquals(1, afterLend.transactions.size)

        vm.recordCollect(
            depositAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            principalText = "400",
            interestText = "20",
            interestIncomeAccountId = DefaultSeedCatalog.AccountIds.INC_INVEST_INTEREST,
            note = "首笔还本付息",
        ) { err = it }
        val afterCollect = vm.uiState.awaitFirst {
            it.summary?.receivableBalance == Money.fromDecimalString("600.00", Currency.CNY) &&
                it.summary?.totalInterestIncome == Money.fromDecimalString("20.00", Currency.CNY)
        }
        assertNull(err)
        assertEquals(2, afterCollect.transactions.size)

        vm.recordBadDebtWriteOff(
            amountText = "600",
            badDebtExpenseAccountId = DefaultSeedCatalog.AccountIds.EXP_FINANCE_BAD_DEBT,
            note = "核销余款",
        ) { err = it }
        val afterWriteOff = vm.uiState.awaitFirst {
            it.summary?.status == DebtProfileStatus.SETTLED &&
                it.summary?.totalBadDebtWriteOff == Money.fromDecimalString("600.00", Currency.CNY)
        }
        assertNull(err)
        assertEquals(Money.zero(Currency.CNY), afterWriteOff.summary?.receivableBalance)
    }

    @Test
    fun reimbursementListViewModel_handlesShortfallSettlementAndBatchSettle() = runBlocking {
        ledgerRepository.ensureSeeded(fixedNowMs)
        val adv1 = engine.buildReimbursableAdvance(
            meta = TransactionMeta(occurredAt = fixedNowMs, payee = "出差打车"),
            reimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
            paymentAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
            amount = Money.fromDecimalString("500.00", Currency.CNY),
        )
        val adv2 = engine.buildReimbursableAdvance(
            meta = TransactionMeta(occurredAt = fixedNowMs + 1000L, payee = "会议茶歇"),
            reimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("200.00", Currency.CNY),
        )
        ledgerRepository.saveTransaction(adv1)
        ledgerRepository.saveTransaction(adv2)

        val vm = ReimbursementListViewModel(
            reimbursementRepository = reimburseRefundRepository,
            ledgerRepository = ledgerRepository,
            clock = NetworkClock { fixedNowMs + 2000L },
        )

        val initial = vm.uiState.awaitFirst { it.overview.pendingCount == 2 }
        assertEquals(Money.fromDecimalString("700.00", Currency.CNY), initial.overview.primaryPendingTotal)

        // Settle adv1 with shortfall: ¥450 received + ¥50 personal expense
        val item1 = initial.overview.pendingItems.first { it.advanceTransaction.id == adv1.id }
        var err: String? = "unset"
        vm.settleSingle(
            item = item1,
            depositAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            receivedAmountText = "450.00",
            convertShortfallToExpense = true,
            shortfallExpenseAccountId = DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TAXI,
            surplusIncomeAccountId = null,
            note = "超标自付50",
        ) { err = it }

        val afterSingle = vm.uiState.awaitFirst { it.overview.pendingCount == 1 && it.overview.settledCount == 1 }
        assertNull(err)
        val settledItem1 = afterSingle.overview.settledItems.single()
        assertEquals(ReimburseState.SETTLED, settledItem1.derivedState)
        assertEquals(Money.fromDecimalString("50.00", Currency.CNY), settledItem1.shortfallExpenseTotal)

        // Batch settle remaining adv2
        vm.toggleSelectAllPending()
        val withSelected = vm.uiState.awaitFirst { it.selectedPendingItems.size == 1 }
        assertTrue(adv2.id in withSelected.selectedAdvanceIds)

        vm.batchSettleSelected(DefaultSeedCatalog.AccountIds.DEBIT_CARD, "批量到账") { err = it }
        val finalState = vm.uiState.awaitFirst { it.overview.pendingCount == 0 && it.overview.settledCount == 2 }
        assertNull(err)
        assertEquals(Money.zero(Currency.CNY), finalState.overview.primaryPendingTotal)
    }

    private suspend fun <T> Flow<T>.awaitFirst(predicate: (T) -> Boolean): T =
        withTimeout(10_000L) {
            first(predicate)
        }
}
