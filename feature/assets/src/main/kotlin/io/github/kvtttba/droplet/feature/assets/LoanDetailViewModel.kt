package io.github.kvtttba.droplet.feature.assets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.EarlyPayoffLoanRequest
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LoanRepository
import io.github.kvtttba.droplet.core.database.repository.RepayInstallmentRequest
import io.github.kvtttba.droplet.core.database.repository.UpdateLoanPlanParametersRequest
import io.github.kvtttba.droplet.core.domain.loan.EarlyPayoffQuote
import io.github.kvtttba.droplet.core.domain.loan.LoanPlanSummary
import io.github.kvtttba.droplet.core.domain.loan.LoanScheduleCalculator
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.InstallmentStatus
import io.github.kvtttba.droplet.core.model.LoanInstallment
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoanScheduleFilterTab(val label: String) {
    ALL("全部期次"),
    PENDING("待还"),
    PAID("已还"),
}

data class LoanDetailUiState(
    val planId: String = "",
    val summary: LoanPlanSummary? = null,
    val loanAccount: Account? = null,
    val paymentAccount: Account? = null,
    val interestExpenseAccount: Account? = null,
    val loanAccountBalance: Money? = null,
    val selectedTab: LoanScheduleFilterTab = LoanScheduleFilterTab.ALL,
    val paymentAccounts: List<Account> = emptyList(),
    val expenseCategories: List<Account> = emptyList(),
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val isDeleted: Boolean = false,
    val userMessage: String? = null,
) {
    val visibleInstallments: List<LoanInstallment>
        get() {
            val all = summary?.installments.orEmpty()
            return when (selectedTab) {
                LoanScheduleFilterTab.ALL -> all
                LoanScheduleFilterTab.PENDING -> all.filter { it.status == InstallmentStatus.PENDING }
                LoanScheduleFilterTab.PAID -> all.filter { it.status == InstallmentStatus.PAID }
            }
        }

    val earlyPayoffQuote: EarlyPayoffQuote?
        get() = summary?.let { s ->
            LoanScheduleCalculator.calculateEarlyPayoff(
                plan = s.plan,
                installments = s.installments,
            )
        }
}

private data class LoanDetailRuntimeState(
    val selectedTab: LoanScheduleFilterTab = LoanScheduleFilterTab.ALL,
    val isSubmitting: Boolean = false,
    val isDeleted: Boolean = false,
    val userMessage: String? = null,
)

