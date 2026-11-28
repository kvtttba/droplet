package io.github.kvtttba.droplet.core.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kvtttba.droplet.core.domain.stats.TrendBucket
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.tabularNums

/**
 * Grouped bar comparison chart for income vs. net expense over daily or monthly buckets,
 * with interactive bucket selection tooltip banner and full [LocalAmountsHidden] masking.
 */
@Composable
fun IncomeExpenseTrendChart(
    buckets: List<TrendBucket>,
    modifier: Modifier = Modifier,
    initialSelectedIndex: Int = -1,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val selectionFill = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)

    val hasAnyValue = remember(buckets) {
        buckets.any { it.income.amountMinor > 0L || it.expense.amountMinor > 0L }
    }

    val defaultSelected = remember(buckets, initialSelectedIndex) {
        if (initialSelectedIndex in buckets.indices) {
            initialSelectedIndex
        } else {
            buckets.indexOfLast { it.income.amountMinor > 0L || it.expense.amountMinor > 0L }
                .takeIf { it >= 0 } ?: buckets.lastIndex
        }
    }
    var selectedIndex by remember(buckets, defaultSelected) {
        mutableIntStateOf(defaultSelected)
    }

    Column(modifier = modifier.fillMaxWidth().testTag("chart_income_expense_trend")) {
        // Legend row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChartLegendDot(color = semantic.expense, label = "净支出")
            ChartLegendDot(color = semantic.income, label = "收入")
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "点击柱状查看详情",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (buckets.isEmpty() || !hasAnyValue) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(156.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "当前周期暂无收支趋势数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        // Selected bucket summary pill banner
        val activeBucket = buckets.getOrNull(selectedIndex)
        if (activeBucket != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("trend_selected_banner"),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = activeBucket.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "支",
                                style = MaterialTheme.typography.labelSmall,
                                color = semantic.expense,
                            )
                            PrivacyAmountText(
                                money = activeBucket.expense,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "收",
                                style = MaterialTheme.typography.labelSmall,
                                color = semantic.income,
                            )
                            PrivacyAmountText(
                                money = activeBucket.income,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        val currency = buckets.first().expense.currency
        val rawMaxMinor = remember(buckets) {
            buckets.maxOf { maxOf(it.income.amountMinor.coerceAtLeast(0L), it.expense.amountMinor.coerceAtLeast(0L)) }
                .coerceAtLeast(Money.SCALE_FACTOR)
        }
        val midMinor = rawMaxMinor / 2L

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left Y-axis labels
            Column(
                modifier = Modifier
                    .width(44.dp)
                    .fillMaxHeight()
                    .padding(top = 2.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = ChartFormatters.formatAxisTick(Money(rawMaxMinor, currency), amountsHidden),
                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    text = ChartFormatters.formatAxisTick(Money(midMinor, currency), amountsHidden),
                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    text = if (amountsHidden) "****" else "0",
                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Main Canvas bar plot
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(buckets) {
                        detectTapGestures { offset ->
                            if (buckets.isNotEmpty() && size.width > 0) {
                                val slotWidth = size.width.toFloat() / buckets.size
                                val tapped = (offset.x / slotWidth).toInt().coerceIn(0, buckets.lastIndex)
                                selectedIndex = tapped
                            }
                        }
                    },
            ) {
                val chartHeight = size.height
                val chartWidth = size.width
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                // Horizontal reference grid lines (top, middle, baseline)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, 1.dp.toPx()),
                    end = Offset(chartWidth, 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
                drawLine(
                    color = gridColor,
                    start = Offset(0f, chartHeight * 0.5f),
                    end = Offset(chartWidth, chartHeight * 0.5f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
                drawLine(
                    color = gridColor,
                    start = Offset(0f, chartHeight - 1.dp.toPx()),
                    end = Offset(chartWidth, chartHeight - 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )

                val count = buckets.size
                val slotWidth = chartWidth / count.toFloat()
                val usableHeight = (chartHeight - 6.dp.toPx()).coerceAtLeast(1f)
                val barPairGap = if (count > 20) 1.dp.toPx() else 2.dp.toPx()
                val barWidth = ((slotWidth * 0.72f - barPairGap) / 2f).coerceIn(2.dp.toPx(), 12.dp.toPx())
                val corner = CornerRadius(barWidth / 2f, barWidth / 2f)

                buckets.forEachIndexed { idx, bucket ->
                    val slotLeft = idx * slotWidth
                    if (idx == selectedIndex) {
                        drawRoundRect(
                            color = selectionFill,
                            topLeft = Offset(slotLeft + slotWidth * 0.06f, 0f),
                            size = Size(slotWidth * 0.88f, chartHeight),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                        )
                    }

                    val pairWidth = barWidth * 2f + barPairGap
                    val pairStart = slotLeft + (slotWidth - pairWidth) / 2f

                    val expMinor = bucket.expense.amountMinor.coerceAtLeast(0L)
                    if (expMinor > 0L) {
                        val ratio = ChartFormatters.ratioToFloat(expMinor, rawMaxMinor)
                        val barH = (usableHeight * ratio).coerceAtLeast(3.dp.toPx())
                        drawRoundRect(
                            color = if (idx == selectedIndex) semantic.expense else semantic.expense.copy(alpha = 0.78f),
                            topLeft = Offset(pairStart, chartHeight - barH - 1.dp.toPx()),
                            size = Size(barWidth, barH),
                            cornerRadius = corner,
                        )
                    }

                    val incMinor = bucket.income.amountMinor.coerceAtLeast(0L)
                    if (incMinor > 0L) {
                        val ratio = ChartFormatters.ratioToFloat(incMinor, rawMaxMinor)
                        val barH = (usableHeight * ratio).coerceAtLeast(3.dp.toPx())
                        drawRoundRect(
                            color = if (idx == selectedIndex) semantic.income else semantic.income.copy(alpha = 0.78f),
                            topLeft = Offset(pairStart + barWidth + barPairGap, chartHeight - barH - 1.dp.toPx()),
                            size = Size(barWidth, barH),
                            cornerRadius = corner,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Sparsely sampled X-axis labels aligned under canvas
        val xLabels = remember(buckets) { sampleAxisLabels(buckets.map { it.label }, maxCount = 6) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 52.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            xLabels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun ChartLegendDot(
    color: Color,
    label: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun sampleAxisLabels(
    allLabels: List<String>,
    maxCount: Int,
): List<String> {
    if (allLabels.size <= maxCount) return allLabels
    val indices = linkedSetOf(0)
    val segments = maxCount - 1
    for (i in 1 until segments) {
        val idx = (i * (allLabels.lastIndex)).toDouble() / segments.toDouble()
        indices += idx.toInt().coerceIn(0, allLabels.lastIndex)
    }
    indices += allLabels.lastIndex
    return indices.sorted().map { allLabels[it] }
}
