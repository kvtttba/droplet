package io.github.kvtttba.droplet.core.database

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.repository.BudgetRepository
import io.github.kvtttba.droplet.core.database.repository.LocalBudgetRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LocalRecurringRuleRepository
import io.github.kvtttba.droplet.core.database.repository.LocalTemplateRepository
import io.github.kvtttba.droplet.core.database.repository.RecurringRuleRepository
import io.github.kvtttba.droplet.core.database.repository.SaveBudgetRequest
import io.github.kvtttba.droplet.core.database.repository.SaveRecurringRuleRequest
import io.github.kvtttba.droplet.core.database.repository.SaveTemplateRequest
import io.github.kvtttba.droplet.core.database.repository.TemplateRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.automation.BudgetAlertLevel
import io.github.kvtttba.droplet.core.domain.automation.RecurringPayload
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.RecurringFrequency
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionSource
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BudgetRecurringTemplateRepositoryTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private var nowMs: Long = LocalDate.of(2026, 11, 21).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
    private val clock = NetworkClock { nowMs }

    private lateinit var context: Context
    private lateinit var database: DropletDatabase
    private lateinit var writer: LedgerTransactionWriter
    private lateinit var engine: LedgerEngine
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var recurringRepository: RecurringRuleRepository
    private lateinit var templateRepository: TemplateRepository

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        val seeder = DefaultLedgerSeeder(database)
        writer = LedgerTransactionWriter(database)
        engine = LedgerEngine(baseCurrency = Currency.CNY)
        ledgerRepository = LocalLedgerRepository(database, writer, seeder, engine)
        budgetRepository = LocalBudgetRepository(database, seeder, clock, zone)
        recurringRepository = LocalRecurringRuleRepository(database, writer, seeder, engine, clock, zone)
        templateRepository = LocalTemplateRepository(database, writer, seeder, engine, clock)
        ledgerRepository.ensureSeeded(nowMs)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun budgetRepository_preventsDuplicatesAndTracksReactiveNetExpenseProgress() = runTest {
        val total = budgetRepository.saveBudget(
            SaveBudgetRequest(
                period = BudgetPeriod.MONTHLY,
                categoryAccountId = null,
                limitAmountMinor = Money.fromDecimalString("1000.00").amountMinor,
                warnThresholdPct = 80,
            ),
        )
        budgetRepository.saveBudget(
            SaveBudgetRequest(
                period = BudgetPeriod.MONTHLY,
                categoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD,
                limitAmountMinor = Money.fromDecimalString("500.00").amountMinor,
                warnThresholdPct = 80,
            ),
        )

        // Duplicate overall monthly budget rejected even though category_account_id is NULL
        try {
            budgetRepository.saveBudget(
                SaveBudgetRequest(
                    period = BudgetPeriod.MONTHLY,
                    categoryAccountId = null,
                    limitAmountMinor = Money.fromDecimalString("2000.00").amountMinor,
                ),
            )
            fail("Expected duplicate overall budget to fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("总预算"))
        }

        // Record child food lunch ¥450 + partial refund ¥50 -> net ¥400 (80% of ¥500 food budget -> WARNING)
        val lunchTx = engine.buildExpense(
            meta = TransactionMeta(occurredAt = nowMs, payee = "测试餐厅"),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("450.00"),
        )
        ledgerRepository.saveTransaction(lunchTx)

        val refundTx = engine.buildRefund(
            meta = TransactionMeta(occurredAt = nowMs + 60_000L, payee = "测试退款"),
            originalTransactionId = lunchTx.id,
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
            refundToAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            refundAmount = Money.fromDecimalString("50.00"),
        )
        ledgerRepository.saveTransaction(refundTx)

        val overview = budgetRepository.observeBudgetOverview(YearMonth.of(2026, 11)).first()
        val overall = overview.monthlyOverall
        assertNotNull(overall)
        assertEquals(Money.fromDecimalString("400.00"), overall!!.spentAmount)
        assertEquals(BudgetAlertLevel.NORMAL, overall.alertLevel)

        val foodProgress = overview.monthlyCategoryBudgets.single()
        assertEquals(Money.fromDecimalString("400.00"), foodProgress.spentAmount)
        assertEquals(BudgetAlertLevel.WARNING, foodProgress.alertLevel)
        assertEquals("80.0", foodProgress.usedPercentageDecimal)

        // Editing existing overall budget updates limit cleanly without duplicate error
        budgetRepository.saveBudget(
            SaveBudgetRequest(
                id = total.id,
                period = BudgetPeriod.MONTHLY,
                categoryAccountId = null,
                limitAmountMinor = Money.fromDecimalString("400.00").amountMinor,
                warnThresholdPct = 80,
            ),
        )
        val updated = budgetRepository.getBudgetOverviewSnapshot(nowMs)
        assertEquals(BudgetAlertLevel.EXCEEDED, updated.monthlyOverall?.alertLevel)
    }

    @Test
    fun recurringRuleRepository_idempotentAutoPostManualConfirmAndSkip() = runTest {
        val nov10 = LocalDate.of(2026, 11, 10).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val nov15 = LocalDate.of(2026, 11, 15).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

        // 1. Auto-post monthly rent rule due on Nov 10 (now is Nov 21 -> due once)
        val autoRule = recurringRepository.saveRule(
            SaveRecurringRuleRequest(
                name = "每月房租",
                frequency = RecurringFrequency.MONTHLY,
                intervalCount = 1,
                nextRunAt = nov10,
                payload = RecurringPayload(
                    displayType = TransactionDisplayType.EXPENSE,
                    amountMinor = Money.fromDecimalString("3000.00").amountMinor,
                    categoryAccountId = DefaultSeedCatalog.AccountIds.EXP_HOUSING_RENT_MORTGAGE,
                    accountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                    payee = "测试房东",
                ),
                autoPost = true,
            ),
        )

        // 2. Manual-confirm subscription due on Nov 15
        val manualRule = recurringRepository.saveRule(
            SaveRecurringRuleRequest(
                name = "软件订阅",
                frequency = RecurringFrequency.MONTHLY,
                intervalCount = 1,
                nextRunAt = nov15,
                payload = RecurringPayload(
                    displayType = TransactionDisplayType.EXPENSE,
                    amountMinor = Money.fromDecimalString("30.00").amountMinor,
                    categoryAccountId = DefaultSeedCatalog.AccountIds.EXP_EDUCATION_SUBSCRIPTION,
                    accountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
                    payee = "云服务",
                ),
                autoPost = false,
            ),
        )

        // First auto-post scan posts ONLY autoRule and advances nextRunAt to Dec 10
        val report1 = recurringRepository.processDueAutoPostRules(nowMs)
        assertEquals(1, report1.postedCount)
        assertEquals(TransactionSource.RECURRING, report1.postedItems.single().transaction.source)
        assertEquals(
            LocalDate.of(2026, 12, 10),
            Instant.ofEpochMilli(report1.postedItems.single().rule.nextRunAt).atZone(zone).toLocalDate(),
        )

        // Second auto-post scan at same timestamp is strictly idempotent (0 new posts)
        val report2 = recurringRepository.processDueAutoPostRules(nowMs)
        assertEquals(0, report2.postedCount)
        assertEquals(1, database.transactionDao().countTransactions())

        // Manual rule shows up as pending confirmation -> confirm once, second confirm fails
        val itemsBeforeConfirm = recurringRepository.observeRulesWithStatus(nowMs).first()
        val manualItem = itemsBeforeConfirm.first { it.rule.id == manualRule.id }
        assertTrue(manualItem.isPendingConfirmation)
        assertFalse(itemsBeforeConfirm.first { it.rule.id == autoRule.id }.isDue)

        val confirmed = recurringRepository.confirmAndPostDueRule(manualRule.id, nowMs)
        assertEquals(TransactionSource.RECURRING, confirmed.transaction.source)
        assertEquals(
            LocalDate.of(2026, 12, 15),
            Instant.ofEpochMilli(confirmed.rule.nextRunAt).atZone(zone).toLocalDate(),
        )
        assertEquals(2, database.transactionDao().countTransactions())

        // Advance time to Dec 20 and skip manualRule without creating a transaction
        val dec20 = LocalDate.of(2026, 12, 20).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val skipped = recurringRepository.skipCurrentDueOccurrence(manualRule.id, dec20)
        assertEquals(
            LocalDate.of(2027, 1, 15),
            Instant.ofEpochMilli(skipped.nextRunAt).atZone(zone).toLocalDate(),
        )
        assertEquals(2, database.transactionDao().countTransactions())

        // Terminate autoRule -> marked paused & terminated
        recurringRepository.terminateRule(autoRule.id)
        val afterTerminate = recurringRepository.getRulesWithStatusSnapshot(dec20)
            .first { it.rule.id == autoRule.id }
        assertTrue(afterTerminate.rule.isPaused)
        assertTrue(afterTerminate.isTerminated)
    }

    @Test
    fun templateRepository_postsBalancedTransactionAndTracksUsageAndCommitListener() = runTest {
        var committedCount = 0
        writer.addOnTransactionCommittedListener { committedCount++ }

        val template = templateRepository.saveTemplate(
            SaveTemplateRequest(
                name = "通勤地铁",
                payload = RecurringPayload(
                    displayType = TransactionDisplayType.EXPENSE,
                    amountMinor = Money.fromDecimalString("6.00").amountMinor,
                    categoryAccountId = DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TRANSIT,
                    accountId = DefaultSeedCatalog.AccountIds.ALIPAY,
                    payee = "城市地铁",
                    tagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY),
                    note = "早高峰",
                ),
            ),
        )

        val posted = templateRepository.postFromTemplate(template.id, occurredAtEpochMs = nowMs)
        assertEquals(TransactionSource.MANUAL, posted.source)
        assertEquals(listOf(DefaultSeedCatalog.TagIds.WORKDAY), posted.tagIds)
        assertEquals(1, committedCount)

        val loaded = templateRepository.getTemplateById(template.id)
        assertNotNull(loaded)
        assertEquals(1, loaded!!.template.useCount)
        assertEquals(nowMs, loaded.template.lastUsedAt)
        assertEquals("交通·地铁公交", loaded.categoryOrTargetName)
        assertEquals("支付宝", loaded.accountName)
    }
}
