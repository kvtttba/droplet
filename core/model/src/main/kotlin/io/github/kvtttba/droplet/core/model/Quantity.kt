package io.github.kvtttba.droplet.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.Serializable

/**
 * Signed investment / crypto share quantity backed by a canonical plain-decimal
 * BigDecimal string supporting up to 8 decimal places (never scientific notation).
 * Positive represents buy/inflow; negative represents sell/outflow.
 */
@Serializable
data class Quantity(
    val canonical: String,
) : Comparable<Quantity> {

    init {
        val parsed = try {
            BigDecimal(canonical)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid quantity decimal: '$canonical'", e)
        }
        val expected = DecimalCanonicalizer.normalize(parsed, MAX_SCALE)
        require(canonical == expected) {
            "Quantity must be in canonical plain decimal format (max scale $MAX_SCALE): " +
                "expected '$expected' but got '$canonical'"
        }
    }

    fun toBigDecimal(): BigDecimal = BigDecimal(canonical)

    fun toFixedScaleString(scale: Int = MAX_SCALE): String {
        require(scale in 0..MAX_SCALE) { "Scale must be within 0..$MAX_SCALE, got $scale" }
        return toBigDecimal().setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }

    val isZero: Boolean get() = toBigDecimal().compareTo(BigDecimal.ZERO) == 0
    val isPositive: Boolean get() = toBigDecimal().compareTo(BigDecimal.ZERO) > 0
    val isNegative: Boolean get() = toBigDecimal().compareTo(BigDecimal.ZERO) < 0

    operator fun plus(other: Quantity): Quantity =
        of(toBigDecimal().add(other.toBigDecimal()))

    operator fun minus(other: Quantity): Quantity =
        of(toBigDecimal().subtract(other.toBigDecimal()))

    operator fun unaryMinus(): Quantity =
        if (isZero) this else of(toBigDecimal().negate())

    fun abs(): Quantity = if (isNegative) -this else this

    override fun compareTo(other: Quantity): Int =
        toBigDecimal().compareTo(other.toBigDecimal())

    companion object {
        const val MAX_SCALE: Int = 8

        val ZERO: Quantity = Quantity("0")

        fun of(
            decimal: String,
            roundingMode: RoundingMode = RoundingMode.HALF_UP,
        ): Quantity = Quantity(
            DecimalCanonicalizer.parseAndNormalize(decimal, MAX_SCALE, roundingMode),
        )

        fun of(
            value: BigDecimal,
            roundingMode: RoundingMode = RoundingMode.HALF_UP,
        ): Quantity = Quantity(
            DecimalCanonicalizer.normalize(value, MAX_SCALE, roundingMode),
        )

        fun ofLong(value: Long): Quantity = of(BigDecimal.valueOf(value))
    }
}
