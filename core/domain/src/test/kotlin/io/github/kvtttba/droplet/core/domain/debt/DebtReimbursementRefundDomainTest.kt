package io.github.kvtttba.droplet.core.domain.debt

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerAggregator
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidator
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DebtProfile
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.Transaction
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtReimbursementRefundDomainTest {

    private val engine = LedgerEngine(baseCurrency = Currency.CNY)
    private val zoneId = DebtSummaryCalculator.DEFAULT_ZONE_ID

    private val debitCny = Account(
        id = "acct:debit_cny",
        name = "储蓄卡",
        type = AccountType.ASSET,
        subType = AccountSubType.DEBIT_CARD,
        currency = Currency.CNY,
    )
    private val creditCny = Account(
        id = "acct:credit_cny",
        name = "信用卡",
        type = AccountType.LIABILITY,
        subType = AccountSubType.CREDIT_CARD,
        currency = Currency.CNY,
    )
    private val usdCash = Account(
        id = "acct:usd_cash",
        name = "美元账户",
        type = AccountType.ASSET,
        subType = AccountSubType.INVEST_CASH,
        currency = Currency.USD,
    )
    private val reimburseCny = Account(
        id = "acct:reimburse_cny",
        name = "待报销",
        type = AccountType.ASSET,
        subType = AccountSubType.REIMBURSEMENT,
        currency = Currency.CNY,
        isSystem = true,
    )
    private val recvZhang = Account(
        id = "debt:recv:zhang",
        name = "张三 · 应收",
        type = AccountType.ASSET,
        subType = AccountSubType.RECEIVABLE,
        currency = Currency.CNY,
    )
    private val payZhang = Account(
        id = "debt:pay:zhang",
        name = "张三 · 应付",
        type = AccountType.LIABILITY,
        subType = AccountSubType.PAYABLE,
        currency = Currency.CNY,
    )
    private val interestIncome = Account(
        id = "cat:inc:interest",
        name = "利息收入",
        type = AccountType.INCOME,
        subType = AccountSubType.CATEGORY,
        currency = Currency.CNY,
    )
    private val bonusIncome = Account(
        id = "cat:inc:bonus",
        name = "奖金补贴",
        type = AccountType.INCOME,
        subType = AccountSubType.CATEGORY,
        currency = Currency.CNY,
    )
    private val interestExpense = Account(
        id = "cat:exp:interest",
        name = "利息手续费",
        type = AccountType.EXPENSE,
        subType = AccountSubType.CATEGORY,
        currency = Currency.CNY,
    )
    private val badDebtExpense = Account(
        id = "cat:exp:bad_debt",
        name = "坏账损失",
        type = AccountType.EXPENSE,
        subType = AccountSubType.CATEGORY,
        currency = Currency.CNY,
    )
    private val shoppingExpense = Account(
        id = "cat:exp:shopping",
        name = "日用百货",
        type = AccountType.EXPENSE,
        subType = AccountSubType.CATEGORY,
        currency = Currency.CNY,
    )
    private val taxiExpense = Account(
        id = "cat:exp:taxi",
        name = "打车",
        type = AccountType.EXPENSE,
        subType = AccountSubType.CATEGORY,
        currency = Currency.CNY,
    )

    private val allAccounts = (listOf(
        debitCny, creditCny, usdCash, reimburseCny, recvZhang, payZhang,
        interestIncome, bonusIncome, interestExpense, badDebtExpense, shoppingExpense, taxiExpense,
    ) + SystemAccounts.allSystemEquityAccounts()).associateBy { it.id }

    private fun epochOf(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zoneId).toInstant().toEpochMilli()

    @Test
    fun debtLifecycle_lendPartialCollectWithInterestAndBadDebtWriteOffMaintainAlgebraicInvariants() {
        val profile = DebtProfile(
            id = "profile:zhang",
            name = "张三",
            receivableAccountId = recvZhang.id,
            payableAccountId = payZhang.id,
            defaultDueAt = epochOf(2026, 12, 1),
        )

        // 1. Lend ¥1,000
        val lendTx = DebtTransactionHelper.buildLendTransaction(
            ledgerEngine = engine,
            profile = profile,
            receivableAccount = recvZhang,
            fromAccount = debitCny,
            amount = Money.fromDecimalString("1000.00", Currency.CNY),
            occurredAt = epochOf(2026, 11, 1),
        )
        LedgerValidator.requireValid(lendTx, allAccounts)

        val afterLend = DebtSummaryCalculator.summarize(
            profile = profile,
            receivableAccount = recvZhang,
            payableAccount = payZhang,
            transactions = listOf(lendTx),
            accountsById = allAccounts,
            asOfDate = LocalDate.of(2026, 11, 29),
            remindDaysBefore = 3,
        )
        assertEquals(Money.fromDecimalString("1000.00", Currency.CNY), afterLend.receivableBalance)
        assertEquals(Money.zero(Currency.CNY), afterLend.payableBalance)
        assertEquals(Money.fromDecimalString("1000.00", Currency.CNY), afterLend.netBalance)
        assertEquals(DebtProfileStatus.DUE_SOON, afterLend.status)
        assertEquals(2L, afterLend.daysUntilDue)
        assertTrue(afterLend.isInReminderWindow)

        // 2. Partial collect ¥400 principal + ¥20 interest income
        val collectTx = DebtTransactionHelper.buildCollectTransaction(
            ledgerEngine = engine,
            profile = profile,
            receivableAccount = recvZhang,
            depositAccount = debitCny,
            principalAmount = Money.fromDecimalString("400.00", Currency.CNY),
            interestAmount = Money.fromDecimalString("20.00", Currency.CNY),
            interestIncomeAccount = interestIncome,
            currentReceivableBalance = afterLend.receivableBalance,
            occurredAt = epochOf(2026, 11, 15),
        )
        LedgerValidator.requireValid(collectTx, allAccounts)

        val afterCollect = DebtSummaryCalculator.summarize(
            profile = profile,
            receivableAccount = recvZhang,
            payableAccount = payZhang,
            transactions = listOf(lendTx, collectTx),
            accountsById = allAccounts,
            asOfDate = LocalDate.of(2026, 12, 3),
            remindDaysBefore = 3,
        )
        assertEquals(Money.fromDecimalString("600.00", Currency.CNY), afterCollect.receivableBalance)
        assertEquals(Money.fromDecimalString("400.00", Currency.CNY), afterCollect.totalCollectedPrincipal)
        assertEquals(Money.fromDecimalString("20.00", Currency.CNY), afterCollect.totalInterestIncome)
        assertEquals(DebtProfileStatus.OVERDUE, afterCollect.status)
        assertEquals(2L, afterCollect.overdueDays)

        // Over-collection must be rejected
        var rejectedOverCollect = false
        try {
            DebtTransactionHelper.buildCollectTransaction(
                ledgerEngine = engine,
                profile = profile,
                receivableAccount = recvZhang,
                depositAccount = debitCny,
                principalAmount = Money.fromDecimalString("600.01", Currency.CNY),
                currentReceivableBalance = afterCollect.receivableBalance,
                occurredAt = epochOf(2026, 12, 4),
            )
        } catch (_: IllegalArgumentException) {
            rejectedOverCollect = true
        }
        assertTrue(rejectedOverCollect)

        // 3. Bad-debt write-off remaining ¥600
        val writeOffTx = DebtTransactionHelper.buildBadDebtWriteOffTransaction(
            ledgerEngine = engine,
            profile = profile,
            receivableAccount = recvZhang,
            badDebtExpenseAccount = badDebtExpense,
            amount = Money.fromDecimalString("600.00", Currency.CNY),
            currentReceivableBalance = afterCollect.receivableBalance,
            occurredAt = epochOf(2026, 12, 5),
        )
        LedgerValidator.requireValid(writeOffTx, allAccounts)

        val afterWriteOff = DebtSummaryCalculator.summarize(
            profile = profile,
            receivableAccount = recvZhang,
            payableAccount = payZhang,
            transactions = listOf(lendTx, collectTx, writeOffTx),
            accountsById = allAccounts,
            asOfDate = LocalDate.of(2026, 12, 6),
        )
        assertEquals(Money.zero(Currency.CNY), afterWriteOff.receivableBalance)
        assertEquals(Money.zero(Currency.CNY), afterWriteOff.netBalance)
        assertEquals(Money.fromDecimalString("600.00", Currency.CNY), afterWriteOff.totalBadDebtWriteOff)
        assertEquals(DebtProfileStatus.SETTLED, afterWriteOff.status)
        assertFalse(afterWriteOff.isInReminderWindow)
        assertEquals(3, afterWriteOff.transactionCount)
    }

    @Test
    fun debtBorrowAndRepayWithInterestAndNetOffsetWorkAccurately() {
        val profile = DebtProfile(
            id = "profile:zhang",
            name = "张三",
            receivableAccountId = recvZhang.id,
            payableAccountId = payZhang.id,
        )

        val borrowTx = DebtTransactionHelper.buildBorrowTransaction(
            ledgerEngine = engine,
            profile = profile,
            payableAccount = payZhang,
            toAccount = debitCny,
            amount = Money.fromDecimalString("2000.00", Currency.CNY),
            occurredAt = epochOf(2026, 11, 2),
        )
        val lendTx = DebtTransactionHelper.buildLendTransaction(
            ledgerEngine = engine,
            profile = profile,
            receivableAccount = recvZhang,
            fromAccount = debitCny,
            amount = Money.fromDecimalString("500.00", Currency.CNY),
            occurredAt = epochOf(2026, 11, 3),
        )
        val repayTx = DebtTransactionHelper.buildRepayTransaction(
            ledgerEngine = engine,
            profile = profile,
            payableAccount = payZhang,
            paymentAccount = debitCny,
            principalAmount = Money.fromDecimalString("800.00", Currency.CNY),
            interestAmount = Money.fromDecimalString("15.00", Currency.CNY),
            interestExpenseAccount = interestExpense,
            currentPayableBalance = Money.fromDecimalString("2000.00", Currency.CNY),
            occurredAt = epochOf(2026, 11, 10),
        )
        LedgerValidator.requireValid(borrowTx, allAccounts)
        LedgerValidator.requireValid(repayTx, allAccounts)

        val summary = DebtSummaryCalculator.summarize(
            profile = profile,
            receivableAccount = recvZhang,
            payableAccount = payZhang,
            transactions = listOf(borrowTx, lendTx, repayTx),
            accountsById = allAccounts,
            asOfDate = LocalDate.of(2026, 11, 20),
        )
        assertEquals(Money.fromDecimalString("500.00", Currency.CNY), summary.receivableBalance)
        assertEquals(Money.fromDecimalString("1200.00", Currency.CNY), summary.payableBalance)
        assertEquals(Money.fromDecimalString("-700.00", Currency.CNY), summary.netBalance)
        assertEquals(Money.fromDecimalString("800.00", Currency.CNY), summary.totalRepaidPrincipal)
        assertEquals(Money.fromDecimalString("15.00", Currency.CNY), summary.totalInterestExpense)

        val portfolioTotals = DebtSummaryCalculator.aggregatePortfolioTotals(listOf(summary))
        val cnyTotals = portfolioTotals.getValue(Currency.CNY)
        assertEquals(Money.fromDecimalString("500.00", Currency.CNY), cnyTotals.totalReceivable)
        assertEquals(Money.fromDecimalString("1200.00", Currency.CNY), cnyTotals.totalPayable)
        assertEquals(Money.fromDecimalString("-700.00", Currency.CNY), cnyTotals.netBalance)
        assertEquals(1, cnyTotals.activeCounterpartyCount)
    }

    @Test
    fun reimbursement_partialShortfallSurplusAndBatchSettlementsWorkWithExactZeroSum() {
        val advance1 = engine.buildReimbursableAdvance(
            meta = TransactionMeta(occurredAt = epochOf(2026, 11, 10), payee = "差旅机票"),
            reimbursementAccountId = reimburseCny.id,
            paymentAccountId = creditCny.id,
            amount = Money.fromDecimalString("500.00", Currency.CNY),
            accounts = allAccounts,
        )
        val advance2 = engine.buildReimbursableAdvance(
            meta = TransactionMeta(occurredAt = epochOf(2026, 11, 12), payee = "客户招待"),
            reimbursementAccountId = reimburseCny.id,
            paymentAccountId = debitCny.id,
            amount = Money.fromDecimalString("300.00", Currency.CNY),
            accounts = allAccounts,
        )

        val item1Pending = requireNotNull(
            ReimbursementCalculator.summarizeItem(advance1, emptyList(), allAccounts),
        )
        assertEquals(ReimburseState.PENDING, item1Pending.derivedState)
        assertEquals(Money.fromDecimalString("500.00", Currency.CNY), item1Pending.remainingPendingAmount)

        // Partial reimbursement: ¥200 arrives, ¥300 stays pending
        val partialPlan = ReimbursementSettlementHelper.buildSingleSettlement(
            ledgerEngine = engine,
            item = item1Pending,
            reimbursementAccount = reimburseCny,
            depositAccount = debitCny,
            settledReimbursementAmount = Money.fromDecimalString("200.00", Currency.CNY),
            receivedAmount = Money.fromDecimalString("200.00", Currency.CNY),
            occurredAt = epochOf(2026, 11, 15),
        )
        LedgerValidator.requireValid(partialPlan.settlementTransaction, allAccounts)
        assertEquals(ReimburseState.PARTIAL, partialPlan.nextAdvanceState)
        assertEquals(Money.fromDecimalString("300.00", Currency.CNY), partialPlan.remainingPendingAfter)

        val item1Partial = requireNotNull(
            ReimbursementCalculator.summarizeItem(
                advance1,
                listOf(partialPlan.settlementTransaction),
                allAccounts,
            ),
        )
        assertEquals(ReimburseState.PARTIAL, item1Partial.derivedState)
        assertEquals(Money.fromDecimalString("300.00", Currency.CNY), item1Partial.remainingPendingAmount)

        // Final settlement with shortfall: remaining ¥300 cleared, ¥250 arrives, ¥50 personal expense
        val shortfallPlan = ReimbursementSettlementHelper.buildSingleSettlement(
            ledgerEngine = engine,
            item = item1Partial,
            reimbursementAccount = reimburseCny,
            depositAccount = debitCny,
            settledReimbursementAmount = Money.fromDecimalString("300.00", Currency.CNY),
            receivedAmount = Money.fromDecimalString("250.00", Currency.CNY),
            shortfallExpenseAccount = taxiExpense,
            occurredAt = epochOf(2026, 11, 20),
        )
        LedgerValidator.requireValid(shortfallPlan.settlementTransaction, allAccounts)
        assertEquals(ReimburseState.SETTLED, shortfallPlan.nextAdvanceState)
        assertEquals(Money.fromDecimalString("50.00", Currency.CNY), shortfallPlan.shortfallAmount)

        val item1Settled = requireNotNull(
            ReimbursementCalculator.summarizeItem(
                advance1,
                listOf(partialPlan.settlementTransaction, shortfallPlan.settlementTransaction),
                allAccounts,
            ),
        )
        assertEquals(ReimburseState.SETTLED, item1Settled.derivedState)
        assertEquals(Money.fromDecimalString("450.00", Currency.CNY), item1Settled.receivedCashTotal)
        assertEquals(Money.fromDecimalString("50.00", Currency.CNY), item1Settled.shortfallExpenseTotal)
        assertEquals(Money.zero(Currency.CNY), item1Settled.remainingPendingAmount)

        // Surplus settlement on advance2: ¥300 advance receives ¥320 -> ¥20 income
        val item2Pending = requireNotNull(
            ReimbursementCalculator.summarizeItem(advance2, emptyList(), allAccounts),
        )
        val surplusPlan = ReimbursementSettlementHelper.buildSingleSettlement(
            ledgerEngine = engine,
            item = item2Pending,
            reimbursementAccount = reimburseCny,
            depositAccount = debitCny,
            settledReimbursementAmount = Money.fromDecimalString("300.00", Currency.CNY),
            receivedAmount = Money.fromDecimalString("320.00", Currency.CNY),
            surplusIncomeAccount = bonusIncome,
            occurredAt = epochOf(2026, 11, 21),
        )
        LedgerValidator.requireValid(surplusPlan.settlementTransaction, allAccounts)
        assertEquals(Money.fromDecimalString("20.00", Currency.CNY), surplusPlan.surplusAmount)

        val overview = ReimbursementCalculator.summarizeAll(
            reimburseTransactions = listOf(
                advance1,
                advance2,
                partialPlan.settlementTransaction,
                shortfallPlan.settlementTransaction,
                surplusPlan.settlementTransaction,
            ),
            accountsById = allAccounts,
        )
        assertEquals(0, overview.pendingCount)
        assertEquals(2, overview.settledCount)
        assertEquals(Money.zero(Currency.CNY), overview.primaryPendingTotal)
    }

    @Test
    fun multiStageRefundsEnforceCapMatchLedgerAggregatorAndSupportCrossCurrency() {
        val original = engine.buildExpense(
            meta = TransactionMeta(occurredAt = epochOf(2026, 11, 10), payee = "电商购物"),
            expenseCategoryAccountId = shoppingExpense.id,
            paymentAccountId = creditCny.id,
            amount = Money.fromDecimalString("200.00", Currency.CNY),
            accounts = allAccounts,
        )

        // Refund #1: ¥60
        val r1 = RefundHelper.buildRefundTransaction(
            ledgerEngine = engine,
            originalExpense = original,
            existingLinkedRefunds = emptyList(),
            accountsById = allAccounts,
            refundToAccount = creditCny,
            refundAmount = Money.fromDecimalString("60.00", Currency.CNY),
            occurredAt = epochOf(2026, 11, 12),
        )
        LedgerValidator.requireValid(r1.refundTransaction, allAccounts)
        assertEquals(Money.fromDecimalString("60.00", Currency.CNY), r1.updatedSummary.cumulativeRefundedAmount)
        assertEquals(Money.fromDecimalString("140.00", Currency.CNY), r1.updatedSummary.remainingRefundableAmount)
        assertEquals(Money.fromDecimalString("140.00", Currency.CNY), r1.updatedSummary.netExpenseAmount)
        assertTrue(r1.updatedSummary.isPartiallyRefunded)

        // Refund #2: ¥40
        val r2 = RefundHelper.buildRefundTransaction(
            ledgerEngine = engine,
            originalExpense = original,
            existingLinkedRefunds = listOf(r1.refundTransaction),
            accountsById = allAccounts,
            refundToAccount = debitCny,
            refundAmount = Money.fromDecimalString("40.00", Currency.CNY),
            occurredAt = epochOf(2026, 11, 14),
        )
        LedgerValidator.requireValid(r2.refundTransaction, allAccounts)
        assertEquals(Money.fromDecimalString("100.00", Currency.CNY), r2.updatedSummary.cumulativeRefundedAmount)
        assertEquals(Money.fromDecimalString("100.00", Currency.CNY), r2.updatedSummary.netExpenseAmount)
        assertEquals(2, r2.updatedSummary.refundCount)

        // Verify strict consistency with LedgerAggregator.netExpenseAfterRefunds
        val aggNet = LedgerAggregator.netExpenseAfterRefunds(
            originalExpense = original,
            refunds = listOf(r1.refundTransaction, r2.refundTransaction),
            expenseAccountId = shoppingExpense.id,
        )
        assertEquals(aggNet.originalCurrencyNet, r2.updatedSummary.netExpenseAmount)
        assertEquals(aggNet.baseCurrencyNet, r2.updatedSummary.netExpenseBaseAmount)

        // Over-refund ¥100.01 must be rejected
        var rejectedOverRefund = false
        try {
            RefundHelper.buildRefundTransaction(
                ledgerEngine = engine,
                originalExpense = original,
                existingLinkedRefunds = listOf(r1.refundTransaction, r2.refundTransaction),
                accountsById = allAccounts,
                refundToAccount = creditCny,
                refundAmount = Money.fromDecimalString("100.01", Currency.CNY),
                occurredAt = epochOf(2026, 11, 15),
            )
        } catch (_: IllegalArgumentException) {
            rejectedOverRefund = true
        }
        assertTrue(rejectedOverRefund)

        // Cross-currency refund #3: $10 USD clearing remaining ¥72 CNY with Trading auto-hedge
        val fxRates = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(
                fromCurrency = Currency.USD,
                toCurrency = Currency.CNY,
                rateDecimal = "7.2",
                source = ExchangeRateSource.USER_OVERRIDE,
                capturedAt = epochOf(2026, 11, 16),
            ),
        )
        val r3 = RefundHelper.buildRefundTransaction(
            ledgerEngine = engine.withRateProvider(fxRates),
            originalExpense = original,
            existingLinkedRefunds = listOf(r1.refundTransaction, r2.refundTransaction),
            accountsById = allAccounts,
            refundToAccount = usdCash,
            refundAmount = Money.fromDecimalString("10.00", Currency.USD),
            expenseCurrencyReductionAmount = Money.fromDecimalString("72.00", Currency.CNY),
            occurredAt = epochOf(2026, 11, 16),
            rateProviderOverride = fxRates,
        )
        assertTrue(r3.refundTransaction.isMultiCurrency)
        assertEquals(Money.fromDecimalString("172.00", Currency.CNY), r3.updatedSummary.cumulativeRefundedAmount)
        assertEquals(Money.fromDecimalString("28.00", Currency.CNY), r3.updatedSummary.netExpenseAmount)
        assertNotNull(r3.updatedSummary.linkedRefunds.last().refundToAccountId)
    }
}
