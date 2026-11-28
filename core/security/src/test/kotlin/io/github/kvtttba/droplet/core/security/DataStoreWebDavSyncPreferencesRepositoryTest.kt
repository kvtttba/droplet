package io.github.kvtttba.droplet.core.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreWebDavSyncPreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newInMemoryAes256Key(): SecretKey = KeyGenerator.getInstance("AES").apply {
        init(256)
    }.generateKey()

    @Test
    fun encryptsPasswordAndPassphraseAtRestPersistsAcrossReloadAndRedactsToString() = runTest {
        val storeFile = File(tempFolder.newFolder(), "webdav_sync.preferences_pb")
        val aesKey = newInMemoryAes256Key()
        val cipher = KeystoreSecretCipher { aesKey }
        val sampleDavPass = "demo-dav-pass-2026"
        val sampleBackupPhrase = "demo-backup-phrase-8899"

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store1 = PreferenceDataStoreFactory.create(scope = scope1) { storeFile }
            val repo1 = DataStoreWebDavSyncPreferencesRepository(store1, cipher)

            val defaults = repo1.currentPreferences()
            assertFalse(defaults.isServerConfigured)
            assertTrue(defaults.encryptionEnabled)
            assertFalse(defaults.autoSyncEnabled)
            assertFalse(defaults.dailyBackupEnabled)
            assertFalse(defaults.syncAttachments)
            assertEquals(10, defaults.retentionCount)
            assertEquals("droplet-backups", defaults.remoteDirectory)

            repo1.saveConnectionConfig(
                WebDavConfigSaveInput(
                    serverUrl = "https://webdav.example.invalid/dav/",
                    username = "demo-user",
                    remoteDirectory = "/ledger/backups/",
                    passwordAction = WebDavSecretUpdateAction.Replace(sampleDavPass),
                ),
            )
            repo1.saveBackupPassphrase(WebDavSecretUpdateAction.Replace(sampleBackupPhrase))
            repo1.setAutoSyncEnabled(true)
            repo1.setDailyBackupEnabled(true)
            repo1.setWifiOnly(true)
            repo1.setSyncAttachments(true)
            repo1.setRetentionCount(20)
            repo1.recordBackupOutcome(
                timestampMs = 1_764_211_200_000L,
                success = true,
                message = "手动备份成功",
                fileName = "droplet-backup-20261127-024000-000.drp",
            )
            repo1.recordRestoreSuccess(1_764_214_800_000L)

            // Confirm zero plaintext secrets hit disk
            val diskRaw = storeFile.readBytes().toString(Charsets.UTF_8)
            assertFalse("Plaintext WebDAV password must never hit disk", diskRaw.contains(sampleDavPass))
            assertFalse("Plaintext backup passphrase must never hit disk", diskRaw.contains(sampleBackupPhrase))
        } finally {
            scope1.coroutineContext[Job]?.cancelAndJoin()
        }

        // Reload in fresh DataStore instance
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store2 = PreferenceDataStoreFactory.create(scope = scope2) { storeFile }
            val repo2 = DataStoreWebDavSyncPreferencesRepository(store2, cipher)

            val reloaded = repo2.preferences.first()
            assertTrue(reloaded.isServerConfigured)
            assertTrue(reloaded.isReadyForBackup)
            assertEquals("https://webdav.example.invalid/dav", reloaded.serverUrl)
            assertEquals("demo-user", reloaded.username)
            assertEquals("ledger/backups", reloaded.remoteDirectory)
            assertEquals("••••2026", reloaded.passwordMasked)
            assertEquals("••••8899", reloaded.passphraseMasked)
            assertTrue(reloaded.autoSyncEnabled)
            assertTrue(reloaded.dailyBackupEnabled)
            assertTrue(reloaded.wifiOnly)
            assertTrue(reloaded.syncAttachments)
            assertEquals(20, reloaded.retentionCount)
            assertEquals("droplet-backup-20261127-024000-000.drp", reloaded.lastBackupFileName)
            assertEquals(1_764_214_800_000L, reloaded.lastRestoreAt)
            assertFalse(reloaded.toString().contains(sampleDavPass))
            assertFalse(reloaded.toString().contains(sampleBackupPhrase))

            val resolved = repo2.resolveCredentials()
            assertNotNull(resolved)
            assertEquals(sampleDavPass, resolved!!.password)
            assertEquals(sampleBackupPhrase, resolved.backupPassphrase)
            assertFalse(resolved.toString().contains(sampleDavPass))
            assertFalse(resolved.toString().contains(sampleBackupPhrase))

            // Retention count bounds are enforced strictly
            repo2.setRetentionCount(999)
            assertEquals(WebDavUrlValidator.MAX_RETENTION_COUNT, repo2.currentPreferences().retentionCount)
            repo2.setRetentionCount(0)
            assertEquals(WebDavUrlValidator.MIN_RETENTION_COUNT, repo2.currentPreferences().retentionCount)

            // KeepExisting preserves password when updating directory
            repo2.saveConnectionConfig(
                WebDavConfigSaveInput(
                    serverUrl = "https://webdav.example.invalid/dav",
                    username = "demo-user",
                    remoteDirectory = "droplet-backups",
                    passwordAction = WebDavSecretUpdateAction.KeepExisting,
                ),
            )
            assertEquals(sampleDavPass, repo2.decryptPassword())

            repo2.clearBackupPassphrase()
            assertFalse(repo2.currentPreferences().hasPassphrase)
            assertNull(repo2.decryptBackupPassphrase())
        } finally {
            scope2.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun crossAadSwapAndWrongKeystoreKeyFailClosedSafely() = runTest {
        val storeFile = File(tempFolder.newFolder(), "webdav_aad.preferences_pb")
        val keyA = newInMemoryAes256Key()
        val keyB = newInMemoryAes256Key()
        val cipherA = KeystoreSecretCipher { keyA }
        val cipherB = KeystoreSecretCipher { keyB }
        val samplePass = "demo-aad-dav-pass-1122"

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope1) { storeFile }
            val repo = DataStoreWebDavSyncPreferencesRepository(store, cipherA)
            repo.saveConnectionConfig(
                WebDavConfigSaveInput(
                    serverUrl = "https://webdav.example.invalid/dav",
                    username = "demo-user",
                    passwordAction = WebDavSecretUpdateAction.Replace(samplePass),
                ),
            )

            // Copy password ciphertext into backup passphrase slot -> AAD mismatch must block decryption
            val encPass = store.data.first()[DataStoreWebDavSyncPreferencesRepository.Keys.ENCRYPTED_PASSWORD]
            assertNotNull(encPass)
            store.edit { prefs ->
                prefs[DataStoreWebDavSyncPreferencesRepository.Keys.ENCRYPTED_PASSPHRASE] = encPass!!
            }

            val prefs = repo.currentPreferences()
            assertTrue(prefs.hasPassword)
            assertFalse(prefs.hasPassphrase)
            assertTrue(prefs.isPassphraseCorrupted)
            assertNull(repo.decryptBackupPassphrase())
        } finally {
            scope1.coroutineContext[Job]?.cancelAndJoin()
        }

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store2 = PreferenceDataStoreFactory.create(scope = scope2) { storeFile }
            val wrongKeyRepo = DataStoreWebDavSyncPreferencesRepository(store2, cipherB)
            val prefs = wrongKeyRepo.currentPreferences()
            assertFalse(prefs.hasPassword)
            assertTrue(prefs.isPasswordCorrupted)
            assertNull(wrongKeyRepo.resolveCredentials())
        } finally {
            scope2.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun urlAndDirectoryValidatorRejectsUnsafeInputs() {
        assertTrue(WebDavUrlValidator.validateServerUrl("https://webdav.example.invalid/dav/").isSuccess)
        assertTrue(WebDavUrlValidator.validateServerUrl("http://127.0.0.1:8080/dav").isSuccess)
        assertTrue(WebDavUrlValidator.validateServerUrl("ftp://webdav.example.invalid/dav").isFailure)
        assertTrue(WebDavUrlValidator.validateServerUrl("https://user:pass@webdav.example.invalid/dav").isFailure)
        assertTrue(WebDavUrlValidator.validateServerUrl("https://webdav.example.invalid/dav?a=1").isFailure)

        assertEquals("droplet-backups", WebDavUrlValidator.normalizeRemoteDirectory("").getOrThrow())
        assertEquals("finance/droplet", WebDavUrlValidator.normalizeRemoteDirectory("/finance/droplet/").getOrThrow())
        assertTrue(WebDavUrlValidator.normalizeRemoteDirectory("../escape").isFailure)
        assertTrue(WebDavUrlValidator.normalizeRemoteDirectory("a/../b").isFailure)

        try {
            WebDavUrlValidator.normalizeRemoteDirectory("bad?name").getOrThrow()
            fail("Expected failure for invalid query character in remote directory")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
