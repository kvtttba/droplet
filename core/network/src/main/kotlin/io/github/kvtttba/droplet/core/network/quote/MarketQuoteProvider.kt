package io.github.kvtttba.droplet.core.network.quote

import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.MarketQuote
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.network.MarketNetworkContract
import io.github.kvtttba.droplet.core.network.MarketProviderEndpoints
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.network.NetworkDecimalParser
import io.github.kvtttba.droplet.core.network.ProviderAttemptError
import io.github.kvtttba.droplet.core.network.SymbolNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Single-provider market quote adapter.
 */
interface MarketQuoteProvider {
    val providerId: String

    fun supports(asset: InvestmentAsset): Boolean

    suspend fun fetchQuote(
        asset: InvestmentAsset,
        fetchedAt: Long,
    ): MarketQuote
}

enum class QuoteSkipReason {
    /** Fixed deposits are valued by the local interest accrual engine (M2.2), never over HTTP. */
    LOCAL_CALCULATION_ONLY,

    /** Non-standard bank wealth product or asset explicitly pinned to manual valuation. */
    MANUAL_VALUATION_ONLY,
}

sealed interface QuoteItemOutcome {
    val assetId: String

    data class Success(
        override val assetId: String,
        val quote: MarketQuote,
        val fallbackErrors: List<ProviderAttemptError> = emptyList(),
    ) : QuoteItemOutcome

    data class Skipped(
        override val assetId: String,
        val reason: QuoteSkipReason,
    ) : QuoteItemOutcome

    data class Failure(
        override val assetId: String,
        val errors: List<ProviderAttemptError>,
    ) : QuoteItemOutcome
}

data class MarketQuoteBatchResult(
    val outcomes: List<QuoteItemOutcome>,
    val completedAt: Long,
) {
    val successes: List<QuoteItemOutcome.Success>
        get() = outcomes.filterIsInstance<QuoteItemOutcome.Success>()

    val skipped: List<QuoteItemOutcome.Skipped>
        get() = outcomes.filterIsInstance<QuoteItemOutcome.Skipped>()

    val failures: List<QuoteItemOutcome.Failure>
        get() = outcomes.filterIsInstance<QuoteItemOutcome.Failure>()
}

/**
 * Asset-class-aware quote strategy chain implementing:
 * 1. Domestic direct-connect primary sources -> public fallback sources per [AssetClass]
 * 2. Zero-network skip for [AssetClass.FIXED_DEPOSIT] and non-public [AssetClass.BANK_WEALTH]
 * 3. Optional [InvestmentAsset.quoteProviderHint] provider prioritization
 * 4. Bounded concurrent batch fetching with strict per-asset failure isolation
 */
