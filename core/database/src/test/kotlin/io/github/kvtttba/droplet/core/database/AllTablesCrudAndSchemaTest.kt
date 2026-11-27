package io.github.kvtttba.droplet.core.database

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AiDraft
import io.github.kvtttba.droplet.core.model.AiDraftInputType
import io.github.kvtttba.droplet.core.model.AiDraftStatus
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Budget
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DebtProfile
import io.github.kvtttba.droplet.core.model.DepositInterestMode
import io.github.kvtttba.droplet.core.model.InstallmentStatus
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.LoanInstallment
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.LoanPlan
import io.github.kvtttba.droplet.core.model.MarketQuote
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.RecurringFrequency
import io.github.kvtttba.droplet.core.model.RecurringRule
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionTemplate
import io.github.kvtttba.droplet.core.model.UnitPrice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AllTablesCrudAndSchemaTest {

    private lateinit var context: Context
    private lateinit var database: DropletDatabase

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        DefaultLedgerSeeder(database).seedIfNeeded(nowEpochMs = 1_000L)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun investmentAssetsMarketQuotesAndExchangeRates_crudAndCascade() = runTest {
        val holdingAcc = Account(
            id = "acc:hold:aapl",
            name = "美股持仓",
            type = AccountType.ASSET,
            subType = AccountSubType.INVEST_HOLDING,
            currency = Currency.USD,
        )
        database.accountDao().upsert(holdingAcc.toEntity())

        val stock = InvestmentAsset(
            id = "asset:aapl",
            accountId = holdingAcc.id,
            assetClass = AssetClass.US_STOCK,
            symbol = "AAPL",
            name = "Apple Inc.",
            currency = Currency.USD,
            quoteProviderHint = "eastmoney",
        )
        val deposit = InvestmentAsset(
            id = "asset:dep3y",
            accountId = holdingAcc.id,
            assetClass = AssetClass.FIXED_DEPOSIT,
            symbol = "DEP-3Y",
            name = "三年定期",
            currency = Currency.CNY,
            depositRateAnnualDecimal = "0.0225",
            depositStartAt = 10_000L,
            depositMaturityAt = 90_000L,
            depositInterestMode = DepositInterestMode.SIMPLE_AT_MATURITY,
        )
        database.investmentAssetDao().upsertAll(listOf(stock.toEntity(), deposit.toEntity()))
        assertEquals(2, database.investmentAssetDao().observeByAccountId(holdingAcc.id).first().size)
        assertEquals(
            "0.0225",
            database.investmentAssetDao().getById("asset:dep3y")!!.toDomain().depositRateAnnualDecimal,
        )

        val quote = MarketQuote(
            assetId = "asset:aapl",
            latestPrice = UnitPrice.of("228.45"),
            prevClose = UnitPrice.of("225.00"),
            dailyChangePctDecimal = "1.5333",
            priceCurrency = Currency.USD,
            sourceProvider = "mock-quote",
            isManualOverride = true,
            fetchedAt = 50_000L,
        )
        database.marketQuoteDao().upsert(quote.toEntity())
        val loadedQuote = database.marketQuoteDao().observeByAssetId("asset:aapl").first()!!.toDomain()
        assertEquals(quote, loadedQuote)

        // Deleting investment asset cascades to market_quotes
        database.investmentAssetDao().deleteById("asset:aapl")
        assertNull(database.marketQuoteDao().getByAssetId("asset:aapl"))

        // Exchange rates CRUD + triangulation via ExchangeRateTable
        database.exchangeRateDao().upsertAll(
            listOf(
                CachedExchangeRate(Currency.USD, Currency.CNY, "7.2", "mock", 100L).toEntity(),
                CachedExchangeRate(Currency.HKD, Currency.CNY, "0.9", "mock", 100L).toEntity(),
            ),
        )
        val rateTable = database.exchangeRateDao().observeAll().first().toExchangeRateTable()
        // 1 USD = 7.2 / 0.9 = 8 HKD via CNY triangulation
        assertEquals("8", rateTable.requireRate(Currency.USD, Currency.HKD).rateDecimal)
    }

    @Test
    fun loanPlansInstallmentsAndDebtProfiles_crudAndQueries() = runTest {
        val loanAcc = Account("acc:loan:mortgage", "房贷", AccountType.LIABILITY, AccountSubType.LOAN)
        val recvAcc = Account("acc:recv:alice", "应收-测试A", AccountType.ASSET, AccountSubType.RECEIVABLE)
        val payAcc = Account("acc:pay:alice", "应付-测试A", AccountType.LIABILITY, AccountSubType.PAYABLE)
        database.accountDao().upsertAll(listOf(loanAcc.toEntity(), recvAcc.toEntity(), payAcc.toEntity()))

        val plan = LoanPlan(
            id = "loan:mortgage",
            name = "商业房贷",
            method = LoanMethod.EQUAL_PRINCIPAL,
            totalPrincipalMinor = Money.fromDecimalString("240000.00").amountMinor,
            totalPeriods = 24,
            annualRateDecimal = "0.036",
            firstDueAt = 100_000L,
            monthlyRepayDay = 20,
            loanAccountId = loanAcc.id,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            interestExpenseAccountId = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
            autoPost = true,
            remindDaysBefore = 3,
        )
        val inst1 = LoanInstallment(
            id = "inst:1",
            loanPlanId = plan.id,
            periodNumber = 1,
            dueAt = 100_000L,
            principalMinor = Money.fromDecimalString("10000.00").amountMinor,
            interestOrFeeMinor = Money.fromDecimalString("720.00").amountMinor,
            remainingPrincipalMinor = Money.fromDecimalString("230000.00").amountMinor,
        )
        val inst2 = LoanInstallment(
            id = "inst:2",
            loanPlanId = plan.id,
            periodNumber = 2,
            dueAt = 200_000L,
            principalMinor = Money.fromDecimalString("10000.00").amountMinor,
            interestOrFeeMinor = Money.fromDecimalString("690.00").amountMinor,
            remainingPrincipalMinor = Money.fromDecimalString("220000.00").amountMinor,
        )
        database.loanDao().upsertPlanWithInstallments(plan.toEntity(), listOf(inst1.toEntity(), inst2.toEntity()))

        assertEquals(1, database.loanDao().observePlans().first().size)
        val dueNow = database.loanDao().observeInstallmentsByStatusUpTo(150_000L).first()
        assertEquals(listOf("inst:1"), dueNow.map { it.id })

        database.loanDao().markInstallmentStatus("inst:1", InstallmentStatus.PAID, transactionId = null, paidAt = 100_500L)
        assertTrue(database.loanDao().observeInstallmentsByStatusUpTo(150_000L).first().isEmpty())

        val profile = DebtProfile(
            id = "debt:alice",
            name = "测试往来人A",
            receivableAccountId = recvAcc.id,
            payableAccountId = payAcc.id,
            defaultDueAt = 300_000L,
            annualInterestRateDecimal = "0.05",
            note = "脱敏测试往来",
        )
        database.debtProfileDao().upsert(profile.toEntity())
        assertEquals(profile, database.debtProfileDao().getById(profile.id)!!.toDomain())

        // Deleting unposted receivable account sets debt_profiles.receivable_account_id to NULL
        database.accountDao().deleteById(recvAcc.id)
        val afterRecvDelete = database.debtProfileDao().getById(profile.id)!!
        assertNull(afterRecvDelete.receivableAccountId)
        assertEquals(payAcc.id, afterRecvDelete.payableAccountId)
    }

    @Test
    fun budgetsRecurringRulesTemplatesAndAiDrafts_crudAndLifecycle() = runTest {
        // Budgets: total budget (NULL category) + category budget
        val totalBudget = Budget(
            id = "budget:monthly:total",
            period = BudgetPeriod.MONTHLY,
            categoryAccountId = null,
            limitAmountMinor = Money.fromDecimalString("8000.00").amountMinor,
            warnThresholdPct = 80,
        )
        val foodBudget = Budget(
            id = "budget:monthly:food",
            period = BudgetPeriod.MONTHLY,
            categoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
            limitAmountMinor = Money.fromDecimalString("1500.00").amountMinor,
            warnThresholdPct = 90,
        )
        database.budgetDao().upsert(totalBudget.toEntity())
        database.budgetDao().upsert(foodBudget.toEntity())
        val monthlyBudgets = database.budgetDao().observeByPeriod(BudgetPeriod.MONTHLY).first()
        assertEquals(listOf("budget:monthly:total", "budget:monthly:food"), monthlyBudgets.map { it.id })

        // Deleting category cascades to its category budget
        database.accountDao().deleteById(DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH)
        assertNull(database.budgetDao().getById("budget:monthly:food"))
        assertNotNull(database.budgetDao().getById("budget:monthly:total"))

        // Recurring rules
        val dueRule = RecurringRule(
            id = "rec:rent",
            name = "房租",
            frequency = RecurringFrequency.MONTHLY,
            nextRunAt = 50_000L,
            templatePayloadJson = """{"amount":"3500.00"}""",
            autoPost = true,
        )
        val pausedRule = RecurringRule(
            id = "rec:paused",
            name = "暂停订阅",
            frequency = RecurringFrequency.MONTHLY,
            nextRunAt = 40_000L,
            templatePayloadJson = """{"amount":"25.00"}""",
            isPaused = true,
        )
        database.recurringRuleDao().upsert(dueRule.toEntity())
        database.recurringRuleDao().upsert(pausedRule.toEntity())
        assertEquals(listOf("rec:rent"), database.recurringRuleDao().getDueRules(60_000L).map { it.id })

        database.recurringRuleDao().advanceSchedule("rec:rent", lastRunAt = 50_000L, nextRunAt = 90_000L, updatedAt = 50_100L)
        assertTrue(database.recurringRuleDao().getDueRules(60_000L).isEmpty())

        // Templates
        val tpl = TransactionTemplate(
            id = "tpl:metro",
            name = "早晚地铁",
            displayType = TransactionDisplayType.EXPENSE,
            templatePayloadJson = """{"amount":"6.00"}""",
            sortOrder = 1,
        )
        database.templateDao().upsert(tpl.toEntity())
        database.templateDao().recordUsage("tpl:metro", usedAt = 70_000L)
        val updatedTpl = database.templateDao().getById("tpl:metro")!!.toDomain()
        assertEquals(1, updatedTpl.useCount)
        assertEquals(70_000L, updatedTpl.lastUsedAt)

        // AI drafts offline queue lifecycle
        val draft = AiDraft(
            id = "draft:1",
            inputType = AiDraftInputType.IMAGE,
            attachmentPathsJson = """["drafts/mock_receipt_01.jpg"]""",
            status = AiDraftStatus.PENDING_ONLINE,
            createdAt = 10_000L,
            updatedAt = 10_000L,
        )
        database.aiDraftDao().upsert(draft.toEntity())
        assertEquals(
            listOf("draft:1"),
            database.aiDraftDao().observeByStatuses(listOf(AiDraftStatus.PENDING_ONLINE)).first().map { it.id },
        )
        database.aiDraftDao().updateParsingState(
            draftId = "draft:1",
            status = AiDraftStatus.READY,
            parsedItemsJson = """{"items":[{"amount":"38.50"}]}""",
            errorMessage = null,
            updatedAt = 15_000L,
        )
        val readyDraft = database.aiDraftDao().getById("draft:1")!!.toDomain()
        assertEquals(AiDraftStatus.READY, readyDraft.status)
        assertEquals("""{"items":[{"amount":"38.50"}]}""", readyDraft.parsedItemsJson)
    }

    @Test
    fun exportedRoomSchemaJson_existsAndContainsAllSixteenTables() {
        val schemaPath = "${DropletDatabase::class.java.name}/${DropletDatabaseContract.SCHEMA_VERSION}.json"
        val jsonText = context.assets.open(schemaPath).bufferedReader().use { it.readText() }
        val root = JSONObject(jsonText)
        val dbObj = root.getJSONObject("database")
        assertEquals(DropletDatabaseContract.SCHEMA_VERSION, dbObj.getInt("version"))

        val entitiesArray = dbObj.getJSONArray("entities")
        val tableNames = buildSet {
            for (i in 0 until entitiesArray.length()) {
                add(entitiesArray.getJSONObject(i).getString("tableName"))
            }
        }

        val expectedTables = setOf(
            "accounts",
            "transactions",
            "postings",
            "tags",
            "transaction_tags",
            "attachments",
            "investment_assets",
            "market_quotes",
            "exchange_rates",
            "loan_plans",
            "loan_installments",
            "debt_profiles",
            "budgets",
            "recurring_rules",
            "templates",
            "ai_drafts",
        )
        assertEquals(expectedTables, tableNames)
    }
}
