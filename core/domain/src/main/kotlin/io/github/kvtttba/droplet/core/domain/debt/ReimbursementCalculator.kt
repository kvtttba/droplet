package io.github.kvtttba.droplet.core.domain.debt

import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus

/**
 * Progress & reconciliation summary for a single reimbursable advance transaction.
 *
 * Algebraic invariants (in the advance's reimbursement-account currency):
 * - `remainingPendingAmount == (advanceAmount - settledAmount).coerceAtLeast(0)`
 * - For each settlement, `settledAmount == receivedCash + shortfallExpense - surplusIncome`
 */
data class ReimbursementItemSummary(
    val advanceTransaction: Transaction,
    val reimbursementAccountId: String,
    val paymentAccountId: String?,
    val currency: Currency,
    val advanceAmount: Money,
    val settledAmount: Money,
    val receivedCashTotal: Money,
    val shortfallExpenseTotal: Money,
    val surplusIncomeTotal: Money,
    val remainingPendingAmount: Money,
    val derivedState: ReimburseState,
    val settlementCount: Int,
    val settlementTransactions: List<Transaction>,
) {
    val isPending: Boolean
        get() = derivedState != ReimburseState.SETTLED

    val isSettled: Boolean
        get() = derivedState == ReimburseState.SETTLED
}

/**
 * Overview of all reimbursable advances split into pending (`PENDING`/`PARTIAL`) and `SETTLED`.
 */
data class ReimbursementOverviewSummary(
    val items: List<ReimbursementItemSummary>,
    val pendingItems: List<ReimbursementItemSummary>,
    val settledItems: List<ReimbursementItemSummary>,
    val totalPendingByCurrency: Map<Currency, Money>,
    val primaryPendingTotal: Money,
) {
    val pendingCount: Int
        get() = pendingItems.size

    val settledCount: Int
        get() = settledItems.size

    companion object {
        fun empty(defaultCurrency: Currency = Currency.DEFAULT): ReimbursementOverviewSummary =
            ReimbursementOverviewSummary(
                items = emptyList(),
                pendingItems = emptyList(),
                settledItems = emptyList(),
                totalPendingByCurrency = emptyMap(),
                primaryPendingTotal = Money.zero(defaultCurrency),
            )
    }
}

/**
 * Pure Kotlin/JVM zero-floating-point calculator for reimbursable advance progress,
 * partial settlements, shortfall-to-personal-expense tracking, and batch overview totals.
 */
object ReimbursementCalculator {

