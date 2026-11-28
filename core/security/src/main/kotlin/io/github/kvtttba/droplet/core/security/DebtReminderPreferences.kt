package io.github.kvtttba.droplet.core.security

/**
 * Local DataStore preferences tracking counterparty debt due-date reminder settings
 * and per-profile same-day delivery stamps so local notifications never duplicate on the same day.
 */
data class DebtReminderPreferences(
    val remindersEnabled: Boolean = true,
    val defaultRemindDaysBefore: Int = DEFAULT_REMIND_DAYS_BEFORE,
    /**
     * Maps `profileId -> ISO-8601 LocalDate string (YYYY-MM-DD)` representing the latest
     * calendar day a local due reminder notification was posted for that counterparty.
     */
    val lastNotifiedDateByProfileId: Map<String, String> = emptyMap(),
) {
    init {
        require(defaultRemindDaysBefore in 0..31) {
            "defaultRemindDaysBefore must be in 0..31, got $defaultRemindDaysBefore"
        }
    }

    fun wasNotifiedOn(profileId: String, localDateIso: String): Boolean =
        lastNotifiedDateByProfileId[profileId] == localDateIso

    companion object {
        const val DEFAULT_REMIND_DAYS_BEFORE: Int = 3
    }
}
