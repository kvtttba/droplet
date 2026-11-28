package io.github.kvtttba.droplet.feature.aicapture

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.ai.AiDraftBatchCodec
import io.github.kvtttba.droplet.core.ai.AiDraftTransactionMapper
import io.github.kvtttba.droplet.core.ai.AiParsedTransactionCandidate
import io.github.kvtttba.droplet.core.ai.AiUncertainFieldKeys
import io.github.kvtttba.droplet.core.database.repository.AiDraftRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AiDraftReviewViewModel @Inject constructor(
    private val aiDraftRepository: AiDraftRepository,
    private val ledgerRepository: LedgerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val draftId: String = savedStateHandle[NAV_ARG_DRAFT_ID] ?: ""

    private val _uiState = MutableStateFlow(AiDraftReviewUiState(draftId = draftId))
    val uiState: StateFlow<AiDraftReviewUiState> = _uiState.asStateFlow()

    private var allAccountsById: Map<String, Account> = emptyMap()
    private var hasHydratedItems: Boolean = false

    init {
        if (draftId.isNotBlank()) {
            viewModelScope.launch {
                ledgerRepository.ensureSeeded()
                combine(
                    aiDraftRepository.observeDraft(draftId),
                    ledgerRepository.observeAllAccounts(includeArchived = false, includeSystem = true),
                    ledgerRepository.observeTags(includeArchived = false),
                ) { draft, accounts, tags ->
                    Triple(draft, accounts, tags)
                }.collect { (draft, accounts, tags) ->
                    val accountsMap = accounts.associateBy { it.id }
                    allAccountsById = accountsMap

                    val operationalAccounts = accounts
                        .filter { !it.isArchived && !it.isSystem }
                        .filter { it.type == AccountType.ASSET || it.type == AccountType.LIABILITY }
                        .filter { it.subType != AccountSubType.CATEGORY }
                        .sortedWith(compareBy<Account> { it.sortOrder }.thenBy { it.name })

                    val expenseCategories = buildCategoryOptions(accounts, AccountType.EXPENSE)
                    val incomeCategories = buildCategoryOptions(accounts, AccountType.INCOME)
                    val activeTags = tags.filterNot { it.isArchived }
                        .sortedWith(compareBy<Tag> { it.sortOrder }.thenBy { it.name })

                    if (draft == null) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = "未找到对应草稿，可能已被删除",
                            )
                        }
                        return@collect
                    }

                    val attachments = AiDraftBatchCodec.decodeAttachments(draft.attachmentPathsJson)
                    val nextItems = if (!hasHydratedItems) {
                        hasHydratedItems = true
                        val batch = AiDraftBatchCodec.decodeBatchOrNull(draft.parsedItemsJson)
                        val candidates = batch?.items.orEmpty()
                        candidates.mapIndexed { index, candidate ->
                            candidateToEditorItem(
                                candidate = candidate,
                                accountsById = accountsMap,
                                autoExpand = candidates.size == 1 && candidate.needsUserReview ||
                                    AiDraftTransactionMapper.validateCandidate(candidate, accountsMap) != null,
                                localId = "item_${index}_${UUID.randomUUID().toString().take(8)}",
                            )
                        }
                    } else {
                        _uiState.value.items.map { revalidateEditorItem(it, accountsMap) }
                    }

                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            draftId = draft.id,
                            inputType = draft.inputType,
                            draftStatus = draft.status,
                            rawText = draft.rawText,
                            attachments = attachments,
                            items = nextItems,
                            operationalAccounts = operationalAccounts,
                            expenseCategories = expenseCategories,
                            incomeCategories = incomeCategories,
                            availableTags = activeTags,
                        )
                    }
                }
            }
        } else {
            _uiState.update { it.copy(isLoading = false, errorMessage = "缺少草稿 ID 参数") }
        }
    }

    fun toggleItemSelected(localId: String) {
        updateItem(localId) { it.copy(selected = !it.selected) }
    }

    fun toggleItemExpanded(localId: String) {
        updateItem(localId) { it.copy(expanded = !it.expanded) }
    }

    fun updateItemType(localId: String, type: TransactionDisplayType) {
        updateItem(localId) { item ->
            val nextCategory = when (type) {
                TransactionDisplayType.EXPENSE -> _uiState.value.expenseCategories.firstOrNull()
                TransactionDisplayType.INCOME -> _uiState.value.incomeCategories.firstOrNull()
                else -> null
            }
            val nextTargetId = if (type == TransactionDisplayType.TRANSFER) {
                item.matchedTargetAccountId
                    ?: _uiState.value.operationalAccounts.firstOrNull { it.id != item.matchedAccountId }?.id
            } else {
                null
            }
            val nextTargetName = nextTargetId?.let { allAccountsById[it]?.name }
            item.copy(
                displayType = type,
                matchedCategoryId = nextCategory?.id,
                matchedCategoryPath = nextCategory?.displayPath,
                matchedTargetAccountId = nextTargetId,
                matchedTargetAccountName = nextTargetName,
                uncertainFields = item.uncertainFields - AiUncertainFieldKeys.DISPLAY_TYPE,
            )
        }
    }

    fun updateItemAmount(localId: String, rawInput: String) {
        val sanitized = rawInput.filter { it.isDigit() || it == '.' }
        updateItem(localId) { item ->
            val parsed = DraftCandidateEditorItem.parsePositiveDecimalMoney(sanitized, item.currency)
            item.copy(
                amountInput = sanitized,
                parsedAmount = parsed,
                uncertainFields = if (parsed != null) {
                    item.uncertainFields - AiUncertainFieldKeys.AMOUNT
                } else {
                    item.uncertainFields + AiUncertainFieldKeys.AMOUNT
                },
            )
        }
    }

    fun updateItemCurrency(localId: String, currency: Currency) {
        updateItem(localId) { item ->
            val parsed = DraftCandidateEditorItem.parsePositiveDecimalMoney(item.amountInput, currency)
            item.copy(
                currency = currency,
                parsedAmount = parsed,
                uncertainFields = item.uncertainFields - AiUncertainFieldKeys.CURRENCY,
            )
        }
    }

    fun updateItemCategory(localId: String, categoryId: String) {
        val option = (_uiState.value.expenseCategories + _uiState.value.incomeCategories)
            .firstOrNull { it.id == categoryId }
        updateItem(localId) { item ->
            item.copy(
                matchedCategoryId = categoryId,
                matchedCategoryPath = option?.displayPath ?: allAccountsById[categoryId]?.name,
                uncertainFields = item.uncertainFields - AiUncertainFieldKeys.CATEGORY_PATH,
            )
        }
    }

    fun updateItemAccount(localId: String, accountId: String) {
        val account = allAccountsById[accountId]
        updateItem(localId) { item ->
            item.copy(
                matchedAccountId = accountId,
                matchedAccountName = account?.name,
                uncertainFields = item.uncertainFields - AiUncertainFieldKeys.ACCOUNT_NAME,
            )
        }
    }

    fun updateItemTargetAccount(localId: String, targetAccountId: String) {
        val account = allAccountsById[targetAccountId]
        updateItem(localId) { item ->
            item.copy(
                matchedTargetAccountId = targetAccountId,
                matchedTargetAccountName = account?.name,
                uncertainFields = item.uncertainFields - AiUncertainFieldKeys.TARGET_ACCOUNT_NAME,
            )
        }
    }

    fun updateItemPayee(localId: String, payee: String) {
        updateItem(localId) { item ->
            item.copy(
                payee = payee,
                uncertainFields = item.uncertainFields - AiUncertainFieldKeys.PAYEE,
            )
        }
    }

    fun updateItemNote(localId: String, note: String) {
        updateItem(localId) { item ->
            item.copy(
                note = note,
                uncertainFields = item.uncertainFields - AiUncertainFieldKeys.NOTE,
            )
        }
    }

    fun toggleItemTag(localId: String, tagId: String) {
        updateItem(localId) { item ->
            val nextTags = if (tagId in item.selectedTagIds) {
                item.selectedTagIds - tagId
            } else {
                item.selectedTagIds + tagId
            }
            item.copy(
                selectedTagIds = nextTags,
                uncertainFields = item.uncertainFields - AiUncertainFieldKeys.TAGS,
            )
        }
    }

    fun addBlankItem() {
        val state = _uiState.value
        val now = System.currentTimeMillis()
        val defaultCategory = state.expenseCategories.firstOrNull()
        val defaultAccount = state.operationalAccounts.firstOrNull()
        val newItem = revalidateEditorItem(
            item = DraftCandidateEditorItem(
                localId = "manual_${UUID.randomUUID().toString().take(8)}",
                selected = true,
                expanded = true,
                displayType = TransactionDisplayType.EXPENSE,
                amountInput = "",
                parsedAmount = null,
                currency = defaultAccount?.currency ?: Currency.CNY,
                occurredAtEpochMs = now,
                occurredAtLabel = DraftCandidateEditorItem.formatEpochMs(now),
                matchedCategoryId = defaultCategory?.id,
                matchedCategoryPath = defaultCategory?.displayPath,
                matchedAccountId = defaultAccount?.id,
                matchedAccountName = defaultAccount?.name,
                confidenceDecimal = "1.00",
                uncertainFields = setOf(AiUncertainFieldKeys.AMOUNT),
            ),
            accountsById = allAccountsById,
        )
        _uiState.update { it.copy(items = it.items + newItem, errorMessage = null) }
    }

    fun removeItem(localId: String) {
        _uiState.update { state ->
            state.copy(
                items = state.items.filterNot { it.localId == localId },
                errorMessage = null,
            )
        }
    }

    fun setAttachReceipts(enabled: Boolean) {
        _uiState.update { it.copy(attachReceiptsToTransactions = enabled) }
    }

    fun saveEditsToDraft() {
        val candidates = _uiState.value.items.mapNotNull { it.toCandidateOrNull() }
        if (candidates.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "至少需要 1 笔有效金额的草稿条目才能保存") }
            return
        }
        viewModelScope.launch {
            aiDraftRepository.saveEditedCandidates(draftId = draftId, candidates = candidates)
            _uiState.update { it.copy(userMessage = "草稿修改已保存") }
        }
    }

    fun commitSelectedBatch() {
        val state = _uiState.value
        if (state.isCommitting) return
        val selected = state.selectedItems
        if (selected.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "请至少勾选 1 笔草稿再确认入账") }
            return
        }

        // Validate every selected item before hitting SQLite so UI expands the exact offending row
        val firstInvalid = selected.firstOrNull { it.validationError != null || it.parsedAmount == null }
        if (firstInvalid != null) {
            val msg = firstInvalid.validationError ?: "请输入有效的正数金额"
            _uiState.update { current ->
                current.copy(
                    errorMessage = msg,
                    items = current.items.map { item ->
                        if (item.localId == firstInvalid.localId) item.copy(expanded = true) else item
                    },
                )
            }
            return
        }

        val candidates = selected.mapNotNull { it.toCandidateOrNull() }
        if (candidates.size != selected.size) {
            _uiState.update { it.copy(errorMessage = "存在金额无效的条目，请核对后重试") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isCommitting = true, errorMessage = null) }
            aiDraftRepository.commitDraftBatch(
                draftId = state.draftId,
                selectedCandidates = candidates,
                attachReceiptsToTransactions = state.attachReceiptsToTransactions,
            ).fold(
                onSuccess = { result ->
                    val attachHint = if (result.attachedReceiptCount > 0) "（已关联票据图）" else ""
                    _uiState.update {
                        it.copy(
                            isCommitting = false,
                            committedSummaryMessage = "已原子入账 ${result.committedTransactionIds.size} 笔流水$attachHint",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isCommitting = false,
                            errorMessage = error.message ?: "批量入账失败，已自动回滚",
                        )
                    }
                },
            )
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun consumeCommittedNavigation() {
        _uiState.update { it.copy(committedSummaryMessage = null) }
    }

    private fun updateItem(
        localId: String,
        transform: (DraftCandidateEditorItem) -> DraftCandidateEditorItem,
    ) {
        _uiState.update { state ->
            state.copy(
                errorMessage = null,
                items = state.items.map { item ->
                    if (item.localId == localId) {
                        revalidateEditorItem(transform(item), allAccountsById)
                    } else {
                        item
                    }
                },
            )
        }
    }

    private fun candidateToEditorItem(
        candidate: AiParsedTransactionCandidate,
        accountsById: Map<String, Account>,
        autoExpand: Boolean,
        localId: String,
    ): DraftCandidateEditorItem {
        val rawItem = DraftCandidateEditorItem(
            localId = localId,
            selected = true,
            expanded = autoExpand,
            displayType = candidate.displayType,
            amountInput = formatEditableAmount(candidate.amount),
            parsedAmount = candidate.amount,
            currency = candidate.currency,
            occurredAtEpochMs = candidate.occurredAtEpochMs,
            occurredAtLabel = DraftCandidateEditorItem.formatEpochMs(candidate.occurredAtEpochMs),
            rawCategoryPath = candidate.rawCategoryPath,
            matchedCategoryId = candidate.matchedCategoryId,
            matchedCategoryPath = candidate.matchedCategoryPath
                ?: candidate.matchedCategoryId?.let { resolveCategoryPath(it, accountsById) },
            rawAccountName = candidate.rawAccountName,
            matchedAccountId = candidate.matchedAccountId,
            matchedAccountName = candidate.matchedAccountName
                ?: candidate.matchedAccountId?.let { accountsById[it]?.name },
            rawTargetAccountName = candidate.rawTargetAccountName,
            matchedTargetAccountId = candidate.matchedTargetAccountId,
            matchedTargetAccountName = candidate.matchedTargetAccountName
                ?: candidate.matchedTargetAccountId?.let { accountsById[it]?.name },
            payee = candidate.payee.orEmpty(),
            note = candidate.note.orEmpty(),
            selectedTagIds = candidate.matchedTagIds,
            confidenceDecimal = candidate.confidenceDecimal,
            uncertainFields = candidate.uncertainFields,
            warnings = candidate.warnings,
        )
        return revalidateEditorItem(rawItem, accountsById)
    }

    private fun revalidateEditorItem(
        item: DraftCandidateEditorItem,
        accountsById: Map<String, Account>,
    ): DraftCandidateEditorItem {
        val candidate = item.toCandidateOrNull()
        val error = if (candidate == null) {
            "请输入大于 0 的有效金额"
        } else {
            AiDraftTransactionMapper.validateCandidate(
                candidate = candidate,
                accountsById = accountsById,
            )
        }
        return item.copy(validationError = error)
    }

    private fun buildCategoryOptions(
        accounts: List<Account>,
        type: AccountType,
    ): List<ReviewCategoryOption> {
        val active = accounts
            .filter { !it.isArchived && !it.isSystem && it.subType == AccountSubType.CATEGORY && it.type == type }
            .sortedWith(compareBy<Account> { it.sortOrder }.thenBy { it.name })
        val byId = active.associateBy { it.id }
        val parentIdsWithChildren = active.mapNotNull { it.parentId }.toSet()

        return active.mapNotNull { cat ->
            if (cat.parentId != null) {
                val parentName = byId[cat.parentId]?.name
                val path = if (parentName != null) "$parentName/${cat.name}" else cat.name
                ReviewCategoryOption(id = cat.id, displayPath = path, leafName = cat.name)
            } else if (cat.id !in parentIdsWithChildren) {
                ReviewCategoryOption(id = cat.id, displayPath = cat.name, leafName = cat.name)
            } else {
                null
            }
        }
    }

    private fun resolveCategoryPath(
        categoryId: String,
        accountsById: Map<String, Account>,
    ): String? {
        val cat = accountsById[categoryId] ?: return null
        val parent = cat.parentId?.let { accountsById[it] }
        return if (parent != null) "${parent.name}/${cat.name}" else cat.name
    }

    private fun formatEditableAmount(money: Money): String {
        val stripped = money.toBigDecimal().stripTrailingZeros()
        val scale = stripped.scale().coerceIn(2, Money.SCALE)
        return money.toBigDecimal().setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }

    companion object {
        const val NAV_ARG_DRAFT_ID = "draftId"
    }
}
