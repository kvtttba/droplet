package io.github.kvtttba.droplet.feature.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.BatchSettleReimbursementsRequest
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.ReimbursementAndRefundRepository
import io.github.kvtttba.droplet.core.database.repository.SettleReimbursementRequest
import io.github.kvtttba.droplet.core.domain.debt.ReimbursementItemSummary
import io.github.kvtttba.droplet.core.domain.debt.ReimbursementOverviewSummary
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.network.NetworkClock
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReimbursementFilterTab(val label: String) {
    PENDING("待报销"),
    SETTLED("已结清"),
    ALL("全部"),
}

data class ReimbursementListUiState(
    val overview: ReimbursementOverviewSummary = ReimbursementOverviewSummary.empty(),
    val selectedTab: ReimbursementFilterTab = ReimbursementFilterTab.PENDING,
    val selectedAdvanceIds: Set<String> = emptySet(),
    val depositAccounts: List<Account> = emptyList(),
    val expenseCategories: List<Account> = emptyList(),
    val incomeCategories: List<Account> = emptyList(),
    val accountsById: Map<String, Account> = emptyMap(),
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val userMessage: String? = null,
) {
    val visibleItems: List<ReimbursementItemSummary>
        get() = when (selectedTab) {
            ReimbursementFilterTab.PENDING -> overview.pendingItems
            ReimbursementFilterTab.SETTLED -> overview.settledItems
            ReimbursementFilterTab.ALL -> overview.items
        }

    val selectedPendingItems: List<ReimbursementItemSummary>
        get() = overview.pendingItems.filter { it.advanceTransaction.id in selectedAdvanceIds }
}

private data class ReimbursementRuntimeState(
    val selectedTab: ReimbursementFilterTab = ReimbursementFilterTab.PENDING,
    val selectedAdvanceIds: Set<String> = emptySet(),
    val isSubmitting: Boolean = false,
    val userMessage: String? = null,
)

private data class ReimbursementCatalog(
    val operationalAccounts: List<Account>,
    val expenseCategories: List<Account>,
    val incomeCategories: List<Account>,
    val allAccountsById: Map<String, Account>,
)

