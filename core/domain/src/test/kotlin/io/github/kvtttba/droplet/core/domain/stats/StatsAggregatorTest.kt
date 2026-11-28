package io.github.kvtttba.droplet.core.domain.stats

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.TransactionStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsAggregatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private val debit = Account("acct:debit", "储蓄卡", AccountType.ASSET, AccountSubType.DEBIT_CARD, currency = Currency.CNY, includeInNetWorth = true)
    private val usdCash = Account("acct:usd", "美元户", AccountType.ASSET, AccountSubType.INVEST_CASH, currency = Currency.USD, includeInNetWorth = true)
    private val credit = Account(
        id = "acct:credit",
        name = "信用卡",
        type = AccountType.LIABILITY,
        subType = AccountSubType.CREDIT_CARD,
        currency = Currency.CNY,
        includeInNetWorth = true,
        creditLimitMinor = Money.fromDecimalString("20000.00").amountMinor,
        billingDay = 5,
        dueDay = 25,
    )
    private val excludedFund = Account("acct:housing", "公积金", AccountType.ASSET, AccountSubType.STORED_VALUE, currency = Currency.CNY, includeInNetWorth = false)
    private val reimburseAsset = Account("acct:reimburse", "待报销", AccountType.ASSET, AccountSubType.REIMBURSEMENT, currency = Currency.CNY, includeInNetWorth = true, isSystem = true)

    private val expFood = Account("cat:food", "餐饮", AccountType.EXPENSE, AccountSubType.CATEGORY, sortOrder = 1)
    private val expFoodLunch = Account("cat:food:lunch", "午餐", AccountType.EXPENSE, AccountSubType.CATEGORY, parentId = "cat:food", sortOrder = 2)
    private val expFoodCoffee = Account("cat:food:coffee", "咖啡", AccountType.EXPENSE, AccountSubType.CATEGORY, parentId = "cat:food", sortOrder = 3)
    private val expTransport = Account("cat:transport", "交通", AccountType.EXPENSE, AccountSubType.CATEGORY, sortOrder = 4)
    private val incSalary = Account("cat:salary", "工资", AccountType.INCOME, AccountSubType.CATEGORY, sortOrder = 1)

    private val tradingCny = Account(SystemAccounts.tradingAccountId(Currency.CNY), "Trading CNY", AccountType.EQUITY, AccountSubType.TRADING, currency = Currency.CNY, isSystem = true)
    private val tradingUsd = Account(SystemAccounts.tradingAccountId(Currency.USD), "Trading USD", AccountType.EQUITY, AccountSubType.TRADING, currency = Currency.USD, isSystem = true)
    private val openingCny = Account(SystemAccounts.openingBalanceAccountId(Currency.CNY), "Opening CNY", AccountType.EQUITY, AccountSubType.OPENING, currency = Currency.CNY, isSystem = true)

    private val accountsById = listOf(
        debit, usdCash, credit, excludedFund, reimburseAsset,
        expFood, expFoodLunch, expFoodCoffee, expTransport, incSalary,
        tradingCny, tradingUsd, openingCny,
    ).associateBy { it.id }

    private val tagWork = Tag("tag:work", "工作日")
    private val tagTrip = Tag("tag:trip", "旅行")
    private val tagsById = listOf(tagWork, tagTrip).associateBy { it.id }

    private fun ms(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun monthlyAggregation_excludesVoidAndReimburseAdvances_offsetsRefunds_andLocksHistoricalBaseRate() {
        // Historical USD->CNY rate at transaction time = 7.00
        val txRates = ExchangeRateTable.of(
            ExchangeRateSnapshot(Currency.USD, Currency.CNY, "7", ExchangeRateSource.USER_OVERRIDE, ms(2026, 11, 1)),
        )
        val engine = LedgerEngine(baseCurrency = Currency.CNY, rateProvider = txRates)

        val salaryTx = engine.buildIncome(
            meta = TransactionMeta(id = "tx-salary", occurredAt = ms(2026, 11, 1), payee = "公司发薪"),
            incomeCategoryAccountId = incSalary.id,
            depositAccountId = debit.id,
            amount = Money.fromDecimalString("10000.00"),
        )
        val lunchTx = engine.buildExpense(
            meta = TransactionMeta(id = "tx-lunch", occurredAt = ms(2026, 11, 5), payee = "简餐馆", tagIds = listOf(tagWork.id)),
            expenseCategoryAccountId = expFoodLunch.id,
            paymentAccountId = debit.id,
            amount = Money.fromDecimalString("200.00"),
        )
        // Partial refund linked to lunchTx WITHOUT explicit tags -> should still offset when filtering by tagWork!
        val refundTx = engine.buildRefund(
            meta = TransactionMeta(id = "tx-refund", occurredAt = ms(2026, 11, 6), payee = "简餐馆退款"),
            originalTransactionId = lunchTx.id,
            expenseCategoryAccountId = expFoodLunch.id,
            refundToAccountId = debit.id,
            refundAmount = Money.fromDecimalString("50.00"),
        )
        // Foreign currency USD expense: $10 locked at 7.00 => ¥70.00
        val usdCoffeeTx = engine.buildExpense(
            meta = TransactionMeta(id = "tx-usd", occurredAt = ms(2026, 11, 10), payee = "Overseas Cafe", tagIds = listOf(tagWork.id, tagTrip.id)),
            expenseCategoryAccountId = expFoodCoffee.id,
            paymentAccountId = usdCash.id,
            amount = Money.fromDecimalString("10.00", Currency.USD),
        )
        // Reimbursable advance: must NOT count as personal expense
        val reimburseAdvanceTx = engine.buildReimbursableAdvance(
            meta = TransactionMeta(id = "tx-reimburse", occurredAt = ms(2026, 11, 12), payee = "出差机票", tagIds = listOf(tagTrip.id)),
            reimbursementAccountId = reimburseAsset.id,
            paymentAccountId = credit.id,
            amount = Money.fromDecimalString("800.00"),
        )
        // Voided expense: must be strictly excluded
        val voidedTx = engine.buildExpense(
            meta = TransactionMeta(id = "tx-void", occurredAt = ms(2026, 11, 15), payee = "误记"),
            expenseCategoryAccountId = expTransport.id,
            paymentAccountId = debit.id,
            amount = Money.fromDecimalString("999.00"),
        ).copy(status = TransactionStatus.VOID)

        val allTxs = listOf(salaryTx, lunchTx, refundTx, usdCoffeeTx, reimburseAdvanceTx, voidedTx)

        // Pass a wildly different current FX rate (USD->CNY = 9.00) to verify historical report does NOT drift!
        val laterRates = ExchangeRateTable.of(
            ExchangeRateSnapshot(Currency.USD, Currency.CNY, "9", ExchangeRateSource.USER_OVERRIDE, ms(2026, 11, 28)),
        )

        val snapshot = StatsAggregator.aggregate(
            query = StatsQuery(
                periodType = StatsPeriodType.MONTHLY,
                targetMonth = YearMonth.of(2026, 11),
                calendarMonth = YearMonth.of(2026, 11),
                currencyFilter = StatsCurrencyFilter.BASE_CNY,
            ),
            allTransactions = allTxs,
            accountsById = accountsById,
            tagsById = tagsById,
            exchangeRateProvider = laterRates,
            zoneId = zone,
        )

        // Expected net expense: 200 - 50 + 70 (locked at 7.00, NOT 90) = 220.00 CNY
        assertEquals(Money.fromDecimalString("220.00"), snapshot.totalExpense)
        assertEquals(Money.fromDecimalString("10000.00"), snapshot.totalIncome)
        assertEquals(Money.fromDecimalString("9780.00"), snapshot.netBalance)
        assertEquals(StatsTrendGranularity.DAILY, snapshot.granularity)
        assertEquals(30, snapshot.trendBuckets.size)

        // Primary category ranking groups Lunch (150) + Coffee (70) under Food (220, 100%)
        assertEquals(1, snapshot.expenseRankings.size)
        val foodRank = snapshot.expenseRankings.single()
        assertEquals("餐饮", foodRank.name)
        assertEquals(Money.fromDecimalString("220.00"), foodRank.amount)
        assertEquals("100.0", foodRank.sharePctDecimal)
        assertEquals(2, foodRank.subCategories.size)
        assertEquals("午餐", foodRank.subCategories[0].name)
        assertEquals(Money.fromDecimalString("150.00"), foodRank.subCategories[0].amount)
        assertEquals("咖啡", foodRank.subCategories[1].name)
        assertEquals(Money.fromDecimalString("70.00"), foodRank.subCategories[1].amount)

        // Verify original USD currency filter isolates strictly $10.00
        val usdSnapshot = StatsAggregator.aggregate(
            query = StatsQuery(
                periodType = StatsPeriodType.MONTHLY,
                targetMonth = YearMonth.of(2026, 11),
                currencyFilter = StatsCurrencyFilter.ORIGINAL_USD,
            ),
            allTransactions = allTxs,
            accountsById = accountsById,
            tagsById = tagsById,
            zoneId = zone,
        )
        assertEquals(Money.fromDecimalString("10.00", Currency.USD), usdSnapshot.totalExpense)
        assertEquals(Money.zero(Currency.USD), usdSnapshot.totalIncome)

        // Multi-tag ALL mode: only tx-usd carries both tagWork and tagTrip
        val allTagSnapshot = StatsAggregator.aggregate(
            query = StatsQuery(
                periodType = StatsPeriodType.MONTHLY,
                targetMonth = YearMonth.of(2026, 11),
                selectedTagIds = setOf(tagWork.id, tagTrip.id),
                tagFilterMode = StatsTagFilterMode.ALL,
            ),
            allTransactions = allTxs,
            accountsById = accountsById,
            tagsById = tagsById,
            zoneId = zone,
        )
        assertEquals(Money.fromDecimalString("70.00"), allTagSnapshot.totalExpense)

        // Single-tag filter on tagWork: includes lunchTx (200), linked un-tagged refundTx (-50), and usdCoffeeTx (70)
        val workTagSnapshot = StatsAggregator.aggregate(
            query = StatsQuery(
                periodType = StatsPeriodType.MONTHLY,
                targetMonth = YearMonth.of(2026, 11),
                selectedTagIds = setOf(tagWork.id),
                tagFilterMode = StatsTagFilterMode.ANY,
            ),
            allTransactions = allTxs,
            accountsById = accountsById,
            tagsById = tagsById,
            zoneId = zone,
        )
        assertEquals(Money.fromDecimalString("220.00"), workTagSnapshot.totalExpense)
    }

    @Test
    fun yearlyAndCustomRanges_switchGranularityAt45Days_andComputeAccurateNetWorthTimeline() {
        val engine = LedgerEngine(baseCurrency = Currency.CNY)

        // Opening balance in Jan: debit +50,000; excluded housing fund +20,000 (must be excluded from net worth!)
        val openDebit = engine.buildOpeningBalance(
            meta = TransactionMeta(id = "tx-open-debit", occurredAt = ms(2026, 1, 1)),
            accountId = debit.id,
            accountType = AccountType.ASSET,
            balance = Money.fromDecimalString("50000.00"),
        )
        val openExcluded = engine.buildOpeningBalance(
            meta = TransactionMeta(id = "tx-open-excl", occurredAt = ms(2026, 1, 1)),
            accountId = excludedFund.id,
            accountType = AccountType.ASSET,
            balance = Money.fromDecimalString("20000.00"),
        )
        // Feb: credit card expense 3,000 -> totalAssets 50,000, totalLiabilities 3,000, netWorth 47,000
        val febCreditExp = engine.buildExpense(
            meta = TransactionMeta(id = "tx-feb-cc", occurredAt = ms(2026, 2, 10)),
            expenseCategoryAccountId = expFoodLunch.id,
            paymentAccountId = credit.id,
            amount = Money.fromDecimalString("3000.00"),
        )
        // Mar: overpay credit card by 4,000 from debit -> debit 46,000, credit +1,000 overpayment -> totalAssets 47,000, liabilities 0, netWorth 47,000
        val marOverpay = engine.buildTransfer(
            meta = TransactionMeta(id = "tx-mar-repay", occurredAt = ms(2026, 3, 5)),
            fromAccountId = debit.id,
            toAccountId = credit.id,
            amount = Money.fromDecimalString("4000.00"),
        )

        val txs = listOf(openDebit, openExcluded, febCreditExp, marOverpay)

        val yearly = StatsAggregator.aggregate(
            query = StatsQuery(
                periodType = StatsPeriodType.YEARLY,
                targetYear = 2026,
                targetMonth = YearMonth.of(2026, 3),
                calendarMonth = YearMonth.of(2026, 2),
            ),
            allTransactions = txs,
            accountsById = accountsById,
            zoneId = zone,
        )

        assertEquals(StatsTrendGranularity.MONTHLY, yearly.granularity)
        assertEquals(12, yearly.trendBuckets.size)
        assertEquals(12, yearly.netWorthTrend.size)

        // Jan point: excludedFund ignored -> assets 50,000, liabilities 0, netWorth 50,000
        val janPoint = yearly.netWorthTrend[0]
        assertEquals(Money.fromDecimalString("50000.00"), janPoint.totalAssets)
        assertEquals(Money.zero(Currency.CNY), janPoint.totalLiabilities)
        assertEquals(Money.fromDecimalString("50000.00"), janPoint.netWorth)

        // Feb point: assets 50,000, liabilities 3,000, netWorth 47,000
        val febPoint = yearly.netWorthTrend[1]
        assertEquals(Money.fromDecimalString("50000.00"), febPoint.totalAssets)
        assertEquals(Money.fromDecimalString("3000.00"), febPoint.totalLiabilities)
        assertEquals(Money.fromDecimalString("47000.00"), febPoint.netWorth)

        // Mar point (credit overpayment +1,000 treated as asset): assets 47,000, liabilities 0, netWorth 47,000
        val marPoint = yearly.netWorthTrend[2]
        assertEquals(Money.fromDecimalString("47000.00"), marPoint.totalAssets)
        assertEquals(Money.zero(Currency.CNY), marPoint.totalLiabilities)
        assertEquals(Money.fromDecimalString("47000.00"), marPoint.netWorth)

        // Calendar month check for Feb 2026 (28 days, 2026-02-01 is Sunday -> ISO 7)
        assertEquals(28, yearly.calendarMonth.daysInMonth)
        assertEquals(7, yearly.calendarMonth.firstDayOfWeekIso)
        assertEquals(1, yearly.calendarMonth.activeDayCount)
        val day10 = yearly.calendarMonth.days[9]
        assertEquals(Money.fromDecimalString("3000.00"), day10.expense)
        assertEquals(1, day10.transactions.size)

        // Custom range boundary check: 45 days -> DAILY, 46 days -> MONTHLY
        val range45 = StatsAggregator.aggregate(
            query = StatsQuery(
                periodType = StatsPeriodType.CUSTOM_RANGE,
                customStartDate = LocalDate.of(2026, 1, 1),
                customEndDate = LocalDate.of(2026, 2, 14),
            ),
            allTransactions = txs,
            accountsById = accountsById,
            zoneId = zone,
        )
        assertEquals(45, range45.dateRange.dayCount)
        assertEquals(StatsTrendGranularity.DAILY, range45.granularity)
        assertEquals(45, range45.trendBuckets.size)

        val range46 = StatsAggregator.aggregate(
            query = StatsQuery(
                periodType = StatsPeriodType.CUSTOM_RANGE,
                customStartDate = LocalDate.of(2026, 1, 1),
                customEndDate = LocalDate.of(2026, 2, 15),
            ),
            allTransactions = txs,
            accountsById = accountsById,
            zoneId = zone,
        )
        assertEquals(46, range46.dateRange.dayCount)
        assertEquals(StatsTrendGranularity.MONTHLY, range46.granularity)
        assertTrue(range46.trendBuckets.size == 2)
    }
}
