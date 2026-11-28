package io.github.kvtttba.droplet.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Local DataStore persistence for OpenAI-compatible AI endpoint configuration.
 * All API keys are encrypted via [SecretCipher] (Android Keystore AES-256-GCM with per-slot AAD)
 * prior to touching disk and are never stored in plaintext or logged.
 */
interface AiConfigPreferencesRepository {
    val config: Flow<AiServiceConfig>

    suspend fun currentConfig(): AiServiceConfig

    suspend fun saveEndpoint(input: AiEndpointSaveInput)

    suspend fun setOverrideEnabled(slot: AiEndpointSlot, enabled: Boolean)

    suspend fun clearEndpointApiKey(slot: AiEndpointSlot)

    suspend fun setVoiceEngineMode(mode: VoiceEngineMode)

    suspend fun resolveEndpointForRequest(slot: AiEndpointSlot): AiResolvedEndpoint?

    suspend fun decryptSlotApiKey(slot: AiEndpointSlot): String?

    suspend fun clearAll()
}

class DataStoreAiConfigPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
    private val secretCipher: SecretCipher,
) : AiConfigPreferencesRepository {

    override val config: Flow<AiServiceConfig> = rawPreferencesFlow()
        .map(::decodeConfig)
        .distinctUntilChanged()

    override suspend fun currentConfig(): AiServiceConfig = config.first()

    override suspend fun saveEndpoint(input: AiEndpointSaveInput) {
        val trimmedBaseUrl = input.baseUrl.trim()
        val normalizedBaseUrl = if (trimmedBaseUrl.isEmpty()) {
            ""
        } else {
            AiEndpointValidator.validateBaseUrl(trimmedBaseUrl).getOrThrow()
        }
        val normalizedModel = input.model.trim()
        val encryptedReplacement: String? = when (val action = input.apiKeyAction) {
            AiApiKeyUpdateAction.KeepExisting -> null
            AiApiKeyUpdateAction.Clear -> null
            is AiApiKeyUpdateAction.Replace -> {
                val trimmedKey = action.plaintextApiKey.trim()
                if (trimmedKey.isEmpty()) {
                    null
                } else {
                    secretCipher.encrypt(
                        plaintext = trimmedKey,
                        associatedData = input.slot.aadContext,
                    )
                }
            }
        }

        dataStore.edit { prefs ->
            val slotKeys = Keys.forSlot(input.slot)
            if (input.slot.isOverrideSlot) {
                prefs[slotKeys.overrideEnabled] = input.overrideEnabled
            }
            if (normalizedBaseUrl.isEmpty()) {
                prefs.remove(slotKeys.baseUrl)
            } else {
                prefs[slotKeys.baseUrl] = normalizedBaseUrl
            }
            if (normalizedModel.isEmpty()) {
                prefs.remove(slotKeys.model)
            } else {
                prefs[slotKeys.model] = normalizedModel
            }
            when (val action = input.apiKeyAction) {
                AiApiKeyUpdateAction.KeepExisting -> Unit
                AiApiKeyUpdateAction.Clear -> prefs.remove(slotKeys.encryptedApiKey)
                is AiApiKeyUpdateAction.Replace -> {
                    if (encryptedReplacement == null) {
                        prefs.remove(slotKeys.encryptedApiKey)
                    } else {
                        prefs[slotKeys.encryptedApiKey] = encryptedReplacement
                    }
                }
            }
        }
    }

    override suspend fun setOverrideEnabled(slot: AiEndpointSlot, enabled: Boolean) {
        require(slot.isOverrideSlot) { "DEFAULT endpoint slot cannot toggle overrideEnabled" }
        dataStore.edit { prefs ->
            prefs[Keys.forSlot(slot).overrideEnabled] = enabled
        }
    }

    override suspend fun clearEndpointApiKey(slot: AiEndpointSlot) {
        dataStore.edit { prefs ->
            prefs.remove(Keys.forSlot(slot).encryptedApiKey)
        }
    }

    override suspend fun setVoiceEngineMode(mode: VoiceEngineMode) {
        dataStore.edit { prefs ->
            prefs[Keys.VOICE_ENGINE_MODE] = mode.storageKey
        }
    }

    override suspend fun resolveEndpointForRequest(slot: AiEndpointSlot): AiResolvedEndpoint? {
        val prefs = rawPreferencesFlow().first()
        val decoded = decodeConfig(prefs)
        val effective = decoded.resolveSlot(slot)
        val keySlot = effective.apiKeySourceSlot ?: return null
        if (!effective.isConfigured) return null
        val decryptedKey = decryptSlotKeyFromPrefs(prefs, keySlot)?.takeIf { it.isNotBlank() }
            ?: return null
        return AiResolvedEndpoint(
            requestedSlot = slot,
            effectiveSlot = effective.effectiveSlot,
            baseUrl = effective.baseUrl,
            model = effective.model,
            apiKey = decryptedKey,
        )
    }

    override suspend fun decryptSlotApiKey(slot: AiEndpointSlot): String? {
        val prefs = rawPreferencesFlow().first()
        return decryptSlotKeyFromPrefs(prefs, slot)?.takeIf { it.isNotBlank() }
    }

    override suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private fun rawPreferencesFlow(): Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    private fun decodeConfig(prefs: Preferences): AiServiceConfig = AiServiceConfig(
        defaultEndpoint = decodeSlot(prefs, AiEndpointSlot.DEFAULT),
        visionEndpoint = decodeSlot(prefs, AiEndpointSlot.VISION),
        textEndpoint = decodeSlot(prefs, AiEndpointSlot.TEXT),
        voiceEndpoint = decodeSlot(prefs, AiEndpointSlot.VOICE),
        voiceEngineMode = VoiceEngineMode.fromStorageKey(prefs[Keys.VOICE_ENGINE_MODE]),
    )

    private fun decodeSlot(prefs: Preferences, slot: AiEndpointSlot): AiEndpointConfig {
        val slotKeys = Keys.forSlot(slot)
        val overrideEnabled = if (slot.isOverrideSlot) {
            prefs[slotKeys.overrideEnabled] ?: false
        } else {
            true
        }
        val rawBaseUrl = prefs[slotKeys.baseUrl]?.trim().orEmpty()
        val safeBaseUrl = if (rawBaseUrl.isEmpty()) {
            ""
        } else {
            AiEndpointValidator.validateBaseUrl(rawBaseUrl).getOrDefault("")
        }
        val model = prefs[slotKeys.model]?.trim().orEmpty()
        val encrypted = prefs[slotKeys.encryptedApiKey]?.trim().orEmpty()
        if (encrypted.isEmpty()) {
            return AiEndpointConfig(
                slot = slot,
                overrideEnabled = overrideEnabled,
                baseUrl = safeBaseUrl,
                model = model,
                hasApiKey = false,
                apiKeyMasked = "",
                isKeyCorrupted = false,
            )
        }
        return try {
            val decrypted = secretCipher.decrypt(encrypted, associatedData = slot.aadContext).trim()
            if (decrypted.isEmpty()) {
                AiEndpointConfig(
                    slot = slot,
                    overrideEnabled = overrideEnabled,
                    baseUrl = safeBaseUrl,
                    model = model,
                    hasApiKey = false,
                    apiKeyMasked = "",
                    isKeyCorrupted = false,
                )
            } else {
                AiEndpointConfig(
                    slot = slot,
                    overrideEnabled = overrideEnabled,
                    baseUrl = safeBaseUrl,
                    model = model,
                    hasApiKey = true,
                    apiKeyMasked = AiSecretMasker.mask(decrypted),
                    isKeyCorrupted = false,
                )
            }
        } catch (_: SecretCipherException) {
            AiEndpointConfig(
                slot = slot,
                overrideEnabled = overrideEnabled,
                baseUrl = safeBaseUrl,
                model = model,
                hasApiKey = false,
                apiKeyMasked = "",
                isKeyCorrupted = true,
            )
        }
    }

    private fun decryptSlotKeyFromPrefs(prefs: Preferences, slot: AiEndpointSlot): String? {
        val encrypted = prefs[Keys.forSlot(slot).encryptedApiKey]?.trim().orEmpty()
        if (encrypted.isEmpty()) return null
        return try {
            secretCipher.decrypt(encrypted, associatedData = slot.aadContext).trim()
        } catch (_: SecretCipherException) {
            null
        }
    }

    internal data class SlotPreferenceKeys(
        val overrideEnabled: Preferences.Key<Boolean>,
        val baseUrl: Preferences.Key<String>,
        val model: Preferences.Key<String>,
        val encryptedApiKey: Preferences.Key<String>,
    )

    internal object Keys {
        val VOICE_ENGINE_MODE = stringPreferencesKey("ai_voice_engine_mode")

        private val bySlot: Map<AiEndpointSlot, SlotPreferenceKeys> = AiEndpointSlot.entries.associateWith { slot ->
            val prefix = "ai_slot_${slot.storageKey.lowercase()}"
            SlotPreferenceKeys(
                overrideEnabled = booleanPreferencesKey("${prefix}_override_enabled"),
                baseUrl = stringPreferencesKey("${prefix}_base_url"),
                model = stringPreferencesKey("${prefix}_model"),
                encryptedApiKey = stringPreferencesKey("${prefix}_api_key_enc"),
            )
        }

        fun forSlot(slot: AiEndpointSlot): SlotPreferenceKeys = bySlot.getValue(slot)
    }
}
