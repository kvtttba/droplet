package io.github.kvtttba.droplet.feature.ledger.manage

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.icon.DropletIconRegistry
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.feature.ledger.editor.TransactionEditorViewModel

@Composable
fun AccountManagementRoute(
    onNavigateBack: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AccountManagementViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    AccountManagementScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onToggleShowArchived = viewModel::toggleShowArchived,
        onSaveAccount = viewModel::saveAccount,
        onSetArchived = viewModel::setArchived,
        onDeleteAccount = viewModel::deleteAccountIfUnused,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManagementScreen(
    uiState: AccountManagementUiState,
    onNavigateBack: () -> Unit = {},
    onToggleShowArchived: () -> Unit = {},
    onSaveAccount: (AccountSaveRequest, (Boolean) -> Unit) -> Unit = { _, cb -> cb(true) },
    onSetArchived: (String, Boolean) -> Unit = { _, _ -> },
    onDeleteAccount: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var editingAccount by remember { mutableStateOf<Account?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_account_management"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text("账户管理", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "活跃账户 ${uiState.totalActiveCount} 个 · 支持期初平衡建账",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    FilterChip(
                        selected = uiState.showArchived,
                        onClick = onToggleShowArchived,
                        label = {
                            Text(
                                text = "已归档 (${uiState.archivedAccounts.size})",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .testTag("account_toggle_archived"),
                    )
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("account_add_button"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "新建账户")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            uiState.activeGroups.forEach { group ->
                item(key = "section_${group.title}") {
                    DropletSectionHeader(
                        title = group.title,
                        trailing = "${group.accounts.size} 个",
                    )
                }
                item(key = "card_${group.title}") {
                    DropletCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                        group.accounts.forEachIndexed { index, item ->
                            AccountManageRow(
                                item = item,
                                onClick = { editingAccount = item.account },
                            )
                            if (index != group.accounts.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 52.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.showArchived && uiState.archivedAccounts.isNotEmpty()) {
                item(key = "section_archived") {
                    DropletSectionHeader(
                        title = "已归档账户",
                        trailing = "点击可恢复",
                    )
                }
                item(key = "card_archived") {
                    DropletCard(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        uiState.archivedAccounts.forEachIndexed { index, item ->
                            AccountManageRow(
                                item = item,
                                onClick = { editingAccount = item.account },
                            )
                            if (index != uiState.archivedAccounts.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 52.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        AccountEditorBottomSheet(
            existing = null,
            onDismiss = { showCreateSheet = false },
            onSave = { req ->
                onSaveAccount(req) { ok -> if (ok) showCreateSheet = false }
            },
            onSetArchived = { _, _ -> },
            onDelete = {},
        )
    }

    editingAccount?.let { account ->
        AccountEditorBottomSheet(
            existing = account,
            onDismiss = { editingAccount = null },
            onSave = { req ->
                onSaveAccount(req) { ok -> if (ok) editingAccount = null }
            },
            onSetArchived = { id, archived ->
                onSetArchived(id, archived)
                editingAccount = null
            },
            onDelete = { id ->
                onDeleteAccount(id)
                editingAccount = null
            },
        )
    }
}

@Composable
private fun AccountManageRow(
    item: AccountWithBalance,
    onClick: () -> Unit,
) {
    val account = item.account
    val semantic = DropletTheme.semanticColors
    val tintColor = DropletIconRegistry.parseColor(account.color, MaterialTheme.colorScheme.primary)
    val subtitleParts = buildList {
        add(AccountManagementViewModel.subTypeLabel(account.subType))
        add(account.currency.code)
        if (!account.includeInNetWorth) add("不计入总资产")
        if (account.subType == AccountSubType.CREDIT_CARD) {
            if (account.billingDay != null && account.dueDay != null) {
                add("账单${account.billingDay}日/还款${account.dueDay}日")
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag("account_row_${account.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tintColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DropletIconRegistry.resolve(account.icon),
                contentDescription = null,
                tint = tintColor,
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
                    text = account.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (account.isArchived) {
                    DropletPillBadge(
                        text = "已归档",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitleParts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            PrivacyAmountText(
                money = item.balance,
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    account.type == AccountType.LIABILITY && item.balance.isNegative -> semantic.expense
                    item.balance.isPositive -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            account.creditLimit?.let { limit ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "额度",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrivacyAmountText(
                        money = limit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AccountEditorBottomSheet(
    existing: Account?,
    onDismiss: () -> Unit,
    onSave: (AccountSaveRequest) -> Unit,
    onSetArchived: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var subType by remember(existing) { mutableStateOf(existing?.subType ?: AccountSubType.DEBIT_CARD) }
    var currency by remember(existing) { mutableStateOf(existing?.currency ?: Currency.CNY) }
    var iconKey by remember(existing) { mutableStateOf(existing?.icon ?: "account_balance") }
    var colorHex by remember(existing) { mutableStateOf(existing?.color ?: "#1565C0") }
    var includeInNetWorth by remember(existing) { mutableStateOf(existing?.includeInNetWorth ?: true) }
    var openingBalanceText by remember(existing) { mutableStateOf("") }
    var openingRateText by remember(existing, currency) {
        mutableStateOf(
            if (currency == Currency.DEFAULT) {
                ""
            } else {
                LedgerRepository.fallbackReferenceRate(currency, Currency.DEFAULT).rateDecimal
            },
        )
    }
    var creditLimitText by remember(existing) {
        mutableStateOf(
            existing?.creditLimit?.toBigDecimal()?.stripTrailingZeros()?.toPlainString().orEmpty(),
        )
    }
    var billingDayText by remember(existing) { mutableStateOf(existing?.billingDay?.toString().orEmpty()) }
    var dueDayText by remember(existing) { mutableStateOf(existing?.dueDay?.toString().orEmpty()) }
    var remindDaysText by remember(existing) { mutableStateOf(existing?.remindDaysBefore?.toString().orEmpty()) }

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
                .padding(bottom = 28.dp)
                .testTag("account_editor_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (existing == null) "新建账户" else "编辑账户",
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("账户名称（如 招行储蓄卡 / 美元现汇）") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_name_input"),
            )

            // Account SubType
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("账户类型", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AccountManagementViewModel.creatableSubTypes.forEach { (typeOption, label) ->
                        FilterChip(
                            selected = subType == typeOption,
                            onClick = { subType = typeOption },
                            label = { Text(label) },
                            enabled = existing == null || existing.subType == typeOption,
                        )
                    }
                }
            }

            // Currency selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (existing == null) "账户币种" else "账户币种（建账后锁定以保证复式一致）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Currency.entries.forEach { ccy ->
                        FilterChip(
                            selected = currency == ccy,
                            onClick = {
                                currency = ccy
                                if (ccy != Currency.DEFAULT) {
                                    openingRateText = LedgerRepository.fallbackReferenceRate(ccy, Currency.DEFAULT).rateDecimal
                                }
                            },
                            label = { Text("${ccy.code} ${ccy.displayName}") },
                            enabled = existing == null,
                            modifier = Modifier.testTag("account_currency_${ccy.code.lowercase()}"),
                        )
                    }
                }
            }

            // Opening balance on creation
            if (existing == null) {
                OutlinedTextField(
                    value = openingBalanceText,
                    onValueChange = { openingBalanceText = TransactionEditorViewModel.sanitizeDecimalInput(it) },
                    label = {
                        Text(
                            if (AccountManagementViewModel.accountTypeForSubType(subType) == AccountType.LIABILITY) {
                                "初始待还欠款（可选，自动平入期初权益）"
                            } else {
                                "初始余额（可选，自动平入期初权益）"
                            },
                        )
                    },
                    prefix = { Text("${currency.symbol} ") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("account_opening_balance_input"),
                )

                if (currency != Currency.DEFAULT && openingBalanceText.isNotBlank()) {
                    OutlinedTextField(
                        value = openingRateText,
                        onValueChange = {
                            openingRateText = TransactionEditorViewModel.sanitizeDecimalInput(it, maxDecimals = 8)
                        },
                        label = { Text("期初折算汇率：1 ${currency.code} = ? ${Currency.DEFAULT.code}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Credit Card specific parameters
            if (subType == AccountSubType.CREDIT_CARD) {
                DropletCard(contentPadding = PaddingValues(12.dp)) {
                    Text(
                        text = "信用账户参数（可选）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = creditLimitText,
                        onValueChange = { creditLimitText = TransactionEditorViewModel.sanitizeDecimalInput(it) },
                        label = { Text("信用额度 (${currency.code})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = billingDayText,
                            onValueChange = { billingDayText = it.filter(Char::isDigit).take(2) },
                            label = { Text("账单日") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = dueDayText,
                            onValueChange = { dueDayText = it.filter(Char::isDigit).take(2) },
                            label = { Text("还款日") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = remindDaysText,
                            onValueChange = { remindDaysText = it.filter(Char::isDigit).take(2) },
                            label = { Text("提前提醒") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Include in net worth toggle
            DropletCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("计入总资产", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "关闭后不计入可支配净资产（如公积金/医保卡）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = includeInNetWorth,
                        onCheckedChange = { includeInNetWorth = it },
                        modifier = Modifier.testTag("account_net_worth_switch"),
                    )
                }
            }

            // Color & Icon pickers
            ColorAndIconPicker(
                selectedColorHex = colorHex,
                selectedIconKey = iconKey,
                onColorSelect = { colorHex = it },
                onIconSelect = { iconKey = it },
            )

            // Save + Archive / Delete actions
            Button(
                onClick = {
                    onSave(
                        AccountSaveRequest(
                            existingAccount = existing,
                            name = name,
                            subType = subType,
                            currency = currency,
                            icon = iconKey,
                            color = colorHex,
                            includeInNetWorth = includeInNetWorth,
                            openingBalanceText = openingBalanceText,
                            openingRateText = openingRateText,
                            creditLimitText = creditLimitText,
                            billingDayText = billingDayText,
                            dueDayText = dueDayText,
                            remindDaysBeforeText = remindDaysText,
                        ),
                    )
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("account_save_button"),
            ) {
                Text(if (existing == null) "创建账户" else "保存修改")
            }

            if (existing != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onSetArchived(existing.id, !existing.isArchived) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("account_archive_button"),
                    ) {
                        Icon(DropletIcons.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (existing.isArchived) "恢复账户" else "归档隐藏")
                    }
                    OutlinedButton(
                        onClick = { onDelete(existing.id) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ColorAndIconPicker(
    selectedColorHex: String,
    selectedIconKey: String,
    onColorSelect: (String) -> Unit,
    onIconSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("主题配色", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(DropletIconRegistry.colorPalette) { hex ->
                val color = DropletIconRegistry.parseColor(hex, Color(0xFF006970))
                val selected = hex.equals(selectedColorHex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { onColorSelect(hex) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "已选颜色",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        Text("图标", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DropletIconRegistry.allOptions) { option ->
                val selected = option.key == selectedIconKey
                val tint = DropletIconRegistry.parseColor(selectedColorHex, MaterialTheme.colorScheme.primary)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) tint.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = if (selected) BorderStroke(1.5.dp, tint) else null,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onIconSelect(option.key) },
                ) {
                    Box(
                        modifier = Modifier.padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = option.vector,
                            contentDescription = option.label,
                            tint = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
