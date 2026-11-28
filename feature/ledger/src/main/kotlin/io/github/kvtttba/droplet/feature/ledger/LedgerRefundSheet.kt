package io.github.kvtttba.droplet.feature.ledger

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
import io.github.kvtttba.droplet.core.domain.debt.ExpenseRefundSummary
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RecordRefundBottomSheet(
    itemTitle: String,
    summary: ExpenseRefundSummary,
    refundEligibleAccounts: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        refundToAccountId: String?,
        refundAmountText: String,
        expenseReductionText: String?,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sortedAccounts = remember(refundEligibleAccounts, summary) {
        refundEligibleAccounts.sortedWith(
            compareByDescending<Account> { it.id == summary.originalPaymentAccountId }
                .thenByDescending { it.currency == summary.expenseCurrency }
                .thenBy { it.sortOrder }
                .thenBy { it.name },
        )
    }
    var selectedAccountId by remember(sortedAccounts) {
        mutableStateOf(
            sortedAccounts.firstOrNull { it.id == summary.originalPaymentAccountId }?.id
                ?: sortedAccounts.firstOrNull { it.currency == summary.expenseCurrency }?.id
                ?: sortedAccounts.firstOrNull()?.id,
        )
    }
    val selectedAccount = sortedAccounts.firstOrNull { it.id == selectedAccountId }
    val isCrossCurrency = selectedAccount != null && selectedAccount.currency != summary.expenseCurrency

    val remainingPlain = remember(summary.remainingRefundableAmount) {
        summary.remainingRefundableAmount.toBigDecimal().stripTrailingZeros().toPlainString()
    }
    var refundAmountText by remember(summary.originalTransactionId, summary.refundCount) {
        mutableStateOf(remainingPlain)
    }
    var reductionText by remember(summary.originalTransactionId, summary.refundCount) {
        mutableStateOf(remainingPlain)
    }
    var note by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    val activeCurrency = selectedAccount?.currency ?: summary.expenseCurrency
    val parsedReduction = remember(refundAmountText, reductionText, isCrossCurrency, summary.expenseCurrency) {
        val raw = if (isCrossCurrency) reductionText.trim() else refundAmountText.trim()
        if (raw.isEmpty()) {
            null
        } else {
            runCatching { Money.fromDecimalString(raw, summary.expenseCurrency) }.getOrNull()
        }
    }
    val exceedsCap = parsedReduction != null && parsedReduction > summary.remainingRefundableAmount
    val previewNetAfter = if (parsedReduction != null && parsedReduction.isPositive && !exceedsCap) {
        summary.netExpenseAmount - parsedReduction
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
                .testTag("record_refund_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "发起关联退款",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = itemTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        refundAmountText = remainingPlain
                        reductionText = remainingPlain
                        errorText = null
                    },
                    modifier = Modifier.testTag("refund_fill_remaining_button"),
                ) {
                    Text("全额退回剩余")
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RefundStatRow(
                        label = "原支出金额",
                        money = summary.originalExpenseAmount,
                    )
                    RefundStatRow(
                        label = "累计已退款 (${summary.refundCount} 笔)",
                        money = summary.cumulativeRefundedAmount,
                        valueColor = if (summary.hasRefunds) semantic.income else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RefundStatRow(
                        label = "剩余可退上限",
                        money = summary.remainingRefundableAmount,
                        valueColor = MaterialTheme.colorScheme.primary,
                        valueTestTag = "refund_sheet_remaining_cap",
                    )
                }
            }

            OutlinedTextField(
                value = refundAmountText,
                onValueChange = {
                    refundAmountText = it
                    errorText = null
                },
                label = { Text("本次退款到账金额 (${activeCurrency.code})") },
                placeholder = { Text("支持全额、部分或分次退款") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = exceedsCap,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("refund_amount_input"),
            )

            if (isCrossCurrency) {
                OutlinedTextField(
                    value = reductionText,
                    onValueChange = {
                        reductionText = it
                        errorText = null
                    },
                    label = { Text("冲减原支出金额 (${summary.expenseCurrency.code})") },
                    placeholder = { Text("折算冲减的原单币种金额") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = exceedsCap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("refund_reduction_input"),
                )
            }

            if (exceedsCap) {
                Text(
                    text = "退款金额超过剩余可退上限，请调整金额",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (previewNetAfter != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = semantic.incomeContainer.copy(alpha = 0.72f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "本次退款后原单净支出",
                            style = MaterialTheme.typography.labelMedium,
                            color = semantic.income,
                        )
                        PrivacyAmountText(
                            money = previewNetAfter,
                            style = MaterialTheme.typography.titleSmall,
                            color = semantic.income,
                            modifier = Modifier.testTag("refund_preview_net_expense"),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "退回账户",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    sortedAccounts.forEach { account ->
                        FilterChip(
                            selected = selectedAccountId == account.id,
                            onClick = {
                                selectedAccountId = account.id
                                errorText = null
                            },
                            label = {
                                Text(
                                    if (account.currency == summary.expenseCurrency) {
                                        account.name
                                    } else {
                                        "${account.name} (${account.currency.code})"
                                    },
                                )
                            },
                            modifier = Modifier.testTag("refund_account_${account.name}"),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("退款备注（选填）") },
                placeholder = { Text("例如：部分商品退货、退运费") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("refund_note_input"),
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
                        refundAmountText,
                        if (isCrossCurrency) reductionText else null,
                        note,
                    ) { err ->
                        if (err == null) onDismiss() else errorText = err
                    }
                },
                enabled = !isSubmitting && !exceedsCap && sortedAccounts.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("refund_submit_button"),
            ) {
                Text(if (isSubmitting) "正在入账…" else "确认退款入账")
            }
        }
    }
}

@Composable
private fun RefundStatRow(
    label: String,
    money: Money,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    valueTestTag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrivacyAmountText(
            money = money,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            modifier = if (valueTestTag != null) Modifier.testTag(valueTestTag) else Modifier,
        )
    }
}
