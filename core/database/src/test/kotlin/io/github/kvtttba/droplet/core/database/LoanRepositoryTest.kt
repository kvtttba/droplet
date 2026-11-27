package io.github.kvtttba.droplet.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.repository.CreateLoanPlanRequest
import io.github.kvtttba.droplet.core.database.repository.EarlyPayoffLoanRequest
import io.github.kvtttba.droplet.core.database.repository.LocalLoanRepository
import io.github.kvtttba.droplet.core.database.repository.RepayInstallmentRequest
import io.github.kvtttba.droplet.core.database.repository.UpdateLoanPlanParametersRequest
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.loan.LoanPlanStatus
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.InstallmentStatus
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionSource
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LoanRepositoryTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var database: DropletDatabase
    private lateinit var repository: LocalLoanRepository
    private var currentNowMs: Long = 0L

    private fun epochOn(year: Int, month: Int, day: Int, hour: Int = 9): Long =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DropletDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        currentNowMs = epochOn(2026, 11, 20)
        repository = LocalLoanRepository(
            database = database,
            transactionWriter = LedgerTransactionWriter(database),
            seeder = DefaultLedgerSeeder(database),
            ledgerEngine = LedgerEngine(baseCurrency = Currency.CNY),
            clock = NetworkClock { currentNowMs },
            zoneId = zone,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createLoanPlan_atomicallyCreatesLoanAccountOpeningBalanceAndScheduleThenManualRepay() = runBlocking {
        val created = repository.createLoanPlan(
            CreateLoanPlanRequest(
                id = "loan-pc",
                name = "电脑分期",
                method = LoanMethod.FIXED_FEE,
                currency = Currency.CNY,
                totalPrincipalMinor = Money.fromDecimalString("6000.00", Currency.CNY).amountMinor,
                totalPeriods = 6,
                fixedFeePerPeriodMinor = Money.fromDecimalString("30.00", Currency.CNY).amountMinor,
                firstDueAt = epochOn(2026, 12, 15, hour = 0),
                monthlyRepayDay = 15,
                paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                interestExpenseAccountId = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
                autoPost = false,
                remindDaysBefore = 3,
            ),
        )

        assertEquals(AccountType.LIABILITY, created.loanAccount.type)
        assertEquals(AccountSubType.LOAN, created.loanAccount.subType)
        assertEquals(6, created.installments.size)
        assertNotNull(created.openingTransaction)

        // Verify detail snapshot reflects -6,000.00 liability balance and 6 pending periods
        val initialDetail = repository.observeLoanDetail("loan-pc", asOfEpochMs = currentNowMs).first()
        assertNotNull(initialDetail)
        assertEquals(Money.fromDecimalString("-6000.00", Currency.CNY), initialDetail!!.loanAccountBalance)
        assertEquals(Money.fromDecimalString("6000.00", Currency.CNY), initialDetail.summary.remainingPrincipal)
        assertEquals(6, initialDetail.summary.remainingPeriods)

        // Manually repay period 1
        val period1 = created.installments.first()
        val repayTx = repository.repayInstallment(
            RepayInstallmentRequest(
                loanPlanId = "loan-pc",
                installmentId = period1.id,
                occurredAt = epochOn(2026, 12, 15, hour = 10),
            ),
        )
        assertEquals(TransactionDisplayType.LOAN_REPAY, repayTx.displayType)
        assertEquals(TransactionSource.MANUAL, repayTx.source)

        val afterRepay = repository.observeLoanDetail("loan-pc", asOfEpochMs = epochOn(2026, 12, 16)).first()!!
        assertEquals(Money.fromDecimalString("-5000.00", Currency.CNY), afterRepay.loanAccountBalance)
        assertEquals(Money.fromDecimalString("5000.00", Currency.CNY), afterRepay.summary.remainingPrincipal)
        assertEquals(1, afterRepay.summary.paidPeriods)
        assertEquals(5, afterRepay.summary.remainingPeriods)
        assertEquals(InstallmentStatus.PAID, afterRepay.summary.installments.first().status)
        assertEquals(repayTx.id, afterRepay.summary.installments.first().transactionId)

        // Re-paying the already-paid period must fail
        val duplicateAttempt = runCatching {
            repository.repayInstallment(
                RepayInstallmentRequest(
                    loanPlanId = "loan-pc",
                    installmentId = period1.id,
                ),
            )
        }
        assertTrue(duplicateAttempt.isFailure)
    }

    @Test
    fun autoPostDueInstallments_isStrictlyIdempotentAndOnlyTouchesAutoPostDuePendingPeriods() = runBlocking {
        // Loan A: autoPost = true, Period 1 due Nov 15, Period 2 due Dec 15
        repository.createLoanPlan(
            CreateLoanPlanRequest(
                id = "loan-auto",
                name = "自动扣款车贷",
                method = LoanMethod.EQUAL_PRINCIPAL,
                currency = Currency.CNY,
                totalPrincipalMinor = Money.fromDecimalString("12000.00", Currency.CNY).amountMinor,
                totalPeriods = 12,
                annualRateDecimal = "0.06",
                firstDueAt = epochOn(2026, 11, 15, hour = 0),
                monthlyRepayDay = 15,
                paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                autoPost = true,
                remindDaysBefore = 3,
            ),
        )
        // Loan B: autoPost = false, Period 1 also due Nov 15 -> must NOT auto-post
        repository.createLoanPlan(
            CreateLoanPlanRequest(
                id = "loan-manual",
                name = "手动确认分期",
                method = LoanMethod.FIXED_FEE,
                currency = Currency.CNY,
                totalPrincipalMinor = Money.fromDecimalString("3000.00", Currency.CNY).amountMinor,
                totalPeriods = 3,
                fixedFeePerPeriodMinor = Money.fromDecimalString("20.00", Currency.CNY).amountMinor,
                firstDueAt = epochOn(2026, 11, 15, hour = 0),
                monthlyRepayDay = 15,
                paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                autoPost = false,
                remindDaysBefore = 3,
            ),
        )

        // Run auto-post as of Nov 20 -> posts ONLY loan-auto period 1
        val report1 = repository.autoPostDueInstallments(asOfEpochMs = epochOn(2026, 11, 20))
        assertEquals(1, report1.scannedDueCount)
        assertEquals(1, report1.postedCount)
        assertTrue(report1.failures.isEmpty())
        val postedItem = report1.postedItems.single()
        assertEquals("loan-auto", postedItem.plan.id)
        assertEquals(1, postedItem.installment.periodNumber)

        val persistedTx = database.transactionDao().getById(postedItem.transactionId)
        assertNotNull(persistedTx)
        assertEquals(TransactionSource.LOAN_AUTO, persistedTx!!.transaction.source)
        assertEquals(TransactionDisplayType.LOAN_REPAY, persistedTx.transaction.displayType)

        // Second run on same date -> 100% idempotent no-op!
        val report2 = repository.autoPostDueInstallments(asOfEpochMs = epochOn(2026, 11, 20))
        assertEquals(0, report2.scannedDueCount)
        assertEquals(0, report2.postedCount)

        // Loan B remains 100% PENDING
        val manualDetail = repository.observeLoanDetail("loan-manual", asOfEpochMs = epochOn(2026, 11, 20)).first()!!
        assertEquals(0, manualDetail.summary.paidPeriods)
        assertEquals(3, manualDetail.summary.remainingPeriods)

        // Advance to Dec 15 -> posts ONLY loan-auto period 2
        val report3 = repository.autoPostDueInstallments(asOfEpochMs = epochOn(2026, 12, 15))
        assertEquals(1, report3.postedCount)
        assertEquals(2, report3.postedItems.single().installment.periodNumber)

        val autoDetail = repository.observeLoanDetail("loan-auto", asOfEpochMs = epochOn(2026, 12, 15)).first()!!
        assertEquals(2, autoDetail.summary.paidPeriods)
        assertEquals(Money.fromDecimalString("-10000.00", Currency.CNY), autoDetail.loanAccountBalance)
    }

    @Test
    fun earlyPayoff_settlesAllRemainingPeriodsZerosLiabilityAndWaivesFutureInterest() = runBlocking {
        val created = repository.createLoanPlan(
            CreateLoanPlanRequest(
                id = "loan-early",
                name = "提前结清测试贷",
                method = LoanMethod.FIXED_FEE,
                currency = Currency.CNY,
                totalPrincipalMinor = Money.fromDecimalString("6000.00", Currency.CNY).amountMinor,
                totalPeriods = 6,
                fixedFeePerPeriodMinor = Money.fromDecimalString("50.00", Currency.CNY).amountMinor,
                firstDueAt = epochOn(2026, 11, 10, hour = 0),
                monthlyRepayDay = 10,
                paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            ),
        )
        // Repay period 1 normally (principal 1000 + fee 50)
        repository.repayInstallment(
            RepayInstallmentRequest(
                loanPlanId = "loan-early",
                installmentId = created.installments.first().id,
                occurredAt = epochOn(2026, 11, 10, hour = 10),
            ),
        )

        // Early payoff remaining 5 periods with ¥20.00 extra fee
        val payoff = repository.earlyPayoffLoan(
            EarlyPayoffLoanRequest(
                loanPlanId = "loan-early",
                extraFeeOrInterest = Money.fromDecimalString("20.00", Currency.CNY),
                occurredAt = epochOn(2026, 11, 25, hour = 14),
            ),
        )

        assertEquals(5, payoff.settledInstallmentIds.size)
        assertEquals(Money.fromDecimalString("5000.00", Currency.CNY), payoff.quote.remainingPrincipal)
        assertEquals(Money.fromDecimalString("230.00", Currency.CNY), payoff.quote.netInterestSaved)

        val detail = repository.observeLoanDetail("loan-early", asOfEpochMs = epochOn(2026, 11, 26)).first()!!
        assertEquals(LoanPlanStatus.PAID_OFF, detail.summary.status)
        assertEquals(6, detail.summary.paidPeriods)
        assertEquals(0, detail.summary.remainingPeriods)
        assertEquals(Money.zero(Currency.CNY), detail.summary.remainingPrincipal)
        assertEquals(Money.zero(Currency.CNY), detail.loanAccountBalance)
        // Actual paid interest = period 1 (50.00) + early payoff fee (20.00) = 70.00 (future 250 waived)
        assertEquals(Money.fromDecimalString("70.00", Currency.CNY), detail.summary.paidInterest)
    }

    @Test
    fun updateParametersArchiveAndSafeDeleteBehavior() = runBlocking {
        val created = repository.createLoanPlan(
            CreateLoanPlanRequest(
                id = "loan-unused",
                name = "可删除分期",
                method = LoanMethod.FIXED_FEE,
                currency = Currency.CNY,
                totalPrincipalMinor = Money.fromDecimalString("3000.00", Currency.CNY).amountMinor,
                totalPeriods = 3,
                firstDueAt = epochOn(2026, 12, 20, hour = 0),
                monthlyRepayDay = 20,
                paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                autoPost = false,
                remindDaysBefore = 3,
            ),
        )

        val updated = repository.updateLoanPlanParameters(
            UpdateLoanPlanParametersRequest(
                planId = "loan-unused",
                name = "更新名称分期",
                autoPost = true,
                remindDaysBefore = 5,
            ),
        )
        assertEquals("更新名称分期", updated.name)
        assertTrue(updated.autoPost)
        assertEquals(5, updated.remindDaysBefore)
        assertEquals("更新名称分期", database.accountDao().getById(created.loanAccount.id)?.name)

        // Archive hides from default active list
        repository.setLoanPlanArchived("loan-unused", archived = true)
        assertTrue(repository.getLoanSummariesSnapshot(includeArchived = false).isEmpty())
        assertEquals(1, repository.getLoanSummariesSnapshot(includeArchived = true).size)

        // Unarchive restores
        repository.setLoanPlanArchived("loan-unused", archived = false)
        assertEquals(1, repository.getLoanSummariesSnapshot(includeArchived = false).size)

        // Delete unused loan succeeds and cleans up opening-only loan liability account
        val deleteResult = repository.deleteLoanPlanIfUnused("loan-unused")
        assertTrue(deleteResult.isSuccess)
        assertTrue(repository.getLoanSummariesSnapshot(includeArchived = true).isEmpty())
        assertEquals(null, database.accountDao().getById(created.loanAccount.id))

        // Loan with paid period cannot be deleted
        val used = repository.createLoanPlan(
            CreateLoanPlanRequest(
                id = "loan-used",
                name = "已还不可删分期",
                method = LoanMethod.FIXED_FEE,
                currency = Currency.CNY,
                totalPrincipalMinor = Money.fromDecimalString("2000.00", Currency.CNY).amountMinor,
                totalPeriods = 2,
                firstDueAt = epochOn(2026, 12, 20, hour = 0),
                monthlyRepayDay = 20,
                paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            ),
        )
        repository.repayInstallment(
            RepayInstallmentRequest(
                loanPlanId = "loan-used",
                installmentId = used.installments.first().id,
            ),
        )
        val blockedDelete = repository.deleteLoanPlanIfUnused("loan-used")
        assertFalse(blockedDelete.isSuccess)
    }
}
