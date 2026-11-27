package io.github.kvtttba.droplet.core.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreLoanReminderPreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun persistsReminderToggleAndPerInstallmentSameDayDedupStamps() = runBlocking {
        val storeFile = File(tempFolder.newFolder("loan-prefs"), SecurityContract.LOAN_REMINDER_PREFERENCES_FILE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { storeFile },
            )
            val repo = DataStoreLoanReminderPreferencesRepository(dataStore)

            val initial = repo.currentPreferences()
            assertTrue(initial.remindersEnabled)
            assertTrue(initial.lastNotifiedDateByInstallmentId.isEmpty())

            repo.markNotifiedOnDate(listOf("loan-1:p1", "loan-2:p3"), "2026-11-28")
            val stamped = repo.currentPreferences()
            assertTrue(stamped.wasNotifiedOn("loan-1:p1", "2026-11-28"))
            assertTrue(stamped.wasNotifiedOn("loan-2:p3", "2026-11-28"))
            assertFalse(stamped.wasNotifiedOn("loan-1:p1", "2026-11-29"))

            repo.setRemindersEnabled(false)
            assertFalse(repo.currentPreferences().remindersEnabled)

            repo.clearNotificationHistory("loan-1:p1")
            val afterSingleClear = repo.currentPreferences()
            assertFalse(afterSingleClear.wasNotifiedOn("loan-1:p1", "2026-11-28"))
            assertTrue(afterSingleClear.wasNotifiedOn("loan-2:p3", "2026-11-28"))

            repo.clearNotificationHistory(null)
            assertEquals(emptyMap<String, String>(), repo.currentPreferences().lastNotifiedDateByInstallmentId)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
