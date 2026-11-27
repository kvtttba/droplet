package io.github.kvtttba.droplet.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.kvtttba.droplet.core.database.entity.AccountEntity
import io.github.kvtttba.droplet.core.database.entity.AttachmentEntity
import io.github.kvtttba.droplet.core.database.entity.PostingEntity
import io.github.kvtttba.droplet.core.database.entity.TagEntity
import io.github.kvtttba.droplet.core.database.entity.TransactionEntity
import io.github.kvtttba.droplet.core.database.entity.TransactionTagCrossRef
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import kotlinx.coroutines.flow.Flow

data class TransactionWithDetails(
    @Embedded
    val transaction: TransactionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "transaction_id",
    )
    val postings: List<PostingEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = TransactionTagCrossRef::class,
            parentColumn = "transaction_id",
            entityColumn = "tag_id",
        ),
    )
    val tags: List<TagEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "transaction_id",
    )
    val attachments: List<AttachmentEntity>,
)

data class AccountCurrencyBalanceRow(
    val accountId: String,
    val currency: Currency,
    val balanceMinor: Long,
    val transactionCount: Int,
)

data class AccountBaseTotalRow(
    val accountId: String,
    val accountName: String,
    val accountType: AccountType,
    val parentId: String?,
    val baseCurrency: Currency,
    val totalBaseAmountMinor: Long,
    val transactionCount: Int,
)

data class PeriodSummaryRow(
    val netExpenseBaseMinor: Long,
    val netIncomeBaseMinor: Long,
    val transactionCount: Int,
) {
    val netSavingsBaseMinor: Long
        get() = Math.subtractExact(netIncomeBaseMinor, netExpenseBaseMinor)
}

enum class TagFilterMode {
    /** Union: matches transactions carrying ANY of the selected tags. */
    ANY,

    /** Intersection: matches transactions carrying ALL of the selected tags. */
    ALL,
}

data class TransactionFilter(
    val startInclusive: Long? = null,
    val endExclusive: Long? = null,
    val statuses: Set<TransactionStatus> = setOf(TransactionStatus.POSTED),
    val displayTypes: Set<TransactionDisplayType> = emptySet(),
    val accountIds: Set<String> = emptySet(),
    val categoryAccountIds: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(),
    val tagFilterMode: TagFilterMode = TagFilterMode.ANY,
    val keyword: String? = null,
    val reimburseStates: Set<ReimburseState> = emptySet(),
    val refTransactionId: String? = null,
)

object TransactionFilterSqlBuilder {

