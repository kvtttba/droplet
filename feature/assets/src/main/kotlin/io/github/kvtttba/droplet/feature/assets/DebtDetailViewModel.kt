package io.github.kvtttba.droplet.feature.assets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.DebtBadDebtWriteOffRequest
import io.github.kvtttba.droplet.core.database.repository.DebtBorrowRequest
import io.github.kvtttba.droplet.core.database.repository.DebtCollectRequest
import io.github.kvtttba.droplet.core.database.repository.DebtLendRequest
import io.github.kvtttba.droplet.core.database.repository.DebtRepayRequest
import io.github.kvtttba.droplet.core.database.repository.DebtRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.UpdateDebtProfileRequest
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.debt.DebtProfileSummary
import io.github.kvtttba.droplet.core.domain.debt.DebtSummaryCalculator
import io.github.kvtttba.droplet.core.domain.debt.DebtTransactionRecord
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.DebtReminderPreferencesRepository
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

data class DebtDetailUiState(
    val profileId: String = "",
    val summary: DebtProfileSummary? = null,
    val receivableAccount: Account? = null,
    val payableAccount: Account? = null,
    val transactions: List<DebtTransactionRecord> = emptyList(),
    val fundingAccounts: List<Account> = emptyList(),
    val expenseCategories: List<Account> = emptyList(),
    val incomeCategories: List<Account> = emptyList(),
    val accountsById: Map<String, Account> = emptyMap(),
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val isDeleted: Boolean = false,
    val userMessage: String? = null,
)

private data class DebtDetailRuntimeState(
    val isSubmitting: Boolean = false,
    val isDeleted: Boolean = false,
    val userMessage: String? = null,
)

