package io.github.kvtttba.droplet.core.network.fx

import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.network.MarketProviderEndpoints
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.network.NetworkDecimalParser
import io.github.kvtttba.droplet.core.network.NetworkDecimalParser.positiveDecimalOrNull
import io.github.kvtttba.droplet.core.network.NetworkDecimalParser.rawPrimitiveContentOrNull
import io.github.kvtttba.droplet.core.network.ProviderAttemptError
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Remote free public exchange rate provider for Droplet's three supported currencies
 * (CNY / USD / HKD).
 */
interface ExchangeRateProvider {
    val providerId: String

    suspend fun fetchRates(fetchedAt: Long): List<CachedExchangeRate>
}

sealed interface ExchangeRateFetchResult {
    data class Success(
        val rates: List<CachedExchangeRate>,
        val sourceProvider: String,
        val fetchedAt: Long,
        val fallbackErrors: List<ProviderAttemptError> = emptyList(),
    ) : ExchangeRateFetchResult

    data class Failure(
        val errors: List<ProviderAttemptError>,
    ) : ExchangeRateFetchResult
}

/**
 * Builds the complete 6-pair directed FX matrix across CNY / USD / HKD from any valid
 * anchor currency using high-precision BigDecimal arithmetic (scale 16 intermediate,
 * canonical scale 8 output).
 */
internal object ExchangeRateMatrixBuilder {

    private const val INTERMEDIATE_SCALE: Int = 16

