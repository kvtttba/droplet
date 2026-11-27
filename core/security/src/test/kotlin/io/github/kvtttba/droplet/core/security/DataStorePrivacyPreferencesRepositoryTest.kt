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

class DataStorePrivacyPreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun defaults_matchM14Contract() = runTest {
        val storeFile = File(tempFolder.newFolder(), "defaults.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
            val repository = DataStorePrivacyPreferencesRepository(dataStore)

            val prefs = repository.preferences.first()
            assertFalse(prefs.biometricLockEnabled)
            assertEquals(RelockTimeout.MINUTE_1, prefs.relockTimeout)
            assertFalse(prefs.flagSecureEnabled)
            assertFalse(prefs.amountsHidden)
            assertEquals(AppThemePreference.SYSTEM, prefs.themeMode)
            assertTrue(prefs.dynamicColor)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun updates_persistAcrossRepositoryReload() = runTest {
        val storeFile = File(tempFolder.newFolder(), "persisted.preferences_pb")

        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { storeFile }
            val firstRepo = DataStorePrivacyPreferencesRepository(firstStore)

            firstRepo.setBiometricLockEnabled(true)
            firstRepo.setRelockTimeout(RelockTimeout.SECONDS_30)
            firstRepo.setFlagSecureEnabled(true)
            firstRepo.setAmountsHidden(true)
            firstRepo.setThemeMode(AppThemePreference.DARK)
            firstRepo.setDynamicColor(false)

            val updated = firstRepo.preferences.first()
            assertTrue(updated.biometricLockEnabled)
            assertEquals(RelockTimeout.SECONDS_30, updated.relockTimeout)
            assertTrue(updated.flagSecureEnabled)
            assertTrue(updated.amountsHidden)
            assertEquals(AppThemePreference.DARK, updated.themeMode)
            assertFalse(updated.dynamicColor)
        } finally {
            firstScope.coroutineContext[Job]?.cancelAndJoin()
        }

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val secondStore = PreferenceDataStoreFactory.create(scope = secondScope) { storeFile }
            val secondRepo = DataStorePrivacyPreferencesRepository(secondStore)

            val reloaded = secondRepo.preferences.first()
            assertTrue(reloaded.biometricLockEnabled)
            assertEquals(RelockTimeout.SECONDS_30, reloaded.relockTimeout)
            assertTrue(reloaded.flagSecureEnabled)
            assertTrue(reloaded.amountsHidden)
            assertEquals(AppThemePreference.DARK, reloaded.themeMode)
            assertFalse(reloaded.dynamicColor)
        } finally {
            secondScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun toggleAmountsHidden_flipsAndPersists() = runTest {
        val storeFile = File(tempFolder.newFolder(), "toggle.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
            val repository = DataStorePrivacyPreferencesRepository(dataStore)

            assertTrue(repository.toggleAmountsHidden())
            assertTrue(repository.preferences.first().amountsHidden)

            assertFalse(repository.toggleAmountsHidden())
            assertFalse(repository.preferences.first().amountsHidden)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun unknownEnumStorageKeys_fallbackSafelyToDefaults() = runTest {
        val storeFile = File(tempFolder.newFolder(), "unknown.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
            dataStore.edit { prefs ->
                prefs[DataStorePrivacyPreferencesRepository.Keys.RELOCK_TIMEOUT] = "LEGACY_UNKNOWN"
                prefs[DataStorePrivacyPreferencesRepository.Keys.THEME_MODE] = "SEPIA_INVALID"
            }

            val repository = DataStorePrivacyPreferencesRepository(dataStore)
            val loaded = repository.preferences.first()
            assertEquals(RelockTimeout.DEFAULT, loaded.relockTimeout)
            assertEquals(AppThemePreference.DEFAULT, loaded.themeMode)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
