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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.database.repository.TransactionTemplateItem
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
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

@Composable
fun TemplateManagementRoute(
    onNavigateBack: () -> Unit,
    onToggleAmountsHidden: () -> Unit,
    onOpenEditorWithTemplate: (String) -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TemplateManagementViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    TemplateManagementScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onToggleAmountsHidden = onToggleAmountsHidden,
        onPostDirectly = viewModel::postDirectly,
        onPrefillEditor = onOpenEditorWithTemplate,
        onSaveTemplate = viewModel::saveTemplate,
        onDeleteTemplate = viewModel::deleteTemplate,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TemplateManagementScreen(
    uiState: TemplateManagementUiState,
    onNavigateBack: () -> Unit = {},
    onToggleAmountsHidden: () -> Unit = {},
    onPostDirectly: (String) -> Unit = {},
    onPrefillEditor: (String) -> Unit = {},
    onSaveTemplate: (TemplateFormInput, (String?) -> Unit) -> Unit = { _, cb -> cb(null) },
    onDeleteTemplate: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    var editingItem by remember { mutableStateOf<TransactionTemplateItem?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_template_management"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "快捷模板",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("template_back_button"),
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
                        modifier = Modifier.testTag("template_add_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "新建快捷模板",
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
                .testTag("template_management_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "template_hero") {
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
                                imageVector = DropletIcons.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "高频一键记账模板",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "通勤地铁、固定午餐、咖啡等高频消费一键直接入账或预填表单微调",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DropletPillBadge(
                            text = "已配置 ${uiState.templates.size} 个模板",
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                        DropletPillBadge(
                            text = "累计一键使用 ${uiState.totalUsageCount} 次",
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            item(key = "template_list_header") {
                DropletSectionHeader(
                    title = "我的快捷模板",
                    trailing = "点击卡片可编辑",
                )
            }

            if (uiState.templates.isEmpty() && !uiState.isLoading) {
                item(key = "template_empty") {
                    DropletCard {
                        Text(
                            text = "暂无快捷模板。点击右上角「+」创建常用记账模板（如通勤地铁 ¥6.00），创建后在模板页与首页均可一键入账。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = { showCreateSheet = true },
                            modifier = Modifier.testTag("template_empty_add_button"),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("新建快捷模板")
                        }
                    }
                }
            } else {
                items(
                    items = uiState.templates,
                    key = { "tpl_${it.template.id}" },
                ) { item ->
                    TemplateCard(
                        item = item,
                        isSubmitting = uiState.isSubmitting,
                        onEdit = { editingItem = item },
                        onPostDirectly = { onPostDirectly(item.template.id) },
                        onPrefillEditor = { onPrefillEditor(item.template.id) },
                    )
                }
            }
        }
    }

    val sheetTarget = editingItem
    if (showCreateSheet || sheetTarget != null) {
        TemplateEditorBottomSheet(
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
                onSaveTemplate(input) { err ->
                    if (err == null) {
                        showCreateSheet = false
                        editingItem = null
                    }
                }
            },
            onDelete = { id ->
                onDeleteTemplate(id)
                editingItem = null
            },
        )
    }
}

@Composable
private fun TemplateCard(
    item: TransactionTemplateItem,
    isSubmitting: Boolean,
    onEdit: () -> Unit,
    onPostDirectly: () -> Unit,
    onPrefillEditor: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val accent = DropletIconRegistry.parseColor(item.resolvedColor, MaterialTheme.colorScheme.primary)
    val payload = item.payload

    DropletCard(
        modifier = Modifier.testTag("template_card_${item.template.name}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DropletIconRegistry.resolve(item.resolvedIcon),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.template.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.template.useCount > 0) {
                        DropletPillBadge(
                            text = "已用 ${item.template.useCount} 次",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${item.categoryOrTargetName} · ${item.accountName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
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
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onPrefillEditor,
                enabled = !isSubmitting,
                modifier = Modifier
                    .weight(1f)
                    .testTag("template_prefill_button_${item.template.name}"),
            ) {
                Text("预填记账")
            }
            Button(
                onClick = onPostDirectly,
                enabled = !isSubmitting,
                modifier = Modifier
                    .weight(1f)
                    .testTag("template_post_button_${item.template.name}"),
            ) {
                Text("直接入账")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TemplateEditorBottomSheet(
    editingItem: TransactionTemplateItem?,
    operationalAccounts: List<Account>,
    expenseCategories: List<Account>,
    incomeCategories: List<Account>,
    availableTags: List<Tag>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSave: (TemplateFormInput) -> Unit,
    onDelete: (String) -> Unit,
) {
    val existing = editingItem?.template
    val existingPayload = editingItem?.payload

    var name by remember(editingItem) { mutableStateOf(existing?.name.orEmpty()) }
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
                .testTag("template_editor_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (existing == null) "新建快捷模板" else "编辑快捷模板",
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("模板名称") },
                placeholder = { Text("例如 通勤地铁 / 工作日午餐") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("template_sheet_name_input"),
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
                        modifier = Modifier.testTag("template_sheet_type_${type.name}"),
                    )
                }
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { raw -> amountText = raw.filter { it.isDigit() || it == '.' } },
                label = { Text("预设金额 (${Currency.DEFAULT.symbol})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("template_sheet_amount_input"),
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
                        modifier = Modifier.testTag("template_sheet_account_${acct.name}"),
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
                            modifier = Modifier.testTag("template_sheet_category_${cat.name}"),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = payee,
                onValueChange = { payee = it },
                label = { Text("商户 / 摘要（可选）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("template_sheet_payee_input"),
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (existing != null) {
                    OutlinedButton(
                        onClick = { onDelete(existing.id) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("删除")
                    }
                }
                Button(
                    onClick = {
                        onSave(
                            TemplateFormInput(
                                id = existing?.id,
                                name = name,
                                displayType = displayType,
                                amountText = amountText,
                                currency = operationalAccounts.firstOrNull { it.id == accountId }?.currency
                                    ?: Currency.DEFAULT,
                                accountId = accountId,
                                categoryAccountId = categoryAccountId,
                                targetAccountId = targetAccountId,
                                payee = payee,
                                note = note,
                                tagIds = selectedTagIds.toList(),
                            ),
                        )
                    },
                    enabled = !isSubmitting && name.isNotBlank() && amountText.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("template_sheet_save_button"),
                ) {
                    Text(if (isSubmitting) "保存中..." else "保存模板")
                }
            }
        }
    }
}
