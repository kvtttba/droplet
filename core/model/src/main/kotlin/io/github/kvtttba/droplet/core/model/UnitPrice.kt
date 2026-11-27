package io.github.kvtttba.droplet.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.Serializable

/**
 * Non-negative asset unit price / NAV backed by a canonical plain-decimal
 * BigDecimal string supporting up to 8 decimal places.
 */
@Serializable
data class UnitPrice(
    val canonical: String,
) : Comparable<UnitPrice> {

    init {
        val parsed = try {
            BigDecimal(canonical)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid unit price decimal: '$canonical'", e)
        }
        require(parsed.compareTo(BigDecimal.ZERO) >= 0) {
            "UnitPrice must be non-negative, got '$canonical'"
        }
        val expected = DecimalCanonicalizer.normalize(parsed, MAX_SCALE)
        require(canonical == expected) {
            "UnitPrice must be in canonical plain decimal format (max scale $MAX_SCALE): " +
                "expected '$expected' but got '$canonical'"
        }
    }

    fun toBigDecimal(): BigDecimal = BigDecimal(canonical)

    val isZero: Boolean get() = toBigDecimal().compareTo(BigDecimal.ZERO) == 0
    val isPositive: Boolean get() = toBigDecimal().compareTo(BigDecimal.ZERO) > 0

    override fun compareTo(other: UnitPrice): Int =
        toBigDecimal().compareTo(other.toBigDecimal())

    companion object {
        const val MAX_SCALE: Int = 8

        val ZERO: UnitPrice = UnitPrice("0")

        fun of(
            decimal: String,
            roundingMode: RoundingMode = RoundingMode.HALF_UP,
        ): UnitPrice {
            val normalized = DecimalCanonicalizer.parseAndNormalize(decimal, MAX_SCALE, roundingMode)
            return UnitPrice(normalized)
        }

        fun of(
            value: BigDecimal,
            roundingMode: RoundingMode = RoundingMode.HALF_UP,
        ): UnitPrice {
            require(value.compareTo(BigDecimal.ZERO) >= 0) {
                "UnitPrice must be non-negative, got $value"
            }
            return UnitPrice(DecimalCanonicalizer.normalize(value, MAX_SCALE, roundingMode))
        }
    }
}
