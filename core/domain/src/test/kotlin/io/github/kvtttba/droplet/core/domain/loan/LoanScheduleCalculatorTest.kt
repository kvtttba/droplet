package io.github.kvtttba.droplet.core.domain.loan

import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.InstallmentStatus
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.LoanPlan
import io.github.kvtttba.droplet.core.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoanScheduleCalculatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun epochOn(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun toLocalDate(epochMs: Long): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    private fun planTemplate(
        id: String = "loan-1",
        method: LoanMethod = LoanMethod.EQUAL_INSTALLMENT,
        principalDecimal: String = "120000.00",
        periods: Int = 12,
        annualRateDecimal: String? = "0.048",
        fixedFeePerPeriodDecimal: String? = null,
        fixedFeeRateDecimal: String? = null,
        firstDueDate: LocalDate = LocalDate.of(2026, 1, 15),
        monthlyRepayDay: Int = firstDueDate.dayOfMonth,
        autoPost: Boolean = false,
        remindDaysBefore: Int? = 3,
        currency: Currency = Currency.CNY,
    ): LoanPlan = LoanPlan(
        id = id,
        name = "测试分期贷款",
        method = method,
        currency = currency,
        totalPrincipalMinor = Money.fromDecimalString(principalDecimal, currency).amountMinor,
        totalPeriods = periods,
        annualRateDecimal = annualRateDecimal,
        fixedFeePerPeriodMinor = fixedFeePerPeriodDecimal?.let { Money.fromDecimalString(it, currency).amountMinor },
        fixedFeeRateDecimal = fixedFeeRateDecimal,
        firstDueAt = firstDueDate.atStartOfDay(zone).toInstant().toEpochMilli(),
        monthlyRepayDay = monthlyRepayDay,
        loanAccountId = "acct:loan:1",
        paymentAccountId = "acct:debit",
        interestExpenseAccountId = "cat:interest",
        autoPost = autoPost,
        remindDaysBefore = remindDaysBefore,
    )

    @Test
    fun equalInstallment_12MonthsStandardRate_matchesAnnuityFormulaAndZerosTailResidual() {
        // Principal = 120,000 CNY, 12 months, 4.8% annual -> monthly rate = 0.4% (0.004)
        // Exact PMT = 120000 * 0.004 * 1.004^12 / (1.004^12 - 1) = 10261.90
        val plan = planTemplate(
            method = LoanMethod.EQUAL_INSTALLMENT,
            principalDecimal = "120000.00",
            periods = 12,
            annualRateDecimal = "0.048",
        )
        val schedule = LoanScheduleCalculator.generateSchedule(plan, zoneId = zone)

        assertEquals(12, schedule.size)
        assertEquals(plan.totalPrincipalMinor, schedule.sumOf { it.principalMinor })
        assertEquals(0L, schedule.last().remainingPrincipalMinor)

        // Period 1: interest = 120000 * 0.004 = 480.00, total = 10261.90, principal = 9781.90
        assertEquals(Money.fromDecimalString("480.00", Currency.CNY).amountMinor, schedule[0].interestOrFeeMinor)
        assertEquals(Money.fromDecimalString("9781.90", Currency.CNY).amountMinor, schedule[0].principalMinor)
        assertEquals(Money.fromDecimalString("10261.90", Currency.CNY).amountMinor, schedule[0].totalDueMinor)

        // Periods 1..11 share exact fixed payment 10,261.90 and strictly decreasing interest
        val expectedFixedPayment = Money.fromDecimalString("10261.90", Currency.CNY).amountMinor
        for (i in 0 until 11) {
            assertEquals(expectedFixedPayment, schedule[i].totalDueMinor)
            if (i > 0) {
                assertTrue(schedule[i].interestOrFeeMinor < schedule[i - 1].interestOrFeeMinor)
                assertTrue(schedule[i].principalMinor > schedule[i - 1].principalMinor)
            }
        }
        // Final period tail adjustment keeps total payment within 1 yuan of fixed payment
        assertTrue(kotlin.math.abs(schedule.last().totalDueMinor - expectedFixedPayment) <= 10_000L)
    }

    @Test
    fun equalInstallment_30YearMortgage360Periods_preservesExactPrincipalSumWithZeroResidual() {
        val plan = planTemplate(
            method = LoanMethod.EQUAL_INSTALLMENT,
            principalDecimal = "1000000.00",
            periods = 360,
            annualRateDecimal = "0.036",
        )
        val schedule = LoanScheduleCalculator.generateSchedule(plan, zoneId = zone)

        assertEquals(360, schedule.size)
        assertEquals(plan.totalPrincipalMinor, schedule.sumOf { it.principalMinor })
        assertEquals(0L, schedule.last().remainingPrincipalMinor)
        assertTrue(schedule.all { it.principalMinor > 0L && it.interestOrFeeMinor >= 0L })
        // 1,000,000 @ 3.6% for 360m -> monthly payment = 4546.45 CNY
        assertEquals(Money.fromDecimalString("4546.45", Currency.CNY).amountMinor, schedule.first().totalDueMinor)
    }

    @Test
    fun equalInstallment_zeroRate_splitsPrincipalWithTailAdjustmentAndZeroInterest() {
        val plan = planTemplate(
            method = LoanMethod.EQUAL_INSTALLMENT,
            principalDecimal = "100.00",
            periods = 3,
            annualRateDecimal = "0",
        )
        val schedule = LoanScheduleCalculator.generateSchedule(plan, zoneId = zone)

        assertEquals(3, schedule.size)
        assertEquals(Money.fromDecimalString("33.33", Currency.CNY).amountMinor, schedule[0].principalMinor)
        assertEquals(Money.fromDecimalString("33.33", Currency.CNY).amountMinor, schedule[1].principalMinor)
        assertEquals(Money.fromDecimalString("33.34", Currency.CNY).amountMinor, schedule[2].principalMinor)
        assertTrue(schedule.all { it.interestOrFeeMinor == 0L })
        assertEquals(plan.totalPrincipalMinor, schedule.sumOf { it.principalMinor })
        assertEquals(0L, schedule.last().remainingPrincipalMinor)
    }

    @Test
    fun equalPrincipal_nonDivisibleAmountAndRate_decreasesInterestAndClosesResidual() {
        val plan = planTemplate(
            method = LoanMethod.EQUAL_PRINCIPAL,
            principalDecimal = "10000.00",
            periods = 3,
            annualRateDecimal = "0.06", // 0.5% monthly
        )
        val schedule = LoanScheduleCalculator.generateSchedule(plan, zoneId = zone)

        assertEquals(3, schedule.size)
        assertEquals(Money.fromDecimalString("3333.33", Currency.CNY).amountMinor, schedule[0].principalMinor)
        assertEquals(Money.fromDecimalString("3333.33", Currency.CNY).amountMinor, schedule[1].principalMinor)
        assertEquals(Money.fromDecimalString("3333.34", Currency.CNY).amountMinor, schedule[2].principalMinor)
        // Interest: p1 = 10000 * 0.005 = 50.00; p2 = 6666.67 * 0.005 = 33.33; p3 = 3333.34 * 0.005 = 16.67
        assertEquals(Money.fromDecimalString("50.00", Currency.CNY).amountMinor, schedule[0].interestOrFeeMinor)
        assertEquals(Money.fromDecimalString("33.33", Currency.CNY).amountMinor, schedule[1].interestOrFeeMinor)
        assertEquals(Money.fromDecimalString("16.67", Currency.CNY).amountMinor, schedule[2].interestOrFeeMinor)
        assertEquals(plan.totalPrincipalMinor, schedule.sumOf { it.principalMinor })
        assertEquals(0L, schedule.last().remainingPrincipalMinor)
    }

    @Test
    fun fixedFee_supportsFixedAmountPerPeriodRatePerPeriodAndZeroFee() {
        // 1. Explicit fixed fee per period
        val fixedAmountPlan = planTemplate(
            method = LoanMethod.FIXED_FEE,
            principalDecimal = "6000.00",
            periods = 6,
            annualRateDecimal = null,
            fixedFeePerPeriodDecimal = "30.00",
        )
        val fixedAmountRows = LoanScheduleCalculator.generateSchedule(fixedAmountPlan, zoneId = zone)
        assertEquals(6, fixedAmountRows.size)
        assertTrue(fixedAmountRows.all { it.principalMinor == Money.fromDecimalString("1000.00", Currency.CNY).amountMinor })
        assertTrue(fixedAmountRows.all { it.interestOrFeeMinor == Money.fromDecimalString("30.00", Currency.CNY).amountMinor })
        assertEquals(0L, fixedAmountRows.last().remainingPrincipalMinor)

        // 2. Per-period fee rate (0.6% per period on 10,000 = 60.00 per period, 3 periods -> 3333.33 / 3333.33 / 3333.34)
        val ratePlan = planTemplate(
            method = LoanMethod.FIXED_FEE,
            principalDecimal = "10000.00",
            periods = 3,
            annualRateDecimal = null,
            fixedFeeRateDecimal = "0.006",
        )
        val rateRows = LoanScheduleCalculator.generateSchedule(ratePlan, zoneId = zone)
        assertEquals(Money.fromDecimalString("60.00", Currency.CNY).amountMinor, rateRows[0].interestOrFeeMinor)
        assertEquals(Money.fromDecimalString("60.00", Currency.CNY).amountMinor, rateRows[2].interestOrFeeMinor)
        assertEquals(Money.fromDecimalString("3333.34", Currency.CNY).amountMinor, rateRows[2].principalMinor)
        assertEquals(ratePlan.totalPrincipalMinor, rateRows.sumOf { it.principalMinor })
        assertEquals(0L, rateRows.last().remainingPrincipalMinor)

        // 3. Zero-fee installment
        val zeroFeePlan = planTemplate(
            method = LoanMethod.FIXED_FEE,
            principalDecimal = "1000.00",
            periods = 3,
            annualRateDecimal = null,
        )
        val zeroRows = LoanScheduleCalculator.generateSchedule(zeroFeePlan, zoneId = zone)
        assertTrue(zeroRows.all { it.interestOrFeeMinor == 0L })
        assertEquals(zeroFeePlan.totalPrincipalMinor, zeroRows.sumOf { it.principalMinor })
    }

    @Test
    fun monthEndClamping_handlesDay31AcrossNonLeapAndLeapFebruaryAnd30DayMonths() {
        // Non-leap year 2026: Jan 31 -> Feb 28 -> Mar 31 -> Apr 30 -> May 31
        val plan2026 = planTemplate(
            principalDecimal = "5000.00",
            periods = 5,
            annualRateDecimal = "0",
            firstDueDate = LocalDate.of(2026, 1, 31),
            monthlyRepayDay = 31,
        )
        val dates2026 = LoanScheduleCalculator.generateSchedule(plan2026, zoneId = zone)
            .map { toLocalDate(it.dueAt) }
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 5, 31),
            ),
            dates2026,
        )

        // Leap year 2028: Jan 31 -> Feb 29 -> Mar 31 -> Apr 30
        val plan2028 = planTemplate(
            principalDecimal = "4000.00",
            periods = 4,
            annualRateDecimal = "0",
            firstDueDate = LocalDate.of(2028, 1, 31),
            monthlyRepayDay = 31,
        )
        val dates2028 = LoanScheduleCalculator.generateSchedule(plan2028, zoneId = zone)
            .map { toLocalDate(it.dueAt) }
        assertEquals(
            listOf(
                LocalDate.of(2028, 1, 31),
                LocalDate.of(2028, 2, 29),
                LocalDate.of(2028, 3, 31),
                LocalDate.of(2028, 4, 30),
            ),
            dates2028,
        )

        // Starting in Feb 2026 with monthlyRepayDay = 30 -> Feb 28, Mar 30, Apr 30
        val planStartFeb = planTemplate(
            principalDecimal = "3000.00",
            periods = 3,
            annualRateDecimal = "0",
            firstDueDate = LocalDate.of(2026, 2, 28),
            monthlyRepayDay = 30,
        )
        val datesFeb = LoanScheduleCalculator.generateSchedule(planStartFeb, zoneId = zone)
            .map { toLocalDate(it.dueAt) }
        assertEquals(
            listOf(
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 30),
                LocalDate.of(2026, 4, 30),
            ),
            datesFeb,
        )
    }

    @Test
    fun summarizeAndEarlyPayoff_computesStatusReminderWindowAndInterestSavings() {
        val plan = planTemplate(
            method = LoanMethod.FIXED_FEE,
            principalDecimal = "6000.00",
            periods = 6,
            annualRateDecimal = null,
            fixedFeePerPeriodDecimal = "50.00",
            firstDueDate = LocalDate.of(2026, 10, 20),
            monthlyRepayDay = 20,
            remindDaysBefore = 3,
        )
        val schedule = LoanScheduleCalculator.generateSchedule(plan, zoneId = zone).toMutableList()
        // Mark period 1 (Oct 20) PAID
        schedule[0] = schedule[0].copy(
            status = InstallmentStatus.PAID,
            transactionId = "tx-p1",
            paidAt = epochOn(2026, 10, 20),
        )

        // As of Nov 16 (4 days before Nov 20) -> ACTIVE, outside 3-day reminder window
        val activeSummary = LoanScheduleCalculator.summarize(
            plan = plan,
            installments = schedule,
            asOfDate = LocalDate.of(2026, 11, 16),
            zoneId = zone,
        )
        assertEquals(LoanPlanStatus.ACTIVE, activeSummary.status)
        assertFalse(activeSummary.isInReminderWindow)
        assertNull(activeSummary.reminderCandidateInstallment)
        assertEquals(1, activeSummary.paidPeriods)
        assertEquals(5, activeSummary.remainingPeriods)
        assertEquals(Money.fromDecimalString("5000.00", Currency.CNY), activeSummary.remainingPrincipal)
        assertEquals(Money.fromDecimalString("250.00", Currency.CNY), activeSummary.remainingInterest)
        assertEquals(4, activeSummary.daysUntilNextDue)
        assertTrue(activeSummary.dueNowInstallments.isEmpty())

        // As of Nov 17 (exactly 3 days before Nov 20) -> DUE_SOON, inside reminder window
        val dueSoonSummary = LoanScheduleCalculator.summarize(
            plan = plan,
            installments = schedule,
            asOfDate = LocalDate.of(2026, 11, 17),
            zoneId = zone,
        )
        assertEquals(LoanPlanStatus.DUE_SOON, dueSoonSummary.status)
        assertTrue(dueSoonSummary.isInReminderWindow)
        assertEquals(2, dueSoonSummary.reminderCandidateInstallment?.periodNumber)

        // As of Nov 22 (2 days past Nov 20) -> OVERDUE, dueNowInstallments contains period 2
        val overdueSummary = LoanScheduleCalculator.summarize(
            plan = plan,
            installments = schedule,
            asOfDate = LocalDate.of(2026, 11, 22),
            zoneId = zone,
        )
        assertEquals(LoanPlanStatus.OVERDUE, overdueSummary.status)
        assertEquals(2, overdueSummary.overdueDays)
        assertTrue(overdueSummary.isInReminderWindow)
        assertEquals(listOf(2), overdueSummary.dueNowInstallments.map { it.periodNumber })

        // Early payoff quote with ¥40.00 prepayment fee -> outflow ¥5,040.00, net interest saved ¥210.00
        val quote = LoanScheduleCalculator.calculateEarlyPayoff(
            plan = plan,
            installments = schedule,
            extraFeeOrInterest = Money.fromDecimalString("40.00", Currency.CNY),
        )
        assertEquals(5, quote.remainingPendingPeriods)
        assertEquals(Money.fromDecimalString("5000.00", Currency.CNY), quote.remainingPrincipal)
        assertEquals(Money.fromDecimalString("250.00", Currency.CNY), quote.waivedFutureInterest)
        assertEquals(Money.fromDecimalString("5040.00", Currency.CNY), quote.totalPayoffOutflow)
        assertEquals(Money.fromDecimalString("210.00", Currency.CNY), quote.netInterestSaved)

        // Mark all remaining periods PAID -> PAID_OFF
        val allPaid = schedule.map { it.copy(status = InstallmentStatus.PAID, transactionId = "tx-${it.periodNumber}") }
        val paidOffSummary = LoanScheduleCalculator.summarize(
            plan = plan,
            installments = allPaid,
            asOfDate = LocalDate.of(2026, 11, 22),
            zoneId = zone,
        )
        assertEquals(LoanPlanStatus.PAID_OFF, paidOffSummary.status)
        assertTrue(paidOffSummary.isPaidOff)
        assertEquals(Money.zero(Currency.CNY), paidOffSummary.remainingPrincipal)
        assertNull(paidOffSummary.nextPendingInstallment)
        assertNotNull(paidOffSummary.repaidProgressPctDecimal)
        assertEquals("100", paidOffSummary.repaidProgressPctDecimal)
    }
}
