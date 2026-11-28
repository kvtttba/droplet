package io.github.kvtttba.droplet.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.StatsRepository
import io.github.kvtttba.droplet.core.domain.stats.StatsCategoryDirection
import io.github.kvtttba.droplet.core.domain.stats.StatsCurrencyFilter
import io.github.kvtttba.droplet.core.domain.stats.StatsPeriodType
import io.github.kvtttba.droplet.core.domain.stats.StatsQuery
import io.github.kvtttba.droplet.core.domain.stats.StatsTagFilterMode
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

private data class StatsInteractiveControls(
    val displayTab: StatsDisplayTab = StatsDisplayTab.OVERVIEW,
    val categoryDirection: StatsCategoryDirection = StatsCategoryDirection.EXPENSE,
    val selectedCalendarDate: LocalDate? = null,
    val drilldownCategoryId: String? = null,
    val showTagFilterSheet: Boolean = false,
    val showCustomRangeDialog: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
) : ViewModel() {

    private val queryFlow = MutableStateFlow(StatsQuery())
    private val controlsFlow = MutableStateFlow(StatsInteractiveControls())

    val uiState: StateFlow<StatsUiState> = queryFlow
        .flatMapLatest { query ->
            combine(
                statsRepository.observeStatsDashboard(query),
                controlsFlow,
            ) { dashboard, controls ->
                StatsUiState(
                    isLoading = false,
                    displayTab = controls.displayTab,
                    query = query,
                    categoryDirection = controls.categoryDirection,
                    report = dashboard.report,
                    availableTags = dashboard.availableTags,
                    monthlyBudgetProgress = dashboard.monthlyBudgetProgress,
                    yearlyBudgetProgress = dashboard.yearlyBudgetProgress,
                    selectedCalendarDate = controls.selectedCalendarDate,
                    drilldownCategoryId = controls.drilldownCategoryId,
                    showTagFilterSheet = controls.showTagFilterSheet,
                    showCustomRangeDialog = controls.showCustomRangeDialog,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = StatsUiState(isLoading = true, query = queryFlow.value),
        )

    fun selectDisplayTab(tab: StatsDisplayTab) {
        controlsFlow.update { it.copy(displayTab = tab, drilldownCategoryId = null) }
    }

    fun selectPeriodType(periodType: StatsPeriodType) {
        queryFlow.update { current ->
            when (periodType) {
                StatsPeriodType.MONTHLY -> current.copy(
                    periodType = StatsPeriodType.MONTHLY,
                    calendarMonth = current.targetMonth,
                )
                StatsPeriodType.YEARLY -> current.copy(
                    periodType = StatsPeriodType.YEARLY,
                    targetYear = current.targetMonth.year,
                )
                StatsPeriodType.CUSTOM_RANGE -> current.copy(
                    periodType = StatsPeriodType.CUSTOM_RANGE,
                )
            }
        }
        if (periodType == StatsPeriodType.CUSTOM_RANGE) {
            controlsFlow.update { it.copy(showCustomRangeDialog = true) }
        }
    }

    fun navigatePreviousPeriod() {
        queryFlow.update { current ->
            when (current.periodType) {
                StatsPeriodType.MONTHLY -> {
                    val prev = current.targetMonth.minusMonths(1)
                    current.copy(targetMonth = prev, targetYear = prev.year, calendarMonth = prev)
                }
                StatsPeriodType.YEARLY -> {
                    val prevYear = current.targetYear - 1
                    current.copy(
                        targetYear = prevYear,
                        targetMonth = YearMonth.of(prevYear, current.targetMonth.monthValue),
                    )
                }
                StatsPeriodType.CUSTOM_RANGE -> {
                    val spanDays = java.time.temporal.ChronoUnit.DAYS
                        .between(current.customStartDate, current.customEndDate) + 1L
                    current.copy(
                        customStartDate = current.customStartDate.minusDays(spanDays),
                        customEndDate = current.customEndDate.minusDays(spanDays),
                    )
                }
            }
        }
        controlsFlow.update { it.copy(drilldownCategoryId = null, selectedCalendarDate = null) }
    }

    fun navigateNextPeriod() {
        queryFlow.update { current ->
            when (current.periodType) {
                StatsPeriodType.MONTHLY -> {
                    val next = current.targetMonth.plusMonths(1)
                    current.copy(targetMonth = next, targetYear = next.year, calendarMonth = next)
                }
                StatsPeriodType.YEARLY -> {
                    val nextYear = current.targetYear + 1
                    current.copy(
                        targetYear = nextYear,
                        targetMonth = YearMonth.of(nextYear, current.targetMonth.monthValue),
                    )
                }
                StatsPeriodType.CUSTOM_RANGE -> {
                    val spanDays = java.time.temporal.ChronoUnit.DAYS
                        .between(current.customStartDate, current.customEndDate) + 1L
                    current.copy(
                        customStartDate = current.customStartDate.plusDays(spanDays),
                        customEndDate = current.customEndDate.plusDays(spanDays),
                    )
                }
            }
        }
        controlsFlow.update { it.copy(drilldownCategoryId = null, selectedCalendarDate = null) }
    }

    fun applyCustomDateRange(
        startDate: LocalDate,
        endDate: LocalDate,
    ) {
        val (s, e) = if (endDate.isBefore(startDate)) endDate to startDate else startDate to endDate
        queryFlow.update {
            it.copy(
                periodType = StatsPeriodType.CUSTOM_RANGE,
                customStartDate = s,
                customEndDate = e,
                targetMonth = YearMonth.from(e),
                targetYear = e.year,
            )
        }
        controlsFlow.update { it.copy(showCustomRangeDialog = false, drilldownCategoryId = null) }
    }

    fun selectCurrencyFilter(filter: StatsCurrencyFilter) {
        queryFlow.update { it.copy(currencyFilter = filter) }
        controlsFlow.update { it.copy(drilldownCategoryId = null) }
    }

    fun toggleTag(tagId: String) {
        queryFlow.update { current ->
            val next = if (tagId in current.selectedTagIds) {
                current.selectedTagIds - tagId
            } else {
                current.selectedTagIds + tagId
            }
            current.copy(selectedTagIds = next)
        }
    }

    fun setTagFilterMode(mode: StatsTagFilterMode) {
        queryFlow.update { it.copy(tagFilterMode = mode) }
    }

    fun clearTagFilter() {
        queryFlow.update { it.copy(selectedTagIds = emptySet()) }
    }

    fun selectCategoryDirection(direction: StatsCategoryDirection) {
        controlsFlow.update { it.copy(categoryDirection = direction, drilldownCategoryId = null) }
    }

    fun openCategoryDrilldown(categoryId: String) {
        controlsFlow.update {
            it.copy(
                drilldownCategoryId = categoryId,
                showTagFilterSheet = false,
                showCustomRangeDialog = false,
            )
        }
    }

    fun dismissCategoryDrilldown() {
        controlsFlow.update { it.copy(drilldownCategoryId = null) }
    }

    fun navigatePreviousCalendarMonth() {
        queryFlow.update { current ->
            val prev = current.calendarMonth.minusMonths(1)
            current.copy(calendarMonth = prev)
        }
        controlsFlow.update { it.copy(selectedCalendarDate = null) }
    }

    fun navigateNextCalendarMonth() {
        queryFlow.update { current ->
            val next = current.calendarMonth.plusMonths(1)
            current.copy(calendarMonth = next)
        }
        controlsFlow.update { it.copy(selectedCalendarDate = null) }
    }

    fun selectCalendarDate(date: LocalDate) {
        controlsFlow.update { it.copy(selectedCalendarDate = date) }
    }

    fun setShowTagFilterSheet(show: Boolean) {
        controlsFlow.update {
            it.copy(
                showTagFilterSheet = show,
                drilldownCategoryId = if (show) null else it.drilldownCategoryId,
            )
        }
    }

    fun setShowCustomRangeDialog(show: Boolean) {
        controlsFlow.update { it.copy(showCustomRangeDialog = show) }
    }
}
