package io.github.kvtttba.droplet.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.kvtttba.droplet.core.model.AiDraftInputType
import io.github.kvtttba.droplet.core.model.AiDraftStatus
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.RecurringFrequency
import io.github.kvtttba.droplet.core.model.TransactionDisplayType

@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["category_account_id"]),
        Index(value = ["period", "category_account_id", "currency"], unique = true),
    ],
)
data class BudgetEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "period")
    val period: BudgetPeriod,
    @ColumnInfo(name = "category_account_id")
    val categoryAccountId: String? = null,
    @ColumnInfo(name = "currency")
    val currency: Currency = Currency.CNY,
    @ColumnInfo(name = "limit_amount_minor")
    val limitAmountMinor: Long,
    @ColumnInfo(name = "warn_threshold_pct")
    val warnThresholdPct: Int = 80,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "recurring_rules",
    indices = [
        Index(value = ["is_paused", "next_run_at"]),
    ],
)
data class RecurringRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "frequency")
    val frequency: RecurringFrequency,
    @ColumnInfo(name = "interval_count")
    val intervalCount: Int = 1,
    @ColumnInfo(name = "cron_expression")
    val cronExpression: String? = null,
    @ColumnInfo(name = "next_run_at")
    val nextRunAt: Long,
    @ColumnInfo(name = "end_at")
    val endAt: Long? = null,
    @ColumnInfo(name = "template_payload_json")
    val templatePayloadJson: String,
    @ColumnInfo(name = "auto_post")
    val autoPost: Boolean = false,
    @ColumnInfo(name = "is_paused")
    val isPaused: Boolean = false,
    @ColumnInfo(name = "last_run_at")
    val lastRunAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "templates",
    indices = [
        Index(value = ["sort_order"]),
        Index(value = ["display_type", "sort_order"]),
    ],
)
data class TemplateEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "display_type")
    val displayType: TransactionDisplayType,
    @ColumnInfo(name = "template_payload_json")
    val templatePayloadJson: String,
    @ColumnInfo(name = "icon")
    val icon: String? = null,
    @ColumnInfo(name = "color")
    val color: String? = null,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "use_count")
    val useCount: Int = 0,
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "ai_drafts",
    indices = [
        Index(value = ["status", "created_at"]),
    ],
)
data class AiDraftEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "input_type")
    val inputType: AiDraftInputType,
    @ColumnInfo(name = "raw_text")
    val rawText: String? = null,
    @ColumnInfo(name = "attachment_paths_json")
    val attachmentPathsJson: String? = null,
    @ColumnInfo(name = "status")
    val status: AiDraftStatus = AiDraftStatus.PENDING_ONLINE,
    @ColumnInfo(name = "parsed_items_json")
    val parsedItemsJson: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L,
)
