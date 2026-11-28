package io.github.kvtttba.droplet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.BudgetOverview
import io.github.kvtttba.droplet.core.database.repository.BudgetRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.SaveBudgetRequest
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgress
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.security.BudgetAndRecurringPreferencesRepository
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BudgetManagementUiState(
    val selectedPeriod: BudgetPeriod = BudgetPeriod.MONTHLY,
    val targetMonth: YearMonth = YearMonth.now(),
    val monthlyOverall: BudgetProgress? = null,
    val monthlyCategoryBudgets: List<BudgetProgress> = emptyList(),
    val yearlyOverall: BudgetProgress? = null,
    val yearlyCategoryBudgets: List<BudgetProgress> = emptyList(),
    val expenseCategories: List<Account> = emptyList(),
    val budgetAlertsEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val userMessage: String? = null,
) {
    val activeOverall: BudgetProgress?
        get() = when (selectedPeriod) {
            BudgetPeriod.MONTHLY -> monthlyOverall
            BudgetPeriod.YEARLY -> yearlyOverall
        }

    val activeCategoryBudgets: List<BudgetProgress>
        get() = when (selectedPeriod) {
            BudgetPeriod.MONTHLY -> monthlyCategoryBudgets
            BudgetPeriod.YEARLY -> yearlyCategoryBudgets
        }

    val totalConfiguredCount: Int
        get() = (if (monthlyOverall != null) 1 else 0) +
            monthlyCategoryBudgets.size +
            (if (yearlyOverall != null) 1 else 0) +
            yearlyCategoryBudgets.size
}

@HiltViewModel
class BudgetManagementViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val ledgerRepository: LedgerRepository,
    private val alertPreferencesRepository: BudgetAndRecurringPreferencesRepository,
) : ViewModel() {

    private val selectedPeriodFlow = MutableStateFlow(BudgetPeriod.MONTHLY)
    private val isSavingFlow = MutableStateFlow(false)
    private val userMessageFlow = MutableStateFlow<String?>(null)

    private val runtimeFlow = combine(
        selectedPeriodFlow,
        isSavingFlow,
        userMessageFlow,
    ) { period, saving, message ->
        Triple(period, saving, message)
    }

    val uiState: StateFlow<BudgetManagementUiState> = combine(
        budgetRepository.observeBudgetOverview(includeDisabled = true),
        ledgerRepository.observeCategories(AccountType.EXPENSE, includeArchived = false),
        alertPreferencesRepository.preferences,
        runtimeFlow,
    ) { overview: BudgetOverview, categories, alertPrefs, (period, saving, message) ->
        BudgetManagementUiState(
            selectedPeriod = period,
            targetMonth = overview.targetMonth,
            monthlyOverall = overview.monthlyOverall,
            monthlyCategoryBudgets = overview.monthlyCategoryBudgets,
            yearlyOverall = overview.yearlyOverall,
            yearlyCategoryBudgets = overview.yearlyCategoryBudgets,
            expenseCategories = categories,
            budgetAlertsEnabled = alertPrefs.budgetAlertsEnabled,
            isLoading = false,
            isSaving = saving,
            userMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BudgetManagementUiState(isLoading = true),
    )

    init {
        viewModelScope.launch {
            ledgerRepository.ensureSeeded()
        }
    }

    fun selectPeriod(period: BudgetPeriod) {
        selectedPeriodFlow.value = period
    }

    fun setBudgetAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            alertPreferencesRepository.setBudgetAlertsEnabled(enabled)
            userMessageFlow.value = if (enabled) "已开启预算预警通知" else "已关闭预算预警通知"
        }
    }

    fun saveBudget(
        existingId: String?,
        period: BudgetPeriod,
        categoryAccountId: String?,
        amountText: String,
        warnThresholdPct: Int,
        currency: Currency = Currency.DEFAULT,
        isEnabled: Boolean = true,
        onComplete: (String?) -> Unit = {},
    ) {
        if (isSavingFlow.value) return
        viewModelScope.launch {
            isSavingFlow.value = true
            val result = runCatching {
                val cleaned = amountText.trim()
                require(cleaned.isNotEmpty()) { "请输入预算限额" }
                val money = runCatching {
                    Money.fromDecimalString(cleaned, currency)
                }.getOrElse {
                    throw IllegalArgumentException("预算金额格式无效")
                }
                require(money.isPositive) { "预算限额必须大于 0" }
                val saved = budgetRepository.saveBudget(
                    SaveBudgetRequest(
                        id = existingId,
                        period = period,
                        categoryAccountId = categoryAccountId,
                        currency = currency,
                        limitAmountMinor = money.amountMinor,
                        warnThresholdPct = warnThresholdPct,
                        isEnabled = isEnabled,
                    ),
                )
                // Clear prior period alert stamps for this budget when user adjusts limit/threshold
                alertPreferencesRepository.clearBudgetAlertHistory(saved.id)
                saved
            }
            isSavingFlow.value = false
            result.fold(
                onSuccess = {
                    selectedPeriodFlow.value = period
                    userMessageFlow.value = if (existingId == null) "预算已创建" else "预算已更新"
                    onComplete(null)
                },
                onFailure = { err ->
                    val msg = err.message ?: "保存预算失败"
                    userMessageFlow.value = msg
                    onComplete(msg)
                },
            )
        }
    }

    fun setBudgetEnabled(budgetId: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                budgetRepository.setBudgetEnabled(budgetId, enabled)
            }.onSuccess {
                userMessageFlow.value = if (enabled) "预算已启用" else "预算已停用"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "操作失败"
            }
        }
    }

    fun deleteBudget(budgetId: String) {
        viewModelScope.launch {
            runCatching {
                budgetRepository.deleteBudget(budgetId)
                alertPreferencesRepository.clearBudgetAlertHistory(budgetId)
            }.onSuccess {
                userMessageFlow.value = "预算已删除"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "删除失败"
            }
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }
}
