package io.github.kvtttba.droplet.core.domain.debt

import io.github.kvtttba.droplet.core.domain.ledger.LedgerAggregator
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus

/**
 * Single linked refund item extracted for UI inspection & audit traceability.
 */
data class LinkedRefundRecord(
    val transaction: Transaction,
    val refundToAccountId: String?,
    val refundReceivedAmount: Money,
    val expenseReductionAmount: Money,
)

/**
 * Cumulative refund & net-expense summary for a single `POSTED` `EXPENSE` transaction.
 *
 * Algebraic invariants (verified against [LedgerAggregator.netExpenseAfterRefunds]):
 * - `netExpenseAmount == originalExpenseAmount - cumulativeRefundedAmount`
 * - `remainingRefundableAmount == netExpenseAmount.coerceAtLeast(0)`
 */
data class ExpenseRefundSummary(
    val originalTransactionId: String,
    val expenseCategoryAccountId: String,
    val originalPaymentAccountId: String?,
    val expenseCurrency: Currency,
    val baseCurrency: Currency,
    val originalExpenseAmount: Money,
    val originalExpenseBaseAmount: Money,
    val cumulativeRefundedAmount: Money,
    val cumulativeRefundedBaseAmount: Money,
    val remainingRefundableAmount: Money,
    val netExpenseAmount: Money,
    val netExpenseBaseAmount: Money,
    val refundCount: Int,
    val linkedRefunds: List<LinkedRefundRecord>,
) {
    val hasRefunds: Boolean
        get() = refundCount > 0

    val isFullyRefunded: Boolean
        get() = hasRefunds && remainingRefundableAmount.isZero

    val isPartiallyRefunded: Boolean
        get() = hasRefunds && remainingRefundableAmount.isPositive

    val canRefundMore: Boolean
        get() = remainingRefundableAmount.isPositive
}

/**
 * Pure Kotlin/JVM zero-floating-point calculator aggregating multi-stage linked refunds
 * against an original `EXPENSE` transaction, staying strictly consistent with [LedgerAggregator].
 */
object RefundCalculator {

