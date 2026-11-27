package io.github.kvtttba.droplet.core.domain.loan

import io.github.kvtttba.droplet.core.domain.investment.InvestmentDecimalMath
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.InstallmentStatus
import io.github.kvtttba.droplet.core.model.LoanInstallment
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.LoanPlan
import io.github.kvtttba.droplet.core.model.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Lifecycle status of an installment loan plan as of a given calendar date.
 */
enum class LoanPlanStatus {
    /** All scheduled installments have been paid / settled early. */
    PAID_OFF,

    /** At least one PENDING installment is strictly past its due date. */
    OVERDUE,

    /** Earliest PENDING installment is within `[dueDate - remindDaysBefore, dueDate]` (inclusive). */
    DUE_SOON,

    /** Active loan with upcoming PENDING installments outside the reminder window. */
    ACTIVE,
}

/**
 * Aggregated schedule, progress, and due/reminder snapshot for a [LoanPlan].
 *
 * Algebraic invariants:
 * - `paidPeriods + remainingPeriods == installments.size`
 * - `paidPrincipal + remainingPrincipal == totalScheduledPrincipal` (equals `plan.totalPrincipal`)
 * - `totalScheduledRepayment == totalPrincipal + totalScheduledInterest`
 * - `remainingTotal == remainingPrincipal + remainingInterest`
 */
data class LoanPlanSummary(
    val plan: LoanPlan,
    val installments: List<LoanInstallment>,
    val asOfDate: LocalDate,
    val zoneId: ZoneId,
    val totalPrincipal: Money,
    val totalScheduledInterest: Money,
    val totalScheduledRepayment: Money,
    val paidPeriods: Int,
    val remainingPeriods: Int,
    val paidPrincipal: Money,
    val paidInterest: Money,
    val remainingPrincipal: Money,
    val remainingInterest: Money,
    val remainingTotal: Money,
    val nextPendingInstallment: LoanInstallment?,
    val nextDueDate: LocalDate?,
    val daysUntilNextDue: Int?,
    val isNextDueToday: Boolean,
    val overdueDays: Int,
    val overdueInstallments: List<LoanInstallment>,
    val dueNowInstallments: List<LoanInstallment>,
    val reminderCandidateInstallment: LoanInstallment?,
    val isInReminderWindow: Boolean,
    val status: LoanPlanStatus,
    val repaidProgressPctDecimal: String,
) {
    val isPaidOff: Boolean
        get() = status == LoanPlanStatus.PAID_OFF

    val totalPeriods: Int
        get() = installments.size.takeIf { it > 0 } ?: plan.totalPeriods
}

/**
 * Preview / quote for paying off all remaining PENDING installments early in one lump sum.
 */
data class EarlyPayoffQuote(
    val loanPlanId: String,
    val currency: Currency,
    val remainingPendingPeriods: Int,
    val remainingPrincipal: Money,
    val waivedFutureInterest: Money,
    val earlyPayoffFeeOrInterest: Money,
    val totalPayoffOutflow: Money,
    val netInterestSaved: Money,
)

/**
 * Pure Kotlin/JVM installment loan schedule & summary calculator.
 *
 * Strictly zero floating-point arithmetic: uses fixed-point [Money] (`Long`, scale = 4),
 * high-precision [BigDecimal] (`MathContext.DECIMAL128`), and `java.time`.
 *
 * Supported methods:
 * - [LoanMethod.EQUAL_INSTALLMENT] (等额本息): constant monthly payment across periods `1..N-1`
 *   using exact `BigDecimal` annuity formula `P * r * (1+r)^N / ((1+r)^N - 1)`, with tail-period
 *   principal adjustment guaranteeing `SUM(principalMinor) == totalPrincipalMinor` and final
 *   `remainingPrincipalMinor == 0L`.
 * - [LoanMethod.EQUAL_PRINCIPAL] (等额本金): constant monthly principal `P / N` (rounded to cents)
 *   with monotonically declining interest on remaining principal, plus final-period tail adjustment.
 * - [LoanMethod.FIXED_FEE] (信用卡/消费固定手续费分期): constant principal `P / N` plus fixed
 *   per-period fee (`fixedFeePerPeriodMinor` or `totalPrincipal * fixedFeeRateDecimal`, or 0 for
 *   interest-free installments), with final-period tail adjustment.
 *
 * Calendar rules:
 * - Period 1 falls on `firstDueDate` resolved from `plan.firstDueAt` in [zoneId].
 * - Period `k >= 2` falls on `YearMonth.from(firstDueDate).plusMonths(k - 1)` with
 *   `plan.monthlyRepayDay` clamped to `lengthOfMonth()` (handles 29–31 on 30-day months and
 *   leap/non-leap February cleanly).
 */
