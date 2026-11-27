package io.github.kvtttba.droplet.notification

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.domain.loan.LoanScheduleCalculator
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.LoanPlan
import io.github.kvtttba.droplet.core.model.Money
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LoanReminderNotificationPublisherTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var application: Application
    private lateinit var publisher: LoanReminderNotificationPublisher

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        publisher = LoanReminderNotificationPublisher(application)
    }

    private fun samplePlan(): LoanPlan = LoanPlan(
        id = "loan:car",
        name = "新能源车贷",
        method = LoanMethod.FIXED_FEE,
        currency = Currency.CNY,
        totalPrincipalMinor = Money.fromDecimalString("24000.00", Currency.CNY).amountMinor,
        totalPeriods = 12,
        fixedFeePerPeriodMinor = Money.fromDecimalString("80.00", Currency.CNY).amountMinor,
        firstDueAt = LocalDate.of(2026, 11, 28).atStartOfDay(zone).toInstant().toEpochMilli(),
        monthlyRepayDay = 28,
        loanAccountId = "acct:loan:car",
        paymentAccountId = "acct:debit",
        interestExpenseAccountId = "cat:interest",
        autoPost = true,
        remindDaysBefore = 3,
    )

    @Test
    fun ensureChannelAndPublish_respectsPermissionPrivacyMaskAndDeepLinkExtra() {
        val manager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowManager = Shadows.shadowOf(manager)
        val shadowApp = Shadows.shadowOf(application)

        val plan = samplePlan()
        val schedule = LoanScheduleCalculator.generateSchedule(plan, zoneId = zone)
        val dueSoonSummary = LoanScheduleCalculator.summarize(
            plan = plan,
            installments = schedule,
            asOfDate = LocalDate.of(2026, 11, 26),
            zoneId = zone,
        )
        val firstInst = schedule.first()

        // Without POST_NOTIFICATIONS on API 35, publish returns false gracefully
        shadowApp.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(publisher.canPostNotifications())
        assertFalse(publisher.publishDueReminder(dueSoonSummary, firstInst, hideAmounts = false))
        assertNotNull(manager.getNotificationChannel(LoanReminderNotificationPublisher.CHANNEL_ID))
        assertEquals(0, shadowManager.allNotifications.size)

        // Grant permission -> both due reminder & auto-post notice publish cleanly
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(publisher.canPostNotifications())
        assertTrue(publisher.publishDueReminder(dueSoonSummary, firstInst, hideAmounts = false))
        assertTrue(publisher.publishAutoPostedNotice(plan, firstInst, hideAmounts = false))
        assertEquals(2, shadowManager.allNotifications.size)

        // Verify deep-link PendingIntent carries EXTRA_LOAN_PLAN_ID
        val firstNotif = shadowManager.allNotifications.first()
        val shadowPending = Shadows.shadowOf(firstNotif.contentIntent)
        assertEquals(
            plan.id,
            shadowPending.savedIntent.getStringExtra(LoanReminderNotificationPublisher.EXTRA_LOAN_PLAN_ID),
        )

        // Verify normal vs privacy-hidden vs overdue text formatting
        val normalContent = LoanReminderNotificationPublisher.buildDueReminderContent(
            summary = dueSoonSummary,
            installment = firstInst,
            hideAmounts = false,
            zoneId = zone,
        )
        assertTrue(normalContent.title.contains("新能源车贷"))
        assertTrue(normalContent.title.contains("2 天"))
        assertTrue(normalContent.body.contains("2,080.00"))

        val hiddenContent = LoanReminderNotificationPublisher.buildDueReminderContent(
            summary = dueSoonSummary,
            installment = firstInst,
            hideAmounts = true,
            zoneId = zone,
        )
        assertFalse(hiddenContent.body.contains("2,080.00"))
        assertTrue(hiddenContent.body.contains("隐藏"))

        val autoHidden = LoanReminderNotificationPublisher.buildAutoPostedContent(
            plan = plan,
            installment = firstInst,
            hideAmounts = true,
        )
        assertFalse(autoHidden.body.contains("2,080.00"))
        assertTrue(autoHidden.title.contains("自动入账"))

        val overdueSummary = LoanScheduleCalculator.summarize(
            plan = plan,
            installments = schedule,
            asOfDate = LocalDate.of(2026, 11, 30),
            zoneId = zone,
        )
        val overdueContent = LoanReminderNotificationPublisher.buildDueReminderContent(
            summary = overdueSummary,
            installment = firstInst,
            hideAmounts = false,
            zoneId = zone,
        )
        assertTrue(overdueContent.title.contains("已逾期 2 天"))
    }
}