    fun summarize(
        originalExpense: Transaction,
        linkedRefunds: Iterable<Transaction>,
        accountsById: Map<String, Account>,
        expenseCategoryAccountIdOverride: String? = null,
    ): ExpenseRefundSummary? {
        if (originalExpense.status != TransactionStatus.POSTED) return null
        if (originalExpense.displayType != TransactionDisplayType.EXPENSE) return null

        val business = originalExpense.businessPostings
        val expensePostings = business.filter { posting ->
            accountsById[posting.accountId]?.type == AccountType.EXPENSE && posting.amountMinor > 0L
        }
        val targetExpenseAccountId = expenseCategoryAccountIdOverride
            ?: expensePostings.maxByOrNull { it.amountMinor }?.accountId
            ?: return null

        val matchingOriginalPostings = originalExpense.postings.filter { it.accountId == targetExpenseAccountId }
        if (matchingOriginalPostings.isEmpty()) return null

        val expenseCurrency = matchingOriginalPostings.first().currency
        val baseCurrency = matchingOriginalPostings.first().baseCurrency

        var originalMinor = 0L
        var originalBaseMinor = 0L
        for (posting in matchingOriginalPostings) {
            if (posting.currency != expenseCurrency) return null
            originalMinor = Math.addExact(originalMinor, posting.amountMinor)
            originalBaseMinor = Math.addExact(originalBaseMinor, posting.baseAmountMinor)
        }
        if (originalMinor <= 0L) return null

        val originalPaymentAccountId = business
            .filter { posting ->
                val type = accountsById[posting.accountId]?.type
                (type == AccountType.ASSET || type == AccountType.LIABILITY) && posting.amountMinor < 0L
            }
            .minByOrNull { it.amountMinor }
            ?.accountId

        val validRefundTransactions = linkedRefunds
            .asSequence()
            .filter { tx ->
                tx.status == TransactionStatus.POSTED &&
                    tx.displayType == TransactionDisplayType.REFUND &&
                    tx.refTransactionId == originalExpense.id &&
                    tx.postings.any { it.accountId == targetExpenseAccountId && it.currency == expenseCurrency }
            }
            .sortedWith(
                compareBy<Transaction> { it.occurredAt }
                    .thenBy { it.createdAt }
                    .thenBy { it.id },
            )
            .toList()

        val netSummary = LedgerAggregator.netExpenseAfterRefunds(
            originalExpense = originalExpense,
            refunds = validRefundTransactions,
            expenseAccountId = targetExpenseAccountId,
        )

        val originalMoney = Money(originalMinor, expenseCurrency)
        val originalBaseMoney = Money(originalBaseMinor, baseCurrency)
        val cumulativeRefunded = originalMoney - netSummary.originalCurrencyNet
        val cumulativeRefundedBase = originalBaseMoney - netSummary.baseCurrencyNet
        val remainingRefundable = Money(
            netSummary.originalCurrencyNet.amountMinor.coerceAtLeast(0L),
            expenseCurrency,
        )

        val records = validRefundTransactions.map { refundTx ->
            val refundBusiness = refundTx.businessPostings
            val reductionMinor = refundBusiness
                .filter { it.accountId == targetExpenseAccountId && it.currency == expenseCurrency }
                .fold(0L) { acc, p -> Math.addExact(acc, Math.negateExact(p.amountMinor)) }
            val depositPosting = refundBusiness
                .filter { p ->
                    val type = accountsById[p.accountId]?.type
                    (type == AccountType.ASSET || type == AccountType.LIABILITY) && p.amountMinor > 0L
                }
                .maxByOrNull { it.amountMinor }
            LinkedRefundRecord(
                transaction = refundTx,
                refundToAccountId = depositPosting?.accountId,
                refundReceivedAmount = depositPosting?.money ?: Money(reductionMinor, expenseCurrency),
                expenseReductionAmount = Money(reductionMinor, expenseCurrency),
            )
        }

        return ExpenseRefundSummary(
            originalTransactionId = originalExpense.id,
            expenseCategoryAccountId = targetExpenseAccountId,
            originalPaymentAccountId = originalPaymentAccountId,
            expenseCurrency = expenseCurrency,
            baseCurrency = baseCurrency,
            originalExpenseAmount = originalMoney,
            originalExpenseBaseAmount = originalBaseMoney,
            cumulativeRefundedAmount = cumulativeRefunded,
            cumulativeRefundedBaseAmount = cumulativeRefundedBase,
            remainingRefundableAmount = remainingRefundable,
            netExpenseAmount = netSummary.originalCurrencyNet,
            netExpenseBaseAmount = netSummary.baseCurrencyNet,
            refundCount = netSummary.refundCount,
            linkedRefunds = records,
        )
    }

    /**
     * Batch helper mapping each eligible `EXPENSE` transaction ID to its [ExpenseRefundSummary].
     */
    fun summarizeBatch(
        expenseTransactions: Iterable<Transaction>,
        linkedRefundTransactions: Iterable<Transaction>,
        accountsById: Map<String, Account>,
    ): Map<String, ExpenseRefundSummary> {
        val refundsByParentId = linkedRefundTransactions
            .filter {
                it.status == TransactionStatus.POSTED &&
                    it.displayType == TransactionDisplayType.REFUND &&
                    it.refTransactionId != null
            }
            .groupBy { it.refTransactionId!! }

        return buildMap {
            for (expense in expenseTransactions) {
                val summary = summarize(
                    originalExpense = expense,
                    linkedRefunds = refundsByParentId[expense.id].orEmpty(),
                    accountsById = accountsById,
                ) ?: continue
                put(expense.id, summary)
            }
        }
    }
}
