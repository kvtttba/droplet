package io.github.kvtttba.droplet.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Repository managing credit card repayment reminder settings and per-card same-day delivery stamps.
 */
interface CreditCardReminderPreferencesRepository {

    val preferences: Flow<CreditCardReminderPreferences>

    suspend fun currentPreferences(): CreditCardReminderPreferences

    suspend fun setRemindersEnabled(enabled: Boolean)

    suspend fun setDefaultRemindDaysBefore(days: Int)

    suspend fun markNotifiedOnDate(
        accountIds: Collection<String>,
        localDateIso: String,
    )

    suspend fun clearNotificationHistory(accountId: String? = null)
}

class DataStoreCreditCardReminderPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : CreditCardReminderPreferencesRepository {

    override val preferences: Flow<CreditCardReminderPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs ->
            val rawDays = prefs[Keys.DEFAULT_REMIND_DAYS_BEFORE]
                ?: CreditCardReminderPreferences.DEFAULT_REMIND_DAYS_BEFORE
            val safeDays = rawDays.coerceIn(0, CreditCardReminderPreferences.MAX_REMIND_DAYS_BEFORE)
            CreditCardReminderPreferences(
                remindersEnabled = prefs[Keys.REMINDERS_ENABLED] ?: true,
                defaultRemindDaysBefore = safeDays,
                lastNotifiedDateByAccountId = decodeLastNotifiedMap(prefs[Keys.LAST_NOTIFIED_ENTRIES].orEmpty()),
            )
        }
        .distinctUntilChanged()

    override suspend fun currentPreferences(): CreditCardReminderPreferences = preferences.first()

    override suspend fun setRemindersEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.REMINDERS_ENABLED] = enabled
        }
    }

    override suspend fun setDefaultRemindDaysBefore(days: Int) {
        require(days in 0..CreditCardReminderPreferences.MAX_REMIND_DAYS_BEFORE) {
            "days must be in 0..${CreditCardReminderPreferences.MAX_REMIND_DAYS_BEFORE}, got $days"
        }
        dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_REMIND_DAYS_BEFORE] = days
        }
    }

    override suspend fun markNotifiedOnDate(
        accountIds: Collection<String>,
        localDateIso: String,
    ) {
        val trimmedDate = localDateIso.trim()
        require(trimmedDate.isNotEmpty()) { "localDateIso must not be blank" }
        val normalizedIds = accountIds.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalizedIds.isEmpty()) return

        dataStore.edit { prefs ->
            val map = decodeLastNotifiedMap(prefs[Keys.LAST_NOTIFIED_ENTRIES].orEmpty()).toMutableMap()
            for (id in normalizedIds) {
                map[id] = trimmedDate
            }
            prefs[Keys.LAST_NOTIFIED_ENTRIES] = encodeLastNotifiedMap(map)
        }
    }

    override suspend fun clearNotificationHistory(accountId: String?) {
        dataStore.edit { prefs ->
            if (accountId == null) {
                prefs.remove(Keys.LAST_NOTIFIED_ENTRIES)
            } else {
                val map = decodeLastNotifiedMap(prefs[Keys.LAST_NOTIFIED_ENTRIES].orEmpty()).toMutableMap()
                map.remove(accountId)
                prefs[Keys.LAST_NOTIFIED_ENTRIES] = encodeLastNotifiedMap(map)
            }
        }
    }

    internal object Keys {
        val REMINDERS_ENABLED = booleanPreferencesKey("credit_card_reminders_enabled")
        val DEFAULT_REMIND_DAYS_BEFORE = intPreferencesKey("credit_card_default_remind_days_before")
        val LAST_NOTIFIED_ENTRIES = stringSetPreferencesKey("credit_card_last_notified_entries")
    }

    companion object {
        private const val ENTRY_DELIMITER = "|"

        internal fun decodeLastNotifiedMap(entries: Set<String>): Map<String, String> = buildMap {
            for (raw in entries) {
                val idx = raw.lastIndexOf(ENTRY_DELIMITER)
                if (idx <= 0 || idx >= raw.lastIndex) continue
                val accountId = raw.substring(0, idx)
                val dateIso = raw.substring(idx + 1)
                if (accountId.isNotBlank() && dateIso.isNotBlank()) {
                    put(accountId, dateIso)
                }
            }
        }

        internal fun encodeLastNotifiedMap(map: Map<String, String>): Set<String> =
            map.entries.map { (accountId, dateIso) -> "$accountId$ENTRY_DELIMITER$dateIso" }.toSet()
    }
}
