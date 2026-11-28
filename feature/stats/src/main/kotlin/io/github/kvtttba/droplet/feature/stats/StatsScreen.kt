package io.github.kvtttba.droplet.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.domain.automation.BudgetAlertLevel
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgress
import io.github.kvtttba.droplet.core.domain.stats.CategoryRankingItem
import io.github.kvtttba.droplet.core.domain.stats.StatsCategoryDirection
import io.github.kvtttba.droplet.core.domain.stats.StatsCurrencyFilter
import io.github.kvtttba.droplet.core.domain.stats.StatsDrilldownTransaction
import io.github.kvtttba.droplet.core.domain.stats.StatsPeriodType
import io.github.kvtttba.droplet.core.domain.stats.StatsTagFilterMode
import io.github.kvtttba.droplet.core.domain.stats.StatsTrendGranularity
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.ui.chart.CategoryDonutChart
import io.github.kvtttba.droplet.core.ui.chart.IncomeExpenseCalendarGrid
import io.github.kvtttba.droplet.core.ui.chart.IncomeExpenseTrendChart
import io.github.kvtttba.droplet.core.ui.chart.NetWorthTrendChart
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun StatsRoute(
    onToggleAmountsHidden: () -> Unit,
    onOpenBudgetManagement: () -> Unit,
    onOpenDataExport: () -> Unit,
    onEditTransaction: (String) -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    StatsScreen(
        uiState = uiState,
        onToggleAmountsHidden = onToggleAmountsHidden,
        onOpenBudgetManagement = onOpenBudgetManagement,
        onOpenDataExport = onOpenDataExport,
        onEditTransaction = onEditTransaction,
        onSelectDisplayTab = viewModel::selectDisplayTab,
        onSelectPeriodType = viewModel::selectPeriodType,
        onPreviousPeriod = viewModel::navigatePreviousPeriod,
        onNextPeriod = viewModel::navigateNextPeriod,
        onOpenCustomRangeDialog = { viewModel.setShowCustomRangeDialog(true) },
        onDismissCustomRangeDialog = { viewModel.setShowCustomRangeDialog(false) },
        onApplyCustomRange = viewModel::applyCustomDateRange,
        onSelectCurrencyFilter = viewModel::selectCurrencyFilter,
        onOpenTagFilter = { viewModel.setShowTagFilterSheet(true) },
        onDismissTagFilter = { viewModel.setShowTagFilterSheet(false) },
        onToggleTag = viewModel::toggleTag,
        onSelectTagFilterMode = viewModel::setTagFilterMode,
        onClearTags = viewModel::clearTagFilter,
        onSelectCategoryDirection = viewModel::selectCategoryDirection,
        onOpenCategoryDrilldown = viewModel::openCategoryDrilldown,
        onDismissCategoryDrilldown = viewModel::dismissCategoryDrilldown,
        onPreviousCalendarMonth = viewModel::navigatePreviousCalendarMonth,
        onNextCalendarMonth = viewModel::navigateNextCalendarMonth,
        onSelectCalendarDate = viewModel::selectCalendarDate,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    uiState: StatsUiState,
    onToggleAmountsHidden: () -> Unit = {},
    onOpenBudgetManagement: () -> Unit = {},
    onOpenDataExport: () -> Unit = {},
    onEditTransaction: (String) -> Unit = {},
    onSelectDisplayTab: (StatsDisplayTab) -> Unit = {},
    onSelectPeriodType: (StatsPeriodType) -> Unit = {},
    onPreviousPeriod: () -> Unit = {},
    onNextPeriod: () -> Unit = {},
    onOpenCustomRangeDialog: () -> Unit = {},
    onDismissCustomRangeDialog: () -> Unit = {},
    onApplyCustomRange: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    onSelectCurrencyFilter: (StatsCurrencyFilter) -> Unit = {},
    onOpenTagFilter: () -> Unit = {},
    onDismissTagFilter: () -> Unit = {},
    onToggleTag: (String) -> Unit = {},
    onSelectTagFilterMode: (StatsTagFilterMode) -> Unit = {},
    onClearTags: () -> Unit = {},
    onSelectCategoryDirection: (StatsCategoryDirection) -> Unit = {},
    onOpenCategoryDrilldown: (String) -> Unit = {},
    onDismissCategoryDrilldown: () -> Unit = {},
    onPreviousCalendarMonth: () -> Unit = {},
    onNextCalendarMonth: () -> Unit = {},
    onSelectCalendarDate: (LocalDate) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current
    val semantic = DropletTheme.semanticColors
    val report = uiState.report
    val currency = uiState.query.currencyFilter.displayCurrency

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_stats"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header
        item(key = "stats_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "统计分析",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (uiState.displayTab == StatsDisplayTab.OVERVIEW) {
                            "${uiState.periodHeadlineLabel} · ${uiState.query.currencyFilter.label}"
                        } else {
                            "${uiState.calendarMonthHeadlineLabel} · 收支日历"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenDataExport,
                        modifier = Modifier.testTag("stats_export_button"),
                    ) {
                        Icon(
                            imageVector = DropletIcons.FileDownload,
                            contentDescription = "导出账本数据",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    PrivacyToggleIconButton(
                        amountsHidden = amountsHidden,
                        onToggle = onToggleAmountsHidden,
                    )
                }
            }
        }

        // Top view toggle: 报表概览 / 收支日历
        item(key = "stats_mode_tabs") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                StatsDisplayTab.entries.forEachIndexed { idx, tab ->
                    SegmentedButton(
                        selected = uiState.displayTab == tab,
                        onClick = { onSelectDisplayTab(tab) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = idx,
                            count = StatsDisplayTab.entries.size,
                        ),
                        icon = {
                            Icon(
                                imageVector = if (tab == StatsDisplayTab.OVERVIEW) {
                                    DropletIcons.BarChart
                                } else {
                                    DropletIcons.CalendarMonth
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        modifier = Modifier.testTag("stats_tab_${tab.name.lowercase()}"),
                    ) {
                        Text(tab.label)
                    }
                }
            }
        }

        // Loading indicator when initial load
        if (uiState.isLoading && report == null) {
            item(key = "stats_loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (uiState.displayTab == StatsDisplayTab.OVERVIEW) {
            // Period mode + navigation bar
            item(key = "stats_period_controls") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatsPeriodType.entries.forEach { period ->
                            FilterChip(
                                selected = uiState.query.periodType == period,
                                onClick = { onSelectPeriodType(period) },
                                label = { Text(period.label) },
                                modifier = Modifier.testTag("stats_period_${period.name}"),
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        FilterChip(
                            selected = uiState.query.selectedTagIds.isNotEmpty(),
                            onClick = onOpenTagFilter,
                            label = {
                                Text(
                                    text = uiState.selectedTagSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = DropletIcons.Label,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            modifier = Modifier.testTag("stats_tag_filter_button"),
                        )
                    }

                    // Period stepper bar
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = onPreviousPeriod,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("stats_period_prev"),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                                    contentDescription = "上一周期",
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .then(
                                        if (uiState.query.periodType == StatsPeriodType.CUSTOM_RANGE) {
                                            Modifier.clickable(onClick = onOpenCustomRangeDialog)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = uiState.periodHeadlineLabel,
                                    style = MaterialTheme.typography.titleSmall.tabularNums(),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("stats_period_label"),
                                )
                                if (uiState.query.periodType == StatsPeriodType.CUSTOM_RANGE) {
                                    DropletPillBadge(text = "修改")
                                }
                            }
                            IconButton(
                                onClick = onNextPeriod,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("stats_period_next"),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = "下一周期",
                                )
                            }
                        }
                    }

                    // Currency scope strip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatsCurrencyFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = uiState.query.currencyFilter == filter,
                                onClick = { onSelectCurrencyFilter(filter) },
                                label = { Text(filter.label) },
                                modifier = Modifier.testTag("stats_currency_${filter.name}"),
                            )
                        }
                    }
                }
            }

            // Hero Summary Card
            item(key = "stats_hero") {
                val totalExpense = report?.totalExpense ?: Money.zero(currency)
                val totalIncome = report?.totalIncome ?: Money.zero(currency)
                val netBalance = report?.netBalance ?: Money.zero(currency)
                val dailyAvg = report?.dailyAverageExpense ?: Money.zero(currency)
                val txCount = report?.transactionCount ?: 0

                DropletHeroCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "净结余（已扣退款 · 不含待报销）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                        DropletPillBadge(
                            text = "$txCount 笔收支",
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    PrivacyAmountText(
                        money = netBalance,
                        showPositiveSign = true,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.testTag("stats_net_balance"),
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        HeroMetricItem(
                            label = "净支出",
                            money = totalExpense,
                            valueTag = "stats_total_expense",
                            modifier = Modifier.weight(1f),
                        )
                        HeroMetricItem(
                            label = "总收入",
                            money = totalIncome,
                            valueTag = "stats_total_income",
                            modifier = Modifier.weight(1f),
                        )
                        HeroMetricItem(
                            label = "日均支出",
                            money = dailyAvg,
                            valueTag = "stats_daily_avg_expense",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Income / Expense Trend Chart Card
            item(key = "stats_trend_card") {
                val granularityHint = when (report?.granularity) {
                    StatsTrendGranularity.MONTHLY -> "按月柱状对比"
                    else -> "按日柱状对比"
                }
                DropletCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "收支趋势",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        DropletPillBadge(text = granularityHint)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    IncomeExpenseTrendChart(
                        buckets = report?.trendBuckets.orEmpty(),
                    )
                }
            }

            // Budget progress card
            item(key = "stats_budget") {
                StatsBudgetProgressCard(
                    budget = uiState.activeBudgetProgress,
                    onOpenBudgetManagement = onOpenBudgetManagement,
                )
            }

            // Category Donut + Ranking Card
            item(key = "stats_category_card") {
                DropletCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "分类占比与排行",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "点击任意大类下钻二级分类与具体流水",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatsCategoryDirection.entries.forEach { dir ->
                                FilterChip(
                                    selected = uiState.categoryDirection == dir,
                                    onClick = { onSelectCategoryDirection(dir) },
                                    label = { Text(dir.label) },
                                    modifier = Modifier.testTag("stats_direction_${dir.name}"),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    CategoryDonutChart(
                        rankings = uiState.activeRankings,
                        centerLabel = if (uiState.categoryDirection == StatsCategoryDirection.EXPENSE) {
                            "总净支出"
                        } else {
                            "总收入"
                        },
                        centerTotal = uiState.activeCategoryTotal,
                        selectedCategoryId = uiState.drilldownCategoryId,
                        onSelectCategory = { onOpenCategoryDrilldown(it.categoryId) },
                    )

                    if (uiState.activeRankings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        Spacer(modifier = Modifier.height(8.dp))

                        uiState.activeRankings.forEachIndexed { idx, item ->
                            CategoryRankingRow(
                                rank = idx + 1,
                                item = item,
                                accentColor = DropletIconRegistry.parseColor(
                                    hex = item.colorHex,
                                    fallback = if (uiState.categoryDirection == StatsCategoryDirection.EXPENSE) {
                                        semantic.expense
                                    } else {
                                        semantic.income
                                    },
                                ),
                                onClick = { onOpenCategoryDrilldown(item.categoryId) },
                            )
                            if (idx < uiState.activeRankings.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 48.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                    }
                }
            }

            // Net Worth / Assets / Liabilities Trend Chart Card
            item(key = "stats_net_worth_card") {
                DropletCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "净资产与资产负债走势",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "严格尊重「计入总资产」开关与历史复式分录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    NetWorthTrendChart(
                        points = report?.netWorthTrend.orEmpty(),
                    )
                }
            }
        } else {
            // === CALENDAR TAB ===
            item(key = "stats_calendar_filters") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = uiState.query.selectedTagIds.isNotEmpty(),
                        onClick = onOpenTagFilter,
                        label = { Text(uiState.selectedTagSummary) },
                        leadingIcon = {
                            Icon(
                                imageVector = DropletIcons.Label,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        modifier = Modifier.testTag("stats_calendar_tag_filter"),
                    )
                    StatsCurrencyFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = uiState.query.currencyFilter == filter,
                            onClick = { onSelectCurrencyFilter(filter) },
                            label = { Text(filter.shortLabel) },
                        )
                    }
                }
            }

            item(key = "stats_calendar_card") {
                val cal = report?.calendarMonth
                DropletCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onPreviousCalendarMonth,
                            modifier = Modifier.testTag("calendar_prev_month"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                                contentDescription = "上个月",
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uiState.calendarMonthHeadlineLabel,
                                style = MaterialTheme.typography.titleMedium.tabularNums(),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("calendar_month_title"),
                            )
                            if (cal != null) {
                                Text(
                                    text = "本月 ${cal.activeDayCount} 天有收支记录",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(
                            onClick = onNextCalendarMonth,
                            modifier = Modifier.testTag("calendar_next_month"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = "下个月",
                            )
                        }
                    }

                    if (cal != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                CalendarMonthStatColumn("月支出", cal.totalExpense, semantic.expense)
                                CalendarMonthStatColumn("月收入", cal.totalIncome, semantic.income)
                                CalendarMonthStatColumn("月结余", cal.netFlow, MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        IncomeExpenseCalendarGrid(
                            summary = cal,
                            selectedDate = uiState.resolvedSelectedCalendarDate,
                            onSelectDate = onSelectCalendarDate,
                        )
                    }
                }
            }

            // Selected day transactions drill-down card
            item(key = "stats_calendar_day_detail") {
                val selectedCell = uiState.selectedCalendarDayCell
                DropletCard(modifier = Modifier.testTag("stats_calendar_selected_day_card")) {
                    if (selectedCell == null) {
                        Text(
                            text = "请选择日期查看当日明细",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = "${selectedCell.date.monthValue}月${selectedCell.date.dayOfMonth}日明细",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("calendar_selected_date_header"),
                                )
                                Text(
                                    text = "${selectedCell.transactionCount} 笔收支流水",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (selectedCell.expense.isPositive) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "支出",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = semantic.expense,
                                        )
                                        PrivacyAmountText(
                                            money = selectedCell.expense,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = semantic.expense,
                                        )
                                    }
                                }
                                if (selectedCell.income.isPositive) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "收入",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = semantic.income,
                                        )
                                        PrivacyAmountText(
                                            money = selectedCell.income,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = semantic.income,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                        if (selectedCell.transactions.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "当日暂无收支流水",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            selectedCell.transactions.forEachIndexed { idx, tx ->
                                DrilldownTransactionRow(
                                    item = tx,
                                    onClick = { onEditTransaction(tx.transactionId) },
                                )
                                if (idx < selectedCell.transactions.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 46.dp),
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

    // Mutually exclusive sheets / dialogs (never stack two ModalBottomSheets simultaneously)
    val drilldownCategory = uiState.drilldownCategory
    if (drilldownCategory != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismissCategoryDrilldown,
            sheetState = sheetState,
            modifier = Modifier.testTag("stats_category_drilldown_sheet"),
        ) {
            CategoryDrilldownSheetContent(
                item = drilldownCategory,
                direction = uiState.categoryDirection,
                onEditTransaction = { txId ->
                    onDismissCategoryDrilldown()
                    onEditTransaction(txId)
                },
                onClose = onDismissCategoryDrilldown,
            )
        }
    } else if (uiState.showTagFilterSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismissTagFilter,
            sheetState = sheetState,
            modifier = Modifier.testTag("stats_tag_filter_sheet"),
        ) {
            StatsTagFilterSheetContent(
                availableTags = uiState.availableTags,
                selectedTagIds = uiState.query.selectedTagIds,
                tagFilterMode = uiState.query.tagFilterMode,
                onToggleTag = onToggleTag,
                onSelectMode = onSelectTagFilterMode,
                onClearAll = onClearTags,
                onDone = onDismissTagFilter,
            )
        }
    }

    if (uiState.showCustomRangeDialog) {
        CustomDateRangeDialog(
            initialStartDate = uiState.query.customStartDate,
            initialEndDate = uiState.query.customEndDate,
            onDismiss = onDismissCustomRangeDialog,
            onConfirm = onApplyCustomRange,
        )
    }
}

@Composable
private fun HeroMetricItem(
    label: String,
    money: Money,
    valueTag: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
        )
        Spacer(modifier = Modifier.height(2.dp))
        PrivacyAmountText(
            money = money,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.testTag(valueTag),
        )
    }
}

