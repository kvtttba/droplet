package io.github.kvtttba.droplet.feature.assets

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
import io.github.kvtttba.droplet.core.domain.investment.PortfolioPositionValuation
import io.github.kvtttba.droplet.core.domain.investment.PortfolioSummary
import io.github.kvtttba.droplet.core.domain.investment.SellCostBasisPreview
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DepositInterestMode
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
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
import kotlinx.coroutines.flow.map
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
class AssetsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeInvestmentRepository : InvestmentRepository {
        val observedCurrencies = mutableListOf<Currency>()
        var createdAssetRequest: CreateInvestmentAssetRequest? = null
        var openedFixedDepositRequest: OpenFixedDepositRequest? = null

        override fun observeInvestmentAssets(includeArchived: Boolean): Flow<List<InvestmentAsset>> =
            MutableStateFlow(emptyList())

        override suspend fun getInvestmentAsset(assetId: String): InvestmentAsset? = null

        override suspend fun createInvestmentAsset(request: CreateInvestmentAssetRequest): InvestmentAsset {
            createdAssetRequest = request
            return InvestmentAsset(
                id = request.id,
                accountId = request.existingHoldingAccountId ?: "holding:${request.id}",
                assetClass = request.assetClass,
                symbol = request.symbol,
                name = request.name,
                currency = request.currency,
                depositRateAnnualDecimal = request.depositRateAnnualDecimal,
                depositStartAt = request.depositStartAt,
                depositMaturityAt = request.depositMaturityAt,
                depositInterestMode = request.depositInterestMode,
            )
        }

        override suspend fun updateInvestmentAsset(asset: InvestmentAsset, nowEpochMs: Long?): InvestmentAsset = asset
        override suspend fun setInvestmentAssetArchived(assetId: String, archived: Boolean, nowEpochMs: Long?) = Unit
        override suspend fun deleteInvestmentAssetIfUnused(assetId: String): Result<Unit> = Result.success(Unit)
        override suspend fun recordBuy(request: InvestmentBuyRequest): Transaction = error("unused")
        override suspend fun previewSell(
            assetId: String,
            quantity: Quantity,
            unitPrice: UnitPrice,
            grossProceeds: Money?,
            asOfEpochMs: Long?,
        ): SellCostBasisPreview = error("unused")

        override suspend fun recordSell(request: InvestmentSellRequest): InvestmentSellResult = error("unused")
        override suspend fun recordDividend(request: InvestmentDividendRequest): Transaction = error("unused")

        override suspend fun openFixedDeposit(request: OpenFixedDepositRequest): FixedDepositOpenResult {
            openedFixedDepositRequest = request
            val asset = createInvestmentAsset(request.assetRequest)
            val tx = Transaction(
                id = "tx-fd-1",
                occurredAt = request.occurredAt ?: 0L,
                displayType = TransactionDisplayType.INVEST,
                postings = emptyList(),
            )
            return FixedDepositOpenResult(asset = asset, transaction = tx)
        }

        override suspend fun recordFixedDepositSettlement(request: FixedDepositSettlementRequest): Transaction =
            error("unused")

        override suspend fun recordFixedDepositInterestPayout(request: FixedDepositInterestPayoutRequest): Transaction =
            error("unused")

        override suspend fun updateBankWealthByUnitPrice(
            assetId: String,
            manualUnitPrice: UnitPrice,
            prevCloseOverride: UnitPrice?,
            updatedAtEpochMs: Long?,
        ): BankWealthManualValuationResult = error("unused")

        override suspend fun updateBankWealthByProfitAmount(
            assetId: String,
            latestProfitAmount: Money,
            prevCloseOverride: UnitPrice?,
            updatedAtEpochMs: Long?,
        ): BankWealthManualValuationResult = error("unused")

