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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import io.github.kvtttba.droplet.core.domain.PrivacyAmountFormatter
import io.github.kvtttba.droplet.core.domain.debt.DebtProfileSummary
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DebtLendBorrowSheet(
    isLend: Boolean,
    summary: DebtProfileSummary,
    fundingAccounts: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (accountId: String?, amountText: String, note: String, (String?) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedAccountId by remember(fundingAccounts) {
        mutableStateOf(fundingAccounts.firstOrNull()?.id)
    }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

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
                .testTag(if (isLend) "debt_lend_sheet" else "debt_borrow_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (isLend) "借出给「${summary.profile.name}」" else "向「${summary.profile.name}」借入",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (isLend) {
                    "将同步增加应收往来资产并扣减所选资金账户，不计入个人消费。"
                } else {
                    "将同步增加应付往来负债并入账至所选资金账户，不计入个人收入。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = amountText,
                onValueChange = {
                    amountText = it
                    errorText = null
                },
                label = { Text(if (isLend) "借出金额 (${summary.currency.code})" else "借入金额 (${summary.currency.code})") },
                placeholder = { Text("例如：1000.00") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debt_amount_input"),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (isLend) "付款资金账户" else "收款资金账户",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fundingAccounts.isEmpty()) {
                    Text(
                        text = "暂无匹配币种 (${summary.currency.code}) 的可用资金或信用账户",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        fundingAccounts.forEach { account ->
                            FilterChip(
                                selected = selectedAccountId == account.id,
                                onClick = {
                                    selectedAccountId = account.id
                                    errorText = null
                                },
                                label = { Text(account.name) },
                                modifier = Modifier.testTag("debt_funding_account_${account.name}"),
                            )
                        }
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
                    .testTag("debt_note_input"),
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
                    onSubmit(selectedAccountId, amountText, note) { err ->
                        if (err == null) onDismiss() else errorText = err
                    }
                },
                enabled = !isSubmitting && fundingAccounts.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("debt_sheet_submit_button"),
            ) {
                Text(if (isSubmitting) "正在入账…" else if (isLend) "确认借出" else "确认借入")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DebtCollectRepaySheet(
    isCollect: Boolean,
    summary: DebtProfileSummary,
    fundingAccounts: List<Account>,
    interestCategories: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        fundingAccountId: String?,
        principalText: String,
        interestText: String,
        interestCategoryId: String,
        note: String,
        (String?) -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxPrincipal = if (isCollect) summary.receivableBalance else summary.payableBalance
    val defaultPrincipalText = remember(maxPrincipal) {
        if (maxPrincipal.isPositive) maxPrincipal.toBigDecimal().stripTrailingZeros().toPlainString() else ""
    }
    var selectedAccountId by remember(fundingAccounts) {
        mutableStateOf(fundingAccounts.firstOrNull()?.id)
    }
    val preferredInterestId = if (isCollect) {
        DefaultSeedCatalog.AccountIds.INC_INVEST_INTEREST
    } else {
        DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE
    }
    var selectedInterestCatId by remember(interestCategories) {
        mutableStateOf(
            interestCategories.firstOrNull { it.id == preferredInterestId }?.id
                ?: interestCategories.firstOrNull()?.id
                ?: preferredInterestId,
        )
    }
    var principalText by remember { mutableStateOf(defaultPrincipalText) }
    var interestText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    val parsedPrincipal = remember(principalText, summary.currency) {
        runCatching {
            val cleaned = principalText.trim()
            if (cleaned.isEmpty()) Money.zero(summary.currency) else Money.fromDecimalString(cleaned, summary.currency)
        }.getOrNull()
    }
    val parsedInterest = remember(interestText, summary.currency) {
        runCatching {
            val cleaned = interestText.trim()
            if (cleaned.isEmpty()) Money.zero(summary.currency) else Money.fromDecimalString(cleaned, summary.currency)
        }.getOrNull()
    }
    val totalPreview = if (parsedPrincipal != null && parsedInterest != null && !parsedPrincipal.isNegative && !parsedInterest.isNegative) {
        parsedPrincipal + parsedInterest
    } else {
        null
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
                .testTag(if (isCollect) "debt_collect_sheet" else "debt_repay_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isCollect) "收回「${summary.profile.name}」欠款" else "归还「${summary.profile.name}」借款",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (maxPrincipal.isPositive) {
                    TextButton(
                        onClick = {
                            principalText = maxPrincipal.toBigDecimal().stripTrailingZeros().toPlainString()
                            errorText = null
                        },
                    ) {
                        Text("填入全额")
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isCollect) "当前应收余额" else "当前应付余额",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrivacyAmountText(
                        money = maxPrincipal,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            OutlinedTextField(
                value = principalText,
                onValueChange = {
                    principalText = it
                    errorText = null
                },
                label = { Text(if (isCollect) "本次收回本金 (${summary.currency.code})" else "本次归还本金 (${summary.currency.code})") },
                placeholder = { Text("支持分次收还，可填部分本金") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debt_principal_input"),
            )

            OutlinedTextField(
                value = interestText,
                onValueChange = {
                    interestText = it
                    errorText = null
                },
                label = { Text(if (isCollect) "附加利息收入（选填）" else "附加利息支出（选填）") },
                placeholder = { Text("无息可留空") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debt_interest_input"),
            )

            if (parsedInterest != null && parsedInterest.isPositive && interestCategories.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (isCollect) "利息收入分类" else "利息支出分类",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        interestCategories.take(8).forEach { cat ->
                            FilterChip(
                                selected = selectedInterestCatId == cat.id,
                                onClick = { selectedInterestCatId = cat.id },
                                label = { Text(cat.name) },
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (isCollect) "收款资金账户" else "付款资金账户",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    fundingAccounts.forEach { account ->
                        FilterChip(
                            selected = selectedAccountId == account.id,
                            onClick = {
                                selectedAccountId = account.id
                                errorText = null
                            },
                            label = { Text(account.name) },
                            modifier = Modifier.testTag("debt_funding_account_${account.name}"),
                        )
                    }
                }
            }

            if (totalPreview != null && totalPreview.isPositive) {
                val hidden = LocalAmountsHidden.current
                val formatted = PrivacyAmountFormatter.format(totalPreview, hidden = hidden)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isCollect) "预计实收总额（本+息）" else "预计实付总额（本+息）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = formatted,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
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
                    .testTag("debt_note_input"),
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
                        selectedAccountId,
                        principalText,
                        interestText,
                        selectedInterestCatId,
                        note,
                    ) { err ->
                        if (err == null) onDismiss() else errorText = err
                    }
                },
                enabled = !isSubmitting && fundingAccounts.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("debt_sheet_submit_button"),
            ) {
                Text(if (isSubmitting) "正在入账…" else if (isCollect) "确认收款入账" else "确认还款入账")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DebtBadDebtWriteOffSheet(
    summary: DebtProfileSummary,
    expenseCategories: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (amountText: String, badDebtCategoryId: String, note: String, (String?) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defaultAmount = remember(summary.receivableBalance) {
        if (summary.receivableBalance.isPositive) {
            summary.receivableBalance.toBigDecimal().stripTrailingZeros().toPlainString()
        } else {
            ""
        }
    }
    var amountText by remember { mutableStateOf(defaultAmount) }
    var selectedCategoryId by remember(expenseCategories) {
        mutableStateOf(
            expenseCategories.firstOrNull { it.id == DefaultSeedCatalog.AccountIds.EXP_FINANCE_BAD_DEBT }?.id
                ?: expenseCategories.firstOrNull()?.id
                ?: DefaultSeedCatalog.AccountIds.EXP_FINANCE_BAD_DEBT,
        )
    }
    var note by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

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
                .testTag("debt_write_off_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "坏账核销 · ${summary.profile.name}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
            ) {
                Text(
                    text = "将无法收回的应收欠款一键转为「坏账损失」支出并冲减应收余额，借贷恒平，不涉及现金账户变动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = {
                    amountText = it
                    errorText = null
                },
                label = { Text("核销金额 (${summary.currency.code})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debt_write_off_amount_input"),
            )

            if (expenseCategories.isNotEmpty()) {
                val sortedCats = remember(expenseCategories) {
                    expenseCategories.sortedByDescending {
                        it.id == DefaultSeedCatalog.AccountIds.EXP_FINANCE_BAD_DEBT
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "核销支出分类",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        sortedCats.take(8).forEach { cat ->
                            FilterChip(
                                selected = selectedCategoryId == cat.id,
                                onClick = { selectedCategoryId = cat.id },
                                label = { Text(cat.name) },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（选填）") },
                placeholder = { Text("例如：长期无法追回，转为坏账") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debt_note_input"),
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
                    onSubmit(amountText, selectedCategoryId, note) { err ->
                        if (err == null) onDismiss() else errorText = err
                    }
                },
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("debt_write_off_submit_button"),
            ) {
                Text(if (isSubmitting) "正在核销…" else "确认坏账核销")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditDebtProfileSheet(
    summary: DebtProfileSummary,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, dueDateIso: String, ratePercent: String, note: String, (String?) -> Unit) -> Unit,
    onDelete: ((String?) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val profile = summary.profile
    var name by remember { mutableStateOf(profile.name) }
    var dueDateIso by remember {
        mutableStateOf(
            summary.dueDate?.let { AssetsFormatters.formatLocalDateIso(it) }.orEmpty(),
        )
    }
    var ratePercent by remember {
        mutableStateOf(
            profile.annualInterestRateDecimal?.let { dec ->
                runCatching {
                    BigDecimal(dec).multiply(BigDecimal("100")).stripTrailingZeros().toPlainString()
                }.getOrNull()
            }.orEmpty(),
        )
    }
    var note by remember { mutableStateOf(profile.note.orEmpty()) }
    var errorText by remember { mutableStateOf<String?>(null) }

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
                .testTag("debt_edit_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "维护往来人信息",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    errorText = null
                },
                label = { Text("往来人名称") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debt_edit_name_input"),
            )

            OutlinedTextField(
                value = dueDateIso,
                onValueChange = {
                    dueDateIso = it
                    errorText = null
                },
                label = { Text("约定还款日（YYYY-MM-DD，清空则取消提醒）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debt_edit_due_date_input"),
            )

            OutlinedTextField(
                value = ratePercent,
                onValueChange = {
                    ratePercent = it
                    errorText = null
                },
                label = { Text("约定年利率 %（选填）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debt_edit_rate_input"),
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debt_edit_note_input"),
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
                    onSave(name, dueDateIso, ratePercent, note) { err ->
                        if (err == null) onDismiss() else errorText = err
                    }
                },
                enabled = !isSubmitting,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("debt_edit_save_button"),
            ) {
                Text(if (isSubmitting) "正在保存…" else "保存修改")
            }

            if (summary.transactionCount == 0) {
                OutlinedButton(
                    onClick = {
                        onDelete { err ->
                            if (err == null) onDismiss() else errorText = err
                        }
                    },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("debt_delete_button"),
                ) {
                    Text("删除该往来人（无历史流水）")
                }
            }
        }
    }
}
