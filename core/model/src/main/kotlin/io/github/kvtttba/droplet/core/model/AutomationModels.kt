package io.github.kvtttba.droplet.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class BudgetPeriod {
    MONTHLY,
    YEARLY,
}

/**
 * Monthly or yearly overall/category expense budget with alert threshold percentage.
 */
@Serializable
data class Budget(
    val id: String,
    val period: BudgetPeriod,
    val categoryAccountId: String? = null,
    val currency: Currency = Currency.CNY,
    val limitAmountMinor: Long,
    val warnThresholdPct: Int = 80,
    val isEnabled: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "Budget id must not be blank" }
        require(limitAmountMinor > 0L) { "Budget limitAmountMinor must be positive, got $limitAmountMinor" }
        require(warnThresholdPct in 1..100) {
            "Budget warnThresholdPct must be in 1..100, got $warnThresholdPct"
        }
    }

    val limitAmount: Money
        get() = Money(limitAmountMinor, currency)
}

@Serializable
enum class RecurringFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
    CRON,
}

/**
 * Scheduled recurring transaction rule (rent, salary, subscriptions, DCA).
 */
@Serializable
data class RecurringRule(
    val id: String,
    val name: String,
    val frequency: RecurringFrequency,
    val intervalCount: Int = 1,
    val cronExpression: String? = null,
    val nextRunAt: Long,
    val endAt: Long? = null,
    val templatePayloadJson: String,
    val autoPost: Boolean = false,
    val isPaused: Boolean = false,
    val lastRunAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "RecurringRule id must not be blank" }
        require(name.isNotBlank()) { "RecurringRule name must not be blank" }
        require(intervalCount > 0) { "intervalCount must be positive, got $intervalCount" }
        require(templatePayloadJson.isNotBlank()) { "templatePayloadJson must not be blank" }
        if (frequency == RecurringFrequency.CRON) {
            require(!cronExpression.isNullOrBlank()) {
                "cronExpression is required when frequency is CRON"
            }
        }
    }
}

/**
 * One-tap quick transaction template for frequent daily entries.
 */
@Serializable
data class TransactionTemplate(
    val id: String,
    val name: String,
    val displayType: TransactionDisplayType,
    val templatePayloadJson: String,
    val icon: String? = null,
    val color: String? = null,
    val sortOrder: Int = 0,
    val useCount: Int = 0,
    val lastUsedAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "TransactionTemplate id must not be blank" }
        require(name.isNotBlank()) { "TransactionTemplate name must not be blank" }
        require(templatePayloadJson.isNotBlank()) { "templatePayloadJson must not be blank" }
        require(useCount >= 0) { "useCount must be non-negative, got $useCount" }
    }
}

@Serializable
enum class AiDraftInputType {
    IMAGE,
    VOICE,
    TEXT,
}

@Serializable
enum class AiDraftStatus {
    PENDING_ONLINE,
    PARSING,
    READY,
    FAILED,
    COMMITTED,
}

/**
 * Offline-resilient AI parsing queue record for receipt images, voice transcripts, or natural text.
 */
@Serializable
data class AiDraft(
    val id: String,
    val inputType: AiDraftInputType,
    val rawText: String? = null,
    val attachmentPathsJson: String? = null,
    val status: AiDraftStatus = AiDraftStatus.PENDING_ONLINE,
    val parsedItemsJson: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "AiDraft id must not be blank" }
    }
}
