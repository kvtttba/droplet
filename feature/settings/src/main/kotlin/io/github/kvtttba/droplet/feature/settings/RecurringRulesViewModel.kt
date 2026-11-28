package io.github.kvtttba.droplet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.RecurringRuleItem
import io.github.kvtttba.droplet.core.database.repository.RecurringRuleRepository
import io.github.kvtttba.droplet.core.database.repository.SaveRecurringRuleRequest
import io.github.kvtttba.droplet.core.domain.automation.RecurringPayload
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.RecurringFrequency
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.security.BudgetAndRecurringPreferencesRepository
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecurringRulesUiState(
    val allRules: List<RecurringRuleItem> = emptyList(),
    val operationalAccounts: List<Account> = emptyList(),
    val expenseCategories: List<Account> = emptyList(),
    val incomeCategories: List<Account> = emptyList(),
    val availableTags: List<Tag> = emptyList(),
    val recurringRemindersEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val userMessage: String? = null,
) {
    val pendingConfirmationRules: List<RecurringRuleItem>
        get() = allRules.filter { it.isPendingConfirmation }

    val activeRuleCount: Int
        get() = allRules.count { !it.rule.isPaused && !it.isTerminated }

    val autoPostRuleCount: Int
        get() = allRules.count { !it.rule.isPaused && !it.isTerminated && it.rule.autoPost }
}

data class RecurringRuleFormInput(
    val id: String? = null,
    val name: String,
    val displayType: TransactionDisplayType,
    val amountText: String,
    val currency: Currency = Currency.CNY,
    val accountId: String?,
    val categoryAccountId: String?,
    val targetAccountId: String?,
    val frequency: RecurringFrequency,
    val intervalCount: Int = 1,
    val nextRunDateIso: String,
    val autoPost: Boolean,
    val payee: String = "",
    val note: String = "",
    val tagIds: List<String> = emptyList(),
)

