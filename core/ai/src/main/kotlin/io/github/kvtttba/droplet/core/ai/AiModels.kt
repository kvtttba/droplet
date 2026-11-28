package io.github.kvtttba.droplet.core.ai

import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.security.AiEndpointSlot
import java.math.BigDecimal
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * Privacy-minimized operational account reference sent to AI models.
 * Deliberately excludes balances, credit limits, card numbers, and transaction history.
 */
@Serializable
data class AiPromptAccountRef(
    val id: String,
    val name: String,
    val type: AccountType,
    val subType: AccountSubType,
    val currency: Currency,
) {
    init {
        require(id.isNotBlank()) { "AiPromptAccountRef id must not be blank" }
        require(name.isNotBlank()) { "AiPromptAccountRef name must not be blank" }
    }
}

/**
 * Privacy-minimized two-level category reference sent to AI models (e.g. "餐饮/午餐").
 */
@Serializable
data class AiPromptCategoryRef(
    val id: String,
    val path: String,
    val type: AccountType,
    val parentName: String? = null,
    val leafName: String,
) {
    init {
        require(id.isNotBlank()) { "AiPromptCategoryRef id must not be blank" }
        require(path.isNotBlank()) { "AiPromptCategoryRef path must not be blank" }
        require(leafName.isNotBlank()) { "AiPromptCategoryRef leafName must not be blank" }
        require(type == AccountType.EXPENSE || type == AccountType.INCOME) {
            "Category ref must be EXPENSE or INCOME, got $type"
        }
    }
}

/**
 * Privacy-minimized active tag reference sent to AI models.
 */
@Serializable
data class AiPromptTagRef(
    val id: String,
    val name: String,
) {
    init {
        require(id.isNotBlank()) { "AiPromptTagRef id must not be blank" }
        require(name.isNotBlank()) { "AiPromptTagRef name must not be blank" }
    }
}

/**
 * Minimal ledger metadata context injected into AI prompts per `docs/ARCHITECTURE.md §5.2`.
 * Never includes balances, limits, card numbers, or historical ledger entries.
 */
data class AiPromptContext(
    val currentTimeEpochMs: Long,
    val zoneId: ZoneId = DEFAULT_ZONE_ID,
    val defaultCurrency: Currency = Currency.CNY,
    val accounts: List<AiPromptAccountRef> = emptyList(),
    val categories: List<AiPromptCategoryRef> = emptyList(),
    val tags: List<AiPromptTagRef> = emptyList(),
) {
    companion object {
        val DEFAULT_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")

        /**
         * Projects active catalog records into a strictly privacy-minimized [AiPromptContext].
         * System accounts, archived entries, equity accounts, and all numeric limits/balances are excluded.
         */
        fun fromCatalog(
            accounts: List<Account>,
            tags: List<Tag>,
            currentTimeEpochMs: Long,
            zoneId: ZoneId = DEFAULT_ZONE_ID,
            defaultCurrency: Currency = Currency.CNY,
        ): AiPromptContext {
            val activeAccounts = accounts
                .filter { !it.isArchived && !it.isSystem }
                .sortedWith(compareBy<Account> { it.sortOrder }.thenBy { it.name })

            val operationalAccounts = activeAccounts
                .filter { it.type == AccountType.ASSET || it.type == AccountType.LIABILITY }
                .filter { it.subType != AccountSubType.CATEGORY }
                .map { account ->
                    AiPromptAccountRef(
                        id = account.id,
                        name = sanitizePromptLabel(account.name),
                        type = account.type,
                        subType = account.subType,
                        currency = account.currency,
                    )
                }

            val categoryAccounts = activeAccounts.filter {
                it.subType == AccountSubType.CATEGORY &&
                    (it.type == AccountType.EXPENSE || it.type == AccountType.INCOME)
            }
            val categoryById = categoryAccounts.associateBy { it.id }
            val parentIdsWithChildren = categoryAccounts.mapNotNull { it.parentId }.toSet()

            val categoryRefs = categoryAccounts.mapNotNull { cat ->
                val cleanName = sanitizePromptLabel(cat.name)
                if (cat.parentId != null) {
                    val parent = categoryById[cat.parentId]
                    val parentClean = parent?.let { sanitizePromptLabel(it.name) }
                    val path = if (parentClean != null) "$parentClean/$cleanName" else cleanName
                    AiPromptCategoryRef(
                        id = cat.id,
                        path = path,
                        type = cat.type,
                        parentName = parentClean,
                        leafName = cleanName,
                    )
                } else if (cat.id !in parentIdsWithChildren) {
                    AiPromptCategoryRef(
                        id = cat.id,
                        path = cleanName,
                        type = cat.type,
                        parentName = null,
                        leafName = cleanName,
                    )
                } else {
                    // Parent group that already has active leaf subcategories: omit parent-only
                    // entry from prompt list so the model picks concrete 2-level paths.
                    null
                }
            }

            val activeTags = tags
                .filter { !it.isArchived }
                .sortedWith(compareBy<Tag> { it.sortOrder }.thenBy { it.name })
                .map { tag ->
                    AiPromptTagRef(
                        id = tag.id,
                        name = sanitizePromptLabel(tag.name),
                    )
                }

            return AiPromptContext(
                currentTimeEpochMs = currentTimeEpochMs,
                zoneId = zoneId,
                defaultCurrency = defaultCurrency,
                accounts = operationalAccounts,
                categories = categoryRefs,
                tags = activeTags,
            )
        }

        internal fun sanitizePromptLabel(raw: String): String =
            raw.replace(Regex("[\\r\\n\\t]+"), " ").trim()
    }
}