object LoanScheduleCalculator {

    val DEFAULT_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")

    private val MONTHS_PER_YEAR = BigDecimal("12")
    private val HIGH_PRECISION = MathContext.DECIMAL128
    private const val CENT_DISPLAY_SCALE = 2

    fun deterministicInstallmentId(loanPlanId: String, periodNumber: Int): String {
        require(loanPlanId.isNotBlank()) { "loanPlanId must not be blank" }
        require(periodNumber > 0) { "periodNumber must be positive, got $periodNumber" }
        return "$loanPlanId:p$periodNumber"
    }

    fun clampDayOfMonth(yearMonth: YearMonth, dayOfMonth: Int): LocalDate {
        require(dayOfMonth in 1..31) {
            "dayOfMonth must be in 1..31, got $dayOfMonth"
        }
        return yearMonth.atDay(minOf(dayOfMonth, yearMonth.lengthOfMonth()))
    }

    /**
     * Resolves the calendar due date for 1-based [periodNumber].
     *
     * Period 1 uses [firstDueDate] verbatim; subsequent periods advance by `(periodNumber - 1)`
     * calendar months and clamp [monthlyRepayDay] to that month's length.
     */
    fun resolveDueDate(
        firstDueDate: LocalDate,
        monthlyRepayDay: Int,
        periodNumber: Int,
    ): LocalDate {
        require(monthlyRepayDay in 1..31) { "monthlyRepayDay must be in 1..31, got $monthlyRepayDay" }
        require(periodNumber > 0) { "periodNumber must be positive, got $periodNumber" }
        if (periodNumber == 1) return firstDueDate
        val targetMonth = YearMonth.from(firstDueDate).plusMonths((periodNumber - 1).toLong())
        return clampDayOfMonth(targetMonth, monthlyRepayDay)
    }

    /**
     * Generates the full [LoanInstallment] schedule for [plan].
     *
     * Guaranteed invariants:
     * - `result.size == plan.totalPeriods`
     * - `result.sumOf { it.principalMinor } == plan.totalPrincipalMinor`
     * - `result.last().remainingPrincipalMinor == 0L`
     * - Every installment has non-negative principal, interest/fee, and remaining principal
     */
    fun generateSchedule(
        plan: LoanPlan,
        zoneId: ZoneId = DEFAULT_ZONE_ID,
    ): List<LoanInstallment> {
        val firstDueDate = Instant.ofEpochMilli(plan.firstDueAt).atZone(zoneId).toLocalDate()
        val totalPrincipalMinor = plan.totalPrincipalMinor
        val periods = plan.totalPeriods
        val currency = plan.currency

        val rawRows: List<Triple<Long, Long, Long>> = when (plan.method) {
            LoanMethod.EQUAL_INSTALLMENT -> computeEqualInstallmentRows(
                totalPrincipalMinor = totalPrincipalMinor,
                totalPeriods = periods,
                annualRateDecimal = plan.annualRateDecimal,
                currency = currency,
            )
            LoanMethod.EQUAL_PRINCIPAL -> computeEqualPrincipalRows(
                totalPrincipalMinor = totalPrincipalMinor,
                totalPeriods = periods,
                annualRateDecimal = plan.annualRateDecimal,
                currency = currency,
            )
            LoanMethod.FIXED_FEE -> computeFixedFeeRows(
                totalPrincipalMinor = totalPrincipalMinor,
                totalPeriods = periods,
                fixedFeePerPeriodMinor = plan.fixedFeePerPeriodMinor,
                fixedFeeRateDecimal = plan.fixedFeeRateDecimal,
                currency = currency,
            )
        }

        return rawRows.mapIndexed { index, (principalMinor, interestOrFeeMinor, remainingMinor) ->
            val periodNumber = index + 1
            val dueDate = resolveDueDate(
                firstDueDate = firstDueDate,
                monthlyRepayDay = plan.monthlyRepayDay,
                periodNumber = periodNumber,
            )
            val dueAtEpochMs = dueDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            LoanInstallment(
                id = deterministicInstallmentId(plan.id, periodNumber),
                loanPlanId = plan.id,
                periodNumber = periodNumber,
                dueAt = dueAtEpochMs,
                principalMinor = principalMinor,
                interestOrFeeMinor = interestOrFeeMinor,
                remainingPrincipalMinor = remainingMinor,
                status = InstallmentStatus.PENDING,
                transactionId = null,
                paidAt = null,
            )
        }
    }

