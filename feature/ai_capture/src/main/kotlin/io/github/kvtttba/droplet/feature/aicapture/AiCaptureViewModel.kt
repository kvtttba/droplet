package io.github.kvtttba.droplet.feature.aicapture

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.ai.AiDraftAttachmentRef
import io.github.kvtttba.droplet.core.ai.ReceiptImageProcessor
import io.github.kvtttba.droplet.core.database.repository.AiCaptureOrchestrator
import io.github.kvtttba.droplet.core.database.repository.AiCaptureOutcome
import io.github.kvtttba.droplet.core.database.repository.AiDraftRepository
import io.github.kvtttba.droplet.core.model.AiDraftInputType
import io.github.kvtttba.droplet.core.security.AiConfigPreferencesRepository
import io.github.kvtttba.droplet.core.security.VoiceEngineMode
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AiCaptureViewModel @Inject constructor(
    private val orchestrator: AiCaptureOrchestrator,
    private val aiDraftRepository: AiDraftRepository,
    private val aiConfigPreferencesRepository: AiConfigPreferencesRepository,
    private val receiptImageProcessor: ReceiptImageProcessor,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialTab: AiCaptureTab =
        AiCaptureTab.fromRouteKey(savedStateHandle[NAV_ARG_TAB])

    private val _uiState = MutableStateFlow(AiCaptureUiState(activeTab = initialTab))
    val uiState: StateFlow<AiCaptureUiState> = _uiState.asStateFlow()

    private var lastConsumedShareId: Long? = null

    init {
        viewModelScope.launch {
            aiConfigPreferencesRepository.config.collect { config ->
                _uiState.update { it.copy(aiServiceConfig = config) }
            }
        }
        viewModelScope.launch {
            aiDraftRepository.observeActiveDrafts().collect { drafts ->
                _uiState.update { state ->
                    state.copy(
                        activeDrafts = drafts.map { AiDraftSummaryItem.fromDraft(it) },
                    )
                }
            }
        }
    }

    fun selectTab(tab: AiCaptureTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun onTextInputChange(value: String) {
        _uiState.update { it.copy(textInput = value) }
    }

    fun applyTextExample(example: String) {
        _uiState.update { it.copy(textInput = example, activeTab = AiCaptureTab.TEXT) }
    }

    fun onImageHintChange(value: String) {
        _uiState.update { it.copy(imageHint = value) }
    }

    fun onVoiceTranscriptChange(value: String) {
        _uiState.update { it.copy(voiceTranscript = value) }
    }

    fun setVoiceEngineMode(mode: VoiceEngineMode) {
        viewModelScope.launch {
            aiConfigPreferencesRepository.setVoiceEngineMode(mode)
        }
    }

    fun onSystemVoiceRecognized(transcript: String) {
        val cleaned = transcript.trim()
        if (cleaned.isEmpty()) {
            showMessage("未识别到有效语音内容，可重试或直接编辑下方文本")
            return
        }
        _uiState.update {
            it.copy(
                activeTab = AiCaptureTab.VOICE,
                voiceTranscript = cleaned,
                userMessage = "系统语音转写完成，点击「AI 解析」即可生成草稿",
            )
        }
    }

    fun onVoiceRecordingStateChanged(recording: Boolean, elapsedSeconds: Int = 0) {
        _uiState.update {
            it.copy(
                isRecordingVoice = recording,
                recordingElapsedSeconds = elapsedSeconds,
            )
        }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(userMessage = message) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun consumePendingNavigation() {
        _uiState.update { it.copy(pendingNavigateToDraftId = null) }
    }

    fun consumeSharedPayload(payload: PendingAiSharePayload?) {
        if (payload == null || payload.id == lastConsumedShareId) return
        lastConsumedShareId = payload.id
        when {
            payload.hasImages -> {
                _uiState.update {
                    it.copy(
                        activeTab = AiCaptureTab.IMAGE,
                        imageHint = payload.sharedText?.trim().orEmpty().ifEmpty { it.imageHint },
                    )
                }
                val uris = payload.imageUriStrings.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
                if (uris.isNotEmpty()) {
                    addImagesFromUris(uris = uris, fromSystemShare = true)
                }
            }
            payload.hasText -> {
                _uiState.update {
                    it.copy(
                        activeTab = AiCaptureTab.TEXT,
                        textInput = payload.sharedText!!.trim(),
                        userMessage = "已接收系统分享文本，可点击「AI 智能解析」或先离线暂存",
                    )
                }
            }
        }
    }

    fun addImagesFromUris(
        uris: List<Uri>,
        fromSystemShare: Boolean = false,
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingImages = true, activeTab = AiCaptureTab.IMAGE) }
            try {
                val remaining = (MAX_SELECTED_IMAGES - _uiState.value.selectedAttachments.size).coerceAtLeast(0)
                if (remaining == 0) {
                    _uiState.update {
                        it.copy(
                            isProcessingImages = false,
                            userMessage = "单次最多附带 $MAX_SELECTED_IMAGES 张图片，请先移除部分图片",
                        )
                    }
                    return@launch
                }
                val acceptedUris = uris.take(remaining)
                val processed = receiptImageProcessor.processUris(acceptedUris)
                val newRefs = processed.map { it.attachmentRef }
                val totalSlices = newRefs.sumOf { it.sliceCount }
                val msg = buildString {
                    if (fromSystemShare) append("已接收分享的 ") else append("已导入 ")
                    append("${newRefs.size} 张图片（已自动剥离 EXIF")
                    if (totalSlices > newRefs.size) {
                        append("，含长图智能切片共 $totalSlices 段")
                    }
                    append("）")
                }
                _uiState.update { state ->
                    state.copy(
                        selectedAttachments = state.selectedAttachments + newRefs,
                        isProcessingImages = false,
                        userMessage = msg,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isProcessingImages = false,
                        userMessage = e.message ?: "处理图片失败，请重新选择",
                    )
                }
            }
        }
    }

    fun removeSelectedImage(attachment: AiDraftAttachmentRef) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    selectedAttachments = state.selectedAttachments.filterNot {
                        it.localRelPath == attachment.localRelPath
                    },
                )
            }
            receiptImageProcessor.deleteReceiptFiles(listOf(attachment))
        }
    }

    fun submitTextCapture(saveOfflineOnly: Boolean) {
        val text = _uiState.value.textInput.trim()
        if (text.isEmpty()) {
            showMessage("请先输入一句话记账内容，例如：早饭8块微信")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            try {
                val outcome = orchestrator.submitTextCapture(
                    rawText = text,
                    saveOfflineOnly = saveOfflineOnly,
                    inputType = AiDraftInputType.TEXT,
                )
                handleCaptureOutcome(
                    outcome = outcome,
                    clearInputs = { it.copy(textInput = "") },
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        userMessage = e.message ?: "提交文本记账失败",
                    )
                }
            }
        }
    }

    fun submitImageCapture(saveOfflineOnly: Boolean) {
        val attachments = _uiState.value.selectedAttachments
        if (attachments.isEmpty()) {
            showMessage("请先拍照或从相册选择小票/账单截图")
            return
        }
        val hint = _uiState.value.imageHint.trim().takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            try {
                val outcome = orchestrator.submitImageCapture(
                    attachments = attachments,
                    userHint = hint,
                    saveOfflineOnly = saveOfflineOnly,
                )
                handleCaptureOutcome(
                    outcome = outcome,
                    clearInputs = { it.copy(selectedAttachments = emptyList(), imageHint = "") },
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        userMessage = e.message ?: "提交图片识别失败",
                    )
                }
            }
        }
    }

    fun submitVoiceTranscript(saveOfflineOnly: Boolean) {
        val transcript = _uiState.value.voiceTranscript.trim()
        if (transcript.isEmpty()) {
            showMessage("请先录音转写或在输入框填写语音文本")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            try {
                val outcome = orchestrator.submitTextCapture(
                    rawText = transcript,
                    saveOfflineOnly = saveOfflineOnly,
                    inputType = AiDraftInputType.VOICE,
                )
                handleCaptureOutcome(
                    outcome = outcome,
                    clearInputs = { it.copy(voiceTranscript = "") },
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        userMessage = e.message ?: "提交语音记账失败",
                    )
                }
            }
        }
    }

    fun submitRecordedVoiceFile(audioFile: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, isRecordingVoice = false) }
            try {
                val bytes = audioFile.readBytes()
                val result = orchestrator.submitCustomVoiceAudio(
                    audioBytes = bytes,
                    fileName = audioFile.name,
                    mimeType = "audio/mp4",
                )
                result.fold(
                    onSuccess = { outcome ->
                        handleCaptureOutcome(
                            outcome = outcome,
                            clearInputs = {
                                it.copy(voiceTranscript = outcome.draft.rawText.orEmpty())
                            },
                        )
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isSubmitting = false,
                                userMessage = error.message ?: "AI 语音转写失败，请检查语音槽位配置",
                            )
                        }
                    },
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        userMessage = e.message ?: "读取录音文件失败",
                    )
                }
            } finally {
                if (audioFile.exists()) {
                    audioFile.delete()
                }
            }
        }
    }

    fun retryDraft(draftId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(retryingDraftId = draftId) }
            try {
                val outcome = orchestrator.retryDraft(draftId)
                handleCaptureOutcome(
                    outcome = outcome,
                    clearInputs = { it },
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        retryingDraftId = null,
                        userMessage = e.message ?: "重新解析失败",
                    )
                }
            }
        }
    }

    fun convertDraftToManualReview(draftId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(retryingDraftId = draftId) }
            orchestrator.convertDraftToManualReview(draftId).fold(
                onSuccess = { readyDraft ->
                    _uiState.update {
                        it.copy(
                            retryingDraftId = null,
                            pendingNavigateToDraftId = readyDraft.id,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            retryingDraftId = null,
                            userMessage = error.message ?: "转入人工核对失败",
                        )
                    }
                },
            )
        }
    }

    fun deleteDraft(draftId: String) {
        viewModelScope.launch {
            aiDraftRepository.deleteDraft(draftId)
            showMessage("已删除草稿")
        }
    }

    private fun handleCaptureOutcome(
        outcome: AiCaptureOutcome,
        clearInputs: (AiCaptureUiState) -> AiCaptureUiState,
    ) {
        when (outcome) {
            is AiCaptureOutcome.ParsedReady -> {
                _uiState.update { state ->
                    clearInputs(state).copy(
                        isSubmitting = false,
                        retryingDraftId = null,
                        pendingNavigateToDraftId = outcome.draft.id,
                        userMessage = "识别成功，共拆出 ${outcome.batch.items.size} 笔待确认草稿",
                    )
                }
            }

            is AiCaptureOutcome.SavedOffline -> {
                _uiState.update { state ->
                    clearInputs(state).copy(
                        isSubmitting = false,
                        retryingDraftId = null,
                        userMessage = outcome.message,
                    )
                }
            }

            is AiCaptureOutcome.NeedsConfiguration -> {
                _uiState.update { state ->
                    clearInputs(state).copy(
                        isSubmitting = false,
                        retryingDraftId = null,
                        userMessage = outcome.message,
                    )
                }
            }

            is AiCaptureOutcome.FailedStoredInDrafts -> {
                _uiState.update { state ->
                    clearInputs(state).copy(
                        isSubmitting = false,
                        retryingDraftId = null,
                        userMessage = outcome.message,
                    )
                }
            }
        }
    }

    companion object {
        const val NAV_ARG_TAB = "tab"
        private const val MAX_SELECTED_IMAGES = 9

        val TEXT_EXAMPLES: List<String> = listOf(
            "早饭8块微信，中午打车25刷信用卡",
            "超市买水果42.5支付宝，顺便给家里买了日用品68",
            "收到工资12800进储蓄卡",
        )
    }
}
