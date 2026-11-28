package io.github.kvtttba.droplet.core.ui.chart

import androidx.compose.ui.graphics.Color
import io.github.kvtttba.droplet.core.model.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Zero-floating-point compact numeric formatters for chart axes and calendar micro-cells,
 * strictly honoring global privacy-masking.
 */
internal object ChartFormatters {

    private val TEN_THOUSAND = BigDecimal.valueOf(10_000L)
    private val THOUSAND = BigDecimal.valueOf(1_000L)

    val fallbackChartPalette: List<Color> = listOf(
        Color(0xFF006970),
        Color(0xFFEF6C00),
        Color(0xFF1976D2),
        Color(0xFF8E24AA),
        Color(0xFF2E7D32),
        Color(0xFFD81B60),
        Color(0xFF3949AB),
        Color(0xFF00897B),
        Color(0xFF8C6D46),
        Color(0xFF546E7A),
    )

    fun formatAxisTick(
        money: Money,
        hidden: Boolean,
    ): String {
        if (hidden) return "****"
        val bd = money.toBigDecimal()
        val abs = bd.abs()
        val sign = if (bd.signum() < 0) "-" else ""
        return when {
            abs.compareTo(BigDecimal.ZERO) == 0 -> "0"
            abs >= TEN_THOUSAND -> {
                val w = abs.divide(TEN_THOUSAND, 1, RoundingMode.HALF_UP).stripTrailingZeros()
                "$sign${w.toPlainString()}万"
            }
            abs >= THOUSAND -> {
                val k = abs.divide(THOUSAND, 1, RoundingMode.HALF_UP).stripTrailingZeros()
                "$sign${k.toPlainString()}k"
            }
            else -> {
                val n = abs.setScale(0, RoundingMode.HALF_UP)
                "$sign${n.toPlainString()}"
            }
        }
    }

    fun formatCalendarMicroAmount(
        money: Money,
        prefix: String,
    ): String {
        val abs = money.toBigDecimal().abs()
        return when {
            abs >= TEN_THOUSAND -> {
                val w = abs.divide(TEN_THOUSAND, 1, RoundingMode.HALF_UP).stripTrailingZeros()
                "$prefix${w.toPlainString()}w"
            }
            abs >= THOUSAND -> {
                val k = abs.divide(THOUSAND, 1, RoundingMode.HALF_UP).stripTrailingZeros()
                "$prefix${k.toPlainString()}k"
            }
            abs.stripTrailingZeros().scale() <= 0 -> {
                "$prefix${abs.setScale(0, RoundingMode.HALF_UP).toPlainString()}"
            }
            else -> {
                val rounded = abs.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros()
                "$prefix${rounded.toPlainString()}"
            }
        }
    }

    /**
     * Zero-floating-point ratio calculation returning a 0..1 UI proportion for Canvas rendering.
     */
    fun ratioToFloat(
        numeratorMinor: Long,
        denominatorMinor: Long,
    ): Float {
        if (denominatorMinor <= 0L || numeratorMinor <= 0L) return 0f
        return BigDecimal.valueOf(numeratorMinor)
            .divide(BigDecimal.valueOf(denominatorMinor), 4, RoundingMode.HALF_UP)
            .toFloat()
            .coerceIn(0f, 1f)
    }

    fun signedRatioToFloat(
        valueMinor: Long,
        minMinor: Long,
        maxMinor: Long,
    ): Float {
        val span = maxMinor - minMinor
        if (span <= 0L) return 0.5f
        return BigDecimal.valueOf(valueMinor - minMinor)
            .divide(BigDecimal.valueOf(span), 4, RoundingMode.HALF_UP)
            .toFloat()
            .coerceIn(0f, 1f)
    }
}
