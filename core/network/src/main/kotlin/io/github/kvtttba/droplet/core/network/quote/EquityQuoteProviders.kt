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
import io.github.kvtttba.droplet.core.network.NetworkDecimalParser.rawPrimitiveContentOrNull
import io.github.kvtttba.droplet.core.network.NetworkDecimalParser.signedDecimalOrNull
import io.github.kvtttba.droplet.core.network.SymbolNormalizer
import java.nio.charset.Charset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

internal val CHINESE_LEGACY_CHARSET: Charset =
    runCatching { Charset.forName("GB18030") }.getOrElse { Charsets.UTF_8 }

/**
 * Domestic direct-connect primary quote provider for A-shares, ETFs, HK stocks, and US stocks
 * backed by EastMoney push2 public quote API.
 */
class EastMoneyEquityQuoteProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_EASTMONEY_PUSH_BASE_URL,
) : MarketQuoteProvider {

    override val providerId: String = PROVIDER_ID

    override fun supports(asset: InvestmentAsset): Boolean = when (asset.assetClass) {
        AssetClass.CN_STOCK, AssetClass.ETF ->
            SymbolNormalizer.normalizeCnStockOrEtf(asset.symbol, asset.assetClass) != null
        AssetClass.HK_STOCK ->
            SymbolNormalizer.normalizeHkStock(asset.symbol) != null
        AssetClass.US_STOCK ->
            SymbolNormalizer.normalizeUsStock(asset.symbol) != null
        else -> false
    }

    override suspend fun fetchQuote(asset: InvestmentAsset, fetchedAt: Long): MarketQuote {
        return when (asset.assetClass) {
            AssetClass.CN_STOCK, AssetClass.ETF -> {
                val symbol = SymbolNormalizer.normalizeCnStockOrEtf(asset.symbol, asset.assetClass)
                    ?: throw IllegalArgumentException("Invalid CN stock/ETF symbol: ${asset.symbol}")
                fetchSingleStockGet(
                    asset = asset,
                    secId = symbol.eastMoneySecId,
                    priceCurrency = Currency.CNY,
                    fetchedAt = fetchedAt,
                )
            }
            AssetClass.HK_STOCK -> {
                val symbol = SymbolNormalizer.normalizeHkStock(asset.symbol)
                    ?: throw IllegalArgumentException("Invalid HK stock symbol: ${asset.symbol}")
                fetchSingleStockGet(
                    asset = asset,
                    secId = symbol.eastMoneySecId,
                    priceCurrency = Currency.HKD,
                    fetchedAt = fetchedAt,
                )
            }
            AssetClass.US_STOCK -> {
                val symbol = SymbolNormalizer.normalizeUsStock(asset.symbol)
                    ?: throw IllegalArgumentException("Invalid US stock symbol: ${asset.symbol}")
                fetchUsStockMultiMarket(
                    asset = asset,
                    secIds = symbol.eastMoneyCandidateSecIds,
                    fetchedAt = fetchedAt,
                )
            }
            else -> throw IllegalArgumentException("Unsupported asset class: ${asset.assetClass}")
        }
    }

    private suspend fun fetchSingleStockGet(
        asset: InvestmentAsset,
        secId: String,
        priceCurrency: Currency,
        fetchedAt: Long,
    ): MarketQuote {
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/qt/stock/get")
            .addQueryParameter("fltt", "2")
            .addQueryParameter("fields", "f43,f60,f170,f57,f58")
            .addQueryParameter("secid", secId)
            .build()
            .toString()

        val response = httpClientFactory.executeGet(url)
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val root = NetworkDecimalParser.lenientJson.parseToJsonElement(response.body).jsonObject
        val data = root["data"] as? JsonObject
            ?: throw IllegalStateException("Missing 'data' object in EastMoney quote response")

        val latest = data["f43"].positiveDecimalOrNull()
            ?: throw IllegalStateException("Missing valid latest price (f43) in EastMoney quote response")
        val prevClose = data["f60"].positiveDecimalOrNull()
        val changePct = data["f170"].signedDecimalOrNull()
            ?.let { NetworkDecimalParser.canonicalDecimal(it, ExchangeRateSnapshot.SCALE) }
            ?: NetworkDecimalParser.computeDailyChangePctDecimal(latest, prevClose)

        return MarketQuote(
            assetId = asset.id,
            latestPrice = UnitPrice.of(latest),
            prevClose = prevClose?.let { UnitPrice.of(it) },
            dailyChangePctDecimal = changePct,
            priceCurrency = priceCurrency,
            sourceProvider = providerId,
            isManualOverride = false,
            fetchedAt = fetchedAt,
        )
    }

    private suspend fun fetchUsStockMultiMarket(
        asset: InvestmentAsset,
        secIds: List<String>,
        fetchedAt: Long,
    ): MarketQuote {
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/qt/ulist.np/get")
            .addQueryParameter("fltt", "2")
            .addQueryParameter("fields", "f12,f14,f2,f18,f3")
            .addQueryParameter("secids", secIds.joinToString(","))
            .build()
            .toString()

        val response = httpClientFactory.executeGet(url)
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val root = NetworkDecimalParser.lenientJson.parseToJsonElement(response.body).jsonObject
        val data = root["data"] as? JsonObject
            ?: throw IllegalStateException("Missing 'data' object in EastMoney US quote response")
        val diff = data["diff"]
            ?: throw IllegalStateException("Missing 'diff' entries in EastMoney US quote response")

        val items: List<JsonObject> = when (diff) {
            is JsonArray -> diff.mapNotNull { it as? JsonObject }
            is JsonObject -> diff.values.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }

        val matched = items.firstOrNull { it["f2"].positiveDecimalOrNull() != null }
            ?: throw IllegalStateException("No valid positive US stock price returned by EastMoney")

        val latest = matched["f2"].positiveDecimalOrNull()!!
        val prevClose = matched["f18"].positiveDecimalOrNull()
        val changePct = matched["f3"].signedDecimalOrNull()
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
        const val PROVIDER_ID: String = "eastmoney-stock"
    }
}

