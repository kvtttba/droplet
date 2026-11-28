package io.github.kvtttba.droplet.feature.settings

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.database.repository.RecurringRuleItem
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.RecurringFrequency
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.component.PrivacyToggleIconButton
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormatterIso: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val supportedFrequencies = listOf(
    RecurringFrequency.DAILY to "按天",
    RecurringFrequency.WEEKLY to "按周",
    RecurringFrequency.MONTHLY to "按月",
    RecurringFrequency.YEARLY to "按年",
)

@Composable
fun RecurringRulesRoute(
    onNavigateBack: () -> Unit,
    onToggleAmountsHidden: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: RecurringRulesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    RecurringRulesScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onToggleAmountsHidden = onToggleAmountsHidden,
        onToggleRemindersEnabled = viewModel::setRecurringRemindersEnabled,
        onSaveRule = viewModel::saveRule,
        onTogglePaused = viewModel::setPaused,
        onConfirmDue = viewModel::confirmAndPostDue,
        onSkipDue = viewModel::skipDueOccurrence,
        onExecuteNow = viewModel::executeNow,
        onTerminateRule = viewModel::terminateRule,
        onDeleteRule = viewModel::deleteRule,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecurringRulesScreen(
    uiState: RecurringRulesUiState,
    onNavigateBack: () -> Unit = {},
    onToggleAmountsHidden: () -> Unit = {},
    onToggleRemindersEnabled: (Boolean) -> Unit = {},
    onSaveRule: (RecurringRuleFormInput, (String?) -> Unit) -> Unit = { _, cb -> cb(null) },
    onTogglePaused: (String, Boolean) -> Unit = { _, _ -> },
    onConfirmDue: (String) -> Unit = {},
    onSkipDue: (String) -> Unit = {},
    onExecuteNow: (String) -> Unit = {},
    onTerminateRule: (String) -> Unit = {},
    onDeleteRule: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors
    var editingItem by remember { mutableStateOf<RecurringRuleItem?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_recurring_rules"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "周期记账",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("recurring_back_button"),
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
                        onClick = { showCreateSheet = true },
                        modifier = Modifier.testTag("recurring_add_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "新建周期规则",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("recurring_rules_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "recurring_hero") {
                DropletHeroCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = DropletIcons.Repeat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "周期自动记账与提醒",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "房租、工资、会员订阅与定投计划离线自动调度",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DropletPillBadge(
                            text = "启用中 ${uiState.activeRuleCount} 条",
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                        DropletPillBadge(
                            text = "自动入账 ${uiState.autoPostRuleCount} 条",
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                        if (uiState.pendingConfirmationRules.isNotEmpty()) {
                            DropletPillBadge(
                                text = "待确认 ${uiState.pendingConfirmationRules.size} 期",
                                containerColor = semantic.warningContainer,
                                contentColor = semantic.warning,
                                modifier = Modifier
                                    .semantics(mergeDescendants = true) {}
                                    .testTag("recurring_pending_count_badge"),
                            )
                        }
                    }
                }
            }

            // Pending confirmation due queue
            if (uiState.pendingConfirmationRules.isNotEmpty()) {
                item(key = "recurring_pending_header") {
                    DropletSectionHeader(
                        title = "到期 · 待确认入账",
                        trailing = "${uiState.pendingConfirmationRules.size} 项",
                    )
                }
                items(
                    items = uiState.pendingConfirmationRules,
                    key = { "pending_${it.rule.id}" },
                ) { item ->
                    PendingConfirmationRuleCard(
                        item = item,
                        isSubmitting = uiState.isSubmitting,
                        onConfirm = { onConfirmDue(item.rule.id) },
                        onSkip = { onSkipDue(item.rule.id) },
                    )
                }
            }

            // Reminder switch card
            item(key = "recurring_reminder_toggle_card") {
                DropletCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "周期记账通知与待确认提醒",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "自动入账完成或手动规则到期时发送本地通知，遵循隐私隐身设置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.recurringRemindersEnabled,
                            onCheckedChange = onToggleRemindersEnabled,
                            modifier = Modifier.testTag("recurring_reminders_enabled_switch"),
                        )
                    }
                }
            }

            item(key = "recurring_all_header") {
                DropletSectionHeader(
                    title = "全部周期规则",
                    trailing = "${uiState.allRules.size} 条",
                )
            }

            if (uiState.allRules.isEmpty() && !uiState.isLoading) {
                item(key = "recurring_empty") {
                    DropletCard {
                        Text(
                            text = "尚未创建周期规则。点击右上角「+」新建每月房租、固定工资或会员订阅，支持月末 29–31 号与闰年安全夹紧。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = { showCreateSheet = true },
                            modifier = Modifier.testTag("recurring_empty_add_button"),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("新建周期规则")
                        }
                    }
                }
            } else {
                items(
                    items = uiState.allRules,
                    key = { "rule_${it.rule.id}" },
                ) { item ->
                    RecurringRuleCard(
                        item = item,
                        isSubmitting = uiState.isSubmitting,
                        onEdit = { editingItem = item },
                        onTogglePaused = { paused -> onTogglePaused(item.rule.id, paused) },
                        onExecuteNow = { onExecuteNow(item.rule.id) },
                    )
                }
            }
        }
    }

    val sheetTarget = editingItem
    if (showCreateSheet || sheetTarget != null) {
        RecurringRuleEditorSheet(
            editingItem = sheetTarget,
            operationalAccounts = uiState.operationalAccounts,
            expenseCategories = uiState.expenseCategories,
            incomeCategories = uiState.incomeCategories,
            availableTags = uiState.availableTags,
            isSubmitting = uiState.isSubmitting,
            onDismiss = {
                showCreateSheet = false
                editingItem = null
            },
            onSave = { input ->
                onSaveRule(input) { err ->
                    if (err == null) {
                        showCreateSheet = false
                        editingItem = null
                    }
                }
            },
            onTerminate = { ruleId ->
                onTerminateRule(ruleId)
                editingItem = null
            },
            onDelete = { ruleId ->
                onDeleteRule(ruleId)
                editingItem = null
            },
        )
    }
}

