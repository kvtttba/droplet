package io.github.kvtttba.droplet.feature.ledger.editor

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.domain.PrivacyAmountFormatter
import io.github.kvtttba.droplet.core.domain.ledger.DiscountMode
import io.github.kvtttba.droplet.core.domain.ledger.TransferFeeMode
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.icon.DropletIconRegistry
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.tabularNums
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun TransactionEditorRoute(
    onNavigateBack: () -> Unit,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.savedSuccessfully) {
        if (uiState.savedSuccessfully) {
            val message = if (uiState.isEditing) "流水修改已保存" else "记账已成功入账"
            onSaved(message)
        }
    }

    TransactionEditorScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onSelectTab = viewModel::selectTab,
        onToggleSplitMode = viewModel::toggleSplitMode,
        onAmountChange = viewModel::onAmountChange,
        onTransactionCurrencyChange = viewModel::onTransactionCurrencyChange,
        onCounterAmountChange = viewModel::onCounterAmountChange,
        onManualRateChange = viewModel::onManualRateChange,
        onSelectParentCategory = viewModel::selectParentCategory,
        onSelectCategory = viewModel::selectCategory,
        onSelectAccount = viewModel::selectAccount,
        onSelectTargetAccount = viewModel::selectTargetAccount,
        onSwapTransferAccounts = viewModel::swapTransferAccounts,
        onToggleReimbursable = viewModel::onToggleReimbursable,
        onDiscountChange = viewModel::onDiscountChange,
        onDiscountModeChange = viewModel::onDiscountModeChange,
        onFeeChange = viewModel::onFeeChange,
        onTransferFeeModeChange = viewModel::onTransferFeeModeChange,
        onToggleAdvancedOptions = viewModel::onToggleAdvancedOptions,
        onOccurredAtChange = viewModel::onOccurredAtChange,
        onToggleTag = viewModel::onToggleTag,
        onPayeeChange = viewModel::onPayeeChange,
        onNoteChange = viewModel::onNoteChange,
        onAddSplitRow = viewModel::addSplitRow,
        onRemoveSplitRow = viewModel::removeSplitRow,
        onUpdateSplitRowAccount = viewModel::updateSplitRowAccount,
        onUpdateSplitRowDirection = viewModel::updateSplitRowDirection,
        onUpdateSplitRowCurrency = viewModel::updateSplitRowCurrency,
        onUpdateSplitRowAmount = viewModel::updateSplitRowAmount,
        onAutoHedgeMultiCurrencyChange = viewModel::onAutoHedgeMultiCurrencyChange,
        onSplitReferenceRateChange = viewModel::onSplitReferenceRateChange,
        onAutoBalanceSplit = viewModel::autoBalanceSplit,
        onSave = viewModel::save,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionEditorScreen(
    uiState: TransactionEditorUiState,
    onNavigateBack: () -> Unit = {},
    onSelectTab: (EditorTab) -> Unit = {},
    onToggleSplitMode: () -> Unit = {},
    onAmountChange: (String) -> Unit = {},
    onTransactionCurrencyChange: (Currency) -> Unit = {},
    onCounterAmountChange: (String) -> Unit = {},
    onManualRateChange: (String) -> Unit = {},
    onSelectParentCategory: (String) -> Unit = {},
    onSelectCategory: (String) -> Unit = {},
    onSelectAccount: (String) -> Unit = {},
    onSelectTargetAccount: (String) -> Unit = {},
    onSwapTransferAccounts: () -> Unit = {},
    onToggleReimbursable: (Boolean) -> Unit = {},
    onDiscountChange: (String) -> Unit = {},
    onDiscountModeChange: (DiscountMode) -> Unit = {},
    onFeeChange: (String) -> Unit = {},
    onTransferFeeModeChange: (TransferFeeMode) -> Unit = {},
    onToggleAdvancedOptions: () -> Unit = {},
    onOccurredAtChange: (Long) -> Unit = {},
    onToggleTag: (String) -> Unit = {},
    onPayeeChange: (String) -> Unit = {},
    onNoteChange: (String) -> Unit = {},
    onAddSplitRow: () -> Unit = {},
    onRemoveSplitRow: (String) -> Unit = {},
    onUpdateSplitRowAccount: (String, String) -> Unit = { _, _ -> },
    onUpdateSplitRowDirection: (String, PostingDirection) -> Unit = { _, _ -> },
    onUpdateSplitRowCurrency: (String, Currency) -> Unit = { _, _ -> },
    onUpdateSplitRowAmount: (String, String) -> Unit = { _, _ -> },
    onAutoHedgeMultiCurrencyChange: (Boolean) -> Unit = {},
    onSplitReferenceRateChange: (Currency, String) -> Unit = { _, _ -> },
    onAutoBalanceSplit: () -> Unit = {},
    onSave: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var pickingAccountForRowId by remember { mutableStateOf<String?>(null) }
    val semantic = DropletTheme.semanticColors

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_transaction_editor"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("editor_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                title = {
                    Text(
                        text = when {
                            uiState.isSplitMode -> "专业多拆分录"
                            uiState.isEditing -> "编辑流水"
                            else -> "记一笔"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    Surface(
                        shape = CircleShape,
                        color = if (uiState.isSplitMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onToggleSplitMode)
                            .testTag("editor_toggle_split_mode"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = DropletIcons.CallSplit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (uiState.isSplitMode) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                            )
                            Text(
                                text = if (uiState.isSplitMode) "直观模式" else "专业分录",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (uiState.isSplitMode) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    uiState.errorMessage?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    uiState.livePreviewSummary?.takeIf { !uiState.isSplitMode }?.let { preview ->
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val saveEnabled = !uiState.isSaving && (
                        if (uiState.isSplitMode) uiState.isSplitReadyToSave else uiState.amountText.isNotBlank()
                        )
                    Button(
                        onClick = onSave,
                        enabled = saveEnabled,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("editor_save_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                uiState.isSaving -> "保存中..."
                                uiState.isEditing -> "保存修改"
                                uiState.isSplitMode -> "校验并原子入账"
                                else -> "保存入账"
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("editor_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!uiState.isSplitMode) {
                // 1. Segmented Tabs: 支出 / 收入 / 转账
                item(key = "tab_selector") {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        EditorTab.entries.forEachIndexed { index, tab ->
                            SegmentedButton(
                                selected = uiState.activeTab == tab,
                                onClick = { onSelectTab(tab) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = EditorTab.entries.size,
                                ),
                                modifier = Modifier.testTag("editor_tab_${tab.name.lowercase()}"),
                            ) {
                                Text(tab.label)
                            }
                        }
                    }
                }

                // 2. Amount + Currency + Cross-currency Implied Rate Card
                item(key = "amount_card") {
                    IntuitiveAmountSection(
                        uiState = uiState,
                        onAmountChange = onAmountChange,
                        onCurrencyChange = onTransactionCurrencyChange,
                        onCounterAmountChange = onCounterAmountChange,
                        onManualRateChange = onManualRateChange,
                    )
                }

                // 3. Yimu-style 2-level Category Picker OR Transfer Accounts
                if (uiState.activeTab == EditorTab.TRANSFER) {
                    item(key = "transfer_accounts") {
                        TransferAccountSection(
                            uiState = uiState,
                            onSelectFrom = onSelectAccount,
                            onSelectTo = onSelectTargetAccount,
                            onSwap = onSwapTransferAccounts,
                        )
                    }
                } else {
                    item(key = "category_picker") {
                        TwoLevelCategoryPickerCard(
                            uiState = uiState,
                            onSelectParent = onSelectParentCategory,
                            onSelectCategory = onSelectCategory,
                        )
                    }
                    item(key = "account_picker") {
                        AccountSelectorCard(
                            title = if (uiState.activeTab == EditorTab.EXPENSE) "付款账户" else "收款账户",
                            accounts = uiState.operationalAccounts,
                            balances = uiState.accountBalances,
                            selectedAccountId = uiState.selectedAccountId,
                            onSelectAccount = onSelectAccount,
                        )
                    }
                }

                // 4. Optional Extras: Reimburse / Discount / Fee
                item(key = "advanced_options") {
                    AdvancedOptionsCard(
                        uiState = uiState,
                        onToggleExpanded = onToggleAdvancedOptions,
                        onToggleReimbursable = onToggleReimbursable,
                        onDiscountChange = onDiscountChange,
                        onDiscountModeChange = onDiscountModeChange,
                        onFeeChange = onFeeChange,
                        onTransferFeeModeChange = onTransferFeeModeChange,
                    )
                }
            } else {
                // PROFESSIONAL SPLIT ENTRY MODE
                item(key = "split_banner") {
                    SplitBalanceBannerCard(
                        uiState = uiState,
                        onAutoBalance = onAutoBalanceSplit,
                        onAutoHedgeChange = onAutoHedgeMultiCurrencyChange,
                    )
                }

                items(uiState.splitRows, key = { it.rowId }) { row ->
                    val account = uiState.allSelectableAccounts.firstOrNull { it.id == row.accountId }
                    SplitPostingRowCard(
                        row = row,
                        account = account,
                        canRemove = uiState.splitRows.size > 2,
                        onPickAccount = { pickingAccountForRowId = row.rowId },
                        onDirectionChange = { dir -> onUpdateSplitRowDirection(row.rowId, dir) },
                        onCurrencyChange = { ccy -> onUpdateSplitRowCurrency(row.rowId, ccy) },
                        onAmountChange = { amt -> onUpdateSplitRowAmount(row.rowId, amt) },
                        onRemove = { onRemoveSplitRow(row.rowId) },
                    )
                }

                item(key = "split_add_row") {
                    FilledTonalButton(
                        onClick = onAddSplitRow,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("split_add_row_button"),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("添加分录行")
                    }
                }

                val foreignCurrencies = uiState.splitRows
                    .map { it.currency }
                    .distinct()
                    .filter { it != uiState.baseCurrency }
                if (foreignCurrencies.isNotEmpty()) {
                    item(key = "split_rates") {
                        DropletCard(contentPadding = PaddingValues(14.dp)) {
                            Text(
                                text = "外币入账参考汇率（锁定历史本位币 ${uiState.baseCurrency.code}）",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            foreignCurrencies.forEach { ccy ->
                                OutlinedTextField(
                                    value = uiState.splitReferenceRates[ccy].orEmpty(),
                                    onValueChange = { onSplitReferenceRateChange(ccy, it) },
                                    label = { Text("1 ${ccy.code} = ? ${uiState.baseCurrency.code}") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 5. Shared Metadata: Date/Time, Multi-Tags, Payee & Note
            item(key = "metadata_card") {
                MetadataSectionCard(
                    uiState = uiState,
                    onClickDateTime = { showDatePicker = true },
                    onQuickToday = { onOccurredAtChange(System.currentTimeMillis()) },
                    onQuickYesterday = {
                        onOccurredAtChange(System.currentTimeMillis() - 86_400_000L)
                    },
                    onToggleTag = onToggleTag,
                    onPayeeChange = onPayeeChange,
                    onNoteChange = onNoteChange,
                )
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showDatePicker) {
        val currentZoned = Instant.ofEpochMilli(uiState.occurredAt).atZone(ZoneId.systemDefault())
        val initialUtcMillis = currentZoned.toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { selectedUtc ->
                            val pickedDate = Instant.ofEpochMilli(selectedUtc)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            val keptTime: LocalTime = currentZoned.toLocalTime()
                            val combined = pickedDate
                                .atTime(keptTime)
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                            onOccurredAtChange(combined)
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("取消")
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    pickingAccountForRowId?.let { rowId ->
        SplitAccountPickerSheet(
            accounts = uiState.allSelectableAccounts,
            onSelect = { accountId ->
                onUpdateSplitRowAccount(rowId, accountId)
                pickingAccountForRowId = null
            },
            onDismiss = { pickingAccountForRowId = null },
        )
    }
}

@Composable
private fun IntuitiveAmountSection(
    uiState: TransactionEditorUiState,
    onAmountChange: (String) -> Unit,
    onCurrencyChange: (Currency) -> Unit,
    onCounterAmountChange: (String) -> Unit,
    onManualRateChange: (String) -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val amountAccent = when (uiState.activeTab) {
        EditorTab.EXPENSE -> semantic.expense
        EditorTab.INCOME -> semantic.income
        EditorTab.TRANSFER -> MaterialTheme.colorScheme.primary
    }
    val primaryCurrency = uiState.sourceCurrencyForIntuitive

    DropletCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (uiState.activeTab) {
                    EditorTab.EXPENSE -> if (uiState.isReimbursable) "垫付金额" else "支出金额"
                    EditorTab.INCOME -> "收入金额"
                    EditorTab.TRANSFER -> "转出金额 (${primaryCurrency.code})"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (uiState.activeTab != EditorTab.TRANSFER) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Currency.entries.forEach { currency ->
                        FilterChip(
                            selected = uiState.transactionCurrency == currency,
                            onClick = { onCurrencyChange(currency) },
                            label = { Text(currency.code, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("editor_currency_${currency.code.lowercase()}"),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = uiState.amountText,
            onValueChange = onAmountChange,
            prefix = {
                Text(
                    text = "${primaryCurrency.symbol} ",
                    style = MaterialTheme.typography.headlineSmall.tabularNums(),
                    color = amountAccent,
                )
            },
            placeholder = {
                Text(
                    text = "0.00",
                    style = MaterialTheme.typography.headlineSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                )
            },
            textStyle = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = amountAccent,
            ).tabularNums(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("editor_amount_input"),
        )

        if (uiState.isCrossCurrencyIntuitive) {
            Spacer(modifier = Modifier.height(12.dp))
            val counterCcy = uiState.counterCurrency
            OutlinedTextField(
                value = uiState.counterAmountText,
                onValueChange = onCounterAmountChange,
                label = {
                    Text(
                        when (uiState.activeTab) {
                            EditorTab.EXPENSE -> "账户实际扣款 (${counterCcy.code})"
                            EditorTab.INCOME -> "账户实际到账 (${counterCcy.code})"
                            EditorTab.TRANSFER -> "目标账户实际到账 (${counterCcy.code})"
                        },
                    )
                },
                prefix = {
                    Text(
                        text = "${counterCcy.symbol} ",
                        style = MaterialTheme.typography.titleMedium.tabularNums(),
                    )
                },
                placeholder = { Text("填入实际入账金额自动反推汇率") },
                textStyle = MaterialTheme.typography.titleMedium.tabularNums(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("editor_counter_amount_input"),
            )

            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("editor_implied_rate_card"),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = uiState.impliedRateSummary
                            ?: "输入双币种实付/实收金额后，将自动反推实际成交汇率并插入隐式 Trading 对冲。",
                        style = MaterialTheme.typography.bodySmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    DropletPillBadge(
                        text = "Equity:Trading 自动恒平 · 历史本位币锁定",
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (uiState.needsManualBaseRateInIntuitive) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = uiState.manualRateText,
                onValueChange = onManualRateChange,
                label = {
                    Text("记账汇率：1 ${primaryCurrency.code} = ? ${uiState.baseCurrency.code}（可手动覆盖）")
                },
                textStyle = MaterialTheme.typography.bodyLarge.tabularNums(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("editor_manual_rate_input"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TwoLevelCategoryPickerCard(
    uiState: TransactionEditorUiState,
    onSelectParent: (String) -> Unit,
    onSelectCategory: (String) -> Unit,
) {
    val groups = uiState.activeCategoryGroups
    val selectedGroup = groups.firstOrNull { it.parent.id == uiState.selectedParentCategoryId }
        ?: groups.firstOrNull()

    DropletCard(contentPadding = PaddingValues(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (uiState.activeTab == EditorTab.EXPENSE) "支出分类（先选大类，再点小类）" else "收入分类",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (uiState.isReimbursable && uiState.activeTab == EditorTab.EXPENSE) {
                DropletPillBadge(
                    text = "待报销模式",
                    containerColor = DropletTheme.semanticColors.warningContainer,
                    contentColor = DropletTheme.semanticColors.warning,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        // Level 1: Parent categories horizontal strip
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(groups, key = { it.parent.id }) { group ->
                val selected = group.parent.id == selectedGroup?.parent?.id
                val catColor = DropletIconRegistry.parseColor(
                    hex = group.parent.color,
                    fallback = MaterialTheme.colorScheme.primary,
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(62.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) catColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerLow,
                        )
                        .clickable { onSelectParent(group.parent.id) }
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                        .testTag("editor_parent_cat_${group.parent.name}"),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (selected) catColor else catColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = DropletIconRegistry.resolve(group.parent.icon),
                            contentDescription = group.parent.name,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimary else catColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = group.parent.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) catColor else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Level 2: Child categories under selected parent
        if (selectedGroup != null && selectedGroup.children.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                selectedGroup.children.forEach { child ->
                    val isSelected = uiState.selectedCategoryId == child.id
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectCategory(child.id) },
                        label = { Text(child.name) },
                        modifier = Modifier.testTag("editor_child_cat_${child.name}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountSelectorCard(
    title: String,
    accounts: List<Account>,
    balances: Map<String, Money>,
    selectedAccountId: String?,
    onSelectAccount: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedAccountId, accounts) {
        val index = accounts.indexOfFirst { it.id == selectedAccountId }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    DropletCard(contentPadding = PaddingValues(14.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(accounts, key = { it.id }) { account ->
                val selected = account.id == selectedAccountId
                val acctColor = DropletIconRegistry.parseColor(
                    hex = account.color,
                    fallback = MaterialTheme.colorScheme.primary,
                )
                val balance = balances[account.id] ?: Money.zero(account.currency)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    border = if (selected) {
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelectAccount(account.id) }
                        .testTag("editor_account_${account.name}"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(acctColor.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = DropletIconRegistry.resolve(account.icon),
                                contentDescription = null,
                                tint = acctColor,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = account.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = account.currency.code,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            PrivacyAmountText(
                                money = balance,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferAccountSection(
    uiState: TransactionEditorUiState,
    onSelectFrom: (String) -> Unit,
    onSelectTo: (String) -> Unit,
    onSwap: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AccountSelectorCard(
            title = "转出账户",
            accounts = uiState.operationalAccounts,
            balances = uiState.accountBalances,
            selectedAccountId = uiState.selectedAccountId,
            onSelectAccount = onSelectFrom,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            FilledTonalButton(
                onClick = onSwap,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Icon(DropletIcons.SwapHoriz, contentDescription = "互换账户", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("互换转出 / 转入", style = MaterialTheme.typography.labelMedium)
            }
        }
        AccountSelectorCard(
            title = "转入账户（支持信用卡还款 / 跨币种换汇）",
            accounts = uiState.operationalAccounts,
            balances = uiState.accountBalances,
            selectedAccountId = uiState.selectedTargetAccountId,
            onSelectAccount = onSelectTo,
        )
    }
}

@Composable
private fun AdvancedOptionsCard(
    uiState: TransactionEditorUiState,
    onToggleExpanded: () -> Unit,
    onToggleReimbursable: (Boolean) -> Unit,
    onDiscountChange: (String) -> Unit,
    onDiscountModeChange: (DiscountMode) -> Unit,
    onFeeChange: (String) -> Unit,
    onTransferFeeModeChange: (TransferFeeMode) -> Unit,
) {
    DropletCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onToggleExpanded)
                .padding(vertical = 4.dp)
                .testTag("editor_advanced_toggle"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (uiState.activeTab) {
                    EditorTab.EXPENSE -> "更多选项：待报销 / 优惠 / 手续费"
                    EditorTab.INCOME -> "更多选项：手续费 / 扣费"
                    EditorTab.TRANSFER -> "更多选项：转账手续费"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = if (uiState.showAdvancedOptions) {
                    Icons.Rounded.KeyboardArrowUp
                } else {
                    Icons.Rounded.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (uiState.showAdvancedOptions) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(modifier = Modifier.height(10.dp))

            if (uiState.activeTab == EditorTab.EXPENSE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("标记为待报销垫付", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "计入应收「待报销」资产，不虚增本月个人支出",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uiState.isReimbursable,
                        onCheckedChange = onToggleReimbursable,
                        modifier = Modifier.testTag("editor_reimburse_switch"),
                    )
                }

                if (!uiState.isReimbursable) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = uiState.discountText,
                        onValueChange = onDiscountChange,
                        label = { Text("优惠 / 满减金额 (${uiState.transactionCurrency.code})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (uiState.discountText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = uiState.discountMode == DiscountMode.NET_EXPENSE,
                                onClick = { onDiscountModeChange(DiscountMode.NET_EXPENSE) },
                                label = { Text("按实付净支出记账") },
                            )
                            FilterChip(
                                selected = uiState.discountMode == DiscountMode.GROSS_WITH_REBATE_INCOME,
                                onClick = { onDiscountModeChange(DiscountMode.GROSS_WITH_REBATE_INCOME) },
                                label = { Text("原价 + 优惠返现收入") },
                            )
                        }
                    }
                }
            }

            if (!uiState.isReimbursable) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = uiState.feeText,
                    onValueChange = onFeeChange,
                    label = { Text("手续费 (${uiState.selectedAccount?.currency?.code ?: "CNY"})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (uiState.activeTab == EditorTab.TRANSFER && uiState.feeText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = uiState.transferFeeMode == TransferFeeMode.SENDER_EXTRA,
                            onClick = { onTransferFeeModeChange(TransferFeeMode.SENDER_EXTRA) },
                            label = { Text("转出方额外承担") },
                        )
                        FilterChip(
                            selected = uiState.transferFeeMode == TransferFeeMode.DEDUCTED_FROM_AMOUNT,
                            onClick = { onTransferFeeModeChange(TransferFeeMode.DEDUCTED_FROM_AMOUNT) },
                            label = { Text("从到账金额扣除") },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitBalanceBannerCard(
    uiState: TransactionEditorUiState,
    onAutoBalance: () -> Unit,
    onAutoHedgeChange: (Boolean) -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val containerColor = if (uiState.isSplitReadyToSave) {
        semantic.incomeContainer
    } else {
        semantic.warningContainer
    }
    val contentColor = if (uiState.isSplitReadyToSave) {
        semantic.income
    } else {
        semantic.warning
    }
    val hasSingleUnbalanced = uiState.currencyImbalances.count { !it.isBalanced } == 1
    val isMultiCurrency = uiState.currencyImbalances.size >= 2

    DropletCard(
        containerColor = containerColor,
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier.testTag("split_balance_banner"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (uiState.isSplitReadyToSave) "复式借贷校验通过" else "实时借贷差额校验",
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = uiState.splitStatusSummary,
                    style = MaterialTheme.typography.bodySmall.tabularNums(),
                    color = contentColor,
                )
            }
            if (hasSingleUnbalanced && !uiState.isSplitReadyToSave) {
                FilledTonalButton(
                    onClick = onAutoBalance,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("split_auto_balance_button"),
                ) {
                    Text("一键补平", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (uiState.currencyImbalances.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                uiState.currencyImbalances.forEach { info ->
                    val badgeText = if (info.isBalanced) {
                        "${info.currency.code} 已平衡"
                    } else {
                        "${info.currency.code} ${PrivacyAmountFormatter.format(info.money, showPositiveSign = true)}"
                    }
                    DropletPillBadge(
                        text = badgeText,
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                        contentColor = if (info.isBalanced) semantic.income else semantic.expense,
                    )
                }
            }
        }

        if (isMultiCurrency) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "跨币种自动插入 Equity:Trading 平衡行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                    checked = uiState.autoHedgeMultiCurrency,
                    onCheckedChange = onAutoHedgeChange,
                )
            }
        }
    }
}

@Composable
private fun SplitPostingRowCard(
    row: SplitRowDraft,
    account: Account?,
    canRemove: Boolean,
    onPickAccount: () -> Unit,
    onDirectionChange: (PostingDirection) -> Unit,
    onCurrencyChange: (Currency) -> Unit,
    onAmountChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val isCurrencyLocked = account?.type?.isBalanceSheet == true
    val hintText = when (account?.type) {
        AccountType.EXPENSE -> if (row.direction == PostingDirection.DEBIT) "支出增加" else "支出冲减/退款"
        AccountType.INCOME -> if (row.direction == PostingDirection.CREDIT) "收入增加" else "收入冲减"
        AccountType.ASSET -> if (row.direction == PostingDirection.DEBIT) "资产流入" else "资产流出"
        AccountType.LIABILITY -> if (row.direction == PostingDirection.DEBIT) "偿还负债" else "新增负债"
        else -> "选择科目"
    }

    DropletCard(contentPadding = PaddingValues(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onPickAccount),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = DropletIconRegistry.resolve(account?.icon),
                        contentDescription = null,
                        tint = DropletIconRegistry.parseColor(account?.color, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(18.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account?.name ?: "点击选择账户 / 分类",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = hintText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "删除分录行",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PostingDirection.entries.forEach { dir ->
                    FilterChip(
                        selected = row.direction == dir,
                        onClick = { onDirectionChange(dir) },
                        label = { Text(dir.shortLabel, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (dir == PostingDirection.DEBIT) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.tertiaryContainer
                            },
                        ),
                    )
                }
            }
            OutlinedTextField(
                value = row.amountText,
                onValueChange = onAmountChange,
                prefix = { Text("${row.currency.symbol} ", style = MaterialTheme.typography.bodyMedium.tabularNums()) },
                placeholder = { Text("0.00") },
                textStyle = MaterialTheme.typography.titleSmall.tabularNums(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            )
        }

        if (!isCurrencyLocked) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Currency.entries.forEach { ccy ->
                    FilterChip(
                        selected = row.currency == ccy,
                        onClick = { onCurrencyChange(ccy) },
                        label = { Text(ccy.code, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetadataSectionCard(
    uiState: TransactionEditorUiState,
    onClickDateTime: () -> Unit,
    onQuickToday: () -> Unit,
    onQuickYesterday: () -> Unit,
    onToggleTag: (String) -> Unit,
    onPayeeChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
) {
    DropletCard(contentPadding = PaddingValues(14.dp)) {
        // Date & Time
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onClickDateTime),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = DropletIcons.CalendarMonth,
                        contentDescription = "选择日期",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = uiState.dateTimeLabel,
                        style = MaterialTheme.typography.labelMedium.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onQuickToday, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("今天", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onQuickYesterday, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("昨天", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Multi-Tag Selector
        if (uiState.availableTags.isNotEmpty()) {
            Text(
                text = "标签（可多选）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                uiState.availableTags.forEach { tag ->
                    val selected = tag.id in uiState.selectedTagIds
                    FilterChip(
                        selected = selected,
                        onClick = { onToggleTag(tag.id) },
                        label = { Text("#${tag.name}") },
                        modifier = Modifier.testTag("editor_tag_${tag.name}"),
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Payee & Note
        OutlinedTextField(
            value = uiState.payee,
            onValueChange = onPayeeChange,
            label = { Text("商户 / 交易对象（可选）") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("editor_payee_input"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = uiState.note,
            onValueChange = onNoteChange,
            label = { Text("备注（可选）") },
            maxLines = 2,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("editor_note_input"),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitAccountPickerSheet(
    accounts: List<Account>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val grouped = remember(accounts) {
        val byId = accounts.associateBy { it.id }
        listOf(
            "资金与资产账户" to accounts.filter { it.type == AccountType.ASSET },
            "信用与负债账户" to accounts.filter { it.type == AccountType.LIABILITY },
            "支出分类" to accounts.filter { it.type == AccountType.EXPENSE },
            "收入分类" to accounts.filter { it.type == AccountType.INCOME },
        ).filter { it.second.isNotEmpty() }.map { (section, list) ->
            section to list.map { acct ->
                val parentName = acct.parentId?.let { byId[it]?.name }
                val label = if (parentName != null) "$parentName / ${acct.name}" else acct.name
                acct to label
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    text = "选择复式科目",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            grouped.forEach { (header, entries) ->
                item(key = "header_$header") {
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                items(entries, key = { it.first.id }) { (account, displayLabel) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(account.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = DropletIconRegistry.resolve(account.icon),
                                    contentDescription = null,
                                    tint = DropletIconRegistry.parseColor(account.color, MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = displayLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Text(
                                text = account.currency.code,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
