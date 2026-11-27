package io.github.kvtttba.droplet.core.network.quote

import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.MarketQuote
import io.github.kvtttba.droplet.core.model.UnitPrice
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.network.MarketProviderEndpoints
import io.github.kvtttba.droplet.core.network.NetworkDecimalParser
import io.github.kvtttba.droplet.core.network.NetworkDecimalParser.positiveDecimalOrNull
import io.github.kvtttba.droplet.core.network.NetworkDecimalParser.signedDecimalOrNull
import io.github.kvtttba.droplet.core.network.SymbolNormalizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Primary public mutual fund provider using Tiantian Fund (`fundgz.1234567.com.cn`) JSONP
 * real-time valuation + official NAV endpoint. Also handles [AssetClass.BANK_WEALTH] products
 * that carry a valid 6-digit public fund/wealth code.
 */
class TiantianFundQuoteProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_TIANTIAN_FUND_BASE_URL,
) : MarketQuoteProvider {

    override val providerId: String = PROVIDER_ID

    override fun supports(asset: InvestmentAsset): Boolean = when (asset.assetClass) {
        AssetClass.CN_FUND -> SymbolNormalizer.normalizeCnFundCode(asset.symbol) != null
        AssetClass.BANK_WEALTH -> SymbolNormalizer.hasPublicBankWealthQuoteCode(asset)
        else -> false
    }

    override suspend fun fetchQuote(asset: InvestmentAsset, fetchedAt: Long): MarketQuote {
        val code = SymbolNormalizer.normalizeCnFundCode(asset.symbol)
            ?: throw IllegalArgumentException("Invalid CN fund code: ${asset.symbol}")

        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("js")
            .addPathSegment("$code.js")
            .build()
            .toString()

        val response = httpClientFactory.executeGet(url)
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val jsonPayload = unwrapJsonp(response.body)
            ?: throw IllegalStateException("Malformed Tiantian Fund JSONP payload")
        val obj = NetworkDecimalParser.lenientJson.parseToJsonElement(jsonPayload).jsonObject

        val estimatedNav = obj["gsz"].positiveDecimalOrNull()
        val officialNav = obj["dwjz"].positiveDecimalOrNull()
        val latest = estimatedNav ?: officialNav
            ?: throw IllegalStateException("Missing both estimated (gsz) and official (dwjz) NAV")
        val prevClose = if (estimatedNav != null) officialNav else null
        val changePct = if (estimatedNav != null) {
            obj["gszzl"].signedDecimalOrNull()
                ?.let { NetworkDecimalParser.canonicalDecimal(it, ExchangeRateSnapshot.SCALE) }
                ?: NetworkDecimalParser.computeDailyChangePctDecimal(latest, prevClose)
        } else {
            null
        }

        return MarketQuote(
            assetId = asset.id,
            latestPrice = UnitPrice.of(latest),
            prevClose = prevClose?.let { UnitPrice.of(it) },
            dailyChangePctDecimal = changePct,
            priceCurrency = asset.currency,
            sourceProvider = providerId,
            isManualOverride = false,
            fetchedAt = fetchedAt,
        )
    }

    companion object {
        const val PROVIDER_ID: String = "tiantian-fund"

        internal fun unwrapJsonp(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                return trimmed
            }
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return trimmed.substring(start, end + 1)
        }
    }
}

/**
 * Fallback mutual fund & public bank wealth NAV provider using EastMoney official historical NAV
 * API (`api.fund.eastmoney.com/f10/lsjz`).
 */
class EastMoneyFundQuoteProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_EASTMONEY_FUND_BASE_URL,
) : MarketQuoteProvider {

    override val providerId: String = PROVIDER_ID

    override fun supports(asset: InvestmentAsset): Boolean = when (asset.assetClass) {
        AssetClass.CN_FUND -> SymbolNormalizer.normalizeCnFundCode(asset.symbol) != null
        AssetClass.BANK_WEALTH -> SymbolNormalizer.hasPublicBankWealthQuoteCode(asset)
        else -> false
    }

    override suspend fun fetchQuote(asset: InvestmentAsset, fetchedAt: Long): MarketQuote {
        val code = SymbolNormalizer.normalizeCnFundCode(asset.symbol)
            ?: throw IllegalArgumentException("Invalid CN fund code: ${asset.symbol}")

        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("f10/lsjz")
            .addQueryParameter("fundCode", code)
            .addQueryParameter("pageIndex", "1")
            .addQueryParameter("pageSize", "2")
            .build()
            .toString()

        val response = httpClientFactory.executeGet(
            url = url,
            headers = mapOf("Referer" to EASTMONEY_FUND_REFERER),
        )
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val root = NetworkDecimalParser.lenientJson.parseToJsonElement(response.body).jsonObject
        val data = root["Data"] as? JsonObject
            ?: throw IllegalStateException("Missing 'Data' object in EastMoney Fund NAV response")
        val list = data["LSJZList"] as? JsonArray
            ?: throw IllegalStateException("Missing 'LSJZList' in EastMoney Fund NAV response")
        val latestEntry = list.firstOrNull() as? JsonObject
            ?: throw IllegalStateException("Empty 'LSJZList' in EastMoney Fund NAV response")

        val latest = latestEntry["DWJZ"].positiveDecimalOrNull()
            ?: throw IllegalStateException("Missing valid DWJZ in EastMoney Fund NAV response")
        val prevClose = (list.getOrNull(1) as? JsonObject)?.get("DWJZ").positiveDecimalOrNull()
        val changePct = latestEntry["JZZZL"].signedDecimalOrNull()
            ?.let { NetworkDecimalParser.canonicalDecimal(it, ExchangeRateSnapshot.SCALE) }
            ?: NetworkDecimalParser.computeDailyChangePctDecimal(latest, prevClose)

        return MarketQuote(
            assetId = asset.id,
            latestPrice = UnitPrice.of(latest),
            prevClose = prevClose?.let { UnitPrice.of(it) },
            dailyChangePctDecimal = changePct,
            priceCurrency = asset.currency,
            sourceProvider = providerId,
            isManualOverride = false,
            fetchedAt = fetchedAt,
        )
    }

    companion object {
        const val PROVIDER_ID: String = "eastmoney-fund"
        private const val EASTMONEY_FUND_REFERER: String = "https://fundf10.eastmoney.com/"
    }
}

