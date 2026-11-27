package io.github.kvtttba.droplet.feature.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kvtttba.droplet.core.domain.credit.CreditCardRepaymentHelper
import io.github.kvtttba.droplet.core.domain.credit.CreditCardRepaymentMode
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementSummary
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import java.math.BigDecimal
import java.math.RoundingMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CreditCardRepaymentBottomSheet(
    summary: CreditCardStatementSummary,
    paymentAccounts: List<Account>,
    exchangeRateTable: ExchangeRateTable,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        paymentAccountId: String?,
        repaymentAmountText: String,
        paidFromAmountText: String,
        feeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defaultMode = remember(summary) { CreditCardRepaymentHelper.defaultRepaymentMode(summary) }
    var selectedMode by remember(summary) { mutableStateOf(defaultMode) }
    var selectedAccountId by remember(paymentAccounts) { mutableStateOf(paymentAccounts.firstOrNull()?.id) }

    fun formatMoneyForInput(money: Money): String {
        if (!money.isPositive) return ""
        val bd = money.toBigDecimal().stripTrailingZeros()
        val scale = bd.scale().coerceIn(2, 4)
        return bd.setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }

    var repaymentAmountText by remember(summary) {
        val suggested = when (defaultMode) {
            CreditCardRepaymentMode.STATEMENT_DUE -> summary.remainingStatementDue
            CreditCardRepaymentMode.FULL_BALANCE -> summary.currentDebt
            CreditCardRepaymentMode.CUSTOM -> Money.zero(summary.account.currency)
        }
        mutableStateOf(formatMoneyForInput(suggested))
    }
    var paidFromAmountText by remember { mutableStateOf("") }
    var isPaidFromManuallyEdited by remember { mutableStateOf(false) }
    var feeText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val selectedPaymentAccount = paymentAccounts.firstOrNull { it.id == selectedAccountId }
    val isCrossCurrency = selectedPaymentAccount != null &&
        selectedPaymentAccount.currency != summary.account.currency

    LaunchedEffect(repaymentAmountText, selectedPaymentAccount?.id, exchangeRateTable) {
        if (isCrossCurrency && !isPaidFromManuallyEdited) {
            val estimated = CreditCardDetailViewModel.estimateCrossCurrencyPaidAmount(
                repaymentAmountText = repaymentAmountText,
                cardCurrency = summary.account.currency,
                paymentAccount = selectedPaymentAccount,
                exchangeRateTable = exchangeRateTable,
            )
            paidFromAmountText = estimated?.let { formatMoneyForInput(it) }.orEmpty()
        } else if (!isCrossCurrency) {
            paidFromAmountText = ""
            isPaidFromManuallyEdited = false
        }
    }

    val previewRepayMoney = remember(repaymentAmountText, summary.account.currency) {
        runCatching {
            Money.fromDecimalString(repaymentAmountText.trim(), summary.account.currency)
        }.getOrNull()?.takeIf { it.isPositive }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("credit_card_repayment_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "一键还款 · ${summary.account.name}",
                style = MaterialTheme.typography.titleLarge,
            )

            // Quick amount preset modes
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (summary.remainingStatementDue.isPositive) {
                    FilterChip(
                        selected = selectedMode == CreditCardRepaymentMode.STATEMENT_DUE,
                        onClick = {
                            selectedMode = CreditCardRepaymentMode.STATEMENT_DUE
                            repaymentAmountText = formatMoneyForInput(summary.remainingStatementDue)
                            isPaidFromManuallyEdited = false
                            errorMessage = null
                        },
                        label = { Text("还本期账单") },
                        modifier = Modifier.testTag("repay_mode_statement"),
                    )
                }
                if (summary.currentDebt.isPositive) {
                    FilterChip(
                        selected = selectedMode == CreditCardRepaymentMode.FULL_BALANCE,
                        onClick = {
                            selectedMode = CreditCardRepaymentMode.FULL_BALANCE
                            repaymentAmountText = formatMoneyForInput(summary.currentDebt)
                            isPaidFromManuallyEdited = false
                            errorMessage = null
                        },
                        label = { Text("还清全部欠款") },
                        modifier = Modifier.testTag("repay_mode_full"),
                    )
                }
                FilterChip(
                    selected = selectedMode == CreditCardRepaymentMode.CUSTOM,
                    onClick = {
                        selectedMode = CreditCardRepaymentMode.CUSTOM
                        errorMessage = null
                    },
                    label = { Text("自定义金额") },
                    modifier = Modifier.testTag("repay_mode_custom"),
                )
            }

            // Payment account selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "付款资金账户",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (paymentAccounts.isEmpty()) {
                    Text(
                        text = "暂无可用的现金/储蓄卡账户，请先在账户管理中创建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        paymentAccounts.forEach { account ->
                            FilterChip(
                                selected = selectedAccountId == account.id,
                                onClick = {
                                    selectedAccountId = account.id
                                    isPaidFromManuallyEdited = false
                                    errorMessage = null
                                },
                                label = { Text("${account.name} (${account.currency.code})") },
                                modifier = Modifier.testTag("repay_account_${account.name}"),
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = repaymentAmountText,
                onValueChange = {
                    repaymentAmountText = sanitizeDecimalText(it)
                    selectedMode = CreditCardRepaymentMode.CUSTOM
                    isPaidFromManuallyEdited = false
                    errorMessage = null
                },
                label = { Text("还入信用卡金额 (${summary.account.currency.code})") },
                prefix = { Text("${summary.account.currency.symbol} ") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("repay_amount_input"),
            )

            if (isCrossCurrency) {
                val paymentAcct = selectedPaymentAccount!!
                OutlinedTextField(
                    value = paidFromAmountText,
                    onValueChange = {
                        paidFromAmountText = sanitizeDecimalText(it)
                        isPaidFromManuallyEdited = true
                        errorMessage = null
                    },
                    label = { Text("付款账户实付金额 (${paymentAcct.currency.code}，自动跨币种对冲)") },
                    prefix = { Text("${paymentAcct.currency.symbol} ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("repay_paid_amount_input"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = feeText,
                    onValueChange = { feeText = sanitizeDecimalText(it) },
                    label = { Text("手续费（可选）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.weight(1.2f),
                )
            }

            if (previewRepayMoney != null && selectedPaymentAccount != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (isCrossCurrency) "复式跨币种还款预览（自动生成 Trading 对冲）" else "复式还款分录预览",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${selectedPaymentAccount.name} → ${summary.account.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            PrivacyAmountText(
                                money = previewRepayMoney,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                showPositiveSign = true,
                            )
                        }
                    }
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        onSubmit(
                            selectedAccountId,
                            repaymentAmountText,
                            paidFromAmountText,
                            feeText,
                            note,
                        ) { err ->
                            if (err == null) onDismiss() else errorMessage = err
                        }
                    },
                    enabled = !isSubmitting && paymentAccounts.isNotEmpty(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("repay_submit_button"),
                ) {
                    Text(if (isSubmitting) "入账中…" else "确认还款并入账")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreditCardParametersBottomSheet(
    summary: CreditCardStatementSummary,
    globalDefaultRemindDays: Int,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        limitText: String,
        billingDayText: String,
        dueDayText: String,
        remindDaysText: String,
        onComplete: (String?) -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val account = summary.account

    var limitText by remember(account) {
        mutableStateOf(
            account.creditLimit?.toBigDecimal()?.stripTrailingZeros()?.toPlainString().orEmpty(),
        )
    }
    var billingDayText by remember(account) { mutableStateOf(account.billingDay?.toString().orEmpty()) }
    var dueDayText by remember(account) { mutableStateOf(account.dueDay?.toString().orEmpty()) }
    var remindDaysText by remember(account) { mutableStateOf(account.remindDaysBefore?.toString().orEmpty()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("credit_card_params_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "信用参数设置 · ${account.name}",
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = limitText,
                onValueChange = {
                    limitText = sanitizeDecimalText(it)
                    errorMessage = null
                },
                label = { Text("授信额度 (${account.currency.code})") },
                placeholder = { Text("例如 50000") },
                prefix = { Text("${account.currency.symbol} ") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("credit_params_limit_input"),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = billingDayText,
                    onValueChange = {
                        billingDayText = it.filter(Char::isDigit).take(2)
                        errorMessage = null
                    },
                    label = { Text("账单日 (1–31)") },
                    placeholder = { Text("如 5") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("credit_params_billing_day_input"),
                )
                OutlinedTextField(
                    value = dueDayText,
                    onValueChange = {
                        dueDayText = it.filter(Char::isDigit).take(2)
                        errorMessage = null
                    },
                    label = { Text("还款日 (1–31)") },
                    placeholder = { Text("如 25") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("credit_params_due_day_input"),
                )
            }

            OutlinedTextField(
                value = remindDaysText,
                onValueChange = {
                    remindDaysText = it.filter(Char::isDigit).take(2)
                    errorMessage = null
                },
                label = { Text("提前提醒天数 (0–31，留空跟随全局默认 $globalDefaultRemindDays 天)") },
                placeholder = { Text("例如 3（0 表示仅还款日当天）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("credit_params_remind_days_input"),
            )

            Text(
                text = "• 支持 29–31 日月末自动夹紧（如平年 2 月自动按 28 日、闰年按 29 日）。\n" +
                    "• 还款日小于等于账单日时自动按次月还款日计算。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        onSubmit(
                            limitText,
                            billingDayText,
                            dueDayText,
                            remindDaysText,
                        ) { err ->
                            if (err == null) onDismiss() else errorMessage = err
                        }
                    },
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1.3f)
                        .testTag("credit_params_save_button"),
                ) {
                    Text(if (isSubmitting) "保存中…" else "保存信用参数")
                }
            }
        }
    }
}

private fun sanitizeDecimalText(raw: String, maxDecimals: Int = 4): String {
    val sb = StringBuilder()
    var seenDot = false
    var decimals = 0
    for (ch in raw) {
        when {
            ch.isDigit() -> {
                if (seenDot) {
                    if (decimals < maxDecimals) {
                        sb.append(ch)
                        decimals++
                    }
                } else {
                    sb.append(ch)
                }
            }
            ch == '.' && !seenDot -> {
                if (sb.isEmpty()) sb.append('0')
                sb.append('.')
                seenDot = true
            }
        }
    }
    return sb.toString()
}
