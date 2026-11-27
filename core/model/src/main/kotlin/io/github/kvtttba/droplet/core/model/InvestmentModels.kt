package io.github.kvtttba.droplet.core.model

import java.math.BigDecimal
import kotlinx.serialization.Serializable

@Serializable
enum class AssetClass {
    CN_STOCK,
    CN_FUND,
    ETF,
    HK_STOCK,
    US_STOCK,
    GOLD,
    CRYPTO,
    FIXED_DEPOSIT,
    BANK_WEALTH,
}

@Serializable
enum class DepositInterestMode {
    SIMPLE_AT_MATURITY,
    COMPOUND_ANNUAL,
    MONTHLY_PAYOUT,
}

/**
 * Investment security / holding metadata linked to an `INVEST_HOLDING` [Account].
 */
@Serializable
data class InvestmentAsset(
    val id: String,
    val accountId: String,
    val assetClass: AssetClass,
    val symbol: String,
    val name: String,
    val currency: Currency,
    val quoteProviderHint: String? = null,
    val depositRateAnnualDecimal: String? = null,
    val depositStartAt: Long? = null,
    val depositMaturityAt: Long? = null,
    val depositInterestMode: DepositInterestMode? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "InvestmentAsset id must not be blank" }
        require(accountId.isNotBlank()) { "InvestmentAsset accountId must not be blank" }
        require(symbol.isNotBlank()) { "InvestmentAsset symbol must not be blank" }
        require(name.isNotBlank()) { "InvestmentAsset name must not be blank" }
        if (depositRateAnnualDecimal != null) {
            val parsed = BigDecimal(depositRateAnnualDecimal)
            require(parsed.compareTo(BigDecimal.ZERO) >= 0) {
                "depositRateAnnualDecimal must be non-negative, got '$depositRateAnnualDecimal'"
            }
            val expected = DecimalCanonicalizer.normalize(parsed, ExchangeRateSnapshot.SCALE)
            require(depositRateAnnualDecimal == expected) {
                "depositRateAnnualDecimal must be canonical decimal: expected '$expected', got '$depositRateAnnualDecimal'"
            }
        }
        if (depositStartAt != null && depositMaturityAt != null) {
            require(depositMaturityAt >= depositStartAt) {
                "depositMaturityAt ($depositMaturityAt) must not precede depositStartAt ($depositStartAt)"
            }
        }
    }
}

/**
 * Independent market valuation cache for an [InvestmentAsset], kept separate from ledger postings.
 */
@Serializable
data class MarketQuote(
    val assetId: String,
    val latestPrice: UnitPrice,
    val prevClose: UnitPrice? = null,
    val dailyChangePctDecimal: String? = null,
    val priceCurrency: Currency,
    val sourceProvider: String,
    val isManualOverride: Boolean = false,
    val fetchedAt: Long,
) {
    init {
        require(assetId.isNotBlank()) { "MarketQuote assetId must not be blank" }
        require(sourceProvider.isNotBlank()) { "MarketQuote sourceProvider must not be blank" }
        if (dailyChangePctDecimal != null) {
            val parsed = BigDecimal(dailyChangePctDecimal)
            val expected = DecimalCanonicalizer.normalize(parsed, ExchangeRateSnapshot.SCALE)
            require(dailyChangePctDecimal == expected) {
                "dailyChangePctDecimal must be canonical decimal: expected '$expected', got '$dailyChangePctDecimal'"
            }
        }
    }
}

/**
 * Cached live exchange rate record (`1 fromCurrency = rateDecimal toCurrency`), separate from
 * per-posting historical snapshots.
 */
@Serializable
data class CachedExchangeRate(
    val fromCurrency: Currency,
    val toCurrency: Currency,
    val rateDecimal: String,
    val sourceProvider: String,
    val fetchedAt: Long,
) {
    init {
        require(sourceProvider.isNotBlank()) { "CachedExchangeRate sourceProvider must not be blank" }
        val parsed = BigDecimal(rateDecimal)
        require(parsed.compareTo(BigDecimal.ZERO) > 0) {
            "CachedExchangeRate rateDecimal must be strictly positive, got '$rateDecimal'"
        }
        val expected = DecimalCanonicalizer.normalize(parsed, ExchangeRateSnapshot.SCALE)
        require(rateDecimal == expected) {
            "CachedExchangeRate rateDecimal must be canonical decimal: expected '$expected', got '$rateDecimal'"
        }
        if (fromCurrency == toCurrency) {
            require(rateDecimal == "1") {
                "Same-currency cached exchange rate must be '1', got '$rateDecimal'"
            }
        }
    }

    fun toSnapshot(
        source: ExchangeRateSource = if (fromCurrency == toCurrency) {
            ExchangeRateSource.IDENTITY
        } else {
            ExchangeRateSource.MARKET_CACHE
        },
    ): ExchangeRateSnapshot = ExchangeRateSnapshot(
        fromCurrency = fromCurrency,
        toCurrency = toCurrency,
        rateDecimal = rateDecimal,
        source = source,
        capturedAt = fetchedAt,
    )
}
