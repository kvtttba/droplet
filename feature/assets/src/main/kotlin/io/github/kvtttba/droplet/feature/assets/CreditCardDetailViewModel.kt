package io.github.kvtttba.droplet.feature.assets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.CreditCardRepaymentRequest
import io.github.kvtttba.droplet.core.database.repository.CreditCardRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerTransactionRecord
import io.github.kvtttba.droplet.core.database.repository.UpdateCreditCardParametersRequest
import io.github.kvtttba.droplet.core.domain.credit.CreditCardRepaymentHelper
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementCalculator
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementSummary
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferences
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferencesRepository
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CreditCardStatementTab(val label: String) {
    BILLED("已出账单"),
    UNBILLED("未出账单"),
    ALL("全部流水"),
}

data class CreditCardTransactionItem(
    val id: String,
    val occurredAt: Long,
    val dateTimeLabel: String,
    val displayType: TransactionDisplayType,
    val title: String,
    val subtitle: String,
    val iconKey: String,
    val colorHex: String,
    val deltaAmount: Money,
    val isRepaymentOrRefund: Boolean,
    val tags: List<Tag> = emptyList(),
    val note: String? = null,
)

data class CreditCardDetailUiState(
    val accountId: String = "",
    val summary: CreditCardStatementSummary? = null,
    val selectedTab: CreditCardStatementTab = CreditCardStatementTab.BILLED,
    val billedTransactions: List<CreditCardTransactionItem> = emptyList(),
    val unbilledTransactions: List<CreditCardTransactionItem> = emptyList(),
    val allTransactions: List<CreditCardTransactionItem> = emptyList(),
    val paymentAccounts: List<Account> = emptyList(),
    val exchangeRateTable: ExchangeRateTable = ExchangeRateTable.empty(),
    val remindersEnabled: Boolean = true,
    val globalDefaultRemindDaysBefore: Int = CreditCardReminderPreferences.DEFAULT_REMIND_DAYS_BEFORE,
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val userMessage: String? = null,
) {
    val activeTransactions: List<CreditCardTransactionItem>
        get() = when (selectedTab) {
            CreditCardStatementTab.BILLED -> billedTransactions
            CreditCardStatementTab.UNBILLED -> unbilledTransactions
            CreditCardStatementTab.ALL -> allTransactions
        }
}

