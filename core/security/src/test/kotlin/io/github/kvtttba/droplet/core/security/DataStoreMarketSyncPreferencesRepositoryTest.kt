package io.github.kvtttba.droplet.core.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreMarketSyncPreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun defaultsAndPersistsIntervalAndWifiOnlyAcrossReload() = runTest {
        val storeFile = File(tempFolder.newFolder(), "market_sync.preferences_pb")

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store1 = PreferenceDataStoreFactory.create(scope = scope1) { storeFile }
            val repo1 = DataStoreMarketSyncPreferencesRepository(store1)

            val defaults = repo1.currentPreferences()
            assertEquals(MarketSyncInterval.HOURS_12, defaults.refreshInterval)
            assertFalse(defaults.wifiOnly)

            repo1.setRefreshInterval(MarketSyncInterval.HOURS_6)
            repo1.setWifiOnly(true)

            val updated = repo1.preferences.first()
            assertEquals(MarketSyncInterval.HOURS_6, updated.refreshInterval)
            assertTrue(updated.wifiOnly)
        } finally {
            scope1.coroutineContext[Job]?.cancelAndJoin()
        }

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store2 = PreferenceDataStoreFactory.create(scope = scope2) { storeFile }
            val repo2 = DataStoreMarketSyncPreferencesRepository(store2)

            val reloaded = repo2.currentPreferences()
            assertEquals(MarketSyncInterval.HOURS_6, reloaded.refreshInterval)
            assertTrue(reloaded.wifiOnly)

            repo2.updatePreferences(
                refreshInterval = MarketSyncInterval.MANUAL_ONLY,
                wifiOnly = false,
            )
            val manual = repo2.currentPreferences()
            assertEquals(MarketSyncInterval.MANUAL_ONLY, manual.refreshInterval)
            assertFalse(manual.wifiOnly)
            assertFalse(manual.refreshInterval.isPeriodicEnabled)
        } finally {
            scope2.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun unknownStoredInterval_fallsBackSafelyToDefault() = runTest {
        val storeFile = File(tempFolder.newFolder(), "corrupted_market_sync.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
            store.edit { prefs ->
                prefs[DataStoreMarketSyncPreferencesRepository.Keys.REFRESH_INTERVAL] = "EVERY_SECOND"
                prefs[DataStoreMarketSyncPreferencesRepository.Keys.WIFI_ONLY] = true
            }
            val repo = DataStoreMarketSyncPreferencesRepository(store)
            val loaded = repo.currentPreferences()
            assertEquals(MarketSyncInterval.DEFAULT, loaded.refreshInterval)
            assertTrue(loaded.wifiOnly)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
