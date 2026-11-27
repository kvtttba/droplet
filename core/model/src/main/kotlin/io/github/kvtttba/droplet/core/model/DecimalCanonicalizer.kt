package io.github.kvtttba.droplet.core.model

import java.math.BigDecimal
import java.math.RoundingMode

internal object DecimalCanonicalizer {

    fun normalize(
        value: BigDecimal,
        maxScale: Int,
        roundingMode: RoundingMode = RoundingMode.HALF_UP,
    ): String {
        val scaled = if (value.scale() > maxScale) {
            value.setScale(maxScale, roundingMode)
        } else {
            value
        }
        if (scaled.compareTo(BigDecimal.ZERO) == 0) {
            return "0"
        }
        return scaled.stripTrailingZeros().toPlainString()
    }

    fun parseAndNormalize(
        decimal: String,
        maxScale: Int,
        roundingMode: RoundingMode = RoundingMode.HALF_UP,
    ): String {
        val trimmed = decimal.trim()
        require(trimmed.isNotEmpty()) { "Decimal string must not be blank" }
        val parsed = try {
            BigDecimal(trimmed)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid decimal string: '$decimal'", e)
        }
        return normalize(parsed, maxScale, roundingMode)
    }
}
