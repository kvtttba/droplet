package io.github.kvtttba.droplet.notification

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementCalculator
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
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
class CreditCardReminderNotificationPublisherTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var application: Application
    private lateinit var publisher: CreditCardReminderNotificationPublisher

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        publisher = CreditCardReminderNotificationPublisher(application)
    }

    private fun sampleDueSummary(asOfDate: LocalDate = LocalDate.of(2026, 11, 23)) =
        CreditCardStatementCalculator.calculate(
            account = Account(
                id = "card:cmb",
                name = "招行信用卡",
                type = AccountType.LIABILITY,
                subType = AccountSubType.CREDIT_CARD,
                currency = Currency.CNY,
                creditLimitMinor = Money.fromDecimalString("50000.00", Currency.CNY).amountMinor,
                billingDay = 5,
                dueDay = 25,
                remindDaysBefore = 3,
            ),
            transactions = listOf(
                LedgerEngine(Currency.CNY).buildExpense(
                    meta = TransactionMeta(
                        occurredAt = LocalDate.of(2026, 10, 20).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                    ),
                    expenseCategoryAccountId = "cat:food",
                    paymentAccountId = "card:cmb",
                    amount = Money.fromDecimalString("3280.00", Currency.CNY),
                ),
            ),
            asOfDate = asOfDate,
            zoneId = zone,
        )

    @Test
    fun ensureChannelAndPublish_respectsPermissionAndPrivacyMask() {
        val manager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowManager = Shadows.shadowOf(manager)
        val shadowApp = Shadows.shadowOf(application)

        // Without POST_NOTIFICATIONS on API 35, publish returns false gracefully
        shadowApp.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val summary = sampleDueSummary()
        assertFalse(publisher.canPostNotifications())
        assertFalse(publisher.publishReminder(summary, hideAmounts = false))
        assertNotNull(manager.getNotificationChannel(CreditCardReminderNotificationPublisher.CHANNEL_ID))
        assertEquals(0, shadowManager.allNotifications.size)

        // Grant permission -> notification is published with exact channel & content
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(publisher.canPostNotifications())
        assertTrue(publisher.publishReminder(summary, hideAmounts = false))
        assertEquals(1, shadowManager.allNotifications.size)

        // Verify content formatting for normal, privacy-hidden, and overdue states
        val normalContent = CreditCardReminderNotificationPublisher.buildNotificationContent(summary, hideAmounts = false)
        assertTrue(normalContent.title.contains("招行信用卡"))
        assertTrue(normalContent.title.contains("2 天"))
        assertTrue(normalContent.body.contains("3,280.00"))

        val hiddenContent = CreditCardReminderNotificationPublisher.buildNotificationContent(summary, hideAmounts = true)
        assertFalse(hiddenContent.body.contains("3,280.00"))
        assertTrue(hiddenContent.body.contains("隐藏"))

        val overdueContent = CreditCardReminderNotificationPublisher.buildNotificationContent(
            sampleDueSummary(asOfDate = LocalDate.of(2026, 11, 27)),
            hideAmounts = false,
        )
        assertTrue(overdueContent.title.contains("已逾期 2 天"))
    }
}
