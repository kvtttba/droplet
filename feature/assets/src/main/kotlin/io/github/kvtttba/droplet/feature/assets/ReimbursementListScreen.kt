package io.github.kvtttba.droplet.feature.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.domain.debt.ReimbursementItemSummary
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.component.PrivacyToggleIconButton
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.tabularNums

@Composable
fun ReimbursementListRoute(
    onNavigateBack: () -> Unit,
    onToggleAmountsHidden: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ReimbursementListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    ReimbursementListScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onToggleAmountsHidden = onToggleAmountsHidden,
        onSelectTab = viewModel::selectTab,
        onToggleSelectAdvance = viewModel::toggleSelectAdvance,
        onToggleSelectAllPending = viewModel::toggleSelectAllPending,
        onSettleSingle = viewModel::settleSingle,
        onBatchSettle = viewModel::batchSettleSelected,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReimbursementListScreen(
    uiState: ReimbursementListUiState,
    onNavigateBack: () -> Unit = {},
    onToggleAmountsHidden: () -> Unit = {},
    onSelectTab: (ReimbursementFilterTab) -> Unit = {},
    onToggleSelectAdvance: (String) -> Unit = {},
    onToggleSelectAllPending: () -> Unit = {},
    onSettleSingle: (
        item: ReimbursementItemSummary,
        depositAccountId: String?,
        receivedAmountText: String,
        convertShortfallToExpense: Boolean,
        shortfallExpenseAccountId: String?,
        surplusIncomeAccountId: String?,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, _, _, _, _, _, cb -> cb(null) },
    onBatchSettle: (depositAccountId: String?, note: String, (String?) -> Unit) -> Unit = { _, _, cb -> cb(null) },
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors
    var settlingItem by remember { mutableStateOf<ReimbursementItemSummary?>(null) }
    var showBatchSheet by remember { mutableStateOf(false) }

    val overview = uiState.overview
    val visibleItems = uiState.visibleItems
    val selectedPending = uiState.selectedPendingItems
    val allPendingSelected = overview.pendingItems.isNotEmpty() &&
        overview.pendingItems.all { it.advanceTransaction.id in uiState.selectedAdvanceIds }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_reimbursement_list"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("reimbursement_back"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "待报销管理",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "单笔/批量冲抵到账 · 差额一键转个人自付",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    PrivacyToggleIconButton(
                        amountsHidden = amountsHidden,
                        onToggle = onToggleAmountsHidden,
                    )
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("reimbursement_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "reimbursement_hero_card") {
                DropletHeroCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "剩余待报销总额 (${overview.primaryPendingTotal.currency.code})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                        )
                        DropletPillBadge(
                            text = if (overview.pendingCount > 0) "${overview.pendingCount} 笔待报销" else "已全部结清",
                            containerColor = if (overview.pendingCount > 0) {
                                semantic.warningContainer
                            } else {
                                semantic.incomeContainer
                            },
                            contentColor = if (overview.pendingCount > 0) semantic.warning else semantic.income,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    PrivacyAmountText(
                        money = overview.primaryPendingTotal,
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.testTag("reimbursement_pending_total"),
                    )
                    if (overview.totalPendingByCurrency.size > 1) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            overview.totalPendingByCurrency.forEach { (ccy, amt) ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = ccy.code,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                                    )
                                    PrivacyAmountText(
                                        money = amt,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "待报销 ${overview.pendingCount} 笔 · 已结清 ${overview.settledCount} 笔（垫付记入应收资产，不虚增个人消费）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    )
                }
            }

            item(key = "reimbursement_filter_tabs") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReimbursementFilterTab.entries.forEach { tab ->
                        val count = when (tab) {
                            ReimbursementFilterTab.PENDING -> overview.pendingCount
                            ReimbursementFilterTab.SETTLED -> overview.settledCount
                            ReimbursementFilterTab.ALL -> overview.items.size
                        }
                        FilterChip(
                            selected = uiState.selectedTab == tab,
                            onClick = { onSelectTab(tab) },
                            label = { Text("${tab.label} ($count)") },
                            modifier = Modifier.testTag("reimbursement_tab_${tab.name}"),
                        )
                    }
                }
            }

            if (overview.pendingItems.isNotEmpty() && uiState.selectedTab != ReimbursementFilterTab.SETTLED) {
                item(key = "reimbursement_batch_bar") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = onToggleSelectAllPending,
                                modifier = Modifier.testTag("reimbursement_select_all_button"),
                            ) {
                                Text(if (allPendingSelected) "取消全选" else "全选待报销 (${overview.pendingCount})")
                            }
                            Button(
                                onClick = { showBatchSheet = true },
                                enabled = selectedPending.isNotEmpty() && !uiState.isSubmitting,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("reimbursement_batch_settle_button"),
                            ) {
                                Text("批量到账 (${selectedPending.size})")
                            }
                        }
                    }
                }
            }

            if (visibleItems.isEmpty()) {
                item(key = "reimbursement_empty") {
                    DropletCard(contentPadding = PaddingValues(20.dp)) {
                        Text(
                            text = when (uiState.selectedTab) {
                                ReimbursementFilterTab.PENDING -> "当前没有待报销流水"
                                ReimbursementFilterTab.SETTLED -> "暂无已结清的报销记录"
                                ReimbursementFilterTab.ALL -> "暂无报销记录"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "在记账页记录支出时勾选「待报销」，即可在此统一跟踪进度并一键冲抵到账。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = visibleItems,
                    key = { _, item -> item.advanceTransaction.id },
                ) { index, item ->
                    ReimbursementItemCard(
                        item = item,
                        index = index,
                        isSelected = item.advanceTransaction.id in uiState.selectedAdvanceIds,
                        paymentAccountName = item.paymentAccountId?.let { uiState.accountsById[it]?.name },
                        onToggleSelected = { onToggleSelectAdvance(item.advanceTransaction.id) },
                        onSettleClick = { settlingItem = item },
                    )
                }
            }
        }
    }

    settlingItem?.let { target ->
        SingleReimbursementSettleSheet(
            item = target,
            depositAccounts = uiState.depositAccounts,
            expenseCategories = uiState.expenseCategories,
            incomeCategories = uiState.incomeCategories,
            isSubmitting = uiState.isSubmitting,
            onDismiss = { settlingItem = null },
            onSubmit = { depositId, receivedText, convertShortfall, shortfallCatId, surplusCatId, note, cb ->
                onSettleSingle(
                    target,
                    depositId,
                    receivedText,
                    convertShortfall,
                    shortfallCatId,
                    surplusCatId,
                    note,
                    cb,
                )
            },
        )
    }

    if (showBatchSheet && selectedPending.isNotEmpty()) {
        BatchReimbursementSettleSheet(
            selectedItems = selectedPending,
            depositAccounts = uiState.depositAccounts,
            isSubmitting = uiState.isSubmitting,
            onDismiss = { showBatchSheet = false },
            onSubmit = onBatchSettle,
        )
    }
}

