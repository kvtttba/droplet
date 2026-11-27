package io.github.kvtttba.droplet.core.domain.ledger

import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Quantity

sealed interface LedgerValidationError {
    data class InsufficientPostings(val actualCount: Int) : LedgerValidationError

    data class ZeroAmountPosting(
        val postingId: String,
        val accountId: String,
    ) : LedgerValidationError

    data class DuplicatePostingId(val postingId: String) : LedgerValidationError

    data class CurrencySumOverflow(val currency: Currency) : LedgerValidationError

    data class UnbalancedCurrency(
        val currency: Currency,
        val imbalanceMinor: Long,
    ) : LedgerValidationError

    data class SystemBalancingInSingleCurrency(
        val currency: Currency,
        val postingId: String,
    ) : LedgerValidationError

    data class InvalidTradingAccount(
        val postingId: String,
        val accountId: String,
        val expectedAccountId: String,
    ) : LedgerValidationError

    data class InvalidMultiCurrencyFlow(
        val currencyImbalances: Map<Currency, Long>,
    ) : LedgerValidationError

    data class BaseCurrencyMismatchOnSameCurrency(
        val postingId: String,
        val amountMinor: Long,
        val baseAmountMinor: Long,
    ) : LedgerValidationError

    data class BaseAmountSignMismatch(
        val postingId: String,
        val amountMinor: Long,
        val baseAmountMinor: Long,
    ) : LedgerValidationError

    data class MixedBaseCurrencies(val baseCurrencies: Set<Currency>) : LedgerValidationError

    data class UnknownAccount(
        val postingId: String,
        val accountId: String,
    ) : LedgerValidationError

    data class AccountCurrencyMismatch(
        val postingId: String,
        val accountId: String,
        val accountCurrency: Currency,
        val postingCurrency: Currency,
    ) : LedgerValidationError

    data class ArchivedAccountNotAllowed(
        val postingId: String,
        val accountId: String,
    ) : LedgerValidationError

    data class InvestmentQuantityZero(val postingId: String) : LedgerValidationError

    data class InvestmentQuantitySignMismatch(
        val postingId: String,
        val amountMinor: Long,
        val quantity: Quantity,
    ) : LedgerValidationError
}

sealed interface LedgerValidationResult {
    data object Valid : LedgerValidationResult
    data class Invalid(val errors: List<LedgerValidationError>) : LedgerValidationResult

    val isValid: Boolean
        get() = this is Valid
}

class LedgerValidationException(
    val errors: List<LedgerValidationError>,
    message: String = "Double-entry ledger validation failed: ${errors.joinToString("; ")}",
) : IllegalArgumentException(message)

class MissingExchangeRateException(
    val fromCurrency: Currency,
    val toCurrency: Currency,
) : IllegalStateException(
    "No exchange rate available for ${fromCurrency.code} -> ${toCurrency.code}",
)
