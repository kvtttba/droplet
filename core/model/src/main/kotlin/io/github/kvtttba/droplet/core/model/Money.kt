package io.github.kvtttba.droplet.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.Serializable

/**
 * Fixed-point fiat monetary amount (scale = 4, i.e., 10_000L == 1.0000).
 * Floating-point types are strictly forbidden for monetary computation.
 */
@Serializable
data class Money(
    val amountMinor: Long,
    val currency: Currency = Currency.CNY,
) : Comparable<Money> {

    operator fun plus(other: Money): Money {
        require(currency == other.currency) {
            "Currency mismatch: cannot add ${other.currency.code} to ${currency.code}"
        }
        return Money(Math.addExact(amountMinor, other.amountMinor), currency)
    }

    operator fun minus(other: Money): Money {
        require(currency == other.currency) {
            "Currency mismatch: cannot subtract ${other.currency.code} from ${currency.code}"
        }
        return Money(Math.subtractExact(amountMinor, other.amountMinor), currency)
    }

    operator fun unaryMinus(): Money = Money(Math.negateExact(amountMinor), currency)

    fun abs(): Money = Money(kotlin.math.abs(amountMinor), currency)

    val isZero: Boolean get() = amountMinor == 0L
    val isPositive: Boolean get() = amountMinor > 0L
    val isNegative: Boolean get() = amountMinor < 0L

    fun toBigDecimal(): BigDecimal =
        BigDecimal.valueOf(amountMinor, SCALE)

    override fun compareTo(other: Money): Int {
        require(currency == other.currency) {
            "Currency mismatch: cannot compare ${currency.code} with ${other.currency.code}"
        }
        return amountMinor.compareTo(other.amountMinor)
    }

    companion object {
        const val SCALE: Int = 4
        const val SCALE_FACTOR: Long = 10_000L

        fun zero(currency: Currency = Currency.CNY): Money = Money(0L, currency)

        fun ofMinor(amountMinor: Long, currency: Currency = Currency.CNY): Money =
            Money(amountMinor, currency)

        fun fromDecimalString(decimal: String, currency: Currency = Currency.CNY): Money {
            val trimmed = decimal.trim()
            require(trimmed.isNotEmpty()) { "Decimal amount string must not be blank" }
            val bd = BigDecimal(trimmed)
                .setScale(SCALE, RoundingMode.HALF_UP)
            val minor = bd.movePointRight(SCALE).longValueExact()
            return Money(minor, currency)
        }
    }
}
