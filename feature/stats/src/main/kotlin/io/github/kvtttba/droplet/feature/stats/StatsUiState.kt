package io.github.kvtttba.droplet.feature.stats

import io.github.kvtttba.droplet.core.domain.automation.BudgetProgress
import io.github.kvtttba.droplet.core.domain.stats.CalendarDayCell
import io.github.kvtttba.droplet.core.domain.stats.CategoryRankingItem
import io.github.kvtttba.droplet.core.domain.stats.StatsCategoryDirection
import io.github.kvtttba.droplet.core.domain.stats.StatsPeriodType
import io.github.kvtttba.droplet.core.domain.stats.StatsQuery
import io.github.kvtttba.droplet.core.domain.stats.StatsReportSnapshot
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Tag
import java.time.LocalDate

enum class StatsDisplayTab(val label: String) {
    OVERVIEW("报表概览"),
    CALENDAR("收支日历"),
}

data class StatsUiState(
    val isLoading: Boolean = true,
    val displayTab: StatsDisplayTab = StatsDisplayTab.OVERVIEW,
    val query: StatsQuery = StatsQuery(),
    val categoryDirection: StatsCategoryDirection = StatsCategoryDirection.EXPENSE,
    val report: StatsReportSnapshot? = null,
    val availableTags: List<Tag> = emptyList(),
    val monthlyBudgetProgress: BudgetProgress? = null,
    val yearlyBudgetProgress: BudgetProgress? = null,
    val selectedCalendarDate: LocalDate? = null,
    val drilldownCategoryId: String? = null,
    val showTagFilterSheet: Boolean = false,
    val showCustomRangeDialog: Boolean = false,
) {
    val activeRankings: List<CategoryRankingItem>
        get() = when (categoryDirection) {
            StatsCategoryDirection.EXPENSE -> report?.expenseRankings.orEmpty()
            StatsCategoryDirection.INCOME -> report?.incomeRankings.orEmpty()
        }

    val activeCategoryTotal: Money
        get() {
            val currency = query.currencyFilter.displayCurrency
            return when (categoryDirection) {
                StatsCategoryDirection.EXPENSE -> report?.totalExpense ?: Money.zero(currency)
                StatsCategoryDirection.INCOME -> report?.totalIncome ?: Money.zero(currency)
            }
        }

    val drilldownCategory: CategoryRankingItem?
        get() = drilldownCategoryId?.let { id ->
            activeRankings.firstOrNull { it.categoryId == id }
        }

    val activeBudgetProgress: BudgetProgress?
        get() = when (query.periodType) {
            StatsPeriodType.YEARLY -> yearlyBudgetProgress ?: monthlyBudgetProgress
            StatsPeriodType.MONTHLY,
            StatsPeriodType.CUSTOM_RANGE,
            -> monthlyBudgetProgress
        }

    val resolvedSelectedCalendarDate: LocalDate?
        get() {
            val cal = report?.calendarMonth ?: return selectedCalendarDate
            val explicit = selectedCalendarDate
            if (explicit != null && explicit.year == cal.month.year && explicit.monthValue == cal.month.monthValue) {
                return explicit
            }
            return cal.days.lastOrNull { it.hasActivity }?.date ?: cal.days.firstOrNull()?.date
        }

    val selectedCalendarDayCell: CalendarDayCell?
        get() {
            val date = resolvedSelectedCalendarDate ?: return null
            return report?.calendarMonth?.days?.firstOrNull { it.date == date }
        }

    val periodHeadlineLabel: String
        get() = when (query.periodType) {
            StatsPeriodType.MONTHLY -> "${query.targetMonth.year}年${query.targetMonth.monthValue}月"
            StatsPeriodType.YEARLY -> "${query.targetYear}年"
            StatsPeriodType.CUSTOM_RANGE -> "${query.customStartDate} ~ ${query.customEndDate}"
        }

    val calendarMonthHeadlineLabel: String
        get() = "${query.calendarMonth.year}年${query.calendarMonth.monthValue}月"

    val selectedTagSummary: String
        get() {
            if (query.selectedTagIds.isEmpty()) return "#全部标签"
            val names = availableTags
                .filter { it.id in query.selectedTagIds }
                .map { it.name }
            val modePrefix = if (query.selectedTagIds.size > 1) "${query.tagFilterMode.label}·" else ""
            return when {
                names.isEmpty() -> "${modePrefix}已选 ${query.selectedTagIds.size} 标签"
                names.size == 1 -> "#${names.first()}"
                else -> "$modePrefix#${names.first()} 等${names.size}个"
            }
        }
}
