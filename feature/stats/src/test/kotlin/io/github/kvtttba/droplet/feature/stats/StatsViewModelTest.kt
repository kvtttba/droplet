package io.github.kvtttba.droplet.feature.stats

import io.github.kvtttba.droplet.core.database.repository.StatsDashboardData
import io.github.kvtttba.droplet.core.database.repository.StatsRepository
import io.github.kvtttba.droplet.core.domain.stats.StatsAggregator
import io.github.kvtttba.droplet.core.domain.stats.StatsCategoryDirection
import io.github.kvtttba.droplet.core.domain.stats.StatsCurrencyFilter
import io.github.kvtttba.droplet.core.domain.stats.StatsPeriodType
import io.github.kvtttba.droplet.core.domain.stats.StatsQuery
import io.github.kvtttba.droplet.core.domain.stats.StatsTagFilterMode
import io.github.kvtttba.droplet.core.model.Tag
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val sampleTags = listOf(Tag("t1", "工作日"), Tag("t2", "旅行"))

    private inner class FakeStatsRepository : StatsRepository {
        val observedQueries = mutableListOf<StatsQuery>()

        override fun observeStatsDashboard(query: StatsQuery): Flow<StatsDashboardData> {
            observedQueries += query
            val report = StatsAggregator.aggregate(
                query = query,
                allTransactions = emptyList(),
                accountsById = emptyMap(),
                tagsById = sampleTags.associateBy { it.id },
                zoneId = zone,
            )
            return flowOf(
                StatsDashboardData(
                    report = report,
                    availableTags = sampleTags,
                ),
            )
        }

        override suspend fun getStatsDashboardSnapshot(query: StatsQuery): StatsDashboardData =
            StatsDashboardData(
                report = StatsAggregator.aggregate(query, emptyList(), emptyMap(), zoneId = zone),
                availableTags = sampleTags,
            )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun periodNavigationTagsCurrencyAndCalendarControls_updateQueryReactively() = runTest {
        val repo = FakeStatsRepository()
        val vm = StatsViewModel(repo)
        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        val initialMonth = vm.uiState.value.query.targetMonth

        // Step previous & next month
        vm.navigatePreviousPeriod()
        advanceUntilIdle()
        assertEquals(initialMonth.minusMonths(1), vm.uiState.value.query.targetMonth)
        assertEquals(initialMonth.minusMonths(1), vm.uiState.value.query.calendarMonth)

        vm.navigateNextPeriod()
        advanceUntilIdle()
        assertEquals(initialMonth, vm.uiState.value.query.targetMonth)

        // Switch to YEARLY & navigate year
        vm.selectPeriodType(StatsPeriodType.YEARLY)
        advanceUntilIdle()
        assertEquals(StatsPeriodType.YEARLY, vm.uiState.value.query.periodType)
        val year = vm.uiState.value.query.targetYear
        vm.navigatePreviousPeriod()
        advanceUntilIdle()
        assertEquals(year - 1, vm.uiState.value.query.targetYear)

        // Apply custom date range
        vm.applyCustomDateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 15))
        advanceUntilIdle()
        assertEquals(StatsPeriodType.CUSTOM_RANGE, vm.uiState.value.query.periodType)
        assertEquals(LocalDate.of(2026, 10, 1), vm.uiState.value.query.customStartDate)
        assertEquals(LocalDate.of(2026, 11, 15), vm.uiState.value.query.customEndDate)

        // Toggle multi-tag & ALL mode + currency filter
        vm.toggleTag("t1")
        vm.toggleTag("t2")
        vm.setTagFilterMode(StatsTagFilterMode.ALL)
        vm.selectCurrencyFilter(StatsCurrencyFilter.ORIGINAL_USD)
        advanceUntilIdle()
        assertEquals(setOf("t1", "t2"), vm.uiState.value.query.selectedTagIds)
        assertEquals(StatsTagFilterMode.ALL, vm.uiState.value.query.tagFilterMode)
        assertEquals(StatsCurrencyFilter.ORIGINAL_USD, vm.uiState.value.query.currencyFilter)
        assertTrue(vm.uiState.value.selectedTagSummary.contains("同时满足"))

        // Switch direction, calendar tab, and calendar date
        vm.selectCategoryDirection(StatsCategoryDirection.INCOME)
        vm.openCategoryDrilldown("cat:salary")
        advanceUntilIdle()
        assertEquals(StatsCategoryDirection.INCOME, vm.uiState.value.categoryDirection)
        assertEquals("cat:salary", vm.uiState.value.drilldownCategoryId)

        vm.selectDisplayTab(StatsDisplayTab.CALENDAR)
        vm.selectCalendarDate(LocalDate.of(2026, 11, 10))
        advanceUntilIdle()
        assertEquals(StatsDisplayTab.CALENDAR, vm.uiState.value.displayTab)
        assertNull(vm.uiState.value.drilldownCategoryId)
        assertEquals(LocalDate.of(2026, 11, 10), vm.uiState.value.selectedCalendarDate)

        val calMonth = vm.uiState.value.query.calendarMonth
        vm.navigatePreviousCalendarMonth()
        advanceUntilIdle()
        assertEquals(calMonth.minusMonths(1), vm.uiState.value.query.calendarMonth)
        assertEquals(YearMonth.from(calMonth.minusMonths(1)), vm.uiState.value.query.calendarMonth)

        collectJob.cancel()
    }
}
