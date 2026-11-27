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
import io.github.kvtttba.droplet.core.domain.investment.PortfolioPositionValuation
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.tabularNums
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BuyOrDcaBottomSheet(
    position: PortfolioPositionValuation,
    isDca: Boolean,
    cashAccounts: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        cashAccountId: String?,
        quantityText: String,
        unitPriceText: String,
        totalAmountText: String,
        feeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCashId by remember(cashAccounts) { mutableStateOf(cashAccounts.firstOrNull()?.id) }
    var quantityText by remember { mutableStateOf("") }
    var unitPriceText by remember {
        mutableStateOf(position.effectiveUnitPrice?.canonical ?: "")
    }
    var totalAmountText by remember { mutableStateOf("") }
    var feeText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val estimatedTotal = remember(quantityText, unitPriceText, position.asset.currency) {
        runCatching {
            val q = BigDecimal(quantityText.trim())
            val p = BigDecimal(unitPriceText.trim())
            if (q > BigDecimal.ZERO && p > BigDecimal.ZERO) {
                Money.fromBigDecimal(q.multiply(p), position.asset.currency)
            } else {
                null
            }
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
                .testTag(if (isDca) "dca_sheet" else "buy_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isDca) "定投 ${position.asset.name}" else "买入 ${position.asset.name}",
                style = MaterialTheme.typography.titleLarge,
            )

            CashAccountSelector(
                label = "付款资金账户 (${position.asset.currency.code})",
                accounts = cashAccounts,
                selectedId = selectedCashId,
                onSelect = { selectedCashId = it },
            )

            OutlinedTextField(
                value = quantityText,
                onValueChange = {
                    quantityText = it
                    errorMessage = null
                },
                label = { Text("买入份额（支持最高 8 位小数）") },
                placeholder = { Text("例如 100 或 0.01250000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("buy_quantity_input"),
            )

            OutlinedTextField(
                value = unitPriceText,
                onValueChange = {
                    unitPriceText = it
                    errorMessage = null
                },
                label = { Text("成交单价 (${position.asset.currency.code})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("buy_price_input"),
            )

            if (estimatedTotal != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "按份额 × 单价估算成交额",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrivacyAmountText(
                            money = estimatedTotal,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = totalAmountText,
                    onValueChange = { totalAmountText = it },
                    label = { Text("实付成交额（可选）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = feeText,
                    onValueChange = { feeText = it },
                    label = { Text("手续费（可选）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
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

            Button(
                onClick = {
                    onSubmit(
                        selectedCashId,
                        quantityText,
                        unitPriceText,
                        totalAmountText,
                        feeText,
                        note,
                    ) { err ->
                        if (err != null) errorMessage = err else onDismiss()
                    }
                },
                enabled = !isSubmitting && cashAccounts.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("buy_submit_button"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (isSubmitting) "正在入账…" else if (isDca) "确认定投入账" else "确认买入入账")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SellBottomSheet(
    position: PortfolioPositionValuation,
    cashAccounts: List<Account>,
    isSubmitting: Boolean,
    computePreview: (String, String, String) -> SellPreviewComputation,
    onDismiss: () -> Unit,
    onSubmit: (
        cashAccountId: String?,
        quantityText: String,
        unitPriceText: String,
        grossProceedsText: String,
        feeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val semantic = DropletTheme.semanticColors
    var selectedCashId by remember(cashAccounts) { mutableStateOf(cashAccounts.firstOrNull()?.id) }
    var quantityText by remember { mutableStateOf("") }
    var unitPriceText by remember {
        mutableStateOf(position.effectiveUnitPrice?.canonical ?: "")
    }
    var grossProceedsText by remember { mutableStateOf("") }
    var feeText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var submitError by remember { mutableStateOf<String?>(null) }

    val previewResult = remember(quantityText, unitPriceText, grossProceedsText, position) {
        computePreview(quantityText, unitPriceText, grossProceedsText)
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
                .testTag("sell_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "卖出 ${position.asset.name}",
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedButton(
                    onClick = {
                        quantityText = position.holdingQuantity.canonical
                        submitError = null
                    },
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.testTag("sell_all_button"),
                ) {
                    Text("全部卖出", style = MaterialTheme.typography.labelMedium)
                }
            }

            Text(
                text = "当前可卖份额：${position.holdingQuantity.canonical} · 加权成本价：${
                    AssetsFormatters.formatUnitPrice(position.averageCostPrice, position.asset.currency)
                }",
                style = MaterialTheme.typography.bodySmall.tabularNums(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CashAccountSelector(
                label = "回款资金账户 (${position.asset.currency.code})",
                accounts = cashAccounts,
                selectedId = selectedCashId,
                onSelect = { selectedCashId = it },
            )

            OutlinedTextField(
                value = quantityText,
                onValueChange = {
                    quantityText = it
                    submitError = null
                },
                label = { Text("卖出份额") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sell_quantity_input"),
            )

            OutlinedTextField(
                value = unitPriceText,
                onValueChange = {
                    unitPriceText = it
                    submitError = null
                },
                label = { Text("卖出单价 (${position.asset.currency.code})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sell_price_input"),
            )

            if (previewResult.oversellError != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sell_oversell_error"),
                ) {
                    Text(
                        text = previewResult.oversellError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else if (previewResult.preview != null) {
                val preview = previewResult.preview
                val pnlColor = if (preview.estimatedRealizedPnL.isNegative) semantic.expense else semantic.income
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sell_preview_card"),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (preview.isFullLiquidation) "加权成本结转预览（清仓）" else "加权成本结转预览",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        PreviewMetricRow("预计成交总额", preview.grossProceeds, MaterialTheme.colorScheme.onSurface)
                        PreviewMetricRow("结转持仓成本", preview.costBasis, MaterialTheme.colorScheme.onSurfaceVariant)
                        PreviewMetricRow(
                            label = "预估已实现盈亏",
                            money = preview.estimatedRealizedPnL,
                            color = pnlColor,
                            showPositiveSign = true,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "卖出后剩余份额",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = preview.remainingQuantityAfterSell.canonical,
                                style = MaterialTheme.typography.bodySmall.tabularNums(),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = grossProceedsText,
                    onValueChange = { grossProceedsText = it },
                    label = { Text("成交总额（可选）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = feeText,
                    onValueChange = { feeText = it },
                    label = { Text("手续费（可选）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val displayError = submitError ?: previewResult.inputError
            if (!displayError.isNullOrBlank()) {
                Text(
                    text = displayError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    onSubmit(
                        selectedCashId,
                        quantityText,
                        unitPriceText,
                        grossProceedsText,
                        feeText,
                        note,
                    ) { err ->
                        if (err != null) submitError = err else onDismiss()
                    }
                },
                enabled = !isSubmitting && previewResult.canSubmit && cashAccounts.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sell_submit_button"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (isSubmitting) "正在结转入账…" else "确认卖出并结转成本")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DividendBottomSheet(
    position: PortfolioPositionValuation,
    cashAccounts: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        cashAccountId: String?,
        amountText: String,
        taxOrFeeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCashId by remember(cashAccounts) { mutableStateOf(cashAccounts.firstOrNull()?.id) }
    var amountText by remember { mutableStateOf("") }
    var taxOrFeeText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
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
                .testTag("dividend_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "记录现金分红 · ${position.asset.name}",
                style = MaterialTheme.typography.titleLarge,
            )
            CashAccountSelector(
                label = "收款资金账户 (${position.asset.currency.code})",
                accounts = cashAccounts,
                selectedId = selectedCashId,
                onSelect = { selectedCashId = it },
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = {
                    amountText = it
                    errorMessage = null
                },
                label = { Text("分红金额 (${position.asset.currency.code})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dividend_amount_input"),
            )
            OutlinedTextField(
                value = taxOrFeeText,
                onValueChange = { taxOrFeeText = it },
                label = { Text("红利税 / 手续费（可选）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!errorMessage.isNullOrBlank()) {
                Text(errorMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    onSubmit(selectedCashId, amountText, taxOrFeeText, note) { err ->
                        if (err != null) errorMessage = err else onDismiss()
                    }
                },
                enabled = !isSubmitting && cashAccounts.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dividend_submit_button"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (isSubmitting) "正在入账…" else "确认分红入账")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FixedDepositSettlementBottomSheet(
    position: PortfolioPositionValuation,
    cashAccounts: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        cashAccountId: String?,
        isFullSettlement: Boolean,
        withdrawnPrincipalText: String,
        actualInterestText: String,
        penaltyFeeText: String,
        archiveOnFullSettlement: Boolean,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val depositVal = position.fixedDepositValuation
    var isFullSettlement by remember { mutableStateOf(true) }
    var selectedCashId by remember(cashAccounts) { mutableStateOf(cashAccounts.firstOrNull()?.id) }
    var principalText by remember {
        mutableStateOf(position.remainingCostBasis.toBigDecimal().stripTrailingZeros().toPlainString())
    }
    var actualInterestText by remember {
        mutableStateOf(
            depositVal?.unpaidAccruedInterest?.toBigDecimal()?.stripTrailingZeros()?.toPlainString() ?: "0",
        )
    }
    var penaltyFeeText by remember { mutableStateOf("") }
    var archiveOnFull by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
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
                .testTag("deposit_settlement_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "定存结息 / 提前支取 · ${position.asset.name}",
                style = MaterialTheme.typography.titleLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = isFullSettlement,
                    onClick = { isFullSettlement = true },
                    label = { Text("到期全额结息") },
                )
                FilterChip(
                    selected = !isFullSettlement,
                    onClick = { isFullSettlement = false },
                    label = { Text("提前支取") },
                )
            }
            CashAccountSelector(
                label = "回款资金账户 (${position.asset.currency.code})",
                accounts = cashAccounts,
                selectedId = selectedCashId,
                onSelect = { selectedCashId = it },
            )
            if (!isFullSettlement) {
                OutlinedTextField(
                    value = principalText,
                    onValueChange = {
                        principalText = it
                        errorMessage = null
                    },
                    label = { Text("支取本金（剩余 ${position.remainingCostBasis.toBigDecimal().stripTrailingZeros().toPlainString()}）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = actualInterestText,
                onValueChange = {
                    actualInterestText = it
                    errorMessage = null
                },
                label = { Text("实结利息（默认按本地应计利息预填）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = penaltyFeeText,
                onValueChange = { penaltyFeeText = it },
                label = { Text("提前支取扣费 / 税费（可选）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            if (isFullSettlement) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "全额结息后自动归档该定存账户",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = archiveOnFull, onCheckedChange = { archiveOnFull = it })
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!errorMessage.isNullOrBlank()) {
                Text(errorMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    onSubmit(
                        selectedCashId,
                        isFullSettlement,
                        principalText,
                        actualInterestText,
                        penaltyFeeText,
                        archiveOnFull,
                        note,
                    ) { err ->
                        if (err != null) errorMessage = err else onDismiss()
                    }
                },
                enabled = !isSubmitting && cashAccounts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (isSubmitting) "正在入账…" else if (isFullSettlement) "确认全额结息" else "确认提前支取")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FixedDepositPayoutBottomSheet(
    position: PortfolioPositionValuation,
    cashAccounts: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        cashAccountId: String?,
        interestAmountText: String,
        taxOrFeeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCashId by remember(cashAccounts) { mutableStateOf(cashAccounts.firstOrNull()?.id) }
    var interestText by remember {
        mutableStateOf(
            position.fixedDepositValuation?.estimatedMonthlyPayout
                ?.toBigDecimal()?.stripTrailingZeros()?.toPlainString() ?: "",
        )
    }
    var taxOrFeeText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
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
                .testTag("deposit_payout_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "定存按期付息 · ${position.asset.name}",
                style = MaterialTheme.typography.titleLarge,
            )
            CashAccountSelector(
                label = "收息资金账户 (${position.asset.currency.code})",
                accounts = cashAccounts,
                selectedId = selectedCashId,
                onSelect = { selectedCashId = it },
            )
            OutlinedTextField(
                value = interestText,
                onValueChange = {
                    interestText = it
                    errorMessage = null
                },
                label = { Text("本期实收利息 (${position.asset.currency.code})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = taxOrFeeText,
                onValueChange = { taxOrFeeText = it },
                label = { Text("税费（可选）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!errorMessage.isNullOrBlank()) {
                Text(errorMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    onSubmit(selectedCashId, interestText, taxOrFeeText, note) { err ->
                        if (err != null) errorMessage = err else onDismiss()
                    }
                },
                enabled = !isSubmitting && cashAccounts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (isSubmitting) "正在入账…" else "确认付息入账")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualQuoteOverrideBottomSheet(
    position: PortfolioPositionValuation,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        priceText: String,
        prevCloseText: String,
        onComplete: (String?) -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var priceText by remember { mutableStateOf(position.effectiveUnitPrice?.canonical ?: "") }
    var prevCloseText by remember { mutableStateOf(position.effectivePrevClose?.canonical ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("manual_override_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "手动覆写单价 / 净值",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "覆写后自动行情刷新不会覆盖该单价，可随时一键清除覆写恢复自动拉取。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = priceText,
                onValueChange = {
                    priceText = it
                    errorMessage = null
                },
                label = { Text("最新单价 / 净值 (${position.asset.currency.code})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("override_price_input"),
            )
            OutlinedTextField(
                value = prevCloseText,
                onValueChange = { prevCloseText = it },
                label = { Text("昨收参考价（可选，用于计算当日涨跌）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!errorMessage.isNullOrBlank()) {
                Text(errorMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    onSubmit(priceText, prevCloseText) { err ->
                        if (err != null) errorMessage = err else onDismiss()
                    }
                },
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("override_submit_button"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (isSubmitting) "正在保存…" else "保存手动覆写价")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BankWealthValuationBottomSheet(
    position: PortfolioPositionValuation,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        mode: BankWealthManualInputMode,
        valueText: String,
        onComplete: (String?) -> Unit,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mode by remember { mutableStateOf(BankWealthManualInputMode.PROFIT_AMOUNT) }
    var valueText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("bank_wealth_valuation_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "银行理财手动估值更新",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "当前持仓成本：${position.remainingCostBasis.toBigDecimal().stripTrailingZeros().toPlainString()} ${position.asset.currency.code} · 份额：${position.holdingQuantity.canonical}",
                style = MaterialTheme.typography.bodySmall.tabularNums(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BankWealthManualInputMode.entries.forEach { item ->
                    FilterChip(
                        selected = mode == item,
                        onClick = {
                            mode = item
                            valueText = ""
                            errorMessage = null
                        },
                        label = { Text(item.label) },
                        modifier = Modifier.testTag("bank_wealth_mode_${item.name}"),
                    )
                }
            }
            OutlinedTextField(
                value = valueText,
                onValueChange = {
                    valueText = it
                    errorMessage = null
                },
                label = {
                    Text(
                        when (mode) {
                            BankWealthManualInputMode.UNIT_PRICE -> "输入最新单位净值（如 1.0352）"
                            BankWealthManualInputMode.PROFIT_AMOUNT -> "输入累计收益金额（破净可填负数如 -120.50）"
                            BankWealthManualInputMode.TOTAL_VALUE -> "输入当前持仓总市值"
                        },
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bank_wealth_value_input"),
            )
            if (!errorMessage.isNullOrBlank()) {
                Text(errorMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    onSubmit(mode, valueText) { err ->
                        if (err != null) errorMessage = err else onDismiss()
                    }
                },
                enabled = !isSubmitting && position.hasHolding,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bank_wealth_submit_button"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (isSubmitting) "正在更新…" else "保存并反推净值")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CashAccountSelector(
    label: String,
    accounts: List<Account>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (accounts.isEmpty()) {
            Text(
                text = "暂无匹配币种的资金账户，请先在「我的 → 账户管理」中新建。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                accounts.forEach { account ->
                    FilterChip(
                        selected = selectedId == account.id,
                        onClick = { onSelect(account.id) },
                        label = { Text(account.name) },
                        modifier = Modifier.testTag("cash_account_${account.name}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewMetricRow(
    label: String,
    money: Money,
    color: androidx.compose.ui.graphics.Color,
    showPositiveSign: Boolean = false,
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
            color = color,
            showPositiveSign = showPositiveSign,
        )
    }
}
