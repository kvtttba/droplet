package io.github.kvtttba.droplet.core.database

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.repository.LocalBudgetRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLedgerExportRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LocalStatsRepository
import io.github.kvtttba.droplet.core.database.repository.SaveBudgetRequest
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.export.ExportFormat
import io.github.kvtttba.droplet.core.domain.export.ExportScope
import io.github.kvtttba.droplet.core.domain.export.LedgerExportEngine
import io.github.kvtttba.droplet.core.domain.export.LedgerExportRequest
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.domain.stats.StatsPeriodType
import io.github.kvtttba.droplet.core.domain.stats.StatsQuery
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StatsAndExportRepositoryTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val fixedNow = LocalDate.of(2026, 11, 20).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    private val clock = NetworkClock { fixedNow }

    private lateinit var context: Context
    private lateinit var database: DropletDatabase
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var budgetRepository: LocalBudgetRepository
    private lateinit var statsRepository: LocalStatsRepository
    private lateinit var exportRepository: LocalLedgerExportRepository
    private lateinit var engine: LedgerEngine

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        val seeder = DefaultLedgerSeeder(database)
        val writer = LedgerTransactionWriter(database)
        engine = LedgerEngine(baseCurrency = Currency.CNY)
        ledgerRepository = LocalLedgerRepository(database, writer, seeder, engine)
        budgetRepository = LocalBudgetRepository(database, seeder, clock, zone)
        statsRepository = LocalStatsRepository(database, clock, zone)
        exportRepository = LocalLedgerExportRepository(database, LedgerExportEngine(), clock)
        ledgerRepository.ensureSeeded(fixedNow)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun statsRepository_aggregatesLedgerPostingsWithBudgetProgressAndCalendar() = runTest {
        budgetRepository.saveBudget(
            SaveBudgetRequest(
                period = BudgetPeriod.MONTHLY,
                categoryAccountId = null,
                limitAmountMinor = Money.fromDecimalString("2000.00").amountMinor,
                warnThresholdPct = 80,
            ),
        )

        val day10 = LocalDate.of(2026, 11, 10).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val day15 = LocalDate.of(2026, 11, 15).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()

        val salaryTx = engine.buildIncome(
            meta = TransactionMeta(occurredAt = day10, payee = "发薪"),
            incomeCategoryAccountId = DefaultSeedCatalog.AccountIds.INC_WORK_SALARY,
            depositAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("12000.00"),
        )
        val foodTx = engine.buildExpense(
            meta = TransactionMeta(
                occurredAt = day15,
                payee = "晚餐测试",
                tagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY),
            ),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_DINNER,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("360.00"),
        )
        val refundTx = engine.buildRefund(
            meta = TransactionMeta(occurredAt = day15 + 3_600_000L, payee = "退菜退款"),
            originalTransactionId = foodTx.id,
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_DINNER,
            refundToAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            refundAmount = Money.fromDecimalString("60.00"),
        )
        val advanceTx = engine.buildReimbursableAdvance(
            meta = TransactionMeta(occurredAt = day15, payee = "公司垫付"),
            reimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
            paymentAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
            amount = Money.fromDecimalString("500.00"),
        )

        ledgerRepository.saveTransaction(salaryTx)
        ledgerRepository.saveTransaction(foodTx)
        ledgerRepository.saveTransaction(refundTx)
        ledgerRepository.saveTransaction(advanceTx)

        val dashboard = statsRepository.observeStatsDashboard(
            StatsQuery(
                periodType = StatsPeriodType.MONTHLY,
                targetMonth = YearMonth.of(2026, 11),
                calendarMonth = YearMonth.of(2026, 11),
            ),
        ).first()

        assertEquals(Money.fromDecimalString("300.00"), dashboard.report.totalExpense)
        assertEquals(Money.fromDecimalString("12000.00"), dashboard.report.totalIncome)
        assertEquals(Money.fromDecimalString("11700.00"), dashboard.report.netBalance)
        assertEquals(1, dashboard.report.expenseRankings.size)
        assertEquals("餐饮", dashboard.report.expenseRankings.single().name)
        assertNotNull(dashboard.monthlyBudgetProgress)
        assertEquals(Money.fromDecimalString("300.00"), dashboard.monthlyBudgetProgress!!.spentAmount)

        // Calendar day 15 should carry 2 transactions (foodTx + refundTx) netting 300.00
        val day15Cell = dashboard.report.calendarMonth.days[14]
        assertEquals(Money.fromDecimalString("300.00"), day15Cell.expense)
        assertEquals(2, day15Cell.transactionCount)

        // Latest net worth point: debit 11,700 + reimburse asset 500 = 12,200 assets; credit card -500 = 500 liabilities -> netWorth 11,700
        val latestNw = dashboard.report.netWorthTrend.last()
        assertEquals(Money.fromDecimalString("12200.00"), latestNw.totalAssets)
        assertEquals(Money.fromDecimalString("500.00"), latestNw.totalLiabilities)
        assertEquals(Money.fromDecimalString("11700.00"), latestNw.netWorth)
    }

    @Test
    fun exportRepository_previewsAndWritesValidLocalCsvXlsxAndBeancountFiles() = runTest {
        val tx = engine.buildExpense(
            meta = TransactionMeta(
                occurredAt = fixedNow,
                payee = "测试导出咖啡",
                note = "测试备注",
                tagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY),
            ),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_SNACKS,
            paymentAccountId = DefaultSeedCatalog.AccountIds.ALIPAY,
            amount = Money.fromDecimalString("28.50"),
        )
        ledgerRepository.saveTransaction(tx)

        val preview = exportRepository.observeExportPreview(
            LedgerExportRequest(
                format = ExportFormat.XLSX,
                scope = ExportScope.ALL_TIME,
                zoneId = zone,
                generatedAtEpochMs = fixedNow,
            ),
        ).first()
        assertEquals(1, preview.transactionCount)
        assertEquals(2, preview.postingCount)
        assertEquals(Money.fromDecimalString("28.50"), preview.totalExpenseBase)

        val outDir = File(context.cacheDir, "test_exports_${System.nanoTime()}")
        try {
            val csvRes = exportRepository.exportToLocalFile(
                LedgerExportRequest(format = ExportFormat.CSV, scope = ExportScope.ALL_TIME, zoneId = zone),
                outDir,
            )
            assertTrue(csvRes.file.exists())
            assertTrue(csvRes.file.readText(StandardCharsets.UTF_8).contains("测试导出咖啡"))

            val xlsxRes = exportRepository.exportToLocalFile(
                LedgerExportRequest(format = ExportFormat.XLSX, scope = ExportScope.ALL_TIME, zoneId = zone),
                outDir,
            )
            assertTrue(xlsxRes.file.exists())
            ZipFile(xlsxRes.file).use { zf ->
                assertNotNull(zf.getEntry("xl/workbook.xml"))
                assertNotNull(zf.getEntry("xl/worksheets/sheet1.xml"))
                assertNotNull(zf.getEntry("xl/worksheets/sheet2.xml"))
                assertNotNull(zf.getEntry("xl/worksheets/sheet3.xml"))
            }

            val bcRes = exportRepository.exportToLocalFile(
                LedgerExportRequest(format = ExportFormat.BEANCOUNT, scope = ExportScope.ALL_TIME, zoneId = zone),
                outDir,
            )
            assertTrue(bcRes.file.exists())
            val bcText = bcRes.file.readText(StandardCharsets.UTF_8)
            assertTrue(bcText.contains("option \"operating_currency\" \"CNY\""))
            assertTrue(bcText.contains("测试导出咖啡"))
            assertTrue(bcText.contains("#工作日"))
        } finally {
            outDir.deleteRecursively()
        }
    }
}
