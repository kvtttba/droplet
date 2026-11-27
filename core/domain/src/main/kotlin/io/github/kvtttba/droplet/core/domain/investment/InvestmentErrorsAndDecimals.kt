package io.github.kvtttba.droplet.core.domain.investment

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.Quantity
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Thrown when an investment sell attempts to dispose of more shares/units than currently held.
 */
class OversellPositionException(
    val assetId: String,
    val attemptedSellQuantity: Quantity,
    val availableQuantity: Quantity,
) : IllegalArgumentException(
    "Cannot sell ${attemptedSellQuantity.canonical} units of asset '$assetId': " +
        "only ${availableQuantity.canonical} units currently held",
)

/**
 * Layered [ExchangeRateProvider] that queries [primary] first and falls back to [fallback]
 * when a rate pair is missing from [primary].
 */
class FallbackExchangeRateProvider(
    private val primary: ExchangeRateProvider,
    private val fallback: ExchangeRateProvider,
) : ExchangeRateProvider {

    override fun findRate(from: Currency, to: Currency): ExchangeRateSnapshot? {
        if (from == to) return ExchangeRateSnapshot.identity(from)
        return primary.findRate(from, to) ?: fallback.findRate(from, to)
    }
}

/**
 * Pure BigDecimal helpers shared by investment valuation & aggregation engines.
 * Floating-point types are strictly forbidden across all calculations.
 */
internal object InvestmentDecimalMath {
    const val INTERMEDIATE_SCALE: Int = 16
    const val PERCENT_SCALE: Int = 4
    val HUNDRED: BigDecimal = BigDecimal("100")

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

    /**
     * Computes `(numerator / denominator) * 100` as a canonical percentage string (up to 4 decimals).
     * Returns `null` when [denominator] is zero.
     */
    fun percentageOrNull(
        numerator: BigDecimal,
        denominator: BigDecimal,
        scale: Int = PERCENT_SCALE,
    ): String? {
        if (denominator.compareTo(BigDecimal.ZERO) == 0) return null
        val pct = numerator
            .multiply(HUNDRED)
            .divide(denominator, scale, RoundingMode.HALF_UP)
        return normalize(pct, scale)
    }
}
