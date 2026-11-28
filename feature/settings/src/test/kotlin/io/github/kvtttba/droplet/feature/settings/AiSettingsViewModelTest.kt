package io.github.kvtttba.droplet.feature.settings

import io.github.kvtttba.droplet.core.ai.AiConnectionTestResult
import io.github.kvtttba.droplet.core.ai.AiImagePayload
import io.github.kvtttba.droplet.core.ai.AiParseResult
import io.github.kvtttba.droplet.core.ai.AiPromptContext
import io.github.kvtttba.droplet.core.ai.AiTranscriptionResult
import io.github.kvtttba.droplet.core.ai.OpenAiCompatibleClient
import io.github.kvtttba.droplet.core.security.AiApiKeyUpdateAction
import io.github.kvtttba.droplet.core.security.AiConfigPreferencesRepository
import io.github.kvtttba.droplet.core.security.AiEndpointConfig
import io.github.kvtttba.droplet.core.security.AiEndpointSaveInput
import io.github.kvtttba.droplet.core.security.AiEndpointSlot
import io.github.kvtttba.droplet.core.security.AiEndpointValidator
import io.github.kvtttba.droplet.core.security.AiResolvedEndpoint
import io.github.kvtttba.droplet.core.security.AiSecretMasker
import io.github.kvtttba.droplet.core.security.AiServiceConfig
import io.github.kvtttba.droplet.core.security.VoiceEngineMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeAiConfigPreferencesRepository : AiConfigPreferencesRepository {
        val configFlow = MutableStateFlow(AiServiceConfig())
        val storedKeys = mutableMapOf<AiEndpointSlot, String>()

        override val config: Flow<AiServiceConfig> = configFlow

        override suspend fun currentConfig(): AiServiceConfig = configFlow.value

        override suspend fun saveEndpoint(input: AiEndpointSaveInput) {
            val trimmedUrl = input.baseUrl.trim()
            val normalizedUrl = if (trimmedUrl.isEmpty()) {
                ""
            } else {
                AiEndpointValidator.validateBaseUrl(trimmedUrl).getOrThrow()
            }
            when (val action = input.apiKeyAction) {
                AiApiKeyUpdateAction.KeepExisting -> Unit
                AiApiKeyUpdateAction.Clear -> storedKeys.remove(input.slot)
                is AiApiKeyUpdateAction.Replace -> {
                    if (action.plaintextApiKey.isBlank()) {
                        storedKeys.remove(input.slot)
                    } else {
                        storedKeys[input.slot] = action.plaintextApiKey.trim()
                    }
                }
            }
            val key = storedKeys[input.slot]
            val updatedSlot = AiEndpointConfig(
                slot = input.slot,
                overrideEnabled = if (input.slot.isOverrideSlot) input.overrideEnabled else true,
                baseUrl = normalizedUrl,
                model = input.model.trim(),
                hasApiKey = !key.isNullOrBlank(),
                apiKeyMasked = key?.let(AiSecretMasker::mask).orEmpty(),
            )
            configFlow.update { current ->
                when (input.slot) {
                    AiEndpointSlot.DEFAULT -> current.copy(defaultEndpoint = updatedSlot)
                    AiEndpointSlot.VISION -> current.copy(visionEndpoint = updatedSlot)
                    AiEndpointSlot.TEXT -> current.copy(textEndpoint = updatedSlot)
                    AiEndpointSlot.VOICE -> current.copy(voiceEndpoint = updatedSlot)
                }
            }
        }

        override suspend fun setOverrideEnabled(slot: AiEndpointSlot, enabled: Boolean) {
            configFlow.update { current ->
                val updated = current.endpointFor(slot).copy(overrideEnabled = enabled)
                when (slot) {
                    AiEndpointSlot.DEFAULT -> current
                    AiEndpointSlot.VISION -> current.copy(visionEndpoint = updated)
                    AiEndpointSlot.TEXT -> current.copy(textEndpoint = updated)
                    AiEndpointSlot.VOICE -> current.copy(voiceEndpoint = updated)
                }
            }
        }

        override suspend fun clearEndpointApiKey(slot: AiEndpointSlot) {
            storedKeys.remove(slot)
            configFlow.update { current ->
                val updated = current.endpointFor(slot).copy(hasApiKey = false, apiKeyMasked = "")
                when (slot) {
                    AiEndpointSlot.DEFAULT -> current.copy(defaultEndpoint = updated)
                    AiEndpointSlot.VISION -> current.copy(visionEndpoint = updated)
                    AiEndpointSlot.TEXT -> current.copy(textEndpoint = updated)
                    AiEndpointSlot.VOICE -> current.copy(voiceEndpoint = updated)
                }
            }
        }

        override suspend fun setVoiceEngineMode(mode: VoiceEngineMode) {
            configFlow.update { it.copy(voiceEngineMode = mode) }
        }

        override suspend fun resolveEndpointForRequest(slot: AiEndpointSlot): AiResolvedEndpoint? {
            val effective = configFlow.value.resolveSlot(slot)
            val keySlot = effective.apiKeySourceSlot ?: return null
            val key = storedKeys[keySlot] ?: return null
            if (!effective.isConfigured) return null
            return AiResolvedEndpoint(
                requestedSlot = slot,
                effectiveSlot = effective.effectiveSlot,
                baseUrl = effective.baseUrl,
                model = effective.model,
                apiKey = key,
            )
        }

        override suspend fun decryptSlotApiKey(slot: AiEndpointSlot): String? = storedKeys[slot]

        override suspend fun clearAll() {
            storedKeys.clear()
            configFlow.value = AiServiceConfig()
        }
    }

    private class FakeOpenAiCompatibleClient : OpenAiCompatibleClient {
        var lastTestedEndpoint: AiResolvedEndpoint? = null
        var nextTestResult = AiConnectionTestResult(
            success = true,
            endpointSlot = AiEndpointSlot.DEFAULT,
            resolvedBaseUrl = "https://mock-ai.example.invalid/v1",
            model = "demo-model-mini",
            httpStatusCode = 200,
            probeMethod = "GET /models",
            latencyMs = 88L,
            message = "连接成功，已验证模型 demo-model-mini",
        )

        override suspend fun testConnection(endpoint: AiResolvedEndpoint): AiConnectionTestResult {
            lastTestedEndpoint = endpoint
            return nextTestResult.copy(
                endpointSlot = endpoint.effectiveSlot,
                resolvedBaseUrl = endpoint.baseUrl,
                model = endpoint.model,
            )
        }

        override suspend fun parseText(
            text: String,
            promptContext: AiPromptContext,
            endpoint: AiResolvedEndpoint,
        ): AiParseResult = error("Not used in settings test")

        override suspend fun parseImages(
            images: List<AiImagePayload>,
            promptContext: AiPromptContext,
            endpoint: AiResolvedEndpoint,
            userHint: String?,
        ): AiParseResult = error("Not used in settings test")

        override suspend fun transcribeAudio(
            audioBytes: ByteArray,
            fileName: String,
            mimeType: String,
            endpoint: AiResolvedEndpoint,
            language: String?,
        ): AiTranscriptionResult = error("Not used in settings test")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun validatesSavesDefaultConfigTestsConnectionAndPreservesKeyOnModelEdit() = runTest(testDispatcher) {
        val repo = FakeAiConfigPreferencesRepository()
        val client = FakeOpenAiCompatibleClient()
        val viewModel = AiSettingsViewModel(repo, client)

        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        // Invalid credentialed URL rejected
        viewModel.onBaseUrlChange(AiEndpointSlot.DEFAULT, "https://user:pass@mock-ai.example.invalid/v1")
        viewModel.onModelChange(AiEndpointSlot.DEFAULT, "demo-model-mini")
        viewModel.onApiKeyInputChange(AiEndpointSlot.DEFAULT, "demo-placeholder-key-0001")
        viewModel.saveSlot(AiEndpointSlot.DEFAULT)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.defaultDraft.validationError)
        assertFalse(repo.configFlow.value.defaultEndpoint.isConfigured)

        // Valid save encrypts key, clears transient input, exposes only masked suffix
        viewModel.onBaseUrlChange(AiEndpointSlot.DEFAULT, "https://mock-ai.example.invalid/v1")
        viewModel.saveSlot(AiEndpointSlot.DEFAULT)
        advanceUntilIdle()

        val savedState = viewModel.uiState.value.defaultDraft
        assertNull(savedState.validationError)
        assertEquals("", savedState.apiKeyInput)
        assertTrue(savedState.hasSavedApiKey)
        assertEquals("••••0001", savedState.savedApiKeyMasked)
        assertEquals("demo-placeholder-key-0001", repo.storedKeys[AiEndpointSlot.DEFAULT])

        // Edit model without retyping API key preserves saved key
        viewModel.onModelChange(AiEndpointSlot.DEFAULT, "demo-model-pro")
        viewModel.saveSlot(AiEndpointSlot.DEFAULT)
        advanceUntilIdle()
        assertEquals("demo-model-pro", repo.configFlow.value.defaultEndpoint.model)
        assertEquals("demo-placeholder-key-0001", repo.storedKeys[AiEndpointSlot.DEFAULT])

        // Run connection test using decrypted saved key
        viewModel.testConnection(AiEndpointSlot.DEFAULT)
        advanceUntilIdle()
        assertNotNull(client.lastTestedEndpoint)
        assertEquals("demo-model-pro", client.lastTestedEndpoint!!.model)
        assertEquals("demo-placeholder-key-0001", client.lastTestedEndpoint!!.apiKey)
        assertTrue(viewModel.uiState.value.defaultDraft.testResult?.success == true)

        // Clear saved key
        viewModel.clearSlotApiKey(AiEndpointSlot.DEFAULT)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.defaultDraft.hasSavedApiKey)
        assertNull(repo.storedKeys[AiEndpointSlot.DEFAULT])

        collector.cancel()
    }

    @Test
    fun overrideSlotInheritsDefaultAndVoiceEngineModeSwitchesCleanly() = runTest(testDispatcher) {
        val repo = FakeAiConfigPreferencesRepository()
        val client = FakeOpenAiCompatibleClient()
        val viewModel = AiSettingsViewModel(repo, client)

        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        // Configure default first
        viewModel.onBaseUrlChange(AiEndpointSlot.DEFAULT, "https://mock-ai.example.invalid/v1")
        viewModel.onModelChange(AiEndpointSlot.DEFAULT, "demo-model-mini")
        viewModel.onApiKeyInputChange(AiEndpointSlot.DEFAULT, "demo-placeholder-key-0001")
        viewModel.saveSlot(AiEndpointSlot.DEFAULT)
        advanceUntilIdle()

        // Enable VISION override with custom model only (inherits default baseUrl & key)
        viewModel.onToggleOverride(AiEndpointSlot.VISION, true)
        viewModel.onModelChange(AiEndpointSlot.VISION, "demo-vision-vl")
        viewModel.saveSlot(AiEndpointSlot.VISION)
        advanceUntilIdle()

        assertTrue(repo.configFlow.value.visionEndpoint.overrideEnabled)
        assertEquals("demo-vision-vl", repo.configFlow.value.visionEndpoint.model)

        // Connection test on VISION inherits default BaseURL & key while using demo-vision-vl
        viewModel.testConnection(AiEndpointSlot.VISION)
        advanceUntilIdle()
        assertEquals("https://mock-ai.example.invalid/v1", client.lastTestedEndpoint?.baseUrl)
        assertEquals("demo-vision-vl", client.lastTestedEndpoint?.model)
        assertEquals("demo-placeholder-key-0001", client.lastTestedEndpoint?.apiKey)

        // Toggle voice engine mode
        assertEquals(VoiceEngineMode.ANDROID_SYSTEM, viewModel.uiState.value.voiceEngineMode)
        viewModel.setVoiceEngineMode(VoiceEngineMode.CUSTOM_AI)
        advanceUntilIdle()
        assertEquals(VoiceEngineMode.CUSTOM_AI, viewModel.uiState.value.voiceEngineMode)

        collector.cancel()
    }
}