@Composable
private fun ReimbursementItemCard(
    item: ReimbursementItemSummary,
    index: Int,
    isSelected: Boolean,
    paymentAccountName: String?,
    onToggleSelected: () -> Unit,
    onSettleClick: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val tx = item.advanceTransaction
    val (stateLabel, stateBg, stateFg) = when (item.derivedState) {
        ReimburseState.PENDING -> Triple("待报销", semantic.warningContainer, semantic.warning)
        ReimburseState.PARTIAL -> Triple("部分报销", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        ReimburseState.SETTLED -> Triple("已报销", semantic.incomeContainer, semantic.income)
    }

    DropletCard(
        modifier = Modifier.testTag("reimbursement_item_$index"),
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.isPending) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelected() },
                    modifier = Modifier.testTag("reimbursement_checkbox_$index"),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = tx.payee?.takeIf { it.isNotBlank() } ?: "待报销垫付",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    DropletPillBadge(
                        text = stateLabel,
                        containerColor = stateBg,
                        contentColor = stateFg,
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                val meta = buildList {
                    add(AssetsFormatters.formatFullTimestamp(tx.occurredAt))
                    paymentAccountName?.let { add("垫付：$it") }
                    tx.note?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (item.isPending) "待报销" else "已冲抵",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrivacyAmountText(
                    money = if (item.isPending) item.remainingPendingAmount else item.advanceAmount,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (item.isPending) semantic.warning else semantic.income,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "原垫付",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrivacyAmountText(
                        money = item.advanceAmount,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (item.receivedCashTotal.isPositive) {
                        Text(
                            text = "· 已到账",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrivacyAmountText(
                            money = item.receivedCashTotal,
                            style = MaterialTheme.typography.labelSmall,
                            color = semantic.income,
                        )
                    }
                }
                if (item.shortfallExpenseTotal.isPositive) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "差额转自付支出",
                            style = MaterialTheme.typography.labelSmall,
                            color = semantic.expense,
                        )
                        PrivacyAmountText(
                            money = item.shortfallExpenseTotal,
                            style = MaterialTheme.typography.labelSmall,
                            color = semantic.expense,
                        )
                    }
                }
                if (item.surplusIncomeTotal.isPositive) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "超额补贴收入",
                            style = MaterialTheme.typography.labelSmall,
                            color = semantic.income,
                        )
                        PrivacyAmountText(
                            money = item.surplusIncomeTotal,
                            style = MaterialTheme.typography.labelSmall,
                            color = semantic.income,
                            showPositiveSign = true,
                        )
                    }
                }
            }

            if (item.isPending) {
                FilledTonalButton(
                    onClick = onSettleClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.testTag("reimbursement_settle_btn_$index"),
                ) {
                    Text("冲抵到账", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
