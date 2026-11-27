package io.github.kvtttba.droplet.core.domain

import io.github.kvtttba.droplet.core.model.Money
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Pure JVM formatter for fiat monetary amounts with tabular-friendly grouping
 * and global privacy-masking support.
 */
object PrivacyAmountFormatter {

    const val MASKED_PLACEHOLDER: String = "****"

    fun format(
        money: Money,
        hidden: Boolean = false,
        showPositiveSign: Boolean = false,
        showCurrencySymbol: Boolean = true,
    ): String {
        if (hidden) {
            return if (showCurrencySymbol) {
                "${money.currency.symbol} $MASKED_PLACEHOLDER"
            } else {
                MASKED_PLACEHOLDER
            }
        }

        val scaled = money.toBigDecimal().abs().setScale(2, RoundingMode.HALF_UP)
        val symbols = DecimalFormatSymbols(Locale.US)
        val formatter = DecimalFormat("#,##0.00", symbols)
        val digits = formatter.format(scaled)

        val sign = when {
            money.isNegative -> "-"
            showPositiveSign && money.isPositive -> "+"
            else -> ""
        }
        val symbol = if (showCurrencySymbol) money.currency.symbol else ""
        return "$sign$symbol$digits"
    }
}
