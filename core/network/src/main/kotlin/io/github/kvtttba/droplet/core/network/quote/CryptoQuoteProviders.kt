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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Primary cryptocurrency quote provider backed by OKX public spot Ticker v5 API.
 * Automatically routes through [DropletHttpClientFactory]'s optional HTTP/SOCKS proxy.
 */
class OkxCryptoQuoteProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_OKX_BASE_URL,
) : MarketQuoteProvider {

    override val providerId: String = PROVIDER_ID

    override fun supports(asset: InvestmentAsset): Boolean {
        if (asset.assetClass != AssetClass.CRYPTO) return false
        val base = SymbolNormalizer.normalizeCryptoBaseSymbol(asset.symbol) ?: return false
        return base != "USDT"
    }

    override suspend fun fetchQuote(asset: InvestmentAsset, fetchedAt: Long): MarketQuote {
        val base = SymbolNormalizer.normalizeCryptoBaseSymbol(asset.symbol)
            ?: throw IllegalArgumentException("Invalid crypto symbol: ${asset.symbol}")

        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/v5/market/ticker")
            .addQueryParameter("instId", "$base-USDT")
            .build()
            .toString()

        val response = httpClientFactory.executeGet(url)
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val root = NetworkDecimalParser.lenientJson.parseToJsonElement(response.body).jsonObject
        val code = root["code"].rawPrimitiveContentOrNull()
        if (code != null && code != "0") {
            throw IllegalStateException("OKX error code=$code")
        }
        val data = root["data"] as? JsonArray
            ?: throw IllegalStateException("Missing 'data' array in OKX ticker response")
        val item = data.firstOrNull() as? JsonObject
            ?: throw IllegalStateException("Empty 'data' array in OKX ticker response")

        val latest = item["last"].positiveDecimalOrNull()
            ?: throw IllegalStateException("Missing valid 'last' price in OKX ticker response")
        val prevClose = item["open24h"].positiveDecimalOrNull()

        return MarketQuote(
            assetId = asset.id,
            latestPrice = UnitPrice.of(latest),
            prevClose = prevClose?.let { UnitPrice.of(it) },
            dailyChangePctDecimal = NetworkDecimalParser.computeDailyChangePctDecimal(latest, prevClose),
            priceCurrency = Currency.USD,
            sourceProvider = providerId,
            isManualOverride = false,
            fetchedAt = fetchedAt,
        )
    }

    companion object {
        const val PROVIDER_ID: String = "okx-crypto"
    }
}

/**
 * Secondary cryptocurrency quote provider backed by Binance public 24hr Ticker v3 API.
 */
class BinanceCryptoQuoteProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_BINANCE_BASE_URL,
) : MarketQuoteProvider {

    override val providerId: String = PROVIDER_ID

    override fun supports(asset: InvestmentAsset): Boolean {
        if (asset.assetClass != AssetClass.CRYPTO) return false
        val base = SymbolNormalizer.normalizeCryptoBaseSymbol(asset.symbol) ?: return false
        return base != "USDT"
    }

    override suspend fun fetchQuote(asset: InvestmentAsset, fetchedAt: Long): MarketQuote {
        val base = SymbolNormalizer.normalizeCryptoBaseSymbol(asset.symbol)
            ?: throw IllegalArgumentException("Invalid crypto symbol: ${asset.symbol}")

        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/v3/ticker/24hr")
            .addQueryParameter("symbol", "${base}USDT")
            .build()
            .toString()

        val response = httpClientFactory.executeGet(url)
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val root = NetworkDecimalParser.lenientJson.parseToJsonElement(response.body).jsonObject
        val latest = root["lastPrice"].positiveDecimalOrNull()
            ?: throw IllegalStateException("Missing valid 'lastPrice' in Binance ticker response")
        val prevClose = root["prevClosePrice"].positiveDecimalOrNull()
            ?: root["openPrice"].positiveDecimalOrNull()
        val changePct = root["priceChangePercent"].signedDecimalOrNull()
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
        const val PROVIDER_ID: String = "binance-crypto"
    }
}

/**
 * Tertiary cryptocurrency fallback provider backed by CoinGecko public simple/price v3 API.
 * Supports all coins including stablecoins like USDT.
 */
class CoinGeckoCryptoQuoteProvider(
    private val httpClientFactory: DropletHttpClientFactory,
    private val baseUrl: String = MarketProviderEndpoints.DEFAULT_COINGECKO_BASE_URL,
) : MarketQuoteProvider {

    override val providerId: String = PROVIDER_ID

    override fun supports(asset: InvestmentAsset): Boolean =
        asset.assetClass == AssetClass.CRYPTO &&
            SymbolNormalizer.normalizeCryptoBaseSymbol(asset.symbol) != null

    override suspend fun fetchQuote(asset: InvestmentAsset, fetchedAt: Long): MarketQuote {
        val base = SymbolNormalizer.normalizeCryptoBaseSymbol(asset.symbol)
            ?: throw IllegalArgumentException("Invalid crypto symbol: ${asset.symbol}")
        val coinId = SymbolNormalizer.coinGeckoIdFor(base)

        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/v3/simple/price")
            .addQueryParameter("ids", coinId)
            .addQueryParameter("vs_currencies", "usd")
            .addQueryParameter("include_24hr_change", "true")
            .build()
            .toString()

        val response = httpClientFactory.executeGet(url)
        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.statusCode}")
        }

        val root = NetworkDecimalParser.lenientJson.parseToJsonElement(response.body).jsonObject
        val entry = root[coinId] as? JsonObject
            ?: throw IllegalStateException("Missing coin '$coinId' in CoinGecko response")
        val latest = entry["usd"].positiveDecimalOrNull()
            ?: throw IllegalStateException("Missing valid USD price for '$coinId' in CoinGecko response")
        val changePct = entry["usd_24h_change"].signedDecimalOrNull()
            ?.let { NetworkDecimalParser.canonicalDecimal(it, ExchangeRateSnapshot.SCALE) }

        return MarketQuote(
            assetId = asset.id,
            latestPrice = UnitPrice.of(latest),
            prevClose = null,
            dailyChangePctDecimal = changePct,
            priceCurrency = Currency.USD,
            sourceProvider = providerId,
            isManualOverride = false,
            fetchedAt = fetchedAt,
        )
    }

    companion object {
        const val PROVIDER_ID: String = "coingecko-crypto"
    }
}
