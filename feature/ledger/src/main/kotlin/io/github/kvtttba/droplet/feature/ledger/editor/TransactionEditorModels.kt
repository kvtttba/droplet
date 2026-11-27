package io.github.kvtttba.droplet.feature.ledger.editor

import io.github.kvtttba.droplet.core.domain.ledger.DiscountMode
import io.github.kvtttba.droplet.core.domain.ledger.TransferFeeMode
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.feature.ledger.CategoryGroupOption
import io.github.kvtttba.droplet.feature.ledger.LedgerDateFormats

enum class EditorTab(val label: String) {
    EXPENSE("支出"),
    INCOME("收入"),
    TRANSFER("转账/还款"),
}

enum class PostingDirection(val label: String, val shortLabel: String) {
    DEBIT("借方 (+)", "借 +"),
    CREDIT("贷方 (-)", "贷 -"),
}

data class SplitRowDraft(
    val rowId: String,
    val accountId: String? = null,
    val direction: PostingDirection = PostingDirection.DEBIT,
    val currency: Currency = Currency.CNY,
    val amountText: String = "",
)

data class CurrencyImbalanceInfo(
    val currency: Currency,
    val imbalanceMinor: Long,
) {
    val isBalanced: Boolean
        get() = imbalanceMinor == 0L

    val money: Money
        get() = Money(imbalanceMinor, currency)
}

data class TransactionEditorUiState(
    val isEditing: Boolean = false,
    val editingTransactionId: String? = null,
    val isSplitMode: Boolean = false,
    val activeTab: EditorTab = EditorTab.EXPENSE,
    // Intuitive mode fields
    val amountText: String = "",
    val transactionCurrency: Currency = Currency.CNY,
    val counterAmountText: String = "",
    val manualRateText: String = "",
    val impliedRateSummary: String? = null,
    val selectedParentCategoryId: String? = null,
    val selectedCategoryId: String? = null,
    val selectedAccountId: String? = null,
    val selectedTargetAccountId: String? = null,
    val isReimbursable: Boolean = false,
    val discountText: String = "",
    val discountMode: DiscountMode = DiscountMode.NET_EXPENSE,
    val feeText: String = "",
    val transferFeeMode: TransferFeeMode = TransferFeeMode.SENDER_EXTRA,
    val showAdvancedOptions: Boolean = false,
    // Shared metadata
    val occurredAt: Long = System.currentTimeMillis(),
    val selectedTagIds: Set<String> = emptySet(),
    val payee: String = "",
    val note: String = "",
    // Professional split mode fields
    val splitRows: List<SplitRowDraft> = emptyList(),
    val autoHedgeMultiCurrency: Boolean = true,
    val splitReferenceRates: Map<Currency, String> = mapOf(
        Currency.USD to "7.20",
        Currency.HKD to "0.92",
    ),
    val currencyImbalances: List<CurrencyImbalanceInfo> = emptyList(),
    val isSplitReadyToSave: Boolean = false,
    val splitStatusSummary: String = "请填写至少 2 条有效分录",
    // Live double-entry preview
    val livePreviewSummary: String? = null,
    // Catalogs
    val operationalAccounts: List<Account> = emptyList(),
    val accountBalances: Map<String, Money> = emptyMap(),
    val allSelectableAccounts: List<Account> = emptyList(),
    val expenseCategoryGroups: List<CategoryGroupOption> = emptyList(),
    val incomeCategoryGroups: List<CategoryGroupOption> = emptyList(),
    val availableTags: List<Tag> = emptyList(),
    val baseCurrency: Currency = Currency.DEFAULT,
    // Submission status
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val savedSuccessfully: Boolean = false,
) {
    val dateTimeLabel: String
        get() = LedgerDateFormats.formatDateTime(occurredAt)

    val selectedAccount: Account?
        get() = operationalAccounts.firstOrNull { it.id == selectedAccountId }

    val selectedTargetAccount: Account?
        get() = operationalAccounts.firstOrNull { it.id == selectedTargetAccountId }

    val isCrossCurrencyIntuitive: Boolean
        get() = when (activeTab) {
            EditorTab.EXPENSE, EditorTab.INCOME -> {
                val accountCcy = selectedAccount?.currency ?: transactionCurrency
                transactionCurrency != accountCcy
            }
            EditorTab.TRANSFER -> {
                val fromCcy = selectedAccount?.currency
                val toCcy = selectedTargetAccount?.currency
                fromCcy != null && toCcy != null && fromCcy != toCcy
            }
        }

    val needsManualBaseRateInIntuitive: Boolean
        get() = when (activeTab) {
            EditorTab.EXPENSE, EditorTab.INCOME -> {
                val accountCcy = selectedAccount?.currency ?: transactionCurrency
                if (transactionCurrency == accountCcy) {
                    transactionCurrency != baseCurrency
                } else {
                    transactionCurrency != baseCurrency && accountCcy != baseCurrency
                }
            }
            EditorTab.TRANSFER -> {
                val fromCcy = selectedAccount?.currency ?: baseCurrency
                val toCcy = selectedTargetAccount?.currency ?: baseCurrency
                fromCcy != baseCurrency && toCcy != baseCurrency
            }
        }

    val activeCategoryGroups: List<CategoryGroupOption>
        get() = when (activeTab) {
            EditorTab.EXPENSE -> expenseCategoryGroups
            EditorTab.INCOME -> incomeCategoryGroups
            EditorTab.TRANSFER -> emptyList()
        }

    val counterCurrency: Currency
        get() = when (activeTab) {
            EditorTab.TRANSFER -> selectedTargetAccount?.currency ?: Currency.CNY
            else -> selectedAccount?.currency ?: Currency.CNY
        }

    val sourceCurrencyForIntuitive: Currency
        get() = when (activeTab) {
            EditorTab.TRANSFER -> selectedAccount?.currency ?: Currency.CNY
            else -> transactionCurrency
        }
}