/**
 * EXIF-stripped base64-encoded image payload for vision bookkeeping requests.
 */
data class AiImagePayload(
    val base64Data: String,
    val mimeType: String = "image/jpeg",
) {
    init {
        require(base64Data.isNotBlank()) { "Image base64Data must not be blank" }
        require(mimeType.trim().lowercase().startsWith("image/")) {
            "Image mimeType must start with 'image/', got '$mimeType'"
        }
    }

    fun toDataUri(): String = "data:${mimeType.trim().lowercase()};base64,${base64Data.trim()}"

    override fun toString(): String =
        "AiImagePayload(mimeType=$mimeType, base64Length=${base64Data.length})"
}

/**
 * Canonical field keys used inside [AiParsedTransactionCandidate.uncertainFields].
 */
object AiUncertainFieldKeys {
    const val DISPLAY_TYPE = "display_type"
    const val OCCURRED_AT = "occurred_at"
    const val AMOUNT = "amount"
    const val CURRENCY = "currency"
    const val CATEGORY_PATH = "category_path"
    const val ACCOUNT_NAME = "account_name"
    const val TARGET_ACCOUNT_NAME = "target_account_name"
    const val PAYEE = "payee"
    const val TAGS = "tags"
    const val NOTE = "note"
    const val CONFIDENCE = "confidence"
}

/**
 * Structured candidate transaction parsed from AI JSON output and mapped to local catalog IDs.
 * Monetary values strictly use fixed-point [Money] (`scale = 4`); confidence uses canonical decimal text.
 */
@Serializable
data class AiParsedTransactionCandidate(
    val displayType: TransactionDisplayType,
    val occurredAtEpochMs: Long,
    val occurredAtRaw: String? = null,
    val amount: Money,
    val currency: Currency,
    val rawCategoryPath: String? = null,
    val matchedCategoryId: String? = null,
    val matchedCategoryPath: String? = null,
    val rawAccountName: String? = null,
    val matchedAccountId: String? = null,
    val matchedAccountName: String? = null,
    val rawTargetAccountName: String? = null,
    val matchedTargetAccountId: String? = null,
    val matchedTargetAccountName: String? = null,
    val payee: String? = null,
    val rawTags: List<String> = emptyList(),
    val matchedTagIds: List<String> = emptyList(),
    val matchedTagNames: List<String> = emptyList(),
    val note: String? = null,
    val confidenceDecimal: String = DEFAULT_CONFIDENCE_DECIMAL,
    val uncertainFields: Set<String> = emptySet(),
    val warnings: List<String> = emptyList(),
) {
    init {
        require(amount.amountMinor > 0L) {
            "Candidate amount must be positive, got ${amount.amountMinor}"
        }
        require(amount.currency == currency) {
            "Candidate amount currency (${amount.currency}) must match currency ($currency)"
        }
    }

    val isLowConfidence: Boolean
        get() = BigDecimal(confidenceDecimal).compareTo(LOW_CONFIDENCE_THRESHOLD) < 0

    val needsUserReview: Boolean
        get() = uncertainFields.isNotEmpty() || isLowConfidence

    companion object {
        const val DEFAULT_CONFIDENCE_DECIMAL: String = "0.50"
        val LOW_CONFIDENCE_THRESHOLD: BigDecimal = BigDecimal("0.70")
    }
}

/**
 * Batch of structured candidates produced by [AiDraftJsonParser], ready for M4.2 preview & batch commit.
 */
@Serializable
data class AiParsedDraftBatch(
    val schemaVersion: Int = AiCaptureContract.STRUCTURED_SCHEMA_VERSION,
    val items: List<AiParsedTransactionCandidate>,
    val skippedItemCount: Int = 0,
    val warnings: List<String> = emptyList(),
)

/**
 * Result of testing an OpenAI-compatible AI endpoint configuration.
 * Messages are guaranteed never to include API keys or Authorization tokens.
 */
data class AiConnectionTestResult(
    val success: Boolean,
    val endpointSlot: AiEndpointSlot,
    val resolvedBaseUrl: String,
    val model: String,
    val httpStatusCode: Int? = null,
    val probeMethod: String = "",
    val latencyMs: Long = 0L,
    val message: String,
)

/**
 * Output of audio transcription via an OpenAI Whisper-compatible `/audio/transcriptions` endpoint.
 */
data class AiTranscriptionResult(
    val text: String,
    val model: String,
    val latencyMs: Long = 0L,
)

/**
 * Output of text or vision parsing via `/chat/completions` + [AiDraftJsonParser].
 */
data class AiParseResult(
    val batch: AiParsedDraftBatch,
    val model: String,
    val latencyMs: Long = 0L,
)

/**
 * Sanitized exception thrown when OpenAI-compatible HTTP transport or response handling fails.
 */
class AiClientException(
    val httpStatusCode: Int? = null,
    val errorCode: String? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Exception thrown when AI text cannot be recovered into at least one valid structured candidate.
 */
class AiDraftParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
