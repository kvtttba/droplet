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
 * Repository managing budget threshold alert toggles, recurring due-reminder toggles,
 * and deduplication stamp sets stored in a dedicated Preferences DataStore.
 */
interface BudgetAndRecurringPreferencesRepository {

    val preferences: Flow<BudgetAndRecurringPreferences>

    suspend fun currentPreferences(): BudgetAndRecurringPreferences

    suspend fun setBudgetAlertsEnabled(enabled: Boolean)

    suspend fun setRecurringRemindersEnabled(enabled: Boolean)

    suspend fun markBudgetAlertsSent(alertKeys: Collection<String>)

    suspend fun markRecurringRemindersSent(reminderKeys: Collection<String>)

    suspend fun clearBudgetAlertHistory(budgetIdPrefix: String? = null)

    suspend fun clearRecurringReminderHistory(ruleIdPrefix: String? = null)
}

class DataStoreBudgetAndRecurringPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : BudgetAndRecurringPreferencesRepository {

    override val preferences: Flow<BudgetAndRecurringPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs ->
            BudgetAndRecurringPreferences(
                budgetAlertsEnabled = prefs[Keys.BUDGET_ALERTS_ENABLED] ?: true,
                recurringRemindersEnabled = prefs[Keys.RECURRING_REMINDERS_ENABLED] ?: true,
                sentBudgetAlertKeys = prefs[Keys.SENT_BUDGET_ALERT_KEYS].orEmpty(),
                sentRecurringReminderKeys = prefs[Keys.SENT_RECURRING_REMINDER_KEYS].orEmpty(),
            )
        }
        .distinctUntilChanged()

    override suspend fun currentPreferences(): BudgetAndRecurringPreferences = preferences.first()

    override suspend fun setBudgetAlertsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.BUDGET_ALERTS_ENABLED] = enabled
        }
    }

    override suspend fun setRecurringRemindersEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.RECURRING_REMINDERS_ENABLED] = enabled
        }
    }

    override suspend fun markBudgetAlertsSent(alertKeys: Collection<String>) {
        val cleaned = alertKeys.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        dataStore.edit { prefs ->
            val updated = (prefs[Keys.SENT_BUDGET_ALERT_KEYS].orEmpty() + cleaned)
                .toList()
                .takeLast(MAX_RETAINED_KEYS)
                .toSet()
            prefs[Keys.SENT_BUDGET_ALERT_KEYS] = updated
        }
    }

    override suspend fun markRecurringRemindersSent(reminderKeys: Collection<String>) {
        val cleaned = reminderKeys.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        dataStore.edit { prefs ->
            val updated = (prefs[Keys.SENT_RECURRING_REMINDER_KEYS].orEmpty() + cleaned)
                .toList()
                .takeLast(MAX_RETAINED_KEYS)
                .toSet()
            prefs[Keys.SENT_RECURRING_REMINDER_KEYS] = updated
        }
    }

    override suspend fun clearBudgetAlertHistory(budgetIdPrefix: String?) {
        dataStore.edit { prefs ->
            if (budgetIdPrefix.isNullOrBlank()) {
                prefs.remove(Keys.SENT_BUDGET_ALERT_KEYS)
            } else {
                val prefix = "${budgetIdPrefix.trim()}|"
                val filtered = prefs[Keys.SENT_BUDGET_ALERT_KEYS]
                    .orEmpty()
                    .filterNot { it.startsWith(prefix) }
                    .toSet()
                prefs[Keys.SENT_BUDGET_ALERT_KEYS] = filtered
            }
        }
    }

    override suspend fun clearRecurringReminderHistory(ruleIdPrefix: String?) {
        dataStore.edit { prefs ->
            if (ruleIdPrefix.isNullOrBlank()) {
                prefs.remove(Keys.SENT_RECURRING_REMINDER_KEYS)
            } else {
                val prefix = "${ruleIdPrefix.trim()}|"
                val filtered = prefs[Keys.SENT_RECURRING_REMINDER_KEYS]
                    .orEmpty()
                    .filterNot { it.startsWith(prefix) }
                    .toSet()
                prefs[Keys.SENT_RECURRING_REMINDER_KEYS] = filtered
            }
        }
    }

    internal object Keys {
        val BUDGET_ALERTS_ENABLED = booleanPreferencesKey("budget_alerts_enabled")
        val RECURRING_REMINDERS_ENABLED = booleanPreferencesKey("recurring_reminders_enabled")
        val SENT_BUDGET_ALERT_KEYS = stringSetPreferencesKey("sent_budget_alert_keys")
        val SENT_RECURRING_REMINDER_KEYS = stringSetPreferencesKey("sent_recurring_reminder_keys")
    }

    companion object {
        private const val MAX_RETAINED_KEYS: Int = 256
    }
}
