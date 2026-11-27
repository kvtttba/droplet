package io.github.kvtttba.droplet.core.security

/**
 * Local DataStore preferences tracking per-installment same-day delivery stamps so
 * daily loan due-reminder notifications never duplicate within the same calendar day.
 */
data class LoanReminderPreferences(
    val remindersEnabled: Boolean = true,
    /**
     * Maps `installmentId -> ISO-8601 LocalDate string (YYYY-MM-DD)` representing the latest
     * calendar day a local reminder notification was posted for that installment.
     */
    val lastNotifiedDateByInstallmentId: Map<String, String> = emptyMap(),
) {
    fun wasNotifiedOn(installmentId: String, localDateIso: String): Boolean =
        lastNotifiedDateByInstallmentId[installmentId] == localDateIso
}
