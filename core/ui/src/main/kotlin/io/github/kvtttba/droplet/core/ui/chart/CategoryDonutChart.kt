package io.github.kvtttba.droplet.core.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kvtttba.droplet.core.domain.stats.CategoryRankingItem
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.icon.DropletIconRegistry
import io.github.kvtttba.droplet.core.ui.theme.tabularNums

/**
 * Smooth Material 3 donut ring chart with center total (privacy-masked when amounts are hidden)
 * and top category legend chips alongside the ring.
 */
@Composable
fun CategoryDonutChart(
    rankings: List<CategoryRankingItem>,
    centerLabel: String,
    centerTotal: Money,
    selectedCategoryId: String? = null,
    onSelectCategory: (CategoryRankingItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    if (rankings.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .testTag("chart_category_donut_empty"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "当前筛选下暂无分类占比数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("chart_category_donut"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.size(152.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(144.dp)) {
                val baseStrokeWidth = 20.dp.toPx()
                val highlightStrokeWidth = 24.dp.toPx()
                val inset = highlightStrokeWidth / 2f + 2.dp.toPx()
                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                val topLeft = Offset(inset, inset)

                // Soft track
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = baseStrokeWidth),
                )

                val gapAngle = if (rankings.size > 1) 2.5f else 0f
                var currentStart = -90f
                rankings.forEachIndexed { idx, item ->
                    val rawSweep = (item.shareFraction * 360f).coerceAtLeast(0f)
                    val sweep = (rawSweep - gapAngle).coerceAtLeast(if (rawSweep > 0f) 2f else 0f)
                    val sliceColor = DropletIconRegistry.parseColor(
                        hex = item.colorHex,
                        fallback = ChartFormatters.fallbackChartPalette[idx % ChartFormatters.fallbackChartPalette.size],
                    )
                    val isSelected = selectedCategoryId == item.categoryId
                    drawArc(
                        color = if (selectedCategoryId == null || isSelected) sliceColor else sliceColor.copy(alpha = 0.4f),
                        startAngle = currentStart + gapAngle / 2f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(
                            width = if (isSelected) highlightStrokeWidth else baseStrokeWidth,
                            cap = StrokeCap.Butt,
                        ),
                    )
                    currentStart += rawSweep
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Text(
                    text = centerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(2.dp))
                PrivacyAmountText(
                    money = centerTotal,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Right-hand compact top-5 legend
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rankings.take(5).forEachIndexed { idx, item ->
                val sliceColor = DropletIconRegistry.parseColor(
                    hex = item.colorHex,
                    fallback = ChartFormatters.fallbackChartPalette[idx % ChartFormatters.fallbackChartPalette.size],
                )
                val isSelected = selectedCategoryId == item.categoryId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            },
                        )
                        .clickable { onSelectCategory(item) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(sliceColor),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${item.sharePctDecimal}%",
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (rankings.size > 5) {
                Text(
                    text = "其余 ${rankings.size - 5} 项见下方排行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
