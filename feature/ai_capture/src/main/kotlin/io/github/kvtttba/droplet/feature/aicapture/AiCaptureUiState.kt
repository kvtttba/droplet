package io.github.kvtttba.droplet.feature.aicapture

import io.github.kvtttba.droplet.core.ai.AiDraftAttachmentRef
import io.github.kvtttba.droplet.core.ai.AiDraftBatchCodec
import io.github.kvtttba.droplet.core.ai.AiParsedTransactionCandidate
import io.github.kvtttba.droplet.core.ai.AiUncertainFieldKeys
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AiDraft
import io.github.kvtttba.droplet.core.model.AiDraftInputType
import io.github.kvtttba.droplet.core.model.AiDraftStatus
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.security.AiServiceConfig
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AiCaptureTab(
    val routeKey: String,
    val label: String,
) {
    TEXT(routeKey = "text", label = "一句话"),
    IMAGE(routeKey = "image", label = "拍照/选图"),
    VOICE(routeKey = "voice", label = "语音"),
    ;

    companion object {
        fun fromRouteKey(raw: String?): AiCaptureTab =
            entries.firstOrNull { it.routeKey.equals(raw, ignoreCase = true) } ?: TEXT
    }
}

/**
 * Inbound Android system Share payload (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) queued until
 * the biometric privacy lock is unlocked.
 */
data class PendingAiSharePayload(
    val id: Long,
    val sharedText: String? = null,
    val imageUriStrings: List<String> = emptyList(),
) {
    val hasImages: Boolean get() = imageUriStrings.isNotEmpty()
    val hasText: Boolean get() = !sharedText.isNullOrBlank()
    val preferredTab: AiCaptureTab get() = if (hasImages) AiCaptureTab.IMAGE else AiCaptureTab.TEXT
}

/**
 * UI projection of a persisted [AiDraft] for the draft inbox section.
 */
data class AiDraftSummaryItem(
    val id: String,
    val inputType: AiDraftInputType,
    val status: AiDraftStatus,
    val rawTextPreview: String?,
    val attachmentCount: Int,
    val totalSliceCount: Int,
    val candidateCount: Int,
    val uncertainCandidateCount: Int,
    val errorMessage: String?,
    val createdAtLabel: String,
    val createdAtEpochMs: Long,
) {
    companion object {
        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        fun fromDraft(
            draft: AiDraft,
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): AiDraftSummaryItem {
            val attachments = AiDraftBatchCodec.decodeAttachments(draft.attachmentPathsJson)
            val batch = AiDraftBatchCodec.decodeBatchOrNull(draft.parsedItemsJson)
            val candidates = batch?.items.orEmpty()
            val formattedTime = if (draft.createdAt > 0L) {
                Instant.ofEpochMilli(draft.createdAt).atZone(zoneId).format(TIME_FORMATTER)
            } else {
                "刚刚"
            }
            return AiDraftSummaryItem(
                id = draft.id,
                inputType = draft.inputType,
                status = draft.status,
                rawTextPreview = draft.rawText?.trim()?.takeIf { it.isNotEmpty() },
                attachmentCount = attachments.size,
                totalSliceCount = attachments.sumOf { it.sliceCount },
                candidateCount = candidates.size,
                uncertainCandidateCount = candidates.count { it.needsUserReview },
                errorMessage = draft.errorMessage,
                createdAtLabel = formattedTime,
                createdAtEpochMs = draft.createdAt,
            )
        }
    }
}

data class AiCaptureUiState(
    val activeTab: AiCaptureTab = AiCaptureTab.TEXT,
    val aiServiceConfig: AiServiceConfig = AiServiceConfig(),
    val textInput: String = "",
    val selectedAttachments: List<AiDraftAttachmentRef> = emptyList(),
    val imageHint: String = "",
    val voiceTranscript: String = "",
    val isRecordingVoice: Boolean = false,
    val recordingElapsedSeconds: Int = 0,
    val isProcessingImages: Boolean = false,
    val isSubmitting: Boolean = false,
    val retryingDraftId: String? = null,
    val activeDrafts: List<AiDraftSummaryItem> = emptyList(),
    val userMessage: String? = null,
    val pendingNavigateToDraftId: String? = null,
) {
    val activeDraftCount: Int get() = activeDrafts.size
    val readyDraftCount: Int get() = activeDrafts.count { it.status == AiDraftStatus.READY }
    val pendingOrFailedCount: Int
        get() = activeDrafts.count {
            it.status == AiDraftStatus.PENDING_ONLINE || it.status == AiDraftStatus.FAILED
        }
}

