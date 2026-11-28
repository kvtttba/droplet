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
import io.github.kvtttba.droplet.core.domain.automation.BudgetAlertLevel
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgress
import io.github.kvtttba.droplet.core.domain.automation.RecurringPayload
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.RecurringRule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Abstraction for posting local Android notifications for budget threshold alerts (warning/exceeded),
 * recurring rule auto-post notices, and manual-confirmation due reminders.
 */
interface PlanningAlertNotifier {
    fun ensureNotificationChannel()
    fun canPostNotifications(): Boolean

    fun publishBudgetAlert(
        progress: BudgetProgress,
        hideAmounts: Boolean = false,
    ): Boolean

    fun publishRecurringAutoPosted(
        rule: RecurringRule,
        payload: RecurringPayload,
        hideAmounts: Boolean = false,
    ): Boolean

    fun publishRecurringPendingConfirmation(
        rule: RecurringRule,
        payload: RecurringPayload,
        hideAmounts: Boolean = false,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean
}

class PlanningAlertNotificationPublisher(
    private val context: Context,
) : PlanningAlertNotifier {

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
    override fun publishBudgetAlert(
        progress: BudgetProgress,
        hideAmounts: Boolean,
    ): Boolean {
        ensureNotificationChannel()
        if (!canPostNotifications()) return false
        val content = buildBudgetAlertContent(progress = progress, hideAmounts = hideAmounts)
        val notificationId = notificationIdForBudgetAlert(progress.alertDedupKey)
        return dispatchNotification(
            destination = DEST_BUDGETS,
            notificationId = notificationId,
            content = content,
        )
    }

    @SuppressLint("MissingPermission")
    override fun publishRecurringAutoPosted(
        rule: RecurringRule,
        payload: RecurringPayload,
        hideAmounts: Boolean,
    ): Boolean {
        ensureNotificationChannel()
        if (!canPostNotifications()) return false
        val content = buildRecurringAutoPostedContent(
            rule = rule,
            payload = payload,
            hideAmounts = hideAmounts,
        )
        val notificationId = notificationIdForRecurringAutoPost(rule.id, rule.lastRunAt ?: rule.nextRunAt)
        return dispatchNotification(
            destination = DEST_RECURRING,
            notificationId = notificationId,
            content = content,
        )
    }

    @SuppressLint("MissingPermission")
    override fun publishRecurringPendingConfirmation(
        rule: RecurringRule,
        payload: RecurringPayload,
        hideAmounts: Boolean,
        zoneId: ZoneId,
    ): Boolean {
        ensureNotificationChannel()
        if (!canPostNotifications()) return false
        val content = buildRecurringPendingContent(
            rule = rule,
            payload = payload,
            hideAmounts = hideAmounts,
            zoneId = zoneId,
        )
        val notificationId = notificationIdForRecurringPending(rule.id)
        return dispatchNotification(
            destination = DEST_RECURRING,
            notificationId = notificationId,
            content = content,
        )
    }

    @SuppressLint("MissingPermission")
    private fun dispatchNotification(
        destination: String,
        notificationId: Int,
        content: PlanningNotificationContent,
    ): Boolean {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_PLANNING_DESTINATION, destination)
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

    data class PlanningNotificationContent(
        val title: String,
        val body: String,
    )

    companion object {
        const val CHANNEL_ID: String = "droplet_budget_recurring_alerts"
        const val CHANNEL_NAME: String = "预算预警与周期记账提醒"
        const val CHANNEL_DESCRIPTION: String = "预算达到预警或超支阈值提醒，以及周期记账自动入账与待确认提醒"
        const val EXTRA_PLANNING_DESTINATION: String = "droplet_planning_destination"
        const val DEST_BUDGETS: String = "budgets"
        const val DEST_RECURRING: String = "recurring"

        private val monthDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

        fun notificationIdForBudgetAlert(dedupKey: String): Int =
            61_000 + (dedupKey.hashCode() and 0x7FFF)

        fun notificationIdForRecurringAutoPost(ruleId: String, runAt: Long): Int =
            65_000 + ("$ruleId|$runAt".hashCode() and 0x7FFF)

        fun notificationIdForRecurringPending(ruleId: String): Int =
            69_000 + (ruleId.hashCode() and 0x7FFF)

        fun buildBudgetAlertContent(
            progress: BudgetProgress,
            hideAmounts: Boolean = false,
        ): PlanningNotificationContent {
            val periodPrefix = if (progress.budget.period == BudgetPeriod.MONTHLY) "本月" else "本年"
            val title = when (progress.alertLevel) {
                BudgetAlertLevel.EXCEEDED -> "${progress.displayTitle}已超支"
                BudgetAlertLevel.WARNING -> "${progress.displayTitle}已用达 ${progress.usedPercentageInt}%"
                BudgetAlertLevel.NORMAL -> "${progress.displayTitle}进度提示"
            }

            val body = if (hideAmounts) {
                when (progress.alertLevel) {
                    BudgetAlertLevel.EXCEEDED ->
                        "$periodPrefix「${progress.displayTitle}」支出已达到或超出预算限额，已按隐私设置隐藏具体金额，点击查看预算详情。"
                    else ->
                        "$periodPrefix「${progress.displayTitle}」已消耗 ${progress.usedPercentageDecimal}%（预警线 ${progress.budget.warnThresholdPct}%），已按隐私设置隐藏具体金额，点击查看预算详情。"
                }
            } else {
                val spentStr = PrivacyAmountFormatter.format(progress.spentAmount, hidden = false)
                val limitStr = PrivacyAmountFormatter.format(progress.budget.limitAmount, hidden = false)
                when (progress.alertLevel) {
                    BudgetAlertLevel.EXCEEDED -> {
                        val over = (-progress.remainingAmount)
                        val overStr = PrivacyAmountFormatter.format(over, hidden = false)
                        "$periodPrefix「${progress.displayTitle}」已花费 $spentStr / 限额 $limitStr（已超支 $overStr），点击核对当期消费。"
                    }
                    else -> {
                        val dailyStr = PrivacyAmountFormatter.format(progress.dailyAvailableAmount, hidden = false)
                        "$periodPrefix「${progress.displayTitle}」已花费 $spentStr / 限额 $limitStr（${progress.usedPercentageDecimal}%），剩余 ${progress.periodRange.remainingDays} 天日均可用 $dailyStr。"
                    }
                }
            }
            return PlanningNotificationContent(title = title, body = body)
        }

        fun buildRecurringAutoPostedContent(
            rule: RecurringRule,
            payload: RecurringPayload,
            hideAmounts: Boolean = false,
        ): PlanningNotificationContent {
            val title = "周期记账已自动入账 · ${rule.name}"
            val body = if (hideAmounts) {
                "周期规则「${rule.name}」已按计划自动生成复式账目，已按隐私设置隐藏具体金额，点击查看明细。"
            } else {
                val amountStr = PrivacyAmountFormatter.format(payload.amount, hidden = false)
                "周期规则「${rule.name}」已自动入账 $amountStr，下次执行日已顺延，点击查看周期规则。"
            }
            return PlanningNotificationContent(title = title, body = body)
        }

        fun buildRecurringPendingContent(
            rule: RecurringRule,
            payload: RecurringPayload,
            hideAmounts: Boolean = false,
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): PlanningNotificationContent {
            val dueLabel = Instant.ofEpochMilli(rule.nextRunAt)
                .atZone(zoneId)
                .toLocalDate()
                .format(monthDayFormatter)
            val title = "周期记账待确认 · ${rule.name}"
            val body = if (hideAmounts) {
                "规则「${rule.name}」（$dueLabel 到期）等待您确认入账或跳过本期，已按隐私设置隐藏具体金额。"
            } else {
                val amountStr = PrivacyAmountFormatter.format(payload.amount, hidden = false)
                "规则「${rule.name}」（$dueLabel 到期）计划金额 $amountStr，点击进入周期记账一键确认入账或跳过本期。"
            }
            return PlanningNotificationContent(title = title, body = body)
        }
    }
}