        override suspend fun updateBankWealthByTotalValue(
            assetId: String,
            currentTotalValue: Money,
            prevCloseOverride: UnitPrice?,
            updatedAtEpochMs: Long?,
        ): BankWealthManualValuationResult = error("unused")

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
        ): Flow<PortfolioPositionValuation?> = MutableStateFlow(null)

        override fun observePortfolioSummary(
            baseCurrency: Currency,
            asOfEpochMs: Long?,
        ): Flow<PortfolioSummary> {
            observedCurrencies += baseCurrency
            return MutableStateFlow(
                AssetsUiState.emptyPortfolioSummary(baseCurrency).copy(
                    netWorthBase = Money.fromDecimalString(
                        when (baseCurrency) {
                            Currency.CNY -> "10000.00"
                            Currency.USD -> "1388.89"
                            Currency.HKD -> "10869.57"
                        },
                        baseCurrency,
                    ),
                ),
            )
        }
    }

    private class FakeMarketDataRepository : MarketDataRepository {
        val ratesFlow = MutableStateFlow(
            listOf(CachedExchangeRate(Currency.USD, Currency.CNY, "7.2", "eastmoney", 1_764_216_000_000L)),
        )
        val quotesFlow = MutableStateFlow(emptyList<MarketQuote>())
        val refreshCalls = mutableListOf<Boolean>()
        var nextReport = MarketSyncReport(
            fxSummary = FxRefreshSummary(status = MarketSyncStatus.THROTTLED),
            quoteSummary = QuoteRefreshSummary(status = MarketSyncStatus.THROTTLED, completedAt = 1_764_216_000_000L),
        )

        override fun observeExchangeRates(): Flow<List<CachedExchangeRate>> = ratesFlow
        override fun observeExchangeRateTable(): Flow<ExchangeRateTable> = MutableStateFlow(ExchangeRateTable.empty())
        override suspend fun getExchangeRateTable(): ExchangeRateTable = ExchangeRateTable.empty()
        override suspend fun saveManualExchangeRate(rate: CachedExchangeRate) = Unit
        override suspend fun refreshExchangeRates(forceRefresh: Boolean): FxRefreshSummary = nextReport.fxSummary
        override fun observeInvestmentAssets(): Flow<List<InvestmentAsset>> = MutableStateFlow(emptyList())
        override fun observeMarketQuotes(): Flow<List<MarketQuote>> = quotesFlow
        override fun observeMarketQuote(assetId: String): Flow<MarketQuote?> = MutableStateFlow(null)
        override suspend fun getMarketQuote(assetId: String): MarketQuote? = null
        override suspend fun refreshMarketQuotes(
            assetIds: Set<String>?,
            forceRefresh: Boolean,
            overwriteManualOverrides: Boolean,
        ): QuoteRefreshSummary = nextReport.quoteSummary

        override suspend fun manualOverrideQuote(
            assetId: String,
            priceDecimal: String,
            currency: Currency?,
            prevCloseDecimal: String?,
            nowEpochMs: Long?,
        ): MarketQuote = error("unused")

        override suspend fun clearManualOverride(assetId: String, refreshImmediately: Boolean): QuoteItemOutcome? = null

        override suspend fun refreshAll(
            forceRefresh: Boolean,
            overwriteManualOverrides: Boolean,
        ): MarketSyncReport {
            refreshCalls += forceRefresh
            return nextReport
        }
    }

    private class FakeLedgerRepository : LedgerRepository {
        val operationalAccountsFlow = MutableStateFlow(
            listOf(
                Account(
                    id = "acct:debit",
                    name = "储蓄卡",
                    type = AccountType.ASSET,
                    subType = AccountSubType.DEBIT_CARD,
                    currency = Currency.CNY,
                ),
            ),
        )

        override suspend fun ensureSeeded(nowEpochMs: Long): SeedResult = SeedResult(0, 0)
        override fun observeAllAccounts(includeArchived: Boolean, includeSystem: Boolean): Flow<List<Account>> =
            operationalAccountsFlow

        override fun observeOperationalAccounts(includeArchived: Boolean): Flow<List<Account>> =
            operationalAccountsFlow

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
    fun switchesDisplayCurrencyAndHandlesThrottledVsForcedRefresh() = runTest(testDispatcher) {
        val investRepo = FakeInvestmentRepository()
        val marketRepo = FakeMarketDataRepository()
        val ledgerRepo = FakeLedgerRepository()
        val vm = AssetsViewModel(investRepo, marketRepo, ledgerRepo, NetworkClock { 1_764_216_000_000L })

        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(Currency.CNY, vm.uiState.value.displayCurrency)
        assertEquals(Money.fromDecimalString("10000.00", Currency.CNY), vm.uiState.value.summary.netWorthBase)
        assertEquals(1_764_216_000_000L, vm.uiState.value.lastUpdatedAtEpochMs)

        // Entry refresh uses throttled mode (forceRefresh = false) and stays quiet when throttled
        vm.onScreenEntered()
        advanceUntilIdle()
        assertEquals(listOf(false), marketRepo.refreshCalls)
        assertNull(vm.uiState.value.refreshNotice)

        // Switch to USD triggers portfolio summary re-observation
        vm.selectDisplayCurrency(Currency.USD)
        advanceUntilIdle()
        assertEquals(Currency.USD, vm.uiState.value.displayCurrency)
        assertEquals(Money.fromDecimalString("1388.89", Currency.USD), vm.uiState.value.summary.netWorthBase)

        // Forced manual refresh with failure surfaces cache-retained warning notice
        marketRepo.nextReport = MarketSyncReport(
            fxSummary = FxRefreshSummary(status = MarketSyncStatus.UPDATED, updatedCount = 6),
            quoteSummary = QuoteRefreshSummary(
                status = MarketSyncStatus.FAILED_CACHE_RETAINED,
                completedAt = 1_764_216_500_000L,
            ),
        )
        vm.refreshNow()
        advanceUntilIdle()
        assertEquals(listOf(false, true), marketRepo.refreshCalls)
        assertEquals(AssetsNoticeTone.WARNING, vm.uiState.value.refreshNotice?.tone)
        assertNotNull(vm.uiState.value.userMessage)

        collector.cancel()
    }

    @Test
    fun createsStandardAssetAndOpensFixedDepositWithCanonicalDecimalRate() = runTest(testDispatcher) {
        val investRepo = FakeInvestmentRepository()
        val marketRepo = FakeMarketDataRepository()
        val ledgerRepo = FakeLedgerRepository()
        val vm = AssetsViewModel(investRepo, marketRepo, ledgerRepo, NetworkClock { 1_764_216_000_000L })

        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        var createdId: String? = null
        vm.createInvestmentAsset(
            CreateInvestmentAssetFormInput(
                assetClass = AssetClass.CRYPTO,
                name = "Bitcoin",
                symbol = "BTC",
                currency = Currency.USD,
                includeInNetWorth = true,
            ),
        ) { result ->
            createdId = result.getOrNull()
        }
        advanceUntilIdle()
        assertNotNull(createdId)
        assertEquals(AssetClass.CRYPTO, investRepo.createdAssetRequest?.assetClass)
        assertEquals("BTC", investRepo.createdAssetRequest?.symbol)

        var fdId: String? = null
        vm.createInvestmentAsset(
            CreateInvestmentAssetFormInput(
                assetClass = AssetClass.FIXED_DEPOSIT,
                name = "一年定期",
                symbol = "",
                currency = Currency.CNY,
                depositRatePercentText = "2.45",
                depositStartDateIso = "2026-01-01",
                depositMaturityDateIso = "2027-01-01",
                depositInterestMode = DepositInterestMode.SIMPLE_AT_MATURITY,
                depositPrincipalText = "50000",
                depositCashAccountId = "acct:debit",
            ),
        ) { result ->
            fdId = result.getOrNull()
        }
        advanceUntilIdle()
        assertNotNull(fdId)
        assertNotNull(investRepo.openedFixedDepositRequest)
        assertEquals("0.0245", investRepo.openedFixedDepositRequest!!.assetRequest.depositRateAnnualDecimal)
        assertEquals(Money.fromDecimalString("50000", Currency.CNY), investRepo.openedFixedDepositRequest!!.principal)

        // Toggle excluded foldout
        assertFalse(vm.uiState.value.excludedExpanded)
        vm.toggleExcludedExpanded()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.excludedExpanded)

        collector.cancel()
    }
}