private data class CreditCardRuntimeState(
    val selectedTab: CreditCardStatementTab? = null,
    val isSubmitting: Boolean = false,
    val userMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CreditCardDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val creditCardRepository: CreditCardRepository,
    private val ledgerRepository: LedgerRepository,
    private val reminderPreferencesRepository: CreditCardReminderPreferencesRepository,
    private val clock: NetworkClock,
) : ViewModel() {

    val accountId: String = checkNotNull(savedStateHandle.get<String>(NAV_ARG_ACCOUNT_ID)) {
        "Missing navigation argument '$NAV_ARG_ACCOUNT_ID'"
    }

    private val runtimeFlow = MutableStateFlow(CreditCardRuntimeState())

    private val detailSnapshotFlow = reminderPreferencesRepository.preferences
        .map { it.defaultRemindDaysBefore }
        .distinctUntilChanged()
        .flatMapLatest { defaultDays ->
            creditCardRepository.observeCreditCardDetail(
                accountId = accountId,
                defaultRemindDaysBefore = defaultDays,
            )
        }

    private val ledgerMetaFlow = combine(
        ledgerRepository.observeAllAccounts(includeArchived = true, includeSystem = true),
        ledgerRepository.observeExchangeRateTable(),
    ) { accounts, rates -> accounts to rates }

    val uiState: StateFlow<CreditCardDetailUiState> = combine(
        detailSnapshotFlow,
        ledgerMetaFlow,
        reminderPreferencesRepository.preferences,
        runtimeFlow,
    ) { snapshot, (allAccounts, rates), reminderPrefs, runtime ->
        val accountsById = allAccounts.associateBy { it.id }
        val card = snapshot?.summary?.account
        val paymentAccounts = if (card != null) {
            allAccounts
                .filter { acct ->
                    !acct.isArchived &&
                        !acct.isSystem &&
                        acct.id != card.id &&
                        acct.type == AccountType.ASSET &&
                        acct.subType in AssetsViewModel.FUNDING_CASH_SUB_TYPES
                }
                .sortedWith(
                    compareByDescending<Account> { it.currency == card.currency }
                        .thenBy { it.sortOrder }
                        .thenBy { it.name },
                )
        } else {
            emptyList()
        }

        val billedItems = if (snapshot != null) {
            snapshot.billedTransactions.map { mapCardTransaction(it, snapshot.summary.account, accountsById) }
        } else {
            emptyList()
        }
        val unbilledItems = if (snapshot != null) {
            snapshot.unbilledTransactions.map { mapCardTransaction(it, snapshot.summary.account, accountsById) }
        } else {
            emptyList()
        }
        val allItems = if (snapshot != null) {
            snapshot.allTransactions.map { mapCardTransaction(it, snapshot.summary.account, accountsById) }
        } else {
            emptyList()
        }

        val defaultTab = when {
            snapshot == null -> CreditCardStatementTab.BILLED
            !snapshot.summary.isBillingScheduleConfigured -> CreditCardStatementTab.ALL
            snapshot.summary.remainingStatementDue.isPositive || billedItems.isNotEmpty() -> CreditCardStatementTab.BILLED
            else -> CreditCardStatementTab.UNBILLED
        }

        CreditCardDetailUiState(
            accountId = accountId,
            summary = snapshot?.summary,
            selectedTab = runtime.selectedTab ?: defaultTab,
            billedTransactions = billedItems,
            unbilledTransactions = unbilledItems,
            allTransactions = allItems,
            paymentAccounts = paymentAccounts,
            exchangeRateTable = rates,
            remindersEnabled = reminderPrefs.remindersEnabled,
            globalDefaultRemindDaysBefore = reminderPrefs.defaultRemindDaysBefore,
            isLoading = false,
            isSubmitting = runtime.isSubmitting,
            userMessage = runtime.userMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = CreditCardDetailUiState(accountId = accountId),
    )

    fun selectTab(tab: CreditCardStatementTab) {
        runtimeFlow.update { it.copy(selectedTab = tab) }
    }

    fun clearUserMessage() {
        runtimeFlow.update { it.copy(userMessage = null) }
    }

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            reminderPreferencesRepository.setRemindersEnabled(enabled)
            runtimeFlow.update {
                it.copy(
                    userMessage = if (enabled) {
                        "已开启信用卡还款本地提醒"
                    } else {
                        "已关闭信用卡还款本地提醒"
                    },
                )
            }
        }
    }

    fun recordRepayment(
        paymentAccountId: String?,
        repaymentAmountText: String,
        paidFromAmountText: String,
        feeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        val summary = uiState.value.summary
        if (summary == null) {
            onComplete("信用账户信息尚未就绪")
            return
        }
        runMutation(onComplete) {
            val resolvedPaymentId = paymentAccountId
                ?: throw IllegalArgumentException("请选择付款资金账户")
            val paymentAccount = uiState.value.paymentAccounts.firstOrNull { it.id == resolvedPaymentId }
                ?: throw IllegalArgumentException("未找到所选付款账户")

            val cardCurrency = summary.account.currency
            val repaymentAmount = parsePositiveMoney(repaymentAmountText, cardCurrency, "还款金额")
            val paidFromAmount = if (paymentAccount.currency != cardCurrency) {
                val trimmedPaid = paidFromAmountText.trim()
                if (trimmedPaid.isNotEmpty()) {
                    parsePositiveMoney(trimmedPaid, paymentAccount.currency, "实付金额")
                } else {
                    null
                }
            } else {
                null
            }
            val feeCurrency = if (paymentAccount.currency != cardCurrency) paymentAccount.currency else cardCurrency
            val feeMoney = parseOptionalPositiveMoney(feeText, feeCurrency, "手续费")

            creditCardRepository.recordRepayment(
                CreditCardRepaymentRequest(
                    creditCardAccountId = accountId,
                    paymentAccountId = paymentAccount.id,
                    repaymentAmount = repaymentAmount,
                    paidFromAccountAmount = paidFromAmount,
                    fee = feeMoney,
                    occurredAt = clock.nowEpochMs(),
                    note = note.trim().takeIf { it.isNotEmpty() },
                ),
            )
            "信用卡还款已原子入账"
        }
    }

    fun updateCreditParameters(
        limitText: String,
        billingDayText: String,
        dueDayText: String,
        remindDaysText: String,
        onComplete: (String?) -> Unit,
    ) {
        val summary = uiState.value.summary
        if (summary == null) {
            onComplete("信用账户信息尚未就绪")
            return
        }
        runMutation(onComplete) {
            val currency = summary.account.currency
            val trimmedLimit = limitText.trim()
            val limitMinor = if (trimmedLimit.isEmpty()) {
                null
            } else {
                val money = runCatching { Money.fromDecimalString(trimmedLimit, currency) }.getOrElse {
                    throw IllegalArgumentException("信用额度格式无效")
                }
                require(!money.isNegative) { "信用额度不能为负数" }
                money.amountMinor
            }

            val billingDay = parseOptionalDayInRange(billingDayText, 1..31, "账单日")
            val dueDay = parseOptionalDayInRange(dueDayText, 1..31, "还款日")
            val remindDays = parseOptionalDayInRange(remindDaysText, 0..31, "提前提醒天数")

            creditCardRepository.updateCreditCardParameters(
                UpdateCreditCardParametersRequest(
                    accountId = accountId,
                    creditLimitMinor = limitMinor,
                    billingDay = billingDay,
                    dueDay = dueDay,
                    remindDaysBefore = remindDays,
                ),
            )
            "已更新信用卡参数"
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

    private fun mapCardTransaction(
        record: LedgerTransactionRecord,
        card: Account,
        accountsById: Map<String, Account>,
    ): CreditCardTransactionItem {
        val tx = record.transaction
        val deltaMinor = CreditCardStatementCalculator.netPostingDeltaMinor(tx, card)
        val delta = Money(deltaMinor, card.currency)
        val counterpartPosting = tx.businessPostings.firstOrNull { it.accountId != card.id }
        val counterpartAccount = counterpartPosting?.let { accountsById[it.accountId] }
        val parentAccount = counterpartAccount?.parentId?.let { accountsById[it] }

        val categoryLabel = when {
            counterpartAccount != null && parentAccount != null -> "${parentAccount.name}·${counterpartAccount.name}"
            counterpartAccount != null -> counterpartAccount.name
            else -> null
        }

        val isRepaymentOrRefund = deltaMinor > 0L
        val title = when {
            tx.displayType == TransactionDisplayType.TRANSFER && isRepaymentOrRefund ->
                tx.payee?.takeIf { it.isNotBlank() } ?: "信用卡还款"
            tx.displayType == TransactionDisplayType.REFUND ->
                tx.payee?.takeIf { it.isNotBlank() } ?: categoryLabel?.let { "退款 · $it" } ?: "消费退款"
            !tx.payee.isNullOrBlank() && categoryLabel != null -> "$categoryLabel · ${tx.payee}"
            !tx.payee.isNullOrBlank() -> tx.payee!!
            categoryLabel != null -> categoryLabel
            else -> "信用卡交易"
        }

        val subtitle = buildList {
            if (tx.displayType == TransactionDisplayType.TRANSFER && counterpartAccount != null) {
                add(if (isRepaymentOrRefund) "转自 ${counterpartAccount.name}" else "转至 ${counterpartAccount.name}")
            } else if (categoryLabel != null && !title.startsWith(categoryLabel)) {
                add(categoryLabel)
            }
            tx.note?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ").ifBlank { AssetsFormatters.formatFullTimestamp(tx.occurredAt) }

        val iconKey = when {
            tx.displayType == TransactionDisplayType.TRANSFER -> "swap_horiz"
            else -> counterpartAccount?.icon ?: parentAccount?.icon ?: "credit_card"
        }
        val colorHex = when {
            tx.displayType == TransactionDisplayType.TRANSFER && isRepaymentOrRefund -> "#127A56"
            else -> counterpartAccount?.color ?: parentAccount?.color ?: (card.color ?: "#C62828")
        }

        return CreditCardTransactionItem(
            id = tx.id,
            occurredAt = tx.occurredAt,
            dateTimeLabel = AssetsFormatters.formatFullTimestamp(tx.occurredAt),
            displayType = tx.displayType,
            title = title,
            subtitle = subtitle,
            iconKey = iconKey,
            colorHex = colorHex,
            deltaAmount = delta,
            isRepaymentOrRefund = isRepaymentOrRefund,
            tags = record.tags,
            note = tx.note,
        )
    }

    companion object {
        const val NAV_ARG_ACCOUNT_ID: String = "accountId"

        fun estimateCrossCurrencyPaidAmount(
            repaymentAmountText: String,
            cardCurrency: Currency,
            paymentAccount: Account?,
            exchangeRateTable: ExchangeRateTable,
        ): Money? {
            if (paymentAccount == null || paymentAccount.currency == cardCurrency) return null
            val repaymentMoney = runCatching {
                Money.fromDecimalString(repaymentAmountText.trim(), cardCurrency)
            }.getOrNull() ?: return null
            if (!repaymentMoney.isPositive) return null

            val rate = exchangeRateTable.findRate(cardCurrency, paymentAccount.currency)
                ?: LedgerRepository.fallbackReferenceRate(cardCurrency, paymentAccount.currency)
            return runCatching {
                CreditCardRepaymentHelper.resolveCrossCurrencyPaidAmount(
                    repaymentAmount = repaymentMoney,
                    paymentAccount = paymentAccount,
                    exchangeRateOverride = rate,
                )
            }.getOrNull()
        }

        private fun parsePositiveMoney(raw: String, currency: Currency, label: String): Money {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "请输入$label" }
            val money = runCatching { Money.fromDecimalString(trimmed, currency) }.getOrElse {
                throw IllegalArgumentException("${label}格式无效")
            }
            require(money.isPositive) { "${label}必须大于 0" }
            return money
        }

        private fun parseOptionalPositiveMoney(raw: String, currency: Currency, label: String): Money? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val bd = runCatching { BigDecimal(trimmed) }.getOrElse {
                throw IllegalArgumentException("${label}格式无效")
            }
            if (bd.compareTo(BigDecimal.ZERO) == 0) return null
            val money = Money.fromBigDecimal(bd, currency)
            require(money.isPositive) { "${label}不能为负数" }
            return money
        }

        private fun parseOptionalDayInRange(raw: String, range: IntRange, label: String): Int? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val parsed = trimmed.toIntOrNull()
                ?: throw IllegalArgumentException("${label}需为整数")
            require(parsed in range) { "${label}需在 ${range.first}–${range.last} 之间" }
            return parsed
        }
    }
}