    /**
     * Convenience helper for live UI previews prior to persisting a [LoanPlan].
     */
    fun previewSchedule(
        method: LoanMethod,
        currency: Currency,
        totalPrincipalMinor: Long,
        totalPeriods: Int,
        annualRateDecimal: String? = null,
        fixedFeePerPeriodMinor: Long? = null,
        fixedFeeRateDecimal: String? = null,
        firstDueAt: Long,
        monthlyRepayDay: Int,
        zoneId: ZoneId = DEFAULT_ZONE_ID,
    ): List<LoanInstallment> {
        val draftPlan = LoanPlan(
            id = "preview",
            name = "preview",
            method = method,
            currency = currency,
            totalPrincipalMinor = totalPrincipalMinor,
            totalPeriods = totalPeriods,
            annualRateDecimal = annualRateDecimal,
            fixedFeePerPeriodMinor = fixedFeePerPeriodMinor,
            fixedFeeRateDecimal = fixedFeeRateDecimal,
            firstDueAt = firstDueAt,
            monthlyRepayDay = monthlyRepayDay,
            loanAccountId = "preview:loan",
            paymentAccountId = "preview:payment",
            interestExpenseAccountId = "preview:interest",
        )
        return generateSchedule(draftPlan, zoneId = zoneId)
    }

    fun summarize(
        plan: LoanPlan,
        installments: Iterable<LoanInstallment>,
        asOfEpochMs: Long,
        zoneId: ZoneId = DEFAULT_ZONE_ID,
    ): LoanPlanSummary {
        val asOfDate = Instant.ofEpochMilli(asOfEpochMs).atZone(zoneId).toLocalDate()
        return summarize(
            plan = plan,
            installments = installments,
            asOfDate = asOfDate,
            zoneId = zoneId,
        )
    }

