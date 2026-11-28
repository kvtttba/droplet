package io.github.kvtttba.droplet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.SaveTemplateRequest
import io.github.kvtttba.droplet.core.database.repository.TemplateRepository
import io.github.kvtttba.droplet.core.database.repository.TransactionTemplateItem
import io.github.kvtttba.droplet.core.domain.automation.RecurringPayload
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TemplateManagementUiState(
    val templates: List<TransactionTemplateItem> = emptyList(),
    val operationalAccounts: List<Account> = emptyList(),
    val expenseCategories: List<Account> = emptyList(),
    val incomeCategories: List<Account> = emptyList(),
    val availableTags: List<Tag> = emptyList(),
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val userMessage: String? = null,
) {
    val totalUsageCount: Int
        get() = templates.sumOf { it.template.useCount }
}

data class TemplateFormInput(
    val id: String? = null,
    val name: String,
    val displayType: TransactionDisplayType,
    val amountText: String,
    val currency: Currency = Currency.CNY,
    val accountId: String?,
    val categoryAccountId: String?,
    val targetAccountId: String?,
    val payee: String = "",
    val note: String = "",
    val tagIds: List<String> = emptyList(),
    val icon: String? = null,
    val color: String? = null,
)

@HiltViewModel
class TemplateManagementViewModel @Inject constructor(
    private val templateRepository: TemplateRepository,
    private val ledgerRepository: LedgerRepository,
) : ViewModel() {

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

    val uiState: StateFlow<TemplateManagementUiState> = combine(
        templateRepository.observeTemplates(),
        catalogFlow,
        isSubmittingFlow,
        userMessageFlow,
    ) { templates, catalog, submitting, message ->
        TemplateManagementUiState(
            templates = templates,
            operationalAccounts = catalog.accounts,
            expenseCategories = catalog.expenseCategories,
            incomeCategories = catalog.incomeCategories,
            availableTags = catalog.tags,
            isLoading = false,
            isSubmitting = submitting,
            userMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TemplateManagementUiState(isLoading = true),
    )

    init {
        viewModelScope.launch {
            ledgerRepository.ensureSeeded()
        }
    }

    fun saveTemplate(
        input: TemplateFormInput,
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
                templateRepository.saveTemplate(
                    SaveTemplateRequest(
                        id = input.id,
                        name = input.name,
                        payload = payload,
                        icon = input.icon,
                        color = input.color,
                    ),
                )
            }
            isSubmittingFlow.value = false
            result.fold(
                onSuccess = {
                    userMessageFlow.value = if (input.id == null) "快捷模板已创建" else "快捷模板已更新"
                    onComplete(null)
                },
                onFailure = { err ->
                    val msg = err.message ?: "保存模板失败"
                    userMessageFlow.value = msg
                    onComplete(msg)
                },
            )
        }
    }

    fun postDirectly(templateId: String) {
        if (isSubmittingFlow.value) return
        viewModelScope.launch {
            isSubmittingFlow.value = true
            runCatching {
                val item = templateRepository.getTemplateById(templateId)
                val tx = templateRepository.postFromTemplate(templateId)
                (item?.template?.name ?: "快捷模板") to tx
            }.onSuccess { (name, _) ->
                userMessageFlow.value = "已通过「$name」一键入账"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "一键入账失败"
            }
            isSubmittingFlow.value = false
        }
    }

    fun deleteTemplate(templateId: String) {
        viewModelScope.launch {
            runCatching {
                templateRepository.deleteTemplate(templateId)
            }.onSuccess {
                userMessageFlow.value = "快捷模板已删除"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "删除模板失败"
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
