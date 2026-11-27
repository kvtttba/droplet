package io.github.kvtttba.droplet.core.network.quote

import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DepositInterestMode
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.network.HttpClientTimeouts
import io.github.kvtttba.droplet.core.network.MarketProviderEndpoints
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.nio.charset.Charset
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MarketQuoteProviderChainTest {

    private lateinit var server: MockWebServer
    private lateinit var httpFactory: DropletHttpClientFactory

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpFactory = DropletHttpClientFactory(
            timeouts = HttpClientTimeouts(
                connectTimeoutMs = 500L,
                readTimeoutMs = 500L,
                writeTimeoutMs = 500L,
                callTimeoutMs = 1_000L,
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun testEndpoints(): MarketProviderEndpoints {
        val base = server.url("/").toString()
        return MarketProviderEndpoints(
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
    }

    private fun buildChain(nowMs: Long = 1_000_000L): MarketQuoteProviderChain =
        MarketQuoteProviderChain.default(
            httpClientFactory = httpFactory,
            endpoints = testEndpoints(),
            clock = NetworkClock { nowMs },
        )

    private fun asset(
        id: String,
        assetClass: AssetClass,
        symbol: String,
        currency: Currency,
        hint: String? = null,
    ): InvestmentAsset = InvestmentAsset(
        id = id,
        accountId = "acc:hold:$id",
        assetClass = assetClass,
        symbol = symbol,
        name = "Test $symbol",
        currency = currency,
        quoteProviderHint = hint,
    )

    @Test
    fun cnStockAndEtf_primarySuccessAndTencentSinaFallbackWithGbkDecoding() = runTest {
        val gb18030 = Charset.forName("GB18030")
        var sinaRefererSeen: String? = null

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    // 600519 succeeds on primary EastMoney
                    path.contains("secid=1.600519") -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"rc":0,"data":{"f43":1523.50,"f60":1500.00,"f170":1.57}}""")

                    // 510300 fails EastMoney -> succeeds on Tencent GB18030
                    path.contains("secid=1.510300") -> MockResponse().setResponseCode(500)
                    path.startsWith("/q=sh510300") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            Buffer().write(
                                """v_sh510300="1~沪深300ETF~510300~4.125~4.080~4.090~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~1.10";"""
                                    .toByteArray(gb18030),
                            ),
                        )

                    // 000001 fails EastMoney + Tencent -> falls back to Sina GB18030
                    path.contains("secid=0.000001") -> MockResponse().setResponseCode(502)
                    path.startsWith("/q=sz000001") -> MockResponse().setResponseCode(200).setBody("""v_sz000001="";""")
                    path.startsWith("/list=sz000001") -> {
                        sinaRefererSeen = request.getHeader("Referer")
                        MockResponse()
                            .setResponseCode(200)
                            .setBody(
                                Buffer().write(
                                    """var hq_str_sz000001="平安银行,11.20,11.00,11.55,11.60,11.10";"""
                                        .toByteArray(gb18030),
                                ),
                            )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val chain = buildChain()

        val moutai = chain.fetchQuote(asset("cn1", AssetClass.CN_STOCK, "600519", Currency.CNY))
        assertTrue(moutai is QuoteItemOutcome.Success)
        val moutaiQuote = (moutai as QuoteItemOutcome.Success).quote
        assertEquals("1523.5", moutaiQuote.latestPrice.canonical)
        assertEquals("1500", moutaiQuote.prevClose?.canonical)
        assertEquals("1.57", moutaiQuote.dailyChangePctDecimal)
        assertEquals(Currency.CNY, moutaiQuote.priceCurrency)
        assertEquals(EastMoneyEquityQuoteProvider.PROVIDER_ID, moutaiQuote.sourceProvider)

        val etf = chain.fetchQuote(asset("etf1", AssetClass.ETF, "510300", Currency.CNY))
        assertTrue(etf is QuoteItemOutcome.Success)
        val etfSuccess = etf as QuoteItemOutcome.Success
        assertEquals("4.125", etfSuccess.quote.latestPrice.canonical)
        assertEquals("4.08", etfSuccess.quote.prevClose?.canonical)
        assertEquals("1.1", etfSuccess.quote.dailyChangePctDecimal)
        assertEquals(TencentQuoteProvider.PROVIDER_ID, etfSuccess.quote.sourceProvider)
        assertEquals(listOf(EastMoneyEquityQuoteProvider.PROVIDER_ID), etfSuccess.fallbackErrors.map { it.providerId })

        val szStock = chain.fetchQuote(asset("cn2", AssetClass.CN_STOCK, "000001", Currency.CNY))
        assertTrue(szStock is QuoteItemOutcome.Success)
        val szSuccess = szStock as QuoteItemOutcome.Success
        assertEquals("11.55", szSuccess.quote.latestPrice.canonical)
        assertEquals("11", szSuccess.quote.prevClose?.canonical)
        assertEquals("5", szSuccess.quote.dailyChangePctDecimal)
        assertEquals(SinaQuoteProvider.PROVIDER_ID, szSuccess.quote.sourceProvider)
        assertEquals("https://finance.sina.com.cn", sinaRefererSeen)
        assertEquals(
            listOf(EastMoneyEquityQuoteProvider.PROVIDER_ID, TencentQuoteProvider.PROVIDER_ID),
            szSuccess.fallbackErrors.map { it.providerId },
        )
    }

    @Test
    fun cnFundAndPublicBankWealth_tiantianPrimaryAndEastMoneyFallback() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/js/110011.js" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""jsonpgz({"fundcode":"110011","name":"测试优选混合","jzrq":"2026-11-26","dwjz":"4.8520","gsz":"4.9102","gszzl":"1.20"});""")
                    path == "/js/161725.js" -> MockResponse().setResponseCode(503)
                    path.startsWith("/f10/lsjz") && path.contains("fundCode=161725") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "Data": {
                                "LSJZList": [
                                  {"FSRQ":"2026-11-27","DWJZ":"1.0500","JZZZL":"2.94"},
                                  {"FSRQ":"2026-11-26","DWJZ":"1.0200","JZZZL":"-0.50"}
                                ]
                              },
                              "ErrCode": 0
                            }
                            """.trimIndent(),
                        )
                    path == "/js/000198.js" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""jsonpgz({"fundcode":"000198","dwjz":"1.0000","gsz":""});""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val chain = buildChain()

        val fund1 = chain.fetchQuote(asset("f1", AssetClass.CN_FUND, "110011", Currency.CNY)) as QuoteItemOutcome.Success
        assertEquals("4.9102", fund1.quote.latestPrice.canonical)
        assertEquals("4.852", fund1.quote.prevClose?.canonical)
        assertEquals("1.2", fund1.quote.dailyChangePctDecimal)
        assertEquals(TiantianFundQuoteProvider.PROVIDER_ID, fund1.quote.sourceProvider)

        val fund2 = chain.fetchQuote(asset("f2", AssetClass.CN_FUND, "161725", Currency.CNY)) as QuoteItemOutcome.Success
        assertEquals("1.05", fund2.quote.latestPrice.canonical)
        assertEquals("1.02", fund2.quote.prevClose?.canonical)
        assertEquals("2.94", fund2.quote.dailyChangePctDecimal)
        assertEquals(EastMoneyFundQuoteProvider.PROVIDER_ID, fund2.quote.sourceProvider)
        assertEquals(listOf(TiantianFundQuoteProvider.PROVIDER_ID), fund2.fallbackErrors.map { it.providerId })

        // Public 6-digit BANK_WEALTH falls back cleanly to official dwjz when gsz is blank
        val publicWealth = chain.fetchQuote(
            asset("bw_pub", AssetClass.BANK_WEALTH, "000198", Currency.CNY),
        ) as QuoteItemOutcome.Success
        assertEquals("1", publicWealth.quote.latestPrice.canonical)
        assertEquals(TiantianFundQuoteProvider.PROVIDER_ID, publicWealth.quote.sourceProvider)
    }

    @Test
    fun hkAndUsStocks_eastMoneyPrimaryAndYahooFinanceFallback() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    // HK 00700 fails EastMoney + Tencent -> succeeds on Yahoo Finance 0700.HK
                    path.contains("secid=116.00700") -> MockResponse().setResponseCode(500)
                    path.startsWith("/q=hk00700") -> MockResponse().setResponseCode(500)
                    path.startsWith("/v8/finance/chart/0700.HK") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "chart": {
                                "result": [
                                  {
                                    "meta": {
                                      "currency": "HKD",
                                      "symbol": "0700.HK",
                                      "regularMarketPrice": 412.60,
                                      "chartPreviousClose": 400.00
                                    }
                                  }
                                ],
                                "error": null
                              }
                            }
                            """.trimIndent(),
                        )

                    // US AAPL succeeds on EastMoney multi-secid ulist
                    path.startsWith("/api/qt/ulist.np/get") && path.contains("105.AAPL") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "rc": 0,
                              "data": {
                                "diff": {
                                  "0": {"f12": "AAPL", "f2": 228.45, "f18": 225.00, "f3": 1.53}
                                }
                              }
                            }
                            """.trimIndent(),
                        )

                    // US BRK.B falls through EastMoney & Tencent -> Yahoo Finance BRK-B
                    path.startsWith("/api/qt/ulist.np/get") && path.contains("BRK.B") -> MockResponse().setResponseCode(500)
                    path.startsWith("/q=usBRK.B") -> MockResponse().setResponseCode(500)
                    path.startsWith("/v8/finance/chart/BRK-B") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "chart": {
                                "result": [
                                  {
                                    "meta": {
                                      "currency": "USD",
                                      "symbol": "BRK-B",
                                      "regularMarketPrice": 465.25,
                                      "previousClose": 460.00
                                    }
                                  }
                                ]
                              }
                            }
                            """.trimIndent(),
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val chain = buildChain()

        val hk = chain.fetchQuote(asset("hk1", AssetClass.HK_STOCK, "700", Currency.HKD)) as QuoteItemOutcome.Success
        assertEquals("412.6", hk.quote.latestPrice.canonical)
        assertEquals(Currency.HKD, hk.quote.priceCurrency)
        assertEquals("3.15", hk.quote.dailyChangePctDecimal)
        assertEquals(YahooFinanceQuoteProvider.PROVIDER_ID, hk.quote.sourceProvider)

        val aapl = chain.fetchQuote(asset("us1", AssetClass.US_STOCK, "AAPL", Currency.USD)) as QuoteItemOutcome.Success
        assertEquals("228.45", aapl.quote.latestPrice.canonical)
        assertEquals(Currency.USD, aapl.quote.priceCurrency)
        assertEquals(EastMoneyEquityQuoteProvider.PROVIDER_ID, aapl.quote.sourceProvider)

        val brk = chain.fetchQuote(asset("us2", AssetClass.US_STOCK, "BRK.B", Currency.USD)) as QuoteItemOutcome.Success
        assertEquals("465.25", brk.quote.latestPrice.canonical)
        assertEquals(YahooFinanceQuoteProvider.PROVIDER_ID, brk.quote.sourceProvider)
    }

    @Test
    fun gold_domesticSgeAndInternationalXauPreserveCurrencyAndFallbackChains() = runTest {
        var spotXauCalled = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    // Domestic AU9999 fails EastMoney -> falls back to Sina gds_AU9999 in CNY/g
                    path.contains("secid=118.AU9999") -> MockResponse().setResponseCode(500)
                    path.startsWith("/list=gds_AU9999") -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""var hq_str_gds_AU9999="618.50,0,615.00,619.20,620.00,614.00,15:00:00,615.00";""")

                    // International XAU fails EastMoney + Sina -> falls back to Spot XAU in USD/oz
                    path.contains("secid=122.XAU") -> MockResponse().setResponseCode(503)
                    path.startsWith("/list=hf_XAU") -> MockResponse().setResponseCode(503)
                    path == "/price/XAU" -> {
                        spotXauCalled = true
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"name":"Gold","symbol":"XAU","price":2658.40,"prev_close":2640.00}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val chain = buildChain()

        val domestic = chain.fetchQuote(
            asset("gold_cn", AssetClass.GOLD, "AU9999", Currency.CNY),
        ) as QuoteItemOutcome.Success
        assertEquals("618.5", domestic.quote.latestPrice.canonical)
        assertEquals(Currency.CNY, domestic.quote.priceCurrency)
        assertEquals(SinaQuoteProvider.PROVIDER_ID, domestic.quote.sourceProvider)
        assertTrue(!spotXauCalled)

        val intl = chain.fetchQuote(
            asset("gold_xau", AssetClass.GOLD, "XAU", Currency.USD),
        ) as QuoteItemOutcome.Success
        assertEquals("2658.4", intl.quote.latestPrice.canonical)
        assertEquals("2640", intl.quote.prevClose?.canonical)
        assertEquals(Currency.USD, intl.quote.priceCurrency)
        assertEquals(SpotXauQuoteProvider.PROVIDER_ID, intl.quote.sourceProvider)
        assertTrue(spotXauCalled)
    }

    @Test
    fun crypto_okxPrimaryBinanceSecondaryCoinGeckoFallbackAndEightDecimalPrecision() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    // BTC succeeds on OKX
                    path == "/api/v5/market/ticker?instId=BTC-USDT" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"code":"0","data":[{"instId":"BTC-USDT","last":"91234.56789123","open24h":"90000.00"}]}""")

                    // ETH fails OKX -> succeeds on Binance
                    path == "/api/v5/market/ticker?instId=ETH-USDT" -> MockResponse().setResponseCode(502)
                    path == "/api/v3/ticker/24hr?symbol=ETHUSDT" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"symbol":"ETHUSDT","lastPrice":"3456.78","prevClosePrice":"3400.00","priceChangePercent":"1.67"}""")

                    // Micro-priced DOGE fails OKX & Binance -> succeeds on CoinGecko with 8 decimals
                    path == "/api/v5/market/ticker?instId=DOGE-USDT" -> MockResponse().setResponseCode(500)
                    path == "/api/v3/ticker/24hr?symbol=DOGEUSDT" -> MockResponse().setResponseCode(500)
                    path.startsWith("/api/v3/simple/price") && path.contains("ids=dogecoin") -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"dogecoin":{"usd":0.00012345,"usd_24h_change":-2.3456}}""")

                    // USDT skips OKX/Binance cleanly and resolves via CoinGecko tether
                    path.startsWith("/api/v3/simple/price") && path.contains("ids=tether") -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"tether":{"usd":1.0002,"usd_24h_change":0.01}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val chain = buildChain()

        val btc = chain.fetchQuote(asset("c_btc", AssetClass.CRYPTO, "BTC", Currency.USD)) as QuoteItemOutcome.Success
        assertEquals("91234.56789123", btc.quote.latestPrice.canonical)
        assertEquals(OkxCryptoQuoteProvider.PROVIDER_ID, btc.quote.sourceProvider)

        val eth = chain.fetchQuote(asset("c_eth", AssetClass.CRYPTO, "ETH-USDT", Currency.USD)) as QuoteItemOutcome.Success
        assertEquals("3456.78", eth.quote.latestPrice.canonical)
        assertEquals(BinanceCryptoQuoteProvider.PROVIDER_ID, eth.quote.sourceProvider)

        val doge = chain.fetchQuote(asset("c_doge", AssetClass.CRYPTO, "DOGE", Currency.USD)) as QuoteItemOutcome.Success
        assertEquals("0.00012345", doge.quote.latestPrice.canonical)
        assertEquals("-2.3456", doge.quote.dailyChangePctDecimal)
        assertEquals(CoinGeckoCryptoQuoteProvider.PROVIDER_ID, doge.quote.sourceProvider)

        val usdt = chain.fetchQuote(asset("c_usdt", AssetClass.CRYPTO, "USDT", Currency.USD)) as QuoteItemOutcome.Success
        assertEquals("1.0002", usdt.quote.latestPrice.canonical)
        assertTrue(usdt.fallbackErrors.isEmpty())
    }

    @Test
    fun fixedDepositAndManualBankWealth_skippedWithoutAnyHttpCalls() = runTest {
        val chain = buildChain()

        val deposit = InvestmentAsset(
            id = "dep_1",
            accountId = "acc:dep",
            assetClass = AssetClass.FIXED_DEPOSIT,
            symbol = "DEP-3Y",
            name = "三年定存",
            currency = Currency.CNY,
            depositRateAnnualDecimal = "0.0225",
            depositStartAt = 10_000L,
            depositMaturityAt = 90_000L,
            depositInterestMode = DepositInterestMode.SIMPLE_AT_MATURITY,
        )
        val nonPublicWealth = asset("bw_manual", AssetClass.BANK_WEALTH, "CMB-2026A", Currency.CNY)
        val hintManual = asset("cn_manual", AssetClass.CN_STOCK, "600519", Currency.CNY, hint = "manual")

        val depOutcome = chain.fetchQuote(deposit)
        assertEquals(QuoteItemOutcome.Skipped("dep_1", QuoteSkipReason.LOCAL_CALCULATION_ONLY), depOutcome)

        val bwOutcome = chain.fetchQuote(nonPublicWealth)
        assertEquals(QuoteItemOutcome.Skipped("bw_manual", QuoteSkipReason.MANUAL_VALUATION_ONLY), bwOutcome)

        val hintOutcome = chain.fetchQuote(hintManual)
        assertEquals(QuoteItemOutcome.Skipped("cn_manual", QuoteSkipReason.MANUAL_VALUATION_ONLY), hintOutcome)

        assertEquals(0, server.requestCount)
    }

    @Test
    fun quoteProviderHint_prioritizesMatchingProviderWithFallback() = runTest {
        val hitPaths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                hitPaths += path
                return when {
                    path.startsWith("/q=sh600519") -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""v_sh600519="1~贵州茅台~600519~1510.00~1500.00";""")
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }

        val chain = buildChain()
        val hinted = asset("cn_hint", AssetClass.CN_STOCK, "600519", Currency.CNY, hint = "tencent")
        val outcome = chain.fetchQuote(hinted) as QuoteItemOutcome.Success
        assertEquals(TencentQuoteProvider.PROVIDER_ID, outcome.quote.sourceProvider)
        assertEquals("1510", outcome.quote.latestPrice.canonical)
        assertEquals(1, hitPaths.size)
        assertTrue(hitPaths.first().startsWith("/q=sh600519"))
    }

    @Test
    fun batchFetchAcrossAllNineAssetClasses_isolatesPerAssetFailures() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("secid=1.600519") -> MockResponse().setResponseCode(200)
                        .setBody("""{"data":{"f43":1500.00,"f60":1490.00}}""")
                    path.contains("secid=1.510300") -> MockResponse().setResponseCode(200)
                        .setBody("""{"data":{"f43":4.10,"f60":4.00}}""")
                    path == "/js/110011.js" -> MockResponse().setResponseCode(200)
                        .setBody("""jsonpgz({"dwjz":"4.80","gsz":"4.85","gszzl":"1.04"});""")
                    path.contains("secid=116.00700") -> MockResponse().setResponseCode(200)
                        .setBody("""{"data":{"f43":410.00,"f60":405.00}}""")
                    // US_STOCK intentionally fails all providers
                    path.contains("AAPL") -> MockResponse().setResponseCode(503)
                    path.contains("secid=118.AU9999") -> MockResponse().setResponseCode(200)
                        .setBody("""{"data":{"f43":620.00,"f60":615.00}}""")
                    // CRYPTO intentionally fails all providers
                    path.contains("BTC") || path.contains("bitcoin") -> MockResponse().setResponseCode(500)
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }

        val allNineAssets = listOf(
            asset("1_cn", AssetClass.CN_STOCK, "600519", Currency.CNY),
            asset("2_etf", AssetClass.ETF, "510300", Currency.CNY),
            asset("3_fund", AssetClass.CN_FUND, "110011", Currency.CNY),
            asset("4_hk", AssetClass.HK_STOCK, "00700", Currency.HKD),
            asset("5_us_fail", AssetClass.US_STOCK, "AAPL", Currency.USD),
            asset("6_gold", AssetClass.GOLD, "AU9999", Currency.CNY),
            asset("7_crypto_fail", AssetClass.CRYPTO, "BTC", Currency.USD),
            asset("8_deposit", AssetClass.FIXED_DEPOSIT, "DEP-1Y", Currency.CNY),
            asset("9_wealth", AssetClass.BANK_WEALTH, "ICBC-W01", Currency.CNY),
        )

        val batch = buildChain(nowMs = 999_000L).fetchQuotes(allNineAssets, maxConcurrency = 3)
        assertEquals(9, batch.outcomes.size)
        assertEquals(5, batch.successes.size)
        assertEquals(2, batch.skipped.size)
        assertEquals(2, batch.failures.size)

        assertEquals(setOf("1_cn", "2_etf", "3_fund", "4_hk", "6_gold"), batch.successes.map { it.assetId }.toSet())
        assertEquals(setOf("8_deposit", "9_wealth"), batch.skipped.map { it.assetId }.toSet())
        assertEquals(setOf("5_us_fail", "7_crypto_fail"), batch.failures.map { it.assetId }.toSet())
        assertTrue(batch.failures.all { it.errors.size == 3 })
    }
}
