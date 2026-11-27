package io.github.kvtttba.droplet.feature.assets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementStatus
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementSummary
import io.github.kvtttba.droplet.core.domain.investment.AccountValuation
import io.github.kvtttba.droplet.core.domain.investment.AssetOverviewGroup
import io.github.kvtttba.droplet.core.domain.investment.CurrencyDistribution
import io.github.kvtttba.droplet.core.domain.investment.PortfolioPositionValuation
import io.github.kvtttba.droplet.core.domain.investment.PositionValuationSource
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.component.PrivacyToggleIconButton
import io.github.kvtttba.droplet.core.ui.icon.DropletIconRegistry
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.tabularNums
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun AssetsRoute(
    onToggleAmountsHidden: () -> Unit,
    onOpenInvestmentDetail: (String) -> Unit,
    onOpenCreditCardDetail: (String) -> Unit = {},
    onOpenMarketSyncSettings: () -> Unit = {},
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AssetsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.onScreenEntered()
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    AssetsScreen(
        uiState = uiState,
        onToggleAmountsHidden = onToggleAmountsHidden,
        onSelectDisplayCurrency = viewModel::selectDisplayCurrency,
        onManualRefresh = viewModel::refreshNow,
        onOpenInvestmentDetail = onOpenInvestmentDetail,
        onOpenCreditCardDetail = onOpenCreditCardDetail,
        onOpenMarketSyncSettings = onOpenMarketSyncSettings,
        onToggleExcludedExpanded = viewModel::toggleExcludedExpanded,
        onDismissRefreshNotice = viewModel::dismissRefreshNotice,
        onCreateAsset = { form, callback ->
            viewModel.createInvestmentAsset(form) { result ->
                callback(result.exceptionOrNull()?.message)
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(
    uiState: AssetsUiState,
    onToggleAmountsHidden: () -> Unit,
    onSelectDisplayCurrency: (Currency) -> Unit = {},
    onManualRefresh: () -> Unit = {},
    onOpenInvestmentDetail: (String) -> Unit = {},
    onOpenCreditCardDetail: (String) -> Unit = {},
    onOpenMarketSyncSettings: () -> Unit = {},
    onToggleExcludedExpanded: () -> Unit = {},
    onDismissRefreshNotice: () -> Unit = {},
    onCreateAsset: (CreateInvestmentAssetFormInput, (String?) -> Unit) -> Unit = { _, cb -> cb(null) },
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors
    var showCreateSheet by remember { mutableStateOf(false) }

    val summary = uiState.summary
    val dailyAccounts = remember(summary.accountValuations) {
        summary.accountValuations.filter { it.group == AssetOverviewGroup.DAILY_CASH }
    }
    val creditAccounts = remember(summary.accountValuations) {
        summary.accountValuations.filter { it.group == AssetOverviewGroup.CREDIT }
    }
    val investCashAccounts = remember(summary.accountValuations) {
        summary.accountValuations.filter { it.account.subType == AccountSubType.INVEST_CASH }
    }
    val loanAccounts = remember(summary.accountValuations) {
        summary.accountValuations.filter { it.group == AssetOverviewGroup.LOAN }
    }
    val debtReimburseAccounts = remember(summary.accountValuations) {
        summary.accountValuations.filter { it.group == AssetOverviewGroup.DEBT_AND_REIMBURSEMENT }
    }
    val sortedIncludedPositions = remember(summary.includedPositions) {
        summary.includedPositions.sortedWith(
            compareByDescending<PortfolioPositionValuation> { it.hasHolding }
                .thenByDescending { it.marketValueBase.amountMinor }
                .thenBy { it.asset.name },
        )
    }
    val excludedPositionsByAccountId = remember(summary.positions) {
        summary.positions.groupBy { it.asset.accountId }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onManualRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("screen_assets"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "assets_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "资产总览",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "上次更新：${AssetsFormatters.formatShortTimestamp(uiState.lastUpdatedAtEpochMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("assets_last_updated"),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onManualRefresh,
                            enabled = !uiState.isRefreshing,
                            modifier = Modifier.testTag("assets_refresh_button"),
                        ) {
                            if (uiState.isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "手动刷新行情",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(
                            onClick = onOpenMarketSyncSettings,
                            modifier = Modifier.testTag("assets_sync_settings_button"),
                        ) {
                            Icon(
                                imageVector = DropletIcons.Tune,
                                contentDescription = "行情同步与代理设置",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PrivacyToggleIconButton(
                            amountsHidden = amountsHidden,
                            onToggle = onToggleAmountsHidden,
                        )
                    }
                }
            }

            item(key = "assets_hero_card") {
                DropletHeroCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "总净资产 (${uiState.displayCurrency.code})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Currency.entries.forEach { currency ->
                                FilterChip(
                                    selected = uiState.displayCurrency == currency,
                                    onClick = { onSelectDisplayCurrency(currency) },
                                    label = { Text(currency.code, style = MaterialTheme.typography.labelSmall) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                    modifier = Modifier.testTag("assets_currency_${currency.code}"),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    PrivacyAmountText(
                        money = summary.netWorthBase,
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.testTag("assets_net_worth"),
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "总资产",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            PrivacyAmountText(
                                money = summary.totalAssetsBase,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.testTag("assets_total_assets"),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "总负债",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            PrivacyAmountText(
                                money = summary.totalLiabilitiesBase,
                                style = MaterialTheme.typography.titleMedium,
                                color = semantic.expense,
                                modifier = Modifier.testTag("assets_total_liabilities"),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    CurrencyDistributionBar(
                        distributions = summary.currencyDistributions,
                        totalAssetsBase = summary.totalAssetsBase,
                    )
                }
            }

            if (uiState.refreshNotice != null) {
                item(key = "assets_refresh_notice") {
                    val container = when (uiState.refreshNotice.tone) {
                        AssetsNoticeTone.INFO -> MaterialTheme.colorScheme.secondaryContainer
                        AssetsNoticeTone.WARNING -> semantic.warningContainer
                        AssetsNoticeTone.ERROR -> MaterialTheme.colorScheme.errorContainer
                    }
                    val content = when (uiState.refreshNotice.tone) {
                        AssetsNoticeTone.INFO -> MaterialTheme.colorScheme.onSecondaryContainer
                        AssetsNoticeTone.WARNING -> semantic.warning
                        AssetsNoticeTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("assets_refresh_notice"),
                        shape = RoundedCornerShape(14.dp),
                        color = container,
                        contentColor = content,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = uiState.refreshNotice.message,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onDismissRefreshNotice) {
                                Text("知道了", color = content)
                            }
                        }
                    }
                }
            }

            // 1. Credit Accounts Section (high-priority repayment countdown & credit limit status)
            if (creditAccounts.isNotEmpty()) {
                item(key = "section_credit") {
                    val creditGroup = summary.groupSummaries
                        .firstOrNull { it.group == AssetOverviewGroup.CREDIT }
                    DropletCard {
                        AssetHeaderRow(
                            icon = DropletIcons.CreditCard,
                            title = "信用账户",
                            subtitle = "${creditAccounts.size} 个信用账户 · 点击查看账单与还款",
                            amount = creditGroup?.netBase ?: Money.zero(uiState.displayCurrency),
                            amountColor = if ((creditGroup?.netBase?.isNegative) == true) {
                                semantic.expense
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        creditAccounts.forEachIndexed { index, valuation ->
                            CreditAccountRow(
                                valuation = valuation,
                                statementSummary = uiState.creditCardSummariesById[valuation.account.id],
                                displayCurrency = uiState.displayCurrency,
                                onClick = { onOpenCreditCardDetail(valuation.account.id) },
                            )
                            if (index != creditAccounts.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                    }
                }
            }

            // 2. Investment Holdings Section
            item(key = "section_investments_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "投资持仓",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = { showCreateSheet = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("assets_add_investment_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "新建标的",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            item(key = "investments_card") {
                val investTotals = summary.investmentTotals
                val investGroupNet = summary.groupSummaries
                    .firstOrNull { it.group == AssetOverviewGroup.INVESTMENT }
                    ?.netBase ?: investTotals.marketValueBase
                DropletCard {
                    AssetHeaderRow(
                        icon = DropletIcons.CandlestickChart,
                        title = "投资组合",
                        subtitle = "${investTotals.activePositionCount} 项持仓 · ${investCashAccounts.size} 个资金户",
                        amount = investGroupNet,
                    )
                    if (sortedIncludedPositions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val unrealizedColor = if (investTotals.unrealizedPnLBase.isNegative) {
                                semantic.expense
                            } else {
                                semantic.income
                            }
                            val unrealizedBg = if (investTotals.unrealizedPnLBase.isNegative) {
                                semantic.expenseContainer
                            } else {
                                semantic.incomeContainer
                            }
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = unrealizedBg,
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(
                                        text = "累计浮盈 ${AssetsFormatters.formatPercentSigned(investTotals.unrealizedReturnPctDecimal, "")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = unrealizedColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    PrivacyAmountText(
                                        money = investTotals.unrealizedPnLBase,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = unrealizedColor,
                                        showPositiveSign = true,
                                        modifier = Modifier.testTag("assets_investment_unrealized_pnl"),
                                    )
                                }
                            }
                            val dailyChange = investTotals.dailyChangeBase
                            val dailyColor = when {
                                dailyChange == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                dailyChange.isNegative -> semantic.expense
                                else -> semantic.income
                            }
                            val dailyBg = when {
                                dailyChange == null -> MaterialTheme.colorScheme.surfaceContainerLow
                                dailyChange.isNegative -> semantic.expenseContainer
                                else -> semantic.incomeContainer
                            }
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = dailyBg,
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(
                                        text = "当日涨跌 ${AssetsFormatters.formatPercentSigned(investTotals.dailyChangePctDecimal, "")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = dailyColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
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
                                            color = dailyColor,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        sortedIncludedPositions.forEachIndexed { index, position ->
                            InvestmentPositionRow(
                                position = position,
                                displayCurrency = uiState.displayCurrency,
                                onClick = { onOpenInvestmentDetail(position.asset.id) },
                            )
                            if (index != sortedIncludedPositions.lastIndex || investCashAccounts.isNotEmpty()) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "尚未添加投资标的",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "支持 A股、基金、ETF、港美股、黄金、加密货币（8 位小数）、定期存款与银行理财。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (investCashAccounts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "投资资金户",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                        investCashAccounts.forEachIndexed { idx, acctVal ->
                            AccountValuationRow(
                                valuation = acctVal,
                                displayCurrency = uiState.displayCurrency,
                            )
                            if (idx != investCashAccounts.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 48.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                    }
                }
            }

            // 2. Daily Cash Section
            if (dailyAccounts.isNotEmpty()) {
                item(key = "section_daily_cash") {
                    val dailyGroup = summary.groupSummaries
                        .firstOrNull { it.group == AssetOverviewGroup.DAILY_CASH }
                    DropletCard {
                        AssetHeaderRow(
                            icon = DropletIcons.AccountBalance,
                            title = "日常资金",
                            subtitle = "${dailyAccounts.size} 个账户 · 现金 / 储蓄卡 / 储值",
                            amount = dailyGroup?.netBase ?: Money.zero(uiState.displayCurrency),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        dailyAccounts.forEachIndexed { index, valuation ->
                            AccountValuationRow(
                                valuation = valuation,
                                displayCurrency = uiState.displayCurrency,
                            )
                            if (index != dailyAccounts.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 48.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                    }
                }
            }

            // 4. Loans & Debt / Reimbursement
            if (loanAccounts.isNotEmpty() || debtReimburseAccounts.isNotEmpty()) {
                item(key = "section_loan_debt") {
                    val combinedMinor = (loanAccounts + debtReimburseAccounts)
                        .fold(0L) { acc, item -> Math.addExact(acc, item.baseBalance.amountMinor) }
                    val combinedNet = Money(combinedMinor, uiState.displayCurrency)
                    DropletCard {
                        AssetHeaderRow(
                            icon = DropletIcons.ReceiptLong,
                            title = "分期贷款 · 借贷与待报销",
                            subtitle = "贷款 ${loanAccounts.size} 个 · 借贷/报销 ${debtReimburseAccounts.size} 个",
                            amount = combinedNet,
                            amountColor = if (combinedNet.isNegative) semantic.expense else MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        val allItems = loanAccounts + debtReimburseAccounts
                        allItems.forEachIndexed { index, valuation ->
                            AccountValuationRow(
                                valuation = valuation,
                                displayCurrency = uiState.displayCurrency,
                            )
                            if (index != allItems.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 48.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                    }
                }
            }

            // 5. Excluded from Net Worth (Collapsible)
            if (summary.excludedAccountValuations.isNotEmpty()) {
                item(key = "section_excluded_accounts") {
                    DropletCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onToggleExcludedExpanded)
                                .padding(vertical = 4.dp)
                                .testTag("assets_excluded_toggle"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = DropletIcons.Archive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "不计入总资产 (${summary.excludedAccountValuations.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = if (uiState.excludedExpanded) "点击收起" else "点击展开查看明细",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            PrivacyAmountText(
                                money = summary.excludedNetWorthBase,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (uiState.excludedExpanded) {
                                    Icons.Rounded.KeyboardArrowUp
                                } else {
                                    Icons.Rounded.KeyboardArrowDown
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (uiState.excludedExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            summary.excludedAccountValuations.forEachIndexed { index, valuation ->
                                val linkedPositions = excludedPositionsByAccountId[valuation.account.id].orEmpty()
                                val primaryLinkedAssetId = linkedPositions.firstOrNull()?.asset?.id
                                val excludedClick: (() -> Unit)? = when {
                                    valuation.account.subType == AccountSubType.CREDIT_CARD -> {
                                        { onOpenCreditCardDetail(valuation.account.id) }
                                    }
                                    primaryLinkedAssetId != null -> {
                                        { onOpenInvestmentDetail(primaryLinkedAssetId) }
                                    }
                                    else -> null
                                }
                                AccountValuationRow(
                                    valuation = valuation,
                                    displayCurrency = uiState.displayCurrency,
                                    onClick = excludedClick,
                                )
                                if (index != summary.excludedAccountValuations.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 48.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        CreateInvestmentAssetSheet(
            availableCashAccounts = uiState.availableCashAccounts,
            availableHoldingAccounts = uiState.availableHoldingAccounts,
            isSubmitting = uiState.isCreatingAsset,
            onDismiss = { showCreateSheet = false },
            onSubmit = onCreateAsset,
        )
    }
}

@Composable
private fun InvestmentPositionRow(
    position: PortfolioPositionValuation,
    displayCurrency: Currency,
    onClick: () -> Unit,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors
    val asset = position.asset
    val accentColor = DropletIconRegistry.parseColor(
        AssetsFormatters.assetClassColorHex(asset.assetClass),
        MaterialTheme.colorScheme.primary,
    )
    val pnlColor = when {
        position.unrealizedPnLOriginal.isPositive -> semantic.income
        position.unrealizedPnLOriginal.isNegative -> semantic.expense
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag("assets_position_${asset.symbol}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (asset.assetClass) {
                        AssetClass.FIXED_DEPOSIT -> DropletIcons.Savings
                        AssetClass.BANK_WEALTH -> DropletIcons.AccountBalance
                        AssetClass.GOLD -> DropletIcons.Payments
                        else -> DropletIcons.TrendingUp
                    },
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = asset.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DropletPillBadge(
                        text = AssetsFormatters.assetClassLabel(asset.assetClass),
                        containerColor = accentColor.copy(alpha = 0.12f),
                        contentColor = accentColor,
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                val depositVal = position.fixedDepositValuation
                if (asset.assetClass == AssetClass.FIXED_DEPOSIT && depositVal != null) {
                    Text(
                        text = "年化 ${AssetsFormatters.formatAnnualRatePercent(depositVal.annualRateDecimal)} · " +
                            "${AssetsFormatters.fixedDepositStatusLabel(depositVal.status)} " +
                            "${depositVal.elapsedDays}/${depositVal.totalTermDays}天",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    val priceText = AssetsFormatters.formatUnitPrice(
                        price = position.effectiveUnitPrice,
                        currency = asset.currency,
                        hidden = false,
                    )
                    val qtyText = AssetsFormatters.formatQuantityWithUnit(
                        quantity = position.holdingQuantity,
                        assetClass = asset.assetClass,
                        symbol = asset.symbol,
                        hidden = amountsHidden,
                    )
                    Text(
                        text = "${asset.symbol} · $priceText · $qtyText",
                        style = MaterialTheme.typography.bodySmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (position.valuationSource == PositionValuationSource.MANUAL_OVERRIDE ||
                        position.valuationSource == PositionValuationSource.COST_BASIS_FALLBACK
                    ) {
                        Spacer(modifier = Modifier.height(4.dp))
                        DropletPillBadge(
                            text = AssetsFormatters.valuationSourceBadge(
                                position.valuationSource,
                                position.bankWealthValuationMode,
                            ),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                PrivacyAmountText(
                    money = position.marketValueOriginal,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (asset.currency != displayCurrency) {
                    PrivacyAmountText(
                        money = position.marketValueBase,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrivacyAmountText(
                        money = position.unrealizedPnLOriginal,
                        style = MaterialTheme.typography.labelSmall,
                        color = pnlColor,
                        showPositiveSign = true,
                    )
                    val pct = AssetsFormatters.formatPercentSigned(position.unrealizedReturnPctDecimal, "")
                    if (pct.isNotEmpty()) {
                        Text(
                            text = pct,
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = pnlColor,
                        )
                    }
                }
            }
        }

        val depositVal = position.fixedDepositValuation
        if (asset.assetClass == AssetClass.FIXED_DEPOSIT && depositVal != null && depositVal.totalTermDays > 0L) {
            Spacer(modifier = Modifier.height(8.dp))
            val progress = BigDecimal.valueOf(depositVal.elapsedDays)
                .divide(BigDecimal.valueOf(depositVal.totalTermDays), 4, RoundingMode.HALF_UP)
                .toFloat()
                .coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = accentColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun AccountValuationRow(
    valuation: AccountValuation,
    displayCurrency: Currency,
    onClick: (() -> Unit)? = null,
) {
    val semantic = DropletTheme.semanticColors
    val account = valuation.account
    val accent = DropletIconRegistry.parseColor(account.color, MaterialTheme.colorScheme.primary)
    val amountColor = if (valuation.originalBalance.isNegative) {
        semantic.expense
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DropletIconRegistry.resolve(account.icon),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = account.currency.code,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            PrivacyAmountText(
                money = valuation.originalBalance,
                style = MaterialTheme.typography.titleSmall,
                color = amountColor,
            )
            if (account.currency != displayCurrency) {
                PrivacyAmountText(
                    money = valuation.baseBalance,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CreditAccountRow(
    valuation: AccountValuation,
    statementSummary: CreditCardStatementSummary?,
    displayCurrency: Currency,
    onClick: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val account = valuation.account
    val accent = DropletIconRegistry.parseColor(account.color, MaterialTheme.colorScheme.primary)
    val usedMoney = statementSummary?.usedLimit ?: Money(
        if (valuation.originalBalance.amountMinor < 0L) Math.negateExact(valuation.originalBalance.amountMinor) else 0L,
        account.currency,
    )
    val overpayment = statementSummary?.overpayment ?: Money(
        if (valuation.originalBalance.amountMinor > 0L) valuation.originalBalance.amountMinor else 0L,
        account.currency,
    )
    val limitMoney = statementSummary?.creditLimit ?: account.creditLimit
    val availableMoney = statementSummary?.availableCredit ?: limitMoney?.let { limit ->
        Money(Math.addExact(limit.amountMinor, valuation.originalBalance.amountMinor).coerceAtLeast(0L), account.currency)
    }

    val (statusLabel, statusContainer, statusContent) = when (statementSummary?.status) {
        CreditCardStatementStatus.OVERDUE -> Triple(
            "已逾期 ${statementSummary.overdueDays} 天",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        CreditCardStatementStatus.DUE_SOON -> Triple(
            if (statementSummary.isDueToday) "今日还款" else "${statementSummary.daysUntilCurrentDue}天后还款",
            semantic.warningContainer,
            semantic.warning,
        )
        CreditCardStatementStatus.PENDING -> Triple(
            statementSummary.daysUntilCurrentDue?.let { "${it}天后还款" } ?: "待还款",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        CreditCardStatementStatus.PAID_OFF -> Triple(
            "本期已还清",
            semantic.incomeContainer,
            semantic.income,
        )
        CreditCardStatementStatus.UNCONFIGURED, null -> Triple(
            "点此设账单日",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val countdownMeta = buildList {
        if (statementSummary != null && statementSummary.lastStatementDate != null) {
            val billPart = when {
                statementSummary.isBillingDayToday -> "今日出账"
                statementSummary.daysUntilNextBilling != null -> "${statementSummary.daysUntilNextBilling}天后出账"
                else -> account.billingDay?.let { "账单日 ${it}日" }.orEmpty()
            }
            if (billPart.isNotEmpty()) add(billPart)
        } else {
            account.billingDay?.let { add("账单日 ${it}日") }
        }

        if (statementSummary != null && statementSummary.currentStatementDueDate != null) {
            val duePart = when {
                statementSummary.status == CreditCardStatementStatus.OVERDUE ->
                    "还款日 ${account.dueDay}日 · 已逾期${statementSummary.overdueDays}天"
                statementSummary.isDueToday ->
                    "今日到期还款"
                statementSummary.daysUntilDisplayDue != null ->
                    "还款日 ${account.dueDay}日 · ${statementSummary.daysUntilDisplayDue}天后"
                else -> "还款日 ${account.dueDay}日"
            }
            add(duePart)
        } else {
            account.dueDay?.let { add("还款日 ${it}日") }
        }
    }.joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag("assets_credit_card_${account.name}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DropletIconRegistry.resolve(account.icon),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = account.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DropletPillBadge(
                        text = statusLabel,
                        containerColor = statusContainer,
                        contentColor = statusContent,
                    )
                }
                if (countdownMeta.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = countdownMeta,
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                PrivacyAmountText(
                    money = valuation.originalBalance,
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        valuation.originalBalance.isNegative -> semantic.expense
                        valuation.originalBalance.isPositive -> semantic.income
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (account.currency != displayCurrency) {
                    PrivacyAmountText(
                        money = valuation.baseBalance,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "查看信用卡账单",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }

        if (statementSummary != null && statementSummary.remainingStatementDue.isPositive) {
            Spacer(modifier = Modifier.height(8.dp))
            val dueBg = when (statementSummary.status) {
                CreditCardStatementStatus.OVERDUE -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                CreditCardStatementStatus.DUE_SOON -> semantic.warningContainer.copy(alpha = 0.75f)
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            }
            val dueTextColor = when (statementSummary.status) {
                CreditCardStatementStatus.OVERDUE -> MaterialTheme.colorScheme.onErrorContainer
                CreditCardStatementStatus.DUE_SOON -> semantic.warning
                else -> MaterialTheme.colorScheme.onSurface
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = dueBg,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (statementSummary.status == CreditCardStatementStatus.OVERDUE) {
                            "本期账单剩余应还（已逾期）"
                        } else {
                            "本期账单剩余应还"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = dueTextColor,
                    )
                    PrivacyAmountText(
                        money = statementSummary.remainingStatementDue,
                        style = MaterialTheme.typography.titleSmall,
                        color = dueTextColor,
                    )
                }
            }
        } else if (overpayment.isPositive) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "含溢缴款",
                    style = MaterialTheme.typography.labelSmall,
                    color = semantic.income,
                )
                PrivacyAmountText(
                    money = overpayment,
                    style = MaterialTheme.typography.labelSmall,
                    color = semantic.income,
                    showPositiveSign = true,
                )
            }
        }

        if (limitMoney != null && limitMoney.isPositive && availableMoney != null) {
            val usedRatio = BigDecimal.valueOf(usedMoney.amountMinor)
                .divide(BigDecimal.valueOf(limitMoney.amountMinor), 4, RoundingMode.HALF_UP)
            val progress = usedRatio.toFloat().coerceIn(0f, 1f)
            val usedPctLabel = statementSummary?.utilizationPctDecimal?.let { "$it%" }
                ?: "${usedRatio.multiply(BigDecimal("100")).setScale(0, RoundingMode.HALF_UP).toPlainString()}%"

            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = when {
                    progress >= 0.8f -> semantic.expense
                    progress >= 0.6f -> semantic.warning
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrivacyAmountText(
                        money = usedMoney,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "($usedPctLabel)",
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "可用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrivacyAmountText(
                        money = availableMoney,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrencyDistributionBar(
    distributions: List<CurrencyDistribution>,
    totalAssetsBase: Money,
) {
    val semantic = DropletTheme.semanticColors
    val byCurrency = distributions.associateBy { it.currency }
    val cnyDist = byCurrency[Currency.CNY]
    val usdDist = byCurrency[Currency.USD]
    val hkdDist = byCurrency[Currency.HKD]

    val cnyWeight = cnyDist?.let { AssetsFormatters.computeBarWeight(it.assetSharePctDecimal) } ?: 0f
    val usdWeight = usdDist?.let { AssetsFormatters.computeBarWeight(it.assetSharePctDecimal) } ?: 0f
    val hkdWeight = hkdDist?.let { AssetsFormatters.computeBarWeight(it.assetSharePctDecimal) } ?: 0f
    val hasPositiveAssets = totalAssetsBase.isPositive && (cnyWeight + usdWeight + hkdWeight > 0f)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("assets_currency_distribution"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (hasPositiveAssets) {
                if (cnyWeight > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(cnyWeight)
                            .height(8.dp)
                            .background(semantic.cnyBar),
                    )
                }
                if (usdWeight > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(usdWeight)
                            .height(8.dp)
                            .background(semantic.usdBar),
                    )
                }
                if (hkdWeight > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(hkdWeight)
                            .height(8.dp)
                            .background(semantic.hkdBar),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CurrencyLegendDot(
                color = semantic.cnyBar,
                label = "CNY ${AssetsFormatters.formatSharePercent(cnyDist?.assetSharePctDecimal ?: "0")}",
            )
            CurrencyLegendDot(
                color = semantic.usdBar,
                label = "USD ${AssetsFormatters.formatSharePercent(usdDist?.assetSharePctDecimal ?: "0")}",
            )
            CurrencyLegendDot(
                color = semantic.hkdBar,
                label = "HKD ${AssetsFormatters.formatSharePercent(hkdDist?.assetSharePctDecimal ?: "0")}",
            )
        }
    }
}

@Composable
private fun CurrencyLegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.tabularNums(),
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun AssetHeaderRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    amount: Money,
    amountColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        PrivacyAmountText(
            money = amount,
            style = MaterialTheme.typography.titleMedium,
            color = amountColor,
        )
    }
}
