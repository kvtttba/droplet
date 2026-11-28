package io.github.kvtttba.droplet.core.ui.chart

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kvtttba.droplet.core.domain.stats.CalendarDayCell
import io.github.kvtttba.droplet.core.domain.stats.CalendarMonthSummary
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.tabularNums
import java.time.LocalDate

private val WEEKDAY_HEADERS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 7-column monthly income/expense calendar grid.
 * Displays compact daily income/expense amounts (or privacy-safe colored dots when
 * [LocalAmountsHidden] is active) and highlights the selected day for drill-down inspection.
 */
@Composable
fun IncomeExpenseCalendarGrid(
    summary: CalendarMonthSummary,
    selectedDate: LocalDate?,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("calendar_month_grid"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Weekday header row (Mon..Sun)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            WEEKDAY_HEADERS.forEach { dayName ->
                Text(
                    text = dayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Build padded matrix of 7-column weeks
        val leadingBlanks = (summary.firstDayOfWeekIso - 1).coerceIn(0, 6)
        val totalSlots = leadingBlanks + summary.days.size
        val weekCount = (totalSlots + 6) / 7

        for (week in 0 until weekCount) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (col in 0 until 7) {
                    val slotIndex = week * 7 + col
                    val dayIndex = slotIndex - leadingBlanks
                    val cell = summary.days.getOrNull(dayIndex)
                    if (cell == null) {
                        Spacer(modifier = Modifier.weight(1f).height(46.dp))
                    } else {
                        CalendarDayCellTile(
                            cell = cell,
                            selected = selectedDate == cell.date,
                            amountsHidden = amountsHidden,
                            incomeColor = semantic.income,
                            expenseColor = semantic.expense,
                            onClick = { onSelectDate(cell.date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCellTile(
    cell: CalendarDayCell,
    selected: Boolean,
    amountsHidden: Boolean,
    incomeColor: Color,
    expenseColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
        cell.hasActivity -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> Color.Transparent
    }
    val border = if (selected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }

    Surface(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("calendar_day_${cell.dayOfMonth}"),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = border,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = cell.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected || cell.hasActivity) FontWeight.SemiBold else FontWeight.Normal,
                ).tabularNums(),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )

            if (amountsHidden) {
                // Privacy-safe activity indicator dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(18.dp),
                ) {
                    if (cell.expense.isPositive) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(expenseColor),
                        )
                    }
                    if (cell.income.isPositive) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(incomeColor),
                        )
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.height(21.dp),
                ) {
                    if (cell.income.isPositive) {
                        Text(
                            text = ChartFormatters.formatCalendarMicroAmount(cell.income, "+"),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                lineHeight = 11.sp,
                            ).tabularNums(),
                            color = incomeColor,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                    if (cell.expense.isPositive) {
                        Text(
                            text = ChartFormatters.formatCalendarMicroAmount(cell.expense, "-"),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                lineHeight = 11.sp,
                            ).tabularNums(),
                            color = expenseColor,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
    }
}
