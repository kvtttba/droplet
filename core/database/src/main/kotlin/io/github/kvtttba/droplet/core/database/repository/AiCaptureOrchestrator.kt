package io.github.kvtttba.droplet.core.database.repository

import io.github.kvtttba.droplet.core.ai.AiClientException
import io.github.kvtttba.droplet.core.ai.AiDraftAttachmentRef
import io.github.kvtttba.droplet.core.ai.AiDraftBatchCodec
import io.github.kvtttba.droplet.core.ai.AiDraftParseException
import io.github.kvtttba.droplet.core.ai.AiParsedDraftBatch
import io.github.kvtttba.droplet.core.ai.AiParsedTransactionCandidate
import io.github.kvtttba.droplet.core.ai.AiPromptContext
import io.github.kvtttba.droplet.core.ai.AiUncertainFieldKeys
import io.github.kvtttba.droplet.core.ai.OpenAiCompatibleClient
import io.github.kvtttba.droplet.core.ai.ReceiptImageProcessor
import io.github.kvtttba.droplet.core.model.AiDraft
import io.github.kvtttba.droplet.core.model.AiDraftInputType
import io.github.kvtttba.droplet.core.model.AiDraftStatus
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.security.AiConfigPreferencesRepository
import io.github.kvtttba.droplet.core.security.AiEndpointSlot
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * Outcome of submitting or retrying an AI capture request (text, image, or voice).
 */
sealed interface AiCaptureOutcome {
    val draft: AiDraft

    data class ParsedReady(
        override val draft: AiDraft,
        val batch: AiParsedDraftBatch,
        val model: String,
        val latencyMs: Long,
    ) : AiCaptureOutcome

    data class SavedOffline(
        override val draft: AiDraft,
        val message: String,
    ) : AiCaptureOutcome

    data class NeedsConfiguration(
        override val draft: AiDraft,
        val slot: AiEndpointSlot,
        val message: String,
    ) : AiCaptureOutcome

    data class FailedStoredInDrafts(
        override val draft: AiDraft,
        val message: String,
    ) : AiCaptureOutcome
}

/**
 * Coordinates privacy-minimized AI bookkeeping across text, vision (single/batch/tall receipts),
 * and voice transcription, with automatic offline/failure fallback to the local Room draft queue.
 */
