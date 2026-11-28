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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kvtttba.droplet.core.domain.stats.NetWorthPoint
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.tabularNums

/**
 * Multi-series Net Worth / Total Assets / Total Liabilities line + soft area chart
 * with interactive legend toggles, tap-to-inspect node details, and full privacy masking.
 */
@Composable
fun NetWorthTrendChart(
    points: List<NetWorthPoint>,
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors
    val netWorthColor = MaterialTheme.colorScheme.primary
    val assetsColor = semantic.income
    val liabilitiesColor = semantic.expense
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val pointCenterColor = MaterialTheme.colorScheme.surfaceContainerLowest

    var showNetWorth by remember { mutableStateOf(true) }
    var showAssets by remember { mutableStateOf(true) }
    var showLiabilities by remember { mutableStateOf(true) }
    var selectedIndex by remember(points) { mutableIntStateOf(points.lastIndex.coerceAtLeast(0)) }

    Column(modifier = modifier.fillMaxWidth().testTag("chart_net_worth_trend")) {
        // Series visibility toggles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = showNetWorth,
                onClick = { if (showAssets || showLiabilities || !showNetWorth) showNetWorth = !showNetWorth },
                label = { ChartLegendDot(color = netWorthColor, label = "净资产") },
                modifier = Modifier.testTag("net_worth_toggle_net"),
            )
            FilterChip(
                selected = showAssets,
                onClick = { if (showNetWorth || showLiabilities || !showAssets) showAssets = !showAssets },
                label = { ChartLegendDot(color = assetsColor, label = "总资产") },
                modifier = Modifier.testTag("net_worth_toggle_assets"),
            )
            FilterChip(
                selected = showLiabilities,
                onClick = { if (showNetWorth || showAssets || !showLiabilities) showLiabilities = !showLiabilities },
                label = { ChartLegendDot(color = liabilitiesColor, label = "总负债") },
                modifier = Modifier.testTag("net_worth_toggle_liabilities"),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val hasData = remember(points) {
            points.any {
                !it.netWorth.isZero || !it.totalAssets.isZero || !it.totalLiabilities.isZero
            }
        }
        if (points.isEmpty() || !hasData) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(152.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无历史净资产走势数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        val activePoint = points.getOrNull(selectedIndex) ?: points.last()
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("net_worth_selected_banner"),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "节点 ${activePoint.asOfDate}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MetricMiniColumn("净资产", activePoint.netWorth, netWorthColor)
                    MetricMiniColumn("总资产", activePoint.totalAssets, assetsColor)
                    MetricMiniColumn("总负债", activePoint.totalLiabilities, liabilitiesColor)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val currency = points.first().netWorth.currency
        val visibleValues = remember(points, showNetWorth, showAssets, showLiabilities) {
            buildList {
                for (p in points) {
                    if (showNetWorth) add(p.netWorth.amountMinor)
                    if (showAssets) add(p.totalAssets.amountMinor)
                    if (showLiabilities) add(p.totalLiabilities.amountMinor)
                }
            }
        }
        val rawMin = (visibleValues.minOrNull() ?: 0L).coerceAtMost(0L)
        val rawMax = (visibleValues.maxOrNull() ?: Money.SCALE_FACTOR).let {
            if (it <= rawMin) rawMin + Money.SCALE_FACTOR else it
        }
        val midMinor = rawMin + (rawMax - rawMin) / 2L

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .width(46.dp)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = ChartFormatters.formatAxisTick(Money(rawMax, currency), amountsHidden),
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
                    text = ChartFormatters.formatAxisTick(Money(rawMin, currency), amountsHidden),
                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(points) {
                        detectTapGestures { offset ->
                            if (points.size > 1 && size.width > 0) {
                                val step = size.width.toFloat() / (points.size - 1)
                                selectedIndex = ((offset.x / step) + 0.5f).toInt().coerceIn(0, points.lastIndex)
                            }
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                val topPad = 8.dp.toPx()
                val bottomPad = 8.dp.toPx()
                val plotH = (h - topPad - bottomPad).coerceAtLeast(1f)
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                // Grid lines
                for (frac in listOf(0f, 0.5f, 1f)) {
                    val y = topPad + plotH * frac
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = if (frac < 1f) dash else null,
                    )
                }

                fun xFor(index: Int): Float = if (points.size <= 1) {
                    w / 2f
                } else {
                    w * index.toFloat() / (points.size - 1).toFloat()
                }

                fun yFor(minor: Long): Float {
                    val ratio = ChartFormatters.signedRatioToFloat(minor, rawMin, rawMax)
                    return topPad + plotH * (1f - ratio)
                }

                // Soft gradient area under Net Worth line
                if (showNetWorth && points.size >= 2) {
                    val areaPath = Path().apply {
                        moveTo(xFor(0), yFor(points[0].netWorth.amountMinor))
                        for (i in 1 until points.size) {
                            lineTo(xFor(i), yFor(points[i].netWorth.amountMinor))
                        }
                        lineTo(xFor(points.lastIndex), topPad + plotH)
                        lineTo(xFor(0), topPad + plotH)
                        close()
                    }
                    drawPath(
                        path = areaPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                netWorthColor.copy(alpha = 0.24f),
                                netWorthColor.copy(alpha = 0.02f),
                            ),
                            startY = topPad,
                            endY = topPad + plotH,
                        ),
                    )
                }

                // Draw series lines
                if (showAssets) {
                    drawSeriesLine(
                        points = points.mapIndexed { i, p -> Offset(xFor(i), yFor(p.totalAssets.amountMinor)) },
                        color = assetsColor,
                        strokeWidthPx = 2.dp.toPx(),
                        pathEffect = null,
                    )
                }
                if (showLiabilities) {
                    drawSeriesLine(
                        points = points.mapIndexed { i, p -> Offset(xFor(i), yFor(p.totalLiabilities.amountMinor)) },
                        color = liabilitiesColor,
                        strokeWidthPx = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f),
                    )
                }
                if (showNetWorth) {
                    drawSeriesLine(
                        points = points.mapIndexed { i, p -> Offset(xFor(i), yFor(p.netWorth.amountMinor)) },
                        color = netWorthColor,
                        strokeWidthPx = 3.dp.toPx(),
                        pathEffect = null,
                    )
                }

                // Selected node indicator line & dots
                val selIdx = selectedIndex.coerceIn(0, points.lastIndex)
                val selX = xFor(selIdx)
                drawLine(
                    color = netWorthColor.copy(alpha = 0.35f),
                    start = Offset(selX, topPad),
                    end = Offset(selX, topPad + plotH),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )

                val p = points[selIdx]
                if (showAssets) {
                    val pt = Offset(selX, yFor(p.totalAssets.amountMinor))
                    drawCircle(color = assetsColor, radius = 4.dp.toPx(), center = pt)
                    drawCircle(color = pointCenterColor, radius = 2.dp.toPx(), center = pt)
                }
                if (showLiabilities) {
                    val pt = Offset(selX, yFor(p.totalLiabilities.amountMinor))
                    drawCircle(color = liabilitiesColor, radius = 4.dp.toPx(), center = pt)
                    drawCircle(color = pointCenterColor, radius = 2.dp.toPx(), center = pt)
                }
                if (showNetWorth) {
                    val pt = Offset(selX, yFor(p.netWorth.amountMinor))
                    drawCircle(color = netWorthColor.copy(alpha = 0.25f), radius = 8.dp.toPx(), center = pt)
                    drawCircle(color = netWorthColor, radius = 4.5.dp.toPx(), center = pt)
                    drawCircle(color = pointCenterColor, radius = 2.dp.toPx(), center = pt)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        val xLabels = remember(points) { sampleAxisLabels(points.map { it.label }, maxCount = 5) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 54.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            xLabels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeriesLine(
    points: List<Offset>,
    color: Color,
    strokeWidthPx: Float,
    pathEffect: PathEffect?,
) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color = color, radius = strokeWidthPx * 1.5f, center = points[0])
        return
    }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            lineTo(points[i].x, points[i].y)
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = strokeWidthPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = pathEffect,
        ),
    )
}

@Composable
private fun MetricMiniColumn(
    label: String,
    money: Money,
    accentColor: Color,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accentColor,
        )
        PrivacyAmountText(
            money = money,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
