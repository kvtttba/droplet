package io.github.kvtttba.droplet.feature.ledger.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.feature.ledger.CategoryGroupOption
import io.github.kvtttba.droplet.feature.ledger.LedgerItemMapper
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ManageCatalogTab(val label: String) {
    EXPENSE_CATEGORIES("支出分类"),
    INCOME_CATEGORIES("收入分类"),
    TAGS("多标签"),
}

data class CategoryTagManagementUiState(
    val activeTab: ManageCatalogTab = ManageCatalogTab.EXPENSE_CATEGORIES,
    val showArchived: Boolean = false,
    val expenseGroups: List<CategoryGroupOption> = emptyList(),
    val incomeGroups: List<CategoryGroupOption> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val userMessage: String? = null,
)

data class CategorySaveRequest(
    val existing: Account? = null,
    val type: AccountType,
    val parentId: String?,
    val name: String,
    val icon: String,
    val color: String,
)

data class TagSaveRequest(
    val existing: Tag? = null,
    val name: String,
    val icon: String,
    val color: String,
)

@HiltViewModel
class CategoryTagManagementViewModel @Inject constructor(
    private val repository: LedgerRepository,
) : ViewModel() {

    private val activeTabFlow = MutableStateFlow(ManageCatalogTab.EXPENSE_CATEGORIES)
    private val showArchivedFlow = MutableStateFlow(false)
    private val userMessageFlow = MutableStateFlow<String?>(null)

    private val dataFlow = combine(
        repository.observeCategories(AccountType.EXPENSE, includeArchived = true),
        repository.observeCategories(AccountType.INCOME, includeArchived = true),
        repository.observeTags(includeArchived = true),
    ) { exp, inc, tags ->
        Triple(exp, inc, tags)
    }

    val uiState: StateFlow<CategoryTagManagementUiState> = combine(
        activeTabFlow,
        showArchivedFlow,
        dataFlow,
        userMessageFlow,
    ) { tab, showArchived, (allExp, allInc, allTags), message ->
        val filteredExp = if (showArchived) allExp else allExp.filterNot { it.isArchived }
        val filteredInc = if (showArchived) allInc else allInc.filterNot { it.isArchived }
        val filteredTags = if (showArchived) allTags else allTags.filterNot { it.isArchived }

        CategoryTagManagementUiState(
            activeTab = tab,
            showArchived = showArchived,
            expenseGroups = LedgerItemMapper.buildCategoryGroups(filteredExp),
            incomeGroups = LedgerItemMapper.buildCategoryGroups(filteredInc),
            tags = filteredTags,
            userMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CategoryTagManagementUiState(),
    )

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
        }
    }

    fun selectTab(tab: ManageCatalogTab) {
        activeTabFlow.value = tab
    }

    fun toggleShowArchived() {
        showArchivedFlow.update { !it }
    }

    fun saveCategory(
        request: CategorySaveRequest,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                val trimmed = request.name.trim()
                require(trimmed.isNotEmpty()) { "分类名称不能为空" }
                require(request.type == AccountType.EXPENSE || request.type == AccountType.INCOME) {
                    "分类仅支持支出或收入类型"
                }
                val now = System.currentTimeMillis()
                val existing = request.existing
                val account = Account(
                    id = existing?.id ?: "cat:${UUID.randomUUID()}",
                    name = trimmed,
                    type = request.type,
                    subType = AccountSubType.CATEGORY,
                    parentId = request.parentId,
                    currency = Currency.DEFAULT,
                    icon = request.icon,
                    color = request.color,
                    includeInNetWorth = false,
                    isArchived = existing?.isArchived ?: false,
                    isSystem = false,
                    sortOrder = existing?.sortOrder ?: 500,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
                repository.saveAccount(account = account, nowEpochMs = now)
            }.onSuccess {
                userMessageFlow.value = if (request.existing == null) {
                    "分类「${request.name.trim()}」已创建"
                } else {
                    "分类「${request.name.trim()}」已更新"
                }
                onComplete(true)
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "保存分类失败"
                onComplete(false)
            }
        }
    }

    fun setCategoryArchived(categoryId: String, archived: Boolean) {
        viewModelScope.launch {
            runCatching {
                repository.setAccountArchived(categoryId, archived)
            }.onSuccess {
                userMessageFlow.value = if (archived) "分类已归档" else "分类已恢复"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "操作失败"
            }
        }
    }

    fun deleteCategoryIfUnused(categoryId: String) {
        viewModelScope.launch {
            repository.deleteAccountIfUnused(categoryId)
                .onSuccess { userMessageFlow.value = "分类已删除" }
                .onFailure { err -> userMessageFlow.value = err.message ?: "删除失败" }
        }
    }

    fun saveTag(
        request: TagSaveRequest,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = request.existing
            val tag = Tag(
                id = existing?.id ?: "tag:${UUID.randomUUID()}",
                name = request.name.trim().ifEmpty {
                    userMessageFlow.value = "标签名称不能为空"
                    onComplete(false)
                    return@launch
                },
                color = request.color,
                icon = request.icon,
                sortOrder = existing?.sortOrder ?: 100,
                isArchived = existing?.isArchived ?: false,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            repository.saveTag(tag)
                .onSuccess {
                    userMessageFlow.value = if (existing == null) {
                        "标签「#${tag.name}」已创建"
                    } else {
                        "标签「#${tag.name}」已更新"
                    }
                    onComplete(true)
                }
                .onFailure { err ->
                    userMessageFlow.value = err.message ?: "保存标签失败"
                    onComplete(false)
                }
        }
    }

    fun setTagArchived(tagId: String, archived: Boolean) {
        viewModelScope.launch {
            runCatching {
                repository.setTagArchived(tagId, archived)
            }.onSuccess {
                userMessageFlow.value = if (archived) "标签已归档" else "标签已恢复"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "操作失败"
            }
        }
    }

    fun deleteTag(tagId: String) {
        viewModelScope.launch {
            runCatching {
                repository.deleteTag(tagId)
            }.onSuccess {
                userMessageFlow.value = "标签已删除"
            }.onFailure { err ->
                userMessageFlow.value = err.message ?: "删除失败"
            }
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }
}
