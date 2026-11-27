package io.github.kvtttba.droplet.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.dao.TagFilterMode
import io.github.kvtttba.droplet.core.database.dao.TransactionFilter
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.TransactionStatus
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val repository: LedgerRepository,
) : ViewModel() {

    private val baseCurrency: Currency = Currency.DEFAULT
    private val zoneId: ZoneId = ZoneId.systemDefault()

    private val selectedMonthFlow = MutableStateFlow(YearMonth.now())
    private val filterStateFlow = MutableStateFlow(LedgerFilterState())
    private val userMessageFlow = MutableStateFlow<String?>(null)

    private val queryCriteriaFlow = combine(
        selectedMonthFlow,
        filterStateFlow,
    ) { month, filterState ->
        month to filterState
    }

    private val ledgerSnapshotFlow = queryCriteriaFlow.flatMapLatest { (month, filterState) ->
        val txFilter = buildTransactionFilter(month, filterState)
        val transactionsFlow = repository.observeFilteredTransactions(txFilter)
        val summaryFlow = if (!filterState.hasExtraFilters) {
            val (start, end) = LedgerDateFormats.monthRangeEpochMs(month, zoneId)
            repository.observePeriodSummary(
                baseCurrency = baseCurrency,
                startInclusive = start,
                endExclusive = end,
                status = TransactionStatus.POSTED,
            )
        } else {
            repository.observeFilteredPeriodSummary(
                baseCurrency = baseCurrency,
                filter = txFilter.copy(statuses = setOf(TransactionStatus.POSTED)),
            )
        }
        combine(transactionsFlow, summaryFlow) { records, summary ->
            QuerySnapshot(
                month = month,
                filterState = filterState,
                records = records,
                summary = summary,
            )
        }
    }

    private val catalogFlow = combine(
        repository.observeAllAccounts(includeArchived = true, includeSystem = true),
        repository.observeTags(includeArchived = false),
    ) { accounts, tags ->
        accounts to tags
    }

    val uiState: StateFlow<LedgerUiState> = combine(
        ledgerSnapshotFlow,
        catalogFlow,
        userMessageFlow,
    ) { snapshot, (allAccounts, tags), message ->
        val month = snapshot.month
        val filterState = snapshot.filterState
        val records = snapshot.records
        val summary = snapshot.summary
        val accountsById = allAccounts.associateBy { it.id }
        val activeOperationalAccounts = allAccounts.filter {
            !it.isArchived && !it.isSystem && it.subType in LedgerRepository.OPERATIONAL_SUB_TYPES
        }
        val activeExpenseCategories = allAccounts.filter {
            !it.isArchived && !it.isSystem && it.type == AccountType.EXPENSE && it.subType == AccountSubType.CATEGORY
        }
        val activeIncomeCategories = allAccounts.filter {
            !it.isArchived && !it.isSystem && it.type == AccountType.INCOME && it.subType == AccountSubType.CATEGORY
        }

        LedgerUiState(
            selectedMonth = month,
            baseCurrency = baseCurrency,
            monthlyBalance = Money(summary.netSavingsBaseMinor, baseCurrency),
            monthlyIncome = Money(summary.netIncomeBaseMinor, baseCurrency),
            monthlyExpense = Money(summary.netExpenseBaseMinor, baseCurrency),
            transactionCount = records.size,
            filterState = filterState,
            dayGroups = LedgerItemMapper.groupByDay(
                records = records,
                accountsById = accountsById,
                baseCurrency = baseCurrency,
                zoneId = zoneId,
            ),
            availableAccounts = activeOperationalAccounts,
            expenseCategoryGroups = LedgerItemMapper.buildCategoryGroups(activeExpenseCategories),
            incomeCategoryGroups = LedgerItemMapper.buildCategoryGroups(activeIncomeCategories),
            availableTags = tags,
            isLoading = false,
            userMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LedgerUiState(
            selectedMonth = selectedMonthFlow.value,
            isLoading = true,
        ),
    )

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
        }
    }

    fun previousMonth() {
        selectedMonthFlow.update { it.minusMonths(1) }
    }

    fun nextMonth() {
        selectedMonthFlow.update { it.plusMonths(1) }
    }

    fun selectMonth(month: YearMonth) {
        selectedMonthFlow.value = month
    }

    fun resetToCurrentMonth() {
        selectedMonthFlow.value = YearMonth.now()
    }

    fun onKeywordChange(keyword: String) {
        filterStateFlow.update { it.copy(keyword = keyword) }
    }

    fun onToggleTagFilter(tagId: String) {
        filterStateFlow.update { current ->
            val updated = current.selectedTagIds.toMutableSet()
            if (!updated.add(tagId)) {
                updated.remove(tagId)
            }
            current.copy(selectedTagIds = updated)
        }
    }

    fun onTagFilterModeChange(mode: TagFilterMode) {
        filterStateFlow.update { it.copy(tagFilterMode = mode) }
    }

    fun onSelectAccountFilter(accountId: String?) {
        filterStateFlow.update { it.copy(selectedAccountId = accountId) }
    }

    fun onSelectCategoryFilter(categoryId: String?) {
        filterStateFlow.update { it.copy(selectedCategoryId = categoryId) }
    }

    fun onTogglePendingReimburseOnly() {
        filterStateFlow.update { it.copy(pendingReimburseOnly = !it.pendingReimburseOnly) }
    }

    fun onToggleIncludeVoid() {
        filterStateFlow.update { it.copy(includeVoid = !it.includeVoid) }
    }

    fun onClearAllFilters() {
        filterStateFlow.value = LedgerFilterState()
    }

    fun voidTransaction(transactionId: String) {
        viewModelScope.launch {
            runCatching {
                repository.voidTransaction(transactionId)
            }.onSuccess {
                userMessageFlow.value = "流水已作废，汇总已实时更新"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "作废失败"
            }
        }
    }

    fun restoreTransaction(transactionId: String) {
        viewModelScope.launch {
            runCatching {
                repository.restoreTransaction(transactionId)
            }.onSuccess {
                userMessageFlow.value = "流水已恢复入账"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "恢复失败"
            }
        }
    }

    fun deleteTransaction(transactionId: String) {
        viewModelScope.launch {
            runCatching {
                repository.deleteTransaction(transactionId)
            }.onSuccess {
                userMessageFlow.value = "流水已永久删除"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "删除失败"
            }
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }

    private fun buildTransactionFilter(
        month: YearMonth,
        state: LedgerFilterState,
    ): TransactionFilter {
        val (start, end) = LedgerDateFormats.monthRangeEpochMs(month, zoneId)
        val statuses = if (state.includeVoid) {
            setOf(TransactionStatus.POSTED, TransactionStatus.VOID)
        } else {
            setOf(TransactionStatus.POSTED)
        }
        val reimburseStates = if (state.pendingReimburseOnly) {
            setOf(ReimburseState.PENDING, ReimburseState.PARTIAL)
        } else {
            emptySet()
        }
        return TransactionFilter(
            startInclusive = start,
            endExclusive = end,
            statuses = statuses,
            accountIds = state.selectedAccountId?.let { setOf(it) } ?: emptySet(),
            categoryAccountIds = state.selectedCategoryId?.let { setOf(it) } ?: emptySet(),
            tagIds = state.selectedTagIds,
            tagFilterMode = state.tagFilterMode,
            keyword = state.keyword.trim().ifEmpty { null },
            reimburseStates = reimburseStates,
        )
    }

    private data class QuerySnapshot(
        val month: YearMonth,
        val filterState: LedgerFilterState,
        val records: List<io.github.kvtttba.droplet.core.database.repository.LedgerTransactionRecord>,
        val summary: io.github.kvtttba.droplet.core.database.dao.PeriodSummaryRow,
    )
}