/**
 * Domestic direct-connect quote provider backed by Tencent Finance (`qt.gtimg.cn`),
 * covering A-shares, ETFs, HK stocks, and US stocks.
 */
class TencentQuoteProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_TENCENT_QUOTE_BASE_URL,
) : MarketQuoteProvider {

    override val providerId: String = PROVIDER_ID

    override fun supports(asset: InvestmentAsset): Boolean = resolveWireCode(asset) != null

    override suspend fun fetchQuote(asset: InvestmentAsset, fetchedAt: Long): MarketQuote {
        val (wireCode, quoteCurrency) = resolveWireCode(asset)
            ?: throw IllegalArgumentException("Unsupported symbol for Tencent quote: ${asset.symbol}")

        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("q=$wireCode")
            .build()
            .toString()

        val response = httpClientFactory.executeGet(
            url = url,
            fallbackCharset = CHINESE_LEGACY_CHARSET,
        )
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val payload = extractQuotedPayload(response.body)
            ?: throw IllegalStateException("Malformed Tencent quote payload")
        val fields = payload.split('~')
        if (fields.size < 5) {
            throw IllegalStateException("Incomplete Tencent quote fields (size=${fields.size})")
        }

        val latest = NetworkDecimalParser.parsePositiveDecimalOrNull(fields[3])
            ?: throw IllegalStateException("Invalid latest price in Tencent quote payload")
        val prevClose = NetworkDecimalParser.parsePositiveDecimalOrNull(fields[4])
        val changePct = fields.getOrNull(32)
            ?.let { NetworkDecimalParser.parseDecimalOrNull(it) }
            ?.let { NetworkDecimalParser.canonicalDecimal(it, ExchangeRateSnapshot.SCALE) }
            ?: NetworkDecimalParser.computeDailyChangePctDecimal(latest, prevClose)

        return MarketQuote(
            assetId = asset.id,
            latestPrice = UnitPrice.of(latest),
            prevClose = prevClose?.let { UnitPrice.of(it) },
            dailyChangePctDecimal = changePct,
            priceCurrency = quoteCurrency,
            sourceProvider = providerId,
            isManualOverride = false,
            fetchedAt = fetchedAt,
        )
    }

    private fun resolveWireCode(asset: InvestmentAsset): Pair<String, Currency>? = when (asset.assetClass) {
        AssetClass.CN_STOCK, AssetClass.ETF ->
            SymbolNormalizer.normalizeCnStockOrEtf(asset.symbol, asset.assetClass)
                ?.let { it.tencentCode to Currency.CNY }
        AssetClass.HK_STOCK ->
            SymbolNormalizer.normalizeHkStock(asset.symbol)
                ?.let { it.tencentCode to Currency.HKD }
        AssetClass.US_STOCK ->
            SymbolNormalizer.normalizeUsStock(asset.symbol)
                ?.let { it.tencentCode to Currency.USD }
        else -> null
    }

    companion object {
        const val PROVIDER_ID: String = "tencent-quote"
    }
}

