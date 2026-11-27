package io.github.kvtttba.droplet.feature.ledger.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.PrivacyAmountFormatter
import io.github.kvtttba.droplet.core.domain.ledger.DiscountMode
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidator
import io.github.kvtttba.droplet.core.domain.ledger.PostingDraft
import io.github.kvtttba.droplet.core.domain.ledger.TradingAutoHedger
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.domain.ledger.TransferFeeMode
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Posting
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.feature.ledger.LedgerItemMapper
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class TransactionEditorViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val ledgerEngine: LedgerEngine,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialTransactionId: String? =
        savedStateHandle.get<String>("transactionId")?.takeIf { it.isNotBlank() }

    private val _uiState = MutableStateFlow(
        TransactionEditorUiState(
            isEditing = initialTransactionId != null,
            editingTransactionId = initialTransactionId,
            baseCurrency = ledgerEngine.baseCurrency,
        ),
    )
    val uiState: StateFlow<TransactionEditorUiState> = _uiState.asStateFlow()

    private var cachedRateTable: ExchangeRateTable = ExchangeRateTable.empty()
    private var editingCreatedAt: Long? = null
    private var loadedExistingTransaction: Boolean = false
    private var rowCounter: Int = 0

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
        }

        viewModelScope.launch {
            combine(
                repository.observeAllAccounts(includeArchived = true, includeSystem = true),
                repository.observeAccountBalances(),
                repository.observeTags(includeArchived = false),
                repository.observeExchangeRateTable(),
            ) { allAccounts, balances, tags, rateTable ->
                CatalogSnapshot(allAccounts, balances, tags, rateTable)
            }.collect { snap ->
                cachedRateTable = snap.rateTable
                val operational = snap.allAccounts.filter {
                    !it.isArchived && !it.isSystem && it.subType in LedgerRepository.OPERATIONAL_SUB_TYPES
                }
                val expenseCats = snap.allAccounts.filter {
                    !it.isArchived && !it.isSystem &&
                        it.type == AccountType.EXPENSE && it.subType == AccountSubType.CATEGORY
                }
                val incomeCats = snap.allAccounts.filter {
                    !it.isArchived && !it.isSystem &&
                        it.type == AccountType.INCOME && it.subType == AccountSubType.CATEGORY
                }
                val selectableForSplit = snap.allAccounts.filter {
                    !it.isArchived &&
                        it.subType != AccountSubType.TRADING &&
                        it.subType != AccountSubType.OPENING
                }
                val balanceMap = snap.balances.associate { row ->
                    row.accountId to Money(row.balanceMinor, row.currency)
                }
                val expenseGroups = LedgerItemMapper.buildCategoryGroups(expenseCats)
                val incomeGroups = LedgerItemMapper.buildCategoryGroups(incomeCats)

                val defaultSplitRates = mapOf(
                    Currency.USD to (snap.rateTable.findRate(Currency.USD, Currency.CNY)?.rateDecimal ?: "7.20"),
                    Currency.HKD to (snap.rateTable.findRate(Currency.HKD, Currency.CNY)?.rateDecimal ?: "0.92"),
                )

                _uiState.update { current ->
                    val defaultAccount = current.selectedAccountId
                        ?.takeIf { id -> operational.any { it.id == id } }
                        ?: operational.firstOrNull { it.id == DefaultSeedCatalog.AccountIds.DEBIT_CARD }?.id
                        ?: operational.firstOrNull()?.id

                    val defaultTarget = current.selectedTargetAccountId
                        ?.takeIf { id -> operational.any { it.id == id } && id != defaultAccount }
                        ?: operational.firstOrNull { it.id != defaultAccount }?.id

                    val activeGroups = when (current.activeTab) {
                        EditorTab.EXPENSE -> expenseGroups
                        EditorTab.INCOME -> incomeGroups
                        EditorTab.TRANSFER -> emptyList()
                    }
                    val (parentId, categoryId) = resolveCategorySelection(
                        currentParentId = current.selectedParentCategoryId,
                        currentCategoryId = current.selectedCategoryId,
                        groups = activeGroups,
                        preferredChildId = if (current.activeTab == EditorTab.EXPENSE) {
                            DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH
                        } else {
                            DefaultSeedCatalog.AccountIds.INC_WORK_SALARY
                        },
                    )

                    val selectedAcctCcy = operational.firstOrNull { it.id == defaultAccount }?.currency
                    val resolvedCurrency = if (current.selectedAccountId == null && selectedAcctCcy != null) {
                        selectedAcctCcy
                    } else {
                        current.transactionCurrency
                    }

                    val updated = current.copy(
                        operationalAccounts = operational,
                        accountBalances = balanceMap,
                        allSelectableAccounts = selectableForSplit,
                        expenseCategoryGroups = expenseGroups,
                        incomeCategoryGroups = incomeGroups,
                        availableTags = snap.tags,
                        selectedAccountId = defaultAccount,
                        selectedTargetAccountId = defaultTarget,
                        selectedParentCategoryId = parentId,
                        selectedCategoryId = categoryId,
                        transactionCurrency = resolvedCurrency,
                        splitReferenceRates = if (current.splitReferenceRates.isEmpty()) {
                            defaultSplitRates
                        } else {
                            current.splitReferenceRates
                        },
                        manualRateText = current.manualRateText.ifBlank {
                            defaultRateTextFor(resolvedCurrency, snap.rateTable)
                        },
                    )
                    recomputeDerivedState(updated)
                }

                if (initialTransactionId != null && !loadedExistingTransaction && snap.allAccounts.isNotEmpty()) {
                    loadedExistingTransaction = true
                    loadExistingTransaction(initialTransactionId, snap.allAccounts)
                }
            }
        }
    }

    fun selectTab(tab: EditorTab) {
        _uiState.update { current ->
            if (current.activeTab == tab && !current.isSplitMode) return@update current
            val groups = when (tab) {
                EditorTab.EXPENSE -> current.expenseCategoryGroups
                EditorTab.INCOME -> current.incomeCategoryGroups
                EditorTab.TRANSFER -> emptyList()
            }
            val (parentId, categoryId) = resolveCategorySelection(
                currentParentId = null,
                currentCategoryId = null,
                groups = groups,
                preferredChildId = if (tab == EditorTab.EXPENSE) {
                    DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH
                } else {
                    DefaultSeedCatalog.AccountIds.INC_WORK_SALARY
                },
            )
            val acctCcy = current.selectedAccount?.currency ?: Currency.CNY
            val updated = current.copy(
                activeTab = tab,
                isSplitMode = false,
                selectedParentCategoryId = parentId,
                selectedCategoryId = categoryId,
                transactionCurrency = acctCcy,
                counterAmountText = "",
                isReimbursable = if (tab == EditorTab.EXPENSE) current.isReimbursable else false,
                manualRateText = defaultRateTextFor(acctCcy, cachedRateTable),
                errorMessage = null,
            )
            recomputeDerivedState(updated)
        }
    }

    fun toggleSplitMode() {
        _uiState.update { current ->
            if (current.isSplitMode) {
                recomputeDerivedState(current.copy(isSplitMode = false, errorMessage = null))
            } else {
                val seededRows = if (current.splitRows.size >= 2 && current.splitRows.any { it.amountText.isNotBlank() }) {
                    current.splitRows
                } else {
                    buildInitialSplitRowsFromIntuitive(current)
                }
                recomputeDerivedState(
                    current.copy(
                        isSplitMode = true,
                        splitRows = seededRows,
                        errorMessage = null,
                    ),
                )
            }
        }
    }

    fun onAmountChange(raw: String) {
        val sanitized = sanitizeDecimalInput(raw)
        _uiState.update { current ->
            recomputeDerivedState(current.copy(amountText = sanitized, errorMessage = null))
        }
    }

    fun onTransactionCurrencyChange(currency: Currency) {
        _uiState.update { current ->
            val updated = current.copy(
                transactionCurrency = currency,
                manualRateText = defaultRateTextFor(currency, cachedRateTable),
                errorMessage = null,
            )
            recomputeDerivedState(updated)
        }
    }

    fun onCounterAmountChange(raw: String) {
        val sanitized = sanitizeDecimalInput(raw)
        _uiState.update { current ->
            recomputeDerivedState(current.copy(counterAmountText = sanitized, errorMessage = null))
        }
    }

    fun onManualRateChange(raw: String) {
        val sanitized = sanitizeDecimalInput(raw, maxDecimals = 8)
        _uiState.update { current ->
            recomputeDerivedState(current.copy(manualRateText = sanitized, errorMessage = null))
        }
    }

    fun selectParentCategory(parentId: String) {
        _uiState.update { current ->
            val group = current.activeCategoryGroups.firstOrNull { it.parent.id == parentId }
            val childId = group?.children?.firstOrNull()?.id ?: parentId
            recomputeDerivedState(
                current.copy(
                    selectedParentCategoryId = parentId,
                    selectedCategoryId = childId,
                    errorMessage = null,
                ),
            )
        }
    }

    fun selectCategory(categoryId: String) {
        _uiState.update { current ->
            val parentId = current.activeCategoryGroups.firstOrNull { group ->
                group.parent.id == categoryId || group.children.any { it.id == categoryId }
            }?.parent?.id
            recomputeDerivedState(
                current.copy(
                    selectedParentCategoryId = parentId ?: current.selectedParentCategoryId,
                    selectedCategoryId = categoryId,
                    errorMessage = null,
                ),
            )
        }
    }

    fun selectAccount(accountId: String) {
        _uiState.update { current ->
            val account = current.operationalAccounts.firstOrNull { it.id == accountId }
            val nextTarget = if (current.activeTab == EditorTab.TRANSFER && current.selectedTargetAccountId == accountId) {
                current.operationalAccounts.firstOrNull { it.id != accountId }?.id
            } else {
                current.selectedTargetAccountId
            }
            val nextCurrency = when (current.activeTab) {
                EditorTab.TRANSFER -> account?.currency ?: current.transactionCurrency
                else -> if (current.counterAmountText.isBlank()) {
                    account?.currency ?: current.transactionCurrency
                } else {
                    current.transactionCurrency
                }
            }
            val updated = current.copy(
                selectedAccountId = accountId,
                selectedTargetAccountId = nextTarget,
                transactionCurrency = nextCurrency,
                manualRateText = defaultRateTextFor(nextCurrency, cachedRateTable),
                errorMessage = null,
            )
            recomputeDerivedState(updated)
        }
    }

    fun selectTargetAccount(accountId: String) {
        _uiState.update { current ->
            val nextFrom = if (current.selectedAccountId == accountId) {
                current.operationalAccounts.firstOrNull { it.id != accountId }?.id
            } else {
                current.selectedAccountId
            }
            val updated = current.copy(
                selectedAccountId = nextFrom,
                selectedTargetAccountId = accountId,
                errorMessage = null,
            )
            recomputeDerivedState(updated)
        }
    }

    fun swapTransferAccounts() {
        _uiState.update { current ->
            val updated = current.copy(
                selectedAccountId = current.selectedTargetAccountId,
                selectedTargetAccountId = current.selectedAccountId,
                amountText = current.counterAmountText.ifBlank { current.amountText },
                counterAmountText = if (current.counterAmountText.isNotBlank()) current.amountText else "",
                errorMessage = null,
            )
            recomputeDerivedState(updated)
        }
    }

    fun onToggleReimbursable(enabled: Boolean) {
        _uiState.update { current ->
            recomputeDerivedState(current.copy(isReimbursable = enabled, errorMessage = null))
        }
    }

    fun onDiscountChange(raw: String) {
        _uiState.update { current ->
            recomputeDerivedState(current.copy(discountText = sanitizeDecimalInput(raw), errorMessage = null))
        }
    }

    fun onDiscountModeChange(mode: DiscountMode) {
        _uiState.update { current ->
            recomputeDerivedState(current.copy(discountMode = mode, errorMessage = null))
        }
    }

    fun onFeeChange(raw: String) {
        _uiState.update { current ->
            recomputeDerivedState(current.copy(feeText = sanitizeDecimalInput(raw), errorMessage = null))
        }
    }

    fun onTransferFeeModeChange(mode: TransferFeeMode) {
        _uiState.update { current ->
            recomputeDerivedState(current.copy(transferFeeMode = mode, errorMessage = null))
        }
    }

    fun onToggleAdvancedOptions() {
        _uiState.update { it.copy(showAdvancedOptions = !it.showAdvancedOptions) }
    }

    fun onOccurredAtChange(epochMs: Long) {
        _uiState.update { it.copy(occurredAt = epochMs) }
    }

    fun onToggleTag(tagId: String) {
        _uiState.update { current ->
            val updated = current.selectedTagIds.toMutableSet()
            if (!updated.add(tagId)) {
                updated.remove(tagId)
            }
            current.copy(selectedTagIds = updated)
        }
    }

    fun onPayeeChange(value: String) {
        _uiState.update { it.copy(payee = value) }
    }

    fun onNoteChange(value: String) {
        _uiState.update { it.copy(note = value) }
    }

    // --- Professional Split Entry Mode actions ---

    fun addSplitRow() {
        _uiState.update { current ->
            val newRow = SplitRowDraft(
                rowId = nextRowId(),
                accountId = current.operationalAccounts.firstOrNull()?.id,
                direction = PostingDirection.CREDIT,
                currency = Currency.CNY,
                amountText = "",
            )
            recomputeDerivedState(current.copy(splitRows = current.splitRows + newRow, errorMessage = null))
        }
    }

    fun removeSplitRow(rowId: String) {
        _uiState.update { current ->
            if (current.splitRows.size <= 2) return@update current
            recomputeDerivedState(
                current.copy(
                    splitRows = current.splitRows.filterNot { it.rowId == rowId },
                    errorMessage = null,
                ),
            )
        }
    }

    fun updateSplitRowAccount(rowId: String, accountId: String) {
        _uiState.update { current ->
            val account = current.allSelectableAccounts.firstOrNull { it.id == accountId }
            val updatedRows = current.splitRows.map { row ->
                if (row.rowId != rowId) {
                    row
                } else {
                    val lockedCurrency = if (account != null && account.type.isBalanceSheet) {
                        account.currency
                    } else {
                        row.currency
                    }
                    row.copy(accountId = accountId, currency = lockedCurrency)
                }
            }
            recomputeDerivedState(current.copy(splitRows = updatedRows, errorMessage = null))
        }
    }

    fun updateSplitRowDirection(rowId: String, direction: PostingDirection) {
        _uiState.update { current ->
            val updatedRows = current.splitRows.map { row ->
                if (row.rowId == rowId) row.copy(direction = direction) else row
            }
            recomputeDerivedState(current.copy(splitRows = updatedRows, errorMessage = null))
        }
    }

    fun updateSplitRowCurrency(rowId: String, currency: Currency) {
        _uiState.update { current ->
            val updatedRows = current.splitRows.map { row ->
                if (row.rowId != rowId) {
                    row
                } else {
                    val acct = current.allSelectableAccounts.firstOrNull { it.id == row.accountId }
                    if (acct != null && acct.type.isBalanceSheet) row else row.copy(currency = currency)
                }
            }
            recomputeDerivedState(current.copy(splitRows = updatedRows, errorMessage = null))
        }
    }

    fun updateSplitRowAmount(rowId: String, raw: String) {
        val sanitized = sanitizeDecimalInput(raw)
        _uiState.update { current ->
            val updatedRows = current.splitRows.map { row ->
                if (row.rowId == rowId) row.copy(amountText = sanitized) else row
            }
            recomputeDerivedState(current.copy(splitRows = updatedRows, errorMessage = null))
        }
    }

    fun onAutoHedgeMultiCurrencyChange(enabled: Boolean) {
        _uiState.update { current ->
            recomputeDerivedState(current.copy(autoHedgeMultiCurrency = enabled, errorMessage = null))
        }
    }

    fun onSplitReferenceRateChange(currency: Currency, raw: String) {
        val sanitized = sanitizeDecimalInput(raw, maxDecimals = 8)
        _uiState.update { current ->
            val updated = current.splitReferenceRates.toMutableMap().apply {
                put(currency, sanitized)
            }
            recomputeDerivedState(current.copy(splitReferenceRates = updated, errorMessage = null))
        }
    }

    /**
     * Fills an empty row (or appends a new balancing row) to zero out a single unbalanced currency.
     */
    fun autoBalanceSplit() {
        _uiState.update { current ->
            val unbalanced = current.currencyImbalances.filterNot { it.isBalanced }
            if (unbalanced.size != 1) return@update current
            val target = unbalanced.first()
            val neededDirection = if (target.imbalanceMinor > 0L) {
                PostingDirection.CREDIT
            } else {
                PostingDirection.DEBIT
            }
            val neededText = Money(kotlin.math.abs(target.imbalanceMinor), target.currency)
                .toBigDecimal()
                .stripTrailingZeros()
                .toPlainString()

            val emptyIndex = current.splitRows.indexOfFirst { it.amountText.isBlank() }
            val updatedRows = if (emptyIndex >= 0) {
                current.splitRows.mapIndexed { idx, row ->
                    if (idx == emptyIndex) {
                        row.copy(
                            direction = neededDirection,
                            currency = target.currency,
                            amountText = neededText,
                        )
                    } else {
                        row
                    }
                }
            } else {
                current.splitRows + SplitRowDraft(
                    rowId = nextRowId(),
                    accountId = current.operationalAccounts.firstOrNull { it.currency == target.currency }?.id
                        ?: current.operationalAccounts.firstOrNull()?.id,
                    direction = neededDirection,
                    currency = target.currency,
                    amountText = neededText,
                )
            }
            recomputeDerivedState(current.copy(splitRows = updatedRows, errorMessage = null))
        }
    }

    fun save() {
        val snapshot = _uiState.value
        if (snapshot.isSaving) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            runCatching {
                repository.ensureSeeded()
                val tx = buildTransaction(snapshot)
                repository.saveTransaction(
                    transaction = tx,
                    allowArchivedAccounts = snapshot.isEditing,
                )
            }.onSuccess {
                _uiState.update { it.copy(isSaving = false, savedSuccessfully = true, errorMessage = null) }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = friendlyError(err),
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private suspend fun loadExistingTransaction(
        txId: String,
        allAccounts: List<Account>,
    ) {
        val tx = repository.getTransactionById(txId) ?: return
        editingCreatedAt = tx.createdAt
        val accountsById = allAccounts.associateBy { it.id }
        val business = tx.businessPostings

        val splitRows = business.map { posting ->
            val direction = if (posting.amountMinor >= 0L) PostingDirection.DEBIT else PostingDirection.CREDIT
            val absDecimal = posting.money.abs().toBigDecimal().stripTrailingZeros().toPlainString()
            SplitRowDraft(
                rowId = nextRowId(),
                accountId = posting.accountId,
                direction = direction,
                currency = posting.currency,
                amountText = absDecimal,
            )
        }

        _uiState.update { current ->
            var next = current.copy(
                isEditing = true,
                editingTransactionId = tx.id,
                occurredAt = tx.occurredAt,
                payee = tx.payee.orEmpty(),
                note = tx.note.orEmpty(),
                selectedTagIds = tx.tagIds.toSet(),
                splitRows = splitRows,
            )

            val canPopulateIntuitive = when (tx.displayType) {
                TransactionDisplayType.EXPENSE -> {
                    val expPostings = business.filter { accountsById[it.accountId]?.type == AccountType.EXPENSE }
                    val payPostings = business.filter {
                        val t = accountsById[it.accountId]?.type
                        (t == AccountType.ASSET || t == AccountType.LIABILITY) && it.amountMinor < 0L
                    }
                    if (expPostings.size == 1 && payPostings.size == 1 && business.size == 2) {
                        val exp = expPostings.first()
                        val pay = payPostings.first()
                        val cat = accountsById[exp.accountId]
                        next = next.copy(
                            isSplitMode = false,
                            activeTab = EditorTab.EXPENSE,
                            amountText = exp.money.abs().toBigDecimal().stripTrailingZeros().toPlainString(),
                            transactionCurrency = exp.currency,
                            counterAmountText = if (pay.currency != exp.currency) {
                                pay.money.abs().toBigDecimal().stripTrailingZeros().toPlainString()
                            } else {
                                ""
                            },
                            manualRateText = exp.exchangeRateSnapshot.rateDecimal,
                            selectedCategoryId = exp.accountId,
                            selectedParentCategoryId = cat?.parentId ?: cat?.id,
                            selectedAccountId = pay.accountId,
                            isReimbursable = false,
                        )
                        true
                    } else {
                        false
                    }
                }

                TransactionDisplayType.INCOME -> {
                    val incPostings = business.filter { accountsById[it.accountId]?.type == AccountType.INCOME }
                    val depPostings = business.filter {
                        val t = accountsById[it.accountId]?.type
                        (t == AccountType.ASSET || t == AccountType.LIABILITY) && it.amountMinor > 0L
                    }
                    if (incPostings.size == 1 && depPostings.size == 1 && business.size == 2) {
                        val inc = incPostings.first()
                        val dep = depPostings.first()
                        val cat = accountsById[inc.accountId]
                        next = next.copy(
                            isSplitMode = false,
                            activeTab = EditorTab.INCOME,
                            amountText = inc.money.abs().toBigDecimal().stripTrailingZeros().toPlainString(),
                            transactionCurrency = inc.currency,
                            counterAmountText = if (dep.currency != inc.currency) {
                                dep.money.abs().toBigDecimal().stripTrailingZeros().toPlainString()
                            } else {
                                ""
                            },
                            manualRateText = inc.exchangeRateSnapshot.rateDecimal,
                            selectedCategoryId = inc.accountId,
                            selectedParentCategoryId = cat?.parentId ?: cat?.id,
                            selectedAccountId = dep.accountId,
                        )
                        true
                    } else {
                        false
                    }
                }

                TransactionDisplayType.TRANSFER -> {
                    val outP = business.firstOrNull { it.amountMinor < 0L }
                    val inP = business.firstOrNull { it.amountMinor > 0L && accountsById[it.accountId]?.type != AccountType.EXPENSE }
                    val feeP = business.firstOrNull { accountsById[it.accountId]?.type == AccountType.EXPENSE }
                    if (outP != null && inP != null && business.size <= 3) {
                        next = next.copy(
                            isSplitMode = false,
                            activeTab = EditorTab.TRANSFER,
                            selectedAccountId = outP.accountId,
                            selectedTargetAccountId = inP.accountId,
                            transactionCurrency = outP.currency,
                            amountText = outP.money.abs().toBigDecimal().stripTrailingZeros().toPlainString(),
                            counterAmountText = if (inP.currency != outP.currency) {
                                inP.money.abs().toBigDecimal().stripTrailingZeros().toPlainString()
                            } else {
                                ""
                            },
                            feeText = feeP?.money?.abs()?.toBigDecimal()?.stripTrailingZeros()?.toPlainString().orEmpty(),
                            showAdvancedOptions = feeP != null,
                        )
                        true
                    } else {
                        false
                    }
                }

                TransactionDisplayType.REIMBURSE -> {
                    val payP = business.firstOrNull { it.amountMinor < 0L }
                    val recP = business.firstOrNull { it.amountMinor > 0L }
                    if (tx.reimburseState == ReimburseState.PENDING && tx.refTransactionId == null && payP != null && recP != null) {
                        next = next.copy(
                            isSplitMode = false,
                            activeTab = EditorTab.EXPENSE,
                            amountText = recP.money.abs().toBigDecimal().stripTrailingZeros().toPlainString(),
                            transactionCurrency = recP.currency,
                            counterAmountText = if (payP.currency != recP.currency) {
                                payP.money.abs().toBigDecimal().stripTrailingZeros().toPlainString()
                            } else {
                                ""
                            },
                            selectedAccountId = payP.accountId,
                            isReimbursable = true,
                            showAdvancedOptions = true,
                        )
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }

            if (!canPopulateIntuitive) {
                next = next.copy(isSplitMode = true)
            }
            recomputeDerivedState(next)
        }
    }

    private fun buildTransaction(state: TransactionEditorUiState): Transaction {
        val now = System.currentTimeMillis()
        val meta = TransactionMeta(
            id = state.editingTransactionId,
            occurredAt = state.occurredAt,
            payee = state.payee.trim().ifEmpty { null },
            note = state.note.trim().ifEmpty { null },
            tagIds = state.selectedTagIds.toList(),
            createdAt = editingCreatedAt ?: now,
            updatedAt = now,
        )

        val effectiveRates = buildEffectiveRateTable(state)
        val engine = ledgerEngine.withRateProvider(effectiveRates)

        if (state.isSplitMode) {
            val drafts = state.splitRows.mapNotNull { row ->
                val accountId = row.accountId ?: return@mapNotNull null
                val positiveMoney = parsePositiveMoneyOrNull(row.amountText, row.currency)
                    ?: return@mapNotNull null
                val signed = if (row.direction == PostingDirection.DEBIT) positiveMoney else -positiveMoney
                PostingDraft(accountId = accountId, amount = signed)
            }
            require(drafts.size >= 2) { "专业分录模式至少需要 2 条有效非零分录" }
            return engine.buildCustomSplit(
                meta = meta,
                postings = drafts,
                displayType = TransactionDisplayType.SPLIT,
                autoHedgeMultiCurrency = state.autoHedgeMultiCurrency,
            )
        }

        return when (state.activeTab) {
            EditorTab.EXPENSE -> buildIntuitiveExpense(state, meta, engine)
            EditorTab.INCOME -> buildIntuitiveIncome(state, meta, engine)
            EditorTab.TRANSFER -> buildIntuitiveTransfer(state, meta, engine)
        }
    }

    private fun buildIntuitiveExpense(
        state: TransactionEditorUiState,
        meta: TransactionMeta,
        engine: LedgerEngine,
    ): Transaction {
        val paymentAccount = requireNotNull(state.selectedAccount) { "请选择付款账户" }
        val amount = requireNotNull(parsePositiveMoneyOrNull(state.amountText, state.transactionCurrency)) {
            "请输入大于 0 的有效金额"
        }
        val actualPaid = if (state.transactionCurrency != paymentAccount.currency) {
            requireNotNull(parsePositiveMoneyOrNull(state.counterAmountText, paymentAccount.currency)) {
                "跨币种消费请填写实际扣款金额 (${paymentAccount.currency.code})"
            }
        } else {
            null
        }
        val manualRateOverride = resolveManualRateOverride(
            currency = state.transactionCurrency,
            needsManual = state.needsManualBaseRateInIntuitive,
            rateText = state.manualRateText,
            capturedAt = meta.occurredAt,
            baseCurrency = state.baseCurrency,
        )

        if (state.isReimbursable) {
            return engine.buildReimbursableAdvance(
                meta = meta,
                reimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
                paymentAccountId = paymentAccount.id,
                amount = amount,
                actualPaidAmount = actualPaid,
                exchangeRateOverride = manualRateOverride,
            )
        }

        val categoryId = requireNotNull(state.selectedCategoryId) { "请选择支出分类" }
        val discount = state.discountText.takeIf { it.isNotBlank() }?.let {
            requireNotNull(parsePositiveMoneyOrNull(it, state.transactionCurrency)) { "优惠金额格式无效" }
        }
        val fee = state.feeText.takeIf { it.isNotBlank() }?.let {
            requireNotNull(parsePositiveMoneyOrNull(it, paymentAccount.currency)) { "手续费格式无效" }
        }

        return engine.buildExpense(
            meta = meta,
            expenseCategoryAccountId = categoryId,
            paymentAccountId = paymentAccount.id,
            amount = amount,
            discount = discount,
            discountMode = state.discountMode,
            discountIncomeAccountId = if (discount != null && state.discountMode == DiscountMode.GROSS_WITH_REBATE_INCOME) {
                DefaultSeedCatalog.AccountIds.INC_OTHER_REBATE
            } else {
                null
            },
            fee = fee,
            feeExpenseAccountId = if (fee != null) {
                DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE
            } else {
                null
            },
            actualPaidAmount = actualPaid,
            exchangeRateOverride = manualRateOverride,
        )
    }

    private fun buildIntuitiveIncome(
        state: TransactionEditorUiState,
        meta: TransactionMeta,
        engine: LedgerEngine,
    ): Transaction {
        val depositAccount = requireNotNull(state.selectedAccount) { "请选择收款账户" }
        val categoryId = requireNotNull(state.selectedCategoryId) { "请选择收入分类" }
        val amount = requireNotNull(parsePositiveMoneyOrNull(state.amountText, state.transactionCurrency)) {
            "请输入大于 0 的有效金额"
        }
        val actualReceived = if (state.transactionCurrency != depositAccount.currency) {
            requireNotNull(parsePositiveMoneyOrNull(state.counterAmountText, depositAccount.currency)) {
                "跨币种收入请填写实际到账金额 (${depositAccount.currency.code})"
            }
        } else {
            null
        }
        val fee = state.feeText.takeIf { it.isNotBlank() }?.let {
            requireNotNull(parsePositiveMoneyOrNull(it, depositAccount.currency)) { "手续费格式无效" }
        }
        val manualRateOverride = resolveManualRateOverride(
            currency = state.transactionCurrency,
            needsManual = state.needsManualBaseRateInIntuitive,
            rateText = state.manualRateText,
            capturedAt = meta.occurredAt,
            baseCurrency = state.baseCurrency,
        )

        return engine.buildIncome(
            meta = meta,
            incomeCategoryAccountId = categoryId,
            depositAccountId = depositAccount.id,
            amount = amount,
            fee = fee,
            feeExpenseAccountId = if (fee != null) {
                DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE
            } else {
                null
            },
            actualReceivedAmount = actualReceived,
            exchangeRateOverride = manualRateOverride,
        )
    }

    private fun buildIntuitiveTransfer(
        state: TransactionEditorUiState,
        meta: TransactionMeta,
        engine: LedgerEngine,
    ): Transaction {
        val fromAccount = requireNotNull(state.selectedAccount) { "请选择转出账户" }
        val toAccount = requireNotNull(state.selectedTargetAccount) { "请选择转入账户" }
        require(fromAccount.id != toAccount.id) { "转出账户与转入账户不能相同" }

        val outAmount = requireNotNull(parsePositiveMoneyOrNull(state.amountText, fromAccount.currency)) {
            "请输入大于 0 的转出金额"
        }
        val fee = state.feeText.takeIf { it.isNotBlank() }?.let {
            requireNotNull(parsePositiveMoneyOrNull(it, fromAccount.currency)) { "手续费格式无效" }
        }
        val feeAccountId = if (fee != null) DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE else null

        if (fromAccount.currency != toAccount.currency) {
            val receivedAmount = requireNotNull(parsePositiveMoneyOrNull(state.counterAmountText, toAccount.currency)) {
                "跨币种转账请填写实际到账金额 (${toAccount.currency.code})"
            }
            return engine.buildCrossCurrencyTransfer(
                meta = meta,
                fromAccountId = fromAccount.id,
                toAccountId = toAccount.id,
                paidAmount = outAmount,
                receivedAmount = receivedAmount,
                fee = fee,
                feeExpenseAccountId = feeAccountId,
                feeMode = state.transferFeeMode,
            )
        }

        val manualRateOverride = resolveManualRateOverride(
            currency = fromAccount.currency,
            needsManual = state.needsManualBaseRateInIntuitive,
            rateText = state.manualRateText,
            capturedAt = meta.occurredAt,
            baseCurrency = state.baseCurrency,
        )
        return engine.buildTransfer(
            meta = meta,
            fromAccountId = fromAccount.id,
            toAccountId = toAccount.id,
            amount = outAmount,
            fee = fee,
            feeExpenseAccountId = feeAccountId,
            feeMode = state.transferFeeMode,
            exchangeRateOverride = manualRateOverride,
        )
    }

    private fun recomputeDerivedState(state: TransactionEditorUiState): TransactionEditorUiState {
        // 1. Implied rate summary for cross-currency intuitive mode
        val impliedSummary = computeImpliedRateSummary(state)

        // 2. Live double-entry preview for intuitive mode
        val livePreview = computeIntuitivePreview(state)

        // 3. Split mode live imbalances via LedgerValidator.computeCurrencyImbalances(...)
        val previewPostings = buildPreviewPostingsForSplit(state.splitRows, state.baseCurrency)
        val imbalanceMap = runCatching {
            LedgerValidator.computeCurrencyImbalances(previewPostings)
        }.getOrElse { emptyMap() }

        val imbalances = imbalanceMap.entries.map { (ccy, minor) ->
            CurrencyImbalanceInfo(currency = ccy, imbalanceMinor = minor)
        }

        val validRowCount = previewPostings.size
        val nonZeroImbalances = imbalances.filterNot { it.isBalanced }
        val isMultiCurrencyHedgeable = nonZeroImbalances.size >= 2 &&
            state.autoHedgeMultiCurrency &&
            nonZeroImbalances.any { it.imbalanceMinor > 0L } &&
            nonZeroImbalances.any { it.imbalanceMinor < 0L }

        val splitReady = validRowCount >= 2 && (nonZeroImbalances.isEmpty() || isMultiCurrencyHedgeable)
        val splitStatus = when {
            validRowCount < 2 -> "请至少填写 2 条含有效金额与科目的分录"
            nonZeroImbalances.isEmpty() -> "借贷已完全平衡，可直接原子入账"
            isMultiCurrencyHedgeable -> "跨币种分录就绪：保存时将自动插入 Equity:Trading 对冲"
            else -> nonZeroImbalances.joinToString(" · ") { info ->
                "${info.currency.code} 差额 ${PrivacyAmountFormatter.format(info.money, showPositiveSign = true)}"
            }
        }

        return state.copy(
            impliedRateSummary = impliedSummary,
            livePreviewSummary = livePreview,
            currencyImbalances = imbalances,
            isSplitReadyToSave = splitReady,
            splitStatusSummary = splitStatus,
        )
    }

    private fun computeImpliedRateSummary(state: TransactionEditorUiState): String? {
        if (!state.isCrossCurrencyIntuitive) return null
        val srcMoney = parsePositiveMoneyOrNull(state.amountText, state.sourceCurrencyForIntuitive) ?: return null
        val dstMoney = parsePositiveMoneyOrNull(state.counterAmountText, state.counterCurrency) ?: return null
        if (srcMoney.currency == dstMoney.currency) return null

        val pair = runCatching {
            TradingAutoHedger.inferRatePair(srcMoney, dstMoney, state.occurredAt)
        }.getOrNull() ?: return null

        val forward = pair.paidToReceived
        val backward = pair.receivedToPaid
        return "反推成交汇率：1 ${forward.fromCurrency.code} = ${formatRateShort(forward.rateDecimal)} ${forward.toCurrency.code}" +
            "（1 ${backward.fromCurrency.code} = ${formatRateShort(backward.rateDecimal)} ${backward.toCurrency.code}）"
    }

    private fun computeIntuitivePreview(state: TransactionEditorUiState): String? {
        if (state.isSplitMode) return null
        val srcCcy = state.sourceCurrencyForIntuitive
        val amt = parsePositiveMoneyOrNull(state.amountText, srcCcy) ?: return null
        val acctName = state.selectedAccount?.name ?: return null

        return when (state.activeTab) {
            EditorTab.EXPENSE -> {
                val targetName = if (state.isReimbursable) {
                    "资产·待报销"
                } else {
                    findCategoryName(state.expenseCategoryGroups, state.selectedCategoryId) ?: "支出分类"
                }
                val paidText = if (state.isCrossCurrencyIntuitive) {
                    parsePositiveMoneyOrNull(state.counterAmountText, state.counterCurrency)
                        ?.let { PrivacyAmountFormatter.format(-it) } ?: "待填实付"
                } else {
                    PrivacyAmountFormatter.format(-amt)
                }
                "借: $targetName +${PrivacyAmountFormatter.format(amt)}  ·  贷: $acctName $paidText"
            }
            EditorTab.INCOME -> {
                val catName = findCategoryName(state.incomeCategoryGroups, state.selectedCategoryId) ?: "收入分类"
                val recText = if (state.isCrossCurrencyIntuitive) {
                    parsePositiveMoneyOrNull(state.counterAmountText, state.counterCurrency)
                        ?.let { PrivacyAmountFormatter.format(it, showPositiveSign = true) } ?: "待填实收"
                } else {
                    PrivacyAmountFormatter.format(amt, showPositiveSign = true)
                }
                "借: $acctName $recText  ·  贷: $catName ${PrivacyAmountFormatter.format(-amt)}"
            }
            EditorTab.TRANSFER -> {
                val toName = state.selectedTargetAccount?.name ?: "转入账户"
                val inText = if (state.isCrossCurrencyIntuitive) {
                    parsePositiveMoneyOrNull(state.counterAmountText, state.counterCurrency)
                        ?.let { PrivacyAmountFormatter.format(it, showPositiveSign = true) } ?: "待填到账"
                } else {
                    PrivacyAmountFormatter.format(amt, showPositiveSign = true)
                }
                "借: $toName $inText  ·  贷: $acctName ${PrivacyAmountFormatter.format(-amt)}"
            }
        }
    }

    private fun buildInitialSplitRowsFromIntuitive(state: TransactionEditorUiState): List<SplitRowDraft> {
        val srcCcy = state.sourceCurrencyForIntuitive
        val amtText = state.amountText
        val counterText = state.counterAmountText
        val acctId = state.selectedAccountId ?: state.operationalAccounts.firstOrNull()?.id

        return when (state.activeTab) {
            EditorTab.EXPENSE -> {
                val debitAccountId = if (state.isReimbursable) {
                    DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY
                } else {
                    state.selectedCategoryId ?: state.expenseCategoryGroups.firstOrNull()?.children?.firstOrNull()?.id
                }
                val creditCcy = state.selectedAccount?.currency ?: srcCcy
                listOf(
                    SplitRowDraft(
                        rowId = nextRowId(),
                        accountId = debitAccountId,
                        direction = PostingDirection.DEBIT,
                        currency = srcCcy,
                        amountText = amtText,
                    ),
                    SplitRowDraft(
                        rowId = nextRowId(),
                        accountId = acctId,
                        direction = PostingDirection.CREDIT,
                        currency = creditCcy,
                        amountText = if (state.isCrossCurrencyIntuitive && counterText.isNotBlank()) counterText else amtText,
                    ),
                )
            }
            EditorTab.INCOME -> {
                val creditAccountId = state.selectedCategoryId
                    ?: state.incomeCategoryGroups.firstOrNull()?.children?.firstOrNull()?.id
                val debitCcy = state.selectedAccount?.currency ?: srcCcy
                listOf(
                    SplitRowDraft(
                        rowId = nextRowId(),
                        accountId = acctId,
                        direction = PostingDirection.DEBIT,
                        currency = debitCcy,
                        amountText = if (state.isCrossCurrencyIntuitive && counterText.isNotBlank()) counterText else amtText,
                    ),
                    SplitRowDraft(
                        rowId = nextRowId(),
                        accountId = creditAccountId,
                        direction = PostingDirection.CREDIT,
                        currency = srcCcy,
                        amountText = amtText,
                    ),
                )
            }
            EditorTab.TRANSFER -> {
                val toCcy = state.selectedTargetAccount?.currency ?: srcCcy
                listOf(
                    SplitRowDraft(
                        rowId = nextRowId(),
                        accountId = state.selectedTargetAccountId,
                        direction = PostingDirection.DEBIT,
                        currency = toCcy,
                        amountText = if (state.isCrossCurrencyIntuitive && counterText.isNotBlank()) counterText else amtText,
                    ),
                    SplitRowDraft(
                        rowId = nextRowId(),
                        accountId = acctId,
                        direction = PostingDirection.CREDIT,
                        currency = srcCcy,
                        amountText = amtText,
                    ),
                )
            }
        }
    }

    private fun buildPreviewPostingsForSplit(
        rows: List<SplitRowDraft>,
        baseCurrency: Currency,
    ): List<Posting> = rows.mapIndexedNotNull { index, row ->
        val accountId = row.accountId ?: return@mapIndexedNotNull null
        val positiveMoney = parsePositiveMoneyOrNull(row.amountText, row.currency) ?: return@mapIndexedNotNull null
        val signedMinor = if (row.direction == PostingDirection.DEBIT) {
            positiveMoney.amountMinor
        } else {
            -positiveMoney.amountMinor
        }
        val snapshot = if (row.currency == baseCurrency) {
            ExchangeRateSnapshot.identity(baseCurrency)
        } else {
            LedgerRepository.fallbackReferenceRate(row.currency, baseCurrency)
        }
        Posting(
            id = "preview-$index",
            transactionId = "preview-tx",
            accountId = accountId,
            currency = row.currency,
            amountMinor = signedMinor,
            baseCurrency = baseCurrency,
            baseAmountMinor = if (row.currency == baseCurrency) signedMinor else snapshot.convert(Money(signedMinor, row.currency)).amountMinor,
            exchangeRateSnapshot = snapshot,
        )
    }

    private fun buildEffectiveRateTable(state: TransactionEditorUiState): ExchangeRateTable {
        var table = cachedRateTable
        for (currency in Currency.entries) {
            if (currency == state.baseCurrency) continue
            val manualText = if (state.isSplitMode) {
                state.splitReferenceRates[currency]
            } else if (state.sourceCurrencyForIntuitive == currency) {
                state.manualRateText
            } else {
                null
            }
            val parsedManual = manualText?.trim()?.takeIf { it.isNotEmpty() }?.let { txt ->
                runCatching {
                    ExchangeRateSnapshot.of(
                        fromCurrency = currency,
                        toCurrency = state.baseCurrency,
                        rateDecimal = txt,
                        source = ExchangeRateSource.USER_OVERRIDE,
                        capturedAt = state.occurredAt,
                    )
                }.getOrNull()
            }
            if (parsedManual != null) {
                table = table.withOverride(parsedManual)
            } else if (table.findRate(currency, state.baseCurrency) == null) {
                table = table.withOverride(
                    LedgerRepository.fallbackReferenceRate(
                        from = currency,
                        to = state.baseCurrency,
                        capturedAt = state.occurredAt,
                    ),
                )
            }
        }
        return table
    }

    private fun resolveManualRateOverride(
        currency: Currency,
        needsManual: Boolean,
        rateText: String,
        capturedAt: Long,
        baseCurrency: Currency,
    ): ExchangeRateSnapshot? {
        if (!needsManual || currency == baseCurrency) return null
        val trimmed = rateText.trim()
        if (trimmed.isEmpty()) return null
        return ExchangeRateSnapshot.of(
            fromCurrency = currency,
            toCurrency = baseCurrency,
            rateDecimal = trimmed,
            source = ExchangeRateSource.USER_OVERRIDE,
            capturedAt = capturedAt,
        )
    }

    private fun defaultRateTextFor(currency: Currency, table: ExchangeRateTable): String {
        if (currency == ledgerEngine.baseCurrency) return "1"
        val cached = table.findRate(currency, ledgerEngine.baseCurrency)
        return cached?.rateDecimal
            ?: LedgerRepository.fallbackReferenceRate(currency, ledgerEngine.baseCurrency).rateDecimal
    }

    private fun resolveCategorySelection(
        currentParentId: String?,
        currentCategoryId: String?,
        groups: List<io.github.kvtttba.droplet.feature.ledger.CategoryGroupOption>,
        preferredChildId: String,
    ): Pair<String?, String?> {
        if (groups.isEmpty()) return null to null
        val allIds = groups.flatMap { listOf(it.parent.id) + it.children.map { c -> c.id } }.toSet()
        if (currentCategoryId != null && currentCategoryId in allIds) {
            val parent = currentParentId?.takeIf { id -> groups.any { it.parent.id == id } }
                ?: groups.firstOrNull { g ->
                    g.parent.id == currentCategoryId || g.children.any { it.id == currentCategoryId }
                }?.parent?.id
            return parent to currentCategoryId
        }
        val preferredGroup = groups.firstOrNull { g -> g.children.any { it.id == preferredChildId } }
        if (preferredGroup != null) {
            return preferredGroup.parent.id to preferredChildId
        }
        val firstGroup = groups.first()
        return firstGroup.parent.id to (firstGroup.children.firstOrNull()?.id ?: firstGroup.parent.id)
    }

    private fun findCategoryName(
        groups: List<io.github.kvtttba.droplet.feature.ledger.CategoryGroupOption>,
        categoryId: String?,
    ): String? {
        if (categoryId == null) return null
        for (group in groups) {
            if (group.parent.id == categoryId) return group.parent.name
            val child = group.children.firstOrNull { it.id == categoryId }
            if (child != null) return "${group.parent.name}·${child.name}"
        }
        return null
    }

    private fun nextRowId(): String = "split-row-${++rowCounter}-${UUID.randomUUID().toString().take(6)}"

    private fun friendlyError(err: Throwable): String {
        val msg = err.message.orEmpty()
        return when {
            msg.isBlank() -> "保存失败，请检查金额与分录平衡"
            msg.contains("UnbalancedCurrency") -> "借贷尚未平衡，请核对各币种分录金额"
            msg.contains("AccountCurrencyMismatch") -> "账户币种与分录币种不一致"
            else -> msg
        }
    }

    private data class CatalogSnapshot(
        val allAccounts: List<Account>,
        val balances: List<io.github.kvtttba.droplet.core.database.dao.AccountCurrencyBalanceRow>,
        val tags: List<io.github.kvtttba.droplet.core.model.Tag>,
        val rateTable: ExchangeRateTable,
    )

    companion object {
        fun parsePositiveMoneyOrNull(raw: String, currency: Currency): Money? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val decimal = runCatching { BigDecimal(trimmed) }.getOrNull() ?: return null
            if (decimal.compareTo(BigDecimal.ZERO) <= 0) return null
            return runCatching { Money.fromBigDecimal(decimal, currency) }
                .getOrNull()
                ?.takeIf { it.isPositive }
        }

        fun sanitizeDecimalInput(raw: String, maxDecimals: Int = 4): String {
            val sb = StringBuilder()
            var seenDot = false
            var decimals = 0
            for (ch in raw) {
                when {
                    ch in '0'..'9' -> {
                        if (!seenDot || decimals < maxDecimals) {
                            sb.append(ch)
                            if (seenDot) decimals++
                        }
                    }
                    ch == '.' && !seenDot -> {
                        if (sb.isEmpty()) sb.append('0')
                        sb.append('.')
                        seenDot = true
                    }
                }
            }
            return sb.toString()
        }

        private fun formatRateShort(rateDecimal: String): String {
            val bd = runCatching { BigDecimal(rateDecimal) }.getOrNull() ?: return rateDecimal
            return bd.setScale(4, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        }
    }
}
