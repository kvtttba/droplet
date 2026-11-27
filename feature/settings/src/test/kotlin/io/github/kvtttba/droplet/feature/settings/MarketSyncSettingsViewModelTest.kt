package io.github.kvtttba.droplet.feature.settings

import io.github.kvtttba.droplet.core.database.repository.FxRefreshSummary
import io.github.kvtttba.droplet.core.database.repository.MarketDataRepository
import io.github.kvtttba.droplet.core.database.repository.MarketSyncReport
import io.github.kvtttba.droplet.core.database.repository.MarketSyncStatus
import io.github.kvtttba.droplet.core.database.repository.QuoteRefreshSummary
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.MarketQuote
import io.github.kvtttba.droplet.core.model.UnitPrice
import io.github.kvtttba.droplet.core.network.NetworkProxyConfig
import io.github.kvtttba.droplet.core.network.NetworkProxyType
import io.github.kvtttba.droplet.core.network.quote.QuoteItemOutcome
import io.github.kvtttba.droplet.core.security.MarketSyncInterval
import io.github.kvtttba.droplet.core.security.MarketSyncPreferences
import io.github.kvtttba.droplet.core.security.MarketSyncPreferencesRepository
import io.github.kvtttba.droplet.core.security.NetworkProxyPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
class MarketSyncSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeMarketSyncPreferencesRepository : MarketSyncPreferencesRepository {
        val flow = MutableStateFlow(MarketSyncPreferences())
        override val preferences: Flow<MarketSyncPreferences> = flow
        override suspend fun currentPreferences(): MarketSyncPreferences = flow.value
        override suspend fun setRefreshInterval(interval: MarketSyncInterval) {
            flow.update { it.copy(refreshInterval = interval) }
        }

        override suspend fun setWifiOnly(wifiOnly: Boolean) {
            flow.update { it.copy(wifiOnly = wifiOnly) }
        }

        override suspend fun updatePreferences(refreshInterval: MarketSyncInterval, wifiOnly: Boolean) {
            flow.value = MarketSyncPreferences(refreshInterval = refreshInterval, wifiOnly = wifiOnly)
        }
    }

    private class FakeNetworkProxyPreferencesRepository : NetworkProxyPreferencesRepository {
        val flow = MutableStateFlow(NetworkProxyConfig.NONE)
        override val proxyConfig: Flow<NetworkProxyConfig> = flow
        override suspend fun currentProxyConfig(): NetworkProxyConfig = flow.value
        override suspend fun saveProxyConfig(config: NetworkProxyConfig) {
            flow.value = config.validated()
        }

        override suspend fun clearProxyConfig() {
            flow.value = NetworkProxyConfig.NONE
        }
    }

    private class FakeMarketDataRepository : MarketDataRepository {
        val ratesFlow = MutableStateFlow(
            listOf(
                CachedExchangeRate(Currency.USD, Currency.CNY, "7.2", "eastmoney", 1_764_216_000_000L),
            ),
        )
        val quotesFlow = MutableStateFlow(
            listOf(
                MarketQuote(
                    assetId = "asset-1",
                    latestPrice = UnitPrice.of("100"),
                    priceCurrency = Currency.CNY,
                    sourceProvider = "eastmoney",
                    fetchedAt = 1_764_216_600_000L,
                ),
            ),
        )
        var lastForceRefresh: Boolean? = null
        var nextReport = MarketSyncReport(
            fxSummary = FxRefreshSummary(status = MarketSyncStatus.UPDATED, updatedCount = 6),
            quoteSummary = QuoteRefreshSummary(
                status = MarketSyncStatus.UPDATED,
                updatedAssetIds = listOf("asset-1"),
                completedAt = 1_764_217_000_000L,
            ),
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
        ): MarketQuote = error("Not used")

        override suspend fun clearManualOverride(
            assetId: String,
            refreshImmediately: Boolean,
        ): QuoteItemOutcome? = null

        override suspend fun refreshAll(
            forceRefresh: Boolean,
            overwriteManualOverrides: Boolean,
        ): MarketSyncReport {
            lastForceRefresh = forceRefresh
            return nextReport
        }
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
    fun updatesSyncIntervalWifiOnlyAndRunsForceRefresh() = runTest(testDispatcher) {
        val syncRepo = FakeMarketSyncPreferencesRepository()
        val proxyRepo = FakeNetworkProxyPreferencesRepository()
        val marketRepo = FakeMarketDataRepository()
        val viewModel = MarketSyncSettingsViewModel(syncRepo, proxyRepo, marketRepo)

        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(MarketSyncInterval.HOURS_12, viewModel.uiState.value.refreshInterval)
        assertEquals(1_764_216_600_000L, viewModel.uiState.value.lastUpdatedAtEpochMs)
        assertEquals(1, viewModel.uiState.value.cachedRateCount)
        assertEquals(1, viewModel.uiState.value.cachedQuoteCount)

        viewModel.setRefreshInterval(MarketSyncInterval.HOURS_6)
        viewModel.setWifiOnly(true)
        advanceUntilIdle()

        assertEquals(MarketSyncInterval.HOURS_6, viewModel.uiState.value.refreshInterval)
        assertTrue(viewModel.uiState.value.wifiOnly)

        viewModel.refreshNow()
        advanceUntilIdle()
        assertEquals(true, marketRepo.lastForceRefresh)
        assertNotNull(viewModel.uiState.value.lastRefreshSummary)
        assertTrue(viewModel.uiState.value.lastRefreshSummary!!.contains("更新 1 项行情"))

        collector.cancel()
    }

    @Test
    fun proxyValidationRejectsUnsafeInputsAndPersistsValidHttpAndSocks() = runTest(testDispatcher) {
        val syncRepo = FakeMarketSyncPreferencesRepository()
        val proxyRepo = FakeNetworkProxyPreferencesRepository()
        val marketRepo = FakeMarketDataRepository()
        val viewModel = MarketSyncSettingsViewModel(syncRepo, proxyRepo, marketRepo)

        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        // Credentialed host rejected
        viewModel.onDraftProxyTypeChange(NetworkProxyType.HTTP)
        viewModel.onDraftProxyHostChange("user:pass@127.0.0.1")
        viewModel.onDraftProxyPortChange("7890")
        viewModel.saveProxyConfig()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.proxyValidationError)
        assertFalse(proxyRepo.flow.value.isEnabled)

        // Scheme prefix rejected
        viewModel.onDraftProxyHostChange("http://127.0.0.1")
        viewModel.saveProxyConfig()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.proxyValidationError)

        // Port out of range rejected
        viewModel.onDraftProxyHostChange("127.0.0.1")
        viewModel.onDraftProxyPortChange("70000")
        viewModel.saveProxyConfig()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.proxyValidationError)

        // Valid HTTP proxy persists cleanly
        viewModel.onDraftProxyPortChange("7890")
        viewModel.saveProxyConfig()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.proxyValidationError)
        assertEquals(NetworkProxyType.HTTP, proxyRepo.flow.value.type)
        assertEquals("127.0.0.1", proxyRepo.flow.value.host)
        assertEquals(7890, proxyRepo.flow.value.port)

        // Clear restores direct mode
        viewModel.clearProxyConfig()
        advanceUntilIdle()
        assertEquals(NetworkProxyConfig.NONE, proxyRepo.flow.value)
        assertEquals(NetworkProxyType.NONE, viewModel.uiState.value.draftProxyType)

        collector.cancel()
    }
}