/**
 * Fallback domestic quote provider backed by Sina Finance (`hq.sinajs.cn`),
 * covering A-shares, ETFs, and Gold (both SGE AU9999 in CNY and London spot XAU in USD).
 */
class SinaQuoteProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_SINA_QUOTE_BASE_URL,
) : MarketQuoteProvider {

    override val providerId: String = PROVIDER_ID

    override fun supports(asset: InvestmentAsset): Boolean = when (asset.assetClass) {
        AssetClass.CN_STOCK, AssetClass.ETF ->
            SymbolNormalizer.normalizeCnStockOrEtf(asset.symbol, asset.assetClass) != null
        AssetClass.GOLD ->
            SymbolNormalizer.normalizeGold(asset.symbol, asset.currency) != null
        else -> false
    }

    override suspend fun fetchQuote(asset: InvestmentAsset, fetchedAt: Long): MarketQuote {
        val (sinaCode, quoteCurrency, isGold) = when (asset.assetClass) {
            AssetClass.CN_STOCK, AssetClass.ETF -> {
                val normalized = SymbolNormalizer.normalizeCnStockOrEtf(asset.symbol, asset.assetClass)
                    ?: throw IllegalArgumentException("Invalid CN symbol: ${asset.symbol}")
                Triple(normalized.sinaCode, Currency.CNY, false)
            }
            AssetClass.GOLD -> {
                val normalized = SymbolNormalizer.normalizeGold(asset.symbol, asset.currency)
                    ?: throw IllegalArgumentException("Invalid gold symbol: ${asset.symbol}")
                Triple(normalized.sinaCode, normalized.quoteCurrency, true)
            }
            else -> throw IllegalArgumentException("Unsupported asset class for Sina: ${asset.assetClass}")
        }

        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("list=$sinaCode")
            .build()
            .toString()

        val response = httpClientFactory.executeGet(
            url = url,
            headers = mapOf("Referer" to SINA_REFERER),
            fallbackCharset = CHINESE_LEGACY_CHARSET,
        )
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val payload = extractQuotedPayload(response.body)
            ?: throw IllegalStateException("Malformed Sina quote payload")
        val fields = payload.split(',')

        val (latest, prevClose) = if (isGold) {
            // Sina gds_AU9999 / hf_XAU: index 0 = latest, index 7 (or index 2 for gds) = prevClose
            val latestPrice = NetworkDecimalParser.parsePositiveDecimalOrNull(fields.getOrNull(0))
                ?: throw IllegalStateException("Missing latest gold price in Sina payload")
            val prev = NetworkDecimalParser.parsePositiveDecimalOrNull(fields.getOrNull(7))
                ?: NetworkDecimalParser.parsePositiveDecimalOrNull(fields.getOrNull(2))
            latestPrice to prev
        } else {
            // Sina A-share/ETF: index 2 = prevClose, index 3 = latest
            if (fields.size < 4) {
                throw IllegalStateException("Incomplete Sina stock quote fields (size=${fields.size})")
            }
            val prev = NetworkDecimalParser.parsePositiveDecimalOrNull(fields[2])
            val current = NetworkDecimalParser.parsePositiveDecimalOrNull(fields[3]) ?: prev
                ?: throw IllegalStateException("Missing valid price in Sina stock quote payload")
            current to prev
        }

        return MarketQuote(
            assetId = asset.id,
            latestPrice = UnitPrice.of(latest),
            prevClose = prevClose?.let { UnitPrice.of(it) },
            dailyChangePctDecimal = NetworkDecimalParser.computeDailyChangePctDecimal(latest, prevClose),
            priceCurrency = quoteCurrency,
            sourceProvider = providerId,
            isManualOverride = false,
            fetchedAt = fetchedAt,
        )
    }

    companion object {
        const val PROVIDER_ID: String = "sina-quote"
        private const val SINA_REFERER: String = "https://finance.sina.com.cn"
    }
}

