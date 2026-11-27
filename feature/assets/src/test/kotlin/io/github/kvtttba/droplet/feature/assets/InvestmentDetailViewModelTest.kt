package io.github.kvtttba.droplet.feature.assets

import androidx.lifecycle.SavedStateHandle
import io.github.kvtttba.droplet.core.database.dao.AccountCurrencyBalanceRow
import io.github.kvtttba.droplet.core.database.dao.PeriodSummaryRow
import io.github.kvtttba.droplet.core.database.dao.TransactionFilter
import io.github.kvtttba.droplet.core.database.repository.CreateInvestmentAssetRequest
import io.github.kvtttba.droplet.core.database.repository.FixedDepositInterestPayoutRequest
import io.github.kvtttba.droplet.core.database.repository.FixedDepositOpenResult
import io.github.kvtttba.droplet.core.database.repository.FixedDepositSettlementRequest
import io.github.kvtttba.droplet.core.database.repository.FxRefreshSummary
import io.github.kvtttba.droplet.core.database.repository.InvestmentBuyRequest
import io.github.kvtttba.droplet.core.database.repository.InvestmentDividendRequest
import io.github.kvtttba.droplet.core.database.repository.InvestmentRepository
import io.github.kvtttba.droplet.core.database.repository.InvestmentSellRequest
import io.github.kvtttba.droplet.core.database.repository.InvestmentSellResult
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerTransactionRecord
import io.github.kvtttba.droplet.core.database.repository.MarketDataRepository
import io.github.kvtttba.droplet.core.database.repository.MarketSyncReport
import io.github.kvtttba.droplet.core.database.repository.MarketSyncStatus
import io.github.kvtttba.droplet.core.database.repository.OpenFixedDepositRequest
import io.github.kvtttba.droplet.core.database.repository.QuoteRefreshSummary
import io.github.kvtttba.droplet.core.database.seed.SeedResult
import io.github.kvtttba.droplet.core.domain.investment.BankWealthManualValuationResult
import io.github.kvtttba.droplet.core.domain.investment.BankWealthValuationHelper
import io.github.kvtttba.droplet.core.domain.investment.OversellPositionException
import io.github.kvtttba.droplet.core.domain.investment.PortfolioPositionEngine
import io.github.kvtttba.droplet.core.domain.investment.PortfolioPositionValuation
import io.github.kvtttba.droplet.core.domain.investment.PortfolioSummary
import io.github.kvtttba.droplet.core.domain.investment.PositionLedgerState
import io.github.kvtttba.droplet.core.domain.investment.SellCostBasisPreview
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.MarketQuote
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.model.UnitPrice
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.network.quote.QuoteItemOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InvestmentDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val btcAsset = InvestmentAsset(
        id = "asset-btc",
        accountId = "holding:asset-btc",
        assetClass = AssetClass.CRYPTO,
        symbol = "BTC",
        name = "Bitcoin",
        currency = Currency.USD,
    )

    private val wealthAsset = InvestmentAsset(
        id = "asset-wealth",
        accountId = "holding:asset-wealth",
        assetClass = AssetClass.BANK_WEALTH,
        symbol = "BW-01",
        name = "稳健理财",
        currency = Currency.CNY,
    )

    private fun buildPosition(
        asset: InvestmentAsset,
        qty: String,
        cost: String,
        latestPrice: String,
        manualOverride: Boolean = false,
    ): PortfolioPositionValuation {
        val state = PositionLedgerState(
            assetId = asset.id,
            currency = asset.currency,
            holdingQuantity = Quantity.of(qty),
            remainingCostBasis = Money.fromDecimalString(cost, asset.currency),
            averageCostPrice = UnitPrice.of(
                Money.fromDecimalString(cost, asset.currency).toBigDecimal()
                    .divide(Quantity.of(qty).toBigDecimal(), 8, java.math.RoundingMode.HALF_UP),
            ),
            realizedPnL = Money.zero(asset.currency),
            totalDividends = Money.zero(asset.currency),
            totalDividendTax = Money.zero(asset.currency),
            totalTradeFees = Money.zero(asset.currency),
            totalFees = Money.zero(asset.currency),
            cumulativeBuyCost = Money.fromDecimalString(cost, asset.currency),
            cumulativeSellProceeds = Money.zero(asset.currency),
            buyCount = 1,
            sellCount = 0,
            dividendCount = 0,
        )
        val rates = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.2", ExchangeRateSource.MARKET_CACHE, 1000L),
        )
        return PortfolioPositionEngine.evaluatePositionFromState(
            asset = asset,
            ledgerState = state,
            marketQuote = MarketQuote(
                assetId = asset.id,
                latestPrice = UnitPrice.of(latestPrice),
                priceCurrency = asset.currency,
                sourceProvider = if (manualOverride) "manual" else "okx",
                isManualOverride = manualOverride,
                fetchedAt = 1_764_216_000_000L,
            ),
            baseCurrency = Currency.CNY,
            exchangeRateProvider = rates,
            asOfEpochMs = 1_764_216_000_000L,
        )
    }

    private class FakeInvestmentRepo(
        initialPosition: PortfolioPositionValuation,
    ) : InvestmentRepository {
        val positionFlow = MutableStateFlow<PortfolioPositionValuation?>(initialPosition)
        var lastBuyRequest: InvestmentBuyRequest? = null
        var lastSellRequest: InvestmentSellRequest? = null
        var lastDividendRequest: InvestmentDividendRequest? = null
        var lastBankWealthProfit: Money? = null

        override fun observeInvestmentAssets(includeArchived: Boolean): Flow<List<InvestmentAsset>> =
            MutableStateFlow(emptyList())

        override suspend fun getInvestmentAsset(assetId: String): InvestmentAsset? = positionFlow.value?.asset
        override suspend fun createInvestmentAsset(request: CreateInvestmentAssetRequest): InvestmentAsset = error("unused")
        override suspend fun updateInvestmentAsset(asset: InvestmentAsset, nowEpochMs: Long?): InvestmentAsset = asset
        override suspend fun setInvestmentAssetArchived(assetId: String, archived: Boolean, nowEpochMs: Long?) = Unit
        override suspend fun deleteInvestmentAssetIfUnused(assetId: String): Result<Unit> = Result.success(Unit)
        override suspend fun recordBuy(request: InvestmentBuyRequest): Transaction {
            lastBuyRequest = request
            return Transaction("tx-buy", request.occurredAt, TransactionDisplayType.INVEST, postings = emptyList())
        }

        override suspend fun previewSell(
            assetId: String,
            quantity: Quantity,
            unitPrice: UnitPrice,
            grossProceeds: Money?,
            asOfEpochMs: Long?,
        ): SellCostBasisPreview = PortfolioPositionEngine.previewSellFromState(
            positionFlow.value!!.ledgerState,
            quantity,
            unitPrice,
            grossProceeds,
        )

        override suspend fun recordSell(request: InvestmentSellRequest): InvestmentSellResult {
            val preview = PortfolioPositionEngine.previewSellFromState(
                positionFlow.value!!.ledgerState,
                request.quantity,
                request.unitPrice,
                request.grossProceeds,
            )
            lastSellRequest = request
            return InvestmentSellResult(
                Transaction("tx-sell", request.occurredAt, TransactionDisplayType.INVEST, postings = emptyList()),
                preview,
            )
        }

        override suspend fun recordDividend(request: InvestmentDividendRequest): Transaction {
            lastDividendRequest = request
            return Transaction("tx-div", request.occurredAt, TransactionDisplayType.INVEST, postings = emptyList())
        }

        override suspend fun openFixedDeposit(request: OpenFixedDepositRequest): FixedDepositOpenResult = error("unused")
        override suspend fun recordFixedDepositSettlement(request: FixedDepositSettlementRequest): Transaction =
            Transaction("tx-settle", request.occurredAt, TransactionDisplayType.INVEST, postings = emptyList())

        override suspend fun recordFixedDepositInterestPayout(request: FixedDepositInterestPayoutRequest): Transaction =
            Transaction("tx-payout", request.occurredAt, TransactionDisplayType.INVEST, postings = emptyList())

        override suspend fun updateBankWealthByUnitPrice(
            assetId: String,
            manualUnitPrice: UnitPrice,
            prevCloseOverride: UnitPrice?,
            updatedAtEpochMs: Long?,
        ): BankWealthManualValuationResult = BankWealthValuationHelper.deriveQuoteFromUnitPrice(
            asset = positionFlow.value!!.asset,
            holdingQuantity = positionFlow.value!!.holdingQuantity,
            costBasis = positionFlow.value!!.remainingCostBasis,
            manualUnitPrice = manualUnitPrice,
            updatedAtEpochMs = updatedAtEpochMs ?: 0L,
        )

        override suspend fun updateBankWealthByProfitAmount(
            assetId: String,
            latestProfitAmount: Money,
            prevCloseOverride: UnitPrice?,
            updatedAtEpochMs: Long?,
        ): BankWealthManualValuationResult {
            lastBankWealthProfit = latestProfitAmount
            return BankWealthValuationHelper.deriveQuoteFromProfitAmount(
                asset = positionFlow.value!!.asset,
                holdingQuantity = positionFlow.value!!.holdingQuantity,
                costBasis = positionFlow.value!!.remainingCostBasis,
                latestProfitAmount = latestProfitAmount,
                updatedAtEpochMs = updatedAtEpochMs ?: 0L,
            )
        }

        override suspend fun updateBankWealthByTotalValue(
            assetId: String,
            currentTotalValue: Money,
            prevCloseOverride: UnitPrice?,
            updatedAtEpochMs: Long?,
        ): BankWealthManualValuationResult = BankWealthValuationHelper.deriveQuoteFromTotalValue(
            asset = positionFlow.value!!.asset,
            holdingQuantity = positionFlow.value!!.holdingQuantity,
            costBasis = positionFlow.value!!.remainingCostBasis,
            currentTotalValue = currentTotalValue,
            updatedAtEpochMs = updatedAtEpochMs ?: 0L,
        )

        override fun observeAssetTransactions(assetId: String): Flow<List<Transaction>> = MutableStateFlow(emptyList())
        override fun observePortfolioPositions(
            baseCurrency: Currency,
            includeZeroPositions: Boolean,
            includeArchived: Boolean,
            asOfEpochMs: Long?,
        ): Flow<List<PortfolioPositionValuation>> = MutableStateFlow(emptyList())

        override fun observePosition(
            assetId: String,
            baseCurrency: Currency,
            asOfEpochMs: Long?,
        ): Flow<PortfolioPositionValuation?> = positionFlow

        override fun observePortfolioSummary(baseCurrency: Currency, asOfEpochMs: Long?): Flow<PortfolioSummary> =
            MutableStateFlow(AssetsUiState.emptyPortfolioSummary(baseCurrency))
    }

    private class FakeMarketRepo : MarketDataRepository {
        var lastOverridePrice: String? = null
        var clearedManualAssetId: String? = null

        override fun observeExchangeRates(): Flow<List<CachedExchangeRate>> = MutableStateFlow(emptyList())
        override fun observeExchangeRateTable(): Flow<ExchangeRateTable> = MutableStateFlow(ExchangeRateTable.empty())
        override suspend fun getExchangeRateTable(): ExchangeRateTable = ExchangeRateTable.empty()
        override suspend fun saveManualExchangeRate(rate: CachedExchangeRate) = Unit
        override suspend fun refreshExchangeRates(forceRefresh: Boolean): FxRefreshSummary =
            FxRefreshSummary(MarketSyncStatus.UPDATED)

        override fun observeInvestmentAssets(): Flow<List<InvestmentAsset>> = MutableStateFlow(emptyList())
        override fun observeMarketQuotes(): Flow<List<MarketQuote>> = MutableStateFlow(emptyList())
        override fun observeMarketQuote(assetId: String): Flow<MarketQuote?> = MutableStateFlow(null)
        override suspend fun getMarketQuote(assetId: String): MarketQuote? = null
        override suspend fun refreshMarketQuotes(
            assetIds: Set<String>?,
            forceRefresh: Boolean,
            overwriteManualOverrides: Boolean,
        ): QuoteRefreshSummary = QuoteRefreshSummary(MarketSyncStatus.UPDATED, completedAt = 0L)

        override suspend fun manualOverrideQuote(
            assetId: String,
            priceDecimal: String,
            currency: Currency?,
            prevCloseDecimal: String?,
            nowEpochMs: Long?,
        ): MarketQuote {
            lastOverridePrice = priceDecimal
            return MarketQuote(
                assetId = assetId,
                latestPrice = UnitPrice.of(priceDecimal),
                priceCurrency = currency ?: Currency.USD,
                sourceProvider = "manual",
                isManualOverride = true,
                fetchedAt = nowEpochMs ?: 0L,
            )
        }

        override suspend fun clearManualOverride(assetId: String, refreshImmediately: Boolean): QuoteItemOutcome? {
            clearedManualAssetId = assetId
            return null
        }

        override suspend fun refreshAll(forceRefresh: Boolean, overwriteManualOverrides: Boolean): MarketSyncReport =
            MarketSyncReport(
                FxRefreshSummary(MarketSyncStatus.UPDATED),
                QuoteRefreshSummary(MarketSyncStatus.UPDATED, completedAt = 0L),
            )
    }

    private class FakeLedgerRepo : LedgerRepository {
        val accounts = MutableStateFlow(
            listOf(
                Account(
                    id = "acct:usd",
                    name = "美元资金户",
                    type = AccountType.ASSET,
                    subType = AccountSubType.INVEST_CASH,
                    currency = Currency.USD,
                ),
                Account(
                    id = "acct:cny",
                    name = "储蓄卡",
                    type = AccountType.ASSET,
                    subType = AccountSubType.DEBIT_CARD,
                    currency = Currency.CNY,
                ),
            ),
        )

        override suspend fun ensureSeeded(nowEpochMs: Long): SeedResult = SeedResult(0, 0)
        override fun observeAllAccounts(includeArchived: Boolean, includeSystem: Boolean): Flow<List<Account>> = accounts
        override fun observeOperationalAccounts(includeArchived: Boolean): Flow<List<Account>> = accounts
        override fun observeCategories(type: AccountType, includeArchived: Boolean): Flow<List<Account>> =
            MutableStateFlow(emptyList())

        override fun observeAccountBalances(status: TransactionStatus): Flow<List<AccountCurrencyBalanceRow>> =
            MutableStateFlow(emptyList())

        override suspend fun getAccountById(accountId: String): Account? = null
        override suspend fun saveAccount(
            account: Account,
            openingBalance: Money?,
            openingExchangeRateOverride: ExchangeRateSnapshot?,
            nowEpochMs: Long,
        ) = Unit

        override suspend fun setAccountArchived(accountId: String, archived: Boolean, nowEpochMs: Long) = Unit
        override suspend fun deleteAccountIfUnused(accountId: String): Result<Unit> = Result.success(Unit)
        override fun observeTags(includeArchived: Boolean): Flow<List<Tag>> = MutableStateFlow(emptyList())
        override suspend fun saveTag(tag: Tag): Result<Unit> = Result.success(Unit)
        override suspend fun setTagArchived(tagId: String, archived: Boolean, nowEpochMs: Long) = Unit
        override suspend fun deleteTag(tagId: String) = Unit
        override fun observeFilteredTransactions(filter: TransactionFilter): Flow<List<LedgerTransactionRecord>> =
            MutableStateFlow(emptyList())

        override fun observeFilteredPeriodSummary(baseCurrency: Currency, filter: TransactionFilter): Flow<PeriodSummaryRow> =
            MutableStateFlow(PeriodSummaryRow(0L, 0L, 0))

        override fun observePeriodSummary(
            baseCurrency: Currency,
            startInclusive: Long?,
            endExclusive: Long?,
            status: TransactionStatus,
        ): Flow<PeriodSummaryRow> = MutableStateFlow(PeriodSummaryRow(0L, 0L, 0))

        override suspend fun getTransactionById(transactionId: String): Transaction? = null
        override suspend fun saveTransaction(transaction: Transaction, allowArchivedAccounts: Boolean) = Unit
        override suspend fun voidTransaction(transactionId: String, nowEpochMs: Long) = Unit
        override suspend fun restoreTransaction(transactionId: String, nowEpochMs: Long) = Unit
        override suspend fun deleteTransaction(transactionId: String) = Unit
        override fun observeExchangeRateTable(): Flow<ExchangeRateTable> = MutableStateFlow(ExchangeRateTable.empty())
        override suspend fun getExchangeRateTable(): ExchangeRateTable = ExchangeRateTable.empty()
        override suspend fun upsertExchangeRate(rate: CachedExchangeRate) = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun eightDecimalCryptoSellPreview_guardsOversellAndRecordsManualOverride() = runTest(testDispatcher) {
        val pos = buildPosition(
            asset = btcAsset,
            qty = "0.12345678",
            cost = "9000.00",
            latestPrice = "96000.00",
        )
        val investRepo = FakeInvestmentRepo(pos)
        val marketRepo = FakeMarketRepo()
        val ledgerRepo = FakeLedgerRepo()
        val vm = InvestmentDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(InvestmentDetailViewModel.NAV_ARG_ASSET_ID to btcAsset.id)),
            investmentRepository = investRepo,
            marketDataRepository = marketRepo,
            ledgerRepository = ledgerRepo,
            clock = NetworkClock { 1_764_216_000_000L },
        )

        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals("0.12345678", vm.uiState.value.position?.holdingQuantity?.canonical)
        assertEquals(listOf("acct:usd"), vm.uiState.value.matchingCashAccounts.map { it.id })

        // Valid partial sell preview
        val validPreview = vm.computeSellPreview("0.05000000", "96000.00")
        assertTrue(validPreview.canSubmit)
        assertNull(validPreview.oversellError)
        assertEquals("0.07345678", validPreview.preview?.remainingQuantityAfterSell?.canonical)

        // Oversell is blocked in real time
        val oversellPreview = vm.computeSellPreview("0.20000000", "96000.00")
        assertFalse(oversellPreview.canSubmit)
        assertNotNull(oversellPreview.oversellError)
        assertTrue(oversellPreview.oversellError!!.contains("超卖拦截"))

        // Manual override + clear
        var overrideErr: String? = "unset"
        vm.manualOverrideQuote("98500.50", "96000") { overrideErr = it }
        advanceUntilIdle()
        assertNull(overrideErr)
        assertEquals("98500.5", marketRepo.lastOverridePrice)

        vm.clearManualOverride()
        advanceUntilIdle()
        assertEquals(btcAsset.id, marketRepo.clearedManualAssetId)

        // Record DCA buy
        var buyErr: String? = "unset"
        vm.recordBuy(true, "acct:usd", "0.00500000", "95000", "", "", "周定投") { buyErr = it }
        advanceUntilIdle()
        assertNull(buyErr)
        assertTrue(investRepo.lastBuyRequest!!.isDca)
        assertEquals("0.005", investRepo.lastBuyRequest!!.quantity.canonical)

        collector.cancel()
    }

    @Test
    fun bankWealthManualValuation_supportsNegativeProfitAmount() = runTest(testDispatcher) {
        val pos = buildPosition(
            asset = wealthAsset,
            qty = "10000",
            cost = "10000.00",
            latestPrice = "1.02",
        )
        val investRepo = FakeInvestmentRepo(pos)
        val marketRepo = FakeMarketRepo()
        val ledgerRepo = FakeLedgerRepo()
        val vm = InvestmentDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(InvestmentDetailViewModel.NAV_ARG_ASSET_ID to wealthAsset.id)),
            investmentRepository = investRepo,
            marketDataRepository = marketRepo,
            ledgerRepository = ledgerRepo,
            clock = NetworkClock { 1_764_216_000_000L },
        )

        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        var err: String? = "unset"
        vm.updateBankWealthValuation(BankWealthManualInputMode.PROFIT_AMOUNT, "-125.50") { err = it }
        advanceUntilIdle()
        assertNull(err)
        assertEquals(Money.fromDecimalString("-125.50", Currency.CNY), investRepo.lastBankWealthProfit)

        collector.cancel()
    }
}
