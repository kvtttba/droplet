package io.github.kvtttba.droplet.feature.assets

import androidx.compose.foundation.background
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.component.PrivacyToggleIconButton
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme

@Composable
fun AssetsScreen(
    onToggleAmountsHidden: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    var displayCurrency by remember { mutableStateOf(Currency.CNY) }
    val semantic = DropletTheme.semanticColors

    val (netWorth, totalAssets, totalLiabilities) = when (displayCurrency) {
        Currency.CNY -> Triple(
            Money.fromDecimalString("186420.00", Currency.CNY),
            Money.fromDecimalString("198920.00", Currency.CNY),
            Money.fromDecimalString("12500.00", Currency.CNY),
        )
        Currency.USD -> Triple(
            Money.fromDecimalString("25891.67", Currency.USD),
            Money.fromDecimalString("27627.78", Currency.USD),
            Money.fromDecimalString("1736.11", Currency.USD),
        )
        Currency.HKD -> Triple(
            Money.fromDecimalString("201535.14", Currency.HKD),
            Money.fromDecimalString("215048.65", Currency.HKD),
            Money.fromDecimalString("13513.51", Currency.HKD),
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_assets"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "资产总览",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "多币种实时折算 · 9 类投资资产",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrivacyToggleIconButton(
                    amountsHidden = amountsHidden,
                    onToggle = onToggleAmountsHidden,
                )
            }
        }

        item {
            DropletHeroCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "总净资产 (${displayCurrency.code})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Currency.entries.forEach { currency ->
                            FilterChip(
                                selected = displayCurrency == currency,
                                onClick = { displayCurrency = currency },
                                label = { Text(currency.code, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                PrivacyAmountText(
                    money = netWorth,
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
                            money = totalAssets,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
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
                            money = totalLiabilities,
                            style = MaterialTheme.typography.titleMedium,
                            color = semantic.expense,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                CurrencyDistributionBar(
                    cnyWeight = 0.76f,
                    usdWeight = 0.16f,
                    hkdWeight = 0.08f,
                )
            }
        }

        item {
            DropletSectionHeader(
                title = "账户与持仓分组",
                trailing = "示例预览 · M2 / M3 接入",
            )
        }

        item {
            AssetGroupCard(
                icon = DropletIcons.AccountBalance,
                title = "日常资金",
                subtitle = "现金 · 储蓄卡 · 交通/储值账户",
                amount = Money.fromDecimalString("64220.00", Currency.CNY),
                badgeText = "3 个账户",
            )
        }

        item {
            DropletCard {
                AssetHeaderRow(
                    icon = DropletIcons.CreditCard,
                    title = "信用账户",
                    subtitle = "账单日 4 天后 · 还款日 19 天后",
                    amount = Money.fromDecimalString("-12500.00", Currency.CNY),
                    amountColor = semantic.expense,
                )
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { 0.25f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "已用额度 25%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "可用额度",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrivacyAmountText(
                            money = Money.fromDecimalString("37500.00", Currency.CNY),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            DropletCard {
                AssetHeaderRow(
                    icon = DropletIcons.CandlestickChart,
                    title = "投资持仓",
                    subtitle = "A股 · 基金 · ETF · 港美股 · 黄金 · 加密 · 定存 · 理财",
                    amount = Money.fromDecimalString("134700.00", Currency.CNY),
                )
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DropletPillBadge(
                        text = "当日浮盈 +1.42%",
                        containerColor = semantic.incomeContainer,
                        contentColor = semantic.income,
                    )
                    Text(
                        text = "公开行情自动刷新 · 支持手动覆写",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            AssetGroupCard(
                icon = DropletIcons.ReceiptLong,
                title = "分期贷款 · 借贷与待报销",
                subtitle = "等额本息 / 本金 / 固定手续费 · 往来人应收应付",
                amount = Money.fromDecimalString("64.00", Currency.CNY),
                badgeText = "待报销 1 笔",
            )
        }
    }
}

@Composable
private fun CurrencyDistributionBar(
    cnyWeight: Float,
    usdWeight: Float,
    hkdWeight: Float,
) {
    val semantic = DropletTheme.semanticColors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp)),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(cnyWeight)
                    .height(8.dp)
                    .background(semantic.cnyBar),
            )
            Box(
                modifier = Modifier
                    .weight(usdWeight)
                    .height(8.dp)
                    .background(semantic.usdBar),
            )
            Box(
                modifier = Modifier
                    .weight(hkdWeight)
                    .height(8.dp)
                    .background(semantic.hkdBar),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CurrencyLegendDot(color = semantic.cnyBar, label = "CNY 76%")
            CurrencyLegendDot(color = semantic.usdBar, label = "USD 16%")
            CurrencyLegendDot(color = semantic.hkdBar, label = "HKD 8%")
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
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun AssetGroupCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    amount: Money,
    badgeText: String,
) {
    DropletCard {
        AssetHeaderRow(
            icon = icon,
            title = title,
            subtitle = subtitle,
            amount = amount,
        )
        Spacer(modifier = Modifier.height(10.dp))
        DropletPillBadge(text = badgeText)
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
