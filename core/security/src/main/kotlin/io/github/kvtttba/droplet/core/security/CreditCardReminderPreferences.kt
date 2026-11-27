package io.github.kvtttba.droplet.core.security

/**
 * Local DataStore preferences controlling credit-card repayment reminder scheduling,
 * default advance reminder window, and per-card same-day anti-duplicate notification history.
 */
data class CreditCardReminderPreferences(
    val remindersEnabled: Boolean = true,
    val defaultRemindDaysBefore: Int = DEFAULT_REMIND_DAYS_BEFORE,
    /**
     * Maps `accountId -> ISO-8601 LocalDate string (YYYY-MM-DD)` representing the latest calendar day
     * a local reminder notification was posted for that credit card.
     */
    val lastNotifiedDateByAccountId: Map<String, String> = emptyMap(),
) {
    init {
        require(defaultRemindDaysBefore in 0..MAX_REMIND_DAYS_BEFORE) {
            "defaultRemindDaysBefore must be in 0..$MAX_REMIND_DAYS_BEFORE, got $defaultRemindDaysBefore"
        }
    }

    fun wasNotifiedOn(accountId: String, localDateIso: String): Boolean =
        lastNotifiedDateByAccountId[accountId] == localDateIso

    companion object {
        const val DEFAULT_REMIND_DAYS_BEFORE: Int = 3
        const val MAX_REMIND_DAYS_BEFORE: Int = 31
        val PRESET_REMIND_DAYS: List<Int> = listOf(0, 1, 3, 5, 7)
    }
}
