package io.github.kvtttba.droplet.core.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataStoreDebtReminderPreferencesRepositoryTest {

    @Test
    fun persistsReminderToggleLeadDaysAndSameDayDedupHistory() = runBlocking {
        val tempFile = File.createTempFile("debt_reminder_${UUID.randomUUID()}", ".preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { tempFile },
            )
            val repo = DataStoreDebtReminderPreferencesRepository(dataStore)

            val initial = repo.currentPreferences()
            assertTrue(initial.remindersEnabled)
            assertEquals(DebtReminderPreferences.DEFAULT_REMIND_DAYS_BEFORE, initial.defaultRemindDaysBefore)
            assertTrue(initial.lastNotifiedDateByProfileId.isEmpty())

            repo.setRemindersEnabled(false)
            repo.setDefaultRemindDaysBefore(5)
            repo.markNotifiedOnDate(listOf("p1", "p2"), "2026-11-28")

            val updated = repo.currentPreferences()
            assertFalse(updated.remindersEnabled)
            assertEquals(5, updated.defaultRemindDaysBefore)
            assertTrue(updated.wasNotifiedOn("p1", "2026-11-28"))
            assertTrue(updated.wasNotifiedOn("p2", "2026-11-28"))
            assertFalse(updated.wasNotifiedOn("p1", "2026-11-29"))

            repo.clearNotificationHistory("p1")
            val afterPartialClear = repo.currentPreferences()
            assertFalse(afterPartialClear.wasNotifiedOn("p1", "2026-11-28"))
            assertTrue(afterPartialClear.wasNotifiedOn("p2", "2026-11-28"))

            repo.clearNotificationHistory(null)
            assertTrue(repo.currentPreferences().lastNotifiedDateByProfileId.isEmpty())
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            tempFile.delete()
        }
    }
}
