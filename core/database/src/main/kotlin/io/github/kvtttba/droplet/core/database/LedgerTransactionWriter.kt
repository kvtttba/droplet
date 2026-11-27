package io.github.kvtttba.droplet.core.database

import androidx.room.withTransaction
import io.github.kvtttba.droplet.core.database.entity.TransactionTagCrossRef
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.entity.toHeaderEntity
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidator
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionStatus

/**
 * Strongly consistent transactional write gateway for double-entry ledger mutations.
 *
 * Every transaction write executes inside a Room SQLite transaction, loads the live referenced
 * accounts, enforces [LedgerValidator.requireValid], and atomically persists `transactions`,
 * `postings`, and `transaction_tags`. Any invariant violation or foreign-key error rolls back
 * the entire transaction so unbalanced or partial ledger state can never enter SQLite.
 */
class LedgerTransactionWriter(
    private val database: DropletDatabase,
) {
    private val accountDao = database.accountDao()
    private val transactionDao = database.transactionDao()

    /**
     * Validates [transaction] against live database accounts and atomically upserts its header,
     * postings, and tag links.
     */
    suspend fun saveTransaction(
        transaction: Transaction,
        allowArchivedAccounts: Boolean = false,
    ) {
        database.withTransaction {
            validateAndWriteSingle(
                transaction = transaction,
                allowArchivedAccounts = allowArchivedAccounts,
            )
        }
    }

    /**
     * Validates and persists all [transactions] in a single all-or-nothing SQLite transaction.
     * If any item fails double-entry validation or FK checks, every prior item in the batch is
     * rolled back.
     */
    suspend fun saveTransactions(
        transactions: List<Transaction>,
        allowArchivedAccounts: Boolean = false,
    ) {
        if (transactions.isEmpty()) return
        database.withTransaction {
            for (tx in transactions) {
                validateAndWriteSingle(
                    transaction = tx,
                    allowArchivedAccounts = allowArchivedAccounts,
                )
            }
        }
    }

    /**
     * Soft-voids an existing transaction without erasing its audit history.
     */
    suspend fun voidTransaction(
        transactionId: String,
        updatedAt: Long,
    ) {
        database.withTransaction {
            transactionDao.updateStatus(
                transactionId = transactionId,
                status = TransactionStatus.VOID,
                updatedAt = updatedAt,
            )
        }
    }

    /**
     * Restores a voided or draft transaction back to POSTED status after re-validating
     * its persisted double-entry postings against live accounts.
     */
    suspend fun restoreTransaction(
        transactionId: String,
        updatedAt: Long,
        allowArchivedAccounts: Boolean = true,
    ) {
        database.withTransaction {
            val existing = transactionDao.getById(transactionId)?.toDomain()
                ?: throw IllegalArgumentException("Transaction '$transactionId' not found")
            val referencedAccountIds = existing.postings.map { it.accountId }.distinct()
            val accountsById = if (referencedAccountIds.isEmpty()) {
                emptyMap()
            } else {
                accountDao.getByIds(referencedAccountIds)
                    .associate { entity -> entity.id to entity.toDomain() }
            }
            LedgerValidator.requireValid(
                transaction = existing.copy(status = TransactionStatus.POSTED, updatedAt = updatedAt),
                accounts = accountsById,
                allowArchivedAccounts = allowArchivedAccounts,
            )
            transactionDao.updateStatus(
                transactionId = transactionId,
                status = TransactionStatus.POSTED,
                updatedAt = updatedAt,
            )
        }
    }

    /**
     * Updates the reimbursement settlement status of an advance transaction.
     */
    suspend fun updateReimburseState(
        transactionId: String,
        reimburseState: ReimburseState?,
        updatedAt: Long,
    ) {
        database.withTransaction {
            transactionDao.updateReimburseState(
                transactionId = transactionId,
                state = reimburseState,
                updatedAt = updatedAt,
            )
        }
    }

    /**
     * Permanently deletes a transaction (cascading postings, tag cross-refs, and attachments;
     * setting `ref_transaction_id` / `loan_installments.transaction_id` to NULL).
     */
    suspend fun deleteTransaction(transactionId: String) {
        database.withTransaction {
            transactionDao.deleteById(transactionId)
        }
    }

    private suspend fun validateAndWriteSingle(
        transaction: Transaction,
        allowArchivedAccounts: Boolean,
    ) {
        val referencedAccountIds = transaction.postings
            .map { it.accountId }
            .distinct()
        val accountsById = if (referencedAccountIds.isEmpty()) {
            emptyMap()
        } else {
            accountDao.getByIds(referencedAccountIds)
                .associate { entity -> entity.id to entity.toDomain() }
        }

        LedgerValidator.requireValid(
            transaction = transaction,
            accounts = accountsById,
            allowArchivedAccounts = allowArchivedAccounts,
        )

        val header = transaction.toHeaderEntity()
        val postings = transaction.postings.map { it.toEntity() }
        val tagCrossRefs = transaction.tagIds
            .distinct()
            .map { tagId ->
                TransactionTagCrossRef(
                    transactionId = transaction.id,
                    tagId = tagId,
                )
            }

        transactionDao.upsertValidatedTransactionGraph(
            header = header,
            postings = postings,
            tagCrossRefs = tagCrossRefs,
        )
    }
}