@HiltViewModel
class LoanDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val loanRepository: LoanRepository,
    private val ledgerRepository: LedgerRepository,
    private val clock: NetworkClock,
) : ViewModel() {

    val planId: String = checkNotNull(savedStateHandle.get<String>(NAV_ARG_PLAN_ID)) {
        "Missing navigation argument '$NAV_ARG_PLAN_ID'"
    }

    private val runtimeFlow = MutableStateFlow(LoanDetailRuntimeState())

    private val accountsFlow = combine(
        ledgerRepository.observeOperationalAccounts(includeArchived = false),
        ledgerRepository.observeCategories(AccountType.EXPENSE, includeArchived = false),
    ) { operational, expenses ->
        operational to expenses
    }

    val uiState: StateFlow<LoanDetailUiState> = combine(
        loanRepository.observeLoanDetail(planId = planId),
        accountsFlow,
        runtimeFlow,
    ) { snapshot, (operationalAccounts, expenseCategories), runtime ->
        val plan = snapshot?.summary?.plan
        val matchingPayments = if (plan != null) {
            operationalAccounts
                .filter { acct ->
                    acct.id != plan.loanAccountId &&
                        acct.currency == plan.currency &&
                        (acct.subType in AssetsViewModel.FUNDING_CASH_SUB_TYPES ||
                            acct.subType == AccountSubType.CREDIT_CARD)
                }
                .sortedWith(
                    compareByDescending<Account> { it.id == plan.paymentAccountId }
                        .thenBy { it.sortOrder }
                        .thenBy { it.name },
                )
        } else {
            emptyList()
        }
        val leafExpenses = expenseCategories.filter { it.parentId != null }
            .ifEmpty { expenseCategories }

        LoanDetailUiState(
            planId = planId,
            summary = snapshot?.summary,
            loanAccount = snapshot?.loanAccount,
            paymentAccount = snapshot?.paymentAccount,
            interestExpenseAccount = snapshot?.interestExpenseAccount,
            loanAccountBalance = snapshot?.loanAccountBalance,
            selectedTab = runtime.selectedTab,
            paymentAccounts = matchingPayments,
            expenseCategories = leafExpenses,
            isLoading = false,
            isSubmitting = runtime.isSubmitting,
            isDeleted = runtime.isDeleted,
            userMessage = runtime.userMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LoanDetailUiState(planId = planId),
    )

    fun selectTab(tab: LoanScheduleFilterTab) {
        runtimeFlow.update { it.copy(selectedTab = tab) }
    }

    fun clearUserMessage() {
        runtimeFlow.update { it.copy(userMessage = null) }
    }

    fun toggleAutoPost(enabled: Boolean) {
        val current = uiState.value.summary ?: return
        if (current.plan.autoPost == enabled) return
        runMutation(onComplete = {}) {
            loanRepository.updateLoanPlanParameters(
                UpdateLoanPlanParametersRequest(
                    planId = planId,
                    autoPost = enabled,
                ),
            )
            if (enabled) "已开启到期自动生成还款分录" else "已关闭到期自动入账"
        }
    }

    fun repayInstallment(
        installmentId: String,
        paymentAccountIdOverride: String?,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        val summary = uiState.value.summary
        if (summary == null) {
            onComplete("贷款信息尚未就绪")
            return
        }
        runMutation(onComplete) {
            val target = summary.installments.firstOrNull { it.id == installmentId }
                ?: throw IllegalArgumentException("未找到目标期次")
            loanRepository.repayInstallment(
                RepayInstallmentRequest(
                    loanPlanId = planId,
                    installmentId = installmentId,
                    paymentAccountIdOverride = paymentAccountIdOverride,
                    occurredAt = clock.nowEpochMs(),
                    note = note.trim().takeIf { it.isNotEmpty() },
                ),
            )
            "第 ${target.periodNumber} 期还款已原子入账"
        }
    }

    fun earlyPayoff(
        paymentAccountIdOverride: String?,
        extraFeeText: String,
        note: String,
        archiveAfterPayoff: Boolean,
        onComplete: (String?) -> Unit,
    ) {
        val summary = uiState.value.summary
        if (summary == null) {
            onComplete("贷款信息尚未就绪")
            return
        }
        runMutation(onComplete) {
            val currency = summary.plan.currency
            val extraFee = parseOptionalNonNegativeMoney(extraFeeText, currency, "提前结清附加费用/利息")
            val result = loanRepository.earlyPayoffLoan(
                EarlyPayoffLoanRequest(
                    loanPlanId = planId,
                    paymentAccountIdOverride = paymentAccountIdOverride,
                    extraFeeOrInterest = extraFee,
                    occurredAt = clock.nowEpochMs(),
                    note = note.trim().takeIf { it.isNotEmpty() },
                    archiveAfterPayoff = archiveAfterPayoff,
                ),
            )
            "已提前结清剩余 ${result.settledInstallmentIds.size} 期并同步清零贷款负债"
        }
    }

    fun updateParameters(
        name: String,
        paymentAccountId: String?,
        interestExpenseAccountId: String?,
        autoPost: Boolean,
        remindDaysBeforeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        if (uiState.value.summary == null) {
            onComplete("贷款信息尚未就绪")
            return
        }
        runMutation(onComplete) {
            val trimmedName = name.trim()
            require(trimmedName.isNotEmpty()) { "贷款名称不能为空" }
            val trimmedRemind = remindDaysBeforeText.trim()
            val remindDays = if (trimmedRemind.isEmpty()) {
                null
            } else {
                val parsed = trimmedRemind.toIntOrNull()
                    ?: throw IllegalArgumentException("提前提醒天数需为 0–31 的整数")
                require(parsed in 0..31) { "提前提醒天数需在 0–31 之间" }
                parsed
            }

            loanRepository.updateLoanPlanParameters(
                UpdateLoanPlanParametersRequest(
                    planId = planId,
                    name = trimmedName,
                    paymentAccountId = paymentAccountId,
                    interestExpenseAccountId = interestExpenseAccountId,
                    autoPost = autoPost,
                    remindDaysBefore = remindDays,
                    clearRemindDaysBefore = trimmedRemind.isEmpty(),
                    note = note.trim().takeIf { it.isNotEmpty() },
                    clearNote = note.trim().isEmpty(),
                ),
            )
            "已更新分期贷款参数"
        }
    }

    fun setArchived(archived: Boolean, onComplete: (String?) -> Unit = {}) {
        runMutation(onComplete) {
            loanRepository.setLoanPlanArchived(
                planId = planId,
                archived = archived,
                nowEpochMs = clock.nowEpochMs(),
            )
            if (archived) "已归档该分期贷款" else "已恢复该分期贷款"
        }
    }

    fun deleteLoanPlan(onComplete: (String?) -> Unit) {
        if (runtimeFlow.value.isSubmitting) return
        viewModelScope.launch {
            runtimeFlow.update { it.copy(isSubmitting = true) }
            val result = loanRepository.deleteLoanPlanIfUnused(planId)
            runtimeFlow.update {
                it.copy(
                    isSubmitting = false,
                    isDeleted = result.isSuccess,
                    userMessage = result.fold(
                        onSuccess = { "已删除未入账的分期贷款" },
                        onFailure = { err -> err.message ?: "删除分期贷款失败" },
                    ),
                )
            }
            onComplete(result.exceptionOrNull()?.message)
        }
    }

    private fun runMutation(
        onComplete: (String?) -> Unit,
        block: suspend () -> String,
    ) {
        if (runtimeFlow.value.isSubmitting) return
        viewModelScope.launch {
            runtimeFlow.update { it.copy(isSubmitting = true) }
            val result = runCatching { block() }
            runtimeFlow.update {
                it.copy(
                    isSubmitting = false,
                    userMessage = result.fold(
                        onSuccess = { msg -> msg },
                        onFailure = { err -> err.message ?: "操作失败" },
                    ),
                )
            }
            onComplete(result.exceptionOrNull()?.message)
        }
    }

    companion object {
        const val NAV_ARG_PLAN_ID: String = "planId"

        private fun parseOptionalNonNegativeMoney(
            raw: String,
            currency: io.github.kvtttba.droplet.core.model.Currency,
            label: String,
        ): Money? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val bd = runCatching { BigDecimal(trimmed) }.getOrElse {
                throw IllegalArgumentException("${label}格式无效")
            }
            require(bd.compareTo(BigDecimal.ZERO) >= 0) { "${label}不能为负数" }
            if (bd.compareTo(BigDecimal.ZERO) == 0) return null
            return Money.fromBigDecimal(bd, currency)
        }
    }
}
