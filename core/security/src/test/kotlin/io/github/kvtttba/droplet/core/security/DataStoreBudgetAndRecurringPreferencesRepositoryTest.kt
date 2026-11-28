package io.github.kvtttba.droplet.core.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreBudgetAndRecurringPreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun persistsAlertTogglesAndDeduplicationKeysWithSelectiveClearing() = runTest {
        val file = File(tempFolder.root, "budget_recurring_test.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val repo = DataStoreBudgetAndRecurringPreferencesRepository(store)

            val initial = repo.currentPreferences()
            assertTrue(initial.budgetAlertsEnabled)
            assertTrue(initial.recurringRemindersEnabled)
            assertTrue(initial.sentBudgetAlertKeys.isEmpty())

            repo.setBudgetAlertsEnabled(false)
            repo.setRecurringRemindersEnabled(false)
            repo.markBudgetAlertsSent(listOf("b1|2026-11|WARNING", "b2|2026-11|EXCEEDED"))
            val recurringKey = BudgetAndRecurringPreferences.recurringReminderKey("r1", 1000L, "2026-11-21")
            repo.markRecurringRemindersSent(listOf(recurringKey))

            val saved = repo.currentPreferences()
            assertFalse(saved.budgetAlertsEnabled)
            assertFalse(saved.recurringRemindersEnabled)
            assertTrue(saved.hasSentBudgetAlert("b1|2026-11|WARNING"))
            assertTrue(saved.hasSentBudgetAlert("b2|2026-11|EXCEEDED"))
            assertTrue(saved.hasSentRecurringReminder(recurringKey))

            repo.clearBudgetAlertHistory("b1")
            val afterClear = repo.currentPreferences()
            assertFalse(afterClear.hasSentBudgetAlert("b1|2026-11|WARNING"))
            assertTrue(afterClear.hasSentBudgetAlert("b2|2026-11|EXCEEDED"))
            assertEquals(1, afterClear.sentBudgetAlertKeys.size)

            repo.clearRecurringReminderHistory()
            assertTrue(repo.currentPreferences().sentRecurringReminderKeys.isEmpty())
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