    fun buildTransactionQuery(filter: TransactionFilter): SupportSQLiteQuery {
        val sql = StringBuilder("SELECT t.* FROM transactions t")
        val args = mutableListOf<Any>()
        val conditions = buildWhereConditions(filter, args)
        if (conditions.isNotEmpty()) {
            sql.append(" WHERE ").append(conditions.joinToString(" AND "))
        }
        sql.append(" ORDER BY t.occurred_at DESC, t.created_at DESC, t.id DESC")
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    fun buildHistoricalBaseTotalsByAccountQuery(
        baseCurrency: Currency,
        filter: TransactionFilter = TransactionFilter(),
        accountTypes: Set<AccountType> = emptySet(),
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        val conditions = mutableListOf<String>()
        conditions += "p.base_currency = ?"
        args += baseCurrency.code
        conditions += "p.is_system_balancing = 0"
        if (accountTypes.isNotEmpty()) {
            conditions += "a.type IN (${placeholders(accountTypes.size)})"
            accountTypes.forEach { args += it.name }
        }
        conditions += buildWhereConditions(filter, args)

        val sql = """
            SELECT p.account_id AS accountId,
                   a.name AS accountName,
                   a.type AS accountType,
                   a.parent_id AS parentId,
                   p.base_currency AS baseCurrency,
                   SUM(p.base_amount_minor) AS totalBaseAmountMinor,
                   COUNT(DISTINCT p.transaction_id) AS transactionCount
            FROM postings p
            INNER JOIN transactions t ON t.id = p.transaction_id
            INNER JOIN accounts a ON a.id = p.account_id
            WHERE ${conditions.joinToString(" AND ")}
            GROUP BY p.account_id, p.base_currency
            ORDER BY a.type ASC, a.sort_order ASC, a.id ASC
        """.trimIndent()
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    fun buildPeriodSummaryQuery(
        baseCurrency: Currency,
        filter: TransactionFilter = TransactionFilter(),
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        val conditions = mutableListOf<String>()
        conditions += "p.base_currency = ?"
        args += baseCurrency.code
        conditions += "p.is_system_balancing = 0"
        conditions += "a.type IN ('EXPENSE', 'INCOME')"
        conditions += buildWhereConditions(filter, args)

        val sql = """
            SELECT COALESCE(SUM(CASE WHEN a.type = 'EXPENSE' THEN p.base_amount_minor ELSE 0 END), 0) AS netExpenseBaseMinor,
                   COALESCE(SUM(CASE WHEN a.type = 'INCOME' THEN -p.base_amount_minor ELSE 0 END), 0) AS netIncomeBaseMinor,
                   COUNT(DISTINCT t.id) AS transactionCount
            FROM postings p
            INNER JOIN transactions t ON t.id = p.transaction_id
            INNER JOIN accounts a ON a.id = p.account_id
            WHERE ${conditions.joinToString(" AND ")}
        """.trimIndent()
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private fun buildWhereConditions(
        filter: TransactionFilter,
        args: MutableList<Any>,
    ): List<String> {
        val conditions = mutableListOf<String>()

        if (filter.startInclusive != null) {
            conditions += "t.occurred_at >= ?"
            args += filter.startInclusive
        }
        if (filter.endExclusive != null) {
            conditions += "t.occurred_at < ?"
            args += filter.endExclusive
        }
        if (filter.statuses.isNotEmpty()) {
            conditions += "t.status IN (${placeholders(filter.statuses.size)})"
            filter.statuses.forEach { args += it.name }
        }
        if (filter.displayTypes.isNotEmpty()) {
            conditions += "t.display_type IN (${placeholders(filter.displayTypes.size)})"
            filter.displayTypes.forEach { args += it.name }
        }
        if (filter.reimburseStates.isNotEmpty()) {
            conditions += "t.reimburse_state IN (${placeholders(filter.reimburseStates.size)})"
            filter.reimburseStates.forEach { args += it.name }
        }
        if (filter.refTransactionId != null) {
            conditions += "t.ref_transaction_id = ?"
            args += filter.refTransactionId
        }
        if (filter.accountIds.isNotEmpty()) {
            val ph = placeholders(filter.accountIds.size)
            conditions += "EXISTS (SELECT 1 FROM postings fp WHERE fp.transaction_id = t.id AND fp.account_id IN ($ph))"
            filter.accountIds.forEach { args += it }
        }
        if (filter.categoryAccountIds.isNotEmpty()) {
            val ph = placeholders(filter.categoryAccountIds.size)
            conditions += """
                EXISTS (
                    SELECT 1 FROM postings cp
                    INNER JOIN accounts ca ON ca.id = cp.account_id
                    WHERE cp.transaction_id = t.id
                      AND (cp.account_id IN ($ph) OR ca.parent_id IN ($ph))
                )
            """.trimIndent()
            filter.categoryAccountIds.forEach { args += it }
            filter.categoryAccountIds.forEach { args += it }
        }
        if (filter.tagIds.isNotEmpty()) {
            val ph = placeholders(filter.tagIds.size)
            when (filter.tagFilterMode) {
                TagFilterMode.ANY -> {
                    conditions += """
                        EXISTS (
                            SELECT 1 FROM transaction_tags tt
                            WHERE tt.transaction_id = t.id AND tt.tag_id IN ($ph)
                        )
                    """.trimIndent()
                    filter.tagIds.forEach { args += it }
                }
                TagFilterMode.ALL -> {
                    conditions += """
                        (
                            SELECT COUNT(DISTINCT tt.tag_id)
                            FROM transaction_tags tt
                            WHERE tt.transaction_id = t.id AND tt.tag_id IN ($ph)
                        ) = ?
                    """.trimIndent()
                    filter.tagIds.forEach { args += it }
                    args += filter.tagIds.size
                }
            }
        }
        val trimmedKeyword = filter.keyword?.trim()
        if (!trimmedKeyword.isNullOrEmpty()) {
            val pattern = "%${escapeLike(trimmedKeyword)}%"
            conditions += """
                (
                    COALESCE(t.payee, '') LIKE ? ESCAPE '\'
                    OR COALESCE(t.note, '') LIKE ? ESCAPE '\'
                    OR EXISTS (
                        SELECT 1 FROM postings kp
                        INNER JOIN accounts ka ON ka.id = kp.account_id
                        WHERE kp.transaction_id = t.id
                          AND kp.is_system_balancing = 0
                          AND ka.name LIKE ? ESCAPE '\'
                    )
                    OR EXISTS (
                        SELECT 1 FROM transaction_tags kt
                        INNER JOIN tags ktag ON ktag.id = kt.tag_id
                        WHERE kt.transaction_id = t.id
                          AND ktag.name LIKE ? ESCAPE '\'
                    )
                )
            """.trimIndent()
            args += pattern
            args += pattern
            args += pattern
            args += pattern
        }

        return conditions
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

    private fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
}

@Dao
interface TransactionDao {

    @Upsert
    suspend fun upsertTransactionHeader(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPostings(postings: List<PostingEntity>)

    @Query("DELETE FROM postings WHERE transaction_id = :transactionId")
    suspend fun deletePostingsByTransactionId(transactionId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTagCrossRefs(crossRefs: List<TransactionTagCrossRef>)

    @Query("DELETE FROM transaction_tags WHERE transaction_id = :transactionId")
    suspend fun deleteTagCrossRefsByTransactionId(transactionId: String)

    /**
     * Atomically replaces a transaction's header, postings, and tag links without firing
     * `ON DELETE CASCADE` on attachments or `ON DELETE SET NULL` on child references.
     *
     * Callers MUST invoke this through [io.github.kvtttba.droplet.core.database.LedgerTransactionWriter]
     * so [io.github.kvtttba.droplet.core.domain.ledger.LedgerValidator] enforces double-entry balance
     * within the same SQLite transaction.
     */
    @Transaction
    suspend fun upsertValidatedTransactionGraph(
        header: TransactionEntity,
        postings: List<PostingEntity>,
        tagCrossRefs: List<TransactionTagCrossRef>,
    ) {
        upsertTransactionHeader(header)
        deletePostingsByTransactionId(header.id)
        insertPostings(postings)
        deleteTagCrossRefsByTransactionId(header.id)
        if (tagCrossRefs.isNotEmpty()) {
            insertTagCrossRefs(tagCrossRefs)
        }
    }

    @Query("UPDATE transactions SET status = :status, updated_at = :updatedAt WHERE id = :transactionId")
    suspend fun updateStatus(transactionId: String, status: TransactionStatus, updatedAt: Long)

    @Query("UPDATE transactions SET reimburse_state = :state, updated_at = :updatedAt WHERE id = :transactionId")
    suspend fun updateReimburseState(
        transactionId: String,
        state: ReimburseState?,
        updatedAt: Long,
    )

    @Query("DELETE FROM transactions WHERE id = :transactionId")
    suspend fun deleteById(transactionId: String)

    @Transaction
    @Query("SELECT * FROM transactions WHERE id = :transactionId LIMIT 1")
    suspend fun getById(transactionId: String): TransactionWithDetails?

    @Transaction
    @Query("SELECT * FROM transactions WHERE id = :transactionId LIMIT 1")
    fun observeById(transactionId: String): Flow<TransactionWithDetails?>

    @Transaction
    @Query(
        """
        SELECT * FROM transactions
        WHERE status = :status
          AND occurred_at >= :startInclusive
          AND occurred_at < :endExclusive
        ORDER BY occurred_at DESC, created_at DESC, id DESC
        """,
    )
    fun observeInDateRange(
        startInclusive: Long,
        endExclusive: Long,
        status: TransactionStatus = TransactionStatus.POSTED,
    ): Flow<List<TransactionWithDetails>>

    @Transaction
    @RawQuery(
        observedEntities = [
            TransactionEntity::class,
            PostingEntity::class,
            TransactionTagCrossRef::class,
            AccountEntity::class,
            TagEntity::class,
            AttachmentEntity::class,
        ],
    )
    fun observeFilteredRaw(query: SupportSQLiteQuery): Flow<List<TransactionWithDetails>>

    fun observeFiltered(filter: TransactionFilter): Flow<List<TransactionWithDetails>> =
        observeFilteredRaw(TransactionFilterSqlBuilder.buildTransactionQuery(filter))

    @Transaction
    @Query(
        """
        SELECT * FROM transactions
        WHERE ref_transaction_id = :refTransactionId
          AND status = :status
        ORDER BY occurred_at ASC, created_at ASC, id ASC
        """,
    )
    fun observeLinkedTransactions(
        refTransactionId: String,
        status: TransactionStatus = TransactionStatus.POSTED,
    ): Flow<List<TransactionWithDetails>>

    @Transaction
    @Query(
        """
        SELECT * FROM transactions
        WHERE ref_transaction_id = :refTransactionId
          AND display_type = :displayType
          AND status = :status
        ORDER BY occurred_at ASC, created_at ASC, id ASC
        """,
    )
    fun observeLinkedTransactionsByType(
        refTransactionId: String,
        displayType: TransactionDisplayType,
        status: TransactionStatus = TransactionStatus.POSTED,
    ): Flow<List<TransactionWithDetails>>

    @Transaction
    @Query(
        """
        SELECT * FROM transactions
        WHERE reimburse_state IN (:states)
          AND status = :status
        ORDER BY occurred_at DESC, created_at DESC, id DESC
        """,
    )
    fun observeReimbursementsByStates(
        states: List<ReimburseState>,
        status: TransactionStatus = TransactionStatus.POSTED,
    ): Flow<List<TransactionWithDetails>>

    fun observePendingReimbursements(): Flow<List<TransactionWithDetails>> =
        observeReimbursementsByStates(listOf(ReimburseState.PENDING, ReimburseState.PARTIAL))

    @Query(
        """
        SELECT p.account_id AS accountId,
               p.currency AS currency,
               SUM(p.amount_minor) AS balanceMinor,
               COUNT(DISTINCT p.transaction_id) AS transactionCount
        FROM postings p
        INNER JOIN transactions t ON t.id = p.transaction_id
        WHERE t.status = :status
        GROUP BY p.account_id, p.currency
        ORDER BY p.account_id ASC, p.currency ASC
        """,
    )
    fun observeAccountOriginalBalances(
        status: TransactionStatus = TransactionStatus.POSTED,
    ): Flow<List<AccountCurrencyBalanceRow>>

    @Query(
        """
        SELECT p.account_id AS accountId,
               p.currency AS currency,
               SUM(p.amount_minor) AS balanceMinor,
               COUNT(DISTINCT p.transaction_id) AS transactionCount
        FROM postings p
        INNER JOIN transactions t ON t.id = p.transaction_id
        WHERE t.status = :status
          AND p.account_id = :accountId
        GROUP BY p.account_id, p.currency
        """,
    )
    fun observeSingleAccountOriginalBalances(
        accountId: String,
        status: TransactionStatus = TransactionStatus.POSTED,
    ): Flow<List<AccountCurrencyBalanceRow>>

    @Query(
        """
        SELECT p.account_id AS accountId,
               a.name AS accountName,
               a.type AS accountType,
               a.parent_id AS parentId,
               p.base_currency AS baseCurrency,
               SUM(p.base_amount_minor) AS totalBaseAmountMinor,
               COUNT(DISTINCT p.transaction_id) AS transactionCount
        FROM postings p
        INNER JOIN transactions t ON t.id = p.transaction_id
        INNER JOIN accounts a ON a.id = p.account_id
        WHERE t.status = :status
          AND p.base_currency = :baseCurrency
          AND p.is_system_balancing = 0
          AND (:startInclusive IS NULL OR t.occurred_at >= :startInclusive)
          AND (:endExclusive IS NULL OR t.occurred_at < :endExclusive)
        GROUP BY p.account_id, p.base_currency
        ORDER BY a.type ASC, a.sort_order ASC, a.id ASC
        """,
    )
    fun observeAccountHistoricalBaseTotals(
        baseCurrency: Currency,
        startInclusive: Long? = null,
        endExclusive: Long? = null,
        status: TransactionStatus = TransactionStatus.POSTED,
    ): Flow<List<AccountBaseTotalRow>>

    @RawQuery(
        observedEntities = [
            TransactionEntity::class,
            PostingEntity::class,
            TransactionTagCrossRef::class,
            AccountEntity::class,
            TagEntity::class,
        ],
    )
    fun observeFilteredHistoricalBaseTotalsRaw(
        query: SupportSQLiteQuery,
    ): Flow<List<AccountBaseTotalRow>>

    fun observeFilteredHistoricalBaseTotals(
        baseCurrency: Currency,
        filter: TransactionFilter = TransactionFilter(),
        accountTypes: Set<AccountType> = emptySet(),
    ): Flow<List<AccountBaseTotalRow>> = observeFilteredHistoricalBaseTotalsRaw(
        TransactionFilterSqlBuilder.buildHistoricalBaseTotalsByAccountQuery(
            baseCurrency = baseCurrency,
            filter = filter,
            accountTypes = accountTypes,
        ),
    )

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN a.type = 'EXPENSE' THEN p.base_amount_minor ELSE 0 END), 0) AS netExpenseBaseMinor,
               COALESCE(SUM(CASE WHEN a.type = 'INCOME' THEN -p.base_amount_minor ELSE 0 END), 0) AS netIncomeBaseMinor,
               COUNT(DISTINCT t.id) AS transactionCount
        FROM postings p
        INNER JOIN transactions t ON t.id = p.transaction_id
        INNER JOIN accounts a ON a.id = p.account_id
        WHERE t.status = :status
          AND p.base_currency = :baseCurrency
          AND p.is_system_balancing = 0
          AND a.type IN ('EXPENSE', 'INCOME')
          AND (:startInclusive IS NULL OR t.occurred_at >= :startInclusive)
          AND (:endExclusive IS NULL OR t.occurred_at < :endExclusive)
        """,
    )
    fun observePeriodSummary(
        baseCurrency: Currency,
        startInclusive: Long? = null,
        endExclusive: Long? = null,
        status: TransactionStatus = TransactionStatus.POSTED,
    ): Flow<PeriodSummaryRow>

    @RawQuery(
        observedEntities = [
            TransactionEntity::class,
            PostingEntity::class,
            TransactionTagCrossRef::class,
            AccountEntity::class,
            TagEntity::class,
        ],
    )
    fun observeFilteredPeriodSummaryRaw(query: SupportSQLiteQuery): Flow<PeriodSummaryRow>

    fun observeFilteredPeriodSummary(
        baseCurrency: Currency,
        filter: TransactionFilter = TransactionFilter(),
    ): Flow<PeriodSummaryRow> = observeFilteredPeriodSummaryRaw(
        TransactionFilterSqlBuilder.buildPeriodSummaryQuery(
            baseCurrency = baseCurrency,
            filter = filter,
        ),
    )

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun countTransactions(): Int

    @Query("SELECT COUNT(*) FROM postings")
    suspend fun countPostings(): Int

    @Query("SELECT COUNT(*) FROM transaction_tags")
    suspend fun countTransactionTags(): Int
}