@HiltViewModel
class ReimbursementListViewModel @Inject constructor(
    private val reimbursementRepository: ReimbursementAndRefundRepository,
    private val ledgerRepository: LedgerRepository,
    private val clock: NetworkClock,
) : ViewModel() {

    private val runtimeFlow = MutableStateFlow(ReimbursementRuntimeState())

    private val catalogFlow = combine(
        ledgerRepository.observeOperationalAccounts(includeArchived = false),
        ledgerRepository.observeCategories(AccountType.EXPENSE, includeArchived = false),
        ledgerRepository.observeCategories(AccountType.INCOME, includeArchived = false),
        ledgerRepository.observeAllAccounts(includeArchived = true, includeSystem = true),
    ) { operational, expenses, incomes, allAccounts ->
        ReimbursementCatalog(
            operationalAccounts = operational,
            expenseCategories = expenses,
            incomeCategories = incomes,
            allAccountsById = allAccounts.associateBy { it.id },
        )
    }

    val uiState: StateFlow<ReimbursementListUiState> = combine(
        reimbursementRepository.observeReimbursementOverview(),
        catalogFlow,
        runtimeFlow,
    ) { overview, catalog, runtime ->
        val validPendingIds = overview.pendingItems.map { it.advanceTransaction.id }.toSet()
        val depositAccounts = catalog.operationalAccounts.filter { acct ->
            acct.subType in AssetsViewModel.FUNDING_CASH_SUB_TYPES ||
                acct.subType == AccountSubType.CREDIT_CARD
        }
        val leafExpenses = catalog.expenseCategories.filter { it.parentId != null }
            .ifEmpty { catalog.expenseCategories }
        val leafIncomes = catalog.incomeCategories.filter { it.parentId != null }
            .ifEmpty { catalog.incomeCategories }

        ReimbursementListUiState(
            overview = overview,
            selectedTab = runtime.selectedTab,
            selectedAdvanceIds = runtime.selectedAdvanceIds.intersect(validPendingIds),
            depositAccounts = depositAccounts,
            expenseCategories = leafExpenses,
            incomeCategories = leafIncomes,
            accountsById = catalog.allAccountsById,
            isLoading = false,
            isSubmitting = runtime.isSubmitting,
            userMessage = runtime.userMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ReimbursementListUiState(),
    )

    fun selectTab(tab: ReimbursementFilterTab) {
        runtimeFlow.update { it.copy(selectedTab = tab) }
    }

    fun toggleSelectAdvance(advanceId: String) {
        runtimeFlow.update { current ->
            val next = current.selectedAdvanceIds.toMutableSet()
            if (!next.add(advanceId)) {
                next.remove(advanceId)
            }
            current.copy(selectedAdvanceIds = next)
        }
    }

    fun toggleSelectAllPending() {
        val pendingIds = uiState.value.overview.pendingItems.map { it.advanceTransaction.id }.toSet()
        runtimeFlow.update { current ->
            val allSelected = pendingIds.isNotEmpty() && pendingIds.all { it in current.selectedAdvanceIds }
            current.copy(
                selectedAdvanceIds = if (allSelected) emptySet() else pendingIds,
            )
        }
    }

    fun clearUserMessage() {
        runtimeFlow.update { it.copy(userMessage = null) }
    }

    /**
     * Settles a single reimbursable advance.
     * - When `receivedAmount < remainingPending` and `convertShortfallToExpense == true`,
     *   clears the entire `remainingPending` and books the difference into `shortfallExpenseAccountId`.
     * - When `receivedAmount < remainingPending` and `convertShortfallToExpense == false`,
     *   clears only `receivedAmount`, leaving the rest as `PARTIAL` pending.
     */
    fun settleSingle(
        item: ReimbursementItemSummary,
        depositAccountId: String?,
        receivedAmountText: String,
        convertShortfallToExpense: Boolean,
        shortfallExpenseAccountId: String?,
        surplusIncomeAccountId: String?,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        if (runtimeFlow.value.isSubmitting) return
        viewModelScope.launch {
            runtimeFlow.update { it.copy(isSubmitting = true) }
            val result = runCatching {
                val depositId = depositAccountId ?: throw IllegalArgumentException("请选择报销收款账户")
                val cleaned = receivedAmountText.trim()
                require(cleaned.isNotEmpty()) { "请输入实际报销到账金额" }
                val received = runCatching {
                    Money.fromDecimalString(cleaned, item.currency)
                }.getOrElse {
                    throw IllegalArgumentException("实际到账金额格式无效")
                }
                require(!received.isNegative) { "实际到账金额不能为负数" }

                val remaining = item.remainingPendingAmount
                val settledAmount = when {
                    received < remaining && !convertShortfallToExpense -> {
                        require(received.isPositive) { "部分报销到账金额必须大于 0" }
                        received
                    }
                    else -> remaining
                }

                val response = reimbursementRepository.settleReimbursement(
                    SettleReimbursementRequest(
                        advanceTransactionId = item.advanceTransaction.id,
                        depositAccountId = depositId,
                        settledReimbursementAmount = settledAmount,
                        receivedAmount = received,
                        shortfallExpenseAccountId = if (received < settledAmount) shortfallExpenseAccountId else null,
                        surplusIncomeAccountId = if (received > settledAmount) surplusIncomeAccountId else null,
                        occurredAt = clock.nowEpochMs(),
                        note = note.trim().takeIf { it.isNotEmpty() },
                    ),
                )

                when {
                    response.shortfallAmount.isPositive ->
                        "报销已结清：到账 $received，差额 ${response.shortfallAmount} 已转个人支出"
                    response.surplusAmount.isPositive ->
                        "报销已结清：到账 $received（含超额补贴 ${response.surplusAmount}）"
                    response.remainingPendingAfter.isPositive ->
                        "部分报销 $received 已入账，剩余待报销 ${response.remainingPendingAfter}"
                    else ->
                        "报销 $received 已全额冲抵入账"
                }
            }

            runtimeFlow.update { current ->
                current.copy(
                    isSubmitting = false,
                    selectedAdvanceIds = if (result.isSuccess) {
                        current.selectedAdvanceIds - item.advanceTransaction.id
                    } else {
                        current.selectedAdvanceIds
                    },
                    userMessage = result.fold(
                        onSuccess = { it },
                        onFailure = { err -> err.message ?: "报销冲抵失败" },
                    ),
                )
            }
            onComplete(result.exceptionOrNull()?.message)
        }
    }

    fun batchSettleSelected(
        depositAccountId: String?,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        if (runtimeFlow.value.isSubmitting) return
        val selected = uiState.value.selectedPendingItems
        if (selected.isEmpty()) {
            onComplete("请先勾选需要批量报销的流水")
            return
        }
        viewModelScope.launch {
            runtimeFlow.update { it.copy(isSubmitting = true) }
            val result = runCatching {
                val depositId = depositAccountId ?: throw IllegalArgumentException("请选择报销收款账户")
                val report = reimbursementRepository.batchSettleReimbursements(
                    BatchSettleReimbursementsRequest(
                        advanceTransactionIds = selected.map { it.advanceTransaction.id },
                        depositAccountId = depositId,
                        occurredAt = clock.nowEpochMs(),
                        note = note.trim().takeIf { it.isNotEmpty() },
                    ),
                )
                "已批量结清 ${report.settledCount} 笔待报销流水"
            }

            runtimeFlow.update { current ->
                current.copy(
                    isSubmitting = false,
                    selectedAdvanceIds = if (result.isSuccess) emptySet() else current.selectedAdvanceIds,
                    userMessage = result.fold(
                        onSuccess = { it },
                        onFailure = { err -> err.message ?: "批量报销失败" },
                    ),
                )
            }
            onComplete(result.exceptionOrNull()?.message)
        }
    }
}
