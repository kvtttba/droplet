package io.github.kvtttba.droplet.feature.assets

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.domain.investment.PortfolioPositionValuation
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.component.PrivacyToggleIconButton
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.tabularNums
import java.math.BigDecimal
import java.math.RoundingMode

private enum class DetailActiveSheet {
    BUY,
    DCA,
    SELL,
    DIVIDEND,
    DEPOSIT_SETTLE,
    DEPOSIT_PAYOUT,
    MANUAL_OVERRIDE,
    BANK_WEALTH_VALUATION,
}

@Composable
fun InvestmentDetailRoute(
    onNavigateBack: () -> Unit,
    onToggleAmountsHidden: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: InvestmentDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    InvestmentDetailScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onToggleAmountsHidden = onToggleAmountsHidden,
        onRefreshQuote = viewModel::refreshQuote,
        computeSellPreview = viewModel::computeSellPreview,
        onRecordBuy = viewModel::recordBuy,
        onRecordSell = viewModel::recordSell,
        onRecordDividend = viewModel::recordDividend,
        onRecordFixedDepositSettlement = viewModel::recordFixedDepositSettlement,
        onRecordFixedDepositPayout = viewModel::recordFixedDepositInterestPayout,
        onManualOverrideQuote = viewModel::manualOverrideQuote,
        onClearManualOverride = viewModel::clearManualOverride,
        onUpdateBankWealthValuation = viewModel::updateBankWealthValuation,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InvestmentDetailScreen(
    uiState: InvestmentDetailUiState,
    onNavigateBack: () -> Unit = {},
    onToggleAmountsHidden: () -> Unit = {},
    onRefreshQuote: () -> Unit = {},
    computeSellPreview: (String, String, String) -> SellPreviewComputation = { _, _, _ -> SellPreviewComputation() },
    onRecordBuy: (
        isDca: Boolean,
        cashAccountId: String?,
        quantityText: String,
        unitPriceText: String,
        totalAmountText: String,
        feeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, _, _, _, _, _, cb -> cb(null) },
    onRecordSell: (
        cashAccountId: String?,
        quantityText: String,
        unitPriceText: String,
        grossProceedsText: String,
        feeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, _, _, _, _, cb -> cb(null) },
    onRecordDividend: (
        cashAccountId: String?,
        amountText: String,
        taxOrFeeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, _, _, cb -> cb(null) },
    onRecordFixedDepositSettlement: (
        cashAccountId: String?,
        isFullSettlement: Boolean,
        withdrawnPrincipalText: String,
        actualInterestText: String,
        penaltyFeeText: String,
        archiveOnFullSettlement: Boolean,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, _, _, _, _, _, cb -> cb(null) },
    onRecordFixedDepositPayout: (
        cashAccountId: String?,
        interestAmountText: String,
        taxOrFeeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, _, _, cb -> cb(null) },
    onManualOverrideQuote: (
        priceText: String,
        prevCloseText: String,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, cb -> cb(null) },
    onClearManualOverride: () -> Unit = {},
    onUpdateBankWealthValuation: (
        mode: BankWealthManualInputMode,
        valueText: String,
        onComplete: (String?) -> Unit,
    ) -> Unit = { _, _, cb -> cb(null) },
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors
    var activeSheet by remember { mutableStateOf<DetailActiveSheet?>(null) }

    val position = uiState.position

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_investment_detail"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("investment_detail_back"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = position?.asset?.name ?: "持仓详情",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (position != null) {
                            Text(
                                text = "${position.asset.symbol} · ${AssetsFormatters.assetClassLabel(position.asset.assetClass)} · ${position.asset.currency.code}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (position != null && position.asset.assetClass != AssetClass.FIXED_DEPOSIT) {
                        IconButton(
                            onClick = onRefreshQuote,
                            enabled = !uiState.isRefreshingQuote,
                            modifier = Modifier.testTag("detail_refresh_quote"),
                        ) {
                            if (uiState.isRefreshingQuote) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "刷新行情",
                                )
                            }
                        }
                    }
                    PrivacyToggleIconButton(
                        amountsHidden = amountsHidden,
                        onToggle = onToggleAmountsHidden,
                    )
                },
            )
        },
    ) { innerPadding ->
        if (position == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (uiState.isLoading) "正在加载持仓…" else "未找到该投资标的",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        val asset = position.asset
        val isFixedDeposit = asset.assetClass == AssetClass.FIXED_DEPOSIT
        val isBankWealth = asset.assetClass == AssetClass.BANK_WEALTH
        val isManualOverride = position.marketQuote?.isManualOverride == true

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("investment_detail_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "detail_hero_card") {
                PositionHeroCard(
                    position = position,
                    amountsHidden = amountsHidden,
                )
            }

            item(key = "detail_actions_card") {
                DropletCard {
                    Text(
                        text = if (isFixedDeposit) "定期存款操作" else "持仓交易与估值操作",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isFixedDeposit) {
                            FilledTonalButton(
                                onClick = { activeSheet = DetailActiveSheet.DEPOSIT_SETTLE },
                                enabled = position.hasHolding,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("detail_action_deposit_settle"),
                            ) {
                                Text("到期结息 / 提前支取")
                            }
                            FilledTonalButton(
                                onClick = { activeSheet = DetailActiveSheet.DEPOSIT_PAYOUT },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("detail_action_deposit_payout"),
                            ) {
                                Text("按期付息")
                            }
                            OutlinedButton(
                                onClick = { activeSheet = DetailActiveSheet.BUY },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("detail_action_buy"),
                            ) {
                                Text("追加存入")
                            }
                        } else {
                            FilledTonalButton(
                                onClick = { activeSheet = DetailActiveSheet.BUY },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("detail_action_buy"),
                            ) {
                                Text("买入")
                            }
                            FilledTonalButton(
                                onClick = { activeSheet = DetailActiveSheet.DCA },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("detail_action_dca"),
                            ) {
                                Text("定投")
                            }
                            FilledTonalButton(
                                onClick = { activeSheet = DetailActiveSheet.SELL },
                                enabled = position.hasHolding,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("detail_action_sell"),
                            ) {
                                Text("卖出")
                            }
                            OutlinedButton(
                                onClick = { activeSheet = DetailActiveSheet.DIVIDEND },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("detail_action_dividend"),
                            ) {
                                Text("分红")
                            }
                            if (isBankWealth) {
                                FilledTonalButton(
                                    onClick = { activeSheet = DetailActiveSheet.BANK_WEALTH_VALUATION },
                                    enabled = position.hasHolding,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("detail_action_bank_wealth_valuation"),
                                ) {
                                    Text("更新净值 / 收益")
                                }
                            }
                            OutlinedButton(
                                onClick = { activeSheet = DetailActiveSheet.MANUAL_OVERRIDE },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("detail_action_manual_override"),
                            ) {
                                Text("手动覆写价")
                            }
                            if (isManualOverride) {
                                OutlinedButton(
                                    onClick = onClearManualOverride,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("detail_action_clear_override"),
                                ) {
                                    Text("清除覆写")
                                }
                            }
                        }
                    }
                }
            }

            if (isFixedDeposit && position.fixedDepositValuation != null) {
                item(key = "detail_fixed_deposit_card") {
                    val fd = position.fixedDepositValuation!!
                    val progress = if (fd.totalTermDays > 0L) {
                        BigDecimal.valueOf(fd.elapsedDays)
                            .divide(BigDecimal.valueOf(fd.totalTermDays), 4, RoundingMode.HALF_UP)
                            .toFloat()
                            .coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    DropletCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "定期计息进度 (${fd.dayCountBasis})",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            DropletPillBadge(
                                text = AssetsFormatters.fixedDepositStatusLabel(fd.status),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            strokeCap = StrokeCap.Round,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailInfoLine(
                            "约定年化 / 方式",
                            "${AssetsFormatters.formatAnnualRatePercent(fd.annualRateDecimal)} · ${
                                AssetsFormatters.depositInterestModeLabel(fd.interestMode)
                            }",
                        )
                        DetailInfoLine("起息日 → 到期日", "${fd.startDate} → ${fd.maturityDate}")
                        DetailInfoLine("已计息 / 剩余天数", "${fd.elapsedDays} 天 / 剩余 ${fd.remainingDays} 天")
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                        DetailMoneyLine("当前应计利息", fd.accruedInterest, semantic.income, showPositiveSign = true)
                        DetailMoneyLine("未付应计利息", fd.unpaidAccruedInterest, semantic.income, showPositiveSign = true)
                        DetailMoneyLine("预计到期本息", fd.expectedMaturityTotalValue, MaterialTheme.colorScheme.onSurface)
                        DetailMoneyLine("预计日均计息", fd.dailyAccrual, semantic.income, showPositiveSign = true)
                    }
                }
            }

            item(key = "detail_return_breakdown") {
                DropletCard {
                    Text(
                        text = "收益与成本构成 (${asset.currency.code})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    DetailMoneyLine(
                        label = "剩余持仓成本",
                        money = position.remainingCostBasis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    DetailMoneyLine(
                        label = "累计浮动盈亏 (${AssetsFormatters.formatPercentSigned(position.unrealizedReturnPctDecimal)})",
                        money = position.unrealizedPnLOriginal,
                        color = pnlColor(position.unrealizedPnLOriginal, semantic),
                        showPositiveSign = true,
                        valueTestTag = "detail_unrealized_pnl",
                    )
                    DetailMoneyLine(
                        label = "累计已实现盈亏",
                        money = position.realizedPnLOriginal,
                        color = pnlColor(position.realizedPnLOriginal, semantic),
                        showPositiveSign = true,
                    )
                    DetailMoneyLine(
                        label = if (isFixedDeposit) "累计已结利息" else "累计现金分红",
                        money = position.totalDividendsOriginal,
                        color = semantic.income,
                        showPositiveSign = true,
                    )
                    DetailMoneyLine(
                        label = "累计交易与税费",
                        money = position.totalFeesOriginal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                    DetailMoneyLine(
                        label = "累计净总回报（扣费）",
                        money = position.totalReturnOriginal,
                        color = pnlColor(position.totalReturnOriginal, semantic),
                        showPositiveSign = true,
                        valueTestTag = "detail_total_return",
                    )
                }
            }

            item(key = "detail_history_header") {
                DropletSectionHeader(
                    title = "标的历史流水",
                    trailing = "${uiState.transactions.size} 笔",
                )
            }

            item(key = "detail_history_card") {
                DropletCard {
                    if (uiState.transactions.isEmpty()) {
                        Text(
                            text = "暂无该标的的交易流水，可点击上方按钮记录买入、定投或分红。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        uiState.transactions.forEachIndexed { index, tx ->
                            InvestmentHistoryRow(
                                item = tx,
                                assetClass = asset.assetClass,
                                symbol = asset.symbol,
                                amountsHidden = amountsHidden,
                            )
                            if (index != uiState.transactions.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (position != null) {
        when (activeSheet) {
            DetailActiveSheet.BUY -> BuyOrDcaBottomSheet(
                position = position,
                isDca = false,
                cashAccounts = uiState.matchingCashAccounts,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = { cashId, qty, price, total, fee, note, cb ->
                    onRecordBuy(false, cashId, qty, price, total, fee, note, cb)
                },
            )
            DetailActiveSheet.DCA -> BuyOrDcaBottomSheet(
                position = position,
                isDca = true,
                cashAccounts = uiState.matchingCashAccounts,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = { cashId, qty, price, total, fee, note, cb ->
                    onRecordBuy(true, cashId, qty, price, total, fee, note, cb)
                },
            )
            DetailActiveSheet.SELL -> SellBottomSheet(
                position = position,
                cashAccounts = uiState.matchingCashAccounts,
                isSubmitting = uiState.isSubmitting,
                computePreview = computeSellPreview,
                onDismiss = { activeSheet = null },
                onSubmit = onRecordSell,
            )
            DetailActiveSheet.DIVIDEND -> DividendBottomSheet(
                position = position,
                cashAccounts = uiState.matchingCashAccounts,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = onRecordDividend,
            )
            DetailActiveSheet.DEPOSIT_SETTLE -> FixedDepositSettlementBottomSheet(
                position = position,
                cashAccounts = uiState.matchingCashAccounts,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = onRecordFixedDepositSettlement,
            )
            DetailActiveSheet.DEPOSIT_PAYOUT -> FixedDepositPayoutBottomSheet(
                position = position,
                cashAccounts = uiState.matchingCashAccounts,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = onRecordFixedDepositPayout,
            )
            DetailActiveSheet.MANUAL_OVERRIDE -> ManualQuoteOverrideBottomSheet(
                position = position,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = onManualOverrideQuote,
            )
            DetailActiveSheet.BANK_WEALTH_VALUATION -> BankWealthValuationBottomSheet(
                position = position,
                isSubmitting = uiState.isSubmitting,
                onDismiss = { activeSheet = null },
                onSubmit = onUpdateBankWealthValuation,
            )
            null -> Unit
        }
    }
}

@Composable
private fun PositionHeroCard(
    position: PortfolioPositionValuation,
    amountsHidden: Boolean,
) {
    val semantic = DropletTheme.semanticColors
    val asset = position.asset
    val badgeText = AssetsFormatters.valuationSourceBadge(
        source = position.valuationSource,
        bankWealthMode = position.bankWealthValuationMode,
    )
    val quoteTimeText = position.marketQuote?.let { AssetsFormatters.formatShortTimestamp(it.fetchedAt) }

    DropletHeroCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "当前持仓市值 (${asset.currency.code})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            DropletPillBadge(
                text = if (quoteTimeText != null) "$badgeText · $quoteTimeText" else badgeText,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        PrivacyAmountText(
            money = position.marketValueOriginal,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.testTag("detail_market_value"),
        )
        if (asset.currency != position.baseCurrency) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "折合本位币",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                PrivacyAmountText(
                    money = position.marketValueBase,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HeroMetricCell(
                label = "持仓份额",
                value = AssetsFormatters.formatQuantityWithUnit(
                    quantity = position.holdingQuantity,
                    assetClass = asset.assetClass,
                    symbol = asset.symbol,
                    hidden = amountsHidden,
                ),
                valueTestTag = "detail_holding_quantity",
                modifier = Modifier.weight(1f),
            )
            HeroMetricCell(
                label = "加权成本价",
                value = AssetsFormatters.formatUnitPrice(
                    price = position.averageCostPrice,
                    currency = asset.currency,
                    hidden = false,
                ),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HeroMetricCell(
                label = "最新单价 / 净值",
                value = AssetsFormatters.formatUnitPrice(
                    price = position.effectiveUnitPrice,
                    currency = asset.currency,
                    hidden = false,
                ),
                valueTestTag = "detail_unit_price",
                modifier = Modifier.weight(1f),
            )
            val dailyChange = position.dailyChangeOriginal
            val dailyColor = when {
                dailyChange == null -> MaterialTheme.colorScheme.onPrimaryContainer
                dailyChange.isNegative -> semantic.expense
                else -> semantic.income
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "当日涨跌 ${AssetsFormatters.formatPercentSigned(position.dailyChangePctDecimal, "")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                if (dailyChange != null) {
                    PrivacyAmountText(
                        money = dailyChange,
                        style = MaterialTheme.typography.titleSmall,
                        color = dailyColor,
                        showPositiveSign = true,
                    )
                } else {
                    Text(
                        text = "--",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroMetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueTestTag: String? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.tabularNums(),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (valueTestTag != null) Modifier.testTag(valueTestTag) else Modifier,
        )
    }
}

@Composable
private fun DetailInfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.tabularNums(),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DetailMoneyLine(
    label: String,
    money: Money,
    color: Color,
    showPositiveSign: Boolean = false,
    valueTestTag: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrivacyAmountText(
            money = money,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            showPositiveSign = showPositiveSign,
            modifier = if (valueTestTag != null) Modifier.testTag(valueTestTag) else Modifier,
        )
    }
}

@Composable
private fun InvestmentHistoryRow(
    item: InvestmentTransactionItem,
    assetClass: AssetClass,
    symbol: String,
    amountsHidden: Boolean,
) {
    val semantic = DropletTheme.semanticColors
    val badgeColors = when (item.kind) {
        InvestmentHistoryKind.BUY,
        InvestmentHistoryKind.DCA,
        InvestmentHistoryKind.DEPOSIT_OPEN,
        -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        InvestmentHistoryKind.SELL,
        InvestmentHistoryKind.DEPOSIT_SETTLE,
        -> semantic.warningContainer to semantic.warning
        InvestmentHistoryKind.DIVIDEND,
        InvestmentHistoryKind.DEPOSIT_PAYOUT,
        -> semantic.incomeContainer to semantic.income
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DropletPillBadge(
                    text = item.kind.label,
                    containerColor = badgeColors.first,
                    contentColor = badgeColors.second,
                )
                Text(
                    text = item.dateTimeLabel,
                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val metaLine = buildList {
                item.quantity?.let { qty ->
                    add(AssetsFormatters.formatQuantityWithUnit(qty, assetClass, symbol, amountsHidden))
                }
                item.unitPrice?.let { price ->
                    add("@ ${AssetsFormatters.formatUnitPrice(price, item.amount.currency)}")
                }
                item.cashAccountName?.let { add(it) }
            }.joinToString(" · ")
            if (metaLine.isNotEmpty()) {
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!item.note.isNullOrBlank()) {
                Text(
                    text = item.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            PrivacyAmountText(
                money = item.amount,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            item.fee?.let { fee ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "费",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrivacyAmountText(
                        money = fee,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun pnlColor(
    money: Money,
    semantic: io.github.kvtttba.droplet.core.ui.theme.DropletSemanticColors,
): Color = when {
    money.isPositive -> semantic.income
    money.isNegative -> semantic.expense
    else -> Color.Unspecified
}
