package io.github.kvtttba.droplet.feature.ledger.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.feature.ledger.editor.TransactionEditorViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountWithBalance(
    val account: Account,
    val balance: Money,
    val transactionCount: Int,
)

data class AccountSectionGroup(
    val title: String,
    val accounts: List<AccountWithBalance>,
)

data class AccountManagementUiState(
    val activeGroups: List<AccountSectionGroup> = emptyList(),
    val archivedAccounts: List<AccountWithBalance> = emptyList(),
    val showArchived: Boolean = false,
    val totalActiveCount: Int = 0,
    val userMessage: String? = null,
)

data class AccountSaveRequest(
    val existingAccount: Account? = null,
    val name: String,
    val subType: AccountSubType,
    val currency: Currency,
    val icon: String,
    val color: String,
    val includeInNetWorth: Boolean,
    val openingBalanceText: String = "",
    val openingRateText: String = "",
    val creditLimitText: String = "",
    val billingDayText: String = "",
    val dueDayText: String = "",
    val remindDaysBeforeText: String = "",
)

@HiltViewModel
class AccountManagementViewModel @Inject constructor(
    private val repository: LedgerRepository,
) : ViewModel() {

    private val showArchivedFlow = MutableStateFlow(false)
    private val userMessageFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AccountManagementUiState> = combine(
        repository.observeOperationalAccounts(includeArchived = true),
        repository.observeAccountBalances(),
        showArchivedFlow,
        userMessageFlow,
    ) { accounts, balances, showArchived, message ->
        val balanceByAccount = balances.associateBy { it.accountId }
        val enriched = accounts.map { account ->
            val row = balanceByAccount[account.id]
            val money = if (row != null && row.currency == account.currency) {
                Money(row.balanceMinor, account.currency)
            } else {
                Money.zero(account.currency)
            }
            AccountWithBalance(
                account = account,
                balance = money,
                transactionCount = row?.transactionCount ?: 0,
            )
        }
        val active = enriched.filterNot { it.account.isArchived }
        val archived = enriched.filter { it.account.isArchived }

        val daily = active.filter {
            it.account.subType in setOf(
                AccountSubType.CASH,
                AccountSubType.DEBIT_CARD,
                AccountSubType.STORED_VALUE,
            )
        }
        val credit = active.filter { it.account.subType == AccountSubType.CREDIT_CARD }
        val other = active.filterNot { item -> item in daily || item in credit }

        val groups = buildList {
            if (daily.isNotEmpty()) add(AccountSectionGroup("日常资金账户", daily))
            if (credit.isNotEmpty()) add(AccountSectionGroup("信用账户", credit))
            if (other.isNotEmpty()) add(AccountSectionGroup("投资、贷款与往来账户", other))
        }

        AccountManagementUiState(
            activeGroups = groups,
            archivedAccounts = archived,
            showArchived = showArchived,
            totalActiveCount = active.size,
            userMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AccountManagementUiState(),
    )

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
        }
    }

    fun toggleShowArchived() {
        showArchivedFlow.update { !it }
    }

    fun saveAccount(
        request: AccountSaveRequest,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val trimmedName = request.name.trim()
                require(trimmedName.isNotEmpty()) { "账户名称不能为空" }
                val now = System.currentTimeMillis()
                val existing = request.existingAccount
                val accountType = accountTypeForSubType(request.subType)

                val isCredit = request.subType == AccountSubType.CREDIT_CARD
                val creditLimitMinor = if (isCredit && request.creditLimitText.isNotBlank()) {
                    val m = TransactionEditorViewModel.parsePositiveMoneyOrNull(
                        request.creditLimitText,
                        request.currency,
                    ) ?: error("信用额度格式无效")
                    m.amountMinor
                } else {
                    null
                }
                val billingDay = if (isCredit && request.billingDayText.isNotBlank()) {
                    val d = request.billingDayText.trim().toIntOrNull()
                        ?: error("账单日必须为 1–31 的整数")
                    require(d in 1..31) { "账单日必须在 1–31 之间" }
                    d
                } else {
                    null
                }
                val dueDay = if (isCredit && request.dueDayText.isNotBlank()) {
                    val d = request.dueDayText.trim().toIntOrNull()
                        ?: error("还款日必须为 1–31 的整数")
                    require(d in 1..31) { "还款日必须在 1–31 之间" }
                    d
                } else {
                    null
                }
                val remindDays = if (isCredit && request.remindDaysBeforeText.isNotBlank()) {
                    val d = request.remindDaysBeforeText.trim().toIntOrNull()
                        ?: error("提前提醒天数必须为 0–31 的整数")
                    require(d in 0..31) { "提前提醒天数必须在 0–31 之间" }
                    d
                } else {
                    null
                }

                val openingBalance = if (existing == null && request.openingBalanceText.isNotBlank()) {
                    TransactionEditorViewModel.parsePositiveMoneyOrNull(
                        request.openingBalanceText,
                        request.currency,
                    ) ?: error("期初余额必须为大于 0 的有效金额")
                } else {
                    null
                }

                val openingRateOverride = if (
                    openingBalance != null &&
                    request.currency != Currency.DEFAULT &&
                    request.openingRateText.isNotBlank()
                ) {
                    ExchangeRateSnapshot.of(
                        fromCurrency = request.currency,
                        toCurrency = Currency.DEFAULT,
                        rateDecimal = request.openingRateText.trim(),
                        source = ExchangeRateSource.USER_OVERRIDE,
                        capturedAt = now,
                    )
                } else {
                    null
                }

                val account = Account(
                    id = existing?.id ?: "acct:${UUID.randomUUID()}",
                    name = trimmedName,
                    type = accountType,
                    subType = request.subType,
                    currency = existing?.currency ?: request.currency,
                    icon = request.icon,
                    color = request.color,
                    includeInNetWorth = request.includeInNetWorth,
                    isArchived = existing?.isArchived ?: false,
                    isSystem = false,
                    creditLimitMinor = creditLimitMinor,
                    billingDay = billingDay,
                    dueDay = dueDay,
                    remindDaysBefore = remindDays,
                    sortOrder = existing?.sortOrder ?: 100,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )

                repository.saveAccount(
                    account = account,
                    openingBalance = openingBalance,
                    openingExchangeRateOverride = openingRateOverride,
                    nowEpochMs = now,
                )
            }

            result.onSuccess {
                userMessageFlow.value = if (request.existingAccount == null) {
                    if (request.openingBalanceText.isNotBlank()) {
                        "账户已创建并自动生成期初余额复式分录"
                    } else {
                        "账户「${request.name.trim()}」已创建"
                    }
                } else {
                    "账户「${request.name.trim()}」已更新"
                }
                onComplete(true)
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "保存账户失败"
                onComplete(false)
            }
        }
    }

    fun setArchived(accountId: String, archived: Boolean) {
        viewModelScope.launch {
            runCatching {
                repository.setAccountArchived(accountId, archived)
            }.onSuccess {
                userMessageFlow.value = if (archived) "账户已归档" else "账户已恢复"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "操作失败"
            }
        }
    }

    fun deleteAccountIfUnused(accountId: String) {
        viewModelScope.launch {
            repository.deleteAccountIfUnused(accountId)
                .onSuccess { userMessageFlow.value = "账户已删除" }
                .onFailure { err -> userMessageFlow.value = err.message ?: "删除失败" }
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }

    companion object {
        val creatableSubTypes: List<Pair<AccountSubType, String>> = listOf(
            AccountSubType.DEBIT_CARD to "储蓄卡/活期",
            AccountSubType.CASH to "现金",
            AccountSubType.CREDIT_CARD to "信用账户",
            AccountSubType.STORED_VALUE to "储值卡",
            AccountSubType.INVEST_CASH to "投资资金户",
            AccountSubType.LOAN to "分期贷款",
            AccountSubType.RECEIVABLE to "应收/借出",
            AccountSubType.PAYABLE to "应付/借入",
        )

        fun subTypeLabel(subType: AccountSubType): String = when (subType) {
            AccountSubType.CASH -> "现金"
            AccountSubType.DEBIT_CARD -> "储蓄/活期"
            AccountSubType.CREDIT_CARD -> "信用账户"
            AccountSubType.STORED_VALUE -> "储值账户"
            AccountSubType.INVEST_CASH -> "投资资金"
            AccountSubType.INVEST_HOLDING -> "投资持仓"
            AccountSubType.LOAN -> "分期贷款"
            AccountSubType.RECEIVABLE -> "应收借出"
            AccountSubType.PAYABLE -> "应付借入"
            AccountSubType.REIMBURSEMENT -> "待报销"
            AccountSubType.CATEGORY -> "分类"
            AccountSubType.TRADING -> "币种对冲"
            AccountSubType.OPENING -> "期初权益"
        }

        fun accountTypeForSubType(subType: AccountSubType): AccountType =
            when (subType) {
                AccountSubType.CREDIT_CARD,
                AccountSubType.LOAN,
                AccountSubType.PAYABLE,
                -> AccountType.LIABILITY
                else -> AccountType.ASSET
            }
    }
}
