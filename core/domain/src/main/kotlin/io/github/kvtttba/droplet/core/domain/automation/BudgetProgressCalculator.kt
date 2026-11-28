package io.github.kvtttba.droplet.core.domain.automation

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Budget
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class BudgetAlertLevel {
    NORMAL,
    WARNING,
    EXCEEDED,
}

data class BudgetPeriodRange(
    val period: BudgetPeriod,
    val periodKey: String,
    val startInclusive: Long,
    val endExclusive: Long,
    val totalDays: Int,
    val elapsedDays: Int,
    val remainingDays: Int,
)

data class BudgetProgress(
    val budget: Budget,
    val categoryName: String?,
    val categoryIcon: String?,
    val categoryColor: String?,
    val periodRange: BudgetPeriodRange,
    val spentAmount: Money,
    val remainingAmount: Money,
    val usedPercentageDecimal: String,
    val usedPercentageInt: Int,
    val progressFraction: Float,
    val dailyAvailableAmount: Money,
    val alertLevel: BudgetAlertLevel,
) {
    val isOverall: Boolean
        get() = budget.categoryAccountId == null

    val isExceeded: Boolean
        get() = alertLevel == BudgetAlertLevel.EXCEEDED

    val isWarning: Boolean
        get() = alertLevel == BudgetAlertLevel.WARNING

    val displayTitle: String
        get() = categoryName ?: when (budget.period) {
            BudgetPeriod.MONTHLY -> "月度总预算"
            BudgetPeriod.YEARLY -> "年度总预算"
        }

    /**
     * Deterministic per-budget, per-period, per-threshold deduplication key so an 80% warning
     * and a 100% exceeded alert fire at most once per budget period.
     */
    val alertDedupKey: String
        get() = "${budget.id}|${periodRange.periodKey}|${alertLevel.name}"
}

/**
 * Pure JVM zero-floating-point budget progress and threshold alert calculator.
 *
 * Accounting invariants:
 * - Only [TransactionStatus.POSTED] transactions within the budget's period range are counted;
 *   `VOID` and `DRAFT` transactions are strictly excluded.
 * - Only non-system-balancing postings targeting [AccountType.EXPENSE] accounts are aggregated:
 *   - Regular expenses add positive expense amounts.
 *   - Linked [io.github.kvtttba.droplet.core.model.TransactionDisplayType.REFUND] transactions
 *     carry negative expense postings and automatically reduce net spending.
 *   - Reimbursable advances post to `Asset:Reimbursement` (not `EXPENSE`) and never inflate
 *     personal budget spending; any un-reimbursed personal shortfall posted to `EXPENSE` counts.
 * - Parent category budgets automatically include spending on the parent itself and all descendant
 *   subcategories.
 * - Historical locked `baseAmountMinor` is used directly whenever `posting.baseCurrency == budget.currency`,
 *   guaranteeing budget reports never drift due to later FX updates.
 */
object BudgetProgressCalculator {

    val DEFAULT_ZONE_ID: ZoneId = ZoneId.systemDefault()
    private val HUNDRED = BigDecimal.valueOf(100L)

    fun periodRangeFor(
        period: BudgetPeriod,
        asOfDate: LocalDate,
        zoneId: ZoneId = DEFAULT_ZONE_ID,
    ): BudgetPeriodRange = when (period) {
        BudgetPeriod.MONTHLY -> monthPeriodRange(
            month = YearMonth.from(asOfDate),
            referenceDate = asOfDate,
            zoneId = zoneId,
        )
        BudgetPeriod.YEARLY -> yearPeriodRange(
            year = asOfDate.year,
            referenceDate = asOfDate,
            zoneId = zoneId,
        )
    }

    fun monthPeriodRange(
        month: YearMonth,
        referenceDate: LocalDate = LocalDate.now(DEFAULT_ZONE_ID),
        zoneId: ZoneId = DEFAULT_ZONE_ID,
    ): BudgetPeriodRange {
        val firstDay = month.atDay(1)
        val lastDay = month.atEndOfMonth()
        val startMs = firstDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val totalDays = month.lengthOfMonth()
        val (elapsed, remaining) = computeElapsedAndRemainingDays(firstDay, lastDay, totalDays, referenceDate)
        return BudgetPeriodRange(
            period = BudgetPeriod.MONTHLY,
            periodKey = "%04d-%02d".format(month.year, month.monthValue),
            startInclusive = startMs,
            endExclusive = endMs,
            totalDays = totalDays,
            elapsedDays = elapsed,
            remainingDays = remaining,
        )
    }

