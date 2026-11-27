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
import io.github.kvtttba.droplet.core.domain.loan.LoanPlanStatus
import io.github.kvtttba.droplet.core.domain.loan.LoanPlanSummary
import io.github.kvtttba.droplet.core.model.LoanInstallment
import io.github.kvtttba.droplet.core.model.LoanPlan
import io.github.kvtttba.droplet.core.model.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Abstraction for posting local Android installment-loan due reminders and auto-post notices.
 */
interface LoanReminderNotifier {
    fun ensureNotificationChannel()
    fun canPostNotifications(): Boolean
    fun publishDueReminder(
        summary: LoanPlanSummary,
        installment: LoanInstallment,
        hideAmounts: Boolean = false,
    ): Boolean

    fun publishAutoPostedNotice(
        plan: LoanPlan,
        installment: LoanInstallment,
        hideAmounts: Boolean = false,
    ): Boolean
}

/**
 * Local Android notification publisher for installment loan due reminders and auto-repayment notices.
 *
 * - Uses dedicated channel [CHANNEL_ID] (`droplet_loan_reminders`)
 * - Checks app-level notification enablement and Android 13+ `POST_NOTIFICATIONS` permission
 * - Respects global privacy masking (`hideAmounts`) so exact principal/interest figures are never leaked
 * - Attaches a deep-link [PendingIntent] opening [MainActivity] directly to the target loan detail screen
 */
class LoanReminderNotificationPublisher(
    private val context: Context,
) : LoanReminderNotifier {

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
    override fun publishDueReminder(
        summary: LoanPlanSummary,
        installment: LoanInstallment,
        hideAmounts: Boolean,
    ): Boolean {
        ensureNotificationChannel()
        if (!canPostNotifications()) {
            return false
        }
        val content = buildDueReminderContent(
            summary = summary,
            installment = installment,
            hideAmounts = hideAmounts,
        )
        val notificationId = notificationIdForLoanPlan(summary.plan.id)
        return dispatchNotification(
            loanPlanId = summary.plan.id,
            notificationId = notificationId,
            content = content,
        )
    }

    @SuppressLint("MissingPermission")
    override fun publishAutoPostedNotice(
        plan: LoanPlan,
        installment: LoanInstallment,
        hideAmounts: Boolean,
    ): Boolean {
        ensureNotificationChannel()
        if (!canPostNotifications()) {
            return false
        }
        val content = buildAutoPostedContent(
            plan = plan,
            installment = installment,
            hideAmounts = hideAmounts,
        )
        val notificationId = notificationIdForAutoPost(installment.id)
        return dispatchNotification(
            loanPlanId = plan.id,
            notificationId = notificationId,
            content = content,
        )
    }

    @SuppressLint("MissingPermission")
    private fun dispatchNotification(
        loanPlanId: String,
        notificationId: Int,
        content: LoanNotificationContent,
    ): Boolean {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_LOAN_PLAN_ID, loanPlanId)
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

    data class LoanNotificationContent(
        val title: String,
        val body: String,
    )

    companion object {
        const val CHANNEL_ID: String = "droplet_loan_reminders"
        const val CHANNEL_NAME: String = "分期贷款还款提醒"
        const val CHANNEL_DESCRIPTION: String = "分期贷款临近还款日提醒与到期自动入账通知"
        const val EXTRA_LOAN_PLAN_ID: String = "droplet_loan_plan_id"

        private val monthDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

        fun notificationIdForLoanPlan(planId: String): Int =
            41_000 + (planId.hashCode() and 0x7FFF)

        fun notificationIdForAutoPost(installmentId: String): Int =
            45_000 + (installmentId.hashCode() and 0x7FFF)

        fun buildDueReminderContent(
            summary: LoanPlanSummary,
            installment: LoanInstallment,
            hideAmounts: Boolean = false,
            zoneId: ZoneId = summary.zoneId,
        ): LoanNotificationContent {
            val plan = summary.plan
            val dueDateLabel = Instant.ofEpochMilli(installment.dueAt)
                .atZone(zoneId)
                .toLocalDate()
                .format(monthDayFormatter)

            val title = when {
                summary.status == LoanPlanStatus.OVERDUE ->
                    "${plan.name} · 第 ${installment.periodNumber} 期已逾期 ${summary.overdueDays} 天"
                summary.isNextDueToday ->
                    "${plan.name} · 第 ${installment.periodNumber} 期今日到期"
                summary.daysUntilNextDue != null ->
                    "${plan.name} · 距第 ${installment.periodNumber} 期还款还有 ${summary.daysUntilNextDue} 天"
                else ->
                    "${plan.name} · 第 ${installment.periodNumber} 期还款提醒"
            }

            val body = if (hideAmounts) {
                "第 ${installment.periodNumber}/${summary.totalPeriods} 期将于 $dueDateLabel 到期，已按隐私设置隐藏具体金额，点击查看还款计划。"
            } else {
                val totalDue = PrivacyAmountFormatter.format(
                    money = Money(installment.totalDueMinor, plan.currency),
                    hidden = false,
                )
                val principal = PrivacyAmountFormatter.format(
                    money = Money(installment.principalMinor, plan.currency),
                    hidden = false,
                )
                val interest = PrivacyAmountFormatter.format(
                    money = Money(installment.interestOrFeeMinor, plan.currency),
                    hidden = false,
                )
                "第 ${installment.periodNumber}/${summary.totalPeriods} 期（$dueDateLabel）应还 $totalDue（本金 $principal + 息费 $interest），点击进入详情一键入账。"
            }
            return LoanNotificationContent(title = title, body = body)
        }

        fun buildAutoPostedContent(
            plan: LoanPlan,
            installment: LoanInstallment,
            hideAmounts: Boolean = false,
        ): LoanNotificationContent {
            val title = "${plan.name} · 第 ${installment.periodNumber}/${plan.totalPeriods} 期已自动入账"
            val body = if (hideAmounts) {
                "已自动生成本期复式还款分录并扣减贷款负债，已按隐私设置隐藏具体金额，点击查看详情。"
            } else {
                val totalDue = PrivacyAmountFormatter.format(
                    money = Money(installment.totalDueMinor, plan.currency),
                    hidden = false,
                )
                val principal = PrivacyAmountFormatter.format(
                    money = Money(installment.principalMinor, plan.currency),
                    hidden = false,
                )
                val interest = PrivacyAmountFormatter.format(
                    money = Money(installment.interestOrFeeMinor, plan.currency),
                    hidden = false,
                )
                "已自动生成本期还款分录 $totalDue（本金 $principal 冲减负债，息费 $interest 计入支出），点击核对计划表。"
            }
            return LoanNotificationContent(title = title, body = body)
        }
    }
}
