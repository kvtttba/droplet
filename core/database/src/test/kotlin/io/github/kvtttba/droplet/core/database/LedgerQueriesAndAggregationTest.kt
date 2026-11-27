package io.github.kvtttba.droplet.core.database

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.dao.TagFilterMode
import io.github.kvtttba.droplet.core.database.dao.TransactionFilter
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerAggregator
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
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LedgerQueriesAndAggregationTest {

    private lateinit var database: DropletDatabase
    private lateinit var writer: LedgerTransactionWriter
    private lateinit var engine: LedgerEngine

    private val usdCardId = "acc:usd_debit"
    private val hkdCashId = "acc:hkd_cash"

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        DefaultLedgerSeeder(database).seedIfNeeded(nowEpochMs = 1_000L)
        writer = LedgerTransactionWriter(database)

        database.accountDao().upsertAll(
            listOf(
                Account(
                    id = usdCardId,
                    name = "美元储蓄卡",
                    type = AccountType.ASSET,
                    subType = AccountSubType.DEBIT_CARD,
                    currency = Currency.USD,
                ).toEntity(),
                Account(
                    id = hkdCashId,
                    name = "港币现金",
                    type = AccountType.ASSET,
                    subType = AccountSubType.CASH,
                    currency = Currency.HKD,
                ).toEntity(),
            ),
        )

        database.exchangeRateDao().upsertAll(
            listOf(
                CachedExchangeRate(Currency.USD, Currency.CNY, "7.2", "mock-fx", 1_000L).toEntity(),
                CachedExchangeRate(Currency.HKD, Currency.CNY, "0.92", "mock-fx", 1_000L).toEntity(),
            ),
        )
        val rates = database.exchangeRateDao().getAll().toExchangeRateTable()
        engine = LedgerEngine(baseCurrency = Currency.CNY, rateProvider = rates)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun multiTagUnionAndIntersectionFilteringAndKeywordSearch() = runTest {
        val tWork = DefaultSeedCatalog.TagIds.WORKDAY
        val tTrip = DefaultSeedCatalog.TagIds.BUSINESS_TRIP
        val tTravel = DefaultSeedCatalog.TagIds.TRAVEL

        // tx1: tags [WORKDAY, BUSINESS_TRIP], category 餐饮/午餐, Nov 1
        writer.saveTransaction(
            engine.buildExpense(
                meta = TransactionMeta(
                    id = "tx-1",
                    occurredAt = 10_000L,
                    payee = "星巴克_100%",
                    note = "客户会面咖啡",
                    tagIds = listOf(tWork, tTrip),
                ),
                expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
                paymentAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
                amount = Money.fromDecimalString("45.00"),
            ),
        )
        // tx2: tags [WORKDAY], category 餐饮/早餐, Nov 2
        writer.saveTransaction(
            engine.buildExpense(
                meta = TransactionMeta(
                    id = "tx-2",
                    occurredAt = 20_000L,
                    payee = "便利店",
                    note = "豆浆油条",
                    tagIds = listOf(tWork),
                ),
                expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_BREAKFAST,
                paymentAccountId = DefaultSeedCatalog.AccountIds.WECHAT,
                amount = Money.fromDecimalString("12.00"),
            ),
        )
        // tx3: tags [BUSINESS_TRIP, TRAVEL], category 交通/打车, Nov 3
        writer.saveTransaction(
            engine.buildExpense(
                meta = TransactionMeta(
                    id = "tx-3",
                    occurredAt = 30_000L,
                    payee = "网约车",
                    note = "去机场",
                    tagIds = listOf(tTrip, tTravel),
                ),
                expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TAXI,
                paymentAccountId = DefaultSeedCatalog.AccountIds.ALIPAY,
                amount = Money.fromDecimalString("88.00"),
            ),
        )
        // tx4: tags [WORKDAY, BUSINESS_TRIP, TRAVEL], DRAFT status
        writer.saveTransaction(
            engine.buildExpense(
                meta = TransactionMeta(
                    id = "tx-4-draft",
                    occurredAt = 40_000L,
                    status = TransactionStatus.DRAFT,
                    payee = "待确认酒店",
                    tagIds = listOf(tWork, tTrip, tTravel),
                ),
                expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_ENTERTAINMENT_TRAVEL,
                paymentAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
                amount = Money.fromDecimalString("500.00"),
            ),
        )

        val txDao = database.transactionDao()

        // Tag OR (ANY): WORKDAY or TRAVEL -> tx-1, tx-2, tx-3 (excludes DRAFT by default)
        val anyResult = txDao.observeFiltered(
            TransactionFilter(
                tagIds = setOf(tWork, tTravel),
                tagFilterMode = TagFilterMode.ANY,
            ),
        ).first()
        assertEquals(listOf("tx-3", "tx-2", "tx-1"), anyResult.map { it.transaction.id })

        // Tag AND (ALL): WORKDAY and BUSINESS_TRIP -> only tx-1
        val allResult = txDao.observeFiltered(
            TransactionFilter(
                tagIds = setOf(tWork, tTrip),
                tagFilterMode = TagFilterMode.ALL,
            ),
        ).first()
        assertEquals(listOf("tx-1"), allResult.map { it.transaction.id })

        // Parent category filter ("餐饮") matches BOTH child categories (午餐 + 早餐)
        val foodTreeResult = txDao.observeFiltered(
            TransactionFilter(categoryAccountIds = setOf(DefaultSeedCatalog.AccountIds.EXP_FOOD)),
        ).first()
        assertEquals(listOf("tx-2", "tx-1"), foodTreeResult.map { it.transaction.id })

        // Date range [10_000, 30_000) -> inclusive start, exclusive end -> tx-2, tx-1
        val rangeResult = txDao.observeInDateRange(10_000L, 30_000L).first()
        assertEquals(listOf("tx-2", "tx-1"), rangeResult.map { it.transaction.id })

        // Keyword search matches payee with escaped SQLite wildcards (`_`, `%`), note, category name, tag name
        val escapedWildcard = txDao.observeFiltered(TransactionFilter(keyword = "_100%")).first()
        assertEquals(listOf("tx-1"), escapedWildcard.map { it.transaction.id })

        val byNote = txDao.observeFiltered(TransactionFilter(keyword = "机场")).first()
        assertEquals(listOf("tx-3"), byNote.map { it.transaction.id })

        val byCategoryName = txDao.observeFiltered(TransactionFilter(keyword = "早餐")).first()
        assertEquals(listOf("tx-2"), byCategoryName.map { it.transaction.id })

        val byTagName = txDao.observeFiltered(TransactionFilter(keyword = "旅游")).first()
        assertEquals(listOf("tx-3"), byTagName.map { it.transaction.id })

        // Account filter
        val byWechat = txDao.observeFiltered(
            TransactionFilter(accountIds = setOf(DefaultSeedCatalog.AccountIds.WECHAT)),
        ).first()
        assertEquals(listOf("tx-2"), byWechat.map { it.transaction.id })

        // Filtered historical base summary with tag intersection vs union
        val summaryAny = txDao.observeFilteredPeriodSummary(
            baseCurrency = Currency.CNY,
            filter = TransactionFilter(tagIds = setOf(tWork, tTrip), tagFilterMode = TagFilterMode.ANY),
        ).first()
        assertEquals(Money.fromDecimalString("145.00").amountMinor, summaryAny.netExpenseBaseMinor)
        assertEquals(3, summaryAny.transactionCount)

        val summaryAll = txDao.observeFilteredPeriodSummary(
            baseCurrency = Currency.CNY,
            filter = TransactionFilter(tagIds = setOf(tWork, tTrip), tagFilterMode = TagFilterMode.ALL),
        ).first()
        assertEquals(Money.fromDecimalString("45.00").amountMinor, summaryAll.netExpenseBaseMinor)
        assertEquals(1, summaryAll.transactionCount)
    }

    @Test
    fun historicalBaseTotalsRemainLockedWhenExchangeRatesChange() = runTest {
        // Record $100 USD expense at locked rate USD/CNY = 7.20 -> ¥720.00 CNY
        writer.saveTransaction(
            engine.buildExpense(
                meta = TransactionMeta(
                    id = "tx-usd-exp",
                    occurredAt = 10_000L,
                    tagIds = listOf(DefaultSeedCatalog.TagIds.TRAVEL),
                ),
                expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_DINNER,
                paymentAccountId = usdCardId,
                amount = Money.fromDecimalString("100.00", Currency.USD),
            ),
        )
        // Record HK$200 HKD expense at locked rate HKD/CNY = 0.92 -> ¥184.00 CNY
        writer.saveTransaction(
            engine.buildExpense(
                meta = TransactionMeta(
                    id = "tx-hkd-exp",
                    occurredAt = 20_000L,
                    tagIds = listOf(DefaultSeedCatalog.TagIds.TRAVEL),
                ),
                expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TRANSIT,
                paymentAccountId = hkdCashId,
                amount = Money.fromDecimalString("200.00", Currency.HKD),
            ),
        )
        // Record ¥10,000 CNY salary income
        writer.saveTransaction(
            engine.buildIncome(
                meta = TransactionMeta(id = "tx-salary", occurredAt = 25_000L),
                incomeCategoryAccountId = DefaultSeedCatalog.AccountIds.INC_WORK_SALARY,
                depositAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                amount = Money.fromDecimalString("10000.00", Currency.CNY),
            ),
        )
        // Record VOID transaction that must be ignored in balances & summaries
        writer.saveTransaction(
            engine.buildExpense(
                meta = TransactionMeta(
                    id = "tx-void",
                    occurredAt = 26_000L,
                    status = TransactionStatus.VOID,
                ),
                expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_DINNER,
                paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                amount = Money.fromDecimalString("999.00", Currency.CNY),
            ),
        )

        val summaryBeforeRateMove = database.transactionDao()
            .observePeriodSummary(Currency.CNY)
            .first()
        assertEquals(Money.fromDecimalString("904.00").amountMinor, summaryBeforeRateMove.netExpenseBaseMinor)
        assertEquals(Money.fromDecimalString("10000.00").amountMinor, summaryBeforeRateMove.netIncomeBaseMinor)
        assertEquals(Money.fromDecimalString("9096.00").amountMinor, summaryBeforeRateMove.netSavingsBaseMinor)

        // Now exchange rates move sharply (USD -> 7.80, HKD -> 1.00)
        database.exchangeRateDao().upsertAll(
            listOf(
                CachedExchangeRate(Currency.USD, Currency.CNY, "7.8", "mock-fx", 50_000L).toEntity(),
                CachedExchangeRate(Currency.HKD, Currency.CNY, "1", "mock-fx", 50_000L).toEntity(),
            ),
        )

        // Historical locked base-currency reports MUST NOT drift!
        val summaryAfterRateMove = database.transactionDao()
            .observePeriodSummary(Currency.CNY)
            .first()
        assertEquals(summaryBeforeRateMove, summaryAfterRateMove)

        val byAccount = database.transactionDao()
            .observeAccountHistoricalBaseTotals(Currency.CNY)
            .first()
            .associateBy { it.accountId }
        assertEquals(
            Money.fromDecimalString("720.00").amountMinor,
            byAccount.getValue(DefaultSeedCatalog.AccountIds.EXP_FOOD_DINNER).totalBaseAmountMinor,
        )
        assertEquals(
            Money.fromDecimalString("184.00").amountMinor,
            byAccount.getValue(DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TRANSIT).totalBaseAmountMinor,
        )

        // Meanwhile original-currency account balances reflect exact native amounts
        val origBalances = database.transactionDao()
            .observeAccountOriginalBalances()
            .first()
            .associate { (it.accountId to it.currency) to it.balanceMinor }
        assertEquals(Money.fromDecimalString("-100.00", Currency.USD).amountMinor, origBalances[usdCardId to Currency.USD])
        assertEquals(Money.fromDecimalString("-200.00", Currency.HKD).amountMinor, origBalances[hkdCashId to Currency.HKD])
        assertEquals(
            Money.fromDecimalString("10000.00", Currency.CNY).amountMinor,
            origBalances[DefaultSeedCatalog.AccountIds.DEBIT_CARD to Currency.CNY],
        )

        // Latest cached rate table converts current foreign balance at new 7.80 rate
        val latestTable: ExchangeRateTable = database.exchangeRateDao().getAll().toExchangeRateTable()
        assertEquals("7.8", latestTable.requireRate(Currency.USD, Currency.CNY).rateDecimal)
    }

    @Test
    fun linkedRefundsAndPendingReimbursementsWorkEndToEnd() = runTest {
        // 1. Original expense ¥200 + two partial refunds (¥50 + ¥30) -> net expense ¥120
        val origExpense = engine.buildExpense(
            meta = TransactionMeta(id = "tx-shop-1", occurredAt = 10_000L),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_SHOPPING_CLOTHING,
            paymentAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
            amount = Money.fromDecimalString("200.00"),
        )
        val refund1 = engine.buildRefund(
            meta = TransactionMeta(id = "tx-ref-1", occurredAt = 11_000L),
            originalTransactionId = "tx-shop-1",
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_SHOPPING_CLOTHING,
            refundToAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
            refundAmount = Money.fromDecimalString("50.00"),
        )
        val refund2 = engine.buildRefund(
            meta = TransactionMeta(id = "tx-ref-2", occurredAt = 12_000L),
            originalTransactionId = "tx-shop-1",
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_SHOPPING_CLOTHING,
            refundToAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
            refundAmount = Money.fromDecimalString("30.00"),
        )
        writer.saveTransactions(listOf(origExpense, refund1, refund2))

        val linkedRefunds = database.transactionDao()
            .observeLinkedTransactionsByType("tx-shop-1", TransactionDisplayType.REFUND)
            .first()
            .map { it.toDomain() }
        assertEquals(listOf("tx-ref-1", "tx-ref-2"), linkedRefunds.map { it.id })

        val netSummary = LedgerAggregator.netExpenseAfterRefunds(
            originalExpense = database.transactionDao().getById("tx-shop-1")!!.toDomain(),
            refunds = linkedRefunds,
            expenseAccountId = DefaultSeedCatalog.AccountIds.EXP_SHOPPING_CLOTHING,
        )
        assertEquals(Money.fromDecimalString("120.00"), netSummary.originalCurrencyNet)
        assertEquals(2, netSummary.refundCount)

        // 2. Reimbursable advance ¥500 MUST NOT inflate personal expense
        val advance = engine.buildReimbursableAdvance(
            meta = TransactionMeta(id = "tx-reimb-adv", occurredAt = 20_000L, payee = "出差高铁"),
            reimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
            paymentAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
            amount = Money.fromDecimalString("500.00"),
        )
        writer.saveTransaction(advance)

        val pendingBeforeSettle = database.transactionDao().observePendingReimbursements().first()
        assertEquals(listOf("tx-reimb-adv"), pendingBeforeSettle.map { it.transaction.id })

        val summaryBeforeSettle = database.transactionDao().observePeriodSummary(Currency.CNY).first()
        // Only the ¥120 net clothing expense counts; ¥500 advance is Asset:Reimbursement
        assertEquals(Money.fromDecimalString("120.00").amountMinor, summaryBeforeSettle.netExpenseBaseMinor)

        // 3. Reimburse settlement: ¥450 deposited + ¥50 shortfall booked to personal expense
        val settlement = engine.buildReimbursementSettlement(
            meta = TransactionMeta(id = "tx-reimb-settle", occurredAt = 25_000L),
            advanceTransactionId = "tx-reimb-adv",
            reimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
            depositAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            settledReimbursementAmount = Money.fromDecimalString("500.00"),
            receivedAmount = Money.fromDecimalString("450.00"),
            shortfallExpenseAccountId = DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_LONG_DISTANCE,
        )
        writer.saveTransaction(settlement)
        writer.updateReimburseState("tx-reimb-adv", ReimburseState.SETTLED, updatedAt = 25_000L)

        // Pending list is now empty; net expense increases by the ¥50 shortfall to ¥170
        assertTrue(database.transactionDao().observePendingReimbursements().first().isEmpty())
        val summaryAfterSettle = database.transactionDao().observePeriodSummary(Currency.CNY).first()
        assertEquals(Money.fromDecimalString("170.00").amountMinor, summaryAfterSettle.netExpenseBaseMinor)

        // Reimbursement asset account is back to exact 0 balance
        val reimbBal = database.transactionDao()
            .observeSingleAccountOriginalBalances(DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY)
            .first()
            .single()
        assertEquals(0L, reimbBal.balanceMinor)
    }

    @Test
    fun reactiveFlowQueries_emitUpdatesOnWriteUpdateAndVoidAndSupportCombinedFilters() = runTest {
        val txDao = database.transactionDao()

        // Initially empty summary returns exact zeros
        val emptySummary = txDao.observePeriodSummary(Currency.CNY).first()
        assertEquals(0L, emptySummary.netExpenseBaseMinor)
        assertEquals(0L, emptySummary.netIncomeBaseMinor)
        assertEquals(0, emptySummary.transactionCount)

        // Write first tagged expense with special SQL characters (`\`, `'`, `%`, `_`)
        val tx1 = engine.buildExpense(
            meta = TransactionMeta(
                id = "tx-flow-1",
                occurredAt = 100_000L,
                payee = "O'Brien\\Cafe_50%",
                tagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY, DefaultSeedCatalog.TagIds.FIXED_EXPENSE),
            ),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("50.00"),
        )
        writer.saveTransaction(tx1)

        // Filtered historical base totals by EXPENSE + ALL tags
        val expenseTotals = txDao.observeFilteredHistoricalBaseTotals(
            baseCurrency = Currency.CNY,
            filter = TransactionFilter(
                tagIds = setOf(DefaultSeedCatalog.TagIds.WORKDAY, DefaultSeedCatalog.TagIds.FIXED_EXPENSE),
                tagFilterMode = TagFilterMode.ALL,
            ),
            accountTypes = setOf(AccountType.EXPENSE),
        ).first()
        assertEquals(1, expenseTotals.size)
        assertEquals(DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH, expenseTotals.single().accountId)
        assertEquals(Money.fromDecimalString("50.00").amountMinor, expenseTotals.single().totalBaseAmountMinor)

        // Escaped special-char keyword matches safely without SQL error or wildcard leakage
        assertEquals(
            listOf("tx-flow-1"),
            txDao.observeFiltered(TransactionFilter(keyword = "O'Brien\\Cafe_50%")).first().map { it.transaction.id },
        )
        assertTrue(
            txDao.observeFiltered(TransactionFilter(keyword = "O'Brien\\CafeX50%")).first().isEmpty(),
        )

        // Combined status + displayType filter
        val draftTx = engine.buildIncome(
            meta = TransactionMeta(
                id = "tx-flow-draft",
                occurredAt = 110_000L,
                status = TransactionStatus.DRAFT,
            ),
            incomeCategoryAccountId = DefaultSeedCatalog.AccountIds.INC_WORK_BONUS,
            depositAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("300.00"),
        )
        writer.saveTransaction(draftTx)

        val postedAndDraft = txDao.observeFiltered(
            TransactionFilter(
                statuses = setOf(TransactionStatus.POSTED, TransactionStatus.DRAFT),
                displayTypes = setOf(TransactionDisplayType.INCOME, TransactionDisplayType.EXPENSE),
            ),
        ).first()
        assertEquals(listOf("tx-flow-draft", "tx-flow-1"), postedAndDraft.map { it.transaction.id })

        // Void tx1 -> POSTED summary drops back to 0 immediately
        writer.voidTransaction("tx-flow-1", updatedAt = 120_000L)
        val afterVoid = txDao.observePeriodSummary(Currency.CNY).first()
        assertEquals(0L, afterVoid.netExpenseBaseMinor)
        assertEquals(0, afterVoid.transactionCount)
    }
}
