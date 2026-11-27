package io.github.kvtttba.droplet.core.domain.investment

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.IdGenerator
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.MarketQuote
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.model.UnitPrice
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PortfolioPositionEngineTest {

    private lateinit var rates: ExchangeRateTable
    private lateinit var engine: LedgerEngine
    private lateinit var accounts: Map<String, Account>

    private val aapl = InvestmentAsset(
        id = "asset:aapl",
        accountId = "acc:hold:aapl",
        assetClass = AssetClass.US_STOCK,
        symbol = "AAPL",
        name = "Apple Inc.",
        currency = Currency.USD,
    )

    private val btc = InvestmentAsset(
        id = "asset:btc",
        accountId = "acc:hold:btc",
        assetClass = AssetClass.CRYPTO,
        symbol = "BTC",
        name = "Bitcoin",
        currency = Currency.USD,
    )

    private val goldCny = InvestmentAsset(
        id = "asset:xau",
        accountId = "acc:hold:xau",
        assetClass = AssetClass.GOLD,
        symbol = "XAU",
        name = "国际黄金",
        currency = Currency.CNY,
    )

    private val cnFund = InvestmentAsset(
        id = "asset:fund",
        accountId = "acc:hold:fund",
        assetClass = AssetClass.CN_FUND,
        symbol = "110011",
        name = "易方达中小盘",
        currency = Currency.CNY,
    )

    @Before
    fun setUp() {
        val seq = AtomicInteger(0)
        rates = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.20", capturedAt = 1_000L),
            ExchangeRateSnapshot.of(Currency.HKD, Currency.CNY, "0.90", capturedAt = 1_000L),
        )
        engine = LedgerEngine(
            baseCurrency = Currency.CNY,
            rateProvider = rates,
            idGenerator = IdGenerator { "id-${seq.incrementAndGet()}" },
        )
        accounts = (
            SystemAccounts.allSystemEquityAccounts() + listOf(
                Account("acc:cash:usd", "美元资金户", AccountType.ASSET, AccountSubType.INVEST_CASH, currency = Currency.USD),
                Account("acc:cash:cny", "人民币储蓄卡", AccountType.ASSET, AccountSubType.DEBIT_CARD, currency = Currency.CNY),
                Account("acc:hold:aapl", "AAPL 持仓", AccountType.ASSET, AccountSubType.INVEST_HOLDING, currency = Currency.USD),
                Account("acc:hold:btc", "BTC 持仓", AccountType.ASSET, AccountSubType.INVEST_HOLDING, currency = Currency.USD),
                Account("acc:hold:xau", "黄金持仓", AccountType.ASSET, AccountSubType.INVEST_HOLDING, currency = Currency.CNY),
                Account("acc:hold:fund", "基金持仓", AccountType.ASSET, AccountSubType.INVEST_HOLDING, currency = Currency.CNY),
                Account("exp:fee", "利息手续费", AccountType.EXPENSE, AccountSubType.CATEGORY, currency = Currency.CNY),
                Account("exp:loss", "投资亏损", AccountType.EXPENSE, AccountSubType.CATEGORY, currency = Currency.CNY),
                Account("inc:gain", "投资收益", AccountType.INCOME, AccountSubType.CATEGORY, currency = Currency.CNY),
                Account("inc:div", "投资分红", AccountType.INCOME, AccountSubType.CATEGORY, currency = Currency.CNY),
            )
            ).associateBy { it.id }
    }

    @Test
    fun buyDcaPartialSellDividendAndMarketValuation_calculatesExactWeightedAverageMetrics() {
        // 1. Buy 10 AAPL @ $200 ($2,000) + $2 fee
        val buy1 = engine.buildInvestmentBuy(
            meta = TransactionMeta(occurredAt = 1_000L),
            holdingAccountId = aapl.accountId,
            cashAccountId = "acc:cash:usd",
            investmentAssetId = aapl.id,
            quantity = Quantity.of("10"),
            unitPrice = UnitPrice.of("200"),
            currency = Currency.USD,
            fee = Money.fromDecimalString("2.00", Currency.USD),
            feeExpenseAccountId = "exp:fee",
            accounts = accounts,
        )

        // 2. DCA buy 10 AAPL @ $240 ($2,400) + $3 fee -> 20 shares, cost $4,400, avg $220
        val buy2 = engine.buildInvestmentBuy(
            meta = TransactionMeta(occurredAt = 2_000L),
            holdingAccountId = aapl.accountId,
            cashAccountId = "acc:cash:usd",
            investmentAssetId = aapl.id,
            quantity = Quantity.of("10"),
            unitPrice = UnitPrice.of("240"),
            currency = Currency.USD,
            fee = Money.fromDecimalString("3.00", Currency.USD),
            feeExpenseAccountId = "exp:fee",
            accounts = accounts,
        )

        // 3. Preview partial sell of 5 shares @ $260 -> costBasis = $1,100, proceeds = $1,300, gain = +$200
        val preview = PortfolioPositionEngine.previewSell(
            asset = aapl,
            transactions = listOf(buy2, buy1), // intentionally unsorted to test chronological sorting
            sellQuantity = Quantity.of("5"),
            unitPrice = UnitPrice.of("260"),
            accounts = accounts,
        )
        assertEquals(Money.fromDecimalString("1100.00", Currency.USD), preview.costBasis)
        assertEquals(Money.fromDecimalString("1300.00", Currency.USD), preview.grossProceeds)
        assertEquals(Money.fromDecimalString("200.00", Currency.USD), preview.estimatedRealizedPnL)
        assertEquals(Quantity.of("15"), preview.remainingQuantityAfterSell)
        assertEquals(Money.fromDecimalString("3300.00", Currency.USD), preview.remainingCostBasisAfterSell)
        assertFalse(preview.isFullLiquidation)

        val sell1 = engine.buildInvestmentSell(
            meta = TransactionMeta(occurredAt = 3_000L),
            holdingAccountId = aapl.accountId,
            cashAccountId = "acc:cash:usd",
            investmentAssetId = aapl.id,
            quantity = Quantity.of("5"),
            unitPrice = UnitPrice.of("260"),
            currency = Currency.USD,
            fee = Money.fromDecimalString("1.50", Currency.USD),
            feeExpenseAccountId = "exp:fee",
            costBasis = preview.costBasis,
            realizedPnLAccountId = "inc:gain",
            accounts = accounts,
        )

        // 4. Cash dividend $30 with $3 withholding tax
        val dividend = engine.buildInvestmentDividend(
            meta = TransactionMeta(occurredAt = 4_000L),
            cashAccountId = "acc:cash:usd",
            dividendIncomeAccountId = "inc:div",
            investmentAssetId = aapl.id,
            amount = Money.fromDecimalString("30.00", Currency.USD),
            taxOrFee = Money.fromDecimalString("3.00", Currency.USD),
            taxExpenseAccountId = "exp:fee",
            accounts = accounts,
        )

        // 5. Draft & Void transactions must be ignored
        val voidBuy = engine.buildInvestmentBuy(
            meta = TransactionMeta(occurredAt = 5_000L, status = TransactionStatus.VOID),
            holdingAccountId = aapl.accountId,
            cashAccountId = "acc:cash:usd",
            investmentAssetId = aapl.id,
            quantity = Quantity.of("100"),
            unitPrice = UnitPrice.of("100"),
            currency = Currency.USD,
            accounts = accounts,
        )
        val draftDiv = engine.buildInvestmentDividend(
            meta = TransactionMeta(occurredAt = 5_100L, status = TransactionStatus.DRAFT),
            cashAccountId = "acc:cash:usd",
            dividendIncomeAccountId = "inc:div",
            investmentAssetId = aapl.id,
            amount = Money.fromDecimalString("999.00", Currency.USD),
            accounts = accounts,
        )

        val quote = MarketQuote(
            assetId = aapl.id,
            latestPrice = UnitPrice.of("250"),
            prevClose = UnitPrice.of("240"),
            priceCurrency = Currency.USD,
            sourceProvider = "eastmoney",
            fetchedAt = 6_000L,
        )

        val valuation = PortfolioPositionEngine.evaluatePosition(
            asset = aapl,
            transactions = listOf(dividend, voidBuy, sell1, buy2, draftDiv, buy1),
            marketQuote = quote,
            baseCurrency = Currency.CNY,
            exchangeRateProvider = rates,
            accounts = accounts,
        )

        // Remaining 15 shares @ avg cost $220 -> remainingCost = $3,300
        assertEquals(Quantity.of("15"), valuation.holdingQuantity)
        assertEquals(UnitPrice.of("220"), valuation.averageCostPrice)
        assertEquals(Money.fromDecimalString("3300.00", Currency.USD), valuation.remainingCostBasis)
        assertEquals(PositionValuationSource.MARKET_QUOTE, valuation.valuationSource)

        // Market value: 15 * $250 = $3,750 -> CNY = 3750 * 7.20 = ¥27,000
        assertEquals(Money.fromDecimalString("3750.00", Currency.USD), valuation.marketValueOriginal)
        assertEquals(Money.fromDecimalString("27000.00", Currency.CNY), valuation.marketValueBase)

        // Unrealized P&L: $3,750 - $3,300 = +$450 (+13.6364%)
        assertEquals(Money.fromDecimalString("450.00", Currency.USD), valuation.unrealizedPnLOriginal)
        assertEquals(Money.fromDecimalString("3240.00", Currency.CNY), valuation.unrealizedPnLBase)
        assertEquals("13.6364", valuation.unrealizedReturnPctDecimal)

        // Daily change: 15 * ($250 - $240) = +$150 (+4.1667%), CNY = +¥1,080
        assertEquals(Money.fromDecimalString("150.00", Currency.USD), valuation.dailyChangeOriginal)
        assertEquals(Money.fromDecimalString("1080.00", Currency.CNY), valuation.dailyChangeBase)
        assertEquals("4.1667", valuation.dailyChangePctDecimal)

        // Cumulative realized P&L (+$200), dividends (+$30), fees ($2 + $3 + $1.50 + $3 tax = $9.50)
        assertEquals(Money.fromDecimalString("200.00", Currency.USD), valuation.realizedPnLOriginal)
        assertEquals(Money.fromDecimalString("30.00", Currency.USD), valuation.totalDividendsOriginal)
        assertEquals(Money.fromDecimalString("9.50", Currency.USD), valuation.totalFeesOriginal)

        // Gross total return: 450 + 200 + 30 = $680; Net total return: 680 - 9.50 = $670.50
        assertEquals(Money.fromDecimalString("680.00", Currency.USD), valuation.grossTotalReturnOriginal)
        assertEquals(Money.fromDecimalString("670.50", Currency.USD), valuation.totalReturnOriginal)
        assertEquals(Money.fromDecimalString("4827.60", Currency.CNY), valuation.totalReturnBase)
    }

    @Test
    fun fullLiquidationAndRebuy_resetsCostBasisCleanlyWithZeroDustAndPreservesHistory() {
        // Buy 3 units for ¥100 (non-terminating avg cost 33.33333333...)
        val buy1 = engine.buildInvestmentBuy(
            meta = TransactionMeta(occurredAt = 1_000L),
            holdingAccountId = cnFund.accountId,
            cashAccountId = "acc:cash:cny",
            investmentAssetId = cnFund.id,
            quantity = Quantity.of("3"),
            unitPrice = UnitPrice.of("33.33333333"),
            currency = Currency.CNY,
            totalAmount = Money.fromDecimalString("100.00", Currency.CNY),
            accounts = accounts,
        )

        // Sell 1 unit @ ¥40 (carried cost = 33.3333, gain = 6.6667)
        val preview1 = PortfolioPositionEngine.previewSell(cnFund, listOf(buy1), Quantity.of("1"), UnitPrice.of("40"), accounts = accounts)
        assertEquals(Money.fromDecimalString("33.3333", Currency.CNY), preview1.costBasis)
        val sell1 = engine.buildInvestmentSell(
            meta = TransactionMeta(occurredAt = 2_000L),
            holdingAccountId = cnFund.accountId,
            cashAccountId = "acc:cash:cny",
            investmentAssetId = cnFund.id,
            quantity = Quantity.of("1"),
            unitPrice = UnitPrice.of("40"),
            currency = Currency.CNY,
            costBasis = preview1.costBasis,
            realizedPnLAccountId = "inc:gain",
            accounts = accounts,
        )

        // Sell 1 more unit @ ¥30 (carried cost = 33.3334, loss = -3.3334)
        val preview2 = PortfolioPositionEngine.previewSell(cnFund, listOf(buy1, sell1), Quantity.of("1"), UnitPrice.of("30"), accounts = accounts)
        assertEquals(Money.fromDecimalString("33.3334", Currency.CNY), preview2.costBasis)
        val sell2 = engine.buildInvestmentSell(
            meta = TransactionMeta(occurredAt = 3_000L),
            holdingAccountId = cnFund.accountId,
            cashAccountId = "acc:cash:cny",
            investmentAssetId = cnFund.id,
            quantity = Quantity.of("1"),
            unitPrice = UnitPrice.of("30"),
            currency = Currency.CNY,
            costBasis = preview2.costBasis,
            realizedPnLAccountId = "exp:loss",
            accounts = accounts,
        )

        // Sell final 1 unit @ ¥50 -> full liquidation carries exact remaining 33.3333, leaving 0 dust!
        val preview3 = PortfolioPositionEngine.previewSell(cnFund, listOf(buy1, sell1, sell2), Quantity.of("1"), UnitPrice.of("50"), accounts = accounts)
        assertTrue(preview3.isFullLiquidation)
        assertEquals(Money.fromDecimalString("33.3333", Currency.CNY), preview3.costBasis)
        assertEquals(Money.zero(Currency.CNY), preview3.remainingCostBasisAfterSell)
        assertEquals(Quantity.ZERO, preview3.remainingQuantityAfterSell)

        // Simple-mode sell (costBasis = null) should ALSO replay identically!
        val sell3Simple = engine.buildInvestmentSell(
            meta = TransactionMeta(occurredAt = 4_000L),
            holdingAccountId = cnFund.accountId,
            cashAccountId = "acc:cash:cny",
            investmentAssetId = cnFund.id,
            quantity = Quantity.of("1"),
            unitPrice = UnitPrice.of("50"),
            currency = Currency.CNY,
            accounts = accounts,
        )

        val cleared = PortfolioPositionEngine.replayLedgerState(
            asset = cnFund,
            transactions = listOf(buy1, sell1, sell2, sell3Simple),
            accounts = accounts,
        )
        assertEquals(Quantity.ZERO, cleared.holdingQuantity)
        assertEquals(Money.zero(Currency.CNY), cleared.remainingCostBasis)
        assertEquals(UnitPrice.ZERO, cleared.averageCostPrice)
        // Total sell proceeds = 40 + 30 + 50 = 120; total cost = 100 -> exact realized P&L = +¥20.0000!
        assertEquals(Money.fromDecimalString("20.00", Currency.CNY), cleared.realizedPnL)

        // Re-buy 5 units @ ¥60 -> new cost basis is ¥300, avg cost is ¥60, realized P&L stays +¥20
        val rebuy = engine.buildInvestmentBuy(
            meta = TransactionMeta(occurredAt = 5_000L),
            holdingAccountId = cnFund.accountId,
            cashAccountId = "acc:cash:cny",
            investmentAssetId = cnFund.id,
            quantity = Quantity.of("5"),
            unitPrice = UnitPrice.of("60"),
            currency = Currency.CNY,
            accounts = accounts,
        )
        val afterRebuy = PortfolioPositionEngine.replayLedgerState(
            asset = cnFund,
            transactions = listOf(buy1, sell1, sell2, sell3Simple, rebuy),
            accounts = accounts,
        )
        assertEquals(Quantity.of("5"), afterRebuy.holdingQuantity)
        assertEquals(Money.fromDecimalString("300.00", Currency.CNY), afterRebuy.remainingCostBasis)
        assertEquals(UnitPrice.of("60"), afterRebuy.averageCostPrice)
        assertEquals(Money.fromDecimalString("20.00", Currency.CNY), afterRebuy.realizedPnL)
    }

    @Test
    fun oversell_throwsOversellPositionExceptionOnEmptyAndExcessSell() {
        // Selling from empty position
        val emptyErr = assertThrows(OversellPositionException::class.java) {
            PortfolioPositionEngine.previewSell(
                asset = aapl,
                transactions = emptyList(),
                sellQuantity = Quantity.of("1"),
                unitPrice = UnitPrice.of("200"),
                accounts = accounts,
            )
        }
        assertEquals(Quantity.ZERO, emptyErr.availableQuantity)
        assertEquals(Quantity.of("1"), emptyErr.attemptedSellQuantity)

        // Buy 2 shares, then try to sell 2.00000001 shares
        val buy = engine.buildInvestmentBuy(
            meta = TransactionMeta(occurredAt = 1_000L),
            holdingAccountId = aapl.accountId,
            cashAccountId = "acc:cash:usd",
            investmentAssetId = aapl.id,
            quantity = Quantity.of("2"),
            unitPrice = UnitPrice.of("200"),
            currency = Currency.USD,
            accounts = accounts,
        )
        val excessSell = engine.buildInvestmentSell(
            meta = TransactionMeta(occurredAt = 2_000L),
            holdingAccountId = aapl.accountId,
            cashAccountId = "acc:cash:usd",
            investmentAssetId = aapl.id,
            quantity = Quantity.of("2.00000001"),
            unitPrice = UnitPrice.of("210"),
            currency = Currency.USD,
            accounts = accounts,
        )

        val replayErr = assertThrows(OversellPositionException::class.java) {
            PortfolioPositionEngine.replayLedgerState(
                asset = aapl,
                transactions = listOf(buy, excessSell),
                accounts = accounts,
            )
        }
        assertEquals(aapl.id, replayErr.assetId)
        assertEquals(Quantity.of("2"), replayErr.availableQuantity)
        assertEquals(Quantity.of("2.00000001"), replayErr.attemptedSellQuantity)
    }

    @Test
    fun crypto8DecimalPrecision_maintainsExactSatoshisAcrossBuyDcaPartialSellAndValuation() {
        // Buy 0.00123456 BTC @ $80,000 -> cost = $98.7648
        val buy1 = engine.buildInvestmentBuy(
            meta = TransactionMeta(occurredAt = 1_000L),
            holdingAccountId = btc.accountId,
            cashAccountId = "acc:cash:usd",
            investmentAssetId = btc.id,
            quantity = Quantity.of("0.00123456"),
            unitPrice = UnitPrice.of("80000"),
            currency = Currency.USD,
            accounts = accounts,
        )
        // DCA 0.00876544 BTC @ $90,000 -> cost = $788.8896; total qty = exact 0.01 BTC, total cost = $887.6544
        val buy2 = engine.buildInvestmentBuy(
            meta = TransactionMeta(occurredAt = 2_000L),
            holdingAccountId = btc.accountId,
            cashAccountId = "acc:cash:usd",
            investmentAssetId = btc.id,
            quantity = Quantity.of("0.00876544"),
            unitPrice = UnitPrice.of("90000"),
            currency = Currency.USD,
            accounts = accounts,
        )

        val preview = PortfolioPositionEngine.previewSell(
            asset = btc,
            transactions = listOf(buy1, buy2),
            sellQuantity = Quantity.of("0.00333333"),
            unitPrice = UnitPrice.of("100000"),
            accounts = accounts,
        )
        assertEquals(Quantity.of("0.00666667"), preview.remainingQuantityAfterSell)
        assertEquals(Money.fromDecimalString("333.3330", Currency.USD), preview.grossProceeds)
        // Carried cost = 887.6544 * 0.00333333 / 0.01 = 295.8845041152 -> 295.8845
        assertEquals(Money.fromDecimalString("295.8845", Currency.USD), preview.costBasis)

        val sell = engine.buildInvestmentSell(
            meta = TransactionMeta(occurredAt = 3_000L),
            holdingAccountId = btc.accountId,
            cashAccountId = "acc:cash:usd",
            investmentAssetId = btc.id,
            quantity = Quantity.of("0.00333333"),
            unitPrice = UnitPrice.of("100000"),
            currency = Currency.USD,
            costBasis = preview.costBasis,
            realizedPnLAccountId = "inc:gain",
            accounts = accounts,
        )

        val valuation = PortfolioPositionEngine.evaluatePosition(
            asset = btc,
            transactions = listOf(buy1, buy2, sell),
            marketQuote = MarketQuote(
                assetId = btc.id,
                latestPrice = UnitPrice.of("95000.12345678"),
                dailyChangePctDecimal = "5", // test fallback dailyChange via dailyChangePct when prevClose is null
                priceCurrency = Currency.USD,
                sourceProvider = "okx",
                fetchedAt = 4_000L,
            ),
            baseCurrency = Currency.CNY,
            exchangeRateProvider = rates,
            accounts = accounts,
        )

        assertEquals(Quantity.of("0.00666667"), valuation.holdingQuantity)
        assertEquals("0.00666667", valuation.holdingQuantity.canonical)
        assertEquals(Money.fromDecimalString("591.7699", Currency.USD), valuation.remainingCostBasis)
        // 0.00666667 * 95000.12345678 = 633.3344730464... -> 633.3345
        assertEquals(Money.fromDecimalString("633.3345", Currency.USD), valuation.marketValueOriginal)
        assertEquals("5", valuation.dailyChangePctDecimal)
        // Daily change back-computed from +5%: 633.3345 - (633.3345 / 1.05) = 633.3345 - 603.1757 = 30.1588
        assertEquals(Money.fromDecimalString("30.1588", Currency.USD), valuation.dailyChangeOriginal)
    }

    @Test
    fun crossCurrencyMarketQuote_convertsQuoteCurrencyToHoldingCurrencyAndBaseCurrency() {
        // Gold holding tracked in CNY: bought 2 oz @ ¥18,000 = ¥36,000
        val buyGold = engine.buildInvestmentBuy(
            meta = TransactionMeta(occurredAt = 1_000L),
            holdingAccountId = goldCny.accountId,
            cashAccountId = "acc:cash:cny",
            investmentAssetId = goldCny.id,
            quantity = Quantity.of("2"),
            unitPrice = UnitPrice.of("18000"),
            currency = Currency.CNY,
            accounts = accounts,
        )

        // Spot XAU quote arrives in USD: $2,700/oz (prevClose $2,650/oz)
        // USD -> CNY = 7.20 -> effective price = ¥19,440/oz, prevClose = ¥19,080/oz
        val quoteUsd = MarketQuote(
            assetId = goldCny.id,
            latestPrice = UnitPrice.of("2700"),
            prevClose = UnitPrice.of("2650"),
            priceCurrency = Currency.USD,
            sourceProvider = "spot_xau",
            fetchedAt = 2_000L,
        )

        // Evaluate with HKD as base currency (CNY -> HKD = 1 / 0.90 = 1.11111111)
        val valuation = PortfolioPositionEngine.evaluatePosition(
            asset = goldCny,
            transactions = listOf(buyGold),
            marketQuote = quoteUsd,
            baseCurrency = Currency.HKD,
            exchangeRateProvider = rates,
            accounts = accounts,
        )

        assertEquals("7.2", valuation.quoteToAssetRate?.rateDecimal)
        assertEquals(UnitPrice.of("19440"), valuation.effectiveUnitPrice)
        assertEquals(UnitPrice.of("19080"), valuation.effectivePrevClose)
        // 2 * ¥19,440 = ¥38,880; cost = ¥36,000 -> unrealized = +¥2,880 (+8%)
        assertEquals(Money.fromDecimalString("38880.00", Currency.CNY), valuation.marketValueOriginal)
        assertEquals(Money.fromDecimalString("2880.00", Currency.CNY), valuation.unrealizedPnLOriginal)
        assertEquals("8", valuation.unrealizedReturnPctDecimal)
        // Daily change = 2 * (19440 - 19080) = +¥720
        assertEquals(Money.fromDecimalString("720.00", Currency.CNY), valuation.dailyChangeOriginal)
        // Base currency HKD: ¥38,880 / 0.90 = HK$43,200.00 (rate = 1.11111111 -> 38880 * 1.11111111 = 43199.9999568 -> 43200.0000)
        assertEquals(Money.fromDecimalString("43200.00", Currency.HKD), valuation.marketValueBase)
    }

    @Test
    fun missingQuote_fallsBackToCostBasisAndEmptyPositionReturnsZero() {
        val buy = engine.buildInvestmentBuy(
            meta = TransactionMeta(occurredAt = 1_000L),
            holdingAccountId = cnFund.accountId,
            cashAccountId = "acc:cash:cny",
            investmentAssetId = cnFund.id,
            quantity = Quantity.of("1000"),
            unitPrice = UnitPrice.of("1.50"),
            currency = Currency.CNY,
            accounts = accounts,
        )

        val fallbackVal = PortfolioPositionEngine.evaluatePosition(
            asset = cnFund,
            transactions = listOf(buy),
            marketQuote = null,
            baseCurrency = Currency.CNY,
            exchangeRateProvider = rates,
            accounts = accounts,
        )
        assertEquals(PositionValuationSource.COST_BASIS_FALLBACK, fallbackVal.valuationSource)
        assertEquals(Money.fromDecimalString("1500.00", Currency.CNY), fallbackVal.marketValueOriginal)
        assertEquals(Money.zero(Currency.CNY), fallbackVal.unrealizedPnLOriginal)
        assertNull(fallbackVal.dailyChangeOriginal)

        val emptyVal = PortfolioPositionEngine.evaluatePosition(
            asset = aapl,
            transactions = emptyList(),
            marketQuote = null,
            baseCurrency = Currency.CNY,
            exchangeRateProvider = rates,
            accounts = accounts,
        )
        assertEquals(PositionValuationSource.EMPTY_POSITION, emptyVal.valuationSource)
        assertFalse(emptyVal.hasHolding)
        assertEquals(Money.zero(Currency.USD), emptyVal.marketValueOriginal)
        assertEquals(Money.zero(Currency.CNY), emptyVal.marketValueBase)
        assertNull(emptyVal.unrealizedReturnPctDecimal)
    }
}
