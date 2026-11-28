package io.github.kvtttba.droplet.core.domain.stats

import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import java.time.LocalDate
import java.time.YearMonth

enum class StatsPeriodType(val label: String) {
    MONTHLY("月度"),
    YEARLY("年度"),
    CUSTOM_RANGE("自定义"),
}

enum class StatsTagFilterMode(val label: String) {
    /** Matches transactions carrying ANY of the selected tags. */
    ANY("任一标签"),

    /** Matches transactions carrying ALL of the selected tags. */
    ALL("同时满足"),
}

enum class StatsCurrencyFilter(
    val displayCurrency: Currency,
    val isBaseCurrencyMode: Boolean,
    val label: String,
    val shortLabel: String,
) {
    BASE_CNY(
        displayCurrency = Currency.CNY,
        isBaseCurrencyMode = true,
        label = "本位币 CNY",
        shortLabel = "本位币",
    ),
    ORIGINAL_CNY(
        displayCurrency = Currency.CNY,
        isBaseCurrencyMode = false,
        label = "仅 CNY",
        shortLabel = "CNY",
    ),
    ORIGINAL_USD(
        displayCurrency = Currency.USD,
        isBaseCurrencyMode = false,
        label = "仅 USD",
        shortLabel = "USD",
    ),
    ORIGINAL_HKD(
        displayCurrency = Currency.HKD,
        isBaseCurrencyMode = false,
        label = "仅 HKD",
        shortLabel = "HKD",
    ),
}

enum class StatsCategoryDirection(val label: String) {
    EXPENSE("支出"),
    INCOME("收入"),
}

enum class StatsTrendGranularity {
    DAILY,
    MONTHLY,
}

data class StatsQuery(
    val periodType: StatsPeriodType = StatsPeriodType.MONTHLY,
    val targetMonth: YearMonth = YearMonth.now(),
    val targetYear: Int = targetMonth.year,
    val customStartDate: LocalDate = targetMonth.atDay(1),
    val customEndDate: LocalDate = targetMonth.atEndOfMonth(),
    val selectedTagIds: Set<String> = emptySet(),
    val tagFilterMode: StatsTagFilterMode = StatsTagFilterMode.ANY,
    val currencyFilter: StatsCurrencyFilter = StatsCurrencyFilter.BASE_CNY,
    val calendarMonth: YearMonth = targetMonth,
) {
    init {
        if (periodType == StatsPeriodType.CUSTOM_RANGE) {
            require(!customEndDate.isBefore(customStartDate)) {
                "customEndDate ($customEndDate) must not precede customStartDate ($customStartDate)"
            }
        }
    }
}

data class StatsDateRange(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val startEpochMs: Long,
    val endEpochMsExclusive: Long,
    val dayCount: Int,
)

data class StatsDrilldownTransaction(
    val transactionId: String,
    val occurredAt: Long,
    val date: LocalDate,
    val displayType: TransactionDisplayType,
    val title: String,
    val categoryId: String?,
    val categoryName: String,
    val accountName: String,
    val payee: String?,
    val note: String?,
    /** Signed cashflow contribution: positive for income, negative for net expense. */
    val signedFlowAmount: Money,
    /** Positive display magnitude in the active statistics currency. */
    val displayAmount: Money,
    val isExpense: Boolean,
    val isRefund: Boolean,
    val tagNames: List<String> = emptyList(),
    val iconKey: String? = null,
    val colorHex: String? = null,
)

data class TrendBucket(
    val key: String,
    val label: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val income: Money,
    val expense: Money,
    val netBalance: Money,
    val transactionCount: Int,
)

data class SubCategoryBreakdownItem(
    val categoryId: String,
    val name: String,
    val iconKey: String?,
    val colorHex: String?,
    val amount: Money,
    val shareOfParentPctDecimal: String,
    val shareOfTotalPctDecimal: String,
    val transactionCount: Int,
    val transactionIds: List<String>,
)

data class CategoryRankingItem(
    val categoryId: String,
    val name: String,
    val iconKey: String?,
    val colorHex: String?,
    val amount: Money,
    val sharePctDecimal: String,
    val shareFraction: Float,
    val transactionCount: Int,
    val subCategories: List<SubCategoryBreakdownItem>,
    val transactions: List<StatsDrilldownTransaction>,
)

data class NetWorthPoint(
    val key: String,
    val label: String,
    val asOfDate: LocalDate,
    val totalAssets: Money,
    val totalLiabilities: Money,
    val netWorth: Money,
)

data class CalendarDayCell(
    val date: LocalDate,
    val dayOfMonth: Int,
    val income: Money,
    val expense: Money,
    val netFlow: Money,
    val transactionCount: Int,
    val transactions: List<StatsDrilldownTransaction>,
) {
    val hasActivity: Boolean
        get() = transactionCount > 0 || !income.isZero || !expense.isZero
}

data class CalendarMonthSummary(
    val month: YearMonth,
    val currency: Currency,
    /** ISO day-of-week for the 1st day of the month: 1 (Mon) .. 7 (Sun). */
    val firstDayOfWeekIso: Int,
    val daysInMonth: Int,
    val totalIncome: Money,
    val totalExpense: Money,
    val netFlow: Money,
    val activeDayCount: Int,
    val days: List<CalendarDayCell>,
)

data class StatsReportSnapshot(
    val query: StatsQuery,
    val dateRange: StatsDateRange,
    val displayCurrency: Currency,
    val granularity: StatsTrendGranularity,
    val totalIncome: Money,
    val totalExpense: Money,
    val netBalance: Money,
    val dailyAverageExpense: Money,
    val transactionCount: Int,
    val trendBuckets: List<TrendBucket>,
    val expenseRankings: List<CategoryRankingItem>,
    val incomeRankings: List<CategoryRankingItem>,
    val netWorthTrend: List<NetWorthPoint>,
    val calendarMonth: CalendarMonthSummary,
)
