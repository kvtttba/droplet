package io.github.kvtttba.droplet.core.domain.investment

import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.MarketQuote
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.UnitPrice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BankWealthValuationHelperTest {

    private val wealthAsset = InvestmentAsset(
        id = "bw:cmb-01",
        accountId = "acc:hold:bw1",
        assetClass = AssetClass.BANK_WEALTH,
        symbol = "CMB-W01",
        name = "招银朝朝盈2号",
        currency = Currency.CNY,
    )

    @Test
    fun resolveValuationMode_distinguishesPublicManualAndCostFallback() {
        assertEquals(
            BankWealthValuationMode.COST_BASIS_FALLBACK,
            BankWealthValuationHelper.resolveValuationMode(wealthAsset, null),
        )
        val publicQuote = MarketQuote(
            assetId = wealthAsset.id,
            latestPrice = UnitPrice.of("1.0450"),
            priceCurrency = Currency.CNY,
            sourceProvider = "eastmoney_fund",
            isManualOverride = false,
            fetchedAt = 1_000L,
        )
        assertEquals(
            BankWealthValuationMode.PUBLIC_NAV,
            BankWealthValuationHelper.resolveValuationMode(wealthAsset, publicQuote),
        )
        val manualQuote = publicQuote.copy(
            sourceProvider = BankWealthValuationHelper.MANUAL_OVERRIDE_PROVIDER_ID,
            isManualOverride = true,
        )
        assertEquals(
            BankWealthValuationMode.MANUAL_NAV_OVERRIDE,
            BankWealthValuationHelper.resolveValuationMode(wealthAsset, manualQuote),
        )
    }

    @Test
    fun deriveQuoteFromProfitAmount_supportsGainLossBreakEvenAndNonTerminatingNav() {
        // 1. Positive gain: 50,000 units, cost ¥50,000, profit +¥625 -> MV ¥50,625, NAV 1.0125 (+1.25%)
        val gainResult = BankWealthValuationHelper.deriveQuoteFromProfitAmount(
            asset = wealthAsset,
            holdingQuantity = Quantity.of("50000"),
            costBasis = Money.fromDecimalString("50000.00", Currency.CNY),
            latestProfitAmount = Money.fromDecimalString("625.00", Currency.CNY),
            updatedAtEpochMs = 1_764_240_000_000L,
            prevCloseOverride = UnitPrice.of("1.0100"),
        )
        assertEquals(Money.fromDecimalString("50625.00", Currency.CNY), gainResult.impliedMarketValue)
        assertEquals(UnitPrice.of("1.0125"), gainResult.impliedUnitPrice)
        assertEquals("1.25", gainResult.profitRatePctDecimal)
        assertTrue(gainResult.quote.isManualOverride)
        assertEquals(BankWealthValuationHelper.MANUAL_OVERRIDE_PROVIDER_ID, gainResult.quote.sourceProvider)
        assertEquals(1_764_240_000_000L, gainResult.quote.fetchedAt)
        assertEquals(UnitPrice.of("1.01"), gainResult.quote.prevClose)
        // Daily change from prevClose 1.01 -> 1.0125 = +0.2475%
        assertEquals("0.2475", gainResult.quote.dailyChangePctDecimal)

        // 2. Negative profit (破净): 50,000 units, cost ¥50,000, profit -¥350 -> MV ¥49,650, NAV 0.993 (-0.7%)
        val lossResult = BankWealthValuationHelper.deriveQuoteFromProfitAmount(
            asset = wealthAsset,
            holdingQuantity = Quantity.of("50000"),
            costBasis = Money.fromDecimalString("50000.00", Currency.CNY),
            latestProfitAmount = Money.fromDecimalString("-350.00", Currency.CNY),
            updatedAtEpochMs = 1_764_250_000_000L,
        )
        assertEquals(Money.fromDecimalString("49650.00", Currency.CNY), lossResult.impliedMarketValue)
        assertEquals(UnitPrice.of("0.993"), lossResult.impliedUnitPrice)
        assertEquals("-0.7", lossResult.profitRatePctDecimal)

        // 3. Non-terminating NAV (3000 shares, cost ¥10,000, profit +¥100 -> NAV 3.36666667) round-trips cleanly!
        val nonTerminating = BankWealthValuationHelper.deriveQuoteFromProfitAmount(
            asset = wealthAsset,
            holdingQuantity = Quantity.of("3000"),
            costBasis = Money.fromDecimalString("10000.00", Currency.CNY),
            latestProfitAmount = Money.fromDecimalString("100.00", Currency.CNY),
            updatedAtEpochMs = 1_764_260_000_000L,
        )
        assertEquals(UnitPrice.of("3.36666667"), nonTerminating.impliedUnitPrice)
        val roundTrippedMv = Money.fromBigDecimal(
            Quantity.of("3000").toBigDecimal().multiply(nonTerminating.impliedUnitPrice.toBigDecimal()),
            Currency.CNY,
        )
        assertEquals(Money.fromDecimalString("10100.00", Currency.CNY), roundTrippedMv)
    }

    @Test
    fun deriveQuoteFromUnitPriceAndTotalValue_computeConsistentProfitsAndRejectInvalidInputs() {
        val byNav = BankWealthValuationHelper.deriveQuoteFromUnitPrice(
            asset = wealthAsset,
            holdingQuantity = Quantity.of("20000"),
            costBasis = Money.fromDecimalString("20400.00", Currency.CNY),
            manualUnitPrice = UnitPrice.of("1.0500"),
            updatedAtEpochMs = 10_000L,
        )
        assertEquals(Money.fromDecimalString("21000.00", Currency.CNY), byNav.impliedMarketValue)
        assertEquals(Money.fromDecimalString("600.00", Currency.CNY), byNav.profitAmount)

        val byTotal = BankWealthValuationHelper.deriveQuoteFromTotalValue(
            asset = wealthAsset,
            holdingQuantity = Quantity.of("20000"),
            costBasis = Money.fromDecimalString("20400.00", Currency.CNY),
            currentTotalValue = Money.fromDecimalString("21000.00", Currency.CNY),
            updatedAtEpochMs = 20_000L,
        )
        assertEquals(UnitPrice.of("1.05"), byTotal.impliedUnitPrice)
        assertEquals(Money.fromDecimalString("600.00", Currency.CNY), byTotal.profitAmount)

        // Reject loss wiping out >= 100% of cost basis
        assertThrows(IllegalArgumentException::class.java) {
            BankWealthValuationHelper.deriveQuoteFromProfitAmount(
                asset = wealthAsset,
                holdingQuantity = Quantity.of("1000"),
                costBasis = Money.fromDecimalString("1000.00", Currency.CNY),
                latestProfitAmount = Money.fromDecimalString("-1000.00", Currency.CNY),
                updatedAtEpochMs = 30_000L,
            )
        }

        // Reject non-BANK_WEALTH asset class
        val stockAsset = wealthAsset.copy(assetClass = AssetClass.CN_STOCK)
        assertThrows(IllegalArgumentException::class.java) {
            BankWealthValuationHelper.resolveValuationMode(stockAsset, null)
        }
    }
}
