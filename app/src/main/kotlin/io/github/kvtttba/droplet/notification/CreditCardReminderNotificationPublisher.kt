package io.github.kvtttba.droplet.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.kvtttba.droplet.MainActivity
import io.github.kvtttba.droplet.R
import io.github.kvtttba.droplet.core.domain.PrivacyAmountFormatter
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementStatus
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementSummary
import java.time.format.DateTimeFormatter

/**
 * Abstraction for posting local Android repayment reminder notifications for credit cards.
 */
interface CreditCardReminderNotifier {
    fun ensureNotificationChannel()
    fun canPostNotifications(): Boolean
    fun publishReminder(
        summary: CreditCardStatementSummary,
        hideAmounts: Boolean = false,
    ): Boolean
}

/**
 * Local Android notification publisher for credit-card billing & due-date reminders.
 *
 * - Uses dedicated channel [CHANNEL_ID] (`droplet_credit_card_reminders`)
 * - Checks both app-level notification enablement and Android 13+ `POST_NOTIFICATIONS` permission
 * - Respects global privacy masking (`hideAmounts`) so sensitive amounts are never leaked when hidden
 * - Attaches a deep-link [PendingIntent] opening [MainActivity] directly to the target credit card detail screen
 */
class CreditCardReminderNotificationPublisher(
    private val context: Context,
) : CreditCardReminderNotifier {

    override fun ensureNotificationChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = CHANNEL_DESCRIPTION
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    override fun canPostNotifications(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    override fun publishReminder(
        summary: CreditCardStatementSummary,
        hideAmounts: Boolean,
    ): Boolean {
        ensureNotificationChannel()
        if (!canPostNotifications()) {
            return false
        }

        val content = buildNotificationContent(summary = summary, hideAmounts = hideAmounts)
        val notificationId = notificationIdForAccount(summary.account.id)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CREDIT_CARD_ACCOUNT_ID, summary.account.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_droplet)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        }.getOrDefault(false)
    }

    data class ReminderNotificationContent(
        val title: String,
        val body: String,
    )

    companion object {
        const val CHANNEL_ID: String = "droplet_credit_card_reminders"
        const val CHANNEL_NAME: String = "信用卡还款提醒"
        const val CHANNEL_DESCRIPTION: String = "信用卡临近还款日或逾期未还时的本地提醒"
        const val EXTRA_CREDIT_CARD_ACCOUNT_ID: String = "droplet_credit_card_account_id"

        private val monthDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

        fun notificationIdForAccount(accountId: String): Int =
            31_000 + (accountId.hashCode() and 0x7FFF)

        fun buildNotificationContent(
            summary: CreditCardStatementSummary,
            hideAmounts: Boolean = false,
        ): ReminderNotificationContent {
            val cardName = summary.account.name
            val dueDateLabel = summary.currentStatementDueDate?.format(monthDayFormatter)
            val title = when {
                summary.status == CreditCardStatementStatus.OVERDUE ->
                    "$cardName · 账单已逾期 ${summary.overdueDays} 天"
                summary.isDueToday ->
                    "$cardName · 今日到期还款"
                summary.daysUntilCurrentDue != null ->
                    "$cardName · 距还款日还有 ${summary.daysUntilCurrentDue} 天"
                else ->
                    "$cardName · 还款提醒"
            }

            val body = if (hideAmounts) {
                buildString {
                    append("本期账单仍有待还金额")
                    if (dueDateLabel != null) {
                        append("（还款日 $dueDateLabel）")
                    }
                    append("，已按隐私设置隐藏具体金额，点击查看并一键还款。")
                }
            } else {
                val formattedDue = PrivacyAmountFormatter.format(
                    money = summary.remainingStatementDue,
                    hidden = false,
                )
                buildString {
                    append("本期账单剩余应还 $formattedDue")
                    if (dueDateLabel != null) {
                        append("（还款日 $dueDateLabel）")
                    }
                    append("，点击进入账单详情一键还款。")
                }
            }
            return ReminderNotificationContent(title = title, body = body)
        }
    }
}
