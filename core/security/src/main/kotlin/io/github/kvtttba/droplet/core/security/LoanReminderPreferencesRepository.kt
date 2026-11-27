package io.github.kvtttba.droplet.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Repository managing installment loan reminder delivery toggle and per-installment
 * same-day deduplication stamps.
 */
interface LoanReminderPreferencesRepository {

    val preferences: Flow<LoanReminderPreferences>

    suspend fun currentPreferences(): LoanReminderPreferences

    suspend fun setRemindersEnabled(enabled: Boolean)

    suspend fun markNotifiedOnDate(
        installmentIds: Collection<String>,
        localDateIso: String,
    )

    suspend fun clearNotificationHistory(installmentId: String? = null)
}

class DataStoreLoanReminderPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : LoanReminderPreferencesRepository {

    override val preferences: Flow<LoanReminderPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs ->
            LoanReminderPreferences(
                remindersEnabled = prefs[Keys.REMINDERS_ENABLED] ?: true,
                lastNotifiedDateByInstallmentId = decodeLastNotifiedMap(
                    prefs[Keys.LAST_NOTIFIED_ENTRIES].orEmpty(),
                ),
            )
        }
        .distinctUntilChanged()

    override suspend fun currentPreferences(): LoanReminderPreferences = preferences.first()

    override suspend fun setRemindersEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.REMINDERS_ENABLED] = enabled
        }
    }

    override suspend fun markNotifiedOnDate(
        installmentIds: Collection<String>,
        localDateIso: String,
    ) {
        val trimmedDate = localDateIso.trim()
        require(trimmedDate.isNotEmpty()) { "localDateIso must not be blank" }
        val normalizedIds = installmentIds.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalizedIds.isEmpty()) return

        dataStore.edit { prefs ->
            val map = decodeLastNotifiedMap(prefs[Keys.LAST_NOTIFIED_ENTRIES].orEmpty()).toMutableMap()
            for (id in normalizedIds) {
                map[id] = trimmedDate
            }
            prefs[Keys.LAST_NOTIFIED_ENTRIES] = encodeLastNotifiedMap(map)
        }
    }

    override suspend fun clearNotificationHistory(installmentId: String?) {
        dataStore.edit { prefs ->
            if (installmentId == null) {
                prefs.remove(Keys.LAST_NOTIFIED_ENTRIES)
            } else {
                val map = decodeLastNotifiedMap(prefs[Keys.LAST_NOTIFIED_ENTRIES].orEmpty()).toMutableMap()
                map.remove(installmentId)
                prefs[Keys.LAST_NOTIFIED_ENTRIES] = encodeLastNotifiedMap(map)
            }
        }
    }

    internal object Keys {
        val REMINDERS_ENABLED = booleanPreferencesKey("loan_reminders_enabled")
        val LAST_NOTIFIED_ENTRIES = stringSetPreferencesKey("loan_last_notified_entries")
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
