package io.github.kvtttba.droplet.feature.assets

import androidx.lifecycle.SavedStateHandle
import io.github.kvtttba.droplet.core.database.dao.AccountCurrencyBalanceRow
import io.github.kvtttba.droplet.core.database.dao.PeriodSummaryRow
import io.github.kvtttba.droplet.core.database.dao.TransactionFilter
import io.github.kvtttba.droplet.core.database.repository.CreditCardDetailSnapshot
import io.github.kvtttba.droplet.core.database.repository.CreditCardRepaymentRequest
import io.github.kvtttba.droplet.core.database.repository.CreditCardRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerTransactionRecord
import io.github.kvtttba.droplet.core.database.repository.UpdateCreditCardParametersRequest
import io.github.kvtttba.droplet.core.database.seed.SeedResult
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementCalculator
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementSummary
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferences
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferencesRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreditCardDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val nowMs = LocalDate.of(2026, 11, 23).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private val card = Account(
        id = "acct:cc_cmb",
        name = "招行信用卡",
        type = AccountType.LIABILITY,
        subType = AccountSubType.CREDIT_CARD,
        currency = Currency.CNY,
        creditLimitMinor = Money.fromDecimalString("50000.00", Currency.CNY).amountMinor,
        billingDay = 5,
        dueDay = 25,
        remindDaysBefore = 3,
    )
    private val debitCny = Account(
        id = "acct:debit_cny",
        name = "储蓄卡",
        type = AccountType.ASSET,
        subType = AccountSubType.DEBIT_CARD,
        currency = Currency.CNY,
    )
    private val debitUsd = Account(
        id = "acct:debit_usd",
        name = "美元资金户",
        type = AccountType.ASSET,
        subType = AccountSubType.INVEST_CASH,
        currency = Currency.USD,
    )
    private val foodCat = Account(
        id = "cat:food",
        name = "午餐",
        type = AccountType.EXPENSE,
        subType = AccountSubType.CATEGORY,
        currency = Currency.CNY,
    )

    private class FakeCreditCardRepo(
        initialDetail: CreditCardDetailSnapshot,
    ) : CreditCardRepository {
        val detailFlow = MutableStateFlow<CreditCardDetailSnapshot?>(initialDetail)
        var lastRepayRequest: CreditCardRepaymentRequest? = null
        var lastUpdateParamsRequest: UpdateCreditCardParametersRequest? = null

        override fun observeCreditCardSummaries(
            includeArchived: Boolean,
            defaultRemindDaysBefore: Int,
            asOfEpochMs: Long?,
        ): Flow<List<CreditCardStatementSummary>> = MutableStateFlow(listOfNotNull(detailFlow.value?.summary))

        override fun observeCreditCardDetail(
            accountId: String,
            defaultRemindDaysBefore: Int,
            asOfEpochMs: Long?,
        ): Flow<CreditCardDetailSnapshot?> = detailFlow

        override fun observeAccountTransactions(
            accountId: String,
            status: TransactionStatus,
        ): Flow<List<LedgerTransactionRecord>> = MutableStateFlow(emptyList())

        override suspend fun getCreditCardSummariesSnapshot(
            includeArchived: Boolean,
            defaultRemindDaysBefore: Int,
            asOfEpochMs: Long?,
        ): List<CreditCardStatementSummary> = listOfNotNull(detailFlow.value?.summary)

        override suspend fun recordRepayment(request: CreditCardRepaymentRequest): Transaction {
            lastRepayRequest = request
            return Transaction(
                id = "tx-repay-1",
                occurredAt = request.occurredAt,
                displayType = TransactionDisplayType.TRANSFER,
                postings = emptyList(),
            )
        }

        override suspend fun updateCreditCardParameters(request: UpdateCreditCardParametersRequest): Account {
            lastUpdateParamsRequest = request
            return detailFlow.value!!.summary.account.copy(
                creditLimitMinor = request.creditLimitMinor,
                billingDay = request.billingDay,
                dueDay = request.dueDay,
                remindDaysBefore = request.remindDaysBefore,
            )
        }
    }

    private class FakeLedgerRepo(
        accounts: List<Account>,
        rates: ExchangeRateTable,
    ) : LedgerRepository {
        private val accountsFlow = MutableStateFlow(accounts)
        private val ratesFlow = MutableStateFlow(rates)
        override suspend fun ensureSeeded(nowEpochMs: Long): SeedResult = SeedResult(0, 0)
        override fun observeAllAccounts(includeArchived: Boolean, includeSystem: Boolean): Flow<List<Account>> = accountsFlow
        override fun observeOperationalAccounts(includeArchived: Boolean): Flow<List<Account>> = accountsFlow
        override fun observeCategories(type: AccountType, includeArchived: Boolean): Flow<List<Account>> = MutableStateFlow(emptyList())
        override fun observeAccountBalances(status: TransactionStatus): Flow<List<AccountCurrencyBalanceRow>> = MutableStateFlow(emptyList())
        override suspend fun getAccountById(accountId: String): Account? = accountsFlow.value.firstOrNull { it.id == accountId }
        override suspend fun saveAccount(account: Account, openingBalance: Money?, openingExchangeRateOverride: ExchangeRateSnapshot?, nowEpochMs: Long) = Unit
        override suspend fun setAccountArchived(accountId: String, archived: Boolean, nowEpochMs: Long) = Unit
        override suspend fun deleteAccountIfUnused(accountId: String): Result<Unit> = Result.success(Unit)
        override fun observeTags(includeArchived: Boolean): Flow<List<Tag>> = MutableStateFlow(emptyList())
        override suspend fun saveTag(tag: Tag): Result<Unit> = Result.success(Unit)
        override suspend fun setTagArchived(tagId: String, archived: Boolean, nowEpochMs: Long) = Unit
        override suspend fun deleteTag(tagId: String) = Unit
        override fun observeFilteredTransactions(filter: TransactionFilter): Flow<List<LedgerTransactionRecord>> = MutableStateFlow(emptyList())
        override fun observeFilteredPeriodSummary(baseCurrency: Currency, filter: TransactionFilter): Flow<PeriodSummaryRow> = MutableStateFlow(PeriodSummaryRow(0L, 0L, 0))
        override fun observePeriodSummary(baseCurrency: Currency, startInclusive: Long?, endExclusive: Long?, status: TransactionStatus): Flow<PeriodSummaryRow> = MutableStateFlow(PeriodSummaryRow(0L, 0L, 0))
        override suspend fun getTransactionById(transactionId: String): Transaction? = null
        override suspend fun saveTransaction(transaction: Transaction, allowArchivedAccounts: Boolean) = Unit
        override suspend fun voidTransaction(transactionId: String, nowEpochMs: Long) = Unit
        override suspend fun restoreTransaction(transactionId: String, nowEpochMs: Long) = Unit
        override suspend fun deleteTransaction(transactionId: String) = Unit
        override fun observeExchangeRateTable(): Flow<ExchangeRateTable> = ratesFlow
        override suspend fun getExchangeRateTable(): ExchangeRateTable = ratesFlow.value
        override suspend fun upsertExchangeRate(rate: CachedExchangeRate) = Unit
    }

    private class FakeReminderPrefsRepo : CreditCardReminderPreferencesRepository {
        val state = MutableStateFlow(CreditCardReminderPreferences())
        override val preferences: Flow<CreditCardReminderPreferences> = state
        override suspend fun currentPreferences(): CreditCardReminderPreferences = state.value
        override suspend fun setRemindersEnabled(enabled: Boolean) {
            state.value = state.value.copy(remindersEnabled = enabled)
        }

        override suspend fun setDefaultRemindDaysBefore(days: Int) {
            state.value = state.value.copy(defaultRemindDaysBefore = days)
        }

        override suspend fun markNotifiedOnDate(accountIds: Collection<String>, localDateIso: String) = Unit
        override suspend fun clearNotificationHistory(accountId: String?) = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsStatementTabsSubmitsRepaymentUpdatesParamsAndTogglesReminders() = runTest(testDispatcher) {
        val engine = LedgerEngine(Currency.CNY)
        val billedTx = engine.buildExpense(
            meta = TransactionMeta(
                id = "tx-1",
                occurredAt = LocalDate.of(2026, 10, 20).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                payee = "简餐馆",
            ),
            expenseCategoryAccountId = foodCat.id,
            paymentAccountId = card.id,
            amount = Money.fromDecimalString("1200.00", Currency.CNY),
        )
        val unbilledTx = engine.buildExpense(
            meta = TransactionMeta(
                id = "tx-2",
                occurredAt = LocalDate.of(2026, 11, 18).atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
                payee = "地铁出行",
            ),
            expenseCategoryAccountId = foodCat.id,
            paymentAccountId = card.id,
            amount = Money.fromDecimalString("300.00", Currency.CNY),
        )
        val summary = CreditCardStatementCalculator.calculate(
            account = card,
            transactions = listOf(billedTx, unbilledTx),
            asOfEpochMs = nowMs,
            zoneId = zone,
        )
        val snapshot = CreditCardDetailSnapshot(
            summary = summary,
            billedTransactions = listOf(LedgerTransactionRecord(billedTx, emptyList())),
            unbilledTransactions = listOf(LedgerTransactionRecord(unbilledTx, emptyList())),
            allTransactions = listOf(
                LedgerTransactionRecord(unbilledTx, emptyList()),
                LedgerTransactionRecord(billedTx, emptyList()),
            ),
        )
        val rates = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.20", ExchangeRateSource.MARKET_CACHE, nowMs),
        )

        val creditRepo = FakeCreditCardRepo(snapshot)
        val ledgerRepo = FakeLedgerRepo(listOf(card, debitCny, debitUsd, foodCat), rates)
        val reminderPrefs = FakeReminderPrefsRepo()
        val vm = CreditCardDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(CreditCardDetailViewModel.NAV_ARG_ACCOUNT_ID to card.id)),
            creditCardRepository = creditRepo,
            ledgerRepository = ledgerRepo,
            reminderPreferencesRepository = reminderPrefs,
            clock = NetworkClock { nowMs },
        )

        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        // Default tab selects BILLED when statement due > 0; same-currency payment account is sorted first
        assertEquals(CreditCardStatementTab.BILLED, vm.uiState.value.selectedTab)
        assertEquals(1, vm.uiState.value.billedTransactions.size)
        assertEquals(1, vm.uiState.value.unbilledTransactions.size)
        assertEquals(debitCny.id, vm.uiState.value.paymentAccounts.first().id)

        // Switch to UNBILLED tab
        vm.selectTab(CreditCardStatementTab.UNBILLED)
        advanceUntilIdle()
        assertEquals(CreditCardStatementTab.UNBILLED, vm.uiState.value.selectedTab)
        assertEquals("tx-2", vm.uiState.value.activeTransactions.single().id)

        // One-tap repayment submission
        var repayError: String? = "pending"
        vm.recordRepayment(
            paymentAccountId = debitCny.id,
            repaymentAmountText = "1200.00",
            paidFromAmountText = "",
            feeText = "",
            note = "还上期账单",
        ) { err -> repayError = err }
        advanceUntilIdle()
        assertNull(repayError)
        assertNotNull(creditRepo.lastRepayRequest)
        assertEquals(Money.fromDecimalString("1200.00", Currency.CNY), creditRepo.lastRepayRequest!!.repaymentAmount)

        // Validation error on invalid billingDay (32)
        var invalidParamErr: String? = null
        vm.updateCreditParameters("50000", "32", "25", "3") { invalidParamErr = it }
        advanceUntilIdle()
        assertNotNull(invalidParamErr)

        // Valid parameter update
        var validParamErr: String? = "pending"
        vm.updateCreditParameters("60000", "10", "28", "5") { validParamErr = it }
        advanceUntilIdle()
        assertNull(validParamErr)
        assertEquals(10, creditRepo.lastUpdateParamsRequest?.billingDay)
        assertEquals(28, creditRepo.lastUpdateParamsRequest?.dueDay)
        assertEquals(5, creditRepo.lastUpdateParamsRequest?.remindDaysBefore)

        // Toggle local reminders off
        vm.setRemindersEnabled(false)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.remindersEnabled)

        // Cross-currency estimate helper
        val estimatedUsd = CreditCardDetailViewModel.estimateCrossCurrencyPaidAmount(
            repaymentAmountText = "720.00",
            cardCurrency = Currency.CNY,
            paymentAccount = debitUsd,
            exchangeRateTable = rates,
        )
        assertEquals(Money.fromDecimalString("100.00", Currency.USD), estimatedUsd)
        assertTrue(vm.uiState.value.userMessage != null)

        collector.cancel()
    }
}
