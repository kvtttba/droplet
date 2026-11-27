package io.github.kvtttba.droplet.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.Serializable

@Serializable
enum class ExchangeRateSource {
    IDENTITY,
    MARKET_CACHE,
    USER_OVERRIDE,
    IMPLIED_FROM_AMOUNTS,
}

/**
 * Immutable exchange rate snapshot locked on a posting at transaction time.
 * Semantics: 1 unit of [fromCurrency] equals [rateDecimal] units of [toCurrency].
 * Rate is persisted as a canonical plain-decimal string with up to 8 decimal places.
 */
@Serializable
data class ExchangeRateSnapshot(
    val fromCurrency: Currency,
    val toCurrency: Currency,
    val rateDecimal: String,
    val source: ExchangeRateSource = ExchangeRateSource.MARKET_CACHE,
    val capturedAt: Long = 0L,
) {
    init {
        val parsed = try {
            BigDecimal(rateDecimal)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid exchange rate decimal: '$rateDecimal'", e)
        }
        require(parsed.compareTo(BigDecimal.ZERO) > 0) {
            "Exchange rate must be strictly positive, got '$rateDecimal'"
        }
        val expected = DecimalCanonicalizer.normalize(parsed, SCALE)
        require(rateDecimal == expected) {
            "Exchange rate must use canonical plain decimal format (max scale $SCALE): " +
                "expected '$expected' but got '$rateDecimal'"
        }
        if (fromCurrency == toCurrency) {
            require(rateDecimal == "1") {
                "Same-currency exchange rate (${fromCurrency.code}->${toCurrency.code}) must be 1, got '$rateDecimal'"
            }
        }
    }

    fun toBigDecimal(): BigDecimal = BigDecimal(rateDecimal)

    fun convert(
        amount: Money,
        roundingMode: RoundingMode = RoundingMode.HALF_UP,
    ): Money {
        require(amount.currency == fromCurrency) {
            "Cannot convert ${amount.currency.code} using rate ${fromCurrency.code}->${toCurrency.code}"
        }
        if (fromCurrency == toCurrency) {
            return amount
        }
        val converted = amount.toBigDecimal().multiply(toBigDecimal())
        return Money.fromBigDecimal(converted, toCurrency, roundingMode)
    }

    fun inverse(
        source: ExchangeRateSource = this.source,
        capturedAt: Long = this.capturedAt,
    ): ExchangeRateSnapshot {
        if (fromCurrency == toCurrency) {
            return identity(fromCurrency, capturedAt)
        }
        val inverted = BigDecimal.ONE.divide(toBigDecimal(), SCALE, RoundingMode.HALF_UP)
        require(inverted.compareTo(BigDecimal.ZERO) > 0) {
            "Inverse exchange rate underflowed to zero for $rateDecimal"
        }
        return of(
            fromCurrency = toCurrency,
            toCurrency = fromCurrency,
            rate = inverted,
            source = source,
            capturedAt = capturedAt,
        )
    }

    companion object {
        const val SCALE: Int = 8

        fun identity(
            currency: Currency,
            capturedAt: Long = 0L,
        ): ExchangeRateSnapshot = ExchangeRateSnapshot(
            fromCurrency = currency,
            toCurrency = currency,
            rateDecimal = "1",
            source = ExchangeRateSource.IDENTITY,
            capturedAt = capturedAt,
        )

        fun of(
            fromCurrency: Currency,
            toCurrency: Currency,
            rate: BigDecimal,
            source: ExchangeRateSource = if (fromCurrency == toCurrency) {
                ExchangeRateSource.IDENTITY
            } else {
                ExchangeRateSource.MARKET_CACHE
            },
            capturedAt: Long = 0L,
        ): ExchangeRateSnapshot {
            require(rate.compareTo(BigDecimal.ZERO) > 0) {
                "Exchange rate must be strictly positive, got $rate"
            }
            val normalized = DecimalCanonicalizer.normalize(rate, SCALE)
            require(BigDecimal(normalized).compareTo(BigDecimal.ZERO) > 0) {
                "Exchange rate rounded to zero at scale $SCALE: $rate"
            }
            return ExchangeRateSnapshot(
                fromCurrency = fromCurrency,
                toCurrency = toCurrency,
                rateDecimal = normalized,
                source = source,
                capturedAt = capturedAt,
            )
        }

        fun of(
            fromCurrency: Currency,
            toCurrency: Currency,
            rateDecimal: String,
            source: ExchangeRateSource = if (fromCurrency == toCurrency) {
                ExchangeRateSource.IDENTITY
            } else {
                ExchangeRateSource.MARKET_CACHE
            },
            capturedAt: Long = 0L,
        ): ExchangeRateSnapshot {
            val trimmed = rateDecimal.trim()
            require(trimmed.isNotEmpty()) { "Exchange rate string must not be blank" }
            val parsed = try {
                BigDecimal(trimmed)
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Invalid exchange rate decimal: '$rateDecimal'", e)
            }
            return of(
                fromCurrency = fromCurrency,
                toCurrency = toCurrency,
                rate = parsed,
                source = source,
                capturedAt = capturedAt,
            )
        }

        /**
         * Derives the implied rate `fromAmount.currency -> toAmount.currency`
         * such that `1 fromCurrency = (toAmount / fromAmount) toCurrency`.
         */
        fun impliedFromAmounts(
            fromAmount: Money,
            toAmount: Money,
            capturedAt: Long = 0L,
        ): ExchangeRateSnapshot {
            require(fromAmount.isPositive) {
                "Source amount must be strictly positive to infer exchange rate, got $fromAmount"
            }
            require(toAmount.isPositive) {
                "Target amount must be strictly positive to infer exchange rate, got $toAmount"
            }
            if (fromAmount.currency == toAmount.currency) {
                require(fromAmount.amountMinor == toAmount.amountMinor) {
                    "Same-currency amounts must be equal to imply identity rate: $fromAmount vs $toAmount"
                }
                return identity(fromAmount.currency, capturedAt)
            }
            val rate = toAmount.toBigDecimal()
                .divide(fromAmount.toBigDecimal(), SCALE, RoundingMode.HALF_UP)
            require(rate.compareTo(BigDecimal.ZERO) > 0) {
                "Implied exchange rate underflowed to zero for $fromAmount -> $toAmount"
            }
            return of(
                fromCurrency = fromAmount.currency,
                toCurrency = toAmount.currency,
                rate = rate,
                source = ExchangeRateSource.IMPLIED_FROM_AMOUNTS,
                capturedAt = capturedAt,
            )
        }
    }
}