    fun summarize(
        plan: LoanPlan,
        installments: Iterable<LoanInstallment>,
        asOfDate: LocalDate,
        zoneId: ZoneId = DEFAULT_ZONE_ID,
    ): LoanPlanSummary {
        val sorted = installments
            .filter { it.loanPlanId == plan.id }
            .sortedBy { it.periodNumber }
        val currency = plan.currency

        var totalPrincipalMinor = 0L
        var totalInterestMinor = 0L
        var paidPeriods = 0
        var remainingPeriods = 0
        var paidPrincipalMinor = 0L
        var paidInterestMinor = 0L
        var remainingPrincipalMinor = 0L
        var remainingInterestMinor = 0L

        val overdue = mutableListOf<LoanInstallment>()
        val dueNow = mutableListOf<LoanInstallment>()
        var nextPending: LoanInstallment? = null
        var nextDueDate: LocalDate? = null

        for (item in sorted) {
            totalPrincipalMinor = Math.addExact(totalPrincipalMinor, item.principalMinor)
            totalInterestMinor = Math.addExact(totalInterestMinor, item.interestOrFeeMinor)
            if (item.status == InstallmentStatus.PAID) {
                paidPeriods++
                paidPrincipalMinor = Math.addExact(paidPrincipalMinor, item.principalMinor)
                paidInterestMinor = Math.addExact(paidInterestMinor, item.interestOrFeeMinor)
            } else {
                remainingPeriods++
                remainingPrincipalMinor = Math.addExact(remainingPrincipalMinor, item.principalMinor)
                remainingInterestMinor = Math.addExact(remainingInterestMinor, item.interestOrFeeMinor)

                val itemDueDate = Instant.ofEpochMilli(item.dueAt).atZone(zoneId).toLocalDate()
                if (nextPending == null) {
                    nextPending = item
                    nextDueDate = itemDueDate
                }
                if (itemDueDate.isBefore(asOfDate)) {
                    overdue += item
                }
                if (!itemDueDate.isAfter(asOfDate)) {
                    dueNow += item
                }
            }
        }

        val effectiveTotalPrincipalMinor = if (sorted.isNotEmpty()) totalPrincipalMinor else plan.totalPrincipalMinor
        val daysUntilNextDue: Int? = nextDueDate?.let { ChronoUnit.DAYS.between(asOfDate, it).toInt() }
        val isNextDueToday = daysUntilNextDue == 0
        val overdueDays = if (daysUntilNextDue != null && daysUntilNextDue < 0) {
            Math.negateExact(daysUntilNextDue)
        } else {
            0
        }

        val remindDays = plan.remindDaysBefore
        val isInReminderWindow = nextPending != null &&
            remindDays != null &&
            daysUntilNextDue != null &&
            daysUntilNextDue <= remindDays
        val reminderCandidate = if (isInReminderWindow) nextPending else null

        val status = when {
            remainingPeriods == 0 -> LoanPlanStatus.PAID_OFF
            overdue.isNotEmpty() -> LoanPlanStatus.OVERDUE
            isInReminderWindow || isNextDueToday -> LoanPlanStatus.DUE_SOON
            else -> LoanPlanStatus.ACTIVE
        }

        val repaidProgressPct = if (effectiveTotalPrincipalMinor > 0L) {
            InvestmentDecimalMath.percentageOrNull(
                numerator = Money(paidPrincipalMinor, currency).toBigDecimal(),
                denominator = Money(effectiveTotalPrincipalMinor, currency).toBigDecimal(),
                scale = 2,
            ) ?: "0"
        } else {
            "0"
        }

        return LoanPlanSummary(
            plan = plan,
            installments = sorted,
            asOfDate = asOfDate,
            zoneId = zoneId,
            totalPrincipal = Money(effectiveTotalPrincipalMinor, currency),
            totalScheduledInterest = Money(totalInterestMinor, currency),
            totalScheduledRepayment = Money(Math.addExact(effectiveTotalPrincipalMinor, totalInterestMinor), currency),
            paidPeriods = paidPeriods,
            remainingPeriods = remainingPeriods,
            paidPrincipal = Money(paidPrincipalMinor, currency),
            paidInterest = Money(paidInterestMinor, currency),
            remainingPrincipal = Money(remainingPrincipalMinor, currency),
            remainingInterest = Money(remainingInterestMinor, currency),
            remainingTotal = Money(Math.addExact(remainingPrincipalMinor, remainingInterestMinor), currency),
            nextPendingInstallment = nextPending,
            nextDueDate = nextDueDate,
            daysUntilNextDue = daysUntilNextDue,
            isNextDueToday = isNextDueToday,
            overdueDays = overdueDays,
            overdueInstallments = overdue,
            dueNowInstallments = dueNow,
            reminderCandidateInstallment = reminderCandidate,
            isInReminderWindow = isInReminderWindow,
            status = status,
            repaidProgressPctDecimal = repaidProgressPct,
        )
    }

