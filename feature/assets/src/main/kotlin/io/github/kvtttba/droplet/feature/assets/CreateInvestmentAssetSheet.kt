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
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DepositInterestMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateInvestmentAssetSheet(
    availableCashAccounts: List<Account>,
    availableHoldingAccounts: List<Account>,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (CreateInvestmentAssetFormInput, (String?) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = remember { LocalDate.now() }
    val isoFmt = remember { DateTimeFormatter.ISO_LOCAL_DATE }

    var assetClass by remember { mutableStateOf(AssetClass.ETF) }
    var currency by remember { mutableStateOf(AssetsFormatters.assetClassDefaultCurrency(AssetClass.ETF)) }
    var name by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var includeInNetWorth by remember { mutableStateOf(true) }
    var selectedHoldingAccountId by remember { mutableStateOf<String?>(null) }
    var quoteProviderHint by remember { mutableStateOf("") }

    // Fixed deposit specific fields
    var depositRatePercent by remember { mutableStateOf("2.45") }
    var depositInterestMode by remember { mutableStateOf(DepositInterestMode.SIMPLE_AT_MATURITY) }
    var depositStartDate by remember { mutableStateOf(today.format(isoFmt)) }
    var depositMaturityDate by remember { mutableStateOf(today.plusYears(1).format(isoFmt)) }
    var depositPrincipal by remember { mutableStateOf("") }
    var selectedCashAccountId by remember(currency, availableCashAccounts) {
        mutableStateOf(availableCashAccounts.firstOrNull { it.currency == currency }?.id)
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    val matchingHoldingAccounts = remember(currency, availableHoldingAccounts) {
        availableHoldingAccounts.filter { it.currency == currency }
    }
    val matchingCashAccounts = remember(currency, availableCashAccounts) {
        availableCashAccounts.filter { it.currency == currency }
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
                .testTag("create_asset_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "新建投资标的 / 定存 / 理财",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = "资产类别（支持全 9 类资产）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AssetClass.entries.forEach { cls ->
                    FilterChip(
                        selected = assetClass == cls,
                        onClick = {
                            assetClass = cls
                            val suggested = AssetsFormatters.assetClassDefaultCurrency(cls)
                            currency = suggested
                            selectedHoldingAccountId = null
                            errorMessage = null
                        },
                        label = { Text(AssetsFormatters.assetClassLabel(cls)) },
                        modifier = Modifier.testTag("create_asset_class_${cls.name}"),
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    errorMessage = null
                },
                label = { Text("标的名称") },
                placeholder = {
                    Text(
                        when (assetClass) {
                            AssetClass.FIXED_DEPOSIT -> "例如 招行一年定期"
                            AssetClass.BANK_WEALTH -> "例如 稳健日开理财"
                            AssetClass.CRYPTO -> "例如 Bitcoin"
                            else -> "例如 沪深300ETF"
                        },
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_asset_name_input"),
            )

            OutlinedTextField(
                value = symbol,
                onValueChange = {
                    symbol = it
                    errorMessage = null
                },
                label = {
                    Text(
                        if (assetClass == AssetClass.FIXED_DEPOSIT || assetClass == AssetClass.BANK_WEALTH) {
                            "代码 / 编号（可选）"
                        } else {
                            "行情代码"
                        },
                    )
                },
                placeholder = { Text(AssetsFormatters.assetClassSymbolHint(assetClass)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_asset_symbol_input"),
            )

            Text(
                text = "计价币种",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Currency.entries.forEach { ccy ->
                    FilterChip(
                        selected = currency == ccy,
                        onClick = {
                            currency = ccy
                            selectedHoldingAccountId = null
                            selectedCashAccountId = availableCashAccounts.firstOrNull { it.currency == ccy }?.id
                        },
                        label = { Text("${ccy.symbol} ${ccy.code}") },
                        modifier = Modifier.testTag("create_asset_currency_${ccy.code}"),
                    )
                }
            }

            if (assetClass == AssetClass.FIXED_DEPOSIT) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Text(
                    text = "定期存款计息参数（ACT/365 本地计息）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = depositRatePercent,
                    onValueChange = {
                        depositRatePercent = it
                        errorMessage = null
                    },
                    label = { Text("约定年利率 (%)") },
                    placeholder = { Text("例如 2.45") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_asset_deposit_rate_input"),
                )
                Text(
                    text = "计息方式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DepositInterestMode.entries.forEach { mode ->
                        FilterChip(
                            selected = depositInterestMode == mode,
                            onClick = { depositInterestMode = mode },
                            label = { Text(AssetsFormatters.depositInterestModeLabel(mode)) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = depositStartDate,
                        onValueChange = { depositStartDate = it },
                        label = { Text("起息日") },
                        placeholder = { Text("YYYY-MM-DD") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = depositMaturityDate,
                        onValueChange = { depositMaturityDate = it },
                        label = { Text("到期日") },
                        placeholder = { Text("YYYY-MM-DD") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        "3个月" to 3L,
                        "6个月" to 6L,
                        "1年期" to 12L,
                        "3年期" to 36L,
                    ).forEach { (label, months) ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                val parsedStart = runCatching {
                                    LocalDate.parse(depositStartDate.trim(), isoFmt)
                                }.getOrElse { today }
                                depositStartDate = parsedStart.format(isoFmt)
                                depositMaturityDate = parsedStart.plusMonths(months).format(isoFmt)
                            },
                            label = { Text(label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = depositPrincipal,
                    onValueChange = {
                        depositPrincipal = it
                        errorMessage = null
                    },
                    label = { Text("首笔存入本金（可选，填后自动记入开立流水）") },
                    placeholder = { Text("例如 50000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_asset_deposit_principal_input"),
                )
                if (depositPrincipal.isNotBlank() && matchingCashAccounts.isNotEmpty()) {
                    Text(
                        text = "扣款资金账户 (${currency.code})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        matchingCashAccounts.forEach { acct ->
                            FilterChip(
                                selected = selectedCashAccountId == acct.id,
                                onClick = { selectedCashAccountId = acct.id },
                                label = { Text(acct.name) },
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = quoteProviderHint,
                    onValueChange = { quoteProviderHint = it },
                    label = { Text("优先行情源提示（可选）") },
                    placeholder = { Text("留空自动按国内直连优先策略拉取") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (matchingHoldingAccounts.isNotEmpty()) {
                Text(
                    text = "关联持仓账户（默认自动创建独立持仓户）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = selectedHoldingAccountId == null,
                        onClick = { selectedHoldingAccountId = null },
                        label = { Text("自动新建持仓户") },
                    )
                    matchingHoldingAccounts.forEach { holding ->
                        FilterChip(
                            selected = selectedHoldingAccountId == holding.id,
                            onClick = { selectedHoldingAccountId = holding.id },
                            label = { Text(holding.name) },
                        )
                    }
                }
            }

            if (selectedHoldingAccountId == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "计入总净资产",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "关闭后将归入底部「不计入总资产」折叠分区",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = includeInNetWorth,
                        onCheckedChange = { includeInNetWorth = it },
                        modifier = Modifier.testTag("create_asset_include_net_worth"),
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("create_asset_error"),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = {
                    val form = CreateInvestmentAssetFormInput(
                        assetClass = assetClass,
                        name = name,
                        symbol = symbol,
                        currency = currency,
                        existingHoldingAccountId = selectedHoldingAccountId,
                        includeInNetWorth = includeInNetWorth,
                        quoteProviderHint = quoteProviderHint,
                        depositRatePercentText = depositRatePercent,
                        depositStartDateIso = depositStartDate,
                        depositMaturityDateIso = depositMaturityDate,
                        depositInterestMode = depositInterestMode,
                        depositPrincipalText = depositPrincipal,
                        depositCashAccountId = selectedCashAccountId,
                    )
                    onSubmit(form) { err ->
                        if (err != null) {
                            errorMessage = err
                        } else {
                            onDismiss()
                        }
                    }
                },
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_asset_submit_button"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(text = if (isSubmitting) "正在创建…" else "确认创建标的")
            }
        }
    }
}
