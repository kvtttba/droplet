package io.github.kvtttba.droplet.core.model

import kotlinx.serialization.Serializable

/**
 * Double-entry transaction header aggregate with its ordered list of [Posting]s.
 */
@Serializable
data class Transaction(
    val id: String,
    val occurredAt: Long,
    val displayType: TransactionDisplayType,
    val status: TransactionStatus = TransactionStatus.POSTED,
    val payee: String? = null,
    val note: String? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    val refTransactionId: String? = null,
    val reimburseState: ReimburseState? = null,
    val tagIds: List<String> = emptyList(),
    val postings: List<Posting>,
    val createdAt: Long = occurredAt,
    val updatedAt: Long = occurredAt,
) {
    init {
        require(id.isNotBlank()) { "Transaction id must not be blank" }
        require(postings.all { it.transactionId == id }) {
            "All postings must reference parent transaction id '$id'"
        }
    }

    val businessPostings: List<Posting>
        get() = postings.filterNot { it.isSystemBalancing }

    val systemBalancingPostings: List<Posting>
        get() = postings.filter { it.isSystemBalancing }

    val currencies: Set<Currency>
        get() = postings.mapTo(linkedSetOf()) { it.currency }

    val isMultiCurrency: Boolean
        get() = currencies.size > 1
}
