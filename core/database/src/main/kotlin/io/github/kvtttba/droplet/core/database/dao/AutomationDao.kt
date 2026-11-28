package io.github.kvtttba.droplet.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.kvtttba.droplet.core.database.entity.AiDraftEntity
import io.github.kvtttba.droplet.core.database.entity.BudgetEntity
import io.github.kvtttba.droplet.core.database.entity.RecurringRuleEntity
import io.github.kvtttba.droplet.core.database.entity.TemplateEntity
import io.github.kvtttba.droplet.core.model.AiDraftStatus
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

    @Upsert
    suspend fun upsert(budget: BudgetEntity)

    @Query("SELECT * FROM budgets WHERE id = :budgetId LIMIT 1")
    suspend fun getById(budgetId: String): BudgetEntity?

    @Query(
        """
        SELECT * FROM budgets
        WHERE (:onlyEnabled = 0 OR is_enabled = 1)
        ORDER BY period ASC, category_account_id IS NOT NULL ASC, id ASC
        """,
    )
    suspend fun getAll(onlyEnabled: Boolean = false): List<BudgetEntity>

    @Query(
        """
        SELECT * FROM budgets
        WHERE period = :period
          AND currency = :currency
          AND ((category_account_id IS NULL AND :categoryAccountId IS NULL)
               OR category_account_id = :categoryAccountId)
        LIMIT 1
        """,
    )
    suspend fun findDuplicate(
        period: BudgetPeriod,
        categoryAccountId: String?,
        currency: Currency,
    ): BudgetEntity?

    @Query(
        """
        SELECT * FROM budgets
        WHERE (:onlyEnabled = 0 OR is_enabled = 1)
        ORDER BY period ASC, category_account_id IS NOT NULL ASC, id ASC
        """,
    )
    fun observeAll(onlyEnabled: Boolean = true): Flow<List<BudgetEntity>>

    @Query(
        """
        SELECT * FROM budgets
        WHERE period = :period AND (:onlyEnabled = 0 OR is_enabled = 1)
        ORDER BY category_account_id IS NOT NULL ASC, id ASC
        """,
    )
    fun observeByPeriod(
        period: BudgetPeriod,
        onlyEnabled: Boolean = true,
    ): Flow<List<BudgetEntity>>

    @Query("UPDATE budgets SET is_enabled = :enabled, updated_at = :updatedAt WHERE id = :budgetId")
    suspend fun setEnabled(budgetId: String, enabled: Boolean, updatedAt: Long)

    @Query("DELETE FROM budgets WHERE id = :budgetId")
    suspend fun deleteById(budgetId: String)
}

@Dao
interface RecurringRuleDao {

    @Upsert
    suspend fun upsert(rule: RecurringRuleEntity)

    @Query("SELECT * FROM recurring_rules WHERE id = :ruleId LIMIT 1")
    suspend fun getById(ruleId: String): RecurringRuleEntity?

