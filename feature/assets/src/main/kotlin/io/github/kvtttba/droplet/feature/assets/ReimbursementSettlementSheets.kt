package io.github.kvtttba.droplet.feature.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.debt.ReimbursementItemSummary
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SingleReimbursementSettleSheet(
    item: ReimbursementItemSummary,
    depositAccounts: List<Account>,
    expenseCategories: List<Account>,
    incomeCategories: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        depositAccountId: String?,
        receivedAmountText: String,
        convertShortfallToExpense: Boolean,
        shortfallExpenseAccountId: String?,
        surplusIncomeAccountId: String?,
        note: String,
        (String?) -> Unit,
    ) -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val matchingDeposits = remember(depositAccounts, item.currency) {
        depositAccounts
            .filter { it.currency == item.currency }
            .sortedWith(
                compareByDescending<Account> { it.id == DefaultSeedCatalog.AccountIds.DEBIT_CARD }
                    .thenBy { it.sortOrder },
            )
    }
    var selectedDepositId by remember(matchingDeposits) {
        mutableStateOf(matchingDeposits.firstOrNull()?.id)
    }
    var receivedText by remember(item.remainingPendingAmount) {
        mutableStateOf(item.remainingPendingAmount.toBigDecimal().stripTrailingZeros().toPlainString())
    }
    var convertShortfallToExpense by remember { mutableStateOf(true) }
    var selectedShortfallCategoryId by remember(expenseCategories) {
        mutableStateOf(
            expenseCategories.firstOrNull { it.id == DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TAXI }?.id
                ?: expenseCategories.firstOrNull { it.id == DefaultSeedCatalog.AccountIds.EXP_OTHER_MISC }?.id
                ?: expenseCategories.firstOrNull()?.id,
        )
    }
    var selectedSurplusCategoryId by remember(incomeCategories) {
        mutableStateOf(
            incomeCategories.firstOrNull { it.id == DefaultSeedCatalog.AccountIds.INC_WORK_BONUS }?.id
                ?: incomeCategories.firstOrNull { it.id == DefaultSeedCatalog.AccountIds.INC_OTHER_WINDFALL }?.id
                ?: incomeCategories.firstOrNull()?.id,
        )
    }
    var note by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    val parsedReceived = remember(receivedText, item.currency) {
        runCatching {
            val cleaned = receivedText.trim()
            if (cleaned.isEmpty()) null else Money.fromDecimalString(cleaned, item.currency)
        }.getOrNull()
    }
    val isShortfall = parsedReceived != null && !parsedReceived.isNegative && parsedReceived < item.remainingPendingAmount
    val isSurplus = parsedReceived != null && parsedReceived > item.remainingPendingAmount

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("reimbursement_single_settle_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "报销冲抵到账",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(
                    onClick = {
                        receivedText = item.remainingPendingAmount.toBigDecimal().stripTrailingZeros().toPlainString()
                        errorText = null
                    },
                ) {
                    Text("全额到账")
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.advanceTransaction.payee ?: "待报销垫付",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "剩余待报销",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrivacyAmountText(
                            money = item.remainingPendingAmount,
                            style = MaterialTheme.typography.titleSmall,
                            color = semantic.warning,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = receivedText,
                onValueChange = {
                    receivedText = it
                    errorText = null
                },
                label = { Text("实际报销到账金额 (${item.currency.code})") },
                placeholder = { Text("少于待报销金额时可一键转个人自付") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reimbursement_received_input"),
            )

            if (isShortfall) {
                val diff = item.remainingPendingAmount - parsedReceived!!
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "实报差额处理方式",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = convertShortfallToExpense,
                            onClick = { convertShortfallToExpense = true },
                            label = { Text("差额转个人自付并结清") },
                            modifier = Modifier.testTag("reimbursement_mode_shortfall"),
                        )
                        FilterChip(
                            selected = !convertShortfallToExpense,
                            onClick = { convertShortfallToExpense = false },
                            label = { Text("仅部分报销，余款继续挂账") },
                            modifier = Modifier.testTag("reimbursement_mode_partial"),
                        )
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (convertShortfallToExpense) {
                            semantic.expenseContainer
                        } else {
                            semantic.warningContainer
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (convertShortfallToExpense) "差额将计入个人支出" else "冲抵后剩余待报销",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (convertShortfallToExpense) semantic.expense else semantic.warning,
                            )
                            PrivacyAmountText(
                                money = diff,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (convertShortfallToExpense) semantic.expense else semantic.warning,
                                modifier = Modifier.testTag("reimbursement_diff_preview"),
                            )
                        }
                    }

                    if (convertShortfallToExpense && expenseCategories.isNotEmpty()) {
                        Text(
                            text = "个人自付支出分类",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            expenseCategories.take(10).forEach { cat ->
                                FilterChip(
                                    selected = selectedShortfallCategoryId == cat.id,
                                    onClick = { selectedShortfallCategoryId = cat.id },
                                    label = { Text(cat.name) },
                                    modifier = Modifier.testTag("reimburse_shortfall_cat_${cat.name}"),
                                )
                            }
                        }
                    }
                }
            }

            if (isSurplus) {
                val surplus = parsedReceived!! - item.remainingPendingAmount
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = semantic.incomeContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "超额报销将计入收入",
                            style = MaterialTheme.typography.labelMedium,
                            color = semantic.income,
                        )
                        PrivacyAmountText(
                            money = surplus,
                            style = MaterialTheme.typography.titleSmall,
                            color = semantic.income,
                            showPositiveSign = true,
                        )
                    }
                }
                if (incomeCategories.isNotEmpty()) {
                    Text(
                        text = "超额补贴收入分类",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        incomeCategories.take(8).forEach { cat ->
                            FilterChip(
                                selected = selectedSurplusCategoryId == cat.id,
                                onClick = { selectedSurplusCategoryId = cat.id },
                                label = { Text(cat.name) },
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "报销收款账户",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    matchingDeposits.forEach { account ->
                        FilterChip(
                            selected = selectedDepositId == account.id,
                            onClick = {
                                selectedDepositId = account.id
                                errorText = null
                            },
                            label = { Text(account.name) },
                            modifier = Modifier.testTag("reimburse_deposit_${account.name}"),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（选填）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reimbursement_note_input"),
            )

            errorText?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    onSubmit(
                        selectedDepositId,
                        receivedText,
                        convertShortfallToExpense,
                        selectedShortfallCategoryId,
                        selectedSurplusCategoryId,
                        note,
                    ) { err ->
                        if (err == null) onDismiss() else errorText = err
                    }
                },
                enabled = !isSubmitting && matchingDeposits.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("reimbursement_settle_confirm_button"),
            ) {
                Text(if (isSubmitting) "正在入账…" else "确认报销入账")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BatchReimbursementSettleSheet(
    selectedItems: List<ReimbursementItemSummary>,
    depositAccounts: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (depositAccountId: String?, note: String, (String?) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val distinctCurrencies = remember(selectedItems) {
        selectedItems.map { it.currency }.distinct()
    }
    val hasMixedCurrencies = distinctCurrencies.size > 1
    val currency = distinctCurrencies.firstOrNull() ?: io.github.kvtttba.droplet.core.model.Currency.CNY
    val matchingDeposits = remember(depositAccounts, currency) {
        depositAccounts
            .filter { it.currency == currency }
            .sortedWith(
                compareByDescending<Account> { it.id == DefaultSeedCatalog.AccountIds.DEBIT_CARD }
                    .thenBy { it.sortOrder },
            )
    }
    var selectedDepositId by remember(matchingDeposits) {
        mutableStateOf(matchingDeposits.firstOrNull()?.id)
    }
    var note by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    val totalSelected = remember(selectedItems, currency) {
        val minor = selectedItems
            .filter { it.currency == currency }
            .fold(0L) { acc, item -> Math.addExact(acc, item.remainingPendingAmount.amountMinor) }
        Money(minor, currency)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("reimbursement_batch_settle_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "批量全额报销到账",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (hasMixedCurrencies) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = "当前选中了多币种待报销单（${distinctCurrencies.joinToString("/") { it.code }}），请按币种分批勾选结算。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "共选中 ${selectedItems.size} 笔待报销",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        PrivacyAmountText(
                            money = totalSelected,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "报销收款账户",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    matchingDeposits.forEach { account ->
                        FilterChip(
                            selected = selectedDepositId == account.id,
                            onClick = {
                                selectedDepositId = account.id
                                errorText = null
                            },
                            label = { Text(account.name) },
                            modifier = Modifier.testTag("reimburse_batch_deposit_${account.name}"),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（选填）") },
                placeholder = { Text("例如：11月差旅批量报销到账") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            errorText?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    onSubmit(selectedDepositId, note) { err ->
                        if (err == null) onDismiss() else errorText = err
                    }
                },
                enabled = !isSubmitting && !hasMixedCurrencies && matchingDeposits.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("reimbursement_batch_confirm_button"),
            ) {
                Text(if (isSubmitting) "正在批量冲抵…" else "确认批量结清 (${selectedItems.size} 笔)")
            }
        }
    }
}
