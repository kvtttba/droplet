package io.github.kvtttba.droplet.core.domain.loan

import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidator
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.LoanPlan
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionSource
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoanRepaymentHelperTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val engine = LedgerEngine(baseCurrency = Currency.CNY)

    private val loanAccount = Account(
        id = "loan:plan-1",
        name = "车贷分期",
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
    private val interestExpenseAccount = Account(
        id = "cat:interest",
        name = "利息手续费",
        type = AccountType.EXPENSE,
        subType = AccountSubType.CATEGORY,
        currency = Currency.CNY,
    )

    private fun samplePlan(
        fixedFeeDecimal: String? = "50.00",
    ): LoanPlan = LoanPlan(
        id = "plan-1",
        name = "车贷分期",
        method = LoanMethod.FIXED_FEE,
        currency = Currency.CNY,
        totalPrincipalMinor = Money.fromDecimalString("6000.00", Currency.CNY).amountMinor,
        totalPeriods = 6,
        fixedFeePerPeriodMinor = fixedFeeDecimal?.let { Money.fromDecimalString(it, Currency.CNY).amountMinor },
        firstDueAt = LocalDate.of(2026, 11, 25).atStartOfDay(zone).toInstant().toEpochMilli(),
        monthlyRepayDay = 25,
        loanAccountId = loanAccount.id,
        paymentAccountId = debitAccount.id,
        interestExpenseAccountId = interestExpenseAccount.id,
        autoPost = true,
    )

    @Test
    fun buildInstallmentRepaymentTransaction_generatesBalancedSplitForManualAndLoanAuto() {
        val plan = samplePlan(fixedFeeDecimal = "50.00")
        val firstInstallment = LoanScheduleCalculator.generateSchedule(plan, zone).first()
        val accountsMap = listOf(loanAccount, debitAccount, interestExpenseAccount).associateBy { it.id }

        val manualTx = LoanRepaymentHelper.buildInstallmentRepaymentTransaction(
            ledgerEngine = engine,
            plan = plan,
            installment = firstInstallment,
            loanAccount = loanAccount,
            paymentAccount = debitAccount,
            interestExpenseAccount = interestExpenseAccount,
            occurredAt = firstInstallment.dueAt,
            source = TransactionSource.MANUAL,
            accounts = accountsMap,
        )

        assertEquals(TransactionDisplayType.LOAN_REPAY, manualTx.displayType)
        assertEquals(TransactionSource.MANUAL, manualTx.source)
        assertEquals(3, manualTx.postings.size)
        assertEquals(0L, manualTx.postings.sumOf { it.amountMinor })
        assertEquals(
            Money.fromDecimalString("1000.00", Currency.CNY).amountMinor,
            manualTx.postings.first { it.accountId == loanAccount.id }.amountMinor,
        )
        assertEquals(
            Money.fromDecimalString("50.00", Currency.CNY).amountMinor,
            manualTx.postings.first { it.accountId == interestExpenseAccount.id }.amountMinor,
        )
        assertEquals(
            Money.fromDecimalString("-1050.00", Currency.CNY).amountMinor,
            manualTx.postings.first { it.accountId == debitAccount.id }.amountMinor,
        )
        LedgerValidator.requireValid(manualTx, accountsMap)

        val autoTx = LoanRepaymentHelper.buildInstallmentRepaymentTransaction(
            ledgerEngine = engine,
            plan = plan,
            installment = firstInstallment,
            loanAccount = loanAccount,
            paymentAccount = debitAccount,
            interestExpenseAccount = interestExpenseAccount,
            occurredAt = firstInstallment.dueAt,
            source = TransactionSource.LOAN_AUTO,
            accounts = accountsMap,
        )
        assertEquals(TransactionDisplayType.LOAN_REPAY, autoTx.displayType)
        assertEquals(TransactionSource.LOAN_AUTO, autoTx.source)
        assertTrue(autoTx.note.orEmpty().contains("自动还款"))
    }

    @Test
    fun buildInstallmentRepaymentTransaction_zeroInterestOmitsZeroPostingLine() {
        val zeroPlan = samplePlan(fixedFeeDecimal = "0.00")
        val firstInstallment = LoanScheduleCalculator.generateSchedule(zeroPlan, zone).first()
        val accountsMap = listOf(loanAccount, debitAccount, interestExpenseAccount).associateBy { it.id }

        val tx = LoanRepaymentHelper.buildInstallmentRepaymentTransaction(
            ledgerEngine = engine,
            plan = zeroPlan,
            installment = firstInstallment,
            loanAccount = loanAccount,
            paymentAccount = debitAccount,
            interestExpenseAccount = interestExpenseAccount,
            occurredAt = firstInstallment.dueAt,
            accounts = accountsMap,
        )

        assertEquals(2, tx.postings.size)
        assertEquals(0L, tx.postings.sumOf { it.amountMinor })
        assertTrue(tx.postings.none { it.accountId == interestExpenseAccount.id })
    }

    @Test
    fun buildEarlyPayoffTransaction_buildsBalancedEntryWithOptionalExtraFee() {
        val plan = samplePlan(fixedFeeDecimal = "50.00")
        val schedule = LoanScheduleCalculator.generateSchedule(plan, zone)
        val accountsMap = listOf(loanAccount, debitAccount, interestExpenseAccount).associateBy { it.id }
        val quote = LoanScheduleCalculator.calculateEarlyPayoff(
            plan = plan,
            installments = schedule,
            extraFeeOrInterest = Money.fromDecimalString("25.00", Currency.CNY),
        )

        val payoffTx = LoanRepaymentHelper.buildEarlyPayoffTransaction(
            ledgerEngine = engine,
            plan = plan,
            quote = quote,
            loanAccount = loanAccount,
            paymentAccount = debitAccount,
            interestExpenseAccount = interestExpenseAccount,
            occurredAt = schedule.first().dueAt,
            accounts = accountsMap,
        )

        assertEquals(TransactionDisplayType.LOAN_REPAY, payoffTx.displayType)
        assertEquals(3, payoffTx.postings.size)
        assertEquals(0L, payoffTx.postings.sumOf { it.amountMinor })
        assertEquals(
            Money.fromDecimalString("6000.00", Currency.CNY).amountMinor,
            payoffTx.postings.first { it.accountId == loanAccount.id }.amountMinor,
        )
        assertEquals(
            Money.fromDecimalString("25.00", Currency.CNY).amountMinor,
            payoffTx.postings.first { it.accountId == interestExpenseAccount.id }.amountMinor,
        )
        assertEquals(
            Money.fromDecimalString("-6025.00", Currency.CNY).amountMinor,
            payoffTx.postings.first { it.accountId == debitAccount.id }.amountMinor,
        )
        LedgerValidator.requireValid(payoffTx, accountsMap)
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildInstallmentRepaymentTransaction_rejectsMismatchedPaymentCurrency() {
        val plan = samplePlan()
        val inst = LoanScheduleCalculator.generateSchedule(plan, zone).first()
        val usdDebit = debitAccount.copy(id = "acct:usd", currency = Currency.USD)
        LoanRepaymentHelper.buildInstallmentRepaymentTransaction(
            ledgerEngine = engine,
            plan = plan,
            installment = inst,
            loanAccount = loanAccount,
            paymentAccount = usdDebit,
            interestExpenseAccount = interestExpenseAccount,
            occurredAt = inst.dueAt,
        )
    }
}