    @Query("SELECT * FROM recurring_rules ORDER BY is_paused ASC, next_run_at ASC, id ASC")
    suspend fun getAll(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules ORDER BY is_paused ASC, next_run_at ASC, id ASC")
    fun observeAll(): Flow<List<RecurringRuleEntity>>

    @Query(
        """
        SELECT * FROM recurring_rules
        WHERE is_paused = 0
          AND next_run_at <= :nowEpochMs
          AND (end_at IS NULL OR next_run_at <= end_at)
        ORDER BY next_run_at ASC, id ASC
        """,
    )
    suspend fun getDueRules(nowEpochMs: Long): List<RecurringRuleEntity>

    @Query(
        """
        UPDATE recurring_rules
        SET last_run_at = :lastRunAt, next_run_at = :nextRunAt, updated_at = :updatedAt
        WHERE id = :ruleId
        """,
    )
    suspend fun advanceSchedule(
        ruleId: String,
        lastRunAt: Long,
        nextRunAt: Long,
        updatedAt: Long,
    )

    /**
     * Atomic compare-and-swap schedule advancement guarded by `is_paused = 0 AND next_run_at = :expectedNextRunAt`
     * to guarantee idempotency against concurrent worker / UI double-posting.
     */
    @Query(
        """
        UPDATE recurring_rules
        SET last_run_at = COALESCE(:lastRunAt, last_run_at),
            next_run_at = :nextRunAt,
            updated_at = :updatedAt
        WHERE id = :ruleId
          AND is_paused = 0
          AND next_run_at = :expectedNextRunAt
        """,
    )
    suspend fun advanceScheduleIfExpectedNextRun(
        ruleId: String,
        expectedNextRunAt: Long,
        lastRunAt: Long?,
        nextRunAt: Long,
        updatedAt: Long,
    ): Int

    @Query("UPDATE recurring_rules SET is_paused = :paused, updated_at = :updatedAt WHERE id = :ruleId")
    suspend fun setPaused(ruleId: String, paused: Boolean, updatedAt: Long)

    @Query(
        """
        UPDATE recurring_rules
        SET is_paused = 1, end_at = :endAt, updated_at = :updatedAt
        WHERE id = :ruleId
        """,
    )
    suspend fun terminateRule(ruleId: String, endAt: Long, updatedAt: Long)

    @Query("DELETE FROM recurring_rules WHERE id = :ruleId")
    suspend fun deleteById(ruleId: String)
}

@Dao
interface TemplateDao {

    @Upsert
    suspend fun upsert(template: TemplateEntity)

    @Query("SELECT * FROM templates WHERE id = :templateId LIMIT 1")
    suspend fun getById(templateId: String): TemplateEntity?

    @Query("SELECT * FROM templates ORDER BY sort_order ASC, use_count DESC, id ASC")
    suspend fun getAll(): List<TemplateEntity>

    @Query("SELECT * FROM templates ORDER BY sort_order ASC, use_count DESC, id ASC")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query(
        """
        SELECT * FROM templates
        WHERE display_type = :displayType
        ORDER BY sort_order ASC, use_count DESC, id ASC
        """,
    )
    fun observeByDisplayType(displayType: TransactionDisplayType): Flow<List<TemplateEntity>>

    @Query(
        """
        UPDATE templates
        SET use_count = use_count + 1, last_used_at = :usedAt, updated_at = :usedAt
        WHERE id = :templateId
        """,
    )
    suspend fun recordUsage(templateId: String, usedAt: Long)

    @Query("UPDATE templates SET sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :templateId")
    suspend fun updateSortOrder(templateId: String, sortOrder: Int, updatedAt: Long)

    @Query("DELETE FROM templates WHERE id = :templateId")
    suspend fun deleteById(templateId: String)
}

@Dao
interface AiDraftDao {

    @Upsert
    suspend fun upsert(draft: AiDraftEntity)

    @Query("SELECT * FROM ai_drafts WHERE id = :draftId LIMIT 1")
    suspend fun getById(draftId: String): AiDraftEntity?

    @Query("SELECT * FROM ai_drafts WHERE id = :draftId LIMIT 1")
    fun observeById(draftId: String): Flow<AiDraftEntity?>

    @Query("SELECT * FROM ai_drafts ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<AiDraftEntity>>

    @Query(
        """
        SELECT * FROM ai_drafts
        WHERE status IN (:statuses)
        ORDER BY created_at DESC, id DESC
        """,
    )
    fun observeByStatuses(statuses: List<AiDraftStatus>): Flow<List<AiDraftEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM ai_drafts
        WHERE status IN (:statuses)
        """,
    )
    fun observeCountByStatuses(statuses: List<AiDraftStatus>): Flow<Int>

    @Query(
        """
        UPDATE ai_drafts
        SET status = :status,
            parsed_items_json = COALESCE(:parsedItemsJson, parsed_items_json),
            error_message = :errorMessage,
            updated_at = :updatedAt
        WHERE id = :draftId
        """,
    )
    suspend fun updateParsingState(
        draftId: String,
        status: AiDraftStatus,
        parsedItemsJson: String?,
        errorMessage: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM ai_drafts WHERE id = :draftId")
    suspend fun deleteById(draftId: String)

    @Query("DELETE FROM ai_drafts WHERE status = :status")
    suspend fun deleteByStatus(status: AiDraftStatus)
}