class MarketQuoteProviderChain(
    private val providersByClass: Map<AssetClass, List<MarketQuoteProvider>>,
    private val clock: NetworkClock = NetworkClock.SYSTEM,
    private val defaultMaxConcurrency: Int = MarketNetworkContract.DEFAULT_MAX_CONCURRENCY,
) {
    init {
        require(defaultMaxConcurrency > 0) {
            "defaultMaxConcurrency must be positive, got $defaultMaxConcurrency"
        }
    }

    /**
     * Returns whether this asset is eligible for remote market quote fetching (returns `false`
     * for [AssetClass.FIXED_DEPOSIT], manual-only [AssetClass.BANK_WEALTH], and assets with
     * `quoteProviderHint == "manual"`).
     */
    fun skipReasonOrNull(asset: InvestmentAsset): QuoteSkipReason? {
        if (asset.assetClass == AssetClass.FIXED_DEPOSIT) {
            return QuoteSkipReason.LOCAL_CALCULATION_ONLY
        }
        val hint = asset.quoteProviderHint?.trim()
        if (hint != null && hint.equals(MarketNetworkContract.MANUAL_OVERRIDE_PROVIDER_ID, ignoreCase = true)) {
            return QuoteSkipReason.MANUAL_VALUATION_ONLY
        }
        if (asset.assetClass == AssetClass.BANK_WEALTH && !SymbolNormalizer.hasPublicBankWealthQuoteCode(asset)) {
            return QuoteSkipReason.MANUAL_VALUATION_ONLY
        }
        return null
    }

    suspend fun fetchQuote(asset: InvestmentAsset): QuoteItemOutcome {
        skipReasonOrNull(asset)?.let { reason ->
            return QuoteItemOutcome.Skipped(assetId = asset.id, reason = reason)
        }

        val candidates = resolveOrderedProviders(asset)
        if (candidates.isEmpty()) {
            return QuoteItemOutcome.Failure(
                assetId = asset.id,
                errors = listOf(
                    ProviderAttemptError(
                        providerId = "router",
                        reason = "No compatible quote provider for ${asset.assetClass} symbol '${asset.symbol}'",
                    ),
                ),
            )
        }

        val fetchedAt = clock.nowEpochMs()
        val errors = mutableListOf<ProviderAttemptError>()

        for (provider in candidates) {
            try {
                val quote = provider.fetchQuote(asset = asset, fetchedAt = fetchedAt)
                return QuoteItemOutcome.Success(
                    assetId = asset.id,
                    quote = quote,
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

        return QuoteItemOutcome.Failure(
            assetId = asset.id,
            errors = errors.toList(),
        )
    }

    suspend fun fetchQuotes(
        assets: List<InvestmentAsset>,
        maxConcurrency: Int = defaultMaxConcurrency,
    ): MarketQuoteBatchResult {
        require(maxConcurrency > 0) { "maxConcurrency must be positive, got $maxConcurrency" }
        if (assets.isEmpty()) {
            return MarketQuoteBatchResult(outcomes = emptyList(), completedAt = clock.nowEpochMs())
        }

        val semaphore = Semaphore(permits = maxConcurrency)
        val outcomes = coroutineScope {
            assets.map { asset ->
                async {
                    if (skipReasonOrNull(asset) != null) {
                        fetchQuote(asset)
                    } else {
                        semaphore.withPermit {
                            fetchQuote(asset)
                        }
                    }
                }
            }.awaitAll()
        }

        return MarketQuoteBatchResult(
            outcomes = outcomes,
            completedAt = clock.nowEpochMs(),
        )
    }

    private fun resolveOrderedProviders(asset: InvestmentAsset): List<MarketQuoteProvider> {
        val eligible = providersByClass[asset.assetClass]
            .orEmpty()
            .filter { it.supports(asset) }

        val hint = asset.quoteProviderHint?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return eligible

        val preferred = eligible.filter { provider ->
            val id = provider.providerId.lowercase()
            id == hint || id.startsWith(hint) || id.contains(hint)
        }
        if (preferred.isEmpty()) {
            return eligible
        }
        val preferredIds = preferred.mapTo(HashSet()) { it.providerId }
        return preferred + eligible.filterNot { it.providerId in preferredIds }
    }

    companion object {
        fun default(
            httpClientFactory: DropletHttpClientFactory,
            endpoints: MarketProviderEndpoints = MarketProviderEndpoints.DEFAULT,
            clock: NetworkClock = NetworkClock.SYSTEM,
            maxConcurrency: Int = MarketNetworkContract.DEFAULT_MAX_CONCURRENCY,
        ): MarketQuoteProviderChain {
            val eastMoneyEquity = EastMoneyEquityQuoteProvider(
                httpClientFactory = httpClientFactory,
                baseUrl = endpoints.eastMoneyPushBaseUrl,
            )
            val tencentQuote = TencentQuoteProvider(
                httpClientFactory = httpClientFactory,
                baseUrl = endpoints.tencentQuoteBaseUrl,
            )
            val sinaQuote = SinaQuoteProvider(
                httpClientFactory = httpClientFactory,
                baseUrl = endpoints.sinaQuoteBaseUrl,
            )
            val yahooFinance = YahooFinanceQuoteProvider(
                httpClientFactory = httpClientFactory,
                baseUrl = endpoints.yahooFinanceBaseUrl,
            )
            val tiantianFund = TiantianFundQuoteProvider(
                httpClientFactory = httpClientFactory,
                baseUrl = endpoints.tiantianFundBaseUrl,
            )
            val eastMoneyFund = EastMoneyFundQuoteProvider(
                httpClientFactory = httpClientFactory,
                baseUrl = endpoints.eastMoneyFundBaseUrl,
            )
            val eastMoneyGold = EastMoneyGoldQuoteProvider(
                httpClientFactory = httpClientFactory,
                baseUrl = endpoints.eastMoneyPushBaseUrl,
            )
            val spotXauGold = SpotXauQuoteProvider(
                httpClientFactory = httpClientFactory,
                baseUrl = endpoints.spotXauBaseUrl,
            )
            val okxCrypto = OkxCryptoQuoteProvider(
                httpClientFactory = httpClientFactory,
                baseUrl = endpoints.okxBaseUrl,
            )
            val binanceCrypto = BinanceCryptoQuoteProvider(
                httpClientFactory = httpClientFactory,
                baseUrl = endpoints.binanceBaseUrl,
            )
            val coinGeckoCrypto = CoinGeckoCryptoQuoteProvider(
                httpClientFactory = httpClientFactory,
                baseUrl = endpoints.coinGeckoBaseUrl,
            )

            val fundProviders = listOf(tiantianFund, eastMoneyFund)

            return MarketQuoteProviderChain(
                providersByClass = mapOf(
                    AssetClass.CN_STOCK to listOf(eastMoneyEquity, tencentQuote, sinaQuote),
                    AssetClass.ETF to listOf(eastMoneyEquity, tencentQuote, sinaQuote),
                    AssetClass.CN_FUND to fundProviders,
                    AssetClass.HK_STOCK to listOf(eastMoneyEquity, tencentQuote, yahooFinance),
                    AssetClass.US_STOCK to listOf(eastMoneyEquity, tencentQuote, yahooFinance),
                    AssetClass.GOLD to listOf(eastMoneyGold, sinaQuote, spotXauGold),
                    AssetClass.CRYPTO to listOf(okxCrypto, binanceCrypto, coinGeckoCrypto),
                    AssetClass.FIXED_DEPOSIT to emptyList(),
                    AssetClass.BANK_WEALTH to fundProviders,
                ),
                clock = clock,
                defaultMaxConcurrency = maxConcurrency,
            )
        }
    }
}
