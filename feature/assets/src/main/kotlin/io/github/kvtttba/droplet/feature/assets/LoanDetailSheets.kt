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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import io.github.kvtttba.droplet.core.domain.loan.LoanPlanSummary
import io.github.kvtttba.droplet.core.domain.loan.LoanScheduleCalculator
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.LoanInstallment
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LoanInstallmentRepaymentSheet(
    summary: LoanPlanSummary,
    installment: LoanInstallment,
    paymentAccounts: List<Account>,
    interestExpenseAccountName: String,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        installmentId: String,
        paymentAccountIdOverride: String?,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val plan = summary.plan
    val currency = plan.currency
    var selectedPaymentId by remember(paymentAccounts, plan.paymentAccountId) {
        mutableStateOf(
            paymentAccounts.firstOrNull { it.id == plan.paymentAccountId }?.id
                ?: paymentAccounts.firstOrNull()?.id,
        )
    }
    var note by remember(installment.id) { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val selectedAccount = paymentAccounts.firstOrNull { it.id == selectedPaymentId }
    val principalMoney = Money(installment.principalMinor, currency)
    val interestMoney = Money(installment.interestOrFeeMinor, currency)
    val totalDueMoney = Money(installment.totalDueMinor, currency)

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
                .testTag("loan_repay_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "确认第 ${installment.periodNumber}/${summary.totalPeriods} 期还款",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "${plan.name} · 计划到期日 ${AssetsFormatters.formatIsoDate(installment.dueAt, summary.zoneId)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "当期本金（冲减贷款负债）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrivacyAmountText(
                            money = principalMoney,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "当期利息/手续费（计入 $interestExpenseAccountName）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrivacyAmountText(
                            money = interestMoney,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "当期应还总额",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        PrivacyAmountText(
                            money = totalDueMoney,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Text(
                text = "扣款付款账户 (${currency.code})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (paymentAccounts.isEmpty()) {
                Text(
                    text = "暂无可用的 ${currency.code} 付款账户。",
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
                            selected = selectedPaymentId == account.id,
                            onClick = {
                                selectedPaymentId = account.id
                                errorMessage = null
                            },
                            label = { Text(account.name) },
                            modifier = Modifier.testTag("loan_repay_account_${account.name}"),
                        )
                    }
                }
            }

            if (selectedAccount != null) {
                Text(
                    text = "复式分录：${plan.name}(负债) +本金 · $interestExpenseAccountName +息费 · ${selectedAccount.name} -总额",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                placeholder = { Text("默认记录第 ${installment.periodNumber} 期还款") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

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
                        onConfirm(
                            installment.id,
                            selectedPaymentId,
                            note,
                        ) { err ->
                            if (err == null) onDismiss() else errorMessage = err
                        }
                    },
                    enabled = !isSubmitting && selectedPaymentId != null,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("loan_repay_confirm_button"),
                ) {
                    Text(if (isSubmitting) "入账中…" else "确认还款并入账")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LoanEarlyPayoffSheet(
    summary: LoanPlanSummary,
    paymentAccounts: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        paymentAccountIdOverride: String?,
        extraFeeText: String,
        note: String,
        archiveAfterPayoff: Boolean,
        onComplete: (String?) -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val semantic = DropletTheme.semanticColors
    val plan = summary.plan
    val currency = plan.currency

    var selectedPaymentId by remember(paymentAccounts, plan.paymentAccountId) {
        mutableStateOf(
            paymentAccounts.firstOrNull { it.id == plan.paymentAccountId }?.id
                ?: paymentAccounts.firstOrNull()?.id,
        )
    }
    var extraFeeText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var archiveAfterPayoff by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val liveQuote = remember(summary, extraFeeText) {
        val extraMoney = runCatching {
            val trimmed = extraFeeText.trim()
            if (trimmed.isEmpty()) Money.zero(currency) else Money.fromDecimalString(trimmed, currency)
        }.getOrNull()?.takeIf { !it.isNegative } ?: Money.zero(currency)
        LoanScheduleCalculator.calculateEarlyPayoff(
            plan = plan,
            installments = summary.installments,
            extraFeeOrInterest = extraMoney,
        )
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
                .testTag("loan_early_payoff_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "提前结清 · ${plan.name}",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "将一次性偿还全部剩余 ${liveQuote.remainingPendingPeriods} 期本金，清零贷款负债并免除后续计划利息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "待结清剩余本金",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrivacyAmountText(
                            money = liveQuote.remainingPrincipal,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "减免未来计划息费（净节省）",
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.income,
                        )
                        PrivacyAmountText(
                            money = liveQuote.netInterestSaved,
                            style = MaterialTheme.typography.titleSmall,
                            color = semantic.income,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "本次结清实付总额",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        PrivacyAmountText(
                            money = liveQuote.totalPayoffOutflow,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("loan_early_payoff_total"),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = extraFeeText,
                onValueChange = {
                    extraFeeText = sanitizeSheetDecimal(it)
                    errorMessage = null
                },
                label = { Text("当期应计利息 / 提前结清违约金（可选）") },
                placeholder = { Text("无则留空") },
                prefix = { Text("${currency.symbol} ") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("loan_early_payoff_fee_input"),
            )

            Text(
                text = "结清付款账户 (${currency.code})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                paymentAccounts.forEach { account ->
                    FilterChip(
                        selected = selectedPaymentId == account.id,
                        onClick = { selectedPaymentId = account.id },
                        label = { Text(account.name) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "结清后自动归档该贷款",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "隐藏资产页卡片但完整保留历史账本流水",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = archiveAfterPayoff,
                    onCheckedChange = { archiveAfterPayoff = it },
                )
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

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
                        onConfirm(
                            selectedPaymentId,
                            extraFeeText,
                            note,
                            archiveAfterPayoff,
                        ) { err ->
                            if (err == null) onDismiss() else errorMessage = err
                        }
                    },
                    enabled = !isSubmitting && selectedPaymentId != null && liveQuote.remainingPendingPeriods > 0,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("loan_early_payoff_confirm_button"),
                ) {
                    Text(if (isSubmitting) "结清入账中…" else "确认提前结清")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LoanParametersSheet(
    summary: LoanPlanSummary,
    paymentAccounts: List<Account>,
    expenseCategories: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        paymentAccountId: String?,
        interestExpenseAccountId: String?,
        autoPost: Boolean,
        remindDaysBeforeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit,
    onToggleArchive: (Boolean, (String?) -> Unit) -> Unit,
    onDelete: ((String?) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val plan = summary.plan

    var name by remember(plan) { mutableStateOf(plan.name) }
    var selectedPaymentId by remember(plan) { mutableStateOf<String?>(plan.paymentAccountId) }
    var selectedInterestId by remember(plan) { mutableStateOf<String?>(plan.interestExpenseAccountId) }
    var autoPost by remember(plan) { mutableStateOf(plan.autoPost) }
    var remindDaysText by remember(plan) { mutableStateOf(plan.remindDaysBefore?.toString().orEmpty()) }
    var note by remember(plan) { mutableStateOf(plan.note.orEmpty()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val quickExpenseCategories = remember(expenseCategories, plan.interestExpenseAccountId) {
        val preferredIds = setOf(
            plan.interestExpenseAccountId,
            DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
            DefaultSeedCatalog.AccountIds.EXP_HOUSING_RENT_MORTGAGE,
            DefaultSeedCatalog.AccountIds.EXP_OTHER_MISC,
        )
        val filtered = expenseCategories.filter { it.id in preferredIds }
        if (filtered.isNotEmpty()) filtered else expenseCategories.take(6)
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
                .testTag("loan_params_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "贷款参数维护",
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    errorMessage = null
                },
                label = { Text("贷款名称") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("loan_params_name_input"),
            )

            if (paymentAccounts.isNotEmpty()) {
                Text(
                    text = "默认扣款付款账户 (${plan.currency.code})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    paymentAccounts.forEach { acct ->
                        FilterChip(
                            selected = selectedPaymentId == acct.id,
                            onClick = { selectedPaymentId = acct.id },
                            label = { Text(acct.name) },
                        )
                    }
                }
            }

            if (quickExpenseCategories.isNotEmpty()) {
                Text(
                    text = "利息 / 手续费支出分类",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickExpenseCategories.forEach { cat ->
                        FilterChip(
                            selected = selectedInterestId == cat.id,
                            onClick = { selectedInterestId = cat.id },
                            label = { Text(cat.name) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "到期日自动入账",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "后台每日扫描到期期次并自动生成拆本息还款分录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = autoPost,
                    onCheckedChange = { autoPost = it },
                )
            }

            OutlinedTextField(
                value = remindDaysText,
                onValueChange = {
                    remindDaysText = it.filter(Char::isDigit).take(2)
                    errorMessage = null
                },
                label = { Text("提前提醒天数 (0–31，留空则关闭本地提醒)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("loan_params_remind_days_input"),
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

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
                        onSave(
                            name,
                            selectedPaymentId,
                            selectedInterestId,
                            autoPost,
                            remindDaysText,
                            note,
                        ) { err ->
                            if (err == null) onDismiss() else errorMessage = err
                        }
                    },
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1.3f)
                        .testTag("loan_params_save_button"),
                ) {
                    Text(if (isSubmitting) "保存中…" else "保存参数")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onToggleArchive(!plan.isArchived) { err ->
                            if (err == null) onDismiss() else errorMessage = err
                        }
                    },
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("loan_params_archive_button"),
                ) {
                    Text(if (plan.isArchived) "取消归档" else "归档贷款")
                }
                if (summary.paidPeriods == 0) {
                    OutlinedButton(
                        onClick = {
                            onDelete { err ->
                                if (err == null) onDismiss() else errorMessage = err
                            }
                        },
                        enabled = !isSubmitting,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("loan_params_delete_button"),
                    ) {
                        Text("删除未入账贷款")
                    }
                }
            }
        }
    }
}

private fun sanitizeSheetDecimal(raw: String, maxDecimals: Int = 4): String {
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