@Composable
private fun CalendarMonthStatColumn(
    label: String,
    money: Money,
    accentColor: androidx.compose.ui.graphics.Color,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accentColor,
        )
        PrivacyAmountText(
            money = money,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatsBudgetProgressCard(
    budget: BudgetProgress?,
    onOpenBudgetManagement: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors

    DropletCard(modifier = Modifier.testTag("stats_budget_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = budget?.displayTitle ?: "当期总预算",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (budget != null) {
                    val badgeText = when (budget.alertLevel) {
                        BudgetAlertLevel.EXCEEDED -> "已超支 ${budget.usedPercentageInt}%"
                        BudgetAlertLevel.WARNING -> "预警 ${budget.usedPercentageInt}%"
                        BudgetAlertLevel.NORMAL -> "已用 ${budget.usedPercentageInt}%"
                    }
                    val badgeBg = when (budget.alertLevel) {
                        BudgetAlertLevel.EXCEEDED -> semantic.expenseContainer
                        BudgetAlertLevel.WARNING -> semantic.warningContainer
                        BudgetAlertLevel.NORMAL -> semantic.incomeContainer
                    }
                    val badgeFg = when (budget.alertLevel) {
                        BudgetAlertLevel.EXCEEDED -> semantic.expense
                        BudgetAlertLevel.WARNING -> semantic.warning
                        BudgetAlertLevel.NORMAL -> semantic.income
                    }
                    DropletPillBadge(
                        text = badgeText,
                        containerColor = badgeBg,
                        contentColor = badgeFg,
                    )
                }
                TextButton(
                    onClick = onOpenBudgetManagement,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("stats_budget_manage_button"),
                ) {
                    Text(if (budget == null) "去设置" else "管理")
                }
            }
        }

        if (budget == null) {
            Text(
                text = "暂未配置月度或年度总预算，设置后可在此实时跟踪消耗进度与日均可用额度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val barColor = when (budget.alertLevel) {
                BudgetAlertLevel.EXCEEDED -> semantic.expense
                BudgetAlertLevel.WARNING -> semantic.warning
                BudgetAlertLevel.NORMAL -> MaterialTheme.colorScheme.primary
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                PrivacyAmountText(
                    money = budget.spentAmount,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("stats_budget_spent"),
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
                        money = budget.budget.limitAmount,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("stats_budget_limit"),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { budget.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "剩余 ${budget.periodRange.remainingDays} 天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "日均可用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrivacyAmountText(
                        money = budget.dailyAvailableAmount,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRankingRow(
    rank: Int,
    item: CategoryRankingItem,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 4.dp)
            .testTag("stats_category_row_${item.name}"),
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
                imageVector = DropletIconRegistry.resolve(item.iconKey),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "$rank. ${item.name}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${item.sharePctDecimal}%",
                        style = MaterialTheme.typography.labelSmall.tabularNums(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrivacyAmountText(
                    money = item.amount,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(
                    progress = { item.shareFraction.coerceIn(0.02f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                )
                Text(
                    text = "${item.transactionCount}笔",
                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = "查看分类明细",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CategoryDrilldownSheetContent(
    item: CategoryRankingItem,
    direction: StatsCategoryDirection,
    onEditTransaction: (String) -> Unit,
    onClose: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val accentColor = DropletIconRegistry.parseColor(
        hex = item.colorHex,
        fallback = if (direction == StatsCategoryDirection.EXPENSE) semantic.expense else semantic.income,
    )

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = DropletIconRegistry.resolve(item.iconKey),
                            contentDescription = null,
                            tint = accentColor,
                        )
                    }
                    Column {
                        Text(
                            text = "${item.name} · ${direction.label}下钻",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("stats_drilldown_title"),
                        )
                        Text(
                            text = "占总${direction.label} ${item.sharePctDecimal}% · 共 ${item.transactionCount} 笔",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                PrivacyAmountText(
                    money = item.amount,
                    style = MaterialTheme.typography.titleLarge,
                    color = accentColor,
                )
            }
        }

        if (item.subCategories.isNotEmpty()) {
            item {
                DropletSectionHeader(title = "二级分类分布")
            }
            item {
                DropletCard(contentPadding = PaddingValues(14.dp)) {
                    item.subCategories.forEachIndexed { idx, sub ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${sub.name} (${sub.shareOfParentPctDecimal}% · ${sub.transactionCount}笔)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            PrivacyAmountText(
                                money = sub.amount,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (idx < item.subCategories.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                        }
                    }
                }
            }
        }

        item {
            DropletSectionHeader(
                title = "关联流水明细",
                trailing = "${item.transactions.size} 笔",
            )
        }

        item {
            DropletCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                item.transactions.forEachIndexed { idx, tx ->
                    DrilldownTransactionRow(
                        item = tx,
                        onClick = { onEditTransaction(tx.transactionId) },
                    )
                    if (idx < item.transactions.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 46.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("stats_drilldown_close"),
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
private fun DrilldownTransactionRow(
    item: StatsDrilldownTransaction,
    onClick: () -> Unit,
) {
    val semantic = DropletTheme.semanticColors
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
    val timeText = remember(item.occurredAt) {
        dateFormatter.format(Instant.ofEpochMilli(item.occurredAt).atZone(ZoneId.systemDefault()))
    }
    val accent = DropletIconRegistry.parseColor(
        hex = item.colorHex,
        fallback = if (item.isExpense) semantic.expense else semantic.income,
    )
    val amountColor = when {
        item.isRefund -> semantic.income
        item.signedFlowAmount.isNegative -> semantic.expense
        else -> semantic.income
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag("stats_drilldown_tx_${item.transactionId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DropletIconRegistry.resolve(item.iconKey),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitleParts = buildList {
                add(timeText)
                add(item.accountName)
                if (item.tagNames.isNotEmpty()) {
                    add(item.tagNames.joinToString(" ") { "#$it" })
                }
            }
            Text(
                text = subtitleParts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        PrivacyAmountText(
            money = item.signedFlowAmount,
            showPositiveSign = true,
            style = MaterialTheme.typography.titleSmall,
            color = amountColor,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsTagFilterSheetContent(
    availableTags: List<Tag>,
    selectedTagIds: Set<String>,
    tagFilterMode: StatsTagFilterMode,
    onToggleTag: (String) -> Unit,
    onSelectMode: (StatsTagFilterMode) -> Unit,
    onClearAll: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "按标签筛选统计",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onClearAll) {
                Text("清空已选")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsTagFilterMode.entries.forEach { mode ->
                FilterChip(
                    selected = tagFilterMode == mode,
                    onClick = { onSelectMode(mode) },
                    label = { Text(mode.label) },
                    modifier = Modifier.testTag("stats_tag_mode_${mode.name}"),
                )
            }
        }

        if (availableTags.isEmpty()) {
            Text(
                text = "暂无可用标签，可在「我的 → 二级分类与标签管理」中添加。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                availableTags.forEach { tag ->
                    FilterChip(
                        selected = tag.id in selectedTagIds,
                        onClick = { onToggleTag(tag.id) },
                        label = { Text("#${tag.name}") },
                        modifier = Modifier.testTag("stats_tag_option_${tag.name}"),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onDone,
                modifier = Modifier.testTag("stats_tag_filter_done"),
            ) {
                Text("完成")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomDateRangeDialog(
    initialStartDate: LocalDate,
    initialEndDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    var startText by remember(initialStartDate) { mutableStateOf(initialStartDate.toString()) }
    var endText by remember(initialEndDate) { mutableStateOf(initialEndDate.toString()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val today = remember { LocalDate.now() }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("stats_custom_range_dialog"),
        title = { Text("自定义统计区间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            startText = today.minusDays(6).toString()
                            endText = today.toString()
                            errorText = null
                        },
                        label = { Text("近7天") },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            startText = today.minusDays(29).toString()
                            endText = today.toString()
                            errorText = null
                        },
                        label = { Text("近30天") },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            startText = today.minusDays(89).toString()
                            endText = today.toString()
                            errorText = null
                        },
                        label = { Text("近90天") },
                    )
                }
                OutlinedTextField(
                    value = startText,
                    onValueChange = {
                        startText = it
                        errorText = null
                    },
                    label = { Text("开始日期 (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stats_custom_start_input"),
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = {
                        endText = it
                        errorText = null
                    },
                    label = { Text("结束日期 (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stats_custom_end_input"),
                )
                if (errorText != null) {
                    Text(
                        text = errorText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val s = runCatching { LocalDate.parse(startText.trim()) }.getOrNull()
                    val e = runCatching { LocalDate.parse(endText.trim()) }.getOrNull()
                    when {
                        s == null || e == null -> errorText = "请按 YYYY-MM-DD 格式输入有效日期"
                        e.isBefore(s) -> errorText = "结束日期不能早于开始日期"
                        else -> onConfirm(s, e)
                    }
                },
                modifier = Modifier.testTag("stats_custom_range_confirm"),
            ) {
                Text("应用区间")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
