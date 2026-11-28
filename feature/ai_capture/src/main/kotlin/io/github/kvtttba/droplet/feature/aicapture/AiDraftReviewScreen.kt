package io.github.kvtttba.droplet.feature.aicapture

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.ai.AiUncertainFieldKeys
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.component.PrivacyToggleIconButton
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme

@Composable
fun AiDraftReviewRoute(
    onNavigateBack: () -> Unit,
    onCommitted: (String) -> Unit,
    onToggleAmountsHidden: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AiDraftReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    LaunchedEffect(uiState.committedSummaryMessage) {
        uiState.committedSummaryMessage?.let { msg ->
            viewModel.consumeCommittedNavigation()
            onCommitted(msg)
        }
    }

    AiDraftReviewScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onToggleAmountsHidden = onToggleAmountsHidden,
        onToggleItemSelected = viewModel::toggleItemSelected,
        onToggleItemExpanded = viewModel::toggleItemExpanded,
        onUpdateItemType = viewModel::updateItemType,
        onUpdateItemAmount = viewModel::updateItemAmount,
        onUpdateItemCurrency = viewModel::updateItemCurrency,
        onUpdateItemCategory = viewModel::updateItemCategory,
        onUpdateItemAccount = viewModel::updateItemAccount,
        onUpdateItemTargetAccount = viewModel::updateItemTargetAccount,
        onUpdateItemPayee = viewModel::updateItemPayee,
        onUpdateItemNote = viewModel::updateItemNote,
        onToggleItemTag = viewModel::toggleItemTag,
        onAddBlankItem = viewModel::addBlankItem,
        onRemoveItem = viewModel::removeItem,
        onSetAttachReceipts = viewModel::setAttachReceipts,
        onSaveDraftProgress = viewModel::saveEditsToDraft,
        onCommitSelectedBatch = viewModel::commitSelectedBatch,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDraftReviewScreen(
    uiState: AiDraftReviewUiState,
    onNavigateBack: () -> Unit = {},
    onToggleAmountsHidden: () -> Unit = {},
    onToggleItemSelected: (String) -> Unit = {},
    onToggleItemExpanded: (String) -> Unit = {},
    onUpdateItemType: (String, TransactionDisplayType) -> Unit = { _, _ -> },
    onUpdateItemAmount: (String, String) -> Unit = { _, _ -> },
    onUpdateItemCurrency: (String, Currency) -> Unit = { _, _ -> },
    onUpdateItemCategory: (String, String) -> Unit = { _, _ -> },
    onUpdateItemAccount: (String, String) -> Unit = { _, _ -> },
    onUpdateItemTargetAccount: (String, String) -> Unit = { _, _ -> },
    onUpdateItemPayee: (String, String) -> Unit = { _, _ -> },
    onUpdateItemNote: (String, String) -> Unit = { _, _ -> },
    onToggleItemTag: (String, String) -> Unit = { _, _ -> },
    onAddBlankItem: () -> Unit = {},
    onRemoveItem: (String) -> Unit = {},
    onSetAttachReceipts: (Boolean) -> Unit = {},
    onSaveDraftProgress: () -> Unit = {},
    onCommitSelectedBatch: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_ai_draft_review"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "草稿批量确认",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("ai_review_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    PrivacyToggleIconButton(
                        amountsHidden = amountsHidden,
                        onToggle = onToggleAmountsHidden,
                    )
                    IconButton(
                        onClick = onAddBlankItem,
                        modifier = Modifier.testTag("ai_review_add_item_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "新增草稿条目",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            AiDraftReviewBottomBar(
                uiState = uiState,
                onCommitSelectedBatch = onCommitSelectedBatch,
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("ai_review_list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "review_summary_hero") {
                    AiReviewSummaryHeroCard(
                        uiState = uiState,
                        onSetAttachReceipts = onSetAttachReceipts,
                        onSaveDraftProgress = onSaveDraftProgress,
                    )
                }

                if (!uiState.errorMessage.isNullOrBlank()) {
                    item(key = "review_error_banner") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_review_error_banner"),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = uiState.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }

                if (uiState.items.isEmpty()) {
                    item(key = "review_empty_items") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "当前草稿暂无条目",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                OutlinedButton(onClick = onAddBlankItem) {
                                    Text("新增一笔草稿条目")
                                }
                            }
                        }
                    }
                } else {
                    itemsIndexed(
                        items = uiState.items,
                        key = { _, item -> item.localId },
                    ) { index, item ->
                        AiDraftCandidateCard(
                            index = index,
                            item = item,
                            uiState = uiState,
                            onToggleSelected = { onToggleItemSelected(item.localId) },
                            onToggleExpanded = { onToggleItemExpanded(item.localId) },
                            onUpdateType = { onUpdateItemType(item.localId, it) },
                            onUpdateAmount = { onUpdateItemAmount(item.localId, it) },
                            onUpdateCurrency = { onUpdateItemCurrency(item.localId, it) },
                            onUpdateCategory = { onUpdateItemCategory(item.localId, it) },
                            onUpdateAccount = { onUpdateItemAccount(item.localId, it) },
                            onUpdateTargetAccount = { onUpdateItemTargetAccount(item.localId, it) },
                            onUpdatePayee = { onUpdateItemPayee(item.localId, it) },
                            onUpdateNote = { onUpdateItemNote(item.localId, it) },
                            onToggleTag = { onToggleItemTag(item.localId, it) },
                            onRemove = { onRemoveItem(item.localId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiReviewSummaryHeroCard(
    uiState: AiDraftReviewUiState,
    onSetAttachReceipts: (Boolean) -> Unit,
    onSaveDraftProgress: () -> Unit,
) {
    val semantics = DropletTheme.semanticColors
    DropletHeroCard(
        modifier = Modifier.testTag("ai_review_hero_card"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = DropletIcons.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "${AiReviewFieldLabels.inputTypeLabel(uiState.inputType)} · 共 ${uiState.items.size} 笔",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (uiState.reviewAttentionCount > 0) {
                DropletPillBadge(
                    text = "${uiState.reviewAttentionCount} 笔需核对",
                    containerColor = semantics.warningContainer,
                    contentColor = semantics.warning,
                    modifier = Modifier
                        .semantics(mergeDescendants = true) {}
                        .testTag("ai_review_attention_badge"),
                )
            } else {
                DropletPillBadge(
                    text = "已就绪",
                    containerColor = semantics.incomeContainer,
                    contentColor = semantics.income,
                )
            }
        }
        if (!uiState.rawText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "原文：${uiState.rawText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (uiState.attachments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "关联 ${uiState.attachments.size} 张去 EXIF 票据附件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Switch(
                    checked = uiState.attachReceiptsToTransactions,
                    onCheckedChange = onSetAttachReceipts,
                    modifier = Modifier.testTag("ai_review_attach_receipts_switch"),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "确认后将按复式借贷恒平规则在单事务内原子入账。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onSaveDraftProgress,
                modifier = Modifier.testTag("ai_review_save_draft_button"),
            ) {
                Text("暂存修改")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiDraftCandidateCard(
    index: Int,
    item: DraftCandidateEditorItem,
    uiState: AiDraftReviewUiState,
    onToggleSelected: () -> Unit,
    onToggleExpanded: () -> Unit,
    onUpdateType: (TransactionDisplayType) -> Unit,
    onUpdateAmount: (String) -> Unit,
    onUpdateCurrency: (Currency) -> Unit,
    onUpdateCategory: (String) -> Unit,
    onUpdateAccount: (String) -> Unit,
    onUpdateTargetAccount: (String) -> Unit,
    onUpdatePayee: (String) -> Unit,
    onUpdateNote: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val semantics = DropletTheme.semanticColors
    val borderColor = when {
        item.validationError != null && item.selected -> MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
        item.needsUserReview && item.selected -> semantics.warning.copy(alpha = 0.75f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }
    val amountColor = when (item.displayType) {
        TransactionDisplayType.INCOME -> semantics.income
        TransactionDisplayType.EXPENSE -> semantics.expense
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_review_card_$index"),
        shape = RoundedCornerShape(20.dp),
        color = if (item.selected) {
            MaterialTheme.colorScheme.surfaceContainerLowest
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f)
        },
        border = BorderStroke(
            width = if (item.needsUserReview && item.selected) 1.5.dp else 1.dp,
            color = borderColor,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = item.selected,
                    onCheckedChange = { onToggleSelected() },
                    modifier = Modifier.testTag("ai_review_checkbox_$index"),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DropletPillBadge(
                            text = AiReviewFieldLabels.displayTypeLabel(item.displayType),
                            containerColor = when (item.displayType) {
                                TransactionDisplayType.INCOME -> semantics.incomeContainer
                                TransactionDisplayType.EXPENSE -> semantics.expenseContainer
                                else -> MaterialTheme.colorScheme.tertiaryContainer
                            },
                            contentColor = amountColor,
                        )
                        val confidencePct = AiReviewFieldLabels.formatConfidencePct(item.confidenceDecimal)
                        DropletPillBadge(
                            text = if (item.isLowConfidence) "置信度 $confidencePct · 低" else "置信度 $confidencePct",
                            containerColor = if (item.isLowConfidence) {
                                semantics.warningContainer
                            } else {
                                semantics.incomeContainer
                            },
                            contentColor = if (item.isLowConfidence) {
                                semantics.warning
                            } else {
                                semantics.income
                            },
                            modifier = Modifier
                                .semantics(mergeDescendants = true) {}
                                .testTag("ai_review_confidence_badge_$index"),
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            val primaryTitle = when (item.displayType) {
                                TransactionDisplayType.TRANSFER -> {
                                    val from = item.matchedAccountName ?: item.rawAccountName ?: "未选转出户"
                                    val to = item.matchedTargetAccountName ?: item.rawTargetAccountName ?: "未选转入户"
                                    "$from → $to"
                                }
                                else -> item.matchedCategoryPath ?: item.rawCategoryPath ?: "待选分类"
                            }
                            append(primaryTitle)
                            if (item.payee.isNotBlank()) {
                                append(" · ${item.payee}")
                            }
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(item.matchedAccountName ?: item.rawAccountName ?: "待选账户")
                            append(" · ")
                            append(item.occurredAtLabel)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    val displayMoney = item.parsedAmount ?: Money.zero(item.currency)
                    val signedMoney = when (item.displayType) {
                        TransactionDisplayType.EXPENSE -> -displayMoney
                        else -> displayMoney
                    }
                    PrivacyAmountText(
                        money = signedMoney,
                        style = MaterialTheme.typography.titleMedium,
                        color = amountColor,
                        showPositiveSign = item.displayType == TransactionDisplayType.INCOME,
                        modifier = Modifier.testTag("ai_review_amount_text_$index"),
                    )
                    IconButton(
                        onClick = onToggleExpanded,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("ai_review_edit_toggle_$index"),
                    ) {
                        Icon(
                            imageVector = if (item.expanded) {
                                Icons.Rounded.KeyboardArrowUp
                            } else {
                                Icons.Rounded.KeyboardArrowDown
                            },
                            contentDescription = if (item.expanded) "收起微调" else "展开微调",
                        )
                    }
                }
            }

            if (item.uncertainFields.isNotEmpty() || item.validationError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.testTag("ai_review_uncertain_row_$index"),
                ) {
                    item.uncertainFields.forEach { key ->
                        DropletPillBadge(
                            text = "待核对：${AiReviewFieldLabels.labelForUncertainField(key)}",
                            containerColor = semantics.warningContainer,
                            contentColor = semantics.warning,
                            modifier = Modifier
                                .semantics(mergeDescendants = true) {}
                                .testTag("ai_review_uncertain_${index}_$key"),
                        )
                    }
                    if (item.validationError != null) {
                        DropletPillBadge(
                            text = item.validationError,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .semantics(mergeDescendants = true) {}
                                .testTag("ai_review_validation_badge_$index"),
                        )
                    }
                }
            }

            if (item.note.isNotBlank() && !item.expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "备注：${item.note}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (item.expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))

                // 1. Type selector
                Text(
                    text = "交易类型",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        TransactionDisplayType.EXPENSE,
                        TransactionDisplayType.INCOME,
                        TransactionDisplayType.TRANSFER,
                    ).forEach { type ->
                        FilterChip(
                            selected = item.displayType == type,
                            onClick = { onUpdateType(type) },
                            label = { Text(AiReviewFieldLabels.displayTypeLabel(type)) },
                            modifier = Modifier.testTag("ai_review_type_${index}_${type.name}"),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2. Amount & Currency
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = item.amountInput,
                        onValueChange = onUpdateAmount,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_review_amount_input_$index"),
                        singleLine = true,
                        label = {
                            val labelSuffix = if (AiUncertainFieldKeys.AMOUNT in item.uncertainFields) "（待核对）" else ""
                            Text("金额$labelSuffix")
                        },
                        isError = item.parsedAmount == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Currency.entries.forEach { currency ->
                        FilterChip(
                            selected = item.currency == currency,
                            onClick = { onUpdateCurrency(currency) },
                            label = { Text("${currency.code} ${currency.symbol}") },
                            modifier = Modifier.testTag("ai_review_currency_${index}_${currency.code}"),
                        )
                    }
                }

                // 3. Category selector (Expense / Income)
                if (item.displayType == TransactionDisplayType.EXPENSE ||
                    item.displayType == TransactionDisplayType.INCOME
                ) {
                    val categories = if (item.displayType == TransactionDisplayType.EXPENSE) {
                        uiState.expenseCategories
                    } else {
                        uiState.incomeCategories
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (AiUncertainFieldKeys.CATEGORY_PATH in item.uncertainFields) {
                            "分类（待核对）"
                        } else {
                            "分类"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (AiUncertainFieldKeys.CATEGORY_PATH in item.uncertainFields) {
                            semantics.warning
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.testTag("ai_review_category_row_$index"),
                    ) {
                        items(
                            items = categories,
                            key = { it.id },
                        ) { cat ->
                            FilterChip(
                                selected = item.matchedCategoryId == cat.id,
                                onClick = { onUpdateCategory(cat.id) },
                                label = {
                                    Text(
                                        text = cat.displayPath,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                modifier = Modifier.testTag("ai_review_category_${index}_${cat.leafName}"),
                            )
                        }
                    }
                }

                // 4. Primary account selector
                Spacer(modifier = Modifier.height(10.dp))
                val accountTitle = if (item.displayType == TransactionDisplayType.TRANSFER) "转出账户" else "资金账户"
                Text(
                    text = if (AiUncertainFieldKeys.ACCOUNT_NAME in item.uncertainFields) {
                        "$accountTitle（待核对）"
                    } else {
                        accountTitle
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (AiUncertainFieldKeys.ACCOUNT_NAME in item.uncertainFields) {
                        semantics.warning
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    uiState.operationalAccounts.forEach { acc ->
                        FilterChip(
                            selected = item.matchedAccountId == acc.id,
                            onClick = { onUpdateAccount(acc.id) },
                            label = { Text("${acc.name} (${acc.currency.code})") },
                            modifier = Modifier.testTag("ai_review_account_${index}_${acc.name}"),
                        )
                    }
                }

                // 5. Target account selector for Transfer
                if (item.displayType == TransactionDisplayType.TRANSFER) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (AiUncertainFieldKeys.TARGET_ACCOUNT_NAME in item.uncertainFields) {
                            "转入账户（待核对）"
                        } else {
                            "转入账户"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (AiUncertainFieldKeys.TARGET_ACCOUNT_NAME in item.uncertainFields) {
                            semantics.warning
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        uiState.operationalAccounts.forEach { acc ->
                            FilterChip(
                                selected = item.matchedTargetAccountId == acc.id,
                                onClick = { onUpdateTargetAccount(acc.id) },
                                label = { Text("${acc.name} (${acc.currency.code})") },
                                modifier = Modifier.testTag("ai_review_target_account_${index}_${acc.name}"),
                            )
                        }
                    }
                }

                // 6. Payee & Note
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = item.payee,
                    onValueChange = onUpdatePayee,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_review_payee_input_$index"),
                    singleLine = true,
                    label = { Text("商户 / 对象") },
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = item.note,
                    onValueChange = onUpdateNote,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_review_note_input_$index"),
                    singleLine = true,
                    label = { Text("备注") },
                    shape = RoundedCornerShape(14.dp),
                )

                // 7. Multi-Tag selector
                if (uiState.availableTags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "标签（可多选）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        uiState.availableTags.forEach { tag ->
                            FilterChip(
                                selected = tag.id in item.selectedTagIds,
                                onClick = { onToggleTag(tag.id) },
                                label = { Text("#${tag.name}") },
                                modifier = Modifier.testTag("ai_review_tag_${index}_${tag.name}"),
                            )
                        }
                    }
                }

                // 8. Delete candidate row
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onRemove,
                        modifier = Modifier.testTag("ai_review_delete_item_$index"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "移除此条目",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiDraftReviewBottomBar(
    uiState: AiDraftReviewUiState,
    onCommitSelectedBatch: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_review_bottom_bar"),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已勾选 ${uiState.selectedCount} / ${uiState.items.size} 笔",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("ai_review_selected_count"),
                )
                val totals = uiState.selectedTotalsByCurrency.values.toList()
                if (totals.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        totals.forEachIndexed { idx, money ->
                            PrivacyAmountText(
                                money = money,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("ai_review_total_amount_$idx"),
                            )
                        }
                    }
                } else {
                    Text(
                        text = "¥0.00",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onCommitSelectedBatch,
                enabled = uiState.selectedCount > 0 && !uiState.isCommitting,
                modifier = Modifier.testTag("ai_review_commit_button"),
            ) {
                if (uiState.isCommitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("入账中...")
                } else {
                    Text("确认批量入账")
                }
            }
        }
    }
}