@Composable
private fun PendingConfirmationRuleCard(
    item: RecurringRuleItem,
    isSubmitting: Boolean,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val nextDateLabel = formatEpochDate(item.rule.nextRunAt)

    DropletCard(
        containerColor = semantic.warningContainer.copy(alpha = 0.45f),
        modifier = Modifier.testTag("recurring_pending_card_${item.rule.name}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.rule.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DropletPillBadge(
                        text = "待确认",
                        containerColor = semantic.warningContainer,
                        contentColor = semantic.warning,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${item.categoryOrTargetName} · ${item.accountName} · 计划日 $nextDateLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.payload?.let { payload ->
                PrivacyAmountText(
                    money = payload.amount,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onSkip,
                enabled = !isSubmitting,
                modifier = Modifier
                    .weight(1f)
                    .testTag("recurring_skip_button_${item.rule.name}"),
            ) {
                Text("跳过本期")
            }
            Button(
                onClick = onConfirm,
                enabled = !isSubmitting,
                modifier = Modifier
                    .weight(1f)
                    .testTag("recurring_confirm_button_${item.rule.name}"),
            ) {
                Text("确认入账")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecurringRuleCard(
    item: RecurringRuleItem,
    isSubmitting: Boolean,
    onEdit: () -> Unit,
    onTogglePaused: (Boolean) -> Unit,
    onExecuteNow: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val nextDateLabel = formatEpochDate(item.rule.nextRunAt)
    val payload = item.payload

    DropletCard(
        modifier = Modifier.testTag("recurring_rule_card_${item.rule.name}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.rule.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (item.rule.isPaused) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DropletPillBadge(
                        text = when {
                            item.isTerminated -> "已终止"
                            item.rule.isPaused -> "已暂停"
                            item.rule.autoPost -> "自动入账"
                            else -> "到期提醒"
                        },
                        containerColor = when {
                            item.isTerminated || item.rule.isPaused -> MaterialTheme.colorScheme.surfaceContainerHigh
                            item.rule.autoPost -> semantic.incomeContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = when {
                            item.isTerminated || item.rule.isPaused -> MaterialTheme.colorScheme.onSurfaceVariant
                            item.rule.autoPost -> semantic.income
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier
                            .semantics(mergeDescendants = true) {}
                            .testTag("recurring_rule_status_${item.rule.name}"),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${item.categoryOrTargetName} · ${item.accountName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${item.frequencyLabel} · 下次执行 $nextDateLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (payload != null) {
                    PrivacyAmountText(
                        money = payload.amount,
                        style = MaterialTheme.typography.titleMedium,
                        color = when (payload.displayType) {
                            TransactionDisplayType.INCOME -> semantic.income
                            TransactionDisplayType.EXPENSE -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                }
                if (!item.isTerminated) {
                    Switch(
                        checked = !item.rule.isPaused,
                        onCheckedChange = { active -> onTogglePaused(!active) },
                        modifier = Modifier.testTag("recurring_pause_switch_${item.rule.name}"),
                    )
                }
            }
        }
        if (!item.isTerminated) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onEdit,
                    enabled = !isSubmitting,
                    modifier = Modifier.testTag("recurring_edit_${item.rule.name}"),
                ) {
                    Text("编辑规则")
                }
                TextButton(
                    onClick = onExecuteNow,
                    enabled = !isSubmitting,
                    modifier = Modifier.testTag("recurring_execute_now_${item.rule.name}"),
                ) {
                    Text("立即记一笔")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecurringRuleEditorSheet(
    editingItem: RecurringRuleItem?,
    operationalAccounts: List<Account>,
    expenseCategories: List<Account>,
    incomeCategories: List<Account>,
    availableTags: List<Tag>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSave: (RecurringRuleFormInput) -> Unit,
    onTerminate: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val existingRule = editingItem?.rule
    val existingPayload = editingItem?.payload
    val todayIso = remember { LocalDate.now().format(dateFormatterIso) }

    var name by remember(editingItem) { mutableStateOf(existingRule?.name.orEmpty()) }
    var displayType by remember(editingItem) {
        mutableStateOf(existingPayload?.displayType ?: TransactionDisplayType.EXPENSE)
    }
    var amountText by remember(editingItem) {
        mutableStateOf(
            existingPayload?.amount?.toBigDecimal()?.stripTrailingZeros()?.toPlainString().orEmpty(),
        )
    }
    var accountId by remember(editingItem, operationalAccounts) {
        mutableStateOf(existingPayload?.accountId ?: operationalAccounts.firstOrNull()?.id)
    }
    var targetAccountId by remember(editingItem, operationalAccounts) {
        mutableStateOf(
            existingPayload?.targetAccountId
                ?: operationalAccounts.firstOrNull { it.id != accountId }?.id,
        )
    }
    val activeCategories = when (displayType) {
        TransactionDisplayType.INCOME -> incomeCategories
        else -> expenseCategories
    }
    val selectableCategories = remember(activeCategories) {
        val children = activeCategories.filter { it.parentId != null }
        if (children.isNotEmpty()) children.take(16) else activeCategories.take(16)
    }
    var categoryAccountId by remember(editingItem, displayType, selectableCategories) {
        mutableStateOf(
            existingPayload?.categoryAccountId?.takeIf { id -> activeCategories.any { it.id == id } }
                ?: selectableCategories.firstOrNull()?.id,
        )
    }
    var frequency by remember(editingItem) {
        mutableStateOf(existingRule?.frequency ?: RecurringFrequency.MONTHLY)
    }
    var intervalText by remember(editingItem) {
        mutableStateOf((existingRule?.intervalCount ?: 1).toString())
    }
    var nextRunDateIso by remember(editingItem) {
        mutableStateOf(
            existingRule?.nextRunAt?.let(::formatEpochDate) ?: todayIso,
        )
    }
    var autoPost by remember(editingItem) { mutableStateOf(existingRule?.autoPost ?: true) }
    var payee by remember(editingItem) { mutableStateOf(existingPayload?.payee.orEmpty()) }
    var note by remember(editingItem) { mutableStateOf(existingPayload?.note.orEmpty()) }
    var selectedTagIds by remember(editingItem) {
        mutableStateOf(existingPayload?.tagIds.orEmpty().toSet())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("recurring_editor_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (existingRule == null) "新建周期记账规则" else "编辑周期规则",
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("规则名称") },
                placeholder = { Text("例如 每月房租 / 薪资入账") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recurring_sheet_name_input"),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    TransactionDisplayType.EXPENSE to "支出",
                    TransactionDisplayType.INCOME to "收入",
                    TransactionDisplayType.TRANSFER to "转账",
                ).forEach { (type, label) ->
                    FilterChip(
                        selected = displayType == type,
                        onClick = { displayType = type },
                        label = { Text(label) },
                        modifier = Modifier.testTag("recurring_sheet_type_${type.name}"),
                    )
                }
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { raw -> amountText = raw.filter { it.isDigit() || it == '.' } },
                label = { Text("金额 (${Currency.DEFAULT.symbol})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recurring_sheet_amount_input"),
            )

            Text(
                text = if (displayType == TransactionDisplayType.TRANSFER) "转出账户" else "关联账户",
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                operationalAccounts.forEach { acct ->
                    FilterChip(
                        selected = accountId == acct.id,
                        onClick = { accountId = acct.id },
                        label = { Text(acct.name) },
                        modifier = Modifier.testTag("recurring_sheet_account_${acct.name}"),
                    )
                }
            }

            if (displayType == TransactionDisplayType.TRANSFER) {
                Text(text = "转入账户", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    operationalAccounts.filter { it.id != accountId }.forEach { acct ->
                        FilterChip(
                            selected = targetAccountId == acct.id,
                            onClick = { targetAccountId = acct.id },
                            label = { Text(acct.name) },
                            modifier = Modifier.testTag("recurring_sheet_target_${acct.name}"),
                        )
                    }
                }
            } else {
                Text(text = "分类", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    selectableCategories.forEach { cat ->
                        FilterChip(
                            selected = categoryAccountId == cat.id,
                            onClick = { categoryAccountId = cat.id },
                            label = { Text(cat.name) },
                            modifier = Modifier.testTag("recurring_sheet_category_${cat.name}"),
                        )
                    }
                }
            }

            Text(text = "重复频率", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                supportedFrequencies.forEach { (freq, label) ->
                    FilterChip(
                        selected = frequency == freq,
                        onClick = { frequency = freq },
                        label = { Text(label) },
                        modifier = Modifier.testTag("recurring_sheet_freq_${freq.name}"),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { raw -> intervalText = raw.filter { it.isDigit() }.take(2) },
                    label = { Text("间隔") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.35f),
                )
                OutlinedTextField(
                    value = nextRunDateIso,
                    onValueChange = { nextRunDateIso = it },
                    label = { Text("下次执行日 (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(0.65f)
                        .testTag("recurring_sheet_date_input"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "到期自动入账", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (autoPost) "到期自动生成复式分录并顺延周期" else "到期进入顶部「待确认」队列，由您确认或跳过",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoPost,
                    onCheckedChange = { autoPost = it },
                    modifier = Modifier.testTag("recurring_sheet_autopost_switch"),
                )
            }

            OutlinedTextField(
                value = payee,
                onValueChange = { payee = it },
                label = { Text("商户 / 对手方（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (availableTags.isNotEmpty()) {
                Text(text = "标签（可选）", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    availableTags.forEach { tag ->
                        FilterChip(
                            selected = tag.id in selectedTagIds,
                            onClick = {
                                selectedTagIds = if (tag.id in selectedTagIds) {
                                    selectedTagIds - tag.id
                                } else {
                                    selectedTagIds + tag.id
                                }
                            },
                            label = { Text("#${tag.name}") },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (existingRule != null) {
                    OutlinedButton(
                        onClick = { onTerminate(existingRule.id) },
                    ) {
                        Text("终止")
                    }
                    OutlinedButton(
                        onClick = { onDelete(existingRule.id) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                Button(
                    onClick = {
                        onSave(
                            RecurringRuleFormInput(
                                id = existingRule?.id,
                                name = name,
                                displayType = displayType,
                                amountText = amountText,
                                currency = operationalAccounts.firstOrNull { it.id == accountId }?.currency
                                    ?: Currency.DEFAULT,
                                accountId = accountId,
                                categoryAccountId = categoryAccountId,
                                targetAccountId = targetAccountId,
                                frequency = frequency,
                                intervalCount = intervalText.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                nextRunDateIso = nextRunDateIso,
                                autoPost = autoPost,
                                payee = payee,
                                note = note,
                                tagIds = selectedTagIds.toList(),
                            ),
                        )
                    },
                    enabled = !isSubmitting && name.isNotBlank() && amountText.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("recurring_sheet_save_button"),
                ) {
                    Text(if (isSubmitting) "保存中..." else "保存规则")
                }
            }
        }
    }
}

private fun formatEpochDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFormatterIso)