/**
 * Overseas fallback quote provider backed by Yahoo Finance public chart API,
 * covering HK stocks and US stocks.
 */
class YahooFinanceQuoteProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_YAHOO_FINANCE_BASE_URL,
) : MarketQuoteProvider {

    override val providerId: String = PROVIDER_ID

    override fun supports(asset: InvestmentAsset): Boolean = resolveYahooSymbol(asset) != null

    override suspend fun fetchQuote(asset: InvestmentAsset, fetchedAt: Long): MarketQuote {
        val (yahooSymbol, defaultCurrency) = resolveYahooSymbol(asset)
            ?: throw IllegalArgumentException("Unsupported symbol for Yahoo Finance: ${asset.symbol}")

        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("v8/finance/chart")
            .addPathSegment(yahooSymbol)
            .addQueryParameter("interval", "1d")
            .addQueryParameter("range", "2d")
            .build()
            .toString()

        val response = httpClientFactory.executeGet(url)
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val root = NetworkDecimalParser.lenientJson.parseToJsonElement(response.body).jsonObject
        val chart = root["chart"] as? JsonObject
            ?: throw IllegalStateException("Missing 'chart' object in Yahoo Finance response")
        val results = chart["result"] as? JsonArray
            ?: throw IllegalStateException("Missing 'result' array in Yahoo Finance response")
        val first = results.firstOrNull() as? JsonObject
            ?: throw IllegalStateException("Empty 'result' array in Yahoo Finance response")
        val meta = first["meta"] as? JsonObject
            ?: throw IllegalStateException("Missing 'meta' object in Yahoo Finance response")

        val latest = meta["regularMarketPrice"].positiveDecimalOrNull()
            ?: throw IllegalStateException("Missing regularMarketPrice in Yahoo Finance response")
        val prevClose = meta["previousClose"].positiveDecimalOrNull()
            ?: meta["chartPreviousClose"].positiveDecimalOrNull()

        val rawCurrency = meta["currency"].rawPrimitiveContentOrNull()
        val resolvedCurrency = rawCurrency?.let { Currency.fromCodeOrNull(it) } ?: defaultCurrency

        return MarketQuote(
            assetId = asset.id,
            latestPrice = UnitPrice.of(latest),
            prevClose = prevClose?.let { UnitPrice.of(it) },
            dailyChangePctDecimal = NetworkDecimalParser.computeDailyChangePctDecimal(latest, prevClose),
            priceCurrency = resolvedCurrency,
            sourceProvider = providerId,
            isManualOverride = false,
            fetchedAt = fetchedAt,
        )
    }

    private fun resolveYahooSymbol(asset: InvestmentAsset): Pair<String, Currency>? = when (asset.assetClass) {
        AssetClass.HK_STOCK ->
            SymbolNormalizer.normalizeHkStock(asset.symbol)?.let { it.yahooSymbol to Currency.HKD }
        AssetClass.US_STOCK ->
            SymbolNormalizer.normalizeUsStock(asset.symbol)?.let { it.yahooSymbol to Currency.USD }
        else -> null
    }

    companion object {
        const val PROVIDER_ID: String = "yahoo-finance"
    }
}

internal fun extractQuotedPayload(rawBody: String): String? {
    val start = rawBody.indexOf('"')
    if (start < 0) return null
    val end = rawBody.indexOf('"', startIndex = start + 1)
    if (end <= start + 1) return null
    return rawBody.substring(start + 1, end).takeIf { it.isNotBlank() }
}
