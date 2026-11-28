package io.github.kvtttba.droplet.core.domain.automation

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Budget
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.TransactionStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetProgressCalculatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private val debit = Account("acct:debit", "储蓄卡", AccountType.ASSET, AccountSubType.DEBIT_CARD)
    private val usdCard = Account("acct:usd", "美元卡", AccountType.ASSET, AccountSubType.DEBIT_CARD, currency = Currency.USD)
    private val reimburse = Account("acct:reimb", "待报销", AccountType.ASSET, AccountSubType.REIMBURSEMENT, isSystem = true)
    private val foodRoot = Account("cat:food", "餐饮", AccountType.EXPENSE, AccountSubType.CATEGORY)
    private val foodLunch = Account("cat:food:lunch", "午餐", AccountType.EXPENSE, AccountSubType.CATEGORY, parentId = "cat:food")
    private val foodDinner = Account("cat:food:dinner", "晚餐", AccountType.EXPENSE, AccountSubType.CATEGORY, parentId = "cat:food")
    private val transportRoot = Account("cat:transport", "交通", AccountType.EXPENSE, AccountSubType.CATEGORY)
    private val transit = Account("cat:transport:transit", "地铁", AccountType.EXPENSE, AccountSubType.CATEGORY, parentId = "cat:transport")

    private val accountsById: Map<String, Account> = (
        SystemAccounts.allSystemEquityAccounts() +
            listOf(debit, usdCard, reimburse, foodRoot, foodLunch, foodDinner, transportRoot, transit)
        ).associateBy { it.id }

    private fun epochAt(date: LocalDate, hour: Int = 12): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun calculatesOverallAndParentCategoryBudgets_deductsRefunds_excludesReimburseAndVoid() {
        val engine = LedgerEngine(baseCurrency = Currency.CNY)
        val asOf = epochAt(LocalDate.of(2026, 11, 21)) // Nov has 30 days -> remaining 10 days (21..30)

        // 1. Lunch ¥500 + Dinner ¥400 -> Food subtotal ¥900
        val lunchTx = engine.buildExpense(
            meta = TransactionMeta(id = "tx:lunch", occurredAt = epochAt(LocalDate.of(2026, 11, 5))),
            expenseCategoryAccountId = foodLunch.id,
            paymentAccountId = debit.id,
            amount = Money.fromDecimalString("500.00"),
            accounts = accountsById,
        )
        val dinnerTx = engine.buildExpense(
            meta = TransactionMeta(id = "tx:dinner", occurredAt = epochAt(LocalDate.of(2026, 11, 10))),
            expenseCategoryAccountId = foodDinner.id,
            paymentAccountId = debit.id,
            amount = Money.fromDecimalString("400.00"),
            accounts = accountsById,
        )
        // 2. Partial refund on dinner ¥100 -> Food net ¥800
        val refundTx = engine.buildRefund(
            meta = TransactionMeta(id = "tx:refund", occurredAt = epochAt(LocalDate.of(2026, 11, 11))),
            originalTransactionId = dinnerTx.id,
            expenseCategoryAccountId = foodDinner.id,
            refundToAccountId = debit.id,
            refundAmount = Money.fromDecimalString("100.00"),
            accounts = accountsById,
        )
        // 3. Transit ¥200 -> Overall net ¥1,000
        val transitTx = engine.buildExpense(
            meta = TransactionMeta(id = "tx:transit", occurredAt = epochAt(LocalDate.of(2026, 11, 15))),
            expenseCategoryAccountId = transit.id,
            paymentAccountId = debit.id,
            amount = Money.fromDecimalString("200.00"),
            accounts = accountsById,
        )
        // 4. Reimbursable advance ¥600 -> must NOT inflate budget
        val reimbTx = engine.buildReimbursableAdvance(
            meta = TransactionMeta(id = "tx:reimb", occurredAt = epochAt(LocalDate.of(2026, 11, 16))),
            reimbursementAccountId = reimburse.id,
            paymentAccountId = debit.id,
            amount = Money.fromDecimalString("600.00"),
            accounts = accountsById,
        )
        // 5. Voided expense ¥999 -> must NOT count
        val voidTx = engine.buildExpense(
            meta = TransactionMeta(
                id = "tx:void",
                occurredAt = epochAt(LocalDate.of(2026, 11, 17)),
                status = TransactionStatus.VOID,
            ),
            expenseCategoryAccountId = foodLunch.id,
            paymentAccountId = debit.id,
            amount = Money.fromDecimalString("999.00"),
            accounts = accountsById,
        )
        // 6. Prior month expense ¥300 -> must NOT count in monthly budget, SHOULD count in yearly budget
        val octTx = engine.buildExpense(
            meta = TransactionMeta(id = "tx:oct", occurredAt = epochAt(LocalDate.of(2026, 10, 25))),
            expenseCategoryAccountId = foodLunch.id,
            paymentAccountId = debit.id,
            amount = Money.fromDecimalString("300.00"),
            accounts = accountsById,
        )

        val budgets = listOf(
            Budget(
                id = "b:month:total",
                period = BudgetPeriod.MONTHLY,
                categoryAccountId = null,
                limitAmountMinor = Money.fromDecimalString("2000.00").amountMinor,
                warnThresholdPct = 80,
            ),
            Budget(
                id = "b:month:food",
                period = BudgetPeriod.MONTHLY,
                categoryAccountId = foodRoot.id,
                limitAmountMinor = Money.fromDecimalString("1000.00").amountMinor,
                warnThresholdPct = 80,
            ),
            Budget(
                id = "b:year:total",
                period = BudgetPeriod.YEARLY,
                categoryAccountId = null,
                limitAmountMinor = Money.fromDecimalString("1200.00").amountMinor,
                warnThresholdPct = 80,
            ),
        )

        val results = BudgetProgressCalculator.calculateAll(
            budgets = budgets,
            transactions = listOf(lunchTx, dinnerTx, refundTx, transitTx, reimbTx, voidTx, octTx),
            accountsById = accountsById,
            asOfEpochMs = asOf,
            zoneId = zone,
        ).associateBy { it.budget.id }

        // Monthly overall: spent ¥1,000 / ¥2,000 = 50.0%, remaining ¥1,000 / 10 days = ¥100/day, NORMAL
        val monthTotal = results.getValue("b:month:total")
        assertEquals(Money.fromDecimalString("1000.00"), monthTotal.spentAmount)
        assertEquals(Money.fromDecimalString("1000.00"), monthTotal.remainingAmount)
        assertEquals("50.0", monthTotal.usedPercentageDecimal)
        assertEquals(50, monthTotal.usedPercentageInt)
        assertEquals(10, monthTotal.periodRange.remainingDays)
        assertEquals(Money.fromDecimalString("100.00"), monthTotal.dailyAvailableAmount)
        assertEquals(BudgetAlertLevel.NORMAL, monthTotal.alertLevel)
        assertEquals("b:month:total|2026-11|NORMAL", monthTotal.alertDedupKey)

        // Monthly food (parent aggregates lunch + dinner - refund): ¥800 / ¥1,000 = exact 80.0% -> WARNING
        val monthFood = results.getValue("b:month:food")
        assertEquals("餐饮", monthFood.categoryName)
        assertEquals(Money.fromDecimalString("800.00"), monthFood.spentAmount)
        assertEquals(Money.fromDecimalString("200.00"), monthFood.remainingAmount)
        assertEquals("80.0", monthFood.usedPercentageDecimal)
        assertEquals(Money.fromDecimalString("20.00"), monthFood.dailyAvailableAmount)
        assertEquals(BudgetAlertLevel.WARNING, monthFood.alertLevel)

        // Yearly total: ¥1,000 (Nov) + ¥300 (Oct) = ¥1,300 / ¥1,200 -> EXCEEDED, dailyAvailable = ¥0
        val yearTotal = results.getValue("b:year:total")
        assertEquals(Money.fromDecimalString("1300.00"), yearTotal.spentAmount)
        assertEquals(Money.fromDecimalString("-100.00"), yearTotal.remainingAmount)
        assertEquals(Money.zero(Currency.CNY), yearTotal.dailyAvailableAmount)
        assertEquals(BudgetAlertLevel.EXCEEDED, yearTotal.alertLevel)
        assertEquals("b:year:total|2026|EXCEEDED", yearTotal.alertDedupKey)
        assertTrue(yearTotal.isExceeded)
        assertFalse(yearTotal.isWarning)
    }

    @Test
    fun locksHistoricalBaseCurrencyAndClampsNegativeNetSpendFromCrossMonthRefund() {
        val rateAtTx = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.00", ExchangeRateSource.USER_OVERRIDE, 1_000L),
        )
        val engine = LedgerEngine(baseCurrency = Currency.CNY, rateProvider = rateAtTx)
        val usdExpense = engine.buildExpense(
            meta = TransactionMeta(id = "tx:usd", occurredAt = epochAt(LocalDate.of(2026, 11, 2))),
            expenseCategoryAccountId = foodLunch.id,
            paymentAccountId = usdCard.id,
            amount = Money.fromDecimalString("10.00", Currency.USD),
            accounts = accountsById,
        )
        // Even if current live FX changed to 7.50, budget uses locked historical ¥70.00!
        val laterRates = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.50", ExchangeRateSource.USER_OVERRIDE, 2_000L),
        )
        val budget = Budget(
            id = "b:month:lunch",
            period = BudgetPeriod.MONTHLY,
            categoryAccountId = foodLunch.id,
            currency = Currency.CNY,
            limitAmountMinor = Money.fromDecimalString("100.00").amountMinor,
            warnThresholdPct = 70,
        )
        val progress = BudgetProgressCalculator.calculateSingle(
            budget = budget,
            periodRange = BudgetProgressCalculator.monthPeriodRange(
                month = YearMonth.of(2026, 11),
                referenceDate = LocalDate.of(2026, 11, 2),
                zoneId = zone,
            ),
            transactions = listOf(usdExpense),
            accountsById = accountsById,
            rateProvider = laterRates,
        )
        assertEquals(Money.fromDecimalString("70.00"), progress.spentAmount)
        assertEquals(BudgetAlertLevel.WARNING, progress.alertLevel)

        // Cross-month refund larger than current month spend clamps spent to 0 (no negative percentage)
        val refundOnly = engine.buildRefund(
            meta = TransactionMeta(id = "tx:ref_only", occurredAt = epochAt(LocalDate.of(2026, 12, 3))),
            originalTransactionId = "tx:prev",
            expenseCategoryAccountId = foodLunch.id,
            refundToAccountId = debit.id,
            refundAmount = Money.fromDecimalString("50.00"),
            accounts = accountsById,
        )
        val decProgress = BudgetProgressCalculator.calculateSingle(
            budget = budget,
            periodRange = BudgetProgressCalculator.monthPeriodRange(
                month = YearMonth.of(2026, 12),
                referenceDate = LocalDate.of(2026, 12, 3),
                zoneId = zone,
            ),
            transactions = listOf(refundOnly),
            accountsById = accountsById,
        )
        assertEquals(Money.zero(Currency.CNY), decProgress.spentAmount)
        assertEquals(Money.fromDecimalString("100.00"), decProgress.remainingAmount)
        assertEquals("0.0", decProgress.usedPercentageDecimal)
        assertEquals(BudgetAlertLevel.NORMAL, decProgress.alertLevel)
    }

    @Test
    fun exactThresholdBoundaryAvoidsFloatingPointDrift() {
        val engine = LedgerEngine(baseCurrency = Currency.CNY)
        // 79.9999 / 100.0000 is strictly below 80% threshold -> must remain NORMAL
        val justBelow = engine.buildExpense(
            meta = TransactionMeta(id = "tx:below", occurredAt = epochAt(LocalDate.of(2026, 11, 10))),
            expenseCategoryAccountId = foodLunch.id,
            paymentAccountId = debit.id,
            amount = Money.fromDecimalString("79.9999"),
            accounts = accountsById,
        )
        val budget = Budget(
            id = "b:strict",
            period = BudgetPeriod.MONTHLY,
            limitAmountMinor = Money.fromDecimalString("100.0000").amountMinor,
            warnThresholdPct = 80,
        )
        val range = BudgetProgressCalculator.monthPeriodRange(
            month = YearMonth.of(2026, 11),
            referenceDate = LocalDate.of(2026, 11, 28), // 3 remaining days (28, 29, 30)
            zoneId = zone,
        )
        val progress = BudgetProgressCalculator.calculateSingle(
            budget = budget,
            periodRange = range,
            transactions = listOf(justBelow),
            accountsById = accountsById,
        )
        assertEquals(BudgetAlertLevel.NORMAL, progress.alertLevel)
        // Remaining = 20.0001 -> 200001 / 3 = 66667 minor = 6.6667 (ROUND_DOWN exact)
        assertEquals(Money(66_667L, Currency.CNY), progress.dailyAvailableAmount)
    }
}
