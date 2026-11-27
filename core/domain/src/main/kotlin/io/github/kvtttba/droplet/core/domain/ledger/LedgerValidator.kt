package io.github.kvtttba.droplet.core.domain.ledger

import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Posting
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.Transaction

/**
 * Strict double-entry ledger invariant validator.
 *
 * Enforces:
 * - At least 2 postings per transaction (forbids 0-row and 1-row transactions)
 * - No zero-amount postings and no duplicate posting IDs
 * - Per-currency exact zero-sum (`SUM(amount_minor) == 0`) with overflow detection
 * - No system balancing / Trading postings in single-currency transactions
 * - Proper `Equity:Trading:{CCY}` account mapping on system balancing lines
 * - Base-currency consistency and sign preservation on every posting
 * - Investment share quantity non-zero and sign-aligned with holding amount
 * - Optional account registry verification (existence, currency, archive status)
 */
object LedgerValidator {

    fun validate(
        transaction: Transaction,
        accounts: Map<String, Account>? = null,
        allowArchivedAccounts: Boolean = false,
    ): LedgerValidationResult {
        val errors = mutableListOf<LedgerValidationError>()
        val postings = transaction.postings

        if (postings.size < 2) {
            errors += LedgerValidationError.InsufficientPostings(postings.size)
        }

        val seenPostingIds = hashSetOf<String>()
        val baseCurrencies = linkedSetOf<Currency>()
        val currencies = linkedSetOf<Currency>()

        for (posting in postings) {
            if (!seenPostingIds.add(posting.id)) {
                errors += LedgerValidationError.DuplicatePostingId(posting.id)
            }
            baseCurrencies += posting.baseCurrency
            currencies += posting.currency

            if (posting.amountMinor == 0L) {
                errors += LedgerValidationError.ZeroAmountPosting(posting.id, posting.accountId)
            }

            if (posting.currency == posting.baseCurrency) {
                if (posting.baseAmountMinor != posting.amountMinor) {
                    errors += LedgerValidationError.BaseCurrencyMismatchOnSameCurrency(
                        postingId = posting.id,
                        amountMinor = posting.amountMinor,
                        baseAmountMinor = posting.baseAmountMinor,
                    )
                }
            } else {
                val signOpposite = (posting.amountMinor > 0L && posting.baseAmountMinor < 0L) ||
                    (posting.amountMinor < 0L && posting.baseAmountMinor > 0L)
                if (signOpposite) {
                    errors += LedgerValidationError.BaseAmountSignMismatch(
                        postingId = posting.id,
                        amountMinor = posting.amountMinor,
                        baseAmountMinor = posting.baseAmountMinor,
                    )
                }
            }

            if (posting.isSystemBalancing) {
                val expectedTradingId = SystemAccounts.tradingAccountId(posting.currency)
                if (posting.accountId != expectedTradingId) {
                    errors += LedgerValidationError.InvalidTradingAccount(
                        postingId = posting.id,
                        accountId = posting.accountId,
                        expectedAccountId = expectedTradingId,
                    )
                }
            }

            val quantity = posting.quantity
            if (quantity != null) {
                if (quantity.isZero) {
                    errors += LedgerValidationError.InvestmentQuantityZero(posting.id)
                } else {
                    val qtySignMismatch = (posting.amountMinor > 0L && quantity.isNegative) ||
                        (posting.amountMinor < 0L && quantity.isPositive)
                    if (qtySignMismatch) {
                        errors += LedgerValidationError.InvestmentQuantitySignMismatch(
                            postingId = posting.id,
                            amountMinor = posting.amountMinor,
                            quantity = quantity,
                        )
                    }
                }
            }

            if (accounts != null) {
                val account = accounts[posting.accountId]
                if (account == null) {
                    errors += LedgerValidationError.UnknownAccount(posting.id, posting.accountId)
                } else {
                    if (!allowArchivedAccounts && account.isArchived) {
                        errors += LedgerValidationError.ArchivedAccountNotAllowed(
                            postingId = posting.id,
                            accountId = posting.accountId,
                        )
                    }
                    if (account.type.isBalanceSheet && account.currency != posting.currency) {
                        errors += LedgerValidationError.AccountCurrencyMismatch(
                            postingId = posting.id,
                            accountId = posting.accountId,
                            accountCurrency = account.currency,
                            postingCurrency = posting.currency,
                        )
                    }
                }
            }
        }

        if (baseCurrencies.size > 1) {
            errors += LedgerValidationError.MixedBaseCurrencies(baseCurrencies)
        }

        if (currencies.size == 1) {
            for (posting in postings) {
                if (posting.isSystemBalancing || SystemAccounts.isTradingAccountId(posting.accountId)) {
                    errors += LedgerValidationError.SystemBalancingInSingleCurrency(
                        currency = posting.currency,
                        postingId = posting.id,
                    )
                }
            }
        }

        // Group by currency and check exact zero-sum with overflow protection
        val groupedByCurrency = postings.groupBy { it.currency }
        for ((currency, currencyPostings) in groupedByCurrency) {
            var sum = 0L
            var overflowed = false
            for (posting in currencyPostings) {
                try {
                    sum = Math.addExact(sum, posting.amountMinor)
                } catch (_: ArithmeticException) {
                    overflowed = true
                    break
                }
            }
            if (overflowed) {
                errors += LedgerValidationError.CurrencySumOverflow(currency)
            } else if (sum != 0L) {
                errors += LedgerValidationError.UnbalancedCurrency(currency, sum)
            }
        }

        return if (errors.isEmpty()) {
            LedgerValidationResult.Valid
        } else {
            LedgerValidationResult.Invalid(errors)
        }
    }

    fun requireValid(
        transaction: Transaction,
        accounts: Map<String, Account>? = null,
        allowArchivedAccounts: Boolean = false,
    ) {
        when (val result = validate(transaction, accounts, allowArchivedAccounts)) {
            LedgerValidationResult.Valid -> Unit
            is LedgerValidationResult.Invalid -> throw LedgerValidationException(result.errors)
        }
    }

    /**
     * Computes exact per-currency algebraic sums for live UI display in split entry mode.
     * Throws [ArithmeticException] if any currency sum overflows [Long].
     */
    fun computeCurrencyImbalances(postings: List<Posting>): Map<Currency, Long> {
        val totals = linkedMapOf<Currency, Long>()
        for (posting in postings) {
            val current = totals[posting.currency] ?: 0L
            totals[posting.currency] = Math.addExact(current, posting.amountMinor)
        }
        return totals
    }
}
