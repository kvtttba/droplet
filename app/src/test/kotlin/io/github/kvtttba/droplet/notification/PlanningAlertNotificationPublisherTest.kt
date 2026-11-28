package io.github.kvtttba.droplet.notification

import io.github.kvtttba.droplet.core.domain.automation.BudgetAlertLevel
import io.github.kvtttba.droplet.core.domain.automation.BudgetPeriodRange
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgress
import io.github.kvtttba.droplet.core.domain.automation.RecurringPayload
import io.github.kvtttba.droplet.core.domain.automation.RecurringPayloadCodec
import io.github.kvtttba.droplet.core.model.Budget
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.RecurringFrequency
import io.github.kvtttba.droplet.core.model.RecurringRule
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningAlertNotificationPublisherTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun budgetAlertContent_hidesAmountsWhenPrivacyMaskEnabled() {
        val progress = BudgetProgress(
            budget = Budget(
                id = "b:total",
                period = BudgetPeriod.MONTHLY,
                limitAmountMinor = Money.fromDecimalString("2000.00").amountMinor,
                warnThresholdPct = 80,
            ),
            categoryName = null,
            categoryIcon = null,
            categoryColor = null,
            periodRange = BudgetPeriodRange(
                period = BudgetPeriod.MONTHLY,
                periodKey = "2026-11",
                startInclusive = 0L,
                endExclusive = 100L,
                totalDays = 30,
                elapsedDays = 20,
                remainingDays = 10,
            ),
            spentAmount = Money.fromDecimalString("1700.00"),
            remainingAmount = Money.fromDecimalString("300.00"),
            usedPercentageDecimal = "85.0",
            usedPercentageInt = 85,
            progressFraction = 0.85f,
            dailyAvailableAmount = Money.fromDecimalString("30.00"),
            alertLevel = BudgetAlertLevel.WARNING,
        )

        val visible = PlanningAlertNotificationPublisher.buildBudgetAlertContent(progress, hideAmounts = false)
        assertTrue(visible.body.contains("1,700.00"))
        assertTrue(visible.body.contains("2,000.00"))
        assertTrue(visible.body.contains("30.00"))

        val hidden = PlanningAlertNotificationPublisher.buildBudgetAlertContent(progress, hideAmounts = true)
        assertFalse(hidden.body.contains("1,700"))
        assertFalse(hidden.body.contains("2,000"))
        assertTrue(hidden.body.contains("隐私"))
    }

    @Test
    fun recurringNotificationContent_hidesAmountsWhenPrivacyMaskEnabled() {
        val payload = RecurringPayload(
            displayType = TransactionDisplayType.EXPENSE,
            amountMinor = Money.fromDecimalString("3500.00", Currency.CNY).amountMinor,
            categoryAccountId = "cat:rent",
            accountId = "acct:debit",
        )
        val rule = RecurringRule(
            id = "rec:rent",
            name = "每月房租",
            frequency = RecurringFrequency.MONTHLY,
            nextRunAt = LocalDate.of(2026, 11, 25).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
            templatePayloadJson = RecurringPayloadCodec.encode(payload),
            autoPost = false,
        )

        val visibleAuto = PlanningAlertNotificationPublisher.buildRecurringAutoPostedContent(rule, payload, hideAmounts = false)
        assertTrue(visibleAuto.body.contains("3,500.00"))

        val hiddenAuto = PlanningAlertNotificationPublisher.buildRecurringAutoPostedContent(rule, payload, hideAmounts = true)
        assertFalse(hiddenAuto.body.contains("3,500"))
        assertTrue(hiddenAuto.body.contains("隐私"))

        val visiblePending = PlanningAlertNotificationPublisher.buildRecurringPendingContent(rule, payload, hideAmounts = false, zoneId = zone)
        assertTrue(visiblePending.body.contains("3,500.00"))
        assertTrue(visiblePending.body.contains("11月25日"))

        val hiddenPending = PlanningAlertNotificationPublisher.buildRecurringPendingContent(rule, payload, hideAmounts = true, zoneId = zone)
        assertFalse(hiddenPending.body.contains("3,500"))
        assertTrue(hiddenPending.body.contains("隐私"))
    }
}
