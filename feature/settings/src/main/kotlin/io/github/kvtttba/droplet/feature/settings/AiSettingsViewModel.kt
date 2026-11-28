package io.github.kvtttba.droplet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.ai.AiConnectionTestResult
import io.github.kvtttba.droplet.core.ai.OpenAiCompatibleClient
import io.github.kvtttba.droplet.core.security.AiApiKeyUpdateAction
import io.github.kvtttba.droplet.core.security.AiConfigPreferencesRepository
import io.github.kvtttba.droplet.core.security.AiEndpointConfig
import io.github.kvtttba.droplet.core.security.AiEndpointSaveInput
import io.github.kvtttba.droplet.core.security.AiEndpointSlot
import io.github.kvtttba.droplet.core.security.AiEndpointValidator
import io.github.kvtttba.droplet.core.security.AiResolvedEndpoint
import io.github.kvtttba.droplet.core.security.AiServiceConfig
import io.github.kvtttba.droplet.core.security.VoiceEngineMode
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AiSlotDraftUiState(
    val slot: AiEndpointSlot,
    val overrideEnabled: Boolean = slot == AiEndpointSlot.DEFAULT,
    val baseUrl: String = "",
    val model: String = "",
    val apiKeyInput: String = "",
    val apiKeyVisible: Boolean = false,
    val hasSavedApiKey: Boolean = false,
    val savedApiKeyMasked: String = "",
    val isSavedKeyCorrupted: Boolean = false,
    val isDirty: Boolean = false,
    val validationError: String? = null,
    val isSaving: Boolean = false,
    val isTestingConnection: Boolean = false,
    val testResult: AiConnectionTestResult? = null,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && hasSavedApiKey && !isSavedKeyCorrupted

    override fun toString(): String =
        "AiSlotDraftUiState(slot=$slot, overrideEnabled=$overrideEnabled, baseUrl=$baseUrl, " +
            "model=$model, hasInputKey=${apiKeyInput.isNotEmpty()}, hasSavedApiKey=$hasSavedApiKey, " +
            "isDirty=$isDirty, isSaving=$isSaving, isTesting=$isTestingConnection)"

    companion object {
        fun fromConfig(config: AiEndpointConfig): AiSlotDraftUiState = AiSlotDraftUiState(
            slot = config.slot,
            overrideEnabled = config.overrideEnabled,
            baseUrl = config.baseUrl,
            model = config.model,
            apiKeyInput = "",
            apiKeyVisible = false,
            hasSavedApiKey = config.hasApiKey,
            savedApiKeyMasked = config.apiKeyMasked,
            isSavedKeyCorrupted = config.isKeyCorrupted,
            isDirty = false,
            validationError = null,
        )
    }
}