    /**
     * Calculates the exact early payoff amount and waived future interest across all remaining
     * `PENDING` installments of [plan].
     */
    fun calculateEarlyPayoff(
        plan: LoanPlan,
        installments: Iterable<LoanInstallment>,
        extraFeeOrInterest: Money = Money.zero(plan.currency),
    ): EarlyPayoffQuote {
        require(extraFeeOrInterest.currency == plan.currency) {
            "extraFeeOrInterest currency (${extraFeeOrInterest.currency}) must match loan currency (${plan.currency})"
        }
        require(!extraFeeOrInterest.isNegative) {
            "extraFeeOrInterest must be non-negative, got $extraFeeOrInterest"
        }

        val pending = installments
            .filter { it.loanPlanId == plan.id && it.status == InstallmentStatus.PENDING }
            .sortedBy { it.periodNumber }

        var remainingPrincipalMinor = 0L
        var waivedInterestMinor = 0L
        for (item in pending) {
            remainingPrincipalMinor = Math.addExact(remainingPrincipalMinor, item.principalMinor)
            waivedInterestMinor = Math.addExact(waivedInterestMinor, item.interestOrFeeMinor)
        }

        val remainingPrincipal = Money(remainingPrincipalMinor, plan.currency)
        val waivedFutureInterest = Money(waivedInterestMinor, plan.currency)
        val totalOutflow = remainingPrincipal + extraFeeOrInterest
        val netSavedMinor = Math.subtractExact(waivedInterestMinor, extraFeeOrInterest.amountMinor)
            .coerceAtLeast(0L)

        return EarlyPayoffQuote(
            loanPlanId = plan.id,
            currency = plan.currency,
            remainingPendingPeriods = pending.size,
            remainingPrincipal = remainingPrincipal,
            waivedFutureInterest = waivedFutureInterest,
            earlyPayoffFeeOrInterest = extraFeeOrInterest,
            totalPayoffOutflow = totalOutflow,
            netInterestSaved = Money(netSavedMinor, plan.currency),
        )
    }

    private fun computeEqualInstallmentRows(
        totalPrincipalMinor: Long,
        totalPeriods: Int,
        annualRateDecimal: String?,
        currency: Currency,
    ): List<Triple<Long, Long, Long>> {
        val annualRate = parseNonNegativeDecimalOrZero(annualRateDecimal)
        if (annualRate.compareTo(BigDecimal.ZERO) == 0) {
            return computeZeroInterestEqualRows(totalPrincipalMinor, totalPeriods, currency)
        }

        val monthlyRate = annualRate.divide(MONTHS_PER_YEAR, HIGH_PRECISION)
        val principalBd = Money(totalPrincipalMinor, currency).toBigDecimal()
        val onePlusR = BigDecimal.ONE.add(monthlyRate, HIGH_PRECISION)
        val factor = onePlusR.pow(totalPeriods, HIGH_PRECISION)
        val numerator = principalBd.multiply(monthlyRate, HIGH_PRECISION).multiply(factor, HIGH_PRECISION)
        val denominator = factor.subtract(BigDecimal.ONE, HIGH_PRECISION)
        val fixedPaymentBd = numerator.divide(denominator, HIGH_PRECISION)
        val fixedPaymentMinor = roundMajorToCentMinor(fixedPaymentBd, currency)

        val rows = ArrayList<Triple<Long, Long, Long>>(totalPeriods)
        var remainingMinor = totalPrincipalMinor

        for (period in 1..totalPeriods) {
            val remainingBd = Money(remainingMinor, currency).toBigDecimal()
            val interestMinor = roundMajorToCentMinor(
                remainingBd.multiply(monthlyRate, HIGH_PRECISION),
                currency,
            )
            val principalMinor = if (period == totalPeriods) {
                remainingMinor
            } else {
                val rawPrincipal = Math.subtractExact(fixedPaymentMinor, interestMinor)
                rawPrincipal.coerceIn(0L, remainingMinor)
            }
            remainingMinor = Math.subtractExact(remainingMinor, principalMinor)
            rows += Triple(principalMinor, interestMinor, remainingMinor)
        }
        return rows
    }

