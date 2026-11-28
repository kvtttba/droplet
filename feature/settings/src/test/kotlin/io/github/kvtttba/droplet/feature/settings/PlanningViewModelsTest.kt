package io.github.kvtttba.droplet.feature.settings

import io.github.kvtttba.droplet.core.database.dao.AccountCurrencyBalanceRow
import io.github.kvtttba.droplet.core.database.dao.PeriodSummaryRow
import io.github.kvtttba.droplet.core.database.dao.TransactionFilter
import io.github.kvtttba.droplet.core.database.repository.AutoPostRecurringReport
import io.github.kvtttba.droplet.core.database.repository.BudgetOverview
import io.github.kvtttba.droplet.core.database.repository.BudgetRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerTransactionRecord
import io.github.kvtttba.droplet.core.database.repository.RecurringExecutionResult
import io.github.kvtttba.droplet.core.database.repository.RecurringRuleItem
import io.github.kvtttba.droplet.core.database.repository.RecurringRuleRepository
import io.github.kvtttba.droplet.core.database.repository.SaveBudgetRequest
import io.github.kvtttba.droplet.core.database.repository.SaveRecurringRuleRequest
import io.github.kvtttba.droplet.core.database.repository.SaveTemplateRequest
import io.github.kvtttba.droplet.core.database.repository.TemplateRepository
import io.github.kvtttba.droplet.core.database.repository.TransactionTemplateItem
import io.github.kvtttba.droplet.core.database.seed.SeedResult
import io.github.kvtttba.droplet.core.domain.automation.BudgetAlertLevel
import io.github.kvtttba.droplet.core.domain.automation.BudgetPeriodRange
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgress
import io.github.kvtttba.droplet.core.domain.automation.RecurringPayloadCodec
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Budget
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.RecurringFrequency
import io.github.kvtttba.droplet.core.model.RecurringRule
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.model.TransactionTemplate
import io.github.kvtttba.droplet.core.security.BudgetAndRecurringPreferences
import io.github.kvtttba.droplet.core.security.BudgetAndRecurringPreferencesRepository
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningViewModelsTest {

    private val testDispatcher = StandardTestDispatcher()

    private val debit = Account("acct:debit", "储蓄卡", AccountType.ASSET, AccountSubType.DEBIT_CARD)
    private val foodCat = Account("cat:food", "餐饮", AccountType.EXPENSE, AccountSubType.CATEGORY)
    private val transitCat = Account("cat:transit", "地铁公交", AccountType.EXPENSE, AccountSubType.CATEGORY)

    private inner class StubLedgerRepository : LedgerRepository {
        override suspend fun ensureSeeded(nowEpochMs: Long): SeedResult = SeedResult(0, 0)
        override fun observeAllAccounts(includeArchived: Boolean, includeSystem: Boolean): Flow<List<Account>> =
            flowOf(listOf(debit, foodCat, transitCat))
        override fun observeOperationalAccounts(includeArchived: Boolean): Flow<List<Account>> =
            flowOf(listOf(debit))
        override fun observeCategories(type: AccountType, includeArchived: Boolean): Flow<List<Account>> =
            flowOf(if (type == AccountType.EXPENSE) listOf(foodCat, transitCat) else emptyList())
        override fun observeAccountBalances(status: TransactionStatus): Flow<List<AccountCurrencyBalanceRow>> =
            flowOf(emptyList())
        override suspend fun getAccountById(accountId: String): Account? = null
        override suspend fun saveAccount(account: Account, openingBalance: Money?, openingExchangeRateOverride: ExchangeRateSnapshot?, nowEpochMs: Long) = Unit
        override suspend fun setAccountArchived(accountId: String, archived: Boolean, nowEpochMs: Long) = Unit
        override suspend fun deleteAccountIfUnused(accountId: String): Result<Unit> = Result.success(Unit)
        override fun observeTags(includeArchived: Boolean): Flow<List<Tag>> = flowOf(emptyList())
        override suspend fun saveTag(tag: Tag): Result<Unit> = Result.success(Unit)
        override suspend fun setTagArchived(tagId: String, archived: Boolean, nowEpochMs: Long) = Unit
        override suspend fun deleteTag(tagId: String) = Unit
        override fun observeFilteredTransactions(filter: TransactionFilter): Flow<List<LedgerTransactionRecord>> = flowOf(emptyList())
        override fun observeFilteredPeriodSummary(baseCurrency: Currency, filter: TransactionFilter): Flow<PeriodSummaryRow> = flowOf(PeriodSummaryRow(0, 0, 0))
        override fun observePeriodSummary(baseCurrency: Currency, startInclusive: Long?, endExclusive: Long?, status: TransactionStatus): Flow<PeriodSummaryRow> = flowOf(PeriodSummaryRow(0, 0, 0))
        override suspend fun getTransactionById(transactionId: String): Transaction? = null
        override suspend fun saveTransaction(transaction: Transaction, allowArchivedAccounts: Boolean) = Unit
        override suspend fun voidTransaction(transactionId: String, nowEpochMs: Long) = Unit
        override suspend fun restoreTransaction(transactionId: String, nowEpochMs: Long) = Unit
        override suspend fun deleteTransaction(transactionId: String) = Unit
        override fun observeExchangeRateTable(): Flow<ExchangeRateTable> = flowOf(ExchangeRateTable.empty())
        override suspend fun getExchangeRateTable(): ExchangeRateTable = ExchangeRateTable.empty()
        override suspend fun upsertExchangeRate(rate: CachedExchangeRate) = Unit
    }

    private class FakeAlertPrefsRepo : BudgetAndRecurringPreferencesRepository {
        val state = MutableStateFlow(BudgetAndRecurringPreferences())
        override val preferences: Flow<BudgetAndRecurringPreferences> = state
        override suspend fun currentPreferences(): BudgetAndRecurringPreferences = state.value
        override suspend fun setBudgetAlertsEnabled(enabled: Boolean) {
            state.value = state.value.copy(budgetAlertsEnabled = enabled)
        }
        override suspend fun setRecurringRemindersEnabled(enabled: Boolean) {
            state.value = state.value.copy(recurringRemindersEnabled = enabled)
        }
        override suspend fun markBudgetAlertsSent(alertKeys: Collection<String>) = Unit
        override suspend fun markRecurringRemindersSent(reminderKeys: Collection<String>) = Unit
        override suspend fun clearBudgetAlertHistory(budgetIdPrefix: String?) = Unit
        override suspend fun clearRecurringReminderHistory(ruleIdPrefix: String?) = Unit
    }

    private class FakeBudgetRepo : BudgetRepository {
        val overviewFlow = MutableStateFlow(BudgetOverview(targetMonth = YearMonth.of(2026, 11)))
        val savedRequests = mutableListOf<SaveBudgetRequest>()

        override fun observeBudgetOverview(targetMonth: YearMonth?, includeDisabled: Boolean): Flow<BudgetOverview> =
            overviewFlow

        override fun observeMonthlyOverallBudgetProgress(month: YearMonth, currency: Currency): Flow<BudgetProgress?> =
            flowOf(overviewFlow.value.monthlyOverall)

        override suspend fun getBudgetOverviewSnapshot(asOfEpochMs: Long?, onlyEnabled: Boolean): BudgetOverview =
            overviewFlow.value

        override suspend fun saveBudget(request: SaveBudgetRequest): Budget {
            savedRequests += request
            val b = Budget(
                id = request.id ?: "b-${savedRequests.size}",
                period = request.period,
                categoryAccountId = request.categoryAccountId,
                currency = request.currency,
                limitAmountMinor = request.limitAmountMinor,
                warnThresholdPct = request.warnThresholdPct,
                isEnabled = request.isEnabled,
            )
            val progress = BudgetProgress(
                budget = b,
                categoryName = if (b.categoryAccountId == null) null else "餐饮",
                categoryIcon = null,
                categoryColor = null,
                periodRange = BudgetPeriodRange(b.period, "2026-11", 0, 100, 30, 10, 20),
                spentAmount = Money.zero(b.currency),
                remainingAmount = b.limitAmount,
                usedPercentageDecimal = "0.0",
                usedPercentageInt = 0,
                progressFraction = 0f,
                dailyAvailableAmount = Money(b.limitAmountMinor / 20L, b.currency),
                alertLevel = BudgetAlertLevel.NORMAL,
            )
            overviewFlow.value = overviewFlow.value.copy(
                progresses = overviewFlow.value.progresses.filterNot { it.budget.id == b.id } + progress,
            )
            return b
        }

        override suspend fun setBudgetEnabled(budgetId: String, enabled: Boolean) = Unit
        override suspend fun deleteBudget(budgetId: String) {
            overviewFlow.value = overviewFlow.value.copy(
                progresses = overviewFlow.value.progresses.filterNot { it.budget.id == budgetId },
            )
        }
    }

    private class FakeRecurringRepo : RecurringRuleRepository {
        val rulesFlow = MutableStateFlow<List<RecurringRuleItem>>(emptyList())
        override fun observeRulesWithStatus(asOfEpochMs: Long?): Flow<List<RecurringRuleItem>> = rulesFlow
        override suspend fun getRulesWithStatusSnapshot(asOfEpochMs: Long?): List<RecurringRuleItem> = rulesFlow.value
        override suspend fun saveRule(request: SaveRecurringRuleRequest): RecurringRule {
            val rule = RecurringRule(
                id = request.id ?: "rec-${rulesFlow.value.size + 1}",
                name = request.name,
                frequency = request.frequency,
                intervalCount = request.intervalCount,
                nextRunAt = request.nextRunAt,
                templatePayloadJson = RecurringPayloadCodec.encode(request.payload),
                autoPost = request.autoPost,
                isPaused = request.isPaused,
            )
            rulesFlow.value = rulesFlow.value + RecurringRuleItem(
                rule = rule,
                payload = request.payload,
                accountName = "储蓄卡",
                categoryOrTargetName = "餐饮",
                frequencyLabel = "每月",
                isDue = false,
                isPendingConfirmation = false,
                isTerminated = false,
            )
            return rule
        }
        override suspend fun setPaused(ruleId: String, paused: Boolean) {
            rulesFlow.value = rulesFlow.value.map {
                if (it.rule.id == ruleId) it.copy(rule = it.rule.copy(isPaused = paused)) else it
            }
        }
        override suspend fun terminateRule(ruleId: String) = Unit
        override suspend fun deleteRule(ruleId: String) {
            rulesFlow.value = rulesFlow.value.filterNot { it.rule.id == ruleId }
        }
        override suspend fun confirmAndPostDueRule(ruleId: String, asOfEpochMs: Long?): RecurringExecutionResult = error("unused")
        override suspend fun skipCurrentDueOccurrence(ruleId: String, asOfEpochMs: Long?): RecurringRule = error("unused")
        override suspend fun executeRuleNow(ruleId: String, asOfEpochMs: Long?): RecurringExecutionResult = error("unused")
        override suspend fun processDueAutoPostRules(asOfEpochMs: Long?): AutoPostRecurringReport = AutoPostRecurringReport()
    }

    private class FakeTemplateRepo : TemplateRepository {
        val templatesFlow = MutableStateFlow<List<TransactionTemplateItem>>(emptyList())
        var postedCount = 0

        override fun observeTemplates(): Flow<List<TransactionTemplateItem>> = templatesFlow
        override suspend fun getTemplateById(templateId: String): TransactionTemplateItem? =
            templatesFlow.value.firstOrNull { it.template.id == templateId }
        override suspend fun saveTemplate(request: SaveTemplateRequest): TransactionTemplate {
            val tpl = TransactionTemplate(
                id = request.id ?: "tpl-${templatesFlow.value.size + 1}",
                name = request.name,
                displayType = request.payload.displayType,
                templatePayloadJson = RecurringPayloadCodec.encode(request.payload),
            )
            templatesFlow.value = templatesFlow.value + TransactionTemplateItem(
                template = tpl,
                payload = request.payload,
                accountName = "储蓄卡",
                categoryOrTargetName = "地铁公交",
                resolvedIcon = "directions_subway",
                resolvedColor = "#1976D2",
            )
            return tpl
        }
        override suspend fun recordTemplateUsage(templateId: String, usedAtEpochMs: Long?) = Unit
        override suspend fun updateSortOrder(templateId: String, sortOrder: Int) = Unit
        override suspend fun deleteTemplate(templateId: String) {
            templatesFlow.value = templatesFlow.value.filterNot { it.template.id == templateId }
        }
        override suspend fun postFromTemplate(templateId: String, occurredAtEpochMs: Long?): Transaction {
            postedCount++
            return Transaction(
                id = "tx-$postedCount",
                occurredAt = 1000L,
                displayType = TransactionDisplayType.EXPENSE,
                postings = emptyList(),
            )
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun budgetManagementViewModel_savesBudgetAndTogglesAlerts() = runTest {
        val budgetRepo = FakeBudgetRepo()
        val alertPrefs = FakeAlertPrefsRepo()
        val vm = BudgetManagementViewModel(budgetRepo, StubLedgerRepository(), alertPrefs)
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        var error: String? = "unset"
        vm.saveBudget(
            existingId = null,
            period = BudgetPeriod.MONTHLY,
            categoryAccountId = null,
            amountText = "5000.00",
            warnThresholdPct = 80,
        ) { error = it }
        advanceUntilIdle()

        assertNull(error)
        assertNotNull(vm.uiState.value.monthlyOverall)
        assertEquals(Money.fromDecimalString("5000.00"), vm.uiState.value.monthlyOverall!!.budget.limitAmount)

        vm.setBudgetAlertsEnabled(false)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.budgetAlertsEnabled)

        collectJob.cancel()
    }

    @Test
    fun recurringAndTemplateViewModels_createPauseAndOneTapPost() = runTest {
        val recurringRepo = FakeRecurringRepo()
        val templateRepo = FakeTemplateRepo()
        val alertPrefs = FakeAlertPrefsRepo()

        val recurringVm = RecurringRulesViewModel(recurringRepo, StubLedgerRepository(), alertPrefs)
        val templateVm = TemplateManagementViewModel(templateRepo, StubLedgerRepository())
        val j1 = launch { recurringVm.uiState.collect {} }
        val j2 = launch { templateVm.uiState.collect {} }
        advanceUntilIdle()

        var recErr: String? = "unset"
        recurringVm.saveRule(
            RecurringRuleFormInput(
                name = "每月房租",
                displayType = TransactionDisplayType.EXPENSE,
                amountText = "3500",
                accountId = debit.id,
                categoryAccountId = foodCat.id,
                targetAccountId = null,
                frequency = RecurringFrequency.MONTHLY,
                nextRunDateIso = "2026-12-01",
                autoPost = true,
            ),
        ) { recErr = it }
        advanceUntilIdle()
        assertNull(recErr)
        assertEquals(1, recurringVm.uiState.value.allRules.size)

        val ruleId = recurringVm.uiState.value.allRules.single().rule.id
        recurringVm.setPaused(ruleId, true)
        advanceUntilIdle()
        assertTrue(recurringVm.uiState.value.allRules.single().rule.isPaused)

        var tplErr: String? = "unset"
        templateVm.saveTemplate(
            TemplateFormInput(
                name = "通勤地铁",
                displayType = TransactionDisplayType.EXPENSE,
                amountText = "6.00",
                accountId = debit.id,
                categoryAccountId = transitCat.id,
                targetAccountId = null,
            ),
        ) { tplErr = it }
        advanceUntilIdle()
        assertNull(tplErr)
        assertEquals(1, templateVm.uiState.value.templates.size)

        templateVm.postDirectly(templateVm.uiState.value.templates.single().template.id)
        advanceUntilIdle()
        assertEquals(1, templateRepo.postedCount)

        j1.cancel()
        j2.cancel()
    }
}