    fun yearPeriodRange(
        year: Int,
        referenceDate: LocalDate = LocalDate.now(DEFAULT_ZONE_ID),
        zoneId: ZoneId = DEFAULT_ZONE_ID,
    ): BudgetPeriodRange {
        val firstDay = LocalDate.of(year, 1, 1)
        val lastDay = LocalDate.of(year, 12, 31)
        val startMs = firstDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = LocalDate.of(year + 1, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val totalDays = Year.of(year).length()
        val (elapsed, remaining) = computeElapsedAndRemainingDays(firstDay, lastDay, totalDays, referenceDate)
        return BudgetPeriodRange(
            period = BudgetPeriod.YEARLY,
            periodKey = "%04d".format(year),
            startInclusive = startMs,
            endExclusive = endMs,
            totalDays = totalDays,
            elapsedDays = elapsed,
            remainingDays = remaining,
        )
    }

    fun calculateAll(
        budgets: List<Budget>,
        transactions: List<Transaction>,
        accountsById: Map<String, Account>,
        asOfEpochMs: Long,
        zoneId: ZoneId = DEFAULT_ZONE_ID,
        targetMonthOverride: YearMonth? = null,
        rateProvider: ExchangeRateProvider? = null,
    ): List<BudgetProgress> {
        val todayDate = Instant.ofEpochMilli(asOfEpochMs).atZone(zoneId).toLocalDate()
        val monthRange = if (targetMonthOverride != null) {
            monthPeriodRange(month = targetMonthOverride, referenceDate = todayDate, zoneId = zoneId)
        } else {
            monthPeriodRange(month = YearMonth.from(todayDate), referenceDate = todayDate, zoneId = zoneId)
        }
        val yearRange = if (targetMonthOverride != null) {
            yearPeriodRange(year = targetMonthOverride.year, referenceDate = todayDate, zoneId = zoneId)
        } else {
            yearPeriodRange(year = todayDate.year, referenceDate = todayDate, zoneId = zoneId)
        }

        return budgets.map { budget ->
            val range = when (budget.period) {
                BudgetPeriod.MONTHLY -> monthRange
                BudgetPeriod.YEARLY -> yearRange
            }
            calculateSingle(
                budget = budget,
                periodRange = range,
                transactions = transactions,
                accountsById = accountsById,
                rateProvider = rateProvider,
            )
        }
    }

    fun calculateSingle(
        budget: Budget,
        periodRange: BudgetPeriodRange,
        transactions: List<Transaction>,
        accountsById: Map<String, Account>,
        rateProvider: ExchangeRateProvider? = null,
    ): BudgetProgress {
        val targetCategoryId = budget.categoryAccountId
        val matchingAccountIds = if (targetCategoryId != null) {
            collectCategoryAndDescendants(targetCategoryId, accountsById)
        } else {
            null
        }

        var rawSpentMinor = 0L
        for (tx in transactions) {
            if (tx.status != TransactionStatus.POSTED) continue
            if (tx.occurredAt < periodRange.startInclusive || tx.occurredAt >= periodRange.endExclusive) {
                continue
            }
            for (posting in tx.postings) {
                if (posting.isSystemBalancing) continue
                val account = accountsById[posting.accountId] ?: continue
                if (account.type != AccountType.EXPENSE) continue
                if (matchingAccountIds != null && posting.accountId !in matchingAccountIds) continue

                val contributionMinor = resolvePostingAmountInBudgetCurrency(
                    postingAmountMinor = posting.amountMinor,
                    postingCurrency = posting.currency,
                    postingBaseAmountMinor = posting.baseAmountMinor,
                    postingBaseCurrency = posting.baseCurrency,
                    budgetCurrency = budget.currency,
                    rateProvider = rateProvider,
                ) ?: continue
                rawSpentMinor = Math.addExact(rawSpentMinor, contributionMinor)
            }
        }

        val spentMinor = rawSpentMinor.coerceAtLeast(0L)
        val limitMinor = budget.limitAmountMinor
        val remainingMinor = Math.subtractExact(limitMinor, spentMinor)

        val spentBd = BigDecimal.valueOf(spentMinor)
        val limitBd = BigDecimal.valueOf(limitMinor)

        // Exact integer/BigDecimal alert threshold check (zero float drift)
        val alertLevel = when {
            spentMinor >= limitMinor -> BudgetAlertLevel.EXCEEDED
            spentBd.multiply(HUNDRED) >= limitBd.multiply(BigDecimal.valueOf(budget.warnThresholdPct.toLong())) ->
                BudgetAlertLevel.WARNING
            else -> BudgetAlertLevel.NORMAL
        }

        val usedPctBd = spentBd.multiply(HUNDRED).divide(limitBd, 1, RoundingMode.HALF_UP)
        val usedPctInt = spentBd.multiply(HUNDRED)
            .divide(limitBd, 0, RoundingMode.HALF_UP)
            .toInt()
            .coerceAtLeast(0)
        val fractionBd = spentBd.divide(limitBd, 4, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal.ZERO, BigDecimal.ONE)

        val dailyAvailableMinor = if (remainingMinor > 0L && periodRange.remainingDays > 0) {
            BigDecimal.valueOf(remainingMinor)
                .divide(BigDecimal.valueOf(periodRange.remainingDays.toLong()), 0, RoundingMode.DOWN)
                .toLong()
        } else {
            0L
        }

        val categoryAccount = targetCategoryId?.let { accountsById[it] }
        val categoryDisplayName = categoryAccount?.let { cat ->
            val parent = cat.parentId?.let { accountsById[it] }
            if (parent != null) "${parent.name}·${cat.name}" else cat.name
        }

        return BudgetProgress(
            budget = budget,
            categoryName = categoryDisplayName,
            categoryIcon = categoryAccount?.icon,
            categoryColor = categoryAccount?.color,
            periodRange = periodRange,
            spentAmount = Money(spentMinor, budget.currency),
            remainingAmount = Money(remainingMinor, budget.currency),
            usedPercentageDecimal = usedPctBd.toPlainString(),
            usedPercentageInt = usedPctInt,
            progressFraction = fractionBd.toFloat(),
            dailyAvailableAmount = Money(dailyAvailableMinor, budget.currency),
            alertLevel = alertLevel,
        )
    }

    internal fun collectCategoryAndDescendants(
        rootCategoryId: String,
        accountsById: Map<String, Account>,
    ): Set<String> {
        val result = linkedSetOf(rootCategoryId)
        val childrenByParent = accountsById.values
            .filter { it.parentId != null }
            .groupBy { it.parentId!! }
        val queue = ArrayDeque<String>()
        queue.add(rootCategoryId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (child in childrenByParent[current].orEmpty()) {
                if (result.add(child.id)) {
                    queue.add(child.id)
                }
            }
        }
        return result
    }

    private fun resolvePostingAmountInBudgetCurrency(
        postingAmountMinor: Long,
        postingCurrency: Currency,
        postingBaseAmountMinor: Long,
        postingBaseCurrency: Currency,
        budgetCurrency: Currency,
        rateProvider: ExchangeRateProvider?,
    ): Long? {
        if (postingBaseCurrency == budgetCurrency) {
            return postingBaseAmountMinor
        }
        if (postingCurrency == budgetCurrency) {
            return postingAmountMinor
        }
        val snapshot = rateProvider?.findRate(postingBaseCurrency, budgetCurrency) ?: return null
        return snapshot.convert(Money(postingBaseAmountMinor, postingBaseCurrency)).amountMinor
    }

    private fun computeElapsedAndRemainingDays(
        firstDay: LocalDate,
        lastDay: LocalDate,
        totalDays: Int,
        referenceDate: LocalDate,
    ): Pair<Int, Int> = when {
        referenceDate.isBefore(firstDay) -> 0 to totalDays
        referenceDate.isAfter(lastDay) -> totalDays to 0
        else -> {
            val elapsed = ChronoUnit.DAYS.between(firstDay, referenceDate).toInt() + 1
            val remaining = ChronoUnit.DAYS.between(referenceDate, lastDay).toInt() + 1
            elapsed.coerceIn(1, totalDays) to remaining.coerceIn(1, totalDays)
        }
    }
}