@HiltViewModel
class RecurringRulesViewModel @Inject constructor(
    private val recurringRuleRepository: RecurringRuleRepository,
    private val ledgerRepository: LedgerRepository,
    private val alertPreferencesRepository: BudgetAndRecurringPreferencesRepository,
) : ViewModel() {

    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val isSubmittingFlow = MutableStateFlow(false)
    private val userMessageFlow = MutableStateFlow<String?>(null)

    private val catalogFlow = combine(
        ledgerRepository.observeOperationalAccounts(includeArchived = false),
        ledgerRepository.observeCategories(AccountType.EXPENSE, includeArchived = false),
        ledgerRepository.observeCategories(AccountType.INCOME, includeArchived = false),
        ledgerRepository.observeTags(includeArchived = false),
    ) { accounts, expCats, incCats, tags ->
        CatalogData(accounts, expCats, incCats, tags)
    }

    val uiState: StateFlow<RecurringRulesUiState> = combine(
        recurringRuleRepository.observeRulesWithStatus(),
        catalogFlow,
        alertPreferencesRepository.preferences,
        isSubmittingFlow,
        userMessageFlow,
    ) { rules, catalog, alertPrefs, submitting, message ->
        RecurringRulesUiState(
            allRules = rules,
            operationalAccounts = catalog.accounts,
            expenseCategories = catalog.expenseCategories,
            incomeCategories = catalog.incomeCategories,
            availableTags = catalog.tags,
            recurringRemindersEnabled = alertPrefs.recurringRemindersEnabled,
            isLoading = false,
            isSubmitting = submitting,
            userMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecurringRulesUiState(isLoading = true),
    )

    init {
        viewModelScope.launch {
            ledgerRepository.ensureSeeded()
        }
    }

    fun setRecurringRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            alertPreferencesRepository.setRecurringRemindersEnabled(enabled)
            userMessageFlow.value = if (enabled) "已开启周期到期本地提醒" else "已关闭周期到期本地提醒"
        }
    }

    fun saveRule(
        input: RecurringRuleFormInput,
        onComplete: (String?) -> Unit = {},
    ) {
        if (isSubmittingFlow.value) return
        viewModelScope.launch {
            isSubmittingFlow.value = true
            val result = runCatching {
                val accountId = input.accountId?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("请选择关联账户")
                val cleanedAmount = input.amountText.trim()
                require(cleanedAmount.isNotEmpty()) { "请输入金额" }
                val money = runCatching {
                    Money.fromDecimalString(cleanedAmount, input.currency)
                }.getOrElse {
                    throw IllegalArgumentException("金额格式无效")
                }
                require(money.isPositive) { "金额必须大于 0" }
                val nextRunDate = runCatching {
                    LocalDate.parse(input.nextRunDateIso.trim())
                }.getOrElse {
                    throw IllegalArgumentException("执行日期格式应为 YYYY-MM-DD")
                }
                val nextRunAt = nextRunDate.atTime(9, 0).atZone(zoneId).toInstant().toEpochMilli()

                val payload = RecurringPayload(
                    displayType = input.displayType,
                    amountMinor = money.amountMinor,
                    currency = input.currency,
                    categoryAccountId = if (input.displayType == TransactionDisplayType.TRANSFER) {
                        null
                    } else {
                        input.categoryAccountId
                    },
                    accountId = accountId,
                    targetAccountId = if (input.displayType == TransactionDisplayType.TRANSFER) {
                        input.targetAccountId
                    } else {
                        null
                    },
                    payee = input.payee.trim().takeIf { it.isNotEmpty() },
                    tagIds = input.tagIds,
                    note = input.note.trim().takeIf { it.isNotEmpty() },
                )
                recurringRuleRepository.saveRule(
                    SaveRecurringRuleRequest(
                        id = input.id,
                        name = input.name,
                        frequency = input.frequency,
                        intervalCount = input.intervalCount.coerceAtLeast(1),
                        nextRunAt = nextRunAt,
                        payload = payload,
                        autoPost = input.autoPost,
                    ),
                )
            }
            isSubmittingFlow.value = false
            result.fold(
                onSuccess = {
                    userMessageFlow.value = if (input.id == null) "周期规则已创建" else "周期规则已更新"
                    onComplete(null)
                },
                onFailure = { err ->
                    val msg = err.message ?: "保存周期规则失败"
                    userMessageFlow.value = msg
                    onComplete(msg)
                },
            )
        }
    }

    fun setPaused(ruleId: String, paused: Boolean) {
        viewModelScope.launch {
            runCatching {
                recurringRuleRepository.setPaused(ruleId, paused)
            }.onSuccess {
                userMessageFlow.value = if (paused) "已暂停该周期规则" else "已恢复该周期规则"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "切换状态失败"
            }
        }
    }

    fun confirmAndPostDue(ruleId: String) {
        if (isSubmittingFlow.value) return
        viewModelScope.launch {
            isSubmittingFlow.value = true
            runCatching {
                recurringRuleRepository.confirmAndPostDueRule(ruleId)
            }.onSuccess { res ->
                alertPreferencesRepository.clearRecurringReminderHistory(ruleId)
                userMessageFlow.value = "已确认入账「${res.rule.name}」并顺延至下期"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "确认入账失败"
            }
            isSubmittingFlow.value = false
        }
    }

    fun skipDueOccurrence(ruleId: String) {
        if (isSubmittingFlow.value) return
        viewModelScope.launch {
            isSubmittingFlow.value = true
            runCatching {
                recurringRuleRepository.skipCurrentDueOccurrence(ruleId)
            }.onSuccess { updated ->
                alertPreferencesRepository.clearRecurringReminderHistory(ruleId)
                userMessageFlow.value = "已跳过「${updated.name}」本期计划"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "跳过失败"
            }
            isSubmittingFlow.value = false
        }
    }

    fun executeNow(ruleId: String) {
        if (isSubmittingFlow.value) return
        viewModelScope.launch {
            isSubmittingFlow.value = true
            runCatching {
                recurringRuleRepository.executeRuleNow(ruleId)
            }.onSuccess { res ->
                userMessageFlow.value = "已立即入账「${res.rule.name}」"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "执行失败"
            }
            isSubmittingFlow.value = false
        }
    }

    fun terminateRule(ruleId: String) {
        viewModelScope.launch {
            runCatching {
                recurringRuleRepository.terminateRule(ruleId)
            }.onSuccess {
                userMessageFlow.value = "周期规则已终止"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "终止失败"
            }
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            runCatching {
                recurringRuleRepository.deleteRule(ruleId)
                alertPreferencesRepository.clearRecurringReminderHistory(ruleId)
            }.onSuccess {
                userMessageFlow.value = "周期规则已删除"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "删除失败"
            }
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }

    private data class CatalogData(
        val accounts: List<Account>,
        val expenseCategories: List<Account>,
        val incomeCategories: List<Account>,
        val tags: List<Tag>,
    )
}
