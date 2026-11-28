package io.github.kvtttba.droplet.core.security

/**
 * Persisted alert delivery preferences and deduplication keys for budget threshold alerts
 * (80% warning / 100% exceeded per budget period) and recurring rule due confirmation reminders.
 */
data class BudgetAndRecurringPreferences(
    val budgetAlertsEnabled: Boolean = true,
    val recurringRemindersEnabled: Boolean = true,
    val sentBudgetAlertKeys: Set<String> = emptySet(),
    val sentRecurringReminderKeys: Set<String> = emptySet(),
) {
    fun hasSentBudgetAlert(alertKey: String): Boolean = alertKey in sentBudgetAlertKeys

    fun hasSentRecurringReminder(reminderKey: String): Boolean = reminderKey in sentRecurringReminderKeys

    companion object {
        fun recurringReminderKey(
            ruleId: String,
            nextRunAt: Long,
            localDateIso: String,
        ): String = "$ruleId|$nextRunAt|$localDateIso"
    }
}