private data class DebtDetailCatalog(
    val operationalAccounts: List<Account>,
    val expenseCategories: List<Account>,
    val incomeCategories: List<Account>,
    val allAccountsById: Map<String, Account>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DebtDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val debtRepository: DebtRepository,
    private val debtReminderPreferencesRepository: DebtReminderPreferencesRepository,
    private val ledgerRepository: LedgerRepository,
    private val clock: NetworkClock,
) : ViewModel() {

    val profileId: String = checkNotNull(savedStateHandle.get<String>(NAV_ARG_PROFILE_ID)) {
        "Missing navigation argument '$NAV_ARG_PROFILE_ID'"
    }

    private val runtimeFlow = MutableStateFlow(DebtDetailRuntimeState())

    private val detailSnapshotFlow = debtReminderPreferencesRepository.preferences
        .map { it.defaultRemindDaysBefore }
        .distinctUntilChanged()
        .flatMapLatest { remindDays ->
            debtRepository.observeDebtDetail(
                profileId = profileId,
                remindDaysBefore = remindDays,
            )
        }

    private val catalogFlow = combine(
        ledgerRepository.observeOperationalAccounts(includeArchived = false),
        ledgerRepository.observeCategories(AccountType.EXPENSE, includeArchived = false),
        ledgerRepository.observeCategories(AccountType.INCOME, includeArchived = false),
        ledgerRepository.observeAllAccounts(includeArchived = true, includeSystem = true),
    ) { operational, expenses, incomes, allAccounts ->
        DebtDetailCatalog(
            operationalAccounts = operational,
            expenseCategories = expenses,
            incomeCategories = incomes,
            allAccountsById = allAccounts.associateBy { it.id },
        )
    }

    val uiState: StateFlow<DebtDetailUiState> = combine(
        detailSnapshotFlow,
        catalogFlow,
        runtimeFlow,
    ) { snapshot, catalog, runtime ->
        val currency = snapshot?.summary?.currency ?: Currency.CNY
        val excludedIds = setOfNotNull(
            snapshot?.summary?.profile?.receivableAccountId,
            snapshot?.summary?.profile?.payableAccountId,
        )
        val matchingFunding = catalog.operationalAccounts
            .filter { acct ->
                acct.id !in excludedIds &&
                    acct.currency == currency &&
                    (acct.subType in AssetsViewModel.FUNDING_CASH_SUB_TYPES ||
                        acct.subType == AccountSubType.CREDIT_CARD)
            }
            .sortedWith(
                compareByDescending<Account> { it.id == DefaultSeedCatalog.AccountIds.DEBIT_CARD }
                    .thenBy { it.sortOrder }
                    .thenBy { it.name },
            )
        val leafExpenses = catalog.expenseCategories.filter { it.parentId != null }
            .ifEmpty { catalog.expenseCategories }
        val leafIncomes = catalog.incomeCategories.filter { it.parentId != null }
            .ifEmpty { catalog.incomeCategories }

        DebtDetailUiState(
            profileId = profileId,
            summary = snapshot?.summary,
            receivableAccount = snapshot?.receivableAccount,
            payableAccount = snapshot?.payableAccount,
            transactions = snapshot?.transactions.orEmpty(),
            fundingAccounts = matchingFunding,
            expenseCategories = leafExpenses,
            incomeCategories = leafIncomes,
            accountsById = catalog.allAccountsById,
            isLoading = false,
            isSubmitting = runtime.isSubmitting,
            isDeleted = runtime.isDeleted,
            userMessage = runtime.userMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = DebtDetailUiState(profileId = profileId),
    )

    fun clearUserMessage() {
        runtimeFlow.update { it.copy(userMessage = null) }
    }

    fun recordLend(
        fromAccountId: String?,
        amountText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        val summary = uiState.value.summary ?: return onComplete("往来人信息尚未就绪")
        runMutation(onComplete) {
            val accountId = fromAccountId ?: throw IllegalArgumentException("请选择付款账户")
            val amount = parsePositiveMoney(amountText, summary.currency, "借出金额")
            debtRepository.recordLend(
                DebtLendRequest(
                    profileId = profileId,
                    fromAccountId = accountId,
                    amount = amount,
                    occurredAt = clock.nowEpochMs(),
                    note = note.trim().takeIf { it.isNotEmpty() },
                ),
            )
            "已记录向「${summary.profile.name}」借出 $amount"
        }
    }

    fun recordBorrow(
        toAccountId: String?,
        amountText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        val summary = uiState.value.summary ?: return onComplete("往来人信息尚未就绪")
        runMutation(onComplete) {
            val accountId = toAccountId ?: throw IllegalArgumentException("请选择收款账户")
            val amount = parsePositiveMoney(amountText, summary.currency, "借入金额")
            debtRepository.recordBorrow(
                DebtBorrowRequest(
                    profileId = profileId,
                    toAccountId = accountId,
                    amount = amount,
                    occurredAt = clock.nowEpochMs(),
                    note = note.trim().takeIf { it.isNotEmpty() },
                ),
            )
            "已记录向「${summary.profile.name}」借入 $amount"
        }
    }

    fun recordCollect(
        depositAccountId: String?,
        principalText: String,
        interestText: String,
        interestIncomeAccountId: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        val summary = uiState.value.summary ?: return onComplete("往来人信息尚未就绪")
        runMutation(onComplete) {
            val accountId = depositAccountId ?: throw IllegalArgumentException("请选择收款账户")
            val principal = parsePositiveMoney(principalText, summary.currency, "收回本金")
            val interest = parseNonNegativeMoney(interestText, summary.currency, "附加利息收入")
            debtRepository.recordCollect(
                DebtCollectRequest(
                    profileId = profileId,
                    depositAccountId = accountId,
                    principalAmount = principal,
                    interestAmount = interest,
                    interestIncomeAccountId = interestIncomeAccountId,
                    occurredAt = clock.nowEpochMs(),
                    note = note.trim().takeIf { it.isNotEmpty() },
                ),
            )
            val total = principal + interest
            if (interest.isPositive) {
                "已记录收款 $total（本金 $principal + 利息 $interest）"
            } else {
                "已记录收回本金 $principal"
            }
        }
    }

    fun recordRepay(
        paymentAccountId: String?,
        principalText: String,
        interestText: String,
        interestExpenseAccountId: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        val summary = uiState.value.summary ?: return onComplete("往来人信息尚未就绪")
        runMutation(onComplete) {
            val accountId = paymentAccountId ?: throw IllegalArgumentException("请选择付款账户")
            val principal = parsePositiveMoney(principalText, summary.currency, "归还本金")
            val interest = parseNonNegativeMoney(interestText, summary.currency, "附加利息支出")
            debtRepository.recordRepay(
                DebtRepayRequest(
                    profileId = profileId,
                    paymentAccountId = accountId,
                    principalAmount = principal,
                    interestAmount = interest,
                    interestExpenseAccountId = interestExpenseAccountId,
                    occurredAt = clock.nowEpochMs(),
                    note = note.trim().takeIf { it.isNotEmpty() },
                ),
            )
            val total = principal + interest
            if (interest.isPositive) {
                "已记录还款 $total（本金 $principal + 利息 $interest）"
            } else {
                "已记录归还本金 $principal"
            }
        }
    }

    fun recordBadDebtWriteOff(
        amountText: String,
        badDebtExpenseAccountId: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        val summary = uiState.value.summary ?: return onComplete("往来人信息尚未就绪")
        runMutation(onComplete) {
            val amount = parsePositiveMoney(amountText, summary.currency, "坏账核销金额")
            debtRepository.recordBadDebtWriteOff(
                DebtBadDebtWriteOffRequest(
                    profileId = profileId,
                    amount = amount,
                    badDebtExpenseAccountId = badDebtExpenseAccountId,
                    occurredAt = clock.nowEpochMs(),
                    note = note.trim().takeIf { it.isNotEmpty() },
                ),
            )
            "已核销坏账 $amount 并同步平账"
        }
    }

    fun updateProfile(
        name: String,
        dueDateIso: String,
        annualInterestRatePercentText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        if (uiState.value.summary == null) return onComplete("往来人信息尚未就绪")
        runMutation(onComplete) {
            val trimmedName = name.trim()
            require(trimmedName.isNotEmpty()) { "往来人名称不能为空" }
            val trimmedDue = dueDateIso.trim()
            val dueAt = if (trimmedDue.isEmpty()) {
                null
            } else {
                runCatching {
                    AssetsFormatters.parseIsoDateToEpochMs(
                        trimmedDue,
                        zoneId = DebtSummaryCalculator.DEFAULT_ZONE_ID,
                    )
                }.getOrElse {
                    throw IllegalArgumentException("约定还款日格式无效，请使用 YYYY-MM-DD")
                }
            }
            val trimmedRate = annualInterestRatePercentText.trim()
            val rateDecimal = AssetsFormatters.parseOptionalRatePercentToCanonicalDecimal(
                trimmedRate,
                "约定年利率",
            )

            debtRepository.updateDebtProfile(
                UpdateDebtProfileRequest(
                    profileId = profileId,
                    name = trimmedName,
                    defaultDueAt = dueAt,
                    clearDefaultDueAt = trimmedDue.isEmpty(),
                    annualInterestRateDecimal = rateDecimal,
                    clearAnnualInterestRate = trimmedRate.isEmpty(),
                    note = note.trim().takeIf { it.isNotEmpty() },
                    clearNote = note.trim().isEmpty(),
                ),
            )
            "已更新往来人信息"
        }
    }

    fun deleteProfile(onComplete: (String?) -> Unit) {
        if (runtimeFlow.value.isSubmitting) return
        viewModelScope.launch {
            runtimeFlow.update { it.copy(isSubmitting = true) }
            val result = debtRepository.deleteDebtProfileIfUnused(profileId)
            runtimeFlow.update {
                it.copy(
                    isSubmitting = false,
                    isDeleted = result.isSuccess,
                    userMessage = result.fold(
                        onSuccess = { "已删除无流水的往来人" },
                        onFailure = { err -> err.message ?: "删除往来人失败" },
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

    private fun parsePositiveMoney(raw: String, currency: Currency, label: String): Money {
        val cleaned = raw.trim()
        require(cleaned.isNotEmpty()) { "请输入$label" }
        val parsed = runCatching { Money.fromDecimalString(cleaned, currency) }.getOrElse {
            throw IllegalArgumentException("${label}格式无效")
        }
        require(parsed.isPositive) { "${label}必须大于 0" }
        return parsed
    }

    private fun parseNonNegativeMoney(raw: String, currency: Currency, label: String): Money {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return Money.zero(currency)
        val parsed = runCatching { Money.fromDecimalString(cleaned, currency) }.getOrElse {
            throw IllegalArgumentException("${label}格式无效")
        }
        require(!parsed.isNegative) { "${label}不能为负数" }
        return parsed
    }

    companion object {
        const val NAV_ARG_PROFILE_ID: String = "profileId"
    }
}