    private fun computeEqualPrincipalRows(
        totalPrincipalMinor: Long,
        totalPeriods: Int,
        annualRateDecimal: String?,
        currency: Currency,
    ): List<Triple<Long, Long, Long>> {
        val annualRate = parseNonNegativeDecimalOrZero(annualRateDecimal)
        val hasInterest = annualRate.compareTo(BigDecimal.ZERO) > 0
        val monthlyRate = if (hasInterest) {
            annualRate.divide(MONTHS_PER_YEAR, HIGH_PRECISION)
        } else {
            BigDecimal.ZERO
        }

        val principalBd = Money(totalPrincipalMinor, currency).toBigDecimal()
        val basePrincipalMinor = roundMajorToCentMinor(
            principalBd.divide(BigDecimal.valueOf(totalPeriods.toLong()), HIGH_PRECISION),
            currency,
        )

        val rows = ArrayList<Triple<Long, Long, Long>>(totalPeriods)
        var remainingMinor = totalPrincipalMinor

        for (period in 1..totalPeriods) {
            val interestMinor = if (hasInterest && remainingMinor > 0L) {
                val remainingBd = Money(remainingMinor, currency).toBigDecimal()
                roundMajorToCentMinor(
                    remainingBd.multiply(monthlyRate, HIGH_PRECISION),
                    currency,
                )
            } else {
                0L
            }
            val principalMinor = if (period == totalPeriods) {
                remainingMinor
            } else {
                basePrincipalMinor.coerceIn(0L, remainingMinor)
            }
            remainingMinor = Math.subtractExact(remainingMinor, principalMinor)
            rows += Triple(principalMinor, interestMinor, remainingMinor)
        }
        return rows
    }

    private fun computeFixedFeeRows(
        totalPrincipalMinor: Long,
        totalPeriods: Int,
        fixedFeePerPeriodMinor: Long?,
        fixedFeeRateDecimal: String?,
        currency: Currency,
    ): List<Triple<Long, Long, Long>> {
        val principalBd = Money(totalPrincipalMinor, currency).toBigDecimal()
        val perPeriodFeeMinor: Long = when {
            fixedFeePerPeriodMinor != null -> fixedFeePerPeriodMinor
            !fixedFeeRateDecimal.isNullOrBlank() -> {
                val rate = parseNonNegativeDecimalOrZero(fixedFeeRateDecimal)
                if (rate.compareTo(BigDecimal.ZERO) == 0) {
                    0L
                } else {
                    roundMajorToCentMinor(principalBd.multiply(rate, HIGH_PRECISION), currency)
                }
            }
            else -> 0L
        }

        val basePrincipalMinor = roundMajorToCentMinor(
            principalBd.divide(BigDecimal.valueOf(totalPeriods.toLong()), HIGH_PRECISION),
            currency,
        )

        val rows = ArrayList<Triple<Long, Long, Long>>(totalPeriods)
        var remainingMinor = totalPrincipalMinor

        for (period in 1..totalPeriods) {
            val principalMinor = if (period == totalPeriods) {
                remainingMinor
            } else {
                basePrincipalMinor.coerceIn(0L, remainingMinor)
            }
            remainingMinor = Math.subtractExact(remainingMinor, principalMinor)
            rows += Triple(principalMinor, perPeriodFeeMinor, remainingMinor)
        }
        return rows
    }

    private fun computeZeroInterestEqualRows(
        totalPrincipalMinor: Long,
        totalPeriods: Int,
        currency: Currency,
    ): List<Triple<Long, Long, Long>> {
        val principalBd = Money(totalPrincipalMinor, currency).toBigDecimal()
        val basePrincipalMinor = roundMajorToCentMinor(
            principalBd.divide(BigDecimal.valueOf(totalPeriods.toLong()), HIGH_PRECISION),
            currency,
        )
        val rows = ArrayList<Triple<Long, Long, Long>>(totalPeriods)
        var remainingMinor = totalPrincipalMinor
        for (period in 1..totalPeriods) {
            val principalMinor = if (period == totalPeriods) {
                remainingMinor
            } else {
                basePrincipalMinor.coerceIn(0L, remainingMinor)
            }
            remainingMinor = Math.subtractExact(remainingMinor, principalMinor)
            rows += Triple(principalMinor, 0L, remainingMinor)
        }
        return rows
    }

    private fun parseNonNegativeDecimalOrZero(raw: String?): BigDecimal {
        if (raw.isNullOrBlank()) return BigDecimal.ZERO
        val parsed = BigDecimal(raw.trim())
        require(parsed.compareTo(BigDecimal.ZERO) >= 0) {
            "Rate must be non-negative, got '$raw'"
        }
        return parsed
    }

    private fun roundMajorToCentMinor(valueMajor: BigDecimal, currency: Currency): Long {
        val roundedMajor = valueMajor.setScale(CENT_DISPLAY_SCALE, RoundingMode.HALF_UP)
        return Money.fromBigDecimal(roundedMajor, currency).amountMinor
    }
}