/**
 * Editable candidate item inside [AiDraftReviewScreen].
 * Stores user-entered decimal text alongside exact fixed-point [Money] so floating-point is never used.
 */
data class DraftCandidateEditorItem(
    val localId: String,
    val selected: Boolean = true,
    val expanded: Boolean = false,
    val displayType: TransactionDisplayType = TransactionDisplayType.EXPENSE,
    val amountInput: String = "0.00",
    val parsedAmount: Money? = null,
    val currency: Currency = Currency.CNY,
    val occurredAtEpochMs: Long = 0L,
    val occurredAtLabel: String = "",
    val rawCategoryPath: String? = null,
    val matchedCategoryId: String? = null,
    val matchedCategoryPath: String? = null,
    val rawAccountName: String? = null,
    val matchedAccountId: String? = null,
    val matchedAccountName: String? = null,
    val rawTargetAccountName: String? = null,
    val matchedTargetAccountId: String? = null,
    val matchedTargetAccountName: String? = null,
    val payee: String = "",
    val note: String = "",
    val selectedTagIds: List<String> = emptyList(),
    val confidenceDecimal: String = AiParsedTransactionCandidate.DEFAULT_CONFIDENCE_DECIMAL,
    val uncertainFields: Set<String> = emptySet(),
    val warnings: List<String> = emptyList(),
    val validationError: String? = null,
) {
    val isLowConfidence: Boolean
        get() = try {
            BigDecimal(confidenceDecimal).compareTo(AiParsedTransactionCandidate.LOW_CONFIDENCE_THRESHOLD) < 0
        } catch (_: NumberFormatException) {
            true
        }

    val needsUserReview: Boolean
        get() = uncertainFields.isNotEmpty() || isLowConfidence || validationError != null

    fun toCandidateOrNull(): AiParsedTransactionCandidate? {
        val money = parsedAmount?.takeIf { it.isPositive } ?: return null
        return AiParsedTransactionCandidate(
            displayType = displayType,
            occurredAtEpochMs = occurredAtEpochMs,
            amount = money,
            currency = currency,
            rawCategoryPath = rawCategoryPath,
            matchedCategoryId = matchedCategoryId,
            matchedCategoryPath = matchedCategoryPath,
            rawAccountName = rawAccountName,
            matchedAccountId = matchedAccountId,
            matchedAccountName = matchedAccountName,
            rawTargetAccountName = rawTargetAccountName,
            matchedTargetAccountId = matchedTargetAccountId,
            matchedTargetAccountName = matchedTargetAccountName,
            payee = payee.trim().takeIf { it.isNotEmpty() },
            matchedTagIds = selectedTagIds.distinct(),
            note = note.trim().takeIf { it.isNotEmpty() },
            confidenceDecimal = confidenceDecimal,
            uncertainFields = uncertainFields,
            warnings = warnings,
        )
    }

    companion object {
        private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun formatEpochMs(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
            if (epochMs > 0L) {
                Instant.ofEpochMilli(epochMs).atZone(zoneId).format(DATE_TIME_FORMATTER)
            } else {
                "刚刚"
            }

        fun parsePositiveDecimalMoney(raw: String, currency: Currency): Money? {
            val cleaned = raw.trim().replace(",", "")
            if (cleaned.isEmpty()) return null
            return try {
                val decimal = BigDecimal(cleaned)
                if (decimal.compareTo(BigDecimal.ZERO) <= 0) return null
                Money.fromBigDecimal(decimal, currency, RoundingMode.HALF_UP)
                    .takeIf { it.isPositive }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Category choice with full "大类/小类" display path for draft review pickers.
 */
data class ReviewCategoryOption(
    val id: String,
    val displayPath: String,
    val leafName: String,
)

data class AiDraftReviewUiState(
    val isLoading: Boolean = true,
    val draftId: String = "",
    val inputType: AiDraftInputType = AiDraftInputType.TEXT,
    val draftStatus: AiDraftStatus = AiDraftStatus.READY,
    val rawText: String? = null,
    val attachments: List<AiDraftAttachmentRef> = emptyList(),
    val attachReceiptsToTransactions: Boolean = true,
    val items: List<DraftCandidateEditorItem> = emptyList(),
    val operationalAccounts: List<Account> = emptyList(),
    val expenseCategories: List<ReviewCategoryOption> = emptyList(),
    val incomeCategories: List<ReviewCategoryOption> = emptyList(),
    val availableTags: List<Tag> = emptyList(),
    val isCommitting: Boolean = false,
    val errorMessage: String? = null,
    val userMessage: String? = null,
    val committedSummaryMessage: String? = null,
) {
    val selectedItems: List<DraftCandidateEditorItem> get() = items.filter { it.selected }
    val selectedCount: Int get() = selectedItems.size
    val reviewAttentionCount: Int get() = selectedItems.count { it.needsUserReview }

    val selectedTotalsByCurrency: Map<Currency, Money>
        get() {
            val grouped = linkedMapOf<Currency, Money>()
            selectedItems.forEach { item ->
                val money = item.parsedAmount ?: return@forEach
                val current = grouped[money.currency] ?: Money.zero(money.currency)
                grouped[money.currency] = current + money
            }
            return grouped
        }
}

object AiReviewFieldLabels {
    private val HUNDRED = BigDecimal("100")

    fun labelForUncertainField(fieldKey: String): String = when (fieldKey) {
        AiUncertainFieldKeys.DISPLAY_TYPE -> "类型"
        AiUncertainFieldKeys.OCCURRED_AT -> "时间"
        AiUncertainFieldKeys.AMOUNT -> "金额"
        AiUncertainFieldKeys.CURRENCY -> "币种"
        AiUncertainFieldKeys.CATEGORY_PATH -> "分类"
        AiUncertainFieldKeys.ACCOUNT_NAME -> "账户"
        AiUncertainFieldKeys.TARGET_ACCOUNT_NAME -> "目标账户"
        AiUncertainFieldKeys.PAYEE -> "商户"
        AiUncertainFieldKeys.TAGS -> "标签"
        AiUncertainFieldKeys.NOTE -> "备注"
        AiUncertainFieldKeys.CONFIDENCE -> "置信度"
        else -> fieldKey
    }

    fun formatConfidencePct(confidenceDecimal: String): String = try {
        val pct = BigDecimal(confidenceDecimal)
            .multiply(HUNDRED)
            .setScale(0, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal.ZERO, HUNDRED)
        "${pct.toPlainString()}%"
    } catch (_: Exception) {
        "50%"
    }

    fun inputTypeLabel(inputType: AiDraftInputType): String = when (inputType) {
        AiDraftInputType.IMAGE -> "图片识别"
        AiDraftInputType.VOICE -> "语音记账"
        AiDraftInputType.TEXT -> "一句话文本"
    }

    fun draftStatusLabel(status: AiDraftStatus, candidateCount: Int): String = when (status) {
        AiDraftStatus.PENDING_ONLINE -> "待联网解析"
        AiDraftStatus.PARSING -> "AI 解析中..."
        AiDraftStatus.READY -> if (candidateCount > 0) "待确认 · $candidateCount 笔" else "待核对"
        AiDraftStatus.FAILED -> "解析失败"
        AiDraftStatus.COMMITTED -> "已入账"
    }

    fun displayTypeLabel(type: TransactionDisplayType): String = when (type) {
        TransactionDisplayType.EXPENSE -> "支出"
        TransactionDisplayType.INCOME -> "收入"
        TransactionDisplayType.TRANSFER -> "转账"
        TransactionDisplayType.REIMBURSE -> "待报销"
        TransactionDisplayType.REFUND -> "退款"
        TransactionDisplayType.DEBT -> "借贷"
        TransactionDisplayType.LOAN_REPAY -> "分期"
        TransactionDisplayType.INVEST -> "投资"
        TransactionDisplayType.SPLIT -> "多拆"
    }
}
