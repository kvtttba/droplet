package io.github.kvtttba.droplet.core.domain.ledger

import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus

data class NetExpenseSummary(
    val originalCurrencyNet: Money,
    val baseCurrencyNet: Money,
    val refundCount: Int,
)

/**
 * Pure domain ledger aggregation helpers verifying historical locked base-currency reporting,
 * current mark-to-market foreign-asset valuation, and cumulative net expense after refunds.
 */
object LedgerAggregator {

    /**
     * Sums posted [Posting.amountMinor] for [accountId] in [currency].
     */
    fun accountBalanceInOriginalCurrency(
        transactions: Iterable<Transaction>,
        accountId: String,
        currency: Currency,
    ): Money {
        var total = 0L
        for (tx in transactions) {
            if (tx.status != TransactionStatus.POSTED) continue
            for (posting in tx.postings) {
                if (posting.accountId == accountId && posting.currency == currency) {
                    total = Math.addExact(total, posting.amountMinor)
                }
            }
        }
        return Money(total, currency)
    }

    /**
     * Sums historical locked [Posting.baseAmountMinor] for [accountId] across posted transactions.
     * Immune to any subsequent exchange-rate updates.
     */
    fun historicalBaseAmountSum(
        transactions: Iterable<Transaction>,
        accountId: String,
        baseCurrency: Currency,
    ): Money {
        var total = 0L
        for (tx in transactions) {
            if (tx.status != TransactionStatus.POSTED) continue
            for (posting in tx.postings) {
                if (posting.accountId == accountId) {
                    require(posting.baseCurrency == baseCurrency) {
                        "Posting ${posting.id} baseCurrency (${posting.baseCurrency}) != requested ($baseCurrency)"
                    }
                    total = Math.addExact(total, posting.baseAmountMinor)
                }
            }
        }
        return Money(total, baseCurrency)
    }

    /**
     * Computes current mark-to-market valuation of a balance-sheet account using [latestRates].
     */
    fun markToMarketBalance(
        transactions: Iterable<Transaction>,
        accountId: String,
        accountCurrency: Currency,
        targetCurrency: Currency,
        latestRates: ExchangeRateProvider,
    ): Money {
        val originalBalance = accountBalanceInOriginalCurrency(transactions, accountId, accountCurrency)
        if (accountCurrency == targetCurrency) return originalBalance
        val rate = latestRates.requireRate(accountCurrency, targetCurrency)
        return rate.convert(originalBalance)
    }

    /**
     * Recalculates net expense of [originalExpense] on [expenseAccountId] after applying all posted
     * linked [TransactionDisplayType.REFUND] transactions referencing `originalExpense.id`.
     */
    fun netExpenseAfterRefunds(
        originalExpense: Transaction,
        refunds: Iterable<Transaction>,
        expenseAccountId: String,
    ): NetExpenseSummary {
        val originalPostings = originalExpense.postings.filter { it.accountId == expenseAccountId }
        require(originalPostings.isNotEmpty()) {
            "Original transaction '${originalExpense.id}' has no posting for expense account '$expenseAccountId'"
        }
        val origCurrency = originalPostings.first().currency
        val baseCurrency = originalPostings.first().baseCurrency

        var netOriginalMinor = 0L
        var netBaseMinor = 0L
        for (posting in originalPostings) {
            require(posting.currency == origCurrency) {
                "Mixed expense currencies on account '$expenseAccountId' in transaction '${originalExpense.id}'"
            }
            netOriginalMinor = Math.addExact(netOriginalMinor, posting.amountMinor)
            netBaseMinor = Math.addExact(netBaseMinor, posting.baseAmountMinor)
        }

        var appliedRefunds = 0
        for (refund in refunds) {
            if (refund.status != TransactionStatus.POSTED) continue
            if (refund.displayType != TransactionDisplayType.REFUND) continue
            if (refund.refTransactionId != originalExpense.id) continue
            val refundPostings = refund.postings.filter { it.accountId == expenseAccountId }
            if (refundPostings.isEmpty()) continue
            appliedRefunds++
            for (posting in refundPostings) {
                require(posting.currency == origCurrency) {
                    "Refund '${refund.id}' expense currency (${posting.currency}) differs from original ($origCurrency)"
                }
                netOriginalMinor = Math.addExact(netOriginalMinor, posting.amountMinor)
                netBaseMinor = Math.addExact(netBaseMinor, posting.baseAmountMinor)
            }
        }

        return NetExpenseSummary(
            originalCurrencyNet = Money(netOriginalMinor, origCurrency),
            baseCurrencyNet = Money(netBaseMinor, baseCurrency),
            refundCount = appliedRefunds,
        )
    }
}
