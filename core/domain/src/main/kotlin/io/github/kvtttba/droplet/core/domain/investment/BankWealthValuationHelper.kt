package io.github.kvtttba.droplet.core.domain.investment

import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.MarketQuote
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.UnitPrice
import java.math.BigDecimal
import java.math.RoundingMode

enum class BankWealthValuationMode {
    /** Product has a public quote fetched from an external provider (`isManualOverride == false`). */
    PUBLIC_NAV,

    /** Product is valued using a user-supplied NAV or profit-implied NAV (`isManualOverride == true`). */
    MANUAL_NAV_OVERRIDE,

    /** No public or manual quote has been recorded yet; valuation falls back to book cost basis. */
    COST_BASIS_FALLBACK,
}

/**
 * Audit-ready result produced when manually updating a [AssetClass.BANK_WEALTH] product's
 * NAV, latest cumulative profit amount, or latest total market value.
 */
data class BankWealthManualValuationResult(
    val assetId: String,
    val holdingQuantity: Quantity,
    val costBasis: Money,
    val impliedMarketValue: Money,
    val impliedUnitPrice: UnitPrice,
    val profitAmount: Money,
    val profitRatePctDecimal: String?,
    val quote: MarketQuote,
    val updatedAtEpochMs: Long,
)

/**
 * Dual-path valuation helper for [AssetClass.BANK_WEALTH] products:
 * 1. Public NAV products use standard `quantity * latestPrice` from their non-manual [MarketQuote].
 * 2. Non-standardized bank wealth products support manually overriding either:
 *    - the latest unit NAV ([deriveQuoteFromUnitPrice]),
 *    - the latest cumulative profit/loss amount ([deriveQuoteFromProfitAmount]), or
 *    - the latest total market value ([deriveQuoteFromTotalValue]),
 *    back-calculating the canonical 8-decimal [UnitPrice] and audit-timestamped manual [MarketQuote].
 */
object BankWealthValuationHelper {

    /** Matches `MarketNetworkContract.MANUAL_OVERRIDE_PROVIDER_ID` while keeping `:core:domain` independent. */
    const val MANUAL_OVERRIDE_PROVIDER_ID: String = "manual"

    fun resolveValuationMode(
        asset: InvestmentAsset,
        marketQuote: MarketQuote?,
    ): BankWealthValuationMode {
        require(asset.assetClass == AssetClass.BANK_WEALTH) {
            "BankWealthValuationHelper only supports BANK_WEALTH assets, got ${asset.assetClass}"
        }
        return when {
            marketQuote == null -> BankWealthValuationMode.COST_BASIS_FALLBACK
            marketQuote.isManualOverride -> BankWealthValuationMode.MANUAL_NAV_OVERRIDE
            else -> BankWealthValuationMode.PUBLIC_NAV
        }
    }

    /**
     * Constructs a manual NAV override for a bank wealth product directly from [manualUnitPrice].
     */
    fun deriveQuoteFromUnitPrice(
        asset: InvestmentAsset,
        holdingQuantity: Quantity,
        costBasis: Money,
        manualUnitPrice: UnitPrice,
        updatedAtEpochMs: Long,
        previousQuote: MarketQuote? = null,
        prevCloseOverride: UnitPrice? = null,
    ): BankWealthManualValuationResult {
        validateCommonInputs(asset, holdingQuantity, costBasis)
        require(manualUnitPrice.isPositive) {
            "Manual bank wealth NAV must be strictly positive, got ${manualUnitPrice.canonical}"
        }

        val impliedMarketValue = Money.fromBigDecimal(
            holdingQuantity.toBigDecimal().multiply(manualUnitPrice.toBigDecimal()),
            asset.currency,
        )
        val profitAmount = impliedMarketValue - costBasis
        return buildManualResult(
            asset = asset,
            holdingQuantity = holdingQuantity,
            costBasis = costBasis,
            impliedMarketValue = impliedMarketValue,
            impliedUnitPrice = manualUnitPrice,
            profitAmount = profitAmount,
            updatedAtEpochMs = updatedAtEpochMs,
            previousQuote = previousQuote,
            prevCloseOverride = prevCloseOverride,
        )
    }

    /**
     * Back-calculates the implied latest NAV and current market value from the user's latest
     * cumulative profit/loss amount ([latestProfitAmount] may be positive, zero, or negative).
     */
    fun deriveQuoteFromProfitAmount(
        asset: InvestmentAsset,
        holdingQuantity: Quantity,
        costBasis: Money,
        latestProfitAmount: Money,
        updatedAtEpochMs: Long,
        previousQuote: MarketQuote? = null,
        prevCloseOverride: UnitPrice? = null,
    ): BankWealthManualValuationResult {
        validateCommonInputs(asset, holdingQuantity, costBasis)
        require(latestProfitAmount.currency == asset.currency) {
            "Profit amount currency (${latestProfitAmount.currency}) must match asset currency (${asset.currency})"
        }
        val impliedMarketValue = costBasis + latestProfitAmount
        require(impliedMarketValue.isPositive) {
            "Implied market value ($impliedMarketValue) from costBasis ($costBasis) + profit ($latestProfitAmount) must be positive"
        }

        val impliedPriceBd = impliedMarketValue.toBigDecimal().divide(
            holdingQuantity.toBigDecimal(),
            UnitPrice.MAX_SCALE,
            RoundingMode.HALF_UP,
        )
        val impliedUnitPrice = UnitPrice.of(impliedPriceBd)
        require(impliedUnitPrice.isPositive) {
            "Implied NAV underflowed to zero for market value $impliedMarketValue and quantity ${holdingQuantity.canonical}"
        }

        return buildManualResult(
            asset = asset,
            holdingQuantity = holdingQuantity,
            costBasis = costBasis,
            impliedMarketValue = impliedMarketValue,
            impliedUnitPrice = impliedUnitPrice,
            profitAmount = latestProfitAmount,
            updatedAtEpochMs = updatedAtEpochMs,
            previousQuote = previousQuote,
            prevCloseOverride = prevCloseOverride,
        )
    }

