package io.github.kvtttba.droplet.core.database

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.repository.CreateInvestmentAssetRequest
import io.github.kvtttba.droplet.core.database.repository.FixedDepositInterestPayoutRequest
import io.github.kvtttba.droplet.core.database.repository.FixedDepositSettlementRequest
import io.github.kvtttba.droplet.core.database.repository.InvestmentBuyRequest
import io.github.kvtttba.droplet.core.database.repository.InvestmentDividendRequest
import io.github.kvtttba.droplet.core.database.repository.InvestmentSellRequest
import io.github.kvtttba.droplet.core.database.repository.LocalInvestmentRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LocalMarketDataRepository
import io.github.kvtttba.droplet.core.database.repository.OpenFixedDepositRequest
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.investment.BankWealthValuationMode
import io.github.kvtttba.droplet.core.domain.investment.FixedDepositStatus
import io.github.kvtttba.droplet.core.domain.investment.OversellPositionException
import io.github.kvtttba.droplet.core.domain.investment.PositionValuationSource
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidator
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DepositInterestMode
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.UnitPrice
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.network.MarketNetworkContract
import io.github.kvtttba.droplet.core.network.MarketProviderEndpoints
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.network.RefreshThrottlePolicy
import io.github.kvtttba.droplet.core.network.fx.ExchangeRateProviderChain
import io.github.kvtttba.droplet.core.network.quote.MarketQuoteProviderChain
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class InvestmentRepositoryTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var context: Context
    private lateinit var database: DropletDatabase
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var marketDataRepository: LocalMarketDataRepository
    private lateinit var investmentRepository: LocalInvestmentRepository

    private var fakeNowMs: Long = LocalDate.of(2025, 1, 1)
        .atStartOfDay(zone).toInstant().toEpochMilli()

    private fun epochOf(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        val seeder = DefaultLedgerSeeder(database)
        val writer = LedgerTransactionWriter(database)
        val engine = LedgerEngine(baseCurrency = Currency.CNY)
        val clock = NetworkClock { fakeNowMs }
        val httpFactory = DropletHttpClientFactory()

        ledgerRepository = LocalLedgerRepository(
            database = database,
            transactionWriter = writer,
            seeder = seeder,
            ledgerEngine = engine,
        )
        marketDataRepository = LocalMarketDataRepository(
            database = database,
            exchangeRateProviderChain = ExchangeRateProviderChain.default(
                httpClientFactory = httpFactory,
                endpoints = MarketProviderEndpoints.DEFAULT,
                clock = clock,
            ),
            marketQuoteProviderChain = MarketQuoteProviderChain.default(
                httpClientFactory = httpFactory,
                endpoints = MarketProviderEndpoints.DEFAULT,
                clock = clock,
            ),
            throttlePolicy = RefreshThrottlePolicy(MarketNetworkContract.DEFAULT_REFRESH_THROTTLE_MS),
            clock = clock,
        )
        investmentRepository = LocalInvestmentRepository(
            database = database,
            transactionWriter = writer,
            seeder = seeder,
            ledgerEngine = engine,
            marketDataRepository = marketDataRepository,
            clock = clock,
            zoneId = zone,
        )

        ledgerRepository.ensureSeeded(fakeNowMs)
        // Persist live USD/HKD rates into Room exchange_rates
        marketDataRepository.saveManualExchangeRate(
            CachedExchangeRate(Currency.USD, Currency.CNY, "7.2", "manual", fakeNowMs),
        )
        marketDataRepository.saveManualExchangeRate(
            CachedExchangeRate(Currency.HKD, Currency.CNY, "0.9", "manual", fakeNowMs),
        )
        // Seed initial balances on CNY debit card & create a USD broker cash account
        ledgerRepository.saveAccount(
            account = ledgerRepository.getAccountById(DefaultSeedCatalog.AccountIds.DEBIT_CARD)!!,
            openingBalance = Money.fromDecimalString("200000.00", Currency.CNY),
            nowEpochMs = fakeNowMs,
        )
        ledgerRepository.saveAccount(
            account = Account(
                id = "acc:usd-cash",
                name = "美股资金户",
                type = AccountType.ASSET,
                subType = AccountSubType.INVEST_CASH,
                currency = Currency.USD,
            ),
            openingBalance = Money.fromDecimalString("10000.00", Currency.USD),
            nowEpochMs = fakeNowMs,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun equityAndCryptoFullLifecycle_buyDcaSellGainLossDividendOversellAndLiveFlow() = runTest {
        // 1. Create AAPL asset (auto-creates linked INVEST_HOLDING account)
        val aapl = investmentRepository.createInvestmentAsset(
            CreateInvestmentAssetRequest(
                id = "asset:aapl",
                assetClass = AssetClass.US_STOCK,
                symbol = "AAPL",
                name = "Apple",
                currency = Currency.USD,
            ),
        )
        val linkedAccount = ledgerRepository.getAccountById(aapl.accountId)
        assertNotNull(linkedAccount)
        assertEquals(AccountSubType.INVEST_HOLDING, linkedAccount!!.subType)
        assertEquals(Currency.USD, linkedAccount.currency)

        // 2. Buy 10 @ $200 (+$1 fee) + DCA 10 @ $240 (+$1 fee) => 20 shares, cost $4,400, avg $220
        val buyTx = investmentRepository.recordBuy(
            InvestmentBuyRequest(
                assetId = aapl.id,
                cashAccountId = "acc:usd-cash",
                quantity = Quantity.of("10"),
                unitPrice = UnitPrice.of("200"),
                fee = Money.fromDecimalString("1.00", Currency.USD),
                occurredAt = epochOf(2025, 1, 5),
            ),
        )
        assertTrue(LedgerValidator.validate(buyTx).isValid)

        val dcaTx = investmentRepository.recordDcaBuy(
            InvestmentBuyRequest(
                assetId = aapl.id,
                cashAccountId = "acc:usd-cash",
                quantity = Quantity.of("10"),
                unitPrice = UnitPrice.of("240"),
                fee = Money.fromDecimalString("1.00", Currency.USD),
                occurredAt = epochOf(2025, 2, 5),
            ),
        )
        assertTrue(LedgerValidator.validate(dcaTx).isValid)
        assertTrue(dcaTx.note!!.contains("定投"))

        // 3. Partial sell 5 shares @ $260 (+$1 fee) -> realized gain $200 routed to INC_INVEST_CAPITAL_GAIN
        val gainSell = investmentRepository.recordSell(
            InvestmentSellRequest(
                assetId = aapl.id,
                cashAccountId = "acc:usd-cash",
                quantity = Quantity.of("5"),
                unitPrice = UnitPrice.of("260"),
                fee = Money.fromDecimalString("1.00", Currency.USD),
                occurredAt = epochOf(2025, 3, 5),
            ),
        )
        assertTrue(LedgerValidator.validate(gainSell.transaction).isValid)
        assertEquals(Money.fromDecimalString("1100.00", Currency.USD), gainSell.preview.costBasis)
        assertEquals(Money.fromDecimalString("200.00", Currency.USD), gainSell.preview.estimatedRealizedPnL)
        assertEquals(
            Money.fromDecimalString("-200.00", Currency.USD),
            gainSell.transaction.postings.first { it.accountId == DefaultSeedCatalog.AccountIds.INC_INVEST_CAPITAL_GAIN }.money,
        )

        // 4. Partial sell 5 shares @ $200 -> realized loss -$100 routed to EXP_FINANCE_INVEST_LOSS
        val lossSell = investmentRepository.recordSell(
            InvestmentSellRequest(
                assetId = aapl.id,
                cashAccountId = "acc:usd-cash",
                quantity = Quantity.of("5"),
                unitPrice = UnitPrice.of("200"),
                occurredAt = epochOf(2025, 3, 15),
            ),
        )
        assertTrue(LedgerValidator.validate(lossSell.transaction).isValid)
        assertEquals(Money.fromDecimalString("-100.00", Currency.USD), lossSell.preview.estimatedRealizedPnL)
        assertEquals(
            Money.fromDecimalString("100.00", Currency.USD),
            lossSell.transaction.postings.first { it.accountId == DefaultSeedCatalog.AccountIds.EXP_FINANCE_INVEST_LOSS }.money,
        )

        // 5. Oversell attempt (10.5 > 10 held) must fail with OversellPositionException and leave DB untouched
        val txCountBeforeOversell = database.transactionDao().countTransactions()
        val oversellOutcome = runCatching {
            investmentRepository.recordSell(
                InvestmentSellRequest(
                    assetId = aapl.id,
                    cashAccountId = "acc:usd-cash",
                    quantity = Quantity.of("10.5"),
                    unitPrice = UnitPrice.of("250"),
                    occurredAt = epochOf(2025, 3, 20),
                ),
            )
        }
        assertTrue(oversellOutcome.exceptionOrNull() is OversellPositionException)
        assertEquals(txCountBeforeOversell, database.transactionDao().countTransactions())

        // 6. Cash dividend $25 (with $2.50 withholding tax)
        val divTx = investmentRepository.recordDividend(
            InvestmentDividendRequest(
                assetId = aapl.id,
                cashAccountId = "acc:usd-cash",
                amount = Money.fromDecimalString("25.00", Currency.USD),
                taxOrFee = Money.fromDecimalString("2.50", Currency.USD),
                occurredAt = epochOf(2025, 4, 1),
            ),
        )
        assertTrue(LedgerValidator.validate(divTx).isValid)

        // 7. Update market quote via MarketDataRepository and verify reactive Flow updates immediately
        marketDataRepository.manualOverrideQuote(
            assetId = aapl.id,
            priceDecimal = "250.00",
            prevCloseDecimal = "245.00",
            nowEpochMs = epochOf(2025, 4, 2),
        )

        val livePos = investmentRepository.observePosition(aapl.id, baseCurrency = Currency.CNY).first()!!
        assertEquals(Quantity.of("10"), livePos.holdingQuantity)
        assertEquals(UnitPrice.of("220"), livePos.averageCostPrice)
        assertEquals(Money.fromDecimalString("2200.00", Currency.USD), livePos.remainingCostBasis)
        assertEquals(Money.fromDecimalString("2500.00", Currency.USD), livePos.marketValueOriginal)
        assertEquals(Money.fromDecimalString("18000.00", Currency.CNY), livePos.marketValueBase)
        // Unrealized = +$300, Realized = +$200 - $100 = +$100, Dividends = +$25, Fees = $1+$1+$1+$2.50 = $5.50
        assertEquals(Money.fromDecimalString("300.00", Currency.USD), livePos.unrealizedPnLOriginal)
        assertEquals(Money.fromDecimalString("100.00", Currency.USD), livePos.realizedPnLOriginal)
        assertEquals(Money.fromDecimalString("25.00", Currency.USD), livePos.totalDividendsOriginal)
        assertEquals(Money.fromDecimalString("5.50", Currency.USD), livePos.totalFeesOriginal)
        assertEquals(Money.fromDecimalString("419.50", Currency.USD), livePos.totalReturnOriginal)

        // Holding account's Room book balance reconciles 100% with remainingCostBasis ($2,200)!
        val balances = ledgerRepository.observeAccountBalances().first()
        val holdingBookMinor = balances.first { it.accountId == aapl.accountId }.balanceMinor
        assertEquals(Money.fromDecimalString("2200.00", Currency.USD).amountMinor, holdingBookMinor)
    }

    @Test
    fun fixedDeposit_openMonthlyPayoutAccrualAndMaturitySettlementReconcileNetWorth() = runTest {
        // 1. Open a 1-year MONTHLY_PAYOUT deposit of ¥100,000 @ 3.65% (¥10/day)
        val openResult = investmentRepository.openFixedDeposit(
            OpenFixedDepositRequest(
                assetRequest = CreateInvestmentAssetRequest(
                    id = "dep:monthly",
                    assetClass = AssetClass.FIXED_DEPOSIT,
                    symbol = "ICBC-1Y",
                    name = "工行按月付息定存",
                    currency = Currency.CNY,
                    depositRateAnnualDecimal = "0.0365",
                    depositStartAt = epochOf(2025, 1, 1),
                    depositMaturityAt = epochOf(2026, 1, 1),
                    depositInterestMode = DepositInterestMode.MONTHLY_PAYOUT,
                ),
                cashAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                principal = Money.fromDecimalString("100000.00", Currency.CNY),
                occurredAt = epochOf(2025, 1, 1),
            ),
        )
        assertTrue(LedgerValidator.validate(openResult.transaction).isValid)

        // After 100 days (2025-04-11), total accrued = ¥1,000; record ¥600 already paid out to debit card
        val payoutTx = investmentRepository.recordFixedDepositInterestPayout(
            FixedDepositInterestPayoutRequest(
                assetId = openResult.asset.id,
                cashAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                interestAmount = Money.fromDecimalString("600.00", Currency.CNY),
                occurredAt = epochOf(2025, 4, 1),
            ),
        )
        assertTrue(LedgerValidator.validate(payoutTx).isValid)

        val midPos = investmentRepository.observePosition(
            assetId = openResult.asset.id,
            baseCurrency = Currency.CNY,
            asOfEpochMs = epochOf(2025, 4, 11),
        ).first()!!
        assertEquals(PositionValuationSource.FIXED_DEPOSIT_ACCRUAL, midPos.valuationSource)
        assertEquals(FixedDepositStatus.ACCRUING, midPos.fixedDepositValuation!!.status)
        // Accrued = ¥1,000, paid = ¥600 -> unpaid = ¥400 -> deposit MV = ¥100,400 (no double counting!)
        assertEquals(Money.fromDecimalString("1000.00", Currency.CNY), midPos.fixedDepositValuation!!.accruedInterest)
        assertEquals(Money.fromDecimalString("400.00", Currency.CNY), midPos.fixedDepositValuation!!.unpaidAccruedInterest)
        assertEquals(Money.fromDecimalString("100400.00", Currency.CNY), midPos.marketValueOriginal)

        // Check PortfolioSummary at day 100:
        // CNY Debit = 200,000 - 100,000 + 600 = 100,600
        // Deposit MV = 100,400 (100,000 principal + 400 unpaid accrued)
        // Total CNY = 201,000 (exact 200,000 + 1,000 total accrued interest, ZERO double count!)
        // Plus USD cash $10,000 * 7.2 = 72,000 -> Total Net Worth = ¥273,000.00
        val midSummary = investmentRepository.observePortfolioSummary(
            baseCurrency = Currency.CNY,
            asOfEpochMs = epochOf(2025, 4, 11),
        ).first()
        assertEquals(Money.fromDecimalString("273000.00", Currency.CNY), midSummary.netWorthBase)

        // 2. Maturity settlement on 2026-01-01 (default interest = unpaid 3650 - 600 = ¥3,050)
        val settleTx = investmentRepository.recordFixedDepositSettlement(
            FixedDepositSettlementRequest(
                assetId = openResult.asset.id,
                cashAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                occurredAt = epochOf(2026, 1, 1),
                archiveHoldingAccountOnFullSettlement = true,
            ),
        )
        assertTrue(LedgerValidator.validate(settleTx).isValid)
        assertEquals(
            Money.fromDecimalString("103050.00", Currency.CNY),
            settleTx.postings.first { it.accountId == DefaultSeedCatalog.AccountIds.DEBIT_CARD }.money,
        )
        assertEquals(
            Money.fromDecimalString("-3050.00", Currency.CNY),
            settleTx.postings.first { it.accountId == DefaultSeedCatalog.AccountIds.INC_INVEST_INTEREST }.money,
        )

        val settledPos = investmentRepository.observePosition(
            assetId = openResult.asset.id,
            baseCurrency = Currency.CNY,
            asOfEpochMs = epochOf(2026, 1, 1),
        ).first()!!
        assertEquals(Quantity.ZERO, settledPos.holdingQuantity)
        assertEquals(Money.zero(Currency.CNY), settledPos.marketValueOriginal)
        assertEquals(Money.fromDecimalString("3650.00", Currency.CNY), settledPos.totalDividendsOriginal)
        assertTrue(ledgerRepository.getAccountById(openResult.asset.accountId)!!.isArchived)

        // Net worth after maturity settlement = 200,000 + 3,650 + 72,000 = ¥275,650.00
        val settledSummary = investmentRepository.observePortfolioSummary(
            baseCurrency = Currency.CNY,
            asOfEpochMs = epochOf(2026, 1, 1),
        ).first()
        assertEquals(Money.fromDecimalString("275650.00", Currency.CNY), settledSummary.netWorthBase)
    }

    @Test
    fun bankWealthManualUpdates_persistAndDriveReactiveValuationAndSummary() = runTest {
        val bw = investmentRepository.createInvestmentAsset(
            CreateInvestmentAssetRequest(
                id = "bw:1",
                assetClass = AssetClass.BANK_WEALTH,
                symbol = "CMB-W01",
                name = "招银理财日日鑫",
                currency = Currency.CNY,
            ),
        )
        investmentRepository.recordBuy(
            InvestmentBuyRequest(
                assetId = bw.id,
                cashAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                quantity = Quantity.of("50000"),
                unitPrice = UnitPrice.of("1"),
                occurredAt = epochOf(2025, 1, 10),
            ),
        )

        // Initially no quote -> COST_BASIS_FALLBACK
        val initial = investmentRepository.observePosition(bw.id).first()!!
        assertEquals(PositionValuationSource.COST_BASIS_FALLBACK, initial.valuationSource)
        assertEquals(BankWealthValuationMode.COST_BASIS_FALLBACK, initial.bankWealthValuationMode)
        assertEquals(Money.fromDecimalString("50000.00", Currency.CNY), initial.marketValueOriginal)

        // 1. Update by latest profit amount (+¥850) -> implied NAV = 1.017, MV = ¥50,850
        val profitUpdate = investmentRepository.updateBankWealthByProfitAmount(
            assetId = bw.id,
            latestProfitAmount = Money.fromDecimalString("850.00", Currency.CNY),
            updatedAtEpochMs = epochOf(2025, 3, 1),
        )
        assertEquals(UnitPrice.of("1.017"), profitUpdate.impliedUnitPrice)
        assertTrue(profitUpdate.quote.isManualOverride)

        val afterProfit = investmentRepository.observePosition(bw.id).first()!!
        assertEquals(PositionValuationSource.MANUAL_OVERRIDE, afterProfit.valuationSource)
        assertEquals(BankWealthValuationMode.MANUAL_NAV_OVERRIDE, afterProfit.bankWealthValuationMode)
        assertEquals(Money.fromDecimalString("50850.00", Currency.CNY), afterProfit.marketValueOriginal)
        assertEquals(Money.fromDecimalString("850.00", Currency.CNY), afterProfit.unrealizedPnLOriginal)
        assertEquals("1.7", afterProfit.unrealizedReturnPctDecimal)

        // 2. Update by unit price (1.025) -> MV = ¥51,250, profit = +¥1,250
        investmentRepository.updateBankWealthByUnitPrice(
            assetId = bw.id,
            manualUnitPrice = UnitPrice.of("1.025"),
            updatedAtEpochMs = epochOf(2025, 4, 1),
        )
        val afterNav = investmentRepository.observePosition(bw.id).first()!!
        assertEquals(Money.fromDecimalString("51250.00", Currency.CNY), afterNav.marketValueOriginal)
        assertEquals(Money.fromDecimalString("1250.00", Currency.CNY), afterNav.unrealizedPnLOriginal)

        // 3. Update by total value (¥49,500 破净) -> MV = ¥49,500, profit = -¥500
        investmentRepository.updateBankWealthByTotalValue(
            assetId = bw.id,
            currentTotalValue = Money.fromDecimalString("49500.00", Currency.CNY),
            updatedAtEpochMs = epochOf(2025, 5, 1),
        )
        val afterTotal = investmentRepository.observePosition(bw.id).first()!!
        assertEquals(Money.fromDecimalString("49500.00", Currency.CNY), afterTotal.marketValueOriginal)
        assertEquals(Money.fromDecimalString("-500.00", Currency.CNY), afterTotal.unrealizedPnLOriginal)
    }

    @Test
    fun portfolioSummaryFlow_respectsIncludeInNetWorthAndAssetLifecycleGuards() = runTest {
        // Add an excluded CNY account (医保卡 ¥12,000, includeInNetWorth = false)
        ledgerRepository.saveAccount(
            account = Account(
                id = "acc:medicare",
                name = "医保卡",
                type = AccountType.ASSET,
                subType = AccountSubType.STORED_VALUE,
                currency = Currency.CNY,
                includeInNetWorth = false,
            ),
            openingBalance = Money.fromDecimalString("12000.00", Currency.CNY),
            nowEpochMs = fakeNowMs,
        )
        // Add a credit card liability (-¥5,000)
        ledgerRepository.saveAccount(
            account = ledgerRepository.getAccountById(DefaultSeedCatalog.AccountIds.CREDIT_CARD)!!,
            openingBalance = Money.fromDecimalString("5000.00", Currency.CNY),
            nowEpochMs = fakeNowMs,
        )

        // Create an unused ETF asset -> test safe delete removes both asset & dedicated holding account
        val unusedEtf = investmentRepository.createInvestmentAsset(
            CreateInvestmentAssetRequest(
                id = "etf:unused",
                assetClass = AssetClass.ETF,
                symbol = "510300",
                name = "沪深300ETF",
                currency = Currency.CNY,
            ),
        )
        assertTrue(investmentRepository.deleteInvestmentAssetIfUnused(unusedEtf.id).isSuccess)
        assertEquals(null, investmentRepository.getInvestmentAsset(unusedEtf.id))
        assertEquals(null, ledgerRepository.getAccountById(unusedEtf.accountId))

        // Create an active HKD stock and buy -> deleting must fail because postings exist
        ledgerRepository.saveAccount(
            account = Account(
                id = "acc:hk-cash",
                name = "港股资金户",
                type = AccountType.ASSET,
                subType = AccountSubType.INVEST_CASH,
                currency = Currency.HKD,
            ),
            openingBalance = Money.fromDecimalString("10000.00", Currency.HKD),
            nowEpochMs = fakeNowMs,
        )
        val hkStock = investmentRepository.createInvestmentAsset(
            CreateInvestmentAssetRequest(
                id = "hk:00700",
                assetClass = AssetClass.HK_STOCK,
                symbol = "00700",
                name = "腾讯控股",
                currency = Currency.HKD,
            ),
        )
        investmentRepository.recordBuy(
            InvestmentBuyRequest(
                assetId = hkStock.id,
                cashAccountId = "acc:hk-cash",
                quantity = Quantity.of("20"),
                unitPrice = UnitPrice.of("400"), // HK$8,000
                occurredAt = epochOf(2025, 1, 20),
            ),
        )
        assertTrue(investmentRepository.deleteInvestmentAssetIfUnused(hkStock.id).isFailure)

        // Update HK quote to HK$450 (MV = HK$9,000 -> ¥8,100; remaining HK cash = HK$2,000 -> ¥1,800)
        marketDataRepository.manualOverrideQuote(hkStock.id, "450", nowEpochMs = epochOf(2025, 2, 1))

        val summary = investmentRepository.observePortfolioSummary(baseCurrency = Currency.CNY).first()
        // Included assets:
        // CNY debit ¥200,000
        // USD cash $10,000 * 7.2 = ¥72,000
        // HKD cash HK$2,000 * 0.9 = ¥1,800 + HK stock MV HK$9,000 * 0.9 = ¥8,100 => HKD total = ¥9,900
        // Total assets = 200,000 + 72,000 + 9,900 = ¥281,900.00
        // Total liabilities = ¥5,000.00 (credit card)
        // Net worth = ¥276,900.00 (medicare ¥12,000 excluded!)
        assertEquals(Money.fromDecimalString("281900.00", Currency.CNY), summary.totalAssetsBase)
        assertEquals(Money.fromDecimalString("5000.00", Currency.CNY), summary.totalLiabilitiesBase)
        assertEquals(Money.fromDecimalString("276900.00", Currency.CNY), summary.netWorthBase)
        assertEquals(Money.fromDecimalString("12000.00", Currency.CNY), summary.excludedNetWorthBase)

        // Live FX update (USD -> 7.50) immediately updates summary Flow!
        marketDataRepository.saveManualExchangeRate(
            CachedExchangeRate(Currency.USD, Currency.CNY, "7.5", "manual", epochOf(2025, 2, 2)),
        )
        val updatedSummary = investmentRepository.observePortfolioSummary(baseCurrency = Currency.CNY).first()
        // USD cash rose by $10,000 * 0.3 = +¥3,000 -> ¥279,900.00
        assertEquals(Money.fromDecimalString("279900.00", Currency.CNY), updatedSummary.netWorthBase)
        assertFalse(updatedSummary.investmentTotals.unrealizedPnLBase.isNegative)
    }

    @Test
    fun crypto8DecimalRoomRoundTrip_fixedDepositEarlyWithdrawalAndBackdatedOversellGuard() = runTest {
        // 1. Crypto 8-decimal buy -> partial sell -> full liquidation -> re-buy round-trip in Room
        val btc = investmentRepository.createInvestmentAsset(
            CreateInvestmentAssetRequest(
                id = "crypto:btc",
                assetClass = AssetClass.CRYPTO,
                symbol = "BTC",
                name = "Bitcoin",
                currency = Currency.USD,
            ),
        )
        investmentRepository.recordBuy(
            InvestmentBuyRequest(
                assetId = btc.id,
                cashAccountId = "acc:usd-cash",
                quantity = Quantity.of("0.00123456"),
                unitPrice = UnitPrice.of("80000"),
                occurredAt = epochOf(2025, 1, 10),
            ),
        )
        investmentRepository.recordBuy(
            InvestmentBuyRequest(
                assetId = btc.id,
                cashAccountId = "acc:usd-cash",
                quantity = Quantity.of("0.00876544"),
                unitPrice = UnitPrice.of("90000"),
                occurredAt = epochOf(2025, 1, 20),
            ),
        )
        val partialBtcSell = investmentRepository.recordSell(
            InvestmentSellRequest(
                assetId = btc.id,
                cashAccountId = "acc:usd-cash",
                quantity = Quantity.of("0.00333333"),
                unitPrice = UnitPrice.of("100000"),
                occurredAt = epochOf(2025, 2, 1),
            ),
        )
        assertTrue(LedgerValidator.validate(partialBtcSell.transaction).isValid)

        // Backdated sell on 2025-01-15 of 0.008 BTC would oversell (only 0.00123456 held on Jan 15)
        val backdatedErr = runCatching {
            investmentRepository.recordSell(
                InvestmentSellRequest(
                    assetId = btc.id,
                    cashAccountId = "acc:usd-cash",
                    quantity = Quantity.of("0.008"),
                    unitPrice = UnitPrice.of("85000"),
                    occurredAt = epochOf(2025, 1, 15),
                ),
            )
        }
        assertTrue(backdatedErr.exceptionOrNull() is OversellPositionException)

        // Full liquidation of remaining 0.00666667 BTC followed by re-buy of 0.005 BTC
        val liquidate = investmentRepository.recordSell(
            InvestmentSellRequest(
                assetId = btc.id,
                cashAccountId = "acc:usd-cash",
                quantity = Quantity.of("0.00666667"),
                unitPrice = UnitPrice.of("95000"),
                occurredAt = epochOf(2025, 2, 10),
            ),
        )
        assertTrue(liquidate.preview.isFullLiquidation)
        assertEquals(Quantity.ZERO, investmentRepository.observePosition(btc.id).first()!!.holdingQuantity)

        investmentRepository.recordBuy(
            InvestmentBuyRequest(
                assetId = btc.id,
                cashAccountId = "acc:usd-cash",
                quantity = Quantity.of("0.005"),
                unitPrice = UnitPrice.of("100000"),
                occurredAt = epochOf(2025, 2, 15),
            ),
        )
        val afterRebuy = investmentRepository.observePosition(btc.id).first()!!
        assertEquals(Quantity.of("0.005"), afterRebuy.holdingQuantity)
        assertEquals(UnitPrice.of("100000"), afterRebuy.averageCostPrice)
        assertEquals(Money.fromDecimalString("500.00", Currency.USD), afterRebuy.remainingCostBasis)

        // Updating asset name succeeds, while changing currency after postings exist is rejected
        val renamed = investmentRepository.updateInvestmentAsset(btc.copy(name = "Bitcoin Spot"))
        assertEquals("Bitcoin Spot", renamed.name)
        assertTrue(
            runCatching {
                investmentRepository.updateInvestmentAsset(btc.copy(currency = Currency.CNY))
            }.isFailure,
        )

        // 2. Fixed deposit partial early withdrawal with penalty fee leaves remaining principal accruing cleanly
        val depOpen = investmentRepository.openFixedDeposit(
            OpenFixedDepositRequest(
                assetRequest = CreateInvestmentAssetRequest(
                    id = "dep:early",
                    assetClass = AssetClass.FIXED_DEPOSIT,
                    symbol = "CMB-2Y",
                    name = "招行2年定存",
                    currency = Currency.CNY,
                    depositRateAnnualDecimal = "0.0365",
                    depositStartAt = epochOf(2025, 1, 1),
                    depositMaturityAt = epochOf(2027, 1, 1),
                    depositInterestMode = DepositInterestMode.SIMPLE_AT_MATURITY,
                ),
                cashAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                principal = Money.fromDecimalString("50000.00", Currency.CNY),
                occurredAt = epochOf(2025, 1, 1),
            ),
        )
        val earlyTx = investmentRepository.recordFixedDepositSettlement(
            FixedDepositSettlementRequest(
                assetId = depOpen.asset.id,
                cashAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                withdrawnPrincipal = Money.fromDecimalString("20000.00", Currency.CNY),
                actualInterestAmount = Money.fromDecimalString("50.00", Currency.CNY),
                penaltyOrTaxFee = Money.fromDecimalString("10.00", Currency.CNY),
                occurredAt = epochOf(2025, 3, 1),
            ),
        )
        assertTrue(LedgerValidator.validate(earlyTx).isValid)

        // Remaining ¥30,000 accrues full SIMPLE_AT_MATURITY rate over 100 days (¥30,000 * 0.0365 * 100 / 365 = ¥300)
        val remainingDep = investmentRepository.observePosition(
            assetId = depOpen.asset.id,
            baseCurrency = Currency.CNY,
            asOfEpochMs = epochOf(2025, 4, 11),
        ).first()!!
        assertEquals(Money.fromDecimalString("30000.00", Currency.CNY), remainingDep.remainingCostBasis)
        assertEquals(Money.fromDecimalString("300.00", Currency.CNY), remainingDep.fixedDepositValuation!!.unpaidAccruedInterest)
        assertEquals(Money.fromDecimalString("30300.00", Currency.CNY), remainingDep.marketValueOriginal)
        assertEquals(Money.fromDecimalString("50.00", Currency.CNY), remainingDep.totalDividendsOriginal)
        assertEquals(Money.fromDecimalString("10.00", Currency.CNY), remainingDep.totalFeesOriginal)
    }
}
