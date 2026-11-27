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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import io.github.kvtttba.droplet.core.domain.loan.LoanScheduleCalculator
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.theme.tabularNums
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateLoanPlanSheet(
    availablePaymentAccounts: List<Account>,
    availableExpenseCategories: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (CreateLoanPlanFormInput, (String?) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = remember { LocalDate.now(LoanScheduleCalculator.DEFAULT_ZONE_ID) }
    val defaultFirstDue = remember(today) { today.plusMonths(1) }
    val isoFmt = remember { DateTimeFormatter.ISO_LOCAL_DATE }

    var name by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(LoanMethod.EQUAL_INSTALLMENT) }
    var currency by remember { mutableStateOf(Currency.CNY) }
    var principalText by remember { mutableStateOf("") }
    var totalPeriodsText by remember { mutableStateOf("12") }
    var annualRatePercentText by remember { mutableStateOf("4.8") }
    var fixedFeeUseRate by remember { mutableStateOf(false) }
    var fixedFeePerPeriodText by remember { mutableStateOf("") }
    var fixedFeeRatePercentText by remember { mutableStateOf("0.6") }
    var firstDueDateIso by remember { mutableStateOf(defaultFirstDue.format(isoFmt)) }
    var monthlyRepayDayText by remember { mutableStateOf(defaultFirstDue.dayOfMonth.toString()) }
    var isRepayDayManuallyEdited by remember { mutableStateOf(false) }
    var autoPost by remember { mutableStateOf(false) }
    var remindDaysBeforeText by remember { mutableStateOf("3") }
    var includeInNetWorth by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val matchingPaymentAccounts = remember(currency, availablePaymentAccounts) {
        availablePaymentAccounts.filter { it.currency == currency }
    }
    var selectedPaymentAccountId by remember(currency, matchingPaymentAccounts) {
        mutableStateOf(
            matchingPaymentAccounts
                .firstOrNull { it.id == DefaultSeedCatalog.AccountIds.DEBIT_CARD }?.id
                ?: matchingPaymentAccounts.firstOrNull()?.id,
        )
    }

    val defaultInterestCategoryId = remember(availableExpenseCategories) {
        availableExpenseCategories
            .firstOrNull { it.id == DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE }?.id
            ?: availableExpenseCategories.firstOrNull()?.id
            ?: DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE
    }
    var selectedInterestCategoryId by remember(defaultInterestCategoryId) {
        mutableStateOf(defaultInterestCategoryId)
    }

    val schedulePreview = remember(
        method,
        currency,
        principalText,
        totalPeriodsText,
        annualRatePercentText,
        fixedFeeUseRate,
        fixedFeePerPeriodText,
        fixedFeeRatePercentText,
        firstDueDateIso,
        monthlyRepayDayText,
    ) {
        runCatching {
            val principal = Money.fromDecimalString(principalText.trim(), currency)
            if (!principal.isPositive) return@runCatching null
            val periods = totalPeriodsText.trim().toIntOrNull() ?: return@runCatching null
            if (periods !in 1..600) return@runCatching null
            val repayDay = monthlyRepayDayText.trim().toIntOrNull() ?: return@runCatching null
            if (repayDay !in 1..31) return@runCatching null
            val firstDueMs = AssetsFormatters.parseIsoDateToEpochMs(
                firstDueDateIso,
                zoneId = LoanScheduleCalculator.DEFAULT_ZONE_ID,
            )

            val annualRateDecimal = if (method == LoanMethod.FIXED_FEE) {
                null
            } else {
                AssetsFormatters.parseOptionalRatePercentToCanonicalDecimal(annualRatePercentText, "年利率")
            }
            val fixedFeeMinor = if (method == LoanMethod.FIXED_FEE && !fixedFeeUseRate) {
                val trimmed = fixedFeePerPeriodText.trim()
                if (trimmed.isEmpty()) 0L else Money.fromDecimalString(trimmed, currency).amountMinor
            } else {
                null
            }
            val fixedFeeRateDecimal = if (method == LoanMethod.FIXED_FEE && fixedFeeUseRate) {
                AssetsFormatters.parseOptionalRatePercentToCanonicalDecimal(fixedFeeRatePercentText, "每期费率")
            } else {
                null
            }

            LoanScheduleCalculator.previewSchedule(
                method = method,
                currency = currency,
                totalPrincipalMinor = principal.amountMinor,
                totalPeriods = periods,
                annualRateDecimal = annualRateDecimal,
                fixedFeePerPeriodMinor = fixedFeeMinor,
                fixedFeeRateDecimal = fixedFeeRateDecimal,
                firstDueAt = firstDueMs,
                monthlyRepayDay = repayDay,
            )
        }.getOrNull()
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
                .testTag("create_loan_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "新建分期贷款",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    errorMessage = null
                },
                label = { Text("贷款 / 分期名称") },
                placeholder = { Text("例如 招行消费分期 / 住房按揭贷款") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_loan_name_input"),
            )

            Text(
                text = "还款方式",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LoanMethod.entries.forEach { item ->
                    FilterChip(
                        selected = method == item,
                        onClick = {
                            method = item
                            errorMessage = null
                        },
                        label = { Text(AssetsFormatters.loanMethodLabel(item)) },
                        modifier = Modifier.testTag("create_loan_method_${item.name}"),
                    )
                }
            }
            Text(
                text = AssetsFormatters.loanMethodDescription(method),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "币种",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Currency.entries.forEach { ccy ->
                    FilterChip(
                        selected = currency == ccy,
                        onClick = {
                            currency = ccy
                            errorMessage = null
                        },
                        label = { Text("${ccy.symbol} ${ccy.code}") },
                        modifier = Modifier.testTag("create_loan_currency_${ccy.code}"),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = principalText,
                    onValueChange = {
                        principalText = sanitizeLoanDecimal(it)
                        errorMessage = null
                    },
                    label = { Text("贷款总本金 (${currency.code})") },
                    placeholder = { Text("例如 12000") },
                    prefix = { Text("${currency.symbol} ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("create_loan_principal_input"),
                )
                OutlinedTextField(
                    value = totalPeriodsText,
                    onValueChange = {
                        totalPeriodsText = it.filter(Char::isDigit).take(3)
                        errorMessage = null
                    },
                    label = { Text("总期数 (月)") },
                    placeholder = { Text("12") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(0.9f)
                        .testTag("create_loan_periods_input"),
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(3, 6, 12, 24, 36, 360).forEach { preset ->
                    FilterChip(
                        selected = totalPeriodsText == preset.toString(),
                        onClick = {
                            totalPeriodsText = preset.toString()
                            errorMessage = null
                        },
                        label = { Text("${preset}期") },
                    )
                }
            }

            if (method == LoanMethod.FIXED_FEE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !fixedFeeUseRate,
                        onClick = {
                            fixedFeeUseRate = false
                            errorMessage = null
                        },
                        label = { Text("按每期固定手续费") },
                        modifier = Modifier.testTag("create_loan_fixed_fee_mode_amount"),
                    )
                    FilterChip(
                        selected = fixedFeeUseRate,
                        onClick = {
                            fixedFeeUseRate = true
                            errorMessage = null
                        },
                        label = { Text("按每期费率 (%)") },
                        modifier = Modifier.testTag("create_loan_fixed_fee_mode_rate"),
                    )
                }
                if (!fixedFeeUseRate) {
                    OutlinedTextField(
                        value = fixedFeePerPeriodText,
                        onValueChange = {
                            fixedFeePerPeriodText = sanitizeLoanDecimal(it)
                            errorMessage = null
                        },
                        label = { Text("每期固定手续费 (${currency.code}，0 或留空为免息)") },
                        placeholder = { Text("例如 30.00") },
                        prefix = { Text("${currency.symbol} ") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create_loan_fixed_fee_input"),
                    )
                } else {
                    OutlinedTextField(
                        value = fixedFeeRatePercentText,
                        onValueChange = {
                            fixedFeeRatePercentText = sanitizeLoanDecimal(it)
                            errorMessage = null
                        },
                        label = { Text("每期手续费率 (% / 期，按总本金计)") },
                        placeholder = { Text("例如 0.6") },
                        suffix = { Text("%") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create_loan_fixed_fee_rate_input"),
                    )
                }
            } else {
                OutlinedTextField(
                    value = annualRatePercentText,
                    onValueChange = {
                        annualRatePercentText = sanitizeLoanDecimal(it)
                        errorMessage = null
                    },
                    label = { Text("年利率 (%，0 或留空为免息)") },
                    placeholder = { Text("例如 4.8") },
                    suffix = { Text("%") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_loan_annual_rate_input"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = firstDueDateIso,
                    onValueChange = { next ->
                        firstDueDateIso = next
                        if (!isRepayDayManuallyEdited) {
                            runCatching { LocalDate.parse(next.trim(), isoFmt) }.getOrNull()?.let { parsed ->
                                monthlyRepayDayText = parsed.dayOfMonth.toString()
                            }
                        }
                        errorMessage = null
                    },
                    label = { Text("首期还款日") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1.3f)
                        .testTag("create_loan_first_due_input"),
                )
                OutlinedTextField(
                    value = monthlyRepayDayText,
                    onValueChange = {
                        monthlyRepayDayText = it.filter(Char::isDigit).take(2)
                        isRepayDayManuallyEdited = true
                        errorMessage = null
                    },
                    label = { Text("每月扣款日 (1–31)") },
                    placeholder = { Text("15") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("create_loan_repay_day_input"),
                )
            }

            if (schedulePreview != null && schedulePreview.isNotEmpty()) {
                val first = schedulePreview.first()
                val totalInterestMinor = schedulePreview.fold(0L) { acc, row ->
                    Math.addExact(acc, row.interestOrFeeMinor)
                }
                val totalRepayMinor = schedulePreview.fold(0L) { acc, row ->
                    Math.addExact(acc, row.totalDueMinor)
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_loan_preview_card"),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "逐期计划实时预览（共 ${schedulePreview.size} 期 · 末期自动平齐尾差）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    text = "首期应还",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                                )
                                PrivacyAmountText(
                                    money = Money(first.totalDueMinor, currency),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "总利息/手续费",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                                )
                                PrivacyAmountText(
                                    money = Money(totalInterestMinor, currency),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "还款总额",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                                )
                                PrivacyAmountText(
                                    money = Money(totalRepayMinor, currency),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                        Text(
                            text = "第 1 期拆分：本金 ${Money(first.principalMinor, currency).toBigDecimal().stripTrailingZeros().toPlainString()} + " +
                                "息费 ${Money(first.interestOrFeeMinor, currency).toBigDecimal().stripTrailingZeros().toPlainString()} (${currency.code})",
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Text(
                text = "关联付款账户 (${currency.code})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (matchingPaymentAccounts.isEmpty()) {
                Text(
                    text = "暂无可用的 ${currency.code} 付款账户，请先在账户管理中创建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    matchingPaymentAccounts.forEach { account ->
                        FilterChip(
                            selected = selectedPaymentAccountId == account.id,
                            onClick = {
                                selectedPaymentAccountId = account.id
                                errorMessage = null
                            },
                            label = { Text(account.name) },
                            modifier = Modifier.testTag("create_loan_payment_${account.name}"),
                        )
                    }
                }
            }

            if (availableExpenseCategories.isNotEmpty()) {
                val quickInterestCategories = remember(availableExpenseCategories) {
                    val preferred = availableExpenseCategories.filter {
                        it.id in setOf(
                            DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
                            DefaultSeedCatalog.AccountIds.EXP_HOUSING_RENT_MORTGAGE,
                            DefaultSeedCatalog.AccountIds.EXP_OTHER_MISC,
                        )
                    }
                    if (preferred.isNotEmpty()) preferred else availableExpenseCategories.take(6)
                }
                Text(
                    text = "利息 / 手续费支出分类",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickInterestCategories.forEach { cat ->
                        FilterChip(
                            selected = selectedInterestCategoryId == cat.id,
                            onClick = { selectedInterestCategoryId = cat.id },
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
                        text = "到期日自动生成复式还款分录",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "开启后每日后台扫描到期期次并自动拆本息入账（幂等防重）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = autoPost,
                    onCheckedChange = { autoPost = it },
                    modifier = Modifier.testTag("create_loan_auto_post_switch"),
                )
            }

            OutlinedTextField(
                value = remindDaysBeforeText,
                onValueChange = {
                    remindDaysBeforeText = it.filter(Char::isDigit).take(2)
                    errorMessage = null
                },
                label = { Text("提前提醒天数 (0–31，留空则不推送本地提醒)") },
                placeholder = { Text("例如 3（0 表示到期日当天提醒）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_loan_remind_days_input"),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "计入总负债与净资产",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "自动创建对应分期贷款负债账户并同步期初待还本金",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = includeInNetWorth,
                    onCheckedChange = { includeInNetWorth = it },
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
                    modifier = Modifier.testTag("create_loan_error"),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = {
                    val form = CreateLoanPlanFormInput(
                        name = name,
                        method = method,
                        currency = currency,
                        principalText = principalText,
                        totalPeriodsText = totalPeriodsText,
                        annualRatePercentText = annualRatePercentText,
                        fixedFeeUseRate = fixedFeeUseRate,
                        fixedFeePerPeriodText = fixedFeePerPeriodText,
                        fixedFeeRatePercentText = fixedFeeRatePercentText,
                        firstDueDateIso = firstDueDateIso,
                        monthlyRepayDayText = monthlyRepayDayText,
                        paymentAccountId = selectedPaymentAccountId,
                        interestExpenseAccountId = selectedInterestCategoryId,
                        autoPost = autoPost,
                        remindDaysBeforeText = remindDaysBeforeText,
                        includeInNetWorth = includeInNetWorth,
                        note = note,
                    )
                    onSubmit(form) { err ->
                        if (err != null) {
                            errorMessage = err
                        } else {
                            onDismiss()
                        }
                    }
                },
                enabled = !isSubmitting && matchingPaymentAccounts.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_loan_submit_button"),
            ) {
                Text(if (isSubmitting) "正在生成还款计划…" else "确认创建分期贷款")
            }
        }
    }
}

private fun sanitizeLoanDecimal(raw: String, maxDecimals: Int = 4): String {
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
