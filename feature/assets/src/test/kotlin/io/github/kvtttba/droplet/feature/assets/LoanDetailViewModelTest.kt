package io.github.kvtttba.droplet.feature.assets

import androidx.lifecycle.SavedStateHandle
import io.github.kvtttba.droplet.core.database.dao.AccountCurrencyBalanceRow
import io.github.kvtttba.droplet.core.database.dao.PeriodSummaryRow
import io.github.kvtttba.droplet.core.database.dao.TransactionFilter
import io.github.kvtttba.droplet.core.database.repository.AutoPostDueInstallmentsReport
import io.github.kvtttba.droplet.core.database.repository.CreateLoanPlanRequest
import io.github.kvtttba.droplet.core.database.repository.EarlyPayoffLoanRequest
import io.github.kvtttba.droplet.core.database.repository.EarlyPayoffLoanResult
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerTransactionRecord
import io.github.kvtttba.droplet.core.database.repository.LoanDetailSnapshot
import io.github.kvtttba.droplet.core.database.repository.LoanPlanCreatedResult
import io.github.kvtttba.droplet.core.database.repository.LoanRepository
import io.github.kvtttba.droplet.core.database.repository.RepayInstallmentRequest
import io.github.kvtttba.droplet.core.database.repository.UpdateLoanPlanParametersRequest
import io.github.kvtttba.droplet.core.database.seed.SeedResult
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.loan.LoanPlanSummary
import io.github.kvtttba.droplet.core.domain.loan.LoanScheduleCalculator
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.InstallmentStatus
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.LoanPlan
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.time.LocalDate
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
class LoanDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val loanAccount = Account(
        id = "loan:plan-1",
        name = "笔记本电脑分期",
        type = AccountType.LIABILITY,
        subType = AccountSubType.LOAN,
        currency = Currency.CNY,
    )
    private val debitAccount = Account(
        id = "acct:debit",
        name = "储蓄卡",
        type = AccountType.ASSET,
        subType = AccountSubType.DEBIT_CARD,
        currency = Currency.CNY,
    )
    private val interestAccount = Account(
        id = "cat:interest",
        name = "利息手续费",
        type = AccountType.EXPENSE,
        subType = AccountSubType.CATEGORY,
        parentId = "cat:finance",
        currency = Currency.CNY,
    )

    private inner class FakeLoanRepository : LoanRepository {
        val plan = LoanPlan(
            id = "plan-1",
            name = "笔记本电脑分期",
            method = LoanMethod.FIXED_FEE,
            currency = Currency.CNY,
            totalPrincipalMinor = Money.fromDecimalString("6000.00", Currency.CNY).amountMinor,
            totalPeriods = 6,
            fixedFeePerPeriodMinor = Money.fromDecimalString("30.00", Currency.CNY).amountMinor,
            firstDueAt = LocalDate.of(2026, 11, 25).atStartOfDay(LoanScheduleCalculator.DEFAULT_ZONE_ID).toInstant().toEpochMilli(),
            monthlyRepayDay = 25,
            loanAccountId = loanAccount.id,
            paymentAccountId = debitAccount.id,
            interestExpenseAccountId = interestAccount.id,
            autoPost = false,
            remindDaysBefore = 3,
        )
        val initialSchedule = LoanScheduleCalculator.generateSchedule(plan)
        val detailFlow = MutableStateFlow<LoanDetailSnapshot?>(
            LoanDetailSnapshot(
                summary = LoanScheduleCalculator.summarize(
                    plan = plan,
                    installments = initialSchedule,
                    asOfDate = LocalDate.of(2026, 11, 23),
                ),
                loanAccount = loanAccount,
                paymentAccount = debitAccount,
                interestExpenseAccount = interestAccount,
                loanAccountBalance = Money.fromDecimalString("-6000.00", Currency.CNY),
            ),
        )

        var lastRepayRequest: RepayInstallmentRequest? = null
        var lastEarlyPayoffRequest: EarlyPayoffLoanRequest? = null
        var lastUpdateParamsRequest: UpdateLoanPlanParametersRequest? = null
        var lastArchivedState: Boolean? = null
        var deleteResult: Result<Unit> = Result.success(Unit)

        override fun observeLoanSummaries(includeArchived: Boolean, asOfEpochMs: Long?): Flow<List<LoanPlanSummary>> =
            MutableStateFlow(listOfNotNull(detailFlow.value?.summary))

        override fun observeLoanDetail(planId: String, asOfEpochMs: Long?): Flow<LoanDetailSnapshot?> =
            detailFlow

        override suspend fun getLoanSummariesSnapshot(includeArchived: Boolean, asOfEpochMs: Long?): List<LoanPlanSummary> =
            listOfNotNull(detailFlow.value?.summary)

        override suspend fun createLoanPlan(request: CreateLoanPlanRequest): LoanPlanCreatedResult = error("unused")

        override suspend fun updateLoanPlanParameters(request: UpdateLoanPlanParametersRequest): LoanPlan {
            lastUpdateParamsRequest = request
            val current = detailFlow.value!!.summary.plan
            val updated = current.copy(
                name = request.name ?: current.name,
                autoPost = request.autoPost ?: current.autoPost,
                remindDaysBefore = if (request.clearRemindDaysBefore) null else (request.remindDaysBefore ?: current.remindDaysBefore),
            )
            detailFlow.value = detailFlow.value!!.copy(
                summary = LoanScheduleCalculator.summarize(
                    plan = updated,
                    installments = detailFlow.value!!.summary.installments,
                    asOfDate = LocalDate.of(2026, 11, 23),
                ),
            )
            return updated
        }

        override suspend fun repayInstallment(request: RepayInstallmentRequest): Transaction {
            lastRepayRequest = request
            val cur = detailFlow.value!!
            val updatedInsts = cur.summary.installments.map {
                if (it.id == request.installmentId) {
                    it.copy(status = InstallmentStatus.PAID, transactionId = "tx-repay-1", paidAt = request.occurredAt)
                } else {
                    it
                }
            }
            detailFlow.value = cur.copy(
                summary = LoanScheduleCalculator.summarize(
                    plan = cur.summary.plan,
                    installments = updatedInsts,
                    asOfDate = LocalDate.of(2026, 11, 23),
                ),
                loanAccountBalance = Money.fromDecimalString("-5000.00", Currency.CNY),
            )
            return Transaction(
                id = "tx-repay-1",
                occurredAt = request.occurredAt ?: 0L,
                displayType = TransactionDisplayType.LOAN_REPAY,
                postings = emptyList(),
            )
        }

        override suspend fun autoPostDueInstallments(asOfEpochMs: Long?): AutoPostDueInstallmentsReport =
            AutoPostDueInstallmentsReport(0, emptyList(), 0, emptyList())

        override suspend fun earlyPayoffLoan(request: EarlyPayoffLoanRequest): EarlyPayoffLoanResult {
            lastEarlyPayoffRequest = request
            val cur = detailFlow.value!!
            val quote = LoanScheduleCalculator.calculateEarlyPayoff(
                plan = cur.summary.plan,
                installments = cur.summary.installments,
                extraFeeOrInterest = request.extraFeeOrInterest ?: Money.zero(Currency.CNY),
            )
            val pendingIds = cur.summary.installments
                .filter { it.status == InstallmentStatus.PENDING }
                .map { it.id }
            return EarlyPayoffLoanResult(
                transaction = Transaction(
                    id = "tx-early-1",
                    occurredAt = request.occurredAt ?: 0L,
                    displayType = TransactionDisplayType.LOAN_REPAY,
                    postings = emptyList(),
                ),
                settledInstallmentIds = pendingIds,
                quote = quote,
            )
        }

        override suspend fun setLoanPlanArchived(planId: String, archived: Boolean, nowEpochMs: Long?) {
            lastArchivedState = archived
        }

        override suspend fun deleteLoanPlanIfUnused(planId: String): Result<Unit> = deleteResult
    }

    private inner class FakeLedgerRepository : LedgerRepository {
        override suspend fun ensureSeeded(nowEpochMs: Long): SeedResult = SeedResult(0, 0)
        override fun observeAllAccounts(includeArchived: Boolean, includeSystem: Boolean): Flow<List<Account>> =
            MutableStateFlow(listOf(loanAccount, debitAccount, interestAccount))
        override fun observeOperationalAccounts(includeArchived: Boolean): Flow<List<Account>> =
            MutableStateFlow(listOf(loanAccount, debitAccount))
        override fun observeCategories(type: AccountType, includeArchived: Boolean): Flow<List<Account>> =
            MutableStateFlow(if (type == AccountType.EXPENSE) listOf(interestAccount) else emptyList())
        override fun observeAccountBalances(status: TransactionStatus): Flow<List<AccountCurrencyBalanceRow>> =
            MutableStateFlow(emptyList())
        override suspend fun getAccountById(accountId: String): Account? = null
        override suspend fun saveAccount(account: Account, openingBalance: Money?, openingExchangeRateOverride: ExchangeRateSnapshot?, nowEpochMs: Long) = Unit
        override suspend fun setAccountArchived(accountId: String, archived: Boolean, nowEpochMs: Long) = Unit
        override suspend fun deleteAccountIfUnused(accountId: String): Result<Unit> = Result.success(Unit)
        override fun observeTags(includeArchived: Boolean): Flow<List<Tag>> = MutableStateFlow(emptyList())
        override suspend fun saveTag(tag: Tag): Result<Unit> = Result.success(Unit)
        override suspend fun setTagArchived(tagId: String, archived: Boolean, nowEpochMs: Long) = Unit
        override suspend fun deleteTag(tagId: String) = Unit
        override fun observeFilteredTransactions(filter: TransactionFilter): Flow<List<LedgerTransactionRecord>> =
            MutableStateFlow(emptyList())
        override fun observeFilteredPeriodSummary(baseCurrency: Currency, filter: TransactionFilter): Flow<PeriodSummaryRow> =
            MutableStateFlow(PeriodSummaryRow(0L, 0L, 0))
        override fun observePeriodSummary(baseCurrency: Currency, startInclusive: Long?, endExclusive: Long?, status: TransactionStatus): Flow<PeriodSummaryRow> =
            MutableStateFlow(PeriodSummaryRow(0L, 0L, 0))
        override suspend fun getTransactionById(transactionId: String): Transaction? = null
        override suspend fun saveTransaction(transaction: Transaction, allowArchivedAccounts: Boolean) = Unit
        override suspend fun voidTransaction(transactionId: String, nowEpochMs: Long) = Unit
        override suspend fun restoreTransaction(transactionId: String, nowEpochMs: Long) = Unit
        override suspend fun deleteTransaction(transactionId: String) = Unit
        override fun observeExchangeRateTable(): Flow<ExchangeRateTable> = MutableStateFlow(ExchangeRateTable.empty())
        override suspend fun getExchangeRateTable(): ExchangeRateTable = ExchangeRateTable.empty()
        override suspend fun upsertExchangeRate(rate: CachedExchangeRate) = Unit
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
    fun loadsScheduleFiltersRepaysEarlyPayoffUpdatesParamsAndDeletes() = runTest(testDispatcher) {
        val loanRepo = FakeLoanRepository()
        val vm = LoanDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(LoanDetailViewModel.NAV_ARG_PLAN_ID to "plan-1")),
            loanRepository = loanRepo,
            ledgerRepository = FakeLedgerRepository(),
            clock = NetworkClock { 1_764_216_000_000L },
        )

        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.summary)
        assertEquals(6, vm.uiState.value.visibleInstallments.size)
        assertEquals(Money.fromDecimalString("6000.00", Currency.CNY), vm.uiState.value.summary!!.remainingPrincipal)

        // Toggle autoPost
        vm.toggleAutoPost(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.summary!!.plan.autoPost)

        // Repay first installment
        val firstInstId = vm.uiState.value.summary!!.installments.first().id
        var repayErr: String? = "not-called"
        vm.repayInstallment(firstInstId, debitAccount.id, "首期确认") { repayErr = it }
        advanceUntilIdle()
        assertNull(repayErr)
        assertEquals(firstInstId, loanRepo.lastRepayRequest?.installmentId)
        assertEquals(1, vm.uiState.value.summary!!.paidPeriods)
        assertEquals(Money.fromDecimalString("5000.00", Currency.CNY), vm.uiState.value.summary!!.remainingPrincipal)

        // Filter PAID vs PENDING
        vm.selectTab(LoanScheduleFilterTab.PAID)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.visibleInstallments.size)
        vm.selectTab(LoanScheduleFilterTab.PENDING)
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.visibleInstallments.size)

        // Early payoff with ¥15.00 extra fee
        var payoffErr: String? = "not-called"
        vm.earlyPayoff(debitAccount.id, "15.00", "结清", true) { payoffErr = it }
        advanceUntilIdle()
        assertNull(payoffErr)
        assertEquals(Money.fromDecimalString("15.00", Currency.CNY), loanRepo.lastEarlyPayoffRequest?.extraFeeOrInterest)
        assertTrue(loanRepo.lastEarlyPayoffRequest?.archiveAfterPayoff == true)

        // Update parameters
        var paramErr: String? = "not-called"
        vm.updateParameters("新贷款名称", debitAccount.id, interestAccount.id, false, "5", "备注") { paramErr = it }
        advanceUntilIdle()
        assertNull(paramErr)
        assertEquals("新贷款名称", loanRepo.lastUpdateParamsRequest?.name)
        assertEquals(5, loanRepo.lastUpdateParamsRequest?.remindDaysBefore)
        assertFalse(vm.uiState.value.summary!!.plan.autoPost)

        // Archive & Delete
        vm.setArchived(true)
        advanceUntilIdle()
        assertEquals(true, loanRepo.lastArchivedState)

        var deleteErr: String? = "not-called"
        vm.deleteLoanPlan { deleteErr = it }
        advanceUntilIdle()
        assertNull(deleteErr)
        assertTrue(vm.uiState.value.isDeleted)

        collector.cancel()
    }
}
