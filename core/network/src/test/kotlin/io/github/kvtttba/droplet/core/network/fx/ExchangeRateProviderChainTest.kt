package io.github.kvtttba.droplet.core.network.fx

import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.network.HttpClientTimeouts
import io.github.kvtttba.droplet.core.network.MarketProviderEndpoints
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.math.BigDecimal
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExchangeRateProviderChainTest {

    private lateinit var server: MockWebServer
    private lateinit var httpFactory: DropletHttpClientFactory

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpFactory = DropletHttpClientFactory(
            timeouts = HttpClientTimeouts(
                connectTimeoutMs = 500L,
                readTimeoutMs = 400L,
                writeTimeoutMs = 500L,
                callTimeoutMs = 600L,
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
        )
    }

    @Test
    fun primaryEastMoneySuccess_buildsExactSixDirectedPairsWithoutFloatDrift() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/qt/ulist.np/get") -> MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "rc": 0,
                          "data": {
                            "diff": [
                              {"f12": "USDCNH", "f14": "美元离岸人民币", "f2": 7.24567891},
                              {"f12": "HKDCNH", "f14": "港币离岸人民币", "f2": 0.93123456}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val chain = ExchangeRateProviderChain.default(
            httpClientFactory = httpFactory,
            endpoints = testEndpoints(),
            clock = NetworkClock { 1_764_240_000_000L },
        )

        val result = chain.fetchRates()
        assertTrue(result is ExchangeRateFetchResult.Success)
        val success = result as ExchangeRateFetchResult.Success
        assertEquals(EastMoneyExchangeRateProvider.PROVIDER_ID, success.sourceProvider)
        assertEquals(1_764_240_000_000L, success.fetchedAt)
        assertTrue(success.fallbackErrors.isEmpty())
        assertEquals(6, success.rates.size)

        val byPair = success.rates.associateBy { it.fromCurrency to it.toCurrency }
        // Exact 8-decimal BigDecimal preservation (no float truncation)
        assertEquals("7.24567891", byPair[Currency.USD to Currency.CNY]!!.rateDecimal)
        assertEquals("0.93123456", byPair[Currency.HKD to Currency.CNY]!!.rateDecimal)

        // Verify derived cross rate USD -> HKD = 7.24567891 / 0.93123456
        val expectedUsdToHkd = BigDecimal("7.24567891")
            .divide(BigDecimal("0.93123456"), 8, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        assertEquals(expectedUsdToHkd, byPair[Currency.USD to Currency.HKD]!!.rateDecimal)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun primaryHttp500_fallsBackToFrankfurterAndRecordsPrimaryError() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/qt/ulist.np/get") ->
                    MockResponse().setResponseCode(500).setBody("upstream error")
                request.path!!.startsWith("/v1/latest") -> MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "amount": 1.0,
                          "base": "USD",
                          "date": "2026-11-27",
                          "rates": {
                            "CNY": 7.2,
                            "HKD": 7.8
                          }
                        }
                        """.trimIndent(),
                    )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val chain = ExchangeRateProviderChain.default(
            httpClientFactory = httpFactory,
            endpoints = testEndpoints(),
            clock = NetworkClock { 200_000L },
        )

        val result = chain.fetchRates()
        assertTrue(result is ExchangeRateFetchResult.Success)
        val success = result as ExchangeRateFetchResult.Success
        assertEquals(FrankfurterExchangeRateProvider.PROVIDER_ID, success.sourceProvider)
        assertEquals(1, success.fallbackErrors.size)
        assertEquals(EastMoneyExchangeRateProvider.PROVIDER_ID, success.fallbackErrors.first().providerId)

        val byPair = success.rates.associateBy { it.fromCurrency to it.toCurrency }
        assertEquals("7.2", byPair[Currency.USD to Currency.CNY]!!.rateDecimal)
        assertEquals("7.8", byPair[Currency.USD to Currency.HKD]!!.rateDecimal)
        // HKD -> CNY = 7.2 / 7.8 = 0.92307692
        assertEquals("0.92307692", byPair[Currency.HKD to Currency.CNY]!!.rateDecimal)
    }

    @Test
    fun primaryTimeoutAndSecondaryMalformedJson_fallsBackToOpenExchangeRateApi() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/qt/ulist.np/get") ->
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                request.path!!.startsWith("/v1/latest") ->
                    MockResponse().setResponseCode(200).setBody("{not-valid-json")
                request.path!!.startsWith("/v6/latest/USD") -> MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "result": "success",
                          "base_code": "USD",
                          "rates": {
                            "USD": 1,
                            "CNY": 7.25,
                            "HKD": 7.75
                          }
                        }
                        """.trimIndent(),
                    )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val chain = ExchangeRateProviderChain.default(
            httpClientFactory = httpFactory,
            endpoints = testEndpoints(),
            clock = NetworkClock { 300_000L },
        )

        val result = chain.fetchRates()
        assertTrue(result is ExchangeRateFetchResult.Success)
        val success = result as ExchangeRateFetchResult.Success
        assertEquals(OpenExchangeRateApiProvider.PROVIDER_ID, success.sourceProvider)
        assertEquals(
            listOf(EastMoneyExchangeRateProvider.PROVIDER_ID, FrankfurterExchangeRateProvider.PROVIDER_ID),
            success.fallbackErrors.map { it.providerId },
        )
        val byPair = success.rates.associateBy { it.fromCurrency to it.toCurrency }
        assertEquals("7.25", byPair[Currency.USD to Currency.CNY]!!.rateDecimal)
        assertEquals("7.75", byPair[Currency.USD to Currency.HKD]!!.rateDecimal)
    }

    @Test
    fun allProvidersFail_returnsCompleteErrorSummary() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/qt/ulist.np/get") ->
                    MockResponse().setResponseCode(200).setBody("""{"data":{"diff":[{"f12":"USDCNH","f2":"-"}]}}""")
                request.path!!.startsWith("/v1/latest") ->
                    MockResponse().setResponseCode(200).setBody("""{"base":"USD","rates":{"HKD":7.8}}""")
                request.path!!.startsWith("/v6/latest/USD") ->
                    MockResponse().setResponseCode(200).setBody("""{"result":"error","error-type":"rate-limited"}""")
                else -> MockResponse().setResponseCode(503)
            }
        }

        val chain = ExchangeRateProviderChain.default(
            httpClientFactory = httpFactory,
            endpoints = testEndpoints(),
            clock = NetworkClock { 400_000L },
        )

        val result = chain.fetchRates()
        assertTrue(result is ExchangeRateFetchResult.Failure)
        val failure = result as ExchangeRateFetchResult.Failure
        assertEquals(
            listOf(
                EastMoneyExchangeRateProvider.PROVIDER_ID,
                FrankfurterExchangeRateProvider.PROVIDER_ID,
                OpenExchangeRateApiProvider.PROVIDER_ID,
            ),
            failure.errors.map { it.providerId },
        )
        assertTrue(failure.errors.all { it.reason.isNotBlank() })
    }
}
