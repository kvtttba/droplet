package io.github.kvtttba.droplet.feature.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.database.dao.TagFilterMode
import io.github.kvtttba.droplet.core.domain.PrivacyAmountFormatter
import io.github.kvtttba.droplet.core.domain.debt.ExpenseRefundSummary
import io.github.kvtttba.droplet.core.domain.debt.ReimbursementItemSummary
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.component.PrivacyToggleIconButton
import io.github.kvtttba.droplet.core.ui.icon.DropletIconRegistry
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.tabularNums

@Composable
fun LedgerRoute(
    onToggleAmountsHidden: () -> Unit,
    onCreateTransaction: () -> Unit,
    onEditTransaction: (String) -> Unit,
    onOpenReimbursementList: () -> Unit = {},
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    LedgerScreen(
        uiState = uiState,
        onToggleAmountsHidden = onToggleAmountsHidden,
        onPreviousMonth = viewModel::previousMonth,
        onNextMonth = viewModel::nextMonth,
        onResetCurrentMonth = viewModel::resetToCurrentMonth,
        onKeywordChange = viewModel::onKeywordChange,
        onToggleTagFilter = viewModel::onToggleTagFilter,
        onTagFilterModeChange = viewModel::onTagFilterModeChange,
        onSelectAccountFilter = viewModel::onSelectAccountFilter,
        onSelectCategoryFilter = viewModel::onSelectCategoryFilter,
        onTogglePendingReimburseOnly = viewModel::onTogglePendingReimburseOnly,
        onToggleIncludeVoid = viewModel::onToggleIncludeVoid,
        onClearAllFilters = viewModel::onClearAllFilters,
        onCreateTransaction = onCreateTransaction,
        onEditTransaction = onEditTransaction,
        onOpenReimbursementList = onOpenReimbursementList,
        onRecordRefund = viewModel::recordRefund,
        onVoidTransaction = viewModel::voidTransaction,
        onRestoreTransaction = viewModel::restoreTransaction,
        onDeleteTransaction = viewModel::deleteTransaction,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    uiState: LedgerUiState,
    onToggleAmountsHidden: () -> Unit,
    onPreviousMonth: () -> Unit = {},
    onNextMonth: () -> Unit = {},
    onResetCurrentMonth: () -> Unit = {},
    onKeywordChange: (String) -> Unit = {},
    onToggleTagFilter: (String) -> Unit = {},
    onTagFilterModeChange: (TagFilterMode) -> Unit = {},
    onSelectAccountFilter: (String?) -> Unit = {},
    onSelectCategoryFilter: (String?) -> Unit = {},
    onTogglePendingReimburseOnly: () -> Unit = {},
    onToggleIncludeVoid: () -> Unit = {},
    onClearAllFilters: () -> Unit = {},
    onCreateTransaction: () -> Unit = {},
    onEditTransaction: (String) -> Unit = {},
    onOpenReimbursementList: () -> Unit = {},
    onRecordRefund: (
        expenseTxId: String,
        refundToAccountId: String?,
        refundAmountText: String,
        expenseReductionText: String?,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, _, _, _, cb -> cb(null) },
    onVoidTransaction: (String) -> Unit = {},
    onRestoreTransaction: (String) -> Unit = {},
    onDeleteTransaction: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(uiState.filterState.keyword.isNotEmpty()) }
    var inspectingItemId by remember { mutableStateOf<String?>(null) }
    var refundingItemId by remember { mutableStateOf<String?>(null) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }

    val allVisibleItems = remember(uiState.dayGroups) {
        uiState.dayGroups.flatMap { it.items }
    }
    val inspectingItem = remember(inspectingItemId, allVisibleItems) {
        inspectingItemId?.let { id -> allVisibleItems.firstOrNull { it.id == id } }
    }
    val refundingItem = remember(refundingItemId, allVisibleItems) {
        refundingItemId?.let { id -> allVisibleItems.firstOrNull { it.id == id } }
    }

    val selectedAccountName = remember(uiState.filterState.selectedAccountId, uiState.availableAccounts) {
        uiState.availableAccounts.firstOrNull { it.id == uiState.filterState.selectedAccountId }?.name
    }
    val selectedCategoryName = remember(
        uiState.filterState.selectedCategoryId,
        uiState.expenseCategoryGroups,
        uiState.incomeCategoryGroups,
    ) {
        val allCats = (uiState.expenseCategoryGroups + uiState.incomeCategoryGroups)
            .flatMap { listOf(it.parent) + it.children }
        allCats.firstOrNull { it.id == uiState.filterState.selectedCategoryId }?.name
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_ledger"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Top Header + Month Switcher + Privacy Toggle
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Droplet 记账",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "本地复式账本 · 纯净离线",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MonthSwitcherPill(
                        monthLabel = uiState.monthLabel,
                        onPreviousMonth = onPreviousMonth,
                        onNextMonth = onNextMonth,
                        onClickLabel = onResetCurrentMonth,
                    )
                    PrivacyToggleIconButton(
                        amountsHidden = amountsHidden,
                        onToggle = onToggleAmountsHidden,
                    )
                }
            }
        }

        // 2. Monthly Summary Hero Card
        item(key = "summary_card") {
            val expenseDisplayMoney = if (uiState.monthlyExpense.isPositive) {
                -uiState.monthlyExpense
            } else {
                uiState.monthlyExpense
            }
            DropletHeroCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (uiState.filterState.hasExtraFilters) {
                            "筛选结余 (${uiState.baseCurrency.code})"
                        } else {
                            "本月结余 (${uiState.baseCurrency.code})"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                    )
                    DropletPillBadge(
                        text = if (uiState.transactionCount > 0) {
                            "${uiState.transactionCount} 笔流水"
                        } else {
                            "单账本 · 多标签"
                        },
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                PrivacyAmountText(
                    money = uiState.monthlyBalance,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("ledger_monthly_balance"),
                )
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (uiState.filterState.hasExtraFilters) "筛选收入" else "本月收入",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        PrivacyAmountText(
                            money = uiState.monthlyIncome,
                            style = MaterialTheme.typography.titleMedium,
                            color = semantic.income,
                            showPositiveSign = uiState.monthlyIncome.isPositive,
                            modifier = Modifier.testTag("ledger_monthly_income"),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (uiState.filterState.hasExtraFilters) "筛选支出" else "本月支出",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        PrivacyAmountText(
                            money = expenseDisplayMoney,
                            style = MaterialTheme.typography.titleMedium,
                            color = semantic.expense,
                            modifier = Modifier.testTag("ledger_monthly_expense"),
                        )
                    }
                }
            }
        }

        // 3. Inline Search Bar (collapsible)
        if (showSearchBar || uiState.filterState.keyword.isNotEmpty()) {
            item(key = "search_bar") {
                OutlinedTextField(
                    value = uiState.filterState.keyword,
                    onValueChange = onKeywordChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ledger_search_input"),
                    placeholder = {
                        Text(
                            text = "搜索商户、备注、分类、账户或标签...",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (uiState.filterState.keyword.isNotEmpty()) {
                                    onKeywordChange("")
                                } else {
                                    showSearchBar = false
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "清除搜索",
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                )
            }
        }

        // 4. Multi-Dimensional Filter Bar
        item(key = "filter_bar") {
            LazyRow(
                modifier = Modifier.testTag("ledger_filter_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item(key = "btn_search") {
                    Surface(
                        shape = CircleShape,
                        color = if (showSearchBar || uiState.filterState.keyword.isNotEmpty()) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { showSearchBar = !showSearchBar }
                            .testTag("ledger_search_toggle"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "搜索流水",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "搜索",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item(key = "btn_filter_sheet") {
                    val activeCount = uiState.filterState.activeFilterCount
                    Surface(
                        shape = CircleShape,
                        color = if (activeCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { showFilterSheet = true }
                            .testTag("ledger_open_filter_sheet"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = DropletIcons.Tune,
                                contentDescription = "高级筛选",
                                modifier = Modifier.size(16.dp),
                                tint = if (activeCount > 0) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = if (activeCount > 0) "筛选 ($activeCount)" else "筛选",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (activeCount > 0) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }

                item(key = "chip_all") {
                    FilterChip(
                        selected = !uiState.filterState.hasExtraFilters,
                        onClick = onClearAllFilters,
                        label = { Text("全部") },
                        modifier = Modifier.testTag("ledger_filter_all"),
                    )
                }

                if (uiState.filterState.selectedTagIds.size >= 2) {
                    item(key = "chip_tag_mode") {
                        val isAll = uiState.filterState.tagFilterMode == TagFilterMode.ALL
                        FilterChip(
                            selected = true,
                            onClick = {
                                onTagFilterModeChange(
                                    if (isAll) TagFilterMode.ANY else TagFilterMode.ALL,
                                )
                            },
                            label = { Text(if (isAll) "标签交集(全含)" else "标签并集(任一)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                            modifier = Modifier.testTag("ledger_tag_mode_toggle"),
                        )
                    }
                }

                if (selectedAccountName != null) {
                    item(key = "active_account") {
                        FilterChip(
                            selected = true,
                            onClick = { onSelectAccountFilter(null) },
                            label = { Text("账户: $selectedAccountName ×") },
                        )
                    }
                }

                if (selectedCategoryName != null) {
                    item(key = "active_category") {
                        FilterChip(
                            selected = true,
                            onClick = { onSelectCategoryFilter(null) },
                            label = { Text("分类: $selectedCategoryName ×") },
                        )
                    }
                }

                item(key = "chip_reimburse") {
                    FilterChip(
                        selected = uiState.filterState.pendingReimburseOnly,
                        onClick = onTogglePendingReimburseOnly,
                        label = { Text("待报销") },
                        modifier = Modifier.testTag("ledger_filter_reimburse"),
                    )
                }

                items(uiState.availableTags, key = { "tag_${it.id}" }) { tag ->
                    val selected = tag.id in uiState.filterState.selectedTagIds
                    FilterChip(
                        selected = selected,
                        onClick = { onToggleTagFilter(tag.id) },
                        label = { Text("#${tag.name}") },
                        modifier = Modifier.testTag("ledger_tag_chip_${tag.name}"),
                    )
                }
            }
        }

        // 5. Daily Grouped Transactions OR Empty State
        if (uiState.dayGroups.isEmpty() && !uiState.isLoading) {
            item(key = "empty_state") {
                LedgerEmptyStateCard(
                    hasFilters = uiState.filterState.hasExtraFilters,
                    monthLabel = uiState.monthLabel,
                    onCreateTransaction = onCreateTransaction,
                    onClearFilters = onClearAllFilters,
                )
            }
        } else {
            uiState.dayGroups.forEach { dayGroup ->
                item(key = "day_header_${dayGroup.date}") {
                    DropletSectionHeader(
                        title = dayGroup.dateHeader,
                        trailing = formatDaySummaryTrailing(
                            dayExpense = dayGroup.dayExpenseBase,
                            dayIncome = dayGroup.dayIncomeBase,
                            itemCount = dayGroup.items.size,
                            hidden = amountsHidden,
                        ),
                    )
                }
                item(key = "day_card_${dayGroup.date}") {
                    DropletCard(contentPadding = PaddingValues(vertical = 4.dp, horizontal = 14.dp)) {
                        dayGroup.items.forEachIndexed { index, item ->
                            LedgerTransactionRow(
                                item = item,
                                onClick = { inspectingItemId = item.id },
                            )
                            if (index != dayGroup.items.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 52.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        LedgerFilterBottomSheet(
            uiState = uiState,
            onToggleTagFilter = onToggleTagFilter,
            onTagFilterModeChange = onTagFilterModeChange,
            onSelectAccountFilter = onSelectAccountFilter,
            onSelectCategoryFilter = onSelectCategoryFilter,
            onTogglePendingReimburseOnly = onTogglePendingReimburseOnly,
            onToggleIncludeVoid = onToggleIncludeVoid,
            onClearAll = onClearAllFilters,
            onDismiss = { showFilterSheet = false },
        )
    }

    val activeRefundingItem = refundingItem
    if (activeRefundingItem == null) {
        inspectingItem?.let { detail ->
            TransactionDetailBottomSheet(
                item = detail,
                accountsById = uiState.accountsById,
                onDismiss = { inspectingItemId = null },
                onEdit = {
                    inspectingItemId = null
                    onEditTransaction(detail.id)
                },
                onRequestRefund = {
                    refundingItemId = detail.id
                },
                onOpenReimbursementList = {
                    inspectingItemId = null
                    onOpenReimbursementList()
                },
                onVoid = {
                    inspectingItemId = null
                    onVoidTransaction(detail.id)
                },
                onRestore = {
                    inspectingItemId = null
                    onRestoreTransaction(detail.id)
                },
                onRequestDelete = {
                    inspectingItemId = null
                    confirmDeleteId = detail.id
                },
            )
        }
    }

    activeRefundingItem?.let { target ->
        val summary = target.refundSummary
        if (summary != null && summary.canRefundMore) {
            RecordRefundBottomSheet(
                itemTitle = target.title,
                summary = summary,
                refundEligibleAccounts = uiState.refundEligibleAccounts.ifEmpty { uiState.availableAccounts },
                isSubmitting = uiState.isSubmittingRefund,
                onDismiss = { refundingItemId = null },
                onSubmit = { refundToId, refundAmtText, reductionText, note, cb ->
                    onRecordRefund(
                        target.id,
                        refundToId,
                        refundAmtText,
                        reductionText,
                        note,
                        cb,
                    )
                },
            )
        }
    }

    confirmDeleteId?.let { txId ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("确认删除流水") },
            text = { Text("删除后将同时移除该笔交易的全部复式分录与标签关联，并实时更新月度汇总与账户余额。如希望保留审计记录，可改用「作废」。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDeleteId = null
                        onDeleteTransaction(txId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.testTag("confirm_delete_tx_button"),
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun MonthSwitcherPill(
    monthLabel: String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onClickLabel: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        ) {
            IconButton(
                onClick = onPreviousMonth,
                modifier = Modifier
                    .size(30.dp)
                    .testTag("ledger_prev_month"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = "上个月",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onClickLabel)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .testTag("ledger_month_label"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = DropletIcons.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = monthLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = onNextMonth,
                modifier = Modifier
                    .size(30.dp)
                    .testTag("ledger_next_month"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "下个月",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LedgerTransactionRow(
    item: LedgerTransactionItem,
    onClick: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val isVoid = item.status == TransactionStatus.VOID
    val accentColor = DropletIconRegistry.parseColor(
        hex = item.colorHex,
        fallback = when (item.amountTone) {
            LedgerAmountTone.INCOME -> semantic.income
            LedgerAmountTone.EXPENSE -> MaterialTheme.colorScheme.primary
            LedgerAmountTone.NEUTRAL -> MaterialTheme.colorScheme.tertiary
            LedgerAmountTone.MUTED -> MaterialTheme.colorScheme.outline
        },
    )
    val iconTint = if (isVoid) MaterialTheme.colorScheme.outline else accentColor
    val iconContainer = iconTint.copy(alpha = if (isVoid) 0.12f else 0.14f)
    val amountColor = when (item.amountTone) {
        LedgerAmountTone.INCOME -> semantic.income
        LedgerAmountTone.EXPENSE -> MaterialTheme.colorScheme.onSurface
        LedgerAmountTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        LedgerAmountTone.MUTED -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag("ledger_tx_row_${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DropletIconRegistry.resolve(item.iconKey),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        textDecoration = if (isVoid) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                    color = if (isVoid) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                item.statusBadge?.let { badge ->
                    DropletPillBadge(
                        text = badge,
                        containerColor = when {
                            isVoid -> MaterialTheme.colorScheme.surfaceContainerHighest
                            item.isReimburseBadge -> semantic.warningContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = when {
                            isVoid -> MaterialTheme.colorScheme.onSurfaceVariant
                            item.isReimburseBadge -> semantic.warning
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${item.timeLabel} · ${item.subtitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                item.tags.take(2).forEach { tag ->
                    val tagColor = DropletIconRegistry.parseColor(
                        hex = tag.color,
                        fallback = MaterialTheme.colorScheme.primary,
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = tagColor.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = "#${tag.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = tagColor,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                if (item.tags.size > 2) {
                    Text(
                        text = "+${item.tags.size - 2}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            PrivacyAmountText(
                money = item.primaryAmount,
                style = MaterialTheme.typography.titleSmall.copy(
                    textDecoration = if (isVoid) TextDecoration.LineThrough else TextDecoration.None,
                ),
                color = amountColor,
                showPositiveSign = item.showPositiveSign,
            )
            val subAmount = item.crossCurrencyTargetAmount ?: item.secondaryBaseAmount
            if (item.refundSummary?.hasRefunds == true) {
                val hidden = LocalAmountsHidden.current
                val netFormatted = PrivacyAmountFormatter.format(
                    money = item.refundSummary.netExpenseAmount,
                    hidden = hidden,
                )
                Text(
                    text = "净支出 $netFormatted",
                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                    color = semantic.income,
                    maxLines = 1,
                )
            } else if (subAmount != null) {
                val hidden = LocalAmountsHidden.current
                val prefix = if (item.crossCurrencyTargetAmount != null) "→ " else "≈ "
                val formatted = PrivacyAmountFormatter.format(
                    money = subAmount,
                    hidden = hidden,
                    showPositiveSign = subAmount.isPositive && item.crossCurrencyTargetAmount != null,
                )
                Text(
                    text = "$prefix$formatted",
                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LedgerEmptyStateCard(
    hasFilters: Boolean,
    monthLabel: String,
    onCreateTransaction: () -> Unit,
    onClearFilters: () -> Unit,
) {
    DropletCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(24.dp),
        modifier = Modifier.testTag("ledger_empty_state"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DropletIcons.ReceiptLong,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Text(
                text = if (hasFilters) "暂无符合筛选条件的流水" else "$monthLabel 暂无记账流水",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (hasFilters) {
                    "可以尝试调整关键词、切换标签交并集或清空筛选条件。"
                } else {
                    "点击下方按钮或右下角 + 号，使用一木式直观表单或专业分录模式快速记账。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (hasFilters) {
                FilledTonalButton(onClick = onClearFilters) {
                    Text("清空筛选")
                }
            } else {
                Button(
                    onClick = onCreateTransaction,
                    modifier = Modifier.testTag("ledger_empty_add_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("记一笔")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LedgerFilterBottomSheet(
    uiState: LedgerUiState,
    onToggleTagFilter: (String) -> Unit,
    onTagFilterModeChange: (TagFilterMode) -> Unit,
    onSelectAccountFilter: (String?) -> Unit,
    onSelectCategoryFilter: (String?) -> Unit,
    onTogglePendingReimburseOnly: () -> Unit,
    onToggleIncludeVoid: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp)
                .testTag("ledger_filter_sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "多维筛选",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onClearAll) {
                    Text("重置全部")
                }
            }

            // Account Filter
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "按账户",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = uiState.filterState.selectedAccountId == null,
                        onClick = { onSelectAccountFilter(null) },
                        label = { Text("全部账户") },
                    )
                    uiState.availableAccounts.forEach { account ->
                        FilterChip(
                            selected = uiState.filterState.selectedAccountId == account.id,
                            onClick = {
                                onSelectAccountFilter(
                                    if (uiState.filterState.selectedAccountId == account.id) null else account.id,
                                )
                            },
                            label = { Text("${account.name} (${account.currency.code})") },
                        )
                    }
                }
            }

            // Category Filter (Parent & Child)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "按分类（选大类含全部小类）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = uiState.filterState.selectedCategoryId == null,
                        onClick = { onSelectCategoryFilter(null) },
                        label = { Text("全部分类") },
                    )
                    (uiState.expenseCategoryGroups + uiState.incomeCategoryGroups).forEach { group ->
                        FilterChip(
                            selected = uiState.filterState.selectedCategoryId == group.parent.id,
                            onClick = {
                                onSelectCategoryFilter(
                                    if (uiState.filterState.selectedCategoryId == group.parent.id) {
                                        null
                                    } else {
                                        group.parent.id
                                    },
                                )
                            },
                            label = { Text(group.parent.name) },
                        )
                    }
                }
            }

            // Multi-Tag Filter + ANY / ALL Mode
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "按多标签",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = uiState.filterState.tagFilterMode == TagFilterMode.ANY,
                            onClick = { onTagFilterModeChange(TagFilterMode.ANY) },
                            label = { Text("任一匹配") },
                        )
                        FilterChip(
                            selected = uiState.filterState.tagFilterMode == TagFilterMode.ALL,
                            onClick = { onTagFilterModeChange(TagFilterMode.ALL) },
                            label = { Text("全部匹配") },
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    uiState.availableTags.forEach { tag ->
                        FilterChip(
                            selected = tag.id in uiState.filterState.selectedTagIds,
                            onClick = { onToggleTagFilter(tag.id) },
                            label = { Text("#${tag.name}") },
                        )
                    }
                }
            }

            // Status Switches
            DropletCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("仅看待报销流水", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "过滤待报销与部分报销垫付单",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uiState.filterState.pendingReimburseOnly,
                        onCheckedChange = { onTogglePendingReimburseOnly() },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示已作废流水", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "作废流水不计入月度收支汇总，可随时恢复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uiState.filterState.includeVoid,
                        onCheckedChange = { onToggleIncludeVoid() },
                    )
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("完成筛选")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionDetailBottomSheetContent(
    item: LedgerTransactionItem,
    accountsById: Map<String, Account> = emptyMap(),
    onEdit: () -> Unit = {},
    onRequestRefund: () -> Unit = {},
    onOpenReimbursementList: () -> Unit = {},
    onVoid: () -> Unit = {},
    onRestore: () -> Unit = {},
    onRequestDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val semantic = DropletTheme.semanticColors
    var showSystemBalancing by remember { mutableStateOf(false) }
    val hasSystemBalancing = item.postings.any { it.isSystemBalancing }
    val visiblePostings = if (showSystemBalancing) {
        item.postings
    } else {
        item.postings.filterNot { it.isSystemBalancing }
    }
    val canTriggerRefund = item.status == TransactionStatus.POSTED &&
        item.displayType == TransactionDisplayType.EXPENSE &&
        item.refundSummary != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 24.dp)
            .testTag("tx_detail_sheet"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    item.statusBadge?.let { DropletPillBadge(text = it) }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.dateTimeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PrivacyAmountText(
                money = item.primaryAmount,
                style = MaterialTheme.typography.headlineSmall,
                color = when (item.amountTone) {
                    LedgerAmountTone.INCOME -> semantic.income
                    LedgerAmountTone.EXPENSE -> semantic.expense
                    LedgerAmountTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
                    LedgerAmountTone.MUTED -> MaterialTheme.colorScheme.outline
                },
                showPositiveSign = item.showPositiveSign,
            )
        }

        // Refund & Net Expense Section (for POSTED EXPENSE transactions)
        if (canTriggerRefund) {
            val refundSummary = item.refundSummary!!
            ExpenseRefundDetailCard(
                summary = refundSummary,
                accountsById = accountsById,
                onRequestRefund = onRequestRefund,
            )
        }

        // Reimbursement Status & Settlements Section (for REIMBURSE advances)
        item.reimbursementSummary?.let { reimburseSummary ->
            ReimbursementDetailCard(
                summary = reimburseSummary,
                accountsById = accountsById,
                onOpenReimbursementList = onOpenReimbursementList,
            )
        }

        if (!item.payee.isNullOrBlank() || !item.note.isNullOrBlank() || item.tags.isNotEmpty()) {
            DropletCard(contentPadding = PaddingValues(14.dp)) {
                if (!item.payee.isNullOrBlank()) {
                    DetailMetaRow(label = "商户 / 对象", value = item.payee)
                }
                if (!item.note.isNullOrBlank()) {
                    DetailMetaRow(label = "备注", value = item.note)
                }
                if (item.tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item.tags.forEach { tag ->
                            val tagColor = DropletIconRegistry.parseColor(
                                hex = tag.color,
                                fallback = MaterialTheme.colorScheme.primary,
                            )
                            DropletPillBadge(
                                text = "#${tag.name}",
                                containerColor = tagColor.copy(alpha = 0.14f),
                                contentColor = tagColor,
                            )
                        }
                    }
                }
            }
        }

        // Double-Entry Postings Section
        DropletCard(contentPadding = PaddingValues(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "复式借贷分录 (${visiblePostings.size} 行)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (hasSystemBalancing) {
                    TextButton(onClick = { showSystemBalancing = !showSystemBalancing }) {
                        Text(if (showSystemBalancing) "隐藏 Trading" else "展开 Trading")
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            visiblePostings.forEachIndexed { index, posting ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = posting.accountName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (posting.isSystemBalancing) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (posting.amount.currency != posting.baseAmount.currency) {
                            Text(
                                text = "锁定汇率 ${posting.rateDecimal}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        PrivacyAmountText(
                            money = posting.amount,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (posting.amount.isPositive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            showPositiveSign = true,
                        )
                        if (posting.amount.currency != posting.baseAmount.currency) {
                            PrivacyAmountText(
                                money = posting.baseAmount,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                showPositiveSign = true,
                            )
                        }
                    }
                }
                if (index != visiblePostings.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onEdit,
                modifier = Modifier
                    .weight(1f)
                    .testTag("tx_detail_edit_button"),
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("编辑")
            }
            if (item.status == TransactionStatus.VOID) {
                FilledTonalButton(
                    onClick = onRestore,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tx_detail_restore_button"),
                ) {
                    Text("恢复入账")
                }
            } else {
                FilledTonalButton(
                    onClick = onVoid,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tx_detail_void_button"),
                ) {
                    Text("作废")
                }
            }
            OutlinedButton(
                onClick = onRequestDelete,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.testTag("tx_detail_delete_button"),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailBottomSheet(
    item: LedgerTransactionItem,
    accountsById: Map<String, Account>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRequestRefund: () -> Unit,
    onOpenReimbursementList: () -> Unit,
    onVoid: () -> Unit,
    onRestore: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        TransactionDetailBottomSheetContent(
            item = item,
            accountsById = accountsById,
            onEdit = onEdit,
            onRequestRefund = onRequestRefund,
            onOpenReimbursementList = onOpenReimbursementList,
            onVoid = onVoid,
            onRestore = onRestore,
            onRequestDelete = onRequestDelete,
        )
    }
}

@Composable
private fun ExpenseRefundDetailCard(
    summary: ExpenseRefundSummary,
    accountsById: Map<String, Account>,
    onRequestRefund: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    DropletCard(
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier.testTag("tx_detail_refund_card"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "关联退款与净支出",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (summary.hasRefunds) {
                        "已关联 ${summary.refundCount} 笔退款 · 自动重算分类净支出"
                    } else {
                        "支持全额退款、部分退款与多次关联退款"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onRequestRefund,
                enabled = summary.canRefundMore,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.testTag("tx_detail_refund_button"),
            ) {
                Text(
                    text = if (summary.canRefundMore) "退款" else "已全额退",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已退金额",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                PrivacyAmountText(
                    money = summary.cumulativeRefundedAmount,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (summary.hasRefunds) semantic.income else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("tx_detail_refunded_total"),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "剩余可退",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                PrivacyAmountText(
                    money = summary.remainingRefundableAmount,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("tx_detail_remaining_refundable"),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "原单净支出",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                PrivacyAmountText(
                    money = summary.netExpenseAmount,
                    style = MaterialTheme.typography.titleSmall,
                    color = semantic.expense,
                    modifier = Modifier.testTag("tx_detail_net_expense"),
                )
            }
        }

        if (summary.linkedRefunds.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "关联退款记录 (${summary.linkedRefunds.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            summary.linkedRefunds.forEachIndexed { idx, record ->
                val accountName = record.refundToAccountId?.let { accountsById[it]?.name } ?: "退回账户"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = record.transaction.note?.takeIf { it.isNotBlank() }
                                ?: "第 ${idx + 1} 次关联退款",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${LedgerDateFormats.formatDateTime(record.transaction.occurredAt)} · 退回 $accountName",
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    PrivacyAmountText(
                        money = record.refundReceivedAmount,
                        style = MaterialTheme.typography.titleSmall,
                        color = semantic.income,
                        showPositiveSign = true,
                    )
                }
                if (idx != summary.linkedRefunds.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
            }
        }
    }
}

@Composable
private fun ReimbursementDetailCard(
    summary: ReimbursementItemSummary,
    accountsById: Map<String, Account>,
    onOpenReimbursementList: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val (stateLabel, stateBg, stateFg) = when (summary.derivedState) {
        ReimburseState.PENDING -> Triple("待报销", semantic.warningContainer, semantic.warning)
        ReimburseState.PARTIAL -> Triple("部分报销", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        ReimburseState.SETTLED -> Triple("已报销结清", semantic.incomeContainer, semantic.income)
    }

    DropletCard(
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier.testTag("tx_detail_reimburse_card"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "待报销进度",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                DropletPillBadge(
                    text = stateLabel,
                    containerColor = stateBg,
                    contentColor = stateFg,
                )
            }
            if (summary.isPending) {
                TextButton(
                    onClick = onOpenReimbursementList,
                    modifier = Modifier.testTag("tx_detail_open_reimburse_list"),
                ) {
                    Text("去冲抵")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("原垫付", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrivacyAmountText(money = summary.advanceAmount, style = MaterialTheme.typography.titleSmall)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("已到账", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrivacyAmountText(
                    money = summary.receivedCashTotal,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (summary.receivedCashTotal.isPositive) semantic.income else MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (summary.shortfallExpenseTotal.isPositive) "差额自付" else "剩余待报销",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrivacyAmountText(
                    money = if (summary.shortfallExpenseTotal.isPositive) {
                        summary.shortfallExpenseTotal
                    } else {
                        summary.remainingPendingAmount
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (summary.shortfallExpenseTotal.isPositive) {
                        semantic.expense
                    } else if (summary.remainingPendingAmount.isPositive) {
                        semantic.warning
                    } else {
                        semantic.income
                    },
                )
            }
        }

        if (summary.settlementTransactions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "关联冲抵记录 (${summary.settlementCount})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            summary.settlementTransactions.forEachIndexed { idx, settlement ->
                val clearedMinor = settlement.businessPostings
                    .filter { it.accountId == summary.reimbursementAccountId && it.amountMinor < 0L }
                    .fold(0L) { acc, p -> Math.addExact(acc, Math.negateExact(p.amountMinor)) }
                val depositAcctName = settlement.businessPostings
                    .firstOrNull { p ->
                        p.accountId != summary.reimbursementAccountId &&
                            p.amountMinor > 0L &&
                            accountsById[p.accountId]?.type != io.github.kvtttba.droplet.core.model.AccountType.EXPENSE
                    }
                    ?.let { accountsById[it.accountId]?.name }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = settlement.note?.takeIf { it.isNotBlank() } ?: settlement.payee ?: "报销冲抵",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = buildList {
                            add(LedgerDateFormats.formatDateTime(settlement.occurredAt))
                            depositAcctName?.let { add("入账 $it") }
                        }
                        Text(
                            text = meta.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    PrivacyAmountText(
                        money = Money(clearedMinor, summary.currency),
                        style = MaterialTheme.typography.titleSmall,
                        color = semantic.income,
                    )
                }
                if (idx != summary.settlementTransactions.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
            }
        }
    }
}

@Composable
private fun DetailMetaRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatDaySummaryTrailing(
    dayExpense: Money,
    dayIncome: Money,
    itemCount: Int,
    hidden: Boolean,
): String {
    if (hidden) {
        return "**** · $itemCount 笔"
    }
    val parts = buildList {
        if (!dayExpense.isZero) {
            add("支 ${PrivacyAmountFormatter.format(dayExpense.abs())}")
        }
        if (!dayIncome.isZero) {
            add("收 ${PrivacyAmountFormatter.format(dayIncome.abs())}")
        }
    }
    return if (parts.isEmpty()) "$itemCount 笔" else parts.joinToString("  ")
}
