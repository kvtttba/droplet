package io.github.kvtttba.droplet.core.model

import kotlinx.serialization.Serializable

/**
 * Single double-entry ledger line belonging to a [Transaction].
 * Carries both original-currency fixed-point amount ([amountMinor]) and
 * transaction-time locked base-currency amount ([baseAmountMinor]) with its
 * immutable [exchangeRateSnapshot].
 */
@Serializable
data class Posting(
    val id: String,
    val transactionId: String,
    val accountId: String,
    val currency: Currency,
    val amountMinor: Long,
    val baseCurrency: Currency,
    val baseAmountMinor: Long,
    val exchangeRateSnapshot: ExchangeRateSnapshot,
    val investmentAssetId: String? = null,
    val quantity: Quantity? = null,
    val unitPrice: UnitPrice? = null,
    val isSystemBalancing: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Posting id must not be blank" }
        require(transactionId.isNotBlank()) { "Posting transactionId must not be blank" }
        require(accountId.isNotBlank()) { "Posting accountId must not be blank" }
        require(exchangeRateSnapshot.fromCurrency == currency) {
            "ExchangeRateSnapshot.fromCurrency (${exchangeRateSnapshot.fromCurrency}) " +
                "must match posting currency ($currency)"
        }
        require(exchangeRateSnapshot.toCurrency == baseCurrency) {
            "ExchangeRateSnapshot.toCurrency (${exchangeRateSnapshot.toCurrency}) " +
                "must match posting baseCurrency ($baseCurrency)"
        }
        if (quantity != null || unitPrice != null) {
            require(!investmentAssetId.isNullOrBlank()) {
                "investmentAssetId is required when quantity or unitPrice is specified"
            }
        }
    }

    val money: Money
        get() = Money(amountMinor, currency)

    val baseMoney: Money
        get() = Money(baseAmountMinor, baseCurrency)

    val quantityDecimal: String?
        get() = quantity?.canonical

    val unitPriceDecimal: String?
        get() = unitPrice?.canonical
}