    /**
     * Back-calculates the implied latest NAV and profit amount from the user's reported
     * total current value ([currentTotalValue]).
     */
    fun deriveQuoteFromTotalValue(
        asset: InvestmentAsset,
        holdingQuantity: Quantity,
        costBasis: Money,
        currentTotalValue: Money,
        updatedAtEpochMs: Long,
        previousQuote: MarketQuote? = null,
        prevCloseOverride: UnitPrice? = null,
    ): BankWealthManualValuationResult {
        validateCommonInputs(asset, holdingQuantity, costBasis)
        require(currentTotalValue.currency == asset.currency) {
            "Total value currency (${currentTotalValue.currency}) must match asset currency (${asset.currency})"
        }
        require(currentTotalValue.isPositive) {
            "currentTotalValue must be strictly positive, got $currentTotalValue"
        }

        val profitAmount = currentTotalValue - costBasis
        val impliedPriceBd = currentTotalValue.toBigDecimal().divide(
            holdingQuantity.toBigDecimal(),
            UnitPrice.MAX_SCALE,
            RoundingMode.HALF_UP,
        )
        val impliedUnitPrice = UnitPrice.of(impliedPriceBd)
        require(impliedUnitPrice.isPositive) {
            "Implied NAV underflowed to zero for total value $currentTotalValue and quantity ${holdingQuantity.canonical}"
        }

        return buildManualResult(
            asset = asset,
            holdingQuantity = holdingQuantity,
            costBasis = costBasis,
            impliedMarketValue = currentTotalValue,
            impliedUnitPrice = impliedUnitPrice,
            profitAmount = profitAmount,
            updatedAtEpochMs = updatedAtEpochMs,
            previousQuote = previousQuote,
            prevCloseOverride = prevCloseOverride,
        )
    }

    private fun validateCommonInputs(
        asset: InvestmentAsset,
        holdingQuantity: Quantity,
        costBasis: Money,
    ) {
        require(asset.assetClass == AssetClass.BANK_WEALTH) {
            "BankWealthValuationHelper only supports BANK_WEALTH assets, got ${asset.assetClass}"
        }
        require(holdingQuantity.isPositive) {
            "Bank wealth holding quantity must be strictly positive to compute manual valuation, got ${holdingQuantity.canonical}"
        }
        require(costBasis.currency == asset.currency) {
            "costBasis currency (${costBasis.currency}) must match asset currency (${asset.currency})"
        }
        require(!costBasis.isNegative) {
            "costBasis must be non-negative, got $costBasis"
        }
    }

    private fun buildManualResult(
        asset: InvestmentAsset,
        holdingQuantity: Quantity,
        costBasis: Money,
        impliedMarketValue: Money,
        impliedUnitPrice: UnitPrice,
        profitAmount: Money,
        updatedAtEpochMs: Long,
        previousQuote: MarketQuote?,
        prevCloseOverride: UnitPrice?,
    ): BankWealthManualValuationResult {
        val resolvedPrevClose = when {
            prevCloseOverride != null -> {
                require(prevCloseOverride.isPositive) {
                    "prevCloseOverride must be strictly positive, got ${prevCloseOverride.canonical}"
                }
                prevCloseOverride
            }
            previousQuote != null && previousQuote.priceCurrency == asset.currency -> previousQuote.prevClose
            else -> null
        }

        val dailyChangePct = resolvedPrevClose?.let { prev ->
            if (prev.isPositive) {
                InvestmentDecimalMath.percentageOrNull(
                    numerator = impliedUnitPrice.toBigDecimal().subtract(prev.toBigDecimal()),
                    denominator = prev.toBigDecimal(),
                )
            } else {
                null
            }
        }

        val profitRatePct = if (costBasis.isPositive) {
            InvestmentDecimalMath.percentageOrNull(
                numerator = profitAmount.toBigDecimal(),
                denominator = costBasis.toBigDecimal(),
            )
        } else {
            null
        }

        val quote = MarketQuote(
            assetId = asset.id,
            latestPrice = impliedUnitPrice,
            prevClose = resolvedPrevClose,
            dailyChangePctDecimal = dailyChangePct,
            priceCurrency = asset.currency,
            sourceProvider = MANUAL_OVERRIDE_PROVIDER_ID,
            isManualOverride = true,
            fetchedAt = updatedAtEpochMs,
        )

        return BankWealthManualValuationResult(
            assetId = asset.id,
            holdingQuantity = holdingQuantity,
            costBasis = costBasis,
            impliedMarketValue = impliedMarketValue,
            impliedUnitPrice = impliedUnitPrice,
            profitAmount = profitAmount,
            profitRatePctDecimal = profitRatePct,
            quote = quote,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }
}