data class AiSettingsUiState(
    val savedConfig: AiServiceConfig = AiServiceConfig(),
    val defaultDraft: AiSlotDraftUiState = AiSlotDraftUiState(slot = AiEndpointSlot.DEFAULT, overrideEnabled = true),
    val visionDraft: AiSlotDraftUiState = AiSlotDraftUiState(slot = AiEndpointSlot.VISION, overrideEnabled = false),
    val textDraft: AiSlotDraftUiState = AiSlotDraftUiState(slot = AiEndpointSlot.TEXT, overrideEnabled = false),
    val voiceDraft: AiSlotDraftUiState = AiSlotDraftUiState(slot = AiEndpointSlot.VOICE, overrideEnabled = false),
    val voiceEngineMode: VoiceEngineMode = VoiceEngineMode.DEFAULT,
    val userMessage: String? = null,
) {
    fun draftFor(slot: AiEndpointSlot): AiSlotDraftUiState = when (slot) {
        AiEndpointSlot.DEFAULT -> defaultDraft
        AiEndpointSlot.VISION -> visionDraft
        AiEndpointSlot.TEXT -> textDraft
        AiEndpointSlot.VOICE -> voiceDraft
    }
}

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val aiConfigRepository: AiConfigPreferencesRepository,
    private val openAiClient: OpenAiCompatibleClient,
) : ViewModel() {

    private val draftsFlow = MutableStateFlow(
        AiEndpointSlot.entries.associateWith { slot ->
            AiSlotDraftUiState(
                slot = slot,
                overrideEnabled = slot == AiEndpointSlot.DEFAULT,
            )
        },
    )
    private val messageFlow = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            aiConfigRepository.config.collect { saved ->
                draftsFlow.update { currentMap ->
                    currentMap.mapValues { (slot, draft) ->
                        val savedSlot = saved.endpointFor(slot)
                        if (draft.isDirty) {
                            draft.copy(
                                hasSavedApiKey = savedSlot.hasApiKey,
                                savedApiKeyMasked = savedSlot.apiKeyMasked,
                                isSavedKeyCorrupted = savedSlot.isKeyCorrupted,
                            )
                        } else {
                            draft.copy(
                                overrideEnabled = savedSlot.overrideEnabled,
                                baseUrl = savedSlot.baseUrl,
                                model = savedSlot.model,
                                hasSavedApiKey = savedSlot.hasApiKey,
                                savedApiKeyMasked = savedSlot.apiKeyMasked,
                                isSavedKeyCorrupted = savedSlot.isKeyCorrupted,
                            )
                        }
                    }
                }
            }
        }
    }

    val uiState: StateFlow<AiSettingsUiState> = combine(
        aiConfigRepository.config,
        draftsFlow,
        messageFlow,
    ) { saved, drafts, message ->
        AiSettingsUiState(
            savedConfig = saved,
            defaultDraft = drafts.getValue(AiEndpointSlot.DEFAULT),
            visionDraft = drafts.getValue(AiEndpointSlot.VISION),
            textDraft = drafts.getValue(AiEndpointSlot.TEXT),
            voiceDraft = drafts.getValue(AiEndpointSlot.VOICE),
            voiceEngineMode = saved.voiceEngineMode,
            userMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AiSettingsUiState(),
    )

    fun onBaseUrlChange(slot: AiEndpointSlot, value: String) {
        updateSlotDraft(slot) {
            it.copy(
                baseUrl = value,
                isDirty = true,
                validationError = null,
            )
        }
    }

    fun onModelChange(slot: AiEndpointSlot, value: String) {
        updateSlotDraft(slot) {
            it.copy(
                model = value,
                isDirty = true,
                validationError = null,
            )
        }
    }

    fun onApiKeyInputChange(slot: AiEndpointSlot, value: String) {
        updateSlotDraft(slot) {
            it.copy(
                apiKeyInput = value,
                isDirty = true,
                validationError = null,
            )
        }
    }

    fun onToggleApiKeyVisible(slot: AiEndpointSlot) {
        updateSlotDraft(slot) {
            it.copy(apiKeyVisible = !it.apiKeyVisible)
        }
    }

    fun onToggleOverride(slot: AiEndpointSlot, enabled: Boolean) {
        if (!slot.isOverrideSlot) return
        updateSlotDraft(slot) {
            it.copy(
                overrideEnabled = enabled,
                validationError = null,
                testResult = if (enabled) it.testResult else null,
            )
        }
        viewModelScope.launch {
            aiConfigRepository.setOverrideEnabled(slot, enabled)
            messageFlow.value = if (enabled) {
                "已开启 ${slot.label} 独立覆盖配置"
            } else {
                "${slot.label} 已恢复复用默认全局配置"
            }
        }
    }

    fun saveSlot(slot: AiEndpointSlot) {
        val draft = draftsFlow.value.getValue(slot)
        if (draft.isSaving) return

        val trimmedUrl = draft.baseUrl.trim()
        val trimmedModel = draft.model.trim()
        val trimmedKey = draft.apiKeyInput.trim()

        if (slot == AiEndpointSlot.DEFAULT) {
            val urlResult = AiEndpointValidator.validateBaseUrl(trimmedUrl)
            if (urlResult.isFailure) {
                updateSlotDraft(slot) {
                    it.copy(validationError = urlResult.exceptionOrNull()?.message ?: "BaseURL 格式无效")
                }
                return
            }
            if (trimmedModel.isEmpty()) {
                updateSlotDraft(slot) { it.copy(validationError = "请输入默认模型名称（例如 gpt-4o-mini）") }
                return
            }
            val hasEffectiveKey = trimmedKey.isNotEmpty() || (draft.hasSavedApiKey && !draft.isSavedKeyCorrupted)
            if (!hasEffectiveKey) {
                updateSlotDraft(slot) { it.copy(validationError = "请输入 API Key（将经 Keystore 本地加密保存）") }
                return
            }
        } else {
            if (trimmedUrl.isNotEmpty()) {
                val urlResult = AiEndpointValidator.validateBaseUrl(trimmedUrl)
                if (urlResult.isFailure) {
                    updateSlotDraft(slot) {
                        it.copy(validationError = urlResult.exceptionOrNull()?.message ?: "BaseURL 格式无效")
                    }
                    return
                }
            }
            val savedDefault = uiState.value.savedConfig.defaultEndpoint
            val effectiveUrl = trimmedUrl.ifEmpty { savedDefault.baseUrl }
            val effectiveModel = trimmedModel.ifEmpty { savedDefault.model }
            val hasEffectiveKey = trimmedKey.isNotEmpty() ||
                (draft.hasSavedApiKey && !draft.isSavedKeyCorrupted) ||
                (savedDefault.hasApiKey && !savedDefault.isKeyCorrupted)

            if (draft.overrideEnabled) {
                if (effectiveUrl.isEmpty()) {
                    updateSlotDraft(slot) { it.copy(validationError = "请填写独立 BaseURL，或先保存默认全局 BaseURL") }
                    return
                }
                if (effectiveModel.isEmpty()) {
                    updateSlotDraft(slot) { it.copy(validationError = "请填写覆盖模型名称，或先配置默认模型") }
                    return
                }
                if (!hasEffectiveKey) {
                    updateSlotDraft(slot) { it.copy(validationError = "请填写独立 API Key，或先保存默认全局 API Key") }
                    return
                }
            }
        }

        val apiKeyAction = if (trimmedKey.isNotEmpty()) {
            AiApiKeyUpdateAction.Replace(trimmedKey)
        } else {
            AiApiKeyUpdateAction.KeepExisting
        }

        viewModelScope.launch {
            updateSlotDraft(slot) { it.copy(isSaving = true, validationError = null) }
            runCatching {
                aiConfigRepository.saveEndpoint(
                    AiEndpointSaveInput(
                        slot = slot,
                        overrideEnabled = if (slot.isOverrideSlot) draft.overrideEnabled else true,
                        baseUrl = trimmedUrl,
                        model = trimmedModel,
                        apiKeyAction = apiKeyAction,
                    ),
                )
            }.onSuccess {
                val latest = aiConfigRepository.currentConfig().endpointFor(slot)
                updateSlotDraft(slot) { current ->
                    current.copy(
                        overrideEnabled = latest.overrideEnabled,
                        baseUrl = latest.baseUrl,
                        model = latest.model,
                        apiKeyInput = "",
                        apiKeyVisible = false,
                        hasSavedApiKey = latest.hasApiKey,
                        savedApiKeyMasked = latest.apiKeyMasked,
                        isSavedKeyCorrupted = latest.isKeyCorrupted,
                        isDirty = false,
                        validationError = null,
                        isSaving = false,
                    )
                }
                messageFlow.value = if (slot == AiEndpointSlot.DEFAULT) {
                    "已加密保存默认全局 AI 配置"
                } else {
                    "已保存 ${slot.label} 独立覆盖配置"
                }
            }.onFailure { error ->
                updateSlotDraft(slot) {
                    it.copy(
                        isSaving = false,
                        validationError = error.message ?: "保存配置失败，请重试",
                    )
                }
            }
        }
    }

    fun clearSlotApiKey(slot: AiEndpointSlot) {
        viewModelScope.launch {
            aiConfigRepository.clearEndpointApiKey(slot)
            updateSlotDraft(slot) {
                it.copy(
                    apiKeyInput = "",
                    apiKeyVisible = false,
                    hasSavedApiKey = false,
                    savedApiKeyMasked = "",
                    isSavedKeyCorrupted = false,
                    validationError = null,
                    testResult = null,
                )
            }
            messageFlow.value = "已清除 ${slot.label} 的本地加密 API Key"
        }
    }

    fun testConnection(slot: AiEndpointSlot) {
        val draft = draftsFlow.value.getValue(slot)
        if (draft.isTestingConnection) return

        viewModelScope.launch {
            updateSlotDraft(slot) {
                it.copy(
                    isTestingConnection = true,
                    validationError = null,
                )
            }

            val resolved = resolveEndpointForTest(slot)
            if (resolved.isFailure) {
                updateSlotDraft(slot) {
                    it.copy(
                        isTestingConnection = false,
                        validationError = resolved.exceptionOrNull()?.message ?: "配置不完整，无法测试连接",
                    )
                }
                return@launch
            }

            val result = openAiClient.testConnection(resolved.getOrThrow())
            updateSlotDraft(slot) {
                it.copy(
                    isTestingConnection = false,
                    testResult = result,
                )
            }
            messageFlow.value = if (result.success) {
                "${slot.label} 连通性测试成功（${result.latencyMs} ms）"
            } else {
                "${slot.label} 连通性测试失败：${result.message}"
            }
        }
    }

    fun setVoiceEngineMode(mode: VoiceEngineMode) {
        viewModelScope.launch {
            aiConfigRepository.setVoiceEngineMode(mode)
            messageFlow.value = "语音转写引擎已切换为：${mode.label}"
        }
    }

    fun clearUserMessage() {
        messageFlow.value = null
    }

    private suspend fun resolveEndpointForTest(slot: AiEndpointSlot): Result<AiResolvedEndpoint> {
        val currentDrafts = draftsFlow.value
        val slotDraft = currentDrafts.getValue(slot)
        val defaultDraft = currentDrafts.getValue(AiEndpointSlot.DEFAULT)
        val savedConfig = aiConfigRepository.currentConfig()

        val rawUrl = if (slot == AiEndpointSlot.DEFAULT) {
            slotDraft.baseUrl.trim()
        } else {
            slotDraft.baseUrl.trim()
                .ifEmpty { defaultDraft.baseUrl.trim() }
                .ifEmpty { savedConfig.defaultEndpoint.baseUrl }
        }
        val validatedUrl = AiEndpointValidator.validateBaseUrl(rawUrl)
        if (validatedUrl.isFailure) {
            return Result.failure(
                IllegalArgumentException(
                    validatedUrl.exceptionOrNull()?.message ?: "请先填写有效的 BaseURL",
                ),
            )
        }

        val effectiveModel = if (slot == AiEndpointSlot.DEFAULT) {
            slotDraft.model.trim()
        } else {
            slotDraft.model.trim()
                .ifEmpty { defaultDraft.model.trim() }
                .ifEmpty { savedConfig.defaultEndpoint.model }
        }
        if (effectiveModel.isEmpty()) {
            return Result.failure(IllegalArgumentException("请先填写模型名称后再测试连接"))
        }

        val effectiveKey = resolveApiKeyForTest(slot, slotDraft, defaultDraft)
            ?: return Result.failure(IllegalArgumentException("请先填写或保存 API Key 后再测试连接"))

        return Result.success(
            AiResolvedEndpoint(
                requestedSlot = slot,
                effectiveSlot = if (slot == AiEndpointSlot.DEFAULT || slotDraft.overrideEnabled) {
                    slot
                } else {
                    AiEndpointSlot.DEFAULT
                },
                baseUrl = validatedUrl.getOrThrow(),
                model = effectiveModel,
                apiKey = effectiveKey,
            ),
        )
    }

    private suspend fun resolveApiKeyForTest(
        slot: AiEndpointSlot,
        slotDraft: AiSlotDraftUiState,
        defaultDraft: AiSlotDraftUiState,
    ): String? {
        slotDraft.apiKeyInput.trim().takeIf { it.isNotEmpty() }?.let { return it }
        aiConfigRepository.decryptSlotApiKey(slot)?.takeIf { it.isNotBlank() }?.let { return it }
        if (slot.isOverrideSlot) {
            defaultDraft.apiKeyInput.trim().takeIf { it.isNotEmpty() }?.let { return it }
            aiConfigRepository.decryptSlotApiKey(AiEndpointSlot.DEFAULT)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun updateSlotDraft(
        slot: AiEndpointSlot,
        transform: (AiSlotDraftUiState) -> AiSlotDraftUiState,
    ) {
        draftsFlow.update { map ->
            val current = map.getValue(slot)
            map + (slot to transform(current))
        }
    }
}
