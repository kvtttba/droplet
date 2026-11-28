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
 * Repository managing counterparty debt due-reminder settings and per-profile
 * same-day deduplication stamps.
 */
interface DebtReminderPreferencesRepository {

    val preferences: Flow<DebtReminderPreferences>

    suspend fun currentPreferences(): DebtReminderPreferences

    suspend fun setRemindersEnabled(enabled: Boolean)

    suspend fun setDefaultRemindDaysBefore(days: Int)

    suspend fun markNotifiedOnDate(
        profileIds: Collection<String>,
        localDateIso: String,
    )

    suspend fun clearNotificationHistory(profileId: String? = null)
}

class DataStoreDebtReminderPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : DebtReminderPreferencesRepository {

    override val preferences: Flow<DebtReminderPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs ->
            val rawDays = prefs[Keys.DEFAULT_REMIND_DAYS]
                ?: DebtReminderPreferences.DEFAULT_REMIND_DAYS_BEFORE
            DebtReminderPreferences(
                remindersEnabled = prefs[Keys.REMINDERS_ENABLED] ?: true,
                defaultRemindDaysBefore = rawDays.coerceIn(0, 31),
                lastNotifiedDateByProfileId = decodeLastNotifiedMap(
                    prefs[Keys.LAST_NOTIFIED_ENTRIES].orEmpty(),
                ),
            )
        }
        .distinctUntilChanged()

    override suspend fun currentPreferences(): DebtReminderPreferences = preferences.first()

    override suspend fun setRemindersEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.REMINDERS_ENABLED] = enabled
        }
    }

    override suspend fun setDefaultRemindDaysBefore(days: Int) {
        require(days in 0..31) { "defaultRemindDaysBefore must be in 0..31, got $days" }
        dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_REMIND_DAYS] = days
        }
    }

    override suspend fun markNotifiedOnDate(
        profileIds: Collection<String>,
        localDateIso: String,
    ) {
        val trimmedDate = localDateIso.trim()
        require(trimmedDate.isNotEmpty()) { "localDateIso must not be blank" }
        val normalizedIds = profileIds.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalizedIds.isEmpty()) return

        dataStore.edit { prefs ->
            val map = decodeLastNotifiedMap(prefs[Keys.LAST_NOTIFIED_ENTRIES].orEmpty()).toMutableMap()
            for (id in normalizedIds) {
                map[id] = trimmedDate
            }
            prefs[Keys.LAST_NOTIFIED_ENTRIES] = encodeLastNotifiedMap(map)
        }
    }

    override suspend fun clearNotificationHistory(profileId: String?) {
        dataStore.edit { prefs ->
            if (profileId == null) {
                prefs.remove(Keys.LAST_NOTIFIED_ENTRIES)
            } else {
                val map = decodeLastNotifiedMap(prefs[Keys.LAST_NOTIFIED_ENTRIES].orEmpty()).toMutableMap()
                map.remove(profileId)
                prefs[Keys.LAST_NOTIFIED_ENTRIES] = encodeLastNotifiedMap(map)
            }
        }
    }

    internal object Keys {
        val REMINDERS_ENABLED = booleanPreferencesKey("debt_reminders_enabled")
        val DEFAULT_REMIND_DAYS = intPreferencesKey("debt_default_remind_days")
        val LAST_NOTIFIED_ENTRIES = stringSetPreferencesKey("debt_last_notified_entries")
    }

    companion object {
        private const val ENTRY_DELIMITER = "|"

        internal fun decodeLastNotifiedMap(entries: Set<String>): Map<String, String> = buildMap {
            for (raw in entries) {
                val idx = raw.lastIndexOf(ENTRY_DELIMITER)
                if (idx <= 0 || idx >= raw.lastIndex) continue
                val id = raw.substring(0, idx)
                val dateIso = raw.substring(idx + 1)
                if (id.isNotBlank() && dateIso.isNotBlank()) {
                    put(id, dateIso)
                }
            }
        }

        internal fun encodeLastNotifiedMap(map: Map<String, String>): Set<String> =
            map.entries.map { (id, dateIso) -> "$id$ENTRY_DELIMITER$dateIso" }.toSet()
    }
}