class AiCaptureOrchestrator(
    private val aiDraftRepository: AiDraftRepository,
    private val ledgerRepository: LedgerRepository,
    private val aiConfigPreferencesRepository: AiConfigPreferencesRepository,
    private val openAiCompatibleClient: OpenAiCompatibleClient,
    private val receiptImageProcessor: ReceiptImageProcessor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
) {

    suspend fun submitTextCapture(
        rawText: String,
        saveOfflineOnly: Boolean = false,
        inputType: AiDraftInputType = AiDraftInputType.TEXT,
    ): AiCaptureOutcome {
        val cleaned = rawText.trim()
        require(cleaned.isNotEmpty()) { "请输入要记账的自然语言内容" }
        val now = clock()
        val initialStatus = if (saveOfflineOnly) AiDraftStatus.PENDING_ONLINE else AiDraftStatus.PARSING
        val created = aiDraftRepository.createDraft(
            inputType = inputType,
            rawText = cleaned,
            status = initialStatus,
            nowEpochMs = now,
        )
        if (saveOfflineOnly) {
            return AiCaptureOutcome.SavedOffline(
                draft = created,
                message = "已暂存至离线草稿箱，联网后可一键重试解析或手动补齐入账",
            )
        }
        return executeTextParsingForDraft(created)
    }

    suspend fun submitImageCapture(
        attachments: List<AiDraftAttachmentRef>,
        userHint: String? = null,
        saveOfflineOnly: Boolean = false,
    ): AiCaptureOutcome {
        require(attachments.isNotEmpty()) { "请至少选择或拍摄 1 张票据图片" }
        val now = clock()
        val initialStatus = if (saveOfflineOnly) AiDraftStatus.PENDING_ONLINE else AiDraftStatus.PARSING
        val created = aiDraftRepository.createDraft(
            inputType = AiDraftInputType.IMAGE,
            rawText = userHint?.trim()?.takeIf { it.isNotEmpty() },
            attachments = attachments,
            status = initialStatus,
            nowEpochMs = now,
        )
        if (saveOfflineOnly) {
            return AiCaptureOutcome.SavedOffline(
                draft = created,
                message = "已将 ${attachments.size} 张去 EXIF 图片暂存至草稿箱",
            )
        }
        return executeImageParsingForDraft(created)
    }

    /**
     * Transcribes recorded audio via OpenAI Whisper-compatible `/audio/transcriptions` and
     * immediately feeds the resulting transcript into text parsing (always preserving the voice
     * transcript inside `ai_drafts` if text parsing fails or AI text endpoint isn't configured).
     */
    suspend fun submitCustomVoiceAudio(
        audioBytes: ByteArray,
        fileName: String,
        mimeType: String,
        saveTranscriptOfflineOnly: Boolean = false,
    ): Result<AiCaptureOutcome> = runCatching {
        require(audioBytes.isNotEmpty()) { "录音内容为空，请重新录制" }
        val voiceEndpoint = aiConfigPreferencesRepository.resolveEndpointForRequest(AiEndpointSlot.VOICE)
            ?: throw IllegalStateException("尚未配置 AI 语音转写服务，请先前往「AI 服务配置」或切换至系统语音输入")

        val transcription = openAiCompatibleClient.transcribeAudio(
            audioBytes = audioBytes,
            fileName = fileName,
            mimeType = mimeType,
            endpoint = voiceEndpoint,
        )
        submitTextCapture(
            rawText = transcription.text,
            saveOfflineOnly = saveTranscriptOfflineOnly,
            inputType = AiDraftInputType.VOICE,
        )
    }

    suspend fun retryDraft(draftId: String): AiCaptureOutcome {
        val draft = aiDraftRepository.getDraft(draftId)
            ?: throw IllegalArgumentException("未找到对应草稿")
        require(draft.status != AiDraftStatus.COMMITTED) { "已入账草稿不可重复解析" }
        val parsingDraft = aiDraftRepository.updateDraftState(
            draftId = draft.id,
            status = AiDraftStatus.PARSING,
            errorMessage = null,
            nowEpochMs = clock(),
        ) ?: draft

        return when (parsingDraft.inputType) {
            AiDraftInputType.IMAGE -> executeImageParsingForDraft(parsingDraft)
            AiDraftInputType.TEXT,
            AiDraftInputType.VOICE,
            -> executeTextParsingForDraft(parsingDraft)
        }
    }

    /**
     * Converts a `PENDING_ONLINE` or `FAILED` offline draft into an editable `READY` candidate batch
     * so users can review, complete uncertain fields, and atomically commit even without network/AI.
     */
    suspend fun convertDraftToManualReview(draftId: String): Result<AiDraft> = runCatching {
        val draft = aiDraftRepository.getDraft(draftId)
            ?: throw IllegalArgumentException("未找到对应草稿")
        val existingBatch = AiDraftBatchCodec.decodeBatchOrNull(draft.parsedItemsJson)
        if (existingBatch != null && existingBatch.items.isNotEmpty()) {
            val ready = aiDraftRepository.updateDraftState(
                draftId = draft.id,
                status = AiDraftStatus.READY,
                parsedBatch = existingBatch,
                errorMessage = null,
                nowEpochMs = clock(),
            ) ?: draft
            return@runCatching ready
        }

        val now = clock()
        ledgerRepository.ensureSeeded(now)
        val rawText = draft.rawText?.trim().orEmpty()
        val parsedAmounts = extractPositiveAmountsFromText(rawText)
        val accounts = ledgerRepository.observeAllAccounts(includeArchived = false, includeSystem = false).first()
        val defaultExpenseCategory = accounts.firstOrNull {
            it.type == io.github.kvtttba.droplet.core.model.AccountType.EXPENSE &&
                it.subType == io.github.kvtttba.droplet.core.model.AccountSubType.CATEGORY &&
                it.parentId != null
        }
        val defaultOperationalAccount = accounts.firstOrNull {
            it.type == io.github.kvtttba.droplet.core.model.AccountType.ASSET &&
                it.subType != io.github.kvtttba.droplet.core.model.AccountSubType.CATEGORY &&
                it.subType != io.github.kvtttba.droplet.core.model.AccountSubType.REIMBURSEMENT
        }

        val amountsToBuild = if (parsedAmounts.isNotEmpty()) {
            parsedAmounts
        } else {
            listOf(Money.fromDecimalString("1.00", Currency.DEFAULT))
        }

        val candidates = amountsToBuild.mapIndexed { index, money ->
            val uncertain = linkedSetOf(
                AiUncertainFieldKeys.CATEGORY_PATH,
                AiUncertainFieldKeys.ACCOUNT_NAME,
                AiUncertainFieldKeys.OCCURRED_AT,
            )
            if (parsedAmounts.isEmpty()) {
                uncertain += AiUncertainFieldKeys.AMOUNT
            }
            AiParsedTransactionCandidate(
                displayType = TransactionDisplayType.EXPENSE,
                occurredAtEpochMs = draft.createdAt.takeIf { it > 0L } ?: now,
                amount = money,
                currency = money.currency,
                matchedCategoryId = defaultExpenseCategory?.id,
                matchedCategoryPath = defaultExpenseCategory?.name,
                matchedAccountId = defaultOperationalAccount?.id,
                matchedAccountName = defaultOperationalAccount?.name,
                payee = null,
                note = rawText.takeIf { it.isNotEmpty() }
                    ?: if (draft.inputType == AiDraftInputType.IMAGE) "票据草稿 #${index + 1}" else null,
                confidenceDecimal = "0.50",
                uncertainFields = uncertain,
                warnings = listOf("由离线草稿转人工核对，请确认金额、分类与账户"),
            )
        }

        val batch = AiParsedDraftBatch(items = candidates)
        aiDraftRepository.updateDraftState(
            draftId = draft.id,
            status = AiDraftStatus.READY,
            parsedBatch = batch,
            errorMessage = null,
            nowEpochMs = now,
        ) ?: throw IllegalStateException("更新草稿状态失败")
    }

    private suspend fun executeTextParsingForDraft(draft: AiDraft): AiCaptureOutcome {
        val text = draft.rawText?.trim().orEmpty()
        if (text.isEmpty()) {
            val failed = aiDraftRepository.updateDraftState(
                draftId = draft.id,
                status = AiDraftStatus.FAILED,
                errorMessage = "草稿文本为空，无法解析",
                nowEpochMs = clock(),
            ) ?: draft
            return AiCaptureOutcome.FailedStoredInDrafts(failed, "草稿文本为空，无法解析")
        }

        val endpoint = aiConfigPreferencesRepository.resolveEndpointForRequest(AiEndpointSlot.TEXT)
        if (endpoint == null) {
            val message = "尚未配置文本 AI 服务，已暂存草稿箱（可前往设置配置或手动补齐）"
            val pending = aiDraftRepository.updateDraftState(
                draftId = draft.id,
                status = AiDraftStatus.PENDING_ONLINE,
                errorMessage = message,
                nowEpochMs = clock(),
            ) ?: draft
            return AiCaptureOutcome.NeedsConfiguration(
                draft = pending,
                slot = AiEndpointSlot.TEXT,
                message = message,
            )
        }

        return try {
            val context = buildPromptContext(clock())
            val result = openAiCompatibleClient.parseText(
                text = text,
                promptContext = context,
                endpoint = endpoint,
            )
            val ready = aiDraftRepository.updateDraftState(
                draftId = draft.id,
                status = AiDraftStatus.READY,
                parsedBatch = result.batch,
                errorMessage = null,
                nowEpochMs = clock(),
            ) ?: draft
            AiCaptureOutcome.ParsedReady(
                draft = ready,
                batch = result.batch,
                model = result.model,
                latencyMs = result.latencyMs,
            )
        } catch (e: Exception) {
            handleParseFailure(draft, e)
        }
    }

    private suspend fun executeImageParsingForDraft(draft: AiDraft): AiCaptureOutcome {
        val attachments = AiDraftBatchCodec.decodeAttachments(draft.attachmentPathsJson)
        if (attachments.isEmpty()) {
            val failed = aiDraftRepository.updateDraftState(
                draftId = draft.id,
                status = AiDraftStatus.FAILED,
                errorMessage = "草稿缺少有效图片附件，请重新选图",
                nowEpochMs = clock(),
            ) ?: draft
            return AiCaptureOutcome.FailedStoredInDrafts(failed, "草稿缺少有效图片附件，请重新选图")
        }

        val endpoint = aiConfigPreferencesRepository.resolveEndpointForRequest(AiEndpointSlot.VISION)
        if (endpoint == null) {
            val message = "尚未配置视觉 AI 服务，图片已去 EXIF 暂存草稿箱"
            val pending = aiDraftRepository.updateDraftState(
                draftId = draft.id,
                status = AiDraftStatus.PENDING_ONLINE,
                errorMessage = message,
                nowEpochMs = clock(),
            ) ?: draft
            return AiCaptureOutcome.NeedsConfiguration(
                draft = pending,
                slot = AiEndpointSlot.VISION,
                message = message,
            )
        }

        return try {
            val payloads = receiptImageProcessor.loadVisionPayloads(attachments)
            val context = buildPromptContext(clock())
            val result = openAiCompatibleClient.parseImages(
                images = payloads,
                promptContext = context,
                endpoint = endpoint,
                userHint = draft.rawText,
            )
            val ready = aiDraftRepository.updateDraftState(
                draftId = draft.id,
                status = AiDraftStatus.READY,
                parsedBatch = result.batch,
                errorMessage = null,
                nowEpochMs = clock(),
            ) ?: draft
            AiCaptureOutcome.ParsedReady(
                draft = ready,
                batch = result.batch,
                model = result.model,
                latencyMs = result.latencyMs,
            )
        } catch (e: Exception) {
            handleParseFailure(draft, e)
        }
    }

    private suspend fun handleParseFailure(draft: AiDraft, error: Throwable): AiCaptureOutcome.FailedStoredInDrafts {
        val friendly = when (error) {
            is AiClientException -> error.message ?: "AI 接口请求失败，已保留至草稿箱"
            is AiDraftParseException -> error.message ?: "AI 返回格式解析失败，已保留至草稿箱"
            is IOException -> error.message ?: "网络或本地文件读取失败，已保留至草稿箱"
            else -> error.message ?: "解析失败，已保留至草稿箱"
        }
        val failed = aiDraftRepository.updateDraftState(
            draftId = draft.id,
            status = AiDraftStatus.FAILED,
            errorMessage = friendly,
            nowEpochMs = clock(),
        ) ?: draft
        return AiCaptureOutcome.FailedStoredInDrafts(
            draft = failed,
            message = friendly,
        )
    }

    private suspend fun buildPromptContext(nowEpochMs: Long): AiPromptContext {
        ledgerRepository.ensureSeeded(nowEpochMs)
        val accounts = ledgerRepository.observeAllAccounts(
            includeArchived = false,
            includeSystem = false,
        ).first()
        val tags = ledgerRepository.observeTags(includeArchived = false).first()
        return AiPromptContext.fromCatalog(
            accounts = accounts,
            tags = tags,
            currentTimeEpochMs = nowEpochMs,
            zoneId = zoneIdProvider(),
            defaultCurrency = Currency.DEFAULT,
        )
    }

    companion object {
        private val DECIMAL_TOKEN_REGEX = Regex("(?<![\\d.])(\\d{1,9}(?:\\.\\d{1,4})?)(?![\\d.])")

        /**
         * Best-effort zero-float extraction of positive numeric tokens from offline text so
         * "手动转草稿编辑" pre-fills candidate amounts cleanly while still marking them uncertain.
         */
        internal fun extractPositiveAmountsFromText(rawText: String): List<Money> {
            if (rawText.isBlank()) return emptyList()
            return DECIMAL_TOKEN_REGEX.findAll(rawText)
                .take(5)
                .mapNotNull { match ->
                    try {
                        val decimal = BigDecimal(match.groupValues[1])
                        if (decimal.compareTo(BigDecimal.ZERO) > 0) {
                            Money.fromBigDecimal(decimal, Currency.DEFAULT, RoundingMode.HALF_UP)
                                .takeIf { it.isPositive }
                        } else {
                            null
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                .toList()
        }
    }
}
