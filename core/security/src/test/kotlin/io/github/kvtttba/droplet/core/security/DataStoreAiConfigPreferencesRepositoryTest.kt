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

class DataStoreAiConfigPreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newInMemoryAes256Key(): SecretKey = KeyGenerator.getInstance("AES").apply {
        init(256)
    }.generateKey()

    @Test
    fun encryptsApiKeyAtRestPersistsAcrossReloadAndResolvesOverrides() = runTest {
        val storeFile = File(tempFolder.newFolder(), "ai_config.preferences_pb")
        val aesKey = newInMemoryAes256Key()
        val cipher = KeystoreSecretCipher { aesKey }
        val defaultInputValue = "demo-placeholder-default-0001"
        val visionInputValue = "demo-placeholder-vision-9988"

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store1 = PreferenceDataStoreFactory.create(scope = scope1) { storeFile }
            val repo1 = DataStoreAiConfigPreferencesRepository(store1, cipher)

            val initial = repo1.currentConfig()
            assertFalse(initial.hasAnyConfiguredEndpoint)
            assertEquals(VoiceEngineMode.ANDROID_SYSTEM, initial.voiceEngineMode)
            assertNull(repo1.resolveEndpointForRequest(AiEndpointSlot.DEFAULT))

            // Save default global config
            repo1.saveEndpoint(
                AiEndpointSaveInput(
                    slot = AiEndpointSlot.DEFAULT,
                    baseUrl = "https://mock-ai.example.invalid/v1/",
                    model = "demo-model-mini",
                    apiKeyAction = AiApiKeyUpdateAction.Replace(defaultInputValue),
                ),
            )

            // Enable partial TEXT override (custom model only -> inherits default BaseURL & key)
            repo1.saveEndpoint(
                AiEndpointSaveInput(
                    slot = AiEndpointSlot.TEXT,
                    overrideEnabled = true,
                    baseUrl = "",
                    model = "demo-text-pro",
                    apiKeyAction = AiApiKeyUpdateAction.KeepExisting,
                ),
            )

            // Enable full VISION override with its own endpoint & encrypted key
            repo1.saveEndpoint(
                AiEndpointSaveInput(
                    slot = AiEndpointSlot.VISION,
                    overrideEnabled = true,
                    baseUrl = "https://mock-vision.example.invalid/v1",
                    model = "demo-vision-vl",
                    apiKeyAction = AiApiKeyUpdateAction.Replace(visionInputValue),
                ),
            )

            repo1.setVoiceEngineMode(VoiceEngineMode.CUSTOM_AI)

            // Verify raw persisted file never contains plaintext keys
            val rawDiskText = storeFile.readBytes().toString(Charsets.UTF_8)
            assertFalse("Plaintext default key must never touch disk", rawDiskText.contains(defaultInputValue))
            assertFalse("Plaintext vision key must never touch disk", rawDiskText.contains(visionInputValue))
        } finally {
            scope1.coroutineContext[Job]?.cancelAndJoin()
        }

        // Reload in fresh DataStore instance with same key
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store2 = PreferenceDataStoreFactory.create(scope = scope2) { storeFile }
            val repo2 = DataStoreAiConfigPreferencesRepository(store2, cipher)

            val reloaded = repo2.config.first()
            assertTrue(reloaded.defaultEndpoint.isConfigured)
            assertEquals("https://mock-ai.example.invalid/v1", reloaded.defaultEndpoint.baseUrl)
            assertEquals("demo-model-mini", reloaded.defaultEndpoint.model)
            assertEquals("••••0001", reloaded.defaultEndpoint.apiKeyMasked)
            assertEquals(VoiceEngineMode.CUSTOM_AI, reloaded.voiceEngineMode)
            assertEquals(2, reloaded.enabledOverrideCount)

            // Resolve DEFAULT
            val resolvedDefault = repo2.resolveEndpointForRequest(AiEndpointSlot.DEFAULT)
            assertNotNull(resolvedDefault)
            assertEquals(AiEndpointSlot.DEFAULT, resolvedDefault!!.effectiveSlot)
            assertEquals("https://mock-ai.example.invalid/v1", resolvedDefault.baseUrl)
            assertEquals("demo-model-mini", resolvedDefault.model)
            assertEquals(defaultInputValue, resolvedDefault.apiKey)

            // Resolve TEXT (partial override inherits default baseUrl & default key)
            val resolvedText = repo2.resolveEndpointForRequest(AiEndpointSlot.TEXT)
            assertNotNull(resolvedText)
            assertEquals(AiEndpointSlot.TEXT, resolvedText!!.effectiveSlot)
            assertEquals("https://mock-ai.example.invalid/v1", resolvedText.baseUrl)
            assertEquals("demo-text-pro", resolvedText.model)
            assertEquals(defaultInputValue, resolvedText.apiKey)

            // Resolve VISION (full independent override)
            val resolvedVision = repo2.resolveEndpointForRequest(AiEndpointSlot.VISION)
            assertNotNull(resolvedVision)
            assertEquals(AiEndpointSlot.VISION, resolvedVision!!.effectiveSlot)
            assertEquals("https://mock-vision.example.invalid/v1", resolvedVision.baseUrl)
            assertEquals("demo-vision-vl", resolvedVision.model)
            assertEquals(visionInputValue, resolvedVision.apiKey)

            // Resolve VOICE (disabled override -> falls back entirely to DEFAULT)
            val resolvedVoice = repo2.resolveEndpointForRequest(AiEndpointSlot.VOICE)
            assertNotNull(resolvedVoice)
            assertEquals(AiEndpointSlot.DEFAULT, resolvedVoice!!.effectiveSlot)
            assertEquals("demo-model-mini", resolvedVoice.model)
            assertEquals(defaultInputValue, resolvedVoice.apiKey)

            // Update default model with KeepExisting preserves existing key
            repo2.saveEndpoint(
                AiEndpointSaveInput(
                    slot = AiEndpointSlot.DEFAULT,
                    baseUrl = "https://mock-ai.example.invalid/v1",
                    model = "demo-model-v2",
                    apiKeyAction = AiApiKeyUpdateAction.KeepExisting,
                ),
            )
            assertEquals(defaultInputValue, repo2.decryptSlotApiKey(AiEndpointSlot.DEFAULT))
            assertEquals("demo-model-v2", repo2.currentConfig().defaultEndpoint.model)

            // Clear key removes stored ciphertext
            repo2.clearEndpointApiKey(AiEndpointSlot.VISION)
            val afterClear = repo2.currentConfig()
            assertFalse(afterClear.visionEndpoint.hasApiKey)
            assertNull(repo2.decryptSlotApiKey(AiEndpointSlot.VISION))
            // Enabled VISION override with cleared own key falls back to default key
            val visionFallbackKey = repo2.resolveEndpointForRequest(AiEndpointSlot.VISION)
            assertEquals(defaultInputValue, visionFallbackKey?.apiKey)
        } finally {
            scope2.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun perSlotAadIsolationAndWrongKeyFailSafelyWithoutLeakingSecret() = runTest {
        val storeFile = File(tempFolder.newFolder(), "aad_isolation.preferences_pb")
        val keyA = newInMemoryAes256Key()
        val keyB = newInMemoryAes256Key()
        val cipherA = KeystoreSecretCipher { keyA }
        val cipherB = KeystoreSecretCipher { keyB }
        val sampleInput = "demo-aad-bound-value-7744"

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope1) { storeFile }
            val repo = DataStoreAiConfigPreferencesRepository(store, cipherA)

            repo.saveEndpoint(
                AiEndpointSaveInput(
                    slot = AiEndpointSlot.DEFAULT,
                    baseUrl = "https://mock-ai.example.invalid/v1",
                    model = "demo-model",
                    apiKeyAction = AiApiKeyUpdateAction.Replace(sampleInput),
                ),
            )

            // Copy DEFAULT ciphertext into VISION slot directly -> AAD mismatch must block decryption
            val defaultCiphertext = store.data.first()[
                DataStoreAiConfigPreferencesRepository.Keys.forSlot(AiEndpointSlot.DEFAULT).encryptedApiKey,
            ]
            assertNotNull(defaultCiphertext)
            store.edit { prefs ->
                val visionKeys = DataStoreAiConfigPreferencesRepository.Keys.forSlot(AiEndpointSlot.VISION)
                prefs[visionKeys.overrideEnabled] = true
                prefs[visionKeys.baseUrl] = "https://mock-vision.example.invalid/v1"
                prefs[visionKeys.model] = "demo-vision"
                prefs[visionKeys.encryptedApiKey] = defaultCiphertext!!
            }

            val config = repo.currentConfig()
            assertTrue(config.defaultEndpoint.hasApiKey)
            assertFalse(config.visionEndpoint.hasApiKey)
            assertTrue(config.visionEndpoint.isKeyCorrupted)
            assertNull(repo.decryptSlotApiKey(AiEndpointSlot.VISION))
        } finally {
            scope1.coroutineContext[Job]?.cancelAndJoin()
        }

        // Opening the same store with a different Keystore key marks stored secret corrupted safely
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store2 = PreferenceDataStoreFactory.create(scope = scope2) { storeFile }
            val wrongKeyRepo = DataStoreAiConfigPreferencesRepository(store2, cipherB)

            val config = wrongKeyRepo.currentConfig()
            assertFalse(config.defaultEndpoint.hasApiKey)
            assertTrue(config.defaultEndpoint.isKeyCorrupted)
            assertNull(wrongKeyRepo.resolveEndpointForRequest(AiEndpointSlot.DEFAULT))
            assertFalse(config.toString().contains(sampleInput))
        } finally {
            scope2.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun validatorRejectsUnsafeUrlsAndToStringRedactsAllCredentials() = runTest {
        val sampleCredential = "demo-redaction-check-5566"
        val resolved = AiResolvedEndpoint(
            requestedSlot = AiEndpointSlot.DEFAULT,
            effectiveSlot = AiEndpointSlot.DEFAULT,
            baseUrl = "https://mock-ai.example.invalid/v1",
            model = "demo-model",
            apiKey = sampleCredential,
        )
        assertFalse(resolved.toString().contains(sampleCredential))
        assertTrue(resolved.toString().contains("[REDACTED]"))

        val saveInput = AiEndpointSaveInput(
            slot = AiEndpointSlot.DEFAULT,
            baseUrl = "https://mock-ai.example.invalid/v1",
            model = "demo-model",
            apiKeyAction = AiApiKeyUpdateAction.Replace(sampleCredential),
        )
        assertFalse(saveInput.toString().contains(sampleCredential))

        assertTrue(AiEndpointValidator.validateBaseUrl("https://mock-ai.example.invalid/v1/").isSuccess)
        assertTrue(AiEndpointValidator.validateBaseUrl("http://127.0.0.1:8080/v1").isSuccess)
        assertTrue(AiEndpointValidator.validateBaseUrl("ftp://mock-ai.example.invalid/v1").isFailure)
        assertTrue(AiEndpointValidator.validateBaseUrl("https://user:pass@mock-ai.example.invalid/v1").isFailure)
        assertTrue(AiEndpointValidator.validateBaseUrl("https://mock-ai.example.invalid/v1?k=v").isFailure)
        assertTrue(AiEndpointValidator.validateBaseUrl("not a url").isFailure)

        val storeFile = File(tempFolder.newFolder(), "reject_url.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
            val repo = DataStoreAiConfigPreferencesRepository(store, KeystoreSecretCipher { newInMemoryAes256Key() })
            try {
                repo.saveEndpoint(
                    AiEndpointSaveInput(
                        slot = AiEndpointSlot.DEFAULT,
                        baseUrl = "https://user:pass@mock-ai.example.invalid/v1",
                        model = "demo-model",
                        apiKeyAction = AiApiKeyUpdateAction.KeepExisting,
                    ),
                )
                fail("Expected IllegalArgumentException for credentialed BaseURL")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
