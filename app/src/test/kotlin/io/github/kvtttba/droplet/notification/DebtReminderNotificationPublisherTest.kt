package io.github.kvtttba.droplet.notification

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.domain.debt.DebtSummaryCalculator
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DebtProfile
import io.github.kvtttba.droplet.core.model.Money
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DebtReminderNotificationPublisherTest {

    private val zoneId = DebtSummaryCalculator.DEFAULT_ZONE_ID

    @Test
    fun buildDueReminderContent_respectsHideAmountsPrivacyFlag() {
        val recv = Account(
            id = "debt:recv:zhang",
            name = "张三 · 应收",
            type = AccountType.ASSET,
            subType = AccountSubType.RECEIVABLE,
            currency = Currency.CNY,
        )
        val pay = Account(
            id = "debt:pay:zhang",
            name = "张三 · 应付",
            type = AccountType.LIABILITY,
            subType = AccountSubType.PAYABLE,
            currency = Currency.CNY,
        )
        val debit = Account(
            id = "acct:debit",
            name = "储蓄卡",
            type = AccountType.ASSET,
            subType = AccountSubType.DEBIT_CARD,
            currency = Currency.CNY,
        )
        val dueMs = LocalDate.of(2026, 11, 30).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val profile = DebtProfile(
            id = "debt:zhang",
            name = "张三",
            receivableAccountId = recv.id,
            payableAccountId = pay.id,
            defaultDueAt = dueMs,
        )
        val lendTx = LedgerEngine(Currency.CNY).buildDebtLend(
            meta = TransactionMeta(occurredAt = dueMs - 10 * 86_400_000L),
            receivableAccountId = recv.id,
            fromAccountId = debit.id,
            amount = Money.fromDecimalString("1250.00", Currency.CNY),
        )
        val summary = DebtSummaryCalculator.summarize(
            profile = profile,
            receivableAccount = recv,
            payableAccount = pay,
            transactions = listOf(lendTx),
            accountsById = mapOf(recv.id to recv, pay.id to pay, debit.id to debit),
            asOfDate = LocalDate.of(2026, 11, 28),
            remindDaysBefore = 3,
            zoneId = zoneId,
        )

        val visibleContent = DebtReminderNotificationPublisher.buildDueReminderContent(
            summary = summary,
            hideAmounts = false,
        )
        assertTrue(visibleContent.title.contains("张三"))
        assertTrue(visibleContent.title.contains("2 天"))
        assertTrue(visibleContent.body.contains("1,250.00"))

        val hiddenContent = DebtReminderNotificationPublisher.buildDueReminderContent(
            summary = summary,
            hideAmounts = true,
        )
        assertTrue(hiddenContent.body.contains("隐私设置隐藏"))
        assertFalse(hiddenContent.body.contains("1,250"))
    }

    @Test
    fun ensureNotificationChannel_createsDedicatedDebtRemindersChannel() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val publisher = DebtReminderNotificationPublisher(context)
        publisher.ensureNotificationChannel()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val channel = manager.getNotificationChannel(DebtReminderNotificationPublisher.CHANNEL_ID)
        assertTrue(channel != null)
        assertTrue(channel!!.name.toString().contains("往来借贷"))
    }
}