    fun fromUsdBase(
        usdToCny: BigDecimal,
        usdToHkd: BigDecimal,
        sourceProvider: String,
        fetchedAt: Long,
    ): List<CachedExchangeRate> {
        require(usdToCny.compareTo(BigDecimal.ZERO) > 0) { "USD->CNY rate must be positive" }
        require(usdToHkd.compareTo(BigDecimal.ZERO) > 0) { "USD->HKD rate must be positive" }

        val cnyToUsd = BigDecimal.ONE.divide(usdToCny, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
        val hkdToUsd = BigDecimal.ONE.divide(usdToHkd, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
        val hkdToCny = usdToCny.divide(usdToHkd, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
        val cnyToHkd = usdToHkd.divide(usdToCny, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)

        return listOf(
            rate(Currency.USD, Currency.CNY, usdToCny, sourceProvider, fetchedAt),
            rate(Currency.CNY, Currency.USD, cnyToUsd, sourceProvider, fetchedAt),
            rate(Currency.USD, Currency.HKD, usdToHkd, sourceProvider, fetchedAt),
            rate(Currency.HKD, Currency.USD, hkdToUsd, sourceProvider, fetchedAt),
            rate(Currency.HKD, Currency.CNY, hkdToCny, sourceProvider, fetchedAt),
            rate(Currency.CNY, Currency.HKD, cnyToHkd, sourceProvider, fetchedAt),
        )
    }

    fun fromCnyQuotes(
        usdToCny: BigDecimal,
        hkdToCny: BigDecimal,
        sourceProvider: String,
        fetchedAt: Long,
    ): List<CachedExchangeRate> {
        require(usdToCny.compareTo(BigDecimal.ZERO) > 0) { "USD->CNY rate must be positive" }
        require(hkdToCny.compareTo(BigDecimal.ZERO) > 0) { "HKD->CNY rate must be positive" }

        val cnyToUsd = BigDecimal.ONE.divide(usdToCny, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
        val cnyToHkd = BigDecimal.ONE.divide(hkdToCny, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
        val usdToHkd = usdToCny.divide(hkdToCny, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
        val hkdToUsd = hkdToCny.divide(usdToCny, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)

        return listOf(
            rate(Currency.USD, Currency.CNY, usdToCny, sourceProvider, fetchedAt),
            rate(Currency.CNY, Currency.USD, cnyToUsd, sourceProvider, fetchedAt),
            rate(Currency.HKD, Currency.CNY, hkdToCny, sourceProvider, fetchedAt),
            rate(Currency.CNY, Currency.HKD, cnyToHkd, sourceProvider, fetchedAt),
            rate(Currency.USD, Currency.HKD, usdToHkd, sourceProvider, fetchedAt),
            rate(Currency.HKD, Currency.USD, hkdToUsd, sourceProvider, fetchedAt),
        )
    }

    fun isCompleteThreeCurrencyMatrix(rates: List<CachedExchangeRate>): Boolean {
        val pairs = rates.map { it.fromCurrency to it.toCurrency }.toSet()
        for (from in Currency.entries) {
            for (to in Currency.entries) {
                if (from != to && (from to to) !in pairs) {
                    return false
                }
            }
        }
        return true
    }

    private fun rate(
        from: Currency,
        to: Currency,
        value: BigDecimal,
        sourceProvider: String,
        fetchedAt: Long,
    ): CachedExchangeRate = CachedExchangeRate(
        fromCurrency = from,
        toCurrency = to,
        rateDecimal = NetworkDecimalParser.canonicalDecimal(value, ExchangeRateSnapshot.SCALE),
        sourceProvider = sourceProvider,
        fetchedAt = fetchedAt,
    )
}

/**
 * Domestic direct-connect primary FX provider using EastMoney public FX spot quotes
 * (USDCNH / HKDCNH / USDCNY / HKDCNY).
 */
class EastMoneyExchangeRateProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_EASTMONEY_PUSH_BASE_URL,
) : ExchangeRateProvider {

    override val providerId: String = PROVIDER_ID

    override suspend fun fetchRates(fetchedAt: Long): List<CachedExchangeRate> {
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/qt/ulist.np/get")
            .addQueryParameter("fltt", "2")
            .addQueryParameter("fields", "f12,f14,f2")
            .addQueryParameter("secids", "133.USDCNH,133.HKDCNH")
            .build()
            .toString()

        val response = httpClientFactory.executeGet(url)
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val root = NetworkDecimalParser.lenientJson.parseToJsonElement(response.body).jsonObject
        val data = root["data"] as? JsonObject
            ?: throw IllegalStateException("Missing 'data' object in EastMoney FX response")
        val diff = data["diff"]
            ?: throw IllegalStateException("Missing 'diff' entries in EastMoney FX response")

        val items: List<JsonObject> = when (diff) {
            is JsonArray -> diff.mapNotNull { it as? JsonObject }
            is JsonObject -> diff.values.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }

        var usdToCny: BigDecimal? = null
        var hkdToCny: BigDecimal? = null
        for (item in items) {
            val code = item["f12"].rawPrimitiveContentOrNull()?.uppercase() ?: continue
            val price = item["f2"].positiveDecimalOrNull() ?: continue
            when {
                code.startsWith("USDCN") -> usdToCny = price
                code.startsWith("HKDCN") -> hkdToCny = price
            }
        }

        val resolvedUsdToCny = usdToCny
            ?: throw IllegalStateException("Missing valid USD->CNY quote in EastMoney FX response")
        val resolvedHkdToCny = hkdToCny
            ?: throw IllegalStateException("Missing valid HKD->CNY quote in EastMoney FX response")

        return ExchangeRateMatrixBuilder.fromCnyQuotes(
            usdToCny = resolvedUsdToCny,
            hkdToCny = resolvedHkdToCny,
            sourceProvider = providerId,
            fetchedAt = fetchedAt,
        )
    }

    companion object {
        const val PROVIDER_ID: String = "eastmoney-fx"
    }
}

/**
 * Fallback #1 FX provider using Frankfurter (ECB public no-key reference rates).
 */
class FrankfurterExchangeRateProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_FRANKFURTER_BASE_URL,
) : ExchangeRateProvider {

    override val providerId: String = PROVIDER_ID

    override suspend fun fetchRates(fetchedAt: Long): List<CachedExchangeRate> {
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("v1/latest")
            .addQueryParameter("base", "USD")
            .addQueryParameter("symbols", "CNY,HKD")
            .build()
            .toString()

        val response = httpClientFactory.executeGet(url)
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val root = NetworkDecimalParser.lenientJson.parseToJsonElement(response.body).jsonObject
        val ratesObj = root["rates"] as? JsonObject
            ?: throw IllegalStateException("Missing 'rates' object in Frankfurter response")

        val usdToCny = ratesObj["CNY"].positiveDecimalOrNull()
            ?: throw IllegalStateException("Missing valid CNY rate in Frankfurter response")
        val usdToHkd = ratesObj["HKD"].positiveDecimalOrNull()
            ?: throw IllegalStateException("Missing valid HKD rate in Frankfurter response")

        return ExchangeRateMatrixBuilder.fromUsdBase(
            usdToCny = usdToCny,
            usdToHkd = usdToHkd,
            sourceProvider = providerId,
            fetchedAt = fetchedAt,
        )
    }

    companion object {
        const val PROVIDER_ID: String = "frankfurter"
    }
}

/**
 * Fallback #2 FX provider using Open ExchangeRate-API (`open.er-api.com`, free no-key tier).
 */
class OpenExchangeRateApiProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_OPEN_ER_API_BASE_URL,
) : ExchangeRateProvider {

    override val providerId: String = PROVIDER_ID

    override suspend fun fetchRates(fetchedAt: Long): List<CachedExchangeRate> {
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("v6/latest/USD")
            .build()
            .toString()

        val response = httpClientFactory.executeGet(url)
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val root = NetworkDecimalParser.lenientJson.parseToJsonElement(response.body).jsonObject
        val resultStatus = root["result"].rawPrimitiveContentOrNull()
        if (resultStatus != null && !resultStatus.equals("success", ignoreCase = true)) {
            throw IllegalStateException("Provider returned non-success result: $resultStatus")
        }
        val ratesObj = root["rates"] as? JsonObject
            ?: throw IllegalStateException("Missing 'rates' object in Open ER API response")

        val usdToCny = ratesObj["CNY"].positiveDecimalOrNull()
            ?: throw IllegalStateException("Missing valid CNY rate in Open ER API response")
        val usdToHkd = ratesObj["HKD"].positiveDecimalOrNull()
            ?: throw IllegalStateException("Missing valid HKD rate in Open ER API response")

        return ExchangeRateMatrixBuilder.fromUsdBase(
            usdToCny = usdToCny,
            usdToHkd = usdToHkd,
            sourceProvider = providerId,
            fetchedAt = fetchedAt,
        )
    }

    companion object {
        const val PROVIDER_ID: String = "open-er-api"
    }
}

/**
 * Primary-to-fallback FX provider chain for CNY / USD / HKD.
 * Tries each configured [ExchangeRateProvider] in priority order, automatically falling back
 * on HTTP errors, timeouts, or malformed payloads.
 */
class ExchangeRateProviderChain(
    private val providers: List<ExchangeRateProvider>,
    private val clock: NetworkClock = NetworkClock.SYSTEM,
) {
    init {
        require(providers.isNotEmpty()) { "ExchangeRateProviderChain requires at least one provider" }
    }

    suspend fun fetchRates(): ExchangeRateFetchResult {
        val fetchedAt = clock.nowEpochMs()
        val errors = mutableListOf<ProviderAttemptError>()

        for (provider in providers) {
            try {
                val rates = provider.fetchRates(fetchedAt)
                if (!ExchangeRateMatrixBuilder.isCompleteThreeCurrencyMatrix(rates)) {
                    errors += ProviderAttemptError(
                        providerId = provider.providerId,
                        reason = "Incomplete currency matrix returned (${rates.size} pairs)",
                    )
                    continue
                }
                return ExchangeRateFetchResult.Success(
                    rates = rates,
                    sourceProvider = provider.providerId,
                    fetchedAt = fetchedAt,
                    fallbackErrors = errors.toList(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errors += ProviderAttemptError(
                    providerId = provider.providerId,
                    reason = NetworkDecimalParser.sanitizeErrorMessage(e),
                )
            }
        }

        return ExchangeRateFetchResult.Failure(errors = errors.toList())
    }

    companion object {
        fun default(
            httpClientFactory: DropletHttpClientFactory,
            endpoints: MarketProviderEndpoints = MarketProviderEndpoints.DEFAULT,
            clock: NetworkClock = NetworkClock.SYSTEM,
        ): ExchangeRateProviderChain = ExchangeRateProviderChain(
            providers = listOf(
                EastMoneyExchangeRateProvider(
                    httpClientFactory = httpClientFactory,
                    baseUrl = endpoints.eastMoneyPushBaseUrl,
                ),
                FrankfurterExchangeRateProvider(
                    httpClientFactory = httpClientFactory,
                    baseUrl = endpoints.frankfurterBaseUrl,
                ),
                OpenExchangeRateApiProvider(
                    httpClientFactory = httpClientFactory,
                    baseUrl = endpoints.openExchangeRateBaseUrl,
                ),
            ),
            clock = clock,
        )
    }
}
