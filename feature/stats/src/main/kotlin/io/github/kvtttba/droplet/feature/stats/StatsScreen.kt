package io.github.kvtttba.droplet.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.kvtttba.droplet.core.domain.automation.BudgetAlertLevel
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgress
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.component.PrivacyToggleIconButton
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme

private val periodOptions = listOf("月度", "年度", "自定义")

@Composable
fun StatsScreen(
    onToggleAmountsHidden: () -> Unit,
    monthlyBudgetProgress: BudgetProgress? = null,
    onOpenBudgetManagement: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    var selectedPeriod by remember { mutableStateOf(periodOptions.first()) }
    val semantic = DropletTheme.semanticColors
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_stats"),
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
                        text = "统计分析",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "按时间区间与标签多维聚合",
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                periodOptions.forEach { period ->
                    FilterChip(
                        selected = selectedPeriod == period,
                        onClick = { selectedPeriod = period },
                        label = { Text(period) },
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                DropletPillBadge(text = "#全部标签")
            }
        }

        item {
            val budget = monthlyBudgetProgress
            val usedMoney = budget?.spentAmount ?: Money.fromDecimalString("8513.50", Currency.CNY)
            val limitMoney = budget?.budget?.limitAmount ?: Money.fromDecimalString("15000.00", Currency.CNY)
            val progressValue = budget?.progressFraction ?: 0.56f
            val badgeText = when {
                budget == null -> "已用 56%"
                budget.alertLevel == BudgetAlertLevel.EXCEEDED -> "已超支 ${budget.usedPercentageInt}%"
                budget.alertLevel == BudgetAlertLevel.WARNING -> "预警 ${budget.usedPercentageInt}%"
                else -> "已用 ${budget.usedPercentageInt}%"
            }
            val badgeBg = when (budget?.alertLevel) {
                BudgetAlertLevel.EXCEEDED -> semantic.expenseContainer
                BudgetAlertLevel.WARNING -> semantic.warningContainer
                else -> semantic.incomeContainer
            }
            val badgeFg = when (budget?.alertLevel) {
                BudgetAlertLevel.EXCEEDED -> semantic.expense
                BudgetAlertLevel.WARNING -> semantic.warning
                else -> semantic.income
            }
            val barColor = when (budget?.alertLevel) {
                BudgetAlertLevel.EXCEEDED -> semantic.expense
                BudgetAlertLevel.WARNING -> semantic.warning
                else -> MaterialTheme.colorScheme.primary
            }

            DropletCard(
                modifier = Modifier
                    .clickable(onClick = onOpenBudgetManagement)
                    .testTag("stats_budget_card"),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "本月总预算消耗",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    DropletPillBadge(
                        text = badgeText,
                        containerColor = badgeBg,
                        contentColor = badgeFg,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    PrivacyAmountText(
                        money = usedMoney,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "预算",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrivacyAmountText(
                            money = limitMoney,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progressValue },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = barColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )
            }
        }

        item {
            DropletSectionHeader(
                title = "核心报表模块",
                trailing = "M5 接入实时图表引擎",
            )
        }

        item {
            DropletCard {
                Text(
                    text = "收支趋势",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "按日 / 月柱状对比与收支净额曲线",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp),
                ) {
                    val heights = listOf(0.45f, 0.72f, 0.58f, 0.85f, 0.64f, 0.52f)
                    val gap = size.width / (heights.size * 2f)
                    val barWidth = gap * 1.05f
                    heights.forEachIndexed { idx, ratio ->
                        val left = gap * 0.5f + idx * (barWidth + gap)
                        val barHeight = size.height * ratio
                        drawRoundRect(
                            color = if (idx == 3) primaryColor else primaryColor.copy(alpha = 0.35f),
                            topLeft = Offset(left, size.height - barHeight),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(12f, 12f),
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DropletCard(
                    modifier = Modifier
                        .weight(1f)
                        .height(180.dp),
                ) {
                    Text(
                        text = "分类占比",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Canvas(
                        modifier = Modifier
                            .size(72.dp)
                            .align(Alignment.CenterHorizontally),
                    ) {
                        val stroke = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
                        drawArc(
                            color = primaryColor,
                            startAngle = -90f,
                            sweepAngle = 155f,
                            useCenter = false,
                            style = stroke,
                        )
                        drawArc(
                            color = secondaryColor,
                            startAngle = 75f,
                            sweepAngle = 95f,
                            useCenter = false,
                            style = stroke,
                        )
                        drawArc(
                            color = semantic.expense.copy(alpha = 0.85f),
                            startAngle = 180f,
                            sweepAngle = 80f,
                            useCenter = false,
                            style = stroke,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "餐饮 · 交通 · 居住",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DropletCard(
                    modifier = Modifier
                        .weight(1f)
                        .height(180.dp),
                ) {
                    Text(
                        text = "净资产与日历",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "资产负债历史走势",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "每日收支日历下钻",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    DropletPillBadge(text = "支持多标签过滤")
                }
            }
        }
    }
}
