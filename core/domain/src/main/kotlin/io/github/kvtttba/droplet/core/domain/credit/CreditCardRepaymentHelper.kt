package io.github.kvtttba.droplet.core.domain.credit

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.domain.ledger.TransferFeeMode
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction

/**
 * Quick-repayment preset options for the credit card one-tap repayment form.
 */
enum class CreditCardRepaymentMode {
    /** Pay off the remaining balance of the most recently closed statement. */
    STATEMENT_DUE,

    /** Pay off the entire current outstanding debt (closed statement + unbilled charges). */
    FULL_BALANCE,

    /** Pay a user-specified custom amount (supports partial repayment or prepayment/overpayment). */
    CUSTOM,
}

/**
 * Pure Kotlin/JVM helper constructing double-entry credit-card repayment transactions via [LedgerEngine].
 *
 * Supports:
 * - Same-currency repayment (`TRANSFER`: `paymentAccount` `-amount`, `creditCardAccount` `+amount`)
 * - Cross-currency repayment (automatic `Equity:Trading:{CCY}` hedging via [LedgerEngine.buildCrossCurrencyTransfer])
 * - Optional transfer/repayment fee
 * - Quick preset amount resolution ([CreditCardRepaymentMode.STATEMENT_DUE], [CreditCardRepaymentMode.FULL_BALANCE],
 *   [CreditCardRepaymentMode.CUSTOM])
 */
object CreditCardRepaymentHelper {

    fun defaultRepaymentMode(summary: CreditCardStatementSummary): CreditCardRepaymentMode = when {
        summary.remainingStatementDue.isPositive -> CreditCardRepaymentMode.STATEMENT_DUE
        summary.currentDebt.isPositive -> CreditCardRepaymentMode.FULL_BALANCE
        else -> CreditCardRepaymentMode.CUSTOM
    }

    fun resolveSuggestedAmount(
        summary: CreditCardStatementSummary,
        mode: CreditCardRepaymentMode,
        customAmount: Money? = null,
    ): Money = when (mode) {
        CreditCardRepaymentMode.STATEMENT_DUE -> summary.remainingStatementDue
        CreditCardRepaymentMode.FULL_BALANCE -> summary.currentDebt
        CreditCardRepaymentMode.CUSTOM -> {
            val amount = requireNotNull(customAmount) {
                "customAmount is required for CreditCardRepaymentMode.CUSTOM"
            }
            require(amount.currency == summary.account.currency) {
                "Custom repayment currency (${amount.currency}) must match credit card currency (${summary.account.currency})"
            }
            amount
        }
    }

    /**
     * Resolves the payment-account outflow amount when repaying a credit card in a different currency,
     * using strict `BigDecimal` rate conversion with zero floating-point arithmetic.
     */
    fun resolveCrossCurrencyPaidAmount(
        repaymentAmount: Money,
        paymentAccount: Account,
        paidFromAccountAmount: Money? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProvider: ExchangeRateProvider? = null,
    ): Money {
        require(repaymentAmount.isPositive) {
            "Repayment amount must be strictly positive, got $repaymentAmount"
        }
        if (paymentAccount.currency == repaymentAmount.currency) {
            if (paidFromAccountAmount != null) {
                require(paidFromAccountAmount == repaymentAmount) {
                    "Same-currency paidFromAccountAmount ($paidFromAccountAmount) must equal repaymentAmount ($repaymentAmount)"
                }
            }
            return repaymentAmount
        }
        if (paidFromAccountAmount != null) {
            require(paidFromAccountAmount.currency == paymentAccount.currency) {
                "paidFromAccountAmount currency (${paidFromAccountAmount.currency}) must match " +
                    "payment account currency (${paymentAccount.currency})"
            }
            require(paidFromAccountAmount.isPositive) {
                "paidFromAccountAmount must be strictly positive, got $paidFromAccountAmount"
            }
            return paidFromAccountAmount
        }

        val rate: ExchangeRateSnapshot = when {
            exchangeRateOverride != null -> resolveDirectedRate(
                snapshot = exchangeRateOverride,
                from = repaymentAmount.currency,
                to = paymentAccount.currency,
            )
            rateProvider != null -> rateProvider.requireRate(
                from = repaymentAmount.currency,
                to = paymentAccount.currency,
            )
            else -> throw IllegalArgumentException(
                "Cross-currency repayment (${paymentAccount.currency} -> ${repaymentAmount.currency}) " +
                    "requires paidFromAccountAmount, exchangeRateOverride, or rateProvider",
            )
        }
        val converted = rate.convert(repaymentAmount)
        require(converted.isPositive) {
            "Converted cross-currency payment amount must be strictly positive, got $converted"
        }
        return converted
    }

