package io.github.kvtttba.droplet.core.database

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.repository.LocalMarketDataRepository
import io.github.kvtttba.droplet.core.database.repository.MarketSyncStatus
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.network.HttpClientTimeouts
import io.github.kvtttba.droplet.core.network.MarketNetworkContract
import io.github.kvtttba.droplet.core.network.MarketProviderEndpoints
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.network.RefreshThrottlePolicy
import io.github.kvtttba.droplet.core.network.fx.ExchangeRateProviderChain
import io.github.kvtttba.droplet.core.network.quote.MarketQuoteProviderChain
import io.github.kvtttba.droplet.core.network.quote.QuoteItemOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MarketDataRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: DropletDatabase
    private lateinit var server: MockWebServer
    private lateinit var repository: LocalMarketDataRepository

    private var fakeNowMs: Long = 1_764_240_000_000L

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        DefaultLedgerSeeder(database).seedIfNeeded(nowEpochMs = 1_000L)

        server = MockWebServer()
        server.start()

        val base = server.url("/").toString()
        val endpoints = MarketProviderEndpoints(
            eastMoneyPushBaseUrl = base,
            frankfurterBaseUrl = base,
            openExchangeRateBaseUrl = base,
            tencentQuoteBaseUrl = base,
            sinaQuoteBaseUrl = base,
            tiantianFundBaseUrl = base,
            eastMoneyFundBaseUrl = base,
            yahooFinanceBaseUrl = base,
            spotXauBaseUrl = base,
            okxBaseUrl = base,
            binanceBaseUrl = base,
            coinGeckoBaseUrl = base,
        )
        val clock = NetworkClock { fakeNowMs }
        val httpFactory = DropletHttpClientFactory(
            timeouts = HttpClientTimeouts(
                connectTimeoutMs = 500L,
                readTimeoutMs = 500L,
                writeTimeoutMs = 500L,
                callTimeoutMs = 1_000L,
            ),
        )

        repository = LocalMarketDataRepository(
            database = database,
            exchangeRateProviderChain = ExchangeRateProviderChain.default(
                httpClientFactory = httpFactory,
                endpoints = endpoints,
                clock = clock,
            ),
            marketQuoteProviderChain = MarketQuoteProviderChain.default(
                httpClientFactory = httpFactory,
                endpoints = endpoints,
                clock = clock,
            ),
            throttlePolicy = RefreshThrottlePolicy(MarketNetworkContract.DEFAULT_REFRESH_THROTTLE_MS),
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
    }

    private suspend fun insertHoldingAsset(
        id: String,
        assetClass: AssetClass,
        symbol: String,
        currency: Currency,
    ): InvestmentAsset {
        val account = Account(
            id = "acc:hold:$id",
            name = "持仓-$symbol",
            type = AccountType.ASSET,
            subType = AccountSubType.INVEST_HOLDING,
            currency = currency,
        )
        database.accountDao().upsert(account.toEntity())
        val asset = InvestmentAsset(
            id = id,
            accountId = account.id,
            assetClass = assetClass,
            symbol = symbol,
            name = "测试-$symbol",
            currency = currency,
        )
        database.investmentAssetDao().upsert(asset.toEntity())
        return asset
    }

    @Test
    fun refreshExchangeRates_persistsThrottlesForcesAndRetainsCacheOnFailure() = runTest {
        var failFx = false
        var usdToCny = "7.20"
        var hkdToCny = "0.90"

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failFx) {
                    return MockResponse().setResponseCode(503)
                }
                return when {
                    request.path!!.startsWith("/api/qt/ulist.np/get") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "data": {
                                "diff": [
                                  {"f12":"USDCNH","f2":$usdToCny},
                                  {"f12":"HKDCNH","f2":$hkdToCny}
                                ]
                              }
                            }
                            """.trimIndent(),
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // 1. First refresh persists all 6 directed pairs and bridges cleanly into ExchangeRateTable
        val first = repository.refreshExchangeRates()
        assertEquals(MarketSyncStatus.UPDATED, first.status)
        assertEquals(6, first.updatedCount)
        assertEquals(fakeNowMs, first.lastSuccessAt)

        val table = repository.observeExchangeRateTable().first()
        assertEquals("7.2", table.requireRate(Currency.USD, Currency.CNY).rateDecimal)
        assertEquals("0.9", table.requireRate(Currency.HKD, Currency.CNY).rateDecimal)
        assertEquals("8", table.requireRate(Currency.USD, Currency.HKD).rateDecimal)
        val requestsAfterFirst = server.requestCount

        // 2. Second call 2 minutes later is throttled with zero additional HTTP calls
        fakeNowMs += 120_000L
        val throttled = repository.refreshExchangeRates()
        assertEquals(MarketSyncStatus.THROTTLED, throttled.status)
        assertEquals(180_000L, throttled.throttleRemainingMs)
        assertEquals(requestsAfterFirst, server.requestCount)

        // 3. Force refresh bypasses throttle and writes updated rates
        usdToCny = "7.25"
        hkdToCny = "0.92"
        val forced = repository.refreshExchangeRates(forceRefresh = true)
        assertEquals(MarketSyncStatus.UPDATED, forced.status)
        assertEquals("7.25", repository.getExchangeRateTable().requireRate(Currency.USD, Currency.CNY).rateDecimal)
        val lastValidTimestamp = fakeNowMs

        // 4. Advance past 5 minutes and simulate total FX outage -> previous cache & fetchedAt stay intact
        fakeNowMs += 400_000L
        failFx = true
        val failed = repository.refreshExchangeRates()
        assertEquals(MarketSyncStatus.FAILED_CACHE_RETAINED, failed.status)
        assertEquals(6, failed.retainedCacheCount)
        assertEquals(lastValidTimestamp, failed.lastSuccessAt)
        assertEquals(3, failed.errors.size)

        val cachedRates = repository.observeExchangeRates().first()
        assertEquals(6, cachedRates.size)
        assertEquals("7.25", repository.getExchangeRateTable().requireRate(Currency.USD, Currency.CNY).rateDecimal)
        assertTrue(cachedRates.all { it.fetchedAt == lastValidTimestamp })

        // 5. Manual exchange rate save bridges immediately into ExchangeRateTable
        repository.saveManualExchangeRate(
            CachedExchangeRate(
                fromCurrency = Currency.USD,
                toCurrency = Currency.CNY,
                rateDecimal = "7.3",
                sourceProvider = MarketNetworkContract.MANUAL_OVERRIDE_PROVIDER_ID,
                fetchedAt = fakeNowMs,
            ),
        )
        assertEquals("7.3", repository.getExchangeRateTable().requireRate(Currency.USD, Currency.CNY).rateDecimal)
    }

    @Test
    fun refreshMarketQuotes_preservesFailedAssetCacheThrottlesPerAssetAndFetchesNewAsset() = runTest {
        insertHoldingAsset("a_cn", AssetClass.CN_STOCK, "600519", Currency.CNY)
        insertHoldingAsset("a_btc", AssetClass.CRYPTO, "BTC", Currency.USD)
        insertHoldingAsset("a_dep", AssetClass.FIXED_DEPOSIT, "DEP-3Y", Currency.CNY)
        insertHoldingAsset("a_bw", AssetClass.BANK_WEALTH, "CMB-W01", Currency.CNY)

        var cnPrice = "1500.00"
        var failBtc = false

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("secid=1.600519") -> MockResponse().setResponseCode(200)
                        .setBody("""{"data":{"f43":$cnPrice,"f60":1480.00}}""")
                    path.contains("secid=1.510300") -> MockResponse().setResponseCode(200)
                        .setBody("""{"data":{"f43":4.20,"f60":4.10}}""")
                    path == "/api/v5/market/ticker?instId=BTC-USDT" && !failBtc -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"code":"0","data":[{"instId":"BTC-USDT","last":"90000.00","open24h":"89000.00"}]}""")
                    else -> MockResponse().setResponseCode(503)
                }
            }
        }

        // 1. Initial sync updates CN stock & BTC, skips deposit & manual bank wealth
        val t0 = fakeNowMs
        val initial = repository.refreshMarketQuotes()
        assertEquals(MarketSyncStatus.UPDATED, initial.status)
        assertEquals(setOf("a_cn", "a_btc"), initial.updatedAssetIds.toSet())
        assertEquals(2, initial.skippedOutcomes.size)
        assertEquals("1500", repository.getMarketQuote("a_cn")!!.latestPrice.canonical)
        assertEquals("90000", repository.getMarketQuote("a_btc")!!.latestPrice.canonical)

        // 2. Add a brand-new ETF 1 minute later -> CN & BTC are throttled, ONLY new ETF is fetched
        fakeNowMs += 60_000L
        insertHoldingAsset("a_etf", AssetClass.ETF, "510300", Currency.CNY)
        val partialNew = repository.refreshMarketQuotes()
        assertEquals(MarketSyncStatus.UPDATED, partialNew.status)
        assertEquals(listOf("a_etf"), partialNew.updatedAssetIds)
        assertEquals(setOf("a_cn", "a_btc"), partialNew.throttledAssetIds.toSet())
        assertEquals("4.2", repository.getMarketQuote("a_etf")!!.latestPrice.canonical)

        // 3. Advance past 5 minutes, make BTC fail all providers while CN stock updates to 1550
        fakeNowMs += 360_000L
        cnPrice = "1550.00"
        failBtc = true

        val partialFail = repository.refreshMarketQuotes()
        assertEquals(MarketSyncStatus.PARTIAL_SUCCESS, partialFail.status)
        assertEquals(setOf("a_cn", "a_etf"), partialFail.updatedAssetIds.toSet())
        assertEquals(listOf("a_btc"), partialFail.failures.map { it.assetId })
        assertEquals(1, partialFail.retainedCacheCountForFailures)

        // CN stock updated; BTC kept exact previous cached price & timestamp!
        assertEquals("1550", repository.getMarketQuote("a_cn")!!.latestPrice.canonical)
        val retainedBtc = repository.getMarketQuote("a_btc")!!
        assertEquals("90000", retainedBtc.latestPrice.canonical)
        assertEquals(t0, retainedBtc.fetchedAt)
    }

    @Test
    fun manualOverrideQuote_protectsDuringNormalAndForceRefreshAndSupportsClear() = runTest {
        insertHoldingAsset("a_cn", AssetClass.CN_STOCK, "600519", Currency.CNY)

        var livePrice = "1520.00"
        var failLive = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failLive) return MockResponse().setResponseCode(500)
                return when {
                    request.path!!.contains("secid=1.600519") -> MockResponse().setResponseCode(200)
                        .setBody("""{"data":{"f43":$livePrice,"f60":1500.00}}""")
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }

        // Seed live quote first (prevClose = 1500)
        val first = repository.refreshMarketQuotes()
        assertEquals(MarketSyncStatus.UPDATED, first.status)
        assertEquals("1520", repository.getMarketQuote("a_cn")!!.latestPrice.canonical)

        // Apply manual override -> preserves prevClose=1500 and computes +10% daily change
        fakeNowMs += 10_000L
        val manual = repository.manualOverrideQuote(assetId = "a_cn", priceDecimal = "1650.00")
        assertTrue(manual.isManualOverride)
        assertEquals(MarketNetworkContract.MANUAL_OVERRIDE_PROVIDER_ID, manual.sourceProvider)
        assertEquals("1650", manual.latestPrice.canonical)
        assertEquals("1500", manual.prevClose?.canonical)
        assertEquals("10", manual.dailyChangePctDecimal)

        // Normal refresh and default forceRefresh must PROTECT the manual override
        livePrice = "1530.00"
        fakeNowMs += 400_000L
        val protectedRefresh = repository.refreshMarketQuotes(forceRefresh = true)
        assertEquals(MarketSyncStatus.SKIPPED_ONLY, protectedRefresh.status)
        assertEquals(listOf("a_cn"), protectedRefresh.preservedManualOverrideAssetIds)
        assertEquals("1650", repository.getMarketQuote("a_cn")!!.latestPrice.canonical)
        assertTrue(repository.getMarketQuote("a_cn")!!.isManualOverride)

        // Explicit clearManualOverride(refreshImmediately = true) replaces manual quote with live quote
        val clearedLive = repository.clearManualOverride("a_cn", refreshImmediately = true)
        assertTrue(clearedLive is QuoteItemOutcome.Success)
        val reloaded = repository.getMarketQuote("a_cn")!!
        assertFalse(reloaded.isManualOverride)
        assertEquals("1530", reloaded.latestPrice.canonical)

        // Manual override again -> clearManualOverride(refreshImmediately = false) deletes manual row
        repository.manualOverrideQuote(assetId = "a_cn", priceDecimal = "1700.00")
        assertNotNull(repository.getMarketQuote("a_cn"))
        assertNull(repository.clearManualOverride("a_cn", refreshImmediately = false))
        assertNull(repository.getMarketQuote("a_cn"))

        // Manual override + clearManualOverride(refreshImmediately = true) when remote fails -> deletes stale manual row
        repository.manualOverrideQuote(assetId = "a_cn", priceDecimal = "1750.00")
        failLive = true
        val failedClear = repository.clearManualOverride("a_cn", refreshImmediately = true)
        assertTrue(failedClear is QuoteItemOutcome.Failure)
        assertNull(repository.getMarketQuote("a_cn"))
    }

    @Test
    fun refreshAll_runsFxAndQuotesTogether() = runTest {
        insertHoldingAsset("a_fund", AssetClass.CN_FUND, "110011", Currency.CNY)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/qt/ulist.np/get") -> MockResponse().setResponseCode(200)
                        .setBody("""{"data":{"diff":[{"f12":"USDCNH","f2":7.2},{"f12":"HKDCNH","f2":0.92}]}}""")
                    path == "/js/110011.js" -> MockResponse().setResponseCode(200)
                        .setBody("""jsonpgz({"dwjz":"3.50","gsz":"3.57","gszzl":"2.00"});""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val report = repository.refreshAll(forceRefresh = true)
        assertEquals(MarketSyncStatus.UPDATED, report.fxSummary.status)
        assertEquals(MarketSyncStatus.UPDATED, report.quoteSummary.status)
        assertEquals("7.2", repository.getExchangeRateTable().requireRate(Currency.USD, Currency.CNY).rateDecimal)
        assertEquals("3.57", repository.observeMarketQuote("a_fund").first()!!.latestPrice.canonical)
    }
}
