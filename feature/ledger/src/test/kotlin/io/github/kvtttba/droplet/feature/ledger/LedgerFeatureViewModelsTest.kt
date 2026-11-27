package io.github.kvtttba.droplet.feature.ledger

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.dao.TagFilterMode
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLedgerRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.PrivacyAmountFormatter
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.SystemAccounts
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
    private lateinit var engine: LedgerEngine
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        engine = LedgerEngine(baseCurrency = Currency.CNY)
        repository = LocalLedgerRepository(
            database = database,
            transactionWriter = LedgerTransactionWriter(database),
            seeder = DefaultLedgerSeeder(database),
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

        val ledgerVm = LedgerViewModel(repository)
        ledgerVm.selectMonth(month)

        // 1. Create an expense via TransactionEditorViewModel
        val editorVm = TransactionEditorViewModel(repository, engine, SavedStateHandle())
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
        val stateAfterVoid = ledgerVm.uiState.awaitFirst { it.transactionCount == 0 }
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
        val stateAfterDelete = ledgerVm.uiState.awaitFirst { it.transactionCount == 0 }
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

        val ledgerVm = LedgerViewModel(repository)
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
        val editorVm = TransactionEditorViewModel(repository, engine, SavedStateHandle())
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

        val editorVm = TransactionEditorViewModel(repository, engine, SavedStateHandle())
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
        val ledgerVm = LedgerViewModel(repository)
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

    private suspend fun saveExpense(
        amount: String,
        categoryId: String,
        accountId: String,
        occurredAt: Long,
        tagIds: List<String>,
        payee: String,
    ) {
        val vm = TransactionEditorViewModel(repository, engine, SavedStateHandle())
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