    /**
     * Computes [ReimbursementItemSummary] for a single reimbursable advance transaction
     * and all its linked `POSTED` settlement transactions.
     */
    fun summarizeItem(
        advanceTransaction: Transaction,
        linkedSettlements: Iterable<Transaction>,
        accountsById: Map<String, Account>,
    ): ReimbursementItemSummary? {
        if (advanceTransaction.status != TransactionStatus.POSTED) return null
        if (advanceTransaction.displayType != TransactionDisplayType.REIMBURSE) return null
        if (advanceTransaction.refTransactionId != null) return null

        val business = advanceTransaction.businessPostings
        val reimbursePostings = business.filter { posting ->
            val acct = accountsById[posting.accountId]
            acct?.subType == AccountSubType.REIMBURSEMENT && posting.amountMinor > 0L
        }
        val primaryReimbursePosting = reimbursePostings.firstOrNull()
            ?: business.firstOrNull { it.amountMinor > 0L }
            ?: return null

        val reimbursementAccountId = primaryReimbursePosting.accountId
        val currency = primaryReimbursePosting.currency

        var advanceMinor = 0L
        for (posting in business) {
            if (posting.accountId == reimbursementAccountId &&
                posting.currency == currency &&
                posting.amountMinor > 0L
            ) {
                advanceMinor = Math.addExact(advanceMinor, posting.amountMinor)
            }
        }
        if (advanceMinor <= 0L) return null

        val paymentAccountId = business
            .filter { it.accountId != reimbursementAccountId && it.amountMinor < 0L }
            .minByOrNull { it.amountMinor }
            ?.accountId

        val validSettlements = linkedSettlements
            .asSequence()
            .filter { tx ->
                tx.status == TransactionStatus.POSTED &&
                    tx.displayType == TransactionDisplayType.REIMBURSE &&
                    tx.refTransactionId == advanceTransaction.id
            }
            .sortedWith(
                compareBy<Transaction> { it.occurredAt }
                    .thenBy { it.createdAt }
                    .thenBy { it.id },
            )
            .toList()

        var settledMinor = 0L
        var receivedCashMinor = 0L
        var shortfallMinor = 0L
        var surplusMinor = 0L

        for (settlement in validSettlements) {
            for (posting in settlement.businessPostings) {
                if (posting.currency != currency) continue
                if (posting.accountId == reimbursementAccountId) {
                    if (posting.amountMinor < 0L) {
                        settledMinor = Math.addExact(settledMinor, Math.negateExact(posting.amountMinor))
                    }
                    continue
                }
                val acctType = accountsById[posting.accountId]?.type
                when (acctType) {
                    AccountType.EXPENSE -> {
                        if (posting.amountMinor > 0L) {
                            shortfallMinor = Math.addExact(shortfallMinor, posting.amountMinor)
                        }
                    }
                    AccountType.INCOME -> {
                        if (posting.amountMinor < 0L) {
                            surplusMinor = Math.addExact(surplusMinor, Math.negateExact(posting.amountMinor))
                        }
                    }
                    AccountType.ASSET, AccountType.LIABILITY, null -> {
                        if (posting.amountMinor > 0L) {
                            receivedCashMinor = Math.addExact(receivedCashMinor, posting.amountMinor)
                        }
                    }
                    AccountType.EQUITY -> Unit
                }
            }
        }

        val remainingMinor = Math.subtractExact(advanceMinor, settledMinor).coerceAtLeast(0L)
        val derivedState = when {
            remainingMinor == 0L && settledMinor > 0L -> ReimburseState.SETTLED
            settledMinor > 0L -> ReimburseState.PARTIAL
            else -> ReimburseState.PENDING
        }

        return ReimbursementItemSummary(
            advanceTransaction = advanceTransaction,
            reimbursementAccountId = reimbursementAccountId,
            paymentAccountId = paymentAccountId,
            currency = currency,
            advanceAmount = Money(advanceMinor, currency),
            settledAmount = Money(settledMinor, currency),
            receivedCashTotal = Money(receivedCashMinor, currency),
            shortfallExpenseTotal = Money(shortfallMinor, currency),
            surplusIncomeTotal = Money(surplusMinor, currency),
            remainingPendingAmount = Money(remainingMinor, currency),
            derivedState = derivedState,
            settlementCount = validSettlements.size,
            settlementTransactions = validSettlements,
        )
    }

    /**
     * Aggregates all `REIMBURSE` transactions (both advances and settlements) into a structured
     * [ReimbursementOverviewSummary].
     */
    fun summarizeAll(
        reimburseTransactions: Iterable<Transaction>,
        accountsById: Map<String, Account>,
        defaultCurrency: Currency = Currency.DEFAULT,
    ): ReimbursementOverviewSummary {
        val posted = reimburseTransactions.filter {
            it.status == TransactionStatus.POSTED && it.displayType == TransactionDisplayType.REIMBURSE
        }
        val advances = posted.filter { it.refTransactionId == null }
        val settlementsByAdvanceId = posted
            .filter { it.refTransactionId != null }
            .groupBy { it.refTransactionId!! }

        val items = advances
            .mapNotNull { advance ->
                summarizeItem(
                    advanceTransaction = advance,
                    linkedSettlements = settlementsByAdvanceId[advance.id].orEmpty(),
                    accountsById = accountsById,
                )
            }
            .sortedWith(
                compareByDescending<ReimbursementItemSummary> { it.isPending }
                    .thenByDescending { it.advanceTransaction.occurredAt }
                    .thenByDescending { it.advanceTransaction.createdAt }
                    .thenByDescending { it.advanceTransaction.id },
            )

        val pendingItems = items.filter { it.isPending }
        val settledItems = items.filter { it.isSettled }

        val pendingTotalsByCurrency = linkedMapOf<Currency, Long>()
        for (item in pendingItems) {
            val prev = pendingTotalsByCurrency[item.currency] ?: 0L
            pendingTotalsByCurrency[item.currency] = Math.addExact(prev, item.remainingPendingAmount.amountMinor)
        }
        val moneyByCurrency = pendingTotalsByCurrency.mapValues { (ccy, minor) -> Money(minor, ccy) }
        val primaryPending = moneyByCurrency[defaultCurrency]
            ?: moneyByCurrency.values.firstOrNull()
            ?: Money.zero(defaultCurrency)

        return ReimbursementOverviewSummary(
            items = items,
            pendingItems = pendingItems,
            settledItems = settledItems,
            totalPendingByCurrency = moneyByCurrency,
            primaryPendingTotal = primaryPending,
        )
    }
}
