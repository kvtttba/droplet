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
import io.github.kvtttba.droplet.core.model.UnitPrice
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioSummaryAggregatorTest {

    private val rates = ExchangeRateTable.of(
        ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.20", capturedAt = 1_000L),
        ExchangeRateSnapshot.of(Currency.HKD, Currency.CNY, "0.90", capturedAt = 1_000L),
    )

    @Test
    fun aggregate_respectsIncludeInNetWorthAvoidsDoubleCountingAndReconcilesThreeCurrencies() {
        val seq = AtomicInteger(0)
        val engine = LedgerEngine(
            baseCurrency = Currency.CNY,
            rateProvider = rates,
            idGenerator = IdGenerator { "id-${seq.incrementAndGet()}" },
        )

        val debitCny = Account("acc:debit:cny", "储蓄卡", AccountType.ASSET, AccountSubType.DEBIT_CARD, currency = Currency.CNY, includeInNetWorth = true)
        val medicareExcluded = Account("acc:medicare", "医保卡", AccountType.ASSET, AccountSubType.STORED_VALUE, currency = Currency.CNY, includeInNetWorth = false)
        val creditCny = Account("acc:credit:cny", "信用卡", AccountType.LIABILITY, AccountSubType.CREDIT_CARD, currency = Currency.CNY, includeInNetWorth = true)
        val loanCny = Account("acc:loan:cny", "房贷", AccountType.LIABILITY, AccountSubType.LOAN, currency = Currency.CNY, includeInNetWorth = true)
        val reimburseCny = Account("acc:reimburse", "待报销", AccountType.ASSET, AccountSubType.REIMBURSEMENT, currency = Currency.CNY, includeInNetWorth = true, isSystem = true)
        val usdCash = Account("acc:cash:usd", "美股现金", AccountType.ASSET, AccountSubType.INVEST_CASH, currency = Currency.USD, includeInNetWorth = true)
        val aaplHold = Account("acc:hold:aapl", "AAPL持仓", AccountType.ASSET, AccountSubType.INVEST_HOLDING, currency = Currency.USD, includeInNetWorth = true)
        val hkHold = Account("acc:hold:hk", "腾讯持仓", AccountType.ASSET, AccountSubType.INVEST_HOLDING, currency = Currency.HKD, includeInNetWorth = true)
        val excludedHold = Account("acc:hold:esop", "受限股", AccountType.ASSET, AccountSubType.INVEST_HOLDING, currency = Currency.USD, includeInNetWorth = false)
        val expenseCat = Account("exp:food", "餐饮", AccountType.EXPENSE, AccountSubType.CATEGORY, currency = Currency.CNY, includeInNetWorth = false)

        val allAccounts = SystemAccounts.allSystemEquityAccounts() + listOf(
            debitCny, medicareExcluded, creditCny, loanCny, reimburseCny,
            usdCash, aaplHold, hkHold, excludedHold, expenseCat,
        )
        val accountsById = allAccounts.associateBy { it.id }

        val aaplAsset = InvestmentAsset("asset:aapl", aaplHold.id, AssetClass.US_STOCK, "AAPL", "Apple", Currency.USD)
        val hkAsset = InvestmentAsset("asset:00700", hkHold.id, AssetClass.HK_STOCK, "00700", "腾讯控股", Currency.HKD)
        val esopAsset = InvestmentAsset("asset:esop", excludedHold.id, AssetClass.US_STOCK, "ESOP", "受限股票", Currency.USD)

        val buyAapl = engine.buildInvestmentBuy(
            meta = TransactionMeta(occurredAt = 1_000L),
            holdingAccountId = aaplHold.id,
            cashAccountId = usdCash.id,
            investmentAssetId = aaplAsset.id,
            quantity = Quantity.of("10"),
            unitPrice = UnitPrice.of("200"), // book cost $2,000
            currency = Currency.USD,
            accounts = accountsById,
        )
        val buyHk = engine.buildInvestmentBuy(
            meta = TransactionMeta(occurredAt = 2_000L),
            holdingAccountId = hkHold.id,
            cashAccountId = "acc:cash:hk", // not validated against map below
            investmentAssetId = hkAsset.id,
            quantity = Quantity.of("100"),
            unitPrice = UnitPrice.of("400"), // book cost HK$40,000
            currency = Currency.HKD,
        )
        val buyEsop = engine.buildInvestmentBuy(
            meta = TransactionMeta(occurredAt = 3_000L),
            holdingAccountId = excludedHold.id,
            cashAccountId = usdCash.id,
            investmentAssetId = esopAsset.id,
            quantity = Quantity.of("10"),
            unitPrice = UnitPrice.of("100"), // book cost $1,000
            currency = Currency.USD,
            accounts = accountsById,
        )

        val quotes = mapOf(
            aaplAsset.id to MarketQuote(aaplAsset.id, UnitPrice.of("250"), UnitPrice.of("240"), priceCurrency = Currency.USD, sourceProvider = "em", fetchedAt = 5_000L),
            hkAsset.id to MarketQuote(hkAsset.id, UnitPrice.of("420"), UnitPrice.of("400"), priceCurrency = Currency.HKD, sourceProvider = "em", fetchedAt = 5_000L),
            esopAsset.id to MarketQuote(esopAsset.id, UnitPrice.of("150"), UnitPrice.of("140"), priceCurrency = Currency.USD, sourceProvider = "em", fetchedAt = 5_000L),
        )

        val positions = PortfolioPositionEngine.evaluatePositions(
            assets = listOf(aaplAsset, hkAsset, esopAsset),
            transactions = listOf(buyAapl, buyHk, buyEsop),
            quotesByAssetId = quotes,
            baseCurrency = Currency.CNY,
            exchangeRateProvider = rates,
            accounts = accountsById,
        )

        // Intentionally provide raw book balances for holding accounts too, verifying aggregator
        // replaces holding book balance with mark-to-market value and never double-counts!
        val rawBalances = mapOf(
            debitCny.id to Money.fromDecimalString("50000.00", Currency.CNY),
            medicareExcluded.id to Money.fromDecimalString("8000.00", Currency.CNY),
            creditCny.id to Money.fromDecimalString("-3000.00", Currency.CNY),
            loanCny.id to Money.fromDecimalString("-20000.00", Currency.CNY),
            reimburseCny.id to Money.fromDecimalString("500.00", Currency.CNY),
            usdCash.id to Money.fromDecimalString("1000.00", Currency.USD),
            aaplHold.id to Money.fromDecimalString("2000.00", Currency.USD), // raw book cost -> replaced by $2,500 MV!
            hkHold.id to Money.fromDecimalString("40000.00", Currency.HKD), // raw book cost -> replaced by HK$42,000 MV!
            excludedHold.id to Money.fromDecimalString("1000.00", Currency.USD), // excluded from net worth!
        )

        val summary = PortfolioSummaryAggregator.aggregate(
            accounts = allAccounts,
            accountBalancesById = rawBalances,
            positions = positions,
            baseCurrency = Currency.CNY,
            exchangeRateProvider = rates,
        )

        // Included assets (CNY base):
        // - CNY: debit ¥50,000 + reimburse ¥500 = ¥50,500
        // - USD: usdCash $1,000 (¥7,200) + aaplHold MV $2,500 (¥18,000) = $3,500 (¥25,200)
        // - HKD: hkHold MV HK$42,000 (¥37,800)
        // Total included assets = 50,500 + 25,200 + 37,800 = ¥113,500.00
        assertEquals(Money.fromDecimalString("113500.00", Currency.CNY), summary.totalAssetsBase)

        // Included liabilities (CNY base):
        // - credit ¥3,000 + loan ¥20,000 = ¥23,000.00
        assertEquals(Money.fromDecimalString("23000.00", Currency.CNY), summary.totalLiabilitiesBase)

        // Net worth = 113,500 - 23,000 = ¥90,500.00
        assertEquals(Money.fromDecimalString("90500.00", Currency.CNY), summary.netWorthBase)

        // Excluded accounts: medicare ¥8,000 + esop MV $1,500 (¥10,800) = ¥18,800.00
        assertEquals(Money.fromDecimalString("18800.00", Currency.CNY), summary.excludedNetWorthBase)
        assertEquals(2, summary.excludedAccountValuations.size)
        assertEquals(2, summary.includedPositions.size)
        assertEquals(3, summary.positions.size)

        // Currency distributions reconcile EXACTLY to headline totals
        val cnyDist = summary.currencyDistributions.first { it.currency == Currency.CNY }
        val usdDist = summary.currencyDistributions.first { it.currency == Currency.USD }
        val hkdDist = summary.currencyDistributions.first { it.currency == Currency.HKD }

        assertEquals(Money.fromDecimalString("50500.00", Currency.CNY), cnyDist.assetsOriginal)
        assertEquals(Money.fromDecimalString("23000.00", Currency.CNY), cnyDist.liabilitiesOriginal)
        assertEquals(Money.fromDecimalString("27500.00", Currency.CNY), cnyDist.netBase)

        assertEquals(Money.fromDecimalString("3500.00", Currency.USD), usdDist.assetsOriginal)
        assertEquals(Money.fromDecimalString("25200.00", Currency.CNY), usdDist.assetsBase)

        assertEquals(Money.fromDecimalString("42000.00", Currency.HKD), hkdDist.assetsOriginal)
        assertEquals(Money.fromDecimalString("37800.00", Currency.CNY), hkdDist.assetsBase)

        assertEquals(
            summary.totalAssetsBase,
            Money.sumOf(summary.currencyDistributions.map { it.assetsBase }, Currency.CNY),
        )
        assertEquals(
            summary.totalLiabilitiesBase,
            Money.sumOf(summary.currencyDistributions.map { it.liabilitiesBase }, Currency.CNY),
        )
        assertEquals(
            summary.netWorthBase,
            Money.sumOf(summary.currencyDistributions.map { it.netBase }, Currency.CNY),
        )

        // Investment portfolio totals (ONLY included positions: AAPL + 00700; ESOP excluded!)
        // MV: ¥18,000 + ¥37,800 = ¥55,800
        // Cost: $2,000 (¥14,400) + HK$40,000 (¥36,000) = ¥50,400
        // Unrealized P&L: +¥5,400
        // Daily change: AAPL +$100 (¥720) + 00700 +HK$2,000 (¥1,800) = +¥2,520
        val inv = summary.investmentTotals
        assertEquals(2, inv.positionCount)
        assertEquals(2, inv.activePositionCount)
        assertEquals(Money.fromDecimalString("55800.00", Currency.CNY), inv.marketValueBase)
        assertEquals(Money.fromDecimalString("50400.00", Currency.CNY), inv.costBasisBase)
        assertEquals(Money.fromDecimalString("5400.00", Currency.CNY), inv.unrealizedPnLBase)
        assertEquals(Money.fromDecimalString("2520.00", Currency.CNY), inv.dailyChangeBase)
        assertTrue(inv.unrealizedReturnPctDecimal == "10.7143")
    }

    @Test
    fun aggregate_handlesCreditOverpaymentAndBaseCurrencySwitchToUsd() {
        val credit = Account("acc:cc", "信用卡", AccountType.LIABILITY, AccountSubType.CREDIT_CARD, currency = Currency.CNY)
        val debit = Account("acc:debit", "储蓄卡", AccountType.ASSET, AccountSubType.DEBIT_CARD, currency = Currency.CNY)
        val usdCash = Account("acc:usd", "美元户", AccountType.ASSET, AccountSubType.INVEST_CASH, currency = Currency.USD)

        // Credit card has +¥720 overpayment -> counts as asset, zero liability!
        val summaryUsd = PortfolioSummaryAggregator.aggregate(
            accounts = listOf(credit, debit, usdCash),
            accountBalancesById = mapOf(
                credit.id to Money.fromDecimalString("720.00", Currency.CNY),
                debit.id to Money.fromDecimalString("6480.00", Currency.CNY),
                usdCash.id to Money.fromDecimalString("500.00", Currency.USD),
            ),
            positions = emptyList(),
            baseCurrency = Currency.USD,
            exchangeRateProvider = rates,
        )

        // CNY assets: 720 + 6480 = ¥7,200 -> $1,000 USD; plus $500 USD cash = $1,500 USD
        assertEquals(Money.zero(Currency.USD), summaryUsd.totalLiabilitiesBase)
        assertEquals(Money.fromDecimalString("1500.00", Currency.USD), summaryUsd.totalAssetsBase)
        assertEquals(Money.fromDecimalString("1500.00", Currency.USD), summaryUsd.netWorthBase)
    }
}