    fun buildRepaymentTransaction(
        ledgerEngine: LedgerEngine,
        meta: TransactionMeta,
        creditCardAccount: Account,
        paymentAccount: Account,
        repaymentAmount: Money,
        paidFromAccountAmount: Money? = null,
        fee: Money? = null,
        feeExpenseAccountId: String? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(
            creditCardAccount.type == AccountType.LIABILITY &&
                creditCardAccount.subType == AccountSubType.CREDIT_CARD,
        ) {
            "Target account '${creditCardAccount.id}' must be a LIABILITY / CREDIT_CARD account"
        }
        require(paymentAccount.id != creditCardAccount.id) {
            "Payment account must differ from credit card account '${creditCardAccount.id}'"
        }
        require(paymentAccount.type == AccountType.ASSET) {
            "Repayment source account '${paymentAccount.id}' must be an ASSET account, got ${paymentAccount.type}"
        }
        require(!creditCardAccount.isArchived) {
            "Credit card account '${creditCardAccount.id}' is archived"
        }
        require(!paymentAccount.isArchived) {
            "Payment account '${paymentAccount.id}' is archived"
        }
        require(repaymentAmount.currency == creditCardAccount.currency) {
            "Repayment amount currency (${repaymentAmount.currency}) must match credit card currency (${creditCardAccount.currency})"
        }
        require(repaymentAmount.isPositive) {
            "Repayment amount must be strictly positive, got $repaymentAmount"
        }

        val resolvedMeta = meta.copy(
            payee = meta.payee?.trim()?.takeIf { it.isNotEmpty() } ?: "信用卡还款 · ${creditCardAccount.name}",
            note = meta.note?.trim()?.takeIf { it.isNotEmpty() },
        )

        val effectiveRates = rateProviderOverride ?: ledgerEngine.rateProvider

        return if (paymentAccount.currency == creditCardAccount.currency) {
            if (paidFromAccountAmount != null) {
                require(paidFromAccountAmount == repaymentAmount) {
                    "Same-currency paidFromAccountAmount ($paidFromAccountAmount) must equal repaymentAmount ($repaymentAmount)"
                }
            }
            ledgerEngine.buildTransfer(
                meta = resolvedMeta,
                fromAccountId = paymentAccount.id,
                toAccountId = creditCardAccount.id,
                amount = repaymentAmount,
                fee = fee,
                feeExpenseAccountId = feeExpenseAccountId,
                feeMode = TransferFeeMode.SENDER_EXTRA,
                exchangeRateOverride = exchangeRateOverride,
                rateProviderOverride = rateProviderOverride,
                accounts = accounts,
            )
        } else {
            val paidAmount = resolveCrossCurrencyPaidAmount(
                repaymentAmount = repaymentAmount,
                paymentAccount = paymentAccount,
                paidFromAccountAmount = paidFromAccountAmount,
                exchangeRateOverride = exchangeRateOverride,
                rateProvider = effectiveRates,
            )
            ledgerEngine.buildCrossCurrencyTransfer(
                meta = resolvedMeta,
                fromAccountId = paymentAccount.id,
                toAccountId = creditCardAccount.id,
                paidAmount = paidAmount,
                receivedAmount = repaymentAmount,
                fee = fee,
                feeExpenseAccountId = feeExpenseAccountId,
                feeMode = TransferFeeMode.SENDER_EXTRA,
                rateProviderOverride = rateProviderOverride,
                accounts = accounts,
            )
        }
    }

    private fun resolveDirectedRate(
        snapshot: ExchangeRateSnapshot,
        from: io.github.kvtttba.droplet.core.model.Currency,
        to: io.github.kvtttba.droplet.core.model.Currency,
    ): ExchangeRateSnapshot = when {
        snapshot.fromCurrency == from && snapshot.toCurrency == to -> snapshot
        snapshot.fromCurrency == to && snapshot.toCurrency == from -> snapshot.inverse()
        else -> throw IllegalArgumentException(
            "exchangeRateOverride (${snapshot.fromCurrency}->${snapshot.toCurrency}) " +
                "does not match repayment currency pair ($from->$to)",
        )
    }
}
