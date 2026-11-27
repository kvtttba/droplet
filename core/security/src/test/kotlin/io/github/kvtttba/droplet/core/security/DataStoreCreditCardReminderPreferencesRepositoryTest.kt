package io.github.kvtttba.droplet.core.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreCreditCardReminderPreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun defaultsPersistsSettingsAndTracksSameDayDedupAcrossReload() = runTest {
        val storeFile = File(tempFolder.newFolder(), "cc_reminders.preferences_pb")

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store1 = PreferenceDataStoreFactory.create(scope = scope1) { storeFile }
            val repo1 = DataStoreCreditCardReminderPreferencesRepository(store1)

            val defaults = repo1.currentPreferences()
            assertTrue(defaults.remindersEnabled)
            assertEquals(3, defaults.defaultRemindDaysBefore)
            assertTrue(defaults.lastNotifiedDateByAccountId.isEmpty())

            repo1.setRemindersEnabled(false)
            repo1.setDefaultRemindDaysBefore(5)
            repo1.markNotifiedOnDate(listOf("card:1", "card:2"), "2026-11-23")

            val updated = repo1.currentPreferences()
            assertFalse(updated.remindersEnabled)
            assertEquals(5, updated.defaultRemindDaysBefore)
            assertTrue(updated.wasNotifiedOn("card:1", "2026-11-23"))
            assertTrue(updated.wasNotifiedOn("card:2", "2026-11-23"))
            assertFalse(updated.wasNotifiedOn("card:1", "2026-11-24"))
        } finally {
            scope1.coroutineContext[Job]?.cancelAndJoin()
        }

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store2 = PreferenceDataStoreFactory.create(scope = scope2) { storeFile }
            val repo2 = DataStoreCreditCardReminderPreferencesRepository(store2)

            val reloaded = repo2.currentPreferences()
            assertFalse(reloaded.remindersEnabled)
            assertEquals(5, reloaded.defaultRemindDaysBefore)
            assertTrue(reloaded.wasNotifiedOn("card:1", "2026-11-23"))

            // Updating card:1 on next day replaces previous date cleanly without duplicate keys
            repo2.markNotifiedOnDate(listOf("card:1"), "2026-11-24")
            val nextDay = repo2.currentPreferences()
            assertTrue(nextDay.wasNotifiedOn("card:1", "2026-11-24"))
            assertFalse(nextDay.wasNotifiedOn("card:1", "2026-11-23"))
            assertTrue(nextDay.wasNotifiedOn("card:2", "2026-11-23"))

            repo2.clearNotificationHistory("card:2")
            assertFalse(repo2.currentPreferences().wasNotifiedOn("card:2", "2026-11-23"))
            assertTrue(repo2.currentPreferences().wasNotifiedOn("card:1", "2026-11-24"))
        } finally {
            scope2.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun outOfRangeStoredDaysAndMalformedEntries_fallbackSafely() = runTest {
        val storeFile = File(tempFolder.newFolder(), "cc_corrupted.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
            store.edit { prefs ->
                prefs[DataStoreCreditCardReminderPreferencesRepository.Keys.DEFAULT_REMIND_DAYS_BEFORE] = 99
                prefs[DataStoreCreditCardReminderPreferencesRepository.Keys.LAST_NOTIFIED_ENTRIES] = setOf(
                    "malformed_no_delimiter",
                    "|2026-11-23",
                    "card:valid|2026-11-23",
                )
            }
            val repo = DataStoreCreditCardReminderPreferencesRepository(store)
            val loaded = repo.currentPreferences()
            assertEquals(31, loaded.defaultRemindDaysBefore)
            assertEquals(mapOf("card:valid" to "2026-11-23"), loaded.lastNotifiedDateByAccountId)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
