package io.github.kvtttba.droplet.feature.ledger

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.dao.TagFilterMode
import io.github.kvtttba.droplet.core.database.repository.BudgetRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LocalBudgetRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LocalReimbursementAndRefundRepository
import io.github.kvtttba.droplet.core.database.repository.LocalTemplateRepository
import io.github.kvtttba.droplet.core.database.repository.RecordRefundRequest
import io.github.kvtttba.droplet.core.database.repository.ReimbursementAndRefundRepository
import io.github.kvtttba.droplet.core.database.repository.SaveBudgetRequest
import io.github.kvtttba.droplet.core.database.repository.SaveTemplateRequest
import io.github.kvtttba.droplet.core.database.repository.SettleReimbursementRequest
import io.github.kvtttba.droplet.core.database.repository.TemplateRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.PrivacyAmountFormatter
import io.github.kvtttba.droplet.core.domain.automation.RecurringPayload
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.feature.ledger.editor.EditorTab
import io.github.kvtttba.droplet.feature.ledger.editor.PostingDirection
import io.github.kvtttba.droplet.feature.ledger.editor.TransactionEditorViewModel
import io.github.kvtttba.droplet.feature.ledger.manage.AccountManagementViewModel
import io.github.kvtttba.droplet.feature.ledger.manage.AccountSaveRequest
import io.github.kvtttba.droplet.feature.ledger.manage.CategorySaveRequest
import io.github.kvtttba.droplet.feature.ledger.manage.CategoryTagManagementViewModel
import io.github.kvtttba.droplet.feature.ledger.manage.TagSaveRequest
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LedgerFeatureViewModelsTest {

    private lateinit var database: DropletDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var reimbursementAndRefundRepository: ReimbursementAndRefundRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var templateRepository: TemplateRepository
    private lateinit var engine: LedgerEngine
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        engine = LedgerEngine(baseCurrency = Currency.CNY)
        val writer = LedgerTransactionWriter(database)
        val seeder = DefaultLedgerSeeder(database)
        repository = LocalLedgerRepository(
            database = database,
            transactionWriter = writer,
            seeder = seeder,
            ledgerEngine = engine,
        )
        reimbursementAndRefundRepository = LocalReimbursementAndRefundRepository(
            database = database,
            transactionWriter = writer,
            seeder = seeder,
            ledgerEngine = engine,
        )
        budgetRepository = LocalBudgetRepository(
            database = database,
            seeder = seeder,
        )
        templateRepository = LocalTemplateRepository(
            database = database,
            transactionWriter = writer,
            seeder = seeder,
            ledgerEngine = engine,
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun expenseEntryEditVoidRestoreAndDeleteUpdateSummaryInRealTime() = runBlocking {
        val month = YearMonth.of(2026, 11)
        val occurredAt = month.atDay(15).atTime(12, 30)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        repository.ensureSeeded(occurredAt)

        val ledgerVm = LedgerViewModel(repository, reimbursementAndRefundRepository, budgetRepository, templateRepository)
        ledgerVm.selectMonth(month)

        // 1. Create an expense via TransactionEditorViewModel
        val editorVm = TransactionEditorViewModel(repository, engine, templateRepository, SavedStateHandle())
        editorVm.uiState.awaitFirst { it.operationalAccounts.isNotEmpty() }

        editorVm.selectTab(EditorTab.EXPENSE)
        editorVm.onAmountChange("38.50")
        editorVm.selectCategory(DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH)
        editorVm.selectAccount(DefaultSeedCatalog.AccountIds.DEBIT_CARD)
        editorVm.onOccurredAtChange(occurredAt)
        editorVm.onToggleTag(DefaultSeedCatalog.TagIds.WORKDAY)
        editorVm.onPayeeChange("简餐馆")
        editorVm.onNoteChange("牛肉面")
        editorVm.save()

        editorVm.uiState.awaitFirst { it.savedSuccessfully }

        // 2. Verify LedgerViewModel reflects the new expense & summary
        val stateAfterCreate = ledgerVm.uiState.awaitFirst { it.transactionCount == 1 }
        assertEquals(Money.fromDecimalString("38.50", Currency.CNY), stateAfterCreate.monthlyExpense)
        assertEquals(Money.fromDecimalString("-38.50", Currency.CNY), stateAfterCreate.monthlyBalance)
        assertEquals(1, stateAfterCreate.dayGroups.size)
        val createdItem = stateAfterCreate.dayGroups.first().items.first()
        assertTrue(createdItem.title.contains("简餐馆"))
        assertEquals(1, createdItem.tags.size)

        // 3. Edit the existing transaction to 52.00
        val editVm = TransactionEditorViewModel(
            repository = repository,
            ledgerEngine = engine,
            templateRepository = templateRepository,
            savedStateHandle = SavedStateHandle(mapOf("transactionId" to createdItem.id)),
        )
        editVm.uiState.awaitFirst { it.amountText == "38.5" || it.amountText == "38.50" }
        editVm.onAmountChange("52.00")
        editVm.onPayeeChange("拉面馆")
        editVm.save()
        editVm.uiState.awaitFirst { it.savedSuccessfully }

        val stateAfterEdit = ledgerVm.uiState.awaitFirst { state ->
            state.monthlyExpense == Money.fromDecimalString("52.00", Currency.CNY) &&
                state.dayGroups.firstOrNull()?.items?.firstOrNull()?.title?.contains("拉面馆") == true
        }
        assertEquals(1, stateAfterEdit.transactionCount)
        assertTrue(stateAfterEdit.dayGroups.first().items.first().title.contains("拉面馆"))

        // 4. Void transaction -> excluded from active summary, visible when includeVoid is toggled
        ledgerVm.voidTransaction(createdItem.id)
        val stateAfterVoid = ledgerVm.uiState.awaitFirst {
            it.transactionCount == 0 && it.monthlyExpense == Money.zero(Currency.CNY)
        }
        assertEquals(Money.zero(Currency.CNY), stateAfterVoid.monthlyExpense)

        ledgerVm.onToggleIncludeVoid()
        val stateWithVoid = ledgerVm.uiState.awaitFirst { it.transactionCount == 1 }
        assertEquals(TransactionStatus.VOID, stateWithVoid.dayGroups.first().items.first().status)
        assertEquals(Money.zero(Currency.CNY), stateWithVoid.monthlyExpense)

        // 5. Restore -> back to POSTED and re-included in monthly expense
        ledgerVm.restoreTransaction(createdItem.id)
        val stateAfterRestore = ledgerVm.uiState.awaitFirst { state ->
            state.monthlyExpense == Money.fromDecimalString("52.00", Currency.CNY) &&
                state.dayGroups.firstOrNull()?.items?.firstOrNull()?.status == TransactionStatus.POSTED
        }
        assertEquals(TransactionStatus.POSTED, stateAfterRestore.dayGroups.first().items.first().status)

        // 6. Delete -> removed permanently
        ledgerVm.deleteTransaction(createdItem.id)
        val stateAfterDelete = ledgerVm.uiState.awaitFirst {
            it.transactionCount == 0 && it.monthlyExpense == Money.zero(Currency.CNY)
        }
        assertEquals(Money.zero(Currency.CNY), stateAfterDelete.monthlyExpense)
    }

    @Test
    fun multiTagAnyVsAllAndParentCategoryAndKeywordFiltersWorkAccurately() = runBlocking {
        val month = YearMonth.of(2026, 11)
        val day1 = month.atDay(10).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val day2 = month.atDay(11).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repository.ensureSeeded(day1)

        // Tx 1: Lunch with tags WORKDAY + BUSINESS_TRIP
        saveExpense(
            amount = "40.00",
            categoryId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
            accountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            occurredAt = day1,
            tagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY, DefaultSeedCatalog.TagIds.BUSINESS_TRIP),
            payee = "机场简餐",
        )
        // Tx 2: Taxi with tag WORKDAY only
        saveExpense(
            amount = "25.00",
            categoryId = DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TAXI,
            accountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
            occurredAt = day2,
            tagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY),
            payee = "网约车",
        )

        val ledgerVm = LedgerViewModel(repository, reimbursementAndRefundRepository, budgetRepository, templateRepository)
        ledgerVm.selectMonth(month)

        val initial = ledgerVm.uiState.awaitFirst { it.transactionCount == 2 }
        assertEquals(Money.fromDecimalString("65.00", Currency.CNY), initial.monthlyExpense)

        // Multi-tag ANY (union): WORKDAY OR BUSINESS_TRIP -> both transactions
        ledgerVm.onToggleTagFilter(DefaultSeedCatalog.TagIds.WORKDAY)
        ledgerVm.onToggleTagFilter(DefaultSeedCatalog.TagIds.BUSINESS_TRIP)
        ledgerVm.onTagFilterModeChange(TagFilterMode.ANY)
        val unionState = ledgerVm.uiState.awaitFirst {
            it.filterState.selectedTagIds.size == 2 && it.filterState.tagFilterMode == TagFilterMode.ANY
        }
        assertEquals(2, unionState.transactionCount)

        // Multi-tag ALL (intersection): WORKDAY AND BUSINESS_TRIP -> only Tx 1 (40.00)
        ledgerVm.onTagFilterModeChange(TagFilterMode.ALL)
        val interState = ledgerVm.uiState.awaitFirst {
            it.filterState.tagFilterMode == TagFilterMode.ALL && it.transactionCount == 1
        }
        assertEquals(Money.fromDecimalString("40.00", Currency.CNY), interState.monthlyExpense)

        // Reset and filter by Parent Category (EXP_FOOD matches EXP_FOOD_LUNCH)
        ledgerVm.onClearAllFilters()
        ledgerVm.onSelectCategoryFilter(DefaultSeedCatalog.AccountIds.EXP_FOOD)
        val catState = ledgerVm.uiState.awaitFirst {
            it.filterState.selectedCategoryId == DefaultSeedCatalog.AccountIds.EXP_FOOD && it.transactionCount == 1
        }
        assertEquals(Money.fromDecimalString("40.00", Currency.CNY), catState.monthlyExpense)

        // Filter by keyword "网约车"
        ledgerVm.onClearAllFilters()
        ledgerVm.onKeywordChange("网约车")
        val searchState = ledgerVm.uiState.awaitFirst {
            it.filterState.keyword == "网约车" && it.transactionCount == 1
        }
        assertEquals(Money.fromDecimalString("25.00", Currency.CNY), searchState.monthlyExpense)
    }

    @Test
    fun crossCurrencyTransferInfersImpliedRateAndInsertsTradingHedge() = runBlocking {
        val now = YearMonth.of(2026, 11).atDay(20).atTime(14, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repository.ensureSeeded(now)

        // Create a USD account first via AccountManagementViewModel with opening balance $500
        val accountVm = AccountManagementViewModel(repository)
        accountVm.saveAccount(
            AccountSaveRequest(
                name = "美元资金户",
                subType = AccountSubType.INVEST_CASH,
                currency = Currency.USD,
                icon = "trending_up",
                color = "#1565C0",
                includeInNetWorth = true,
                openingBalanceText = "500.00",
                openingRateText = "7.20",
            ),
        )

        val usdAccount = repository.observeOperationalAccounts().awaitFirst { list ->
            list.any { it.name == "美元资金户" }
        }.first { it.name == "美元资金户" }

        // Verify opening balance transaction balanced against Equity:OpeningBalance:USD
        val balancesAfterOpen = repository.observeAccountBalances().awaitFirst { list ->
            list.any { it.accountId == usdAccount.id }
        }
        val usdOpenBal = balancesAfterOpen.first { it.accountId == usdAccount.id }
        assertEquals(Money.fromDecimalString("500.00", Currency.USD).amountMinor, usdOpenBal.balanceMinor)
        val equityOpenBal = balancesAfterOpen.first {
            it.accountId == SystemAccounts.openingBalanceAccountId(Currency.USD)
        }
        assertEquals(Money.fromDecimalString("-500.00", Currency.USD).amountMinor, equityOpenBal.balanceMinor)

        // Cross-currency transfer: 7250 CNY -> 1000 USD (implied rate 1 USD = 7.25 CNY)
        val editorVm = TransactionEditorViewModel(repository, engine, templateRepository, SavedStateHandle())
        editorVm.uiState.awaitFirst { it.operationalAccounts.any { a -> a.id == usdAccount.id } }

        editorVm.selectTab(EditorTab.TRANSFER)
        editorVm.selectAccount(DefaultSeedCatalog.AccountIds.DEBIT_CARD)
        editorVm.selectTargetAccount(usdAccount.id)
        editorVm.onAmountChange("7250.00")
        editorVm.onCounterAmountChange("1000.00")
        editorVm.onOccurredAtChange(now)

        assertTrue(editorVm.uiState.value.isCrossCurrencyIntuitive)
        val rateHint = editorVm.uiState.value.impliedRateSummary
        assertNotNull(rateHint)
        assertTrue(rateHint!!.contains("7.25"))

        editorVm.save()
        editorVm.uiState.awaitFirst { it.savedSuccessfully }

        // Verify USD balance is now 500 + 1000 = 1500 USD and Trading accounts are balanced
        val finalBalances = repository.observeAccountBalances().awaitFirst { list ->
            list.firstOrNull { it.accountId == usdAccount.id }?.balanceMinor ==
                Money.fromDecimalString("1500.00", Currency.USD).amountMinor
        }
        val tradingCny = finalBalances.first { it.accountId == SystemAccounts.tradingAccountId(Currency.CNY) }
        val tradingUsd = finalBalances.first { it.accountId == SystemAccounts.tradingAccountId(Currency.USD) }
        assertEquals(Money.fromDecimalString("7250.00", Currency.CNY).amountMinor, tradingCny.balanceMinor)
        assertEquals(Money.fromDecimalString("-1000.00", Currency.USD).amountMinor, tradingUsd.balanceMinor)
    }

    @Test
    fun professionalSplitModeLiveImbalanceAutoBalanceAndAtomicSaveWork() = runBlocking {
        val month = YearMonth.of(2026, 11)
        val now = month.atDay(25).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repository.ensureSeeded(now)

        val editorVm = TransactionEditorViewModel(repository, engine, templateRepository, SavedStateHandle())
        editorVm.uiState.awaitFirst { it.allSelectableAccounts.isNotEmpty() }

        editorVm.toggleSplitMode()
        assertTrue(editorVm.uiState.value.isSplitMode)

        // Scenario: Salary gross 15000 = Debit Card 12000 + Tax/Fee Expense 3000
        val row0 = editorVm.uiState.value.splitRows[0].rowId
        val row1 = editorVm.uiState.value.splitRows[1].rowId

        editorVm.updateSplitRowAccount(row0, DefaultSeedCatalog.AccountIds.INC_WORK_SALARY)
        editorVm.updateSplitRowDirection(row0, PostingDirection.CREDIT)
        editorVm.updateSplitRowAmount(row0, "15000.00")

        editorVm.updateSplitRowAccount(row1, DefaultSeedCatalog.AccountIds.DEBIT_CARD)
        editorVm.updateSplitRowDirection(row1, PostingDirection.DEBIT)
        editorVm.updateSplitRowAmount(row1, "12000.00")

        // Unbalanced by -3000 CNY
        assertFalse(editorVm.uiState.value.isSplitReadyToSave)
        assertEquals(
            Money.fromDecimalString("-3000.00", Currency.CNY).amountMinor,
            editorVm.uiState.value.currencyImbalances.first().imbalanceMinor,
        )

        // One-tap auto balance creates a +3000 row, assign to Finance Fee
        editorVm.autoBalanceSplit()
        val row2 = editorVm.uiState.value.splitRows.last().rowId
        editorVm.updateSplitRowAccount(row2, DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE)
        editorVm.onOccurredAtChange(now)
        editorVm.onPayeeChange("11月工资单")

        assertTrue(editorVm.uiState.value.isSplitReadyToSave)
        assertEquals(0L, editorVm.uiState.value.currencyImbalances.first().imbalanceMinor)

        editorVm.save()
        editorVm.uiState.awaitFirst { it.savedSuccessfully }

        // Verify LedgerViewModel shows +15,000 income, -3,000 expense, +12,000 balance
        val ledgerVm = LedgerViewModel(repository, reimbursementAndRefundRepository, budgetRepository, templateRepository)
        ledgerVm.selectMonth(month)
        val summary = ledgerVm.uiState.awaitFirst { it.transactionCount == 1 }
        assertEquals(Money.fromDecimalString("15000.00", Currency.CNY), summary.monthlyIncome)
        assertEquals(Money.fromDecimalString("3000.00", Currency.CNY), summary.monthlyExpense)
        assertEquals(Money.fromDecimalString("12000.00", Currency.CNY), summary.monthlyBalance)

        // Verify privacy masking hides sensitive numbers
        assertEquals("¥ ****", PrivacyAmountFormatter.format(summary.monthlyBalance, hidden = true))
    }

    @Test
    fun categoryAndTagCrudValidatesDuplicatesAndSupportsArchiveRestore() = runBlocking {
        repository.ensureSeeded()
        val vm = CategoryTagManagementViewModel(repository)
        vm.uiState.awaitFirst { it.expenseGroups.isNotEmpty() && it.tags.isNotEmpty() }

        // 1. Add custom child category under 餐饮
        vm.saveCategory(
            CategorySaveRequest(
                type = AccountType.EXPENSE,
                parentId = DefaultSeedCatalog.AccountIds.EXP_FOOD,
                name = "精品咖啡",
                icon = "restaurant",
                color = "#EF6C00",
            ),
        )

        val updatedFoodGroup = vm.uiState.awaitFirst { state ->
            state.expenseGroups.any { g ->
                g.parent.id == DefaultSeedCatalog.AccountIds.EXP_FOOD &&
                    g.children.any { it.name == "精品咖啡" }
            }
        }
        val coffeeCat = updatedFoodGroup.expenseGroups
            .first { it.parent.id == DefaultSeedCatalog.AccountIds.EXP_FOOD }
            .children.first { it.name == "精品咖啡" }

        // Archive then restore
        vm.setCategoryArchived(coffeeCat.id, true)
        vm.uiState.awaitFirst { state ->
            state.expenseGroups
                .first { it.parent.id == DefaultSeedCatalog.AccountIds.EXP_FOOD }
                .children.none { it.id == coffeeCat.id }
        }
        vm.setCategoryArchived(coffeeCat.id, false)
        vm.uiState.awaitFirst { state ->
            state.expenseGroups
                .first { it.parent.id == DefaultSeedCatalog.AccountIds.EXP_FOOD }
                .children.any { it.id == coffeeCat.id }
        }

        // 2. Create a custom tag and reject duplicate name
        vm.saveTag(TagSaveRequest(name = "装修", icon = "home", color = "#00897B"))
        vm.uiState.awaitFirst { state -> state.tags.any { it.name == "装修" } }

        vm.saveTag(TagSaveRequest(name = "装修", icon = "home", color = "#00897B"))
        val dupState = vm.uiState.awaitFirst { state -> state.userMessage.orEmpty().contains("已存在") }
        assertTrue(dupState.userMessage.orEmpty().contains("已存在"))
    }

    @Test
    fun ledgerViewModel_recordsMultiStageRefundAndReflectsReimbursementProgress() = runBlocking {
        val month = YearMonth.of(2026, 11)
        val day = month.atDay(18).atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repository.ensureSeeded(day)

        val expense = engine.buildExpense(
            meta = TransactionMeta(occurredAt = day, payee = "网购退款单"),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_SHOPPING_DAILY,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("200.00", Currency.CNY),
        )
        val reimburseAdvance = engine.buildReimbursableAdvance(
            meta = TransactionMeta(occurredAt = day + 60_000L, payee = "差旅垫付单"),
            reimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
            paymentAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
            amount = Money.fromDecimalString("400.00", Currency.CNY),
        )
        repository.saveTransaction(expense)
        repository.saveTransaction(reimburseAdvance)

        val ledgerVm = LedgerViewModel(repository, reimbursementAndRefundRepository, budgetRepository, templateRepository)
        ledgerVm.selectMonth(month)

        val initial = ledgerVm.uiState.awaitFirst { it.transactionCount == 2 }
        assertEquals(Money.fromDecimalString("200.00", Currency.CNY), initial.monthlyExpense)

        // First partial refund: ¥60
        var firstErr: String? = "unset"
        ledgerVm.recordRefund(
            expenseTransactionId = expense.id,
            refundToAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            refundAmountText = "60.00",
            expenseReductionText = null,
            note = "第一次退款",
        ) { firstErr = it }

        val afterRefund1 = ledgerVm.uiState.awaitFirst { state ->
            val expItem = state.dayGroups.flatMap { it.items }.firstOrNull { it.id == expense.id }
            expItem?.refundSummary?.refundCount == 1
        }
        assertEquals(null, firstErr)
        assertEquals(Money.fromDecimalString("140.00", Currency.CNY), afterRefund1.monthlyExpense)
        val expAfter1 = afterRefund1.dayGroups.flatMap { it.items }.first { it.id == expense.id }
        assertEquals(Money.fromDecimalString("60.00", Currency.CNY), expAfter1.refundSummary?.cumulativeRefundedAmount)
        assertEquals(Money.fromDecimalString("140.00", Currency.CNY), expAfter1.refundSummary?.remainingRefundableAmount)

        // Second partial refund: ¥40 -> total refunded ¥100, net ¥100
        var secondErr: String? = "unset"
        ledgerVm.recordRefund(
            expenseTransactionId = expense.id,
            refundToAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            refundAmountText = "40.00",
            expenseReductionText = null,
            note = "第二次退款",
        ) { secondErr = it }

        val afterRefund2 = ledgerVm.uiState.awaitFirst { state ->
            val expItem = state.dayGroups.flatMap { it.items }.firstOrNull { it.id == expense.id }
            expItem?.refundSummary?.refundCount == 2
        }
        assertEquals(null, secondErr)
        assertEquals(Money.fromDecimalString("100.00", Currency.CNY), afterRefund2.monthlyExpense)
        val expAfter2 = afterRefund2.dayGroups.flatMap { it.items }.first { it.id == expense.id }
        assertEquals("已退 2 笔", expAfter2.statusBadge)
        assertEquals(Money.fromDecimalString("100.00", Currency.CNY), expAfter2.refundSummary?.netExpenseAmount)

        // Settle the reimbursement advance with ¥350 received + ¥50 shortfall expense
        reimbursementAndRefundRepository.settleReimbursement(
            SettleReimbursementRequest(
                advanceTransactionId = reimburseAdvance.id,
                depositAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                settledReimbursementAmount = Money.fromDecimalString("400.00", Currency.CNY),
                receivedAmount = Money.fromDecimalString("350.00", Currency.CNY),
                shortfallExpenseAccountId = DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TAXI,
                occurredAt = day + 120_000L,
            ),
        )

        val afterSettle = ledgerVm.uiState.awaitFirst { state ->
            val advItem = state.dayGroups.flatMap { it.items }.firstOrNull { it.id == reimburseAdvance.id }
            advItem?.reimbursementSummary?.derivedState == ReimburseState.SETTLED
        }
        val advItem = afterSettle.dayGroups.flatMap { it.items }.first { it.id == reimburseAdvance.id }
        assertEquals("已报销", advItem.statusBadge)
        assertEquals(Money.fromDecimalString("-400.00", Currency.CNY), advItem.primaryAmount)
        assertEquals(1, advItem.reimbursementSummary?.settlementCount)
        assertEquals(Money.fromDecimalString("150.00", Currency.CNY), afterSettle.monthlyExpense)
    }

    @Test
    fun ledgerBudgetProgressAndTemplatePrefillSyncWithRoom() = runBlocking {
        val month = YearMonth.now()
        val occurredAt = month.atDay(1).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repository.ensureSeeded(occurredAt)

        budgetRepository.saveBudget(
            SaveBudgetRequest(
                period = BudgetPeriod.MONTHLY,
                categoryAccountId = null,
                limitAmountMinor = Money.fromDecimalString("500.00").amountMinor,
                warnThresholdPct = 80,
            ),
        )
        val tpl = templateRepository.saveTemplate(
            SaveTemplateRequest(
                name = "通勤地铁",
                payload = RecurringPayload(
                    displayType = TransactionDisplayType.EXPENSE,
                    amountMinor = Money.fromDecimalString("6.00").amountMinor,
                    categoryAccountId = DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TRANSIT,
                    accountId = DefaultSeedCatalog.AccountIds.ALIPAY,
                    payee = "城市地铁",
                    tagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY),
                    note = "通勤",
                ),
            ),
        )

        // Prefill TransactionEditorViewModel from templateId and save -> increments usage & updates budget
        val prefillVm = TransactionEditorViewModel(
            repository = repository,
            ledgerEngine = engine,
            templateRepository = templateRepository,
            savedStateHandle = SavedStateHandle(mapOf(TransactionEditorViewModel.NAV_ARG_TEMPLATE_ID to tpl.id)),
        )
        val prefilled = prefillVm.uiState.awaitFirst { it.amountText == "6" && it.payee == "城市地铁" }
        assertEquals(DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TRANSIT, prefilled.selectedCategoryId)
        assertEquals(DefaultSeedCatalog.AccountIds.ALIPAY, prefilled.selectedAccountId)
        assertTrue(DefaultSeedCatalog.TagIds.WORKDAY in prefilled.selectedTagIds)

        prefillVm.onOccurredAtChange(occurredAt)
        prefillVm.save()
        prefillVm.uiState.awaitFirst { it.savedSuccessfully }

        val updatedTpl = templateRepository.getTemplateById(tpl.id)!!
        assertEquals(1, updatedTpl.template.useCount)

        val ledgerVm = LedgerViewModel(repository, reimbursementAndRefundRepository, budgetRepository, templateRepository)
        val state = ledgerVm.uiState.awaitFirst {
            it.monthlyOverallBudget?.spentAmount == Money.fromDecimalString("6.00") &&
                it.quickTemplates.size == 1
        }
        assertEquals(Money.fromDecimalString("500.00"), state.monthlyOverallBudget?.budget?.limitAmount)
    }

    private suspend fun saveExpense(
        amount: String,
        categoryId: String,
        accountId: String,
        occurredAt: Long,
        tagIds: List<String>,
        payee: String,
    ) {
        val vm = TransactionEditorViewModel(repository, engine, templateRepository, SavedStateHandle())
        vm.uiState.awaitFirst { it.operationalAccounts.isNotEmpty() }
        vm.selectTab(EditorTab.EXPENSE)
        vm.onAmountChange(amount)
        vm.selectCategory(categoryId)
        vm.selectAccount(accountId)
        vm.onOccurredAtChange(occurredAt)
        tagIds.forEach { vm.onToggleTag(it) }
        vm.onPayeeChange(payee)
        vm.save()
        vm.uiState.awaitFirst { it.savedSuccessfully }
    }

    private suspend fun <T> Flow<T>.awaitFirst(predicate: (T) -> Boolean): T =
        withTimeout(10_000L) {
            first(predicate)
        }
}
