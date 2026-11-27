package io.github.kvtttba.droplet.core.domain.credit

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidator
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditCardStatementCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private val rates = ExchangeRateTable.of(
        ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.20", ExchangeRateSource.MARKET_CACHE, 1000L),
        ExchangeRateSnapshot.of(Currency.HKD, Currency.CNY, "0.92", ExchangeRateSource.MARKET_CACHE, 1000L),
    )

    private val engine = LedgerEngine(baseCurrency = Currency.CNY, rateProvider = rates)

    private val expenseFood = Account(
        id = "cat:food",
        name = "餐饮",
        type = AccountType.EXPENSE,
        subType = AccountSubType.CATEGORY,
        currency = Currency.CNY,
    )

    private val expenseFee = Account(
        id = "cat:fee",
        name = "手续费",
        type = AccountType.EXPENSE,
        subType = AccountSubType.CATEGORY,
        currency = Currency.CNY,
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
        name = "美元储蓄卡",
        type = AccountType.ASSET,
        subType = AccountSubType.DEBIT_CARD,
        currency = Currency.USD,
    )

    private fun creditCard(
        id: String = "acct:cc_cny",
        name: String = "招行信用卡",
        currency: Currency = Currency.CNY,
        limitDecimal: String? = "50000.00",
        billingDay: Int? = 5,
        dueDay: Int? = 25,
        remindDaysBefore: Int? = 3,
    ): Account = Account(
        id = id,
        name = name,
        type = AccountType.LIABILITY,
        subType = AccountSubType.CREDIT_CARD,
        currency = currency,
        creditLimitMinor = limitDecimal?.let { Money.fromDecimalString(it, currency).amountMinor },
        billingDay = billingDay,
        dueDay = dueDay,
        remindDaysBefore = remindDaysBefore,
    )

    private fun epochOn(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun expenseTx(
        card: Account,
        amountDecimal: String,
        year: Int,
        month: Int,
        day: Int,
        status: TransactionStatus = TransactionStatus.POSTED,
        id: String? = null,
    ): Transaction = engine.buildExpense(
        meta = TransactionMeta(
            id = id,
            occurredAt = epochOn(year, month, day),
            status = status,
            payee = "商户消费",
        ),
        expenseCategoryAccountId = expenseFood.id,
        paymentAccountId = card.id,
        amount = Money.fromDecimalString(amountDecimal, card.currency),
    )

    private fun repayTx(
        card: Account,
        from: Account = debitCny,
        amountDecimal: String,
        year: Int,
        month: Int,
        day: Int,
        id: String? = null,
    ): Transaction = engine.buildTransfer(
        meta = TransactionMeta(
            id = id,
            occurredAt = epochOn(year, month, day),
            payee = "信用卡还款",
        ),
        fromAccountId = from.id,
        toAccountId = card.id,
        amount = Money.fromDecimalString(amountDecimal, card.currency),
    )

    @Test
    fun monthEndClamping_handlesLeapYearNonLeapYearAnd30DayMonths() {
        assertEquals(LocalDate.of(2024, 2, 29), CreditCardStatementCalculator.clampDayOfMonth(YearMonth.of(2024, 2), 31))
        assertEquals(LocalDate.of(2024, 2, 29), CreditCardStatementCalculator.clampDayOfMonth(YearMonth.of(2024, 2), 29))
        assertEquals(LocalDate.of(2026, 2, 28), CreditCardStatementCalculator.clampDayOfMonth(YearMonth.of(2026, 2), 31))
        assertEquals(LocalDate.of(2026, 2, 28), CreditCardStatementCalculator.clampDayOfMonth(YearMonth.of(2026, 2), 30))
        assertEquals(LocalDate.of(2026, 2, 28), CreditCardStatementCalculator.clampDayOfMonth(YearMonth.of(2026, 2), 29))
        assertEquals(LocalDate.of(2026, 4, 30), CreditCardStatementCalculator.clampDayOfMonth(YearMonth.of(2026, 4), 31))

        // Leap year: billingDay=31, dueDay=30 (cross-month) as of 2024-03-10
        val card = creditCard(billingDay = 31, dueDay = 30, remindDaysBefore = 3)
        val leapSummary = CreditCardStatementCalculator.calculate(
            account = card,
            transactions = emptyList(),
            asOfDate = LocalDate.of(2024, 3, 10),
            zoneId = zone,
        )
        assertEquals(LocalDate.of(2024, 2, 29), leapSummary.lastStatementDate)
        assertEquals(LocalDate.of(2024, 2, 1), leapSummary.lastStatementPeriod?.startDate)
        assertEquals(LocalDate.of(2024, 2, 29), leapSummary.lastStatementPeriod?.endDate)
        assertEquals(LocalDate.of(2024, 3, 31), leapSummary.nextStatementDate)
        assertEquals(LocalDate.of(2024, 3, 1), leapSummary.unbilledPeriod?.startDate)
        assertEquals(LocalDate.of(2024, 3, 31), leapSummary.unbilledPeriod?.endDate)
        assertEquals(LocalDate.of(2024, 3, 30), leapSummary.currentStatementDueDate)
        assertEquals(LocalDate.of(2024, 4, 30), leapSummary.nextStatementDueDate)

        // Non-leap year: billingDay=31, dueDay=30 as of 2026-02-27 -> closed Jan 31, due Feb 28!
        val nonLeapSummary = CreditCardStatementCalculator.calculate(
            account = card,
            transactions = listOf(expenseTx(card, "1200.00", 2026, 1, 15)),
            asOfDate = LocalDate.of(2026, 2, 27),
            zoneId = zone,
        )
        assertEquals(LocalDate.of(2026, 1, 31), nonLeapSummary.lastStatementDate)
        assertEquals(LocalDate.of(2026, 2, 28), nonLeapSummary.nextStatementDate)
        assertEquals(LocalDate.of(2026, 2, 28), nonLeapSummary.currentStatementDueDate)
        assertEquals(1, nonLeapSummary.daysUntilNextBilling)
        assertEquals(1, nonLeapSummary.daysUntilCurrentDue)
        assertEquals(CreditCardStatementStatus.DUE_SOON, nonLeapSummary.status)
        assertTrue(nonLeapSummary.isInReminderWindow)
    }

    @Test
    fun sameMonthAndCrossMonthDueDate_handlesCrossYearRollover() {
        // Same-month due day: billing 5 -> due 25 in same month
        val sameMonthCard = creditCard(billingDay = 5, dueDay = 25, remindDaysBefore = 3)
        val sameMonth = CreditCardStatementCalculator.calculate(
            account = sameMonthCard,
            transactions = listOf(expenseTx(sameMonthCard, "800.00", 2026, 11, 1)),
            asOfDate = LocalDate.of(2026, 11, 20),
            zoneId = zone,
        )
        assertEquals(LocalDate.of(2026, 11, 5), sameMonth.lastStatementDate)
        assertEquals(LocalDate.of(2026, 10, 6), sameMonth.lastStatementPeriod?.startDate)
        assertEquals(LocalDate.of(2026, 11, 5), sameMonth.lastStatementPeriod?.endDate)
        assertEquals(LocalDate.of(2026, 12, 5), sameMonth.nextStatementDate)
        assertEquals(LocalDate.of(2026, 11, 25), sameMonth.currentStatementDueDate)
        assertEquals(LocalDate.of(2026, 12, 25), sameMonth.nextStatementDueDate)
        assertEquals(15, sameMonth.daysUntilNextBilling)
        assertEquals(5, sameMonth.daysUntilCurrentDue)
        assertEquals(CreditCardStatementStatus.PENDING, sameMonth.status)
        assertFalse(sameMonth.isInReminderWindow)

        // Cross-month + cross-year due day: billing 20 -> due 10 of next month (Dec 20 -> Jan 10)
        val crossMonthCard = creditCard(billingDay = 20, dueDay = 10, remindDaysBefore = 5)
        val crossYear = CreditCardStatementCalculator.calculate(
            account = crossMonthCard,
            transactions = listOf(expenseTx(crossMonthCard, "2500.00", 2026, 12, 15)),
            asOfDate = LocalDate.of(2026, 12, 22),
            zoneId = zone,
        )
        assertEquals(LocalDate.of(2026, 12, 20), crossYear.lastStatementDate)
        assertEquals(LocalDate.of(2026, 11, 21), crossYear.lastStatementPeriod?.startDate)
        assertEquals(LocalDate.of(2026, 12, 20), crossYear.lastStatementPeriod?.endDate)
        assertEquals(LocalDate.of(2027, 1, 20), crossYear.nextStatementDate)
        assertEquals(LocalDate.of(2027, 1, 10), crossYear.currentStatementDueDate)
        assertEquals(LocalDate.of(2027, 2, 10), crossYear.nextStatementDueDate)
        assertEquals(19, crossYear.daysUntilCurrentDue)
        assertEquals(CreditCardStatementStatus.PENDING, crossYear.status)
    }

    @Test
    fun partialRepaymentRefundAndCarryover_reconcilesExactStatementAndUnbilledDebt() {
        val card = creditCard(limitDecimal = "10000.00", billingDay = 5, dueDay = 25, remindDaysBefore = 3)
        // Prior cycle unpaid carryover: ¥500 on Sep 20
        // Closed statement [Oct 6..Nov 5]: spend ¥1,000 on Oct 12 + spend ¥600 on Nov 5 (inclusive!)
        //   -> statementTotalDue = 500 + 1000 + 600 = 2100
        // Post-statement [Nov 6..Dec 5]:
        //   - Nov 10 partial repay ¥900 -> remainingStatementDue = 1200
        //   - Nov 12 refund ¥200 -> remainingStatementDue = 1000
        //   - Nov 18 new unbilled spend ¥450
        //   - Void transaction ¥9999 MUST be ignored!
        val billedExpense = expenseTx(card, "1000.00", 2026, 10, 12, id = "tx-billed-1")
        val refund = engine.buildRefund(
            meta = TransactionMeta(id = "tx-refund", occurredAt = epochOn(2026, 11, 12)),
            originalTransactionId = billedExpense.id,
            expenseCategoryAccountId = expenseFood.id,
            refundToAccountId = card.id,
            refundAmount = Money.fromDecimalString("200.00", Currency.CNY),
        )
        val txs = listOf(
            expenseTx(card, "500.00", 2026, 9, 20, id = "tx-prior"),
            billedExpense,
            expenseTx(card, "600.00", 2026, 11, 5, id = "tx-billed-2"),
            repayTx(card, amountDecimal = "900.00", year = 2026, month = 11, day = 10, id = "tx-repay"),
            refund,
            expenseTx(card, "450.00", 2026, 11, 18, id = "tx-unbilled-1"),
            expenseTx(card, "9999.00", 2026, 10, 20, status = TransactionStatus.VOID, id = "tx-void"),
        )

        val summary = CreditCardStatementCalculator.calculate(
            account = card,
            transactions = txs,
            asOfDate = LocalDate.of(2026, 11, 22), // 3 days before due day -> boundary of reminder window!
            zoneId = zone,
        )

        assertEquals(Money.fromDecimalString("1600.00", Currency.CNY), summary.statementPeriodCharges)
        assertEquals(Money.fromDecimalString("2100.00", Currency.CNY), summary.statementTotalDue)
        assertEquals(Money.fromDecimalString("1100.00", Currency.CNY), summary.statementPaidAfterClosing)
        assertEquals(Money.fromDecimalString("1000.00", Currency.CNY), summary.remainingStatementDue)
        assertEquals(Money.fromDecimalString("450.00", Currency.CNY), summary.unbilledCharges)
        assertEquals(Money.fromDecimalString("450.00", Currency.CNY), summary.unbilledRemainingDebt)
        assertEquals(Money.fromDecimalString("1450.00", Currency.CNY), summary.currentDebt)
        assertEquals(summary.currentDebt, summary.remainingStatementDue + summary.unbilledRemainingDebt)
        assertEquals(Money.fromDecimalString("8550.00", Currency.CNY), summary.availableCredit)
        assertEquals(Money.zero(Currency.CNY), summary.overpayment)
        assertEquals("14.5", summary.utilizationPctDecimal)
        assertEquals(2, summary.billedTransactionCount)
        assertEquals(3, summary.unbilledTransactionCount)
        assertEquals(CreditCardStatementStatus.DUE_SOON, summary.status)
        assertTrue(summary.isInReminderWindow)
        assertFalse(summary.isDueToday)

        val partitioned = CreditCardStatementCalculator.partitionRecords(txs, summary) { it }
        assertEquals(listOf("tx-billed-2", "tx-billed-1"), partitioned.billed.map { it.id })
        assertEquals(listOf("tx-unbilled-1", "tx-refund", "tx-repay"), partitioned.unbilled.map { it.id })
        assertEquals(6, partitioned.all.size)
    }

    @Test
    fun overpaymentAndOverLimit_computeAvailableCreditAndUtilizationAccurately() {
        val card = creditCard(limitDecimal = "5000.00", billingDay = 5, dueDay = 25, remindDaysBefore = 3)

        // Overpayment scenario: spend ¥1,000, repay ¥1,800 -> balance +¥800, available credit = ¥5,800
        val overpaid = CreditCardStatementCalculator.calculate(
            account = card,
            transactions = listOf(
                expenseTx(card, "1000.00", 2026, 10, 15),
                repayTx(card, amountDecimal = "1800.00", year = 2026, month = 11, day = 10),
            ),
            asOfDate = LocalDate.of(2026, 11, 24),
            zoneId = zone,
        )
        assertEquals(Money.fromDecimalString("800.00", Currency.CNY), overpaid.currentBalance)
        assertEquals(Money.zero(Currency.CNY), overpaid.currentDebt)
        assertEquals(Money.fromDecimalString("800.00", Currency.CNY), overpaid.overpayment)
        assertEquals(Money.fromDecimalString("5800.00", Currency.CNY), overpaid.availableCredit)
        assertEquals(Money.zero(Currency.CNY), overpaid.overLimitAmount)
        assertEquals("0", overpaid.utilizationPctDecimal)
        assertEquals(Money.zero(Currency.CNY), overpaid.remainingStatementDue)
        assertEquals(CreditCardStatementStatus.PAID_OFF, overpaid.status)
        assertFalse(overpaid.isInReminderWindow)

        // Over-limit scenario: spend ¥5,600 on ¥5,000 limit -> available = 0, overLimit = ¥600, utilization = 112%
        val overLimit = CreditCardStatementCalculator.calculate(
            account = card,
            transactions = listOf(expenseTx(card, "5600.00", 2026, 10, 15)),
            asOfDate = LocalDate.of(2026, 11, 25),
            zoneId = zone,
        )
        assertEquals(Money.fromDecimalString("5600.00", Currency.CNY), overLimit.currentDebt)
        assertEquals(Money.zero(Currency.CNY), overLimit.availableCredit)
        assertEquals(Money.fromDecimalString("600.00", Currency.CNY), overLimit.overLimitAmount)
        assertEquals("112", overLimit.utilizationPctDecimal)
        assertTrue(overLimit.isDueToday)
        assertEquals(CreditCardStatementStatus.DUE_SOON, overLimit.status)
        assertTrue(overLimit.isInReminderWindow)
    }

    @Test
    fun reminderWindowBoundaries_overdueAndPaidOffTransitions() {
        val card = creditCard(billingDay = 5, dueDay = 25, remindDaysBefore = 3)
        val unpaidTxs = listOf(expenseTx(card, "500.00", 2026, 10, 20))

        // 4 days before due -> PENDING, outside window
        val dayMinus4 = CreditCardStatementCalculator.calculate(card, unpaidTxs, LocalDate.of(2026, 11, 21), zoneId = zone)
        assertEquals(CreditCardStatementStatus.PENDING, dayMinus4.status)
        assertFalse(dayMinus4.isInReminderWindow)
        assertEquals(4, dayMinus4.daysUntilCurrentDue)

        // 3 days before due -> DUE_SOON, inside window
        val dayMinus3 = CreditCardStatementCalculator.calculate(card, unpaidTxs, LocalDate.of(2026, 11, 22), zoneId = zone)
        assertEquals(CreditCardStatementStatus.DUE_SOON, dayMinus3.status)
        assertTrue(dayMinus3.isInReminderWindow)
        assertEquals(3, dayMinus3.daysUntilCurrentDue)

        // Due day itself -> DUE_SOON + isDueToday
        val dueDay = CreditCardStatementCalculator.calculate(card, unpaidTxs, LocalDate.of(2026, 11, 25), zoneId = zone)
        assertEquals(CreditCardStatementStatus.DUE_SOON, dueDay.status)
        assertTrue(dueDay.isDueToday)
        assertTrue(dueDay.isInReminderWindow)
        assertEquals(0, dueDay.daysUntilCurrentDue)

        // 2 days past due & still unpaid -> OVERDUE, stays in reminder window
        val overdue = CreditCardStatementCalculator.calculate(card, unpaidTxs, LocalDate.of(2026, 11, 27), zoneId = zone)
        assertEquals(CreditCardStatementStatus.OVERDUE, overdue.status)
        assertEquals(2, overdue.overdueDays)
        assertEquals(-2, overdue.daysUntilCurrentDue)
        assertEquals(LocalDate.of(2026, 11, 25), overdue.displayDueDate)
        assertTrue(overdue.isInReminderWindow)

        // Paid off after due date -> PAID_OFF, displayDueDate rolls to Dec 25, reminder stops
        val paidOffAfterDue = CreditCardStatementCalculator.calculate(
            card,
            unpaidTxs + repayTx(card, amountDecimal = "500.00", year = 2026, month = 11, day = 26),
            LocalDate.of(2026, 11, 27),
            zoneId = zone,
        )
        assertEquals(CreditCardStatementStatus.PAID_OFF, paidOffAfterDue.status)
        assertEquals(0, paidOffAfterDue.overdueDays)
        assertFalse(paidOffAfterDue.isInReminderWindow)
        assertEquals(LocalDate.of(2026, 12, 25), paidOffAfterDue.displayDueDate)
        assertEquals(28, paidOffAfterDue.daysUntilDisplayDue)

        // remindDaysBefore = 0 -> triggers ONLY on due day (0) or overdue, not 1 day before
        val dueDayOnlyCard = creditCard(billingDay = 5, dueDay = 25, remindDaysBefore = 0)
        val oneDayBefore = CreditCardStatementCalculator.calculate(dueDayOnlyCard, unpaidTxs, LocalDate.of(2026, 11, 24), zoneId = zone)
        assertEquals(CreditCardStatementStatus.PENDING, oneDayBefore.status)
        assertFalse(oneDayBefore.isInReminderWindow)
        val onDueDay = CreditCardStatementCalculator.calculate(dueDayOnlyCard, unpaidTxs, LocalDate.of(2026, 11, 25), zoneId = zone)
        assertEquals(CreditCardStatementStatus.DUE_SOON, onDueDay.status)
        assertTrue(onDueDay.isInReminderWindow)
    }

    @Test
    fun unconfiguredCard_computesDebtAndLimitGracefullyWithoutThrowing() {
        val unconfigured = creditCard(limitDecimal = null, billingDay = null, dueDay = null, remindDaysBefore = null)
        val summary = CreditCardStatementCalculator.calculate(
            account = unconfigured,
            transactions = listOf(expenseTx(unconfigured, "320.50", 2026, 11, 20)),
            asOfDate = LocalDate.of(2026, 11, 27),
            defaultRemindDaysBefore = 5,
            zoneId = zone,
        )
        assertFalse(summary.isBillingScheduleConfigured)
        assertEquals(CreditCardStatementStatus.UNCONFIGURED, summary.status)
        assertFalse(summary.isInReminderWindow)
        assertNull(summary.lastStatementDate)
        assertNull(summary.currentStatementDueDate)
        assertNull(summary.availableCredit)
        assertNull(summary.utilizationPctDecimal)
        assertEquals(5, summary.effectiveRemindDaysBefore)
        assertEquals(Money.fromDecimalString("320.50", Currency.CNY), summary.currentDebt)
        assertEquals(Money.fromDecimalString("320.50", Currency.CNY), summary.unbilledRemainingDebt)
    }

    @Test
    fun repaymentHelper_buildsBalancedSameCurrencyAndCrossCurrencyRepayments() {
        val cnyCard = creditCard(id = "acct:cc_cny", name = "招行信用卡", currency = Currency.CNY)
        val usdCard = creditCard(id = "acct:cc_usd", name = "美金信用卡", currency = Currency.USD, limitDecimal = "10000.00")

        // Same-currency repayment with fee
        val sameTx = CreditCardRepaymentHelper.buildRepaymentTransaction(
            ledgerEngine = engine,
            meta = TransactionMeta(occurredAt = epochOn(2026, 11, 25)),
            creditCardAccount = cnyCard,
            paymentAccount = debitCny,
            repaymentAmount = Money.fromDecimalString("1200.00", Currency.CNY),
            fee = Money.fromDecimalString("2.00", Currency.CNY),
            feeExpenseAccountId = expenseFee.id,
        )
        assertEquals(TransactionDisplayType.TRANSFER, sameTx.displayType)
        assertEquals("信用卡还款 · 招行信用卡", sameTx.payee)
        LedgerValidator.requireValid(sameTx)
        assertEquals(
            Money.fromDecimalString("1200.00", Currency.CNY).amountMinor,
            sameTx.postings.first { it.accountId == cnyCard.id }.amountMinor,
        )
        assertEquals(
            Money.fromDecimalString("-1202.00", Currency.CNY).amountMinor,
            sameTx.postings.first { it.accountId == debitCny.id }.amountMinor,
        )

        // Cross-currency repayment: CNY debit card pays $100 USD credit card via cached rate (7.20 -> ¥720)
        val crossTxAuto = CreditCardRepaymentHelper.buildRepaymentTransaction(
            ledgerEngine = engine,
            meta = TransactionMeta(occurredAt = epochOn(2026, 11, 25)),
            creditCardAccount = usdCard,
            paymentAccount = debitCny,
            repaymentAmount = Money.fromDecimalString("100.00", Currency.USD),
        )
        assertEquals(TransactionDisplayType.TRANSFER, crossTxAuto.displayType)
        LedgerValidator.requireValid(crossTxAuto)
        assertEquals(
            Money.fromDecimalString("100.00", Currency.USD).amountMinor,
            crossTxAuto.postings.first { it.accountId == usdCard.id }.amountMinor,
        )
        assertEquals(
            Money.fromDecimalString("-720.00", Currency.CNY).amountMinor,
            crossTxAuto.postings.first { it.accountId == debitCny.id }.amountMinor,
        )
        assertTrue(crossTxAuto.postings.any { it.accountId == SystemAccounts.tradingAccountId(Currency.CNY) })
        assertTrue(crossTxAuto.postings.any { it.accountId == SystemAccounts.tradingAccountId(Currency.USD) })

        // Cross-currency repayment with explicit bank-settled paidFromAccountAmount (¥726.50 for $100)
        val crossTxExplicit = CreditCardRepaymentHelper.buildRepaymentTransaction(
            ledgerEngine = engine,
            meta = TransactionMeta(occurredAt = epochOn(2026, 11, 25)),
            creditCardAccount = usdCard,
            paymentAccount = debitCny,
            repaymentAmount = Money.fromDecimalString("100.00", Currency.USD),
            paidFromAccountAmount = Money.fromDecimalString("726.50", Currency.CNY),
        )
        LedgerValidator.requireValid(crossTxExplicit)
        assertEquals(
            Money.fromDecimalString("-726.50", Currency.CNY).amountMinor,
            crossTxExplicit.postings.first { it.accountId == debitCny.id }.amountMinor,
        )

        // USD debit card paying USD credit card -> same-currency path
        val usdSameTx = CreditCardRepaymentHelper.buildRepaymentTransaction(
            ledgerEngine = engine,
            meta = TransactionMeta(occurredAt = epochOn(2026, 11, 25)),
            creditCardAccount = usdCard,
            paymentAccount = debitUsd,
            repaymentAmount = Money.fromDecimalString("50.00", Currency.USD),
        )
        LedgerValidator.requireValid(usdSameTx)
        assertFalse(usdSameTx.postings.any { it.isSystemBalancing })
    }

    @Test
    fun repaymentHelper_resolvesDefaultModeAndSuggestedAmount() {
        val card = creditCard(billingDay = 5, dueDay = 25, remindDaysBefore = 3)
        // Billed ¥500 + Unbilled ¥300 -> default STATEMENT_DUE (¥500), FULL_BALANCE (¥800)
        val withStatementDue = CreditCardStatementCalculator.calculate(
            account = card,
            transactions = listOf(
                expenseTx(card, "500.00", 2026, 10, 20),
                expenseTx(card, "300.00", 2026, 11, 15),
            ),
            asOfDate = LocalDate.of(2026, 11, 20),
            zoneId = zone,
        )
        assertEquals(CreditCardRepaymentMode.STATEMENT_DUE, CreditCardRepaymentHelper.defaultRepaymentMode(withStatementDue))
        assertEquals(
            Money.fromDecimalString("500.00", Currency.CNY),
            CreditCardRepaymentHelper.resolveSuggestedAmount(withStatementDue, CreditCardRepaymentMode.STATEMENT_DUE),
        )
        assertEquals(
            Money.fromDecimalString("800.00", Currency.CNY),
            CreditCardRepaymentHelper.resolveSuggestedAmount(withStatementDue, CreditCardRepaymentMode.FULL_BALANCE),
        )

        // Only unbilled ¥300 -> default FULL_BALANCE (¥300)
        val unbilledOnly = CreditCardStatementCalculator.calculate(
            account = card,
            transactions = listOf(expenseTx(card, "300.00", 2026, 11, 15)),
            asOfDate = LocalDate.of(2026, 11, 20),
            zoneId = zone,
        )
        assertEquals(CreditCardRepaymentMode.FULL_BALANCE, CreditCardRepaymentHelper.defaultRepaymentMode(unbilledOnly))
        assertEquals(
            Money.fromDecimalString("300.00", Currency.CNY),
            CreditCardRepaymentHelper.resolveSuggestedAmount(unbilledOnly, CreditCardRepaymentMode.FULL_BALANCE),
        )
    }
}
