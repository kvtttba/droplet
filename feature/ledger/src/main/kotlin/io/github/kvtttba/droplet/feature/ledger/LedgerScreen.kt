package io.github.kvtttba.droplet.feature.ledger

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
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

private data class SampleLedgerEntry(
    val id: String,
    val title: String,
    val accountLabel: String,
    val tag: String,
    val statusBadge: String? = null,
    val amount: Money,
    val isIncome: Boolean,
    val icon: ImageVector,
)

private val ledgerFilterOptions = listOf("全部", "工作日", "通勤", "出差", "待报销")

private val todayPreviewEntries = listOf(
    SampleLedgerEntry(
        id = "preview-1",
        title = "午餐 · 简餐",
        accountLabel = "储蓄卡",
        tag = "#工作日",
        amount = Money.fromDecimalString("-38.50", Currency.CNY),
        isIncome = false,
        icon = DropletIcons.Restaurant,
    ),
    SampleLedgerEntry(
        id = "preview-2",
        title = "地铁通勤",
        accountLabel = "交通储值卡",
        tag = "#通勤",
        amount = Money.fromDecimalString("-6.00", Currency.CNY),
        isIncome = false,
        icon = DropletIcons.DirectionsSubway,
    ),
    SampleLedgerEntry(
        id = "preview-3",
        title = "差旅打车",
        accountLabel = "信用卡",
        tag = "#出差",
        statusBadge = "待报销",
        amount = Money.fromDecimalString("-64.00", Currency.CNY),
        isIncome = false,
        icon = DropletIcons.LocalTaxi,
    ),
)

private val yesterdayPreviewEntries = listOf(
    SampleLedgerEntry(
        id = "preview-4",
        title = "ETF 现金分红",
        accountLabel = "美元资金户",
        tag = "#被动收入",
        amount = Money.fromDecimalString("15.00", Currency.USD),
        isIncome = true,
        icon = DropletIcons.TrendingUp,
    ),
)

@Composable
fun LedgerScreen(
    onToggleAmountsHidden: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    var selectedFilter by remember { mutableStateOf(ledgerFilterOptions.first()) }
    val semantic = DropletTheme.semanticColors

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_ledger"),
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
                        text = "Droplet 记账",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "本地复式账本 · 纯净离线",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = DropletIcons.CalendarMonth,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "2026年11月",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    PrivacyToggleIconButton(
                        amountsHidden = amountsHidden,
                        onToggle = onToggleAmountsHidden,
                    )
                }
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
                        text = "本月结余 (CNY)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    DropletPillBadge(
                        text = "单账本 · 多标签",
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                PrivacyAmountText(
                    money = Money.fromDecimalString("4286.50", Currency.CNY),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("ledger_monthly_balance"),
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "本月收入",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        PrivacyAmountText(
                            money = Money.fromDecimalString("12800.00", Currency.CNY),
                            style = MaterialTheme.typography.titleMedium,
                            color = semantic.income,
                            showPositiveSign = true,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "本月支出",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        PrivacyAmountText(
                            money = Money.fromDecimalString("-8513.50", Currency.CNY),
                            style = MaterialTheme.typography.titleMedium,
                            color = semantic.expense,
                        )
                    }
                }
            }
        }

        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "搜索流水",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "筛选",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(ledgerFilterOptions) { label ->
                    FilterChip(
                        selected = selectedFilter == label,
                        onClick = { selectedFilter = label },
                        label = { Text(label) },
                    )
                }
            }
        }

        item {
            DropletSectionHeader(
                title = "11月27日 · 周五",
                trailing = "示例结构 · M1 接入实账",
            )
        }

        item {
            DropletCard(contentPadding = PaddingValues(vertical = 6.dp, horizontal = 14.dp)) {
                todayPreviewEntries.forEachIndexed { index, entry ->
                    LedgerEntryRow(entry = entry)
                    if (index != todayPreviewEntries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 52.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }

        item {
            DropletSectionHeader(
                title = "11月26日 · 周四",
                trailing = "多币种锁定快照",
            )
        }

        item {
            DropletCard(contentPadding = PaddingValues(vertical = 6.dp, horizontal = 14.dp)) {
                yesterdayPreviewEntries.forEach { entry ->
                    LedgerEntryRow(entry = entry)
                }
            }
        }

        item {
            DropletCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = DropletIcons.AccountBalanceWallet,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "右下角按钮支持快速记账",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "支持手动直观表单、专业复式分录，以及拍照 / 语音 / 一句话 AI 记账。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerEntryRow(
    entry: SampleLedgerEntry,
) {
    val semantic = DropletTheme.semanticColors
    val iconContainer: Color = if (entry.isIncome) {
        semantic.incomeContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    }
    val iconTint: Color = if (entry.isIncome) {
        semantic.income
    } else {
        MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.statusBadge != null) {
                    DropletPillBadge(
                        text = entry.statusBadge,
                        containerColor = semantic.warningContainer,
                        contentColor = semantic.warning,
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${entry.accountLabel} · ${entry.tag}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        PrivacyAmountText(
            money = entry.amount,
            style = MaterialTheme.typography.titleSmall,
            color = if (entry.isIncome) semantic.income else MaterialTheme.colorScheme.onSurface,
            showPositiveSign = entry.isIncome,
        )
    }
}