/**
 * Primary precious metals / gold quote provider backed by EastMoney push2 API,
 * supporting domestic SGE gold (AU9999 / AU9995 / Au(T+D) in CNY/g) and international XAU/COMEX in USD/oz.
 */
class EastMoneyGoldQuoteProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_EASTMONEY_PUSH_BASE_URL,
) : MarketQuoteProvider {

    override val providerId: String = PROVIDER_ID

    override fun supports(asset: InvestmentAsset): Boolean =
        asset.assetClass == AssetClass.GOLD &&
            SymbolNormalizer.normalizeGold(asset.symbol, asset.currency) != null

    override suspend fun fetchQuote(asset: InvestmentAsset, fetchedAt: Long): MarketQuote {
        val instrument = SymbolNormalizer.normalizeGold(asset.symbol, asset.currency)
            ?: throw IllegalArgumentException("Invalid gold symbol: ${asset.symbol}")

        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/qt/stock/get")
            .addQueryParameter("fltt", "2")
            .addQueryParameter("fields", "f43,f60,f170,f57,f58")
            .addQueryParameter("secid", instrument.eastMoneySecId)
            .build()
            .toString()

        val response = httpClientFactory.executeGet(url)
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val root = NetworkDecimalParser.lenientJson.parseToJsonElement(response.body).jsonObject
        val data = root["data"] as? JsonObject
            ?: throw IllegalStateException("Missing 'data' object in EastMoney gold quote response")

        val latest = data["f43"].positiveDecimalOrNull()
            ?: throw IllegalStateException("Missing valid gold price (f43) in EastMoney response")
        val prevClose = data["f60"].positiveDecimalOrNull()
        val changePct = data["f170"].signedDecimalOrNull()
            ?.let { NetworkDecimalParser.canonicalDecimal(it, ExchangeRateSnapshot.SCALE) }
            ?: NetworkDecimalParser.computeDailyChangePctDecimal(latest, prevClose)

        return MarketQuote(
            assetId = asset.id,
            latestPrice = UnitPrice.of(latest),
            prevClose = prevClose?.let { UnitPrice.of(it) },
            dailyChangePctDecimal = changePct,
            priceCurrency = instrument.quoteCurrency,
            sourceProvider = providerId,
            isManualOverride = false,
            fetchedAt = fetchedAt,
        )
    }

    companion object {
        const val PROVIDER_ID: String = "eastmoney-gold"
    }
}

/**
 * Fallback international spot gold (XAU, USD/troy oz) provider using public free spot JSON API.
 * Strictly serves non-domestic USD gold symbols so CNY/g SGE gold is never misquoted in USD/oz.
 */
class SpotXauQuoteProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_SPOT_XAU_BASE_URL,
) : MarketQuoteProvider {

    override val providerId: String = PROVIDER_ID

    override fun supports(asset: InvestmentAsset): Boolean {
        if (asset.assetClass != AssetClass.GOLD) return false
        val instrument = SymbolNormalizer.normalizeGold(asset.symbol, asset.currency) ?: return false
        return !instrument.isDomesticCny
    }

    override suspend fun fetchQuote(asset: InvestmentAsset, fetchedAt: Long): MarketQuote {
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("price/XAU")
            .build()
            .toString()

        val response = httpClientFactory.executeGet(url)
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val root = NetworkDecimalParser.lenientJson.parseToJsonElement(response.body).jsonObject
        val latest = root["price"].positiveDecimalOrNull()
            ?: throw IllegalStateException("Missing valid 'price' in Spot XAU response")
        val prevClose = root["prev_close"].positiveDecimalOrNull()
            ?: root["prevClose"].positiveDecimalOrNull()
        val changePct = root["chp"].signedDecimalOrNull()
            ?.let { NetworkDecimalParser.canonicalDecimal(it, ExchangeRateSnapshot.SCALE) }
            ?: NetworkDecimalParser.computeDailyChangePctDecimal(latest, prevClose)

        return MarketQuote(
            assetId = asset.id,
            latestPrice = UnitPrice.of(latest),
            prevClose = prevClose?.let { UnitPrice.of(it) },
            dailyChangePctDecimal = changePct,
            priceCurrency = Currency.USD,
            sourceProvider = providerId,
            isManualOverride = false,
            fetchedAt = fetchedAt,
        )
    }

    companion object {
        const val PROVIDER_ID: String = "spot-xau-gold"
    }
}
