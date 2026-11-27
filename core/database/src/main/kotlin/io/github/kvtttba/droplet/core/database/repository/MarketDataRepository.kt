package io.github.kvtttba.droplet.core.database.repository

import androidx.room.withTransaction
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.entity.ExchangeRateEntity
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.MarketQuote
import io.github.kvtttba.droplet.core.model.UnitPrice
import io.github.kvtttba.droplet.core.network.MarketNetworkContract
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.network.ProviderAttemptError
import io.github.kvtttba.droplet.core.network.RefreshThrottlePolicy
import io.github.kvtttba.droplet.core.network.ThrottleDecision
import io.github.kvtttba.droplet.core.network.fx.ExchangeRateFetchResult
import io.github.kvtttba.droplet.core.network.fx.ExchangeRateProviderChain
import io.github.kvtttba.droplet.core.network.quote.MarketQuoteProviderChain
import io.github.kvtttba.droplet.core.network.quote.QuoteItemOutcome
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MarketSyncStatus {
    UPDATED,
    PARTIAL_SUCCESS,
    THROTTLED,
    SKIPPED_ONLY,
    NO_TARGETS,
    FAILED_CACHE_RETAINED,
}

data class FxRefreshSummary(
    val status: MarketSyncStatus,
    val sourceProvider: String? = null,
    val updatedCount: Int = 0,
    val retainedCacheCount: Int = 0,
    val lastSuccessAt: Long? = null,
    val throttleRemainingMs: Long = 0L,
    val fallbackErrors: List<ProviderAttemptError> = emptyList(),
    val errors: List<ProviderAttemptError> = emptyList(),
)

data class QuoteRefreshSummary(
    val status: MarketSyncStatus,
    val updatedAssetIds: List<String> = emptyList(),
    val skippedOutcomes: List<QuoteItemOutcome.Skipped> = emptyList(),
    val preservedManualOverrideAssetIds: List<String> = emptyList(),
    val throttledAssetIds: List<String> = emptyList(),
    val failures: List<QuoteItemOutcome.Failure> = emptyList(),
    val retainedCacheCountForFailures: Int = 0,
    val completedAt: Long,
)

data class MarketSyncReport(
    val fxSummary: FxRefreshSummary,
    val quoteSummary: QuoteRefreshSummary,
)

/**
 * Coordinates live FX & market quote provider chains with Room's independent `exchange_rates`
 * and `market_quotes` cache tables, enforcing:
 * - 5-minute default throttling with `forceRefresh` override
 * - Atomic transactional persistence
 * - Full cache preservation on network/provider failures
 * - Protection and management of manual quote overrides
 */
interface MarketDataRepository {

    fun observeExchangeRates(): Flow<List<CachedExchangeRate>>

    fun observeExchangeRateTable(): Flow<ExchangeRateTable>

    suspend fun getExchangeRateTable(): ExchangeRateTable

    suspend fun saveManualExchangeRate(rate: CachedExchangeRate)

    suspend fun refreshExchangeRates(forceRefresh: Boolean = false): FxRefreshSummary

    fun observeInvestmentAssets(): Flow<List<InvestmentAsset>>

    fun observeMarketQuotes(): Flow<List<MarketQuote>>

    fun observeMarketQuote(assetId: String): Flow<MarketQuote?>

    suspend fun getMarketQuote(assetId: String): MarketQuote?

    suspend fun refreshMarketQuotes(
        assetIds: Set<String>? = null,
        forceRefresh: Boolean = false,
        overwriteManualOverrides: Boolean = false,
    ): QuoteRefreshSummary

    suspend fun manualOverrideQuote(
        assetId: String,
        priceDecimal: String,
        currency: Currency? = null,
        prevCloseDecimal: String? = null,
        nowEpochMs: Long? = null,
    ): MarketQuote

    suspend fun clearManualOverride(
        assetId: String,
        refreshImmediately: Boolean = false,
    ): QuoteItemOutcome?

    suspend fun refreshAll(
        forceRefresh: Boolean = false,
        overwriteManualOverrides: Boolean = false,
    ): MarketSyncReport
}

class LocalMarketDataRepository(
    private val database: DropletDatabase,
    private val exchangeRateProviderChain: ExchangeRateProviderChain,
    private val marketQuoteProviderChain: MarketQuoteProviderChain,
    private val throttlePolicy: RefreshThrottlePolicy = RefreshThrottlePolicy(),
    private val clock: NetworkClock = NetworkClock.SYSTEM,
) : MarketDataRepository {

    private val exchangeRateDao = database.exchangeRateDao()
    private val marketQuoteDao = database.marketQuoteDao()
    private val investmentAssetDao = database.investmentAssetDao()

    private val fxRefreshMutex = Mutex()
    private val quoteRefreshMutex = Mutex()

    @Volatile
    private var lastFxRemoteSuccessAtMs: Long? = null

    override fun observeExchangeRates(): Flow<List<CachedExchangeRate>> =
        exchangeRateDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeExchangeRateTable(): Flow<ExchangeRateTable> =
        exchangeRateDao.observeAll().map { it.toExchangeRateTable() }

    override suspend fun getExchangeRateTable(): ExchangeRateTable =
        exchangeRateDao.getAll().toExchangeRateTable()

    override suspend fun saveManualExchangeRate(rate: CachedExchangeRate) {
        exchangeRateDao.upsert(rate.toEntity())
    }

    override suspend fun refreshExchangeRates(forceRefresh: Boolean): FxRefreshSummary =
        fxRefreshMutex.withLock {
            val now = clock.nowEpochMs()
            val existing = exchangeRateDao.getAll()
            val hasCompleteMatrix = hasCompleteThreeCurrencyMatrix(existing)
            val persistedRemoteFetchedAt = existing
                .takeIf { hasCompleteMatrix }
                ?.filterNot { it.sourceProvider == MarketNetworkContract.MANUAL_OVERRIDE_PROVIDER_ID }
                ?.maxOfOrNull { it.fetchedAt }

            val lastSuccessAt = listOfNotNull(lastFxRemoteSuccessAtMs, persistedRemoteFetchedAt).maxOrNull()

            if (hasCompleteMatrix) {
                when (val decision = throttlePolicy.evaluate(lastSuccessAt, now, forceRefresh)) {
                    is ThrottleDecision.Throttled -> {
                        return@withLock FxRefreshSummary(
                            status = MarketSyncStatus.THROTTLED,
                            updatedCount = 0,
                            retainedCacheCount = existing.size,
                            lastSuccessAt = decision.lastSuccessAtMs,
                            throttleRemainingMs = decision.remainingMs,
                        )
                    }
                    ThrottleDecision.Allow -> Unit
                }
            }

            when (val result = exchangeRateProviderChain.fetchRates()) {
                is ExchangeRateFetchResult.Success -> {
                    database.withTransaction {
                        exchangeRateDao.upsertAll(result.rates.map { it.toEntity() })
                    }
                    lastFxRemoteSuccessAtMs = result.fetchedAt
                    FxRefreshSummary(
                        status = MarketSyncStatus.UPDATED,
                        sourceProvider = result.sourceProvider,
                        updatedCount = result.rates.size,
                        retainedCacheCount = result.rates.size,
                        lastSuccessAt = result.fetchedAt,
                        fallbackErrors = result.fallbackErrors,
                    )
                }
                is ExchangeRateFetchResult.Failure -> {
                    // Explicitly retain every previously cached exchange rate untouched.
                    FxRefreshSummary(
                        status = MarketSyncStatus.FAILED_CACHE_RETAINED,
                        updatedCount = 0,
                        retainedCacheCount = existing.size,
                        lastSuccessAt = existing.maxOfOrNull { it.fetchedAt },
                        errors = result.errors,
                    )
                }
            }
        }

    override fun observeInvestmentAssets(): Flow<List<InvestmentAsset>> =
        investmentAssetDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeMarketQuotes(): Flow<List<MarketQuote>> =
        marketQuoteDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeMarketQuote(assetId: String): Flow<MarketQuote?> =
        marketQuoteDao.observeByAssetId(assetId).map { it?.toDomain() }

    override suspend fun getMarketQuote(assetId: String): MarketQuote? =
        marketQuoteDao.getByAssetId(assetId)?.toDomain()

    override suspend fun refreshMarketQuotes(
        assetIds: Set<String>?,
        forceRefresh: Boolean,
        overwriteManualOverrides: Boolean,
    ): QuoteRefreshSummary = quoteRefreshMutex.withLock {
        val now = clock.nowEpochMs()
        val allAssets = investmentAssetDao.getAll().map { it.toDomain() }
        val targetAssets = if (assetIds == null) {
            allAssets
        } else {
            allAssets.filter { it.id in assetIds }
        }

        if (targetAssets.isEmpty()) {
            return@withLock QuoteRefreshSummary(
                status = MarketSyncStatus.NO_TARGETS,
                completedAt = now,
            )
        }

        val existingQuotes = marketQuoteDao.getAll()
            .associate { it.assetId to it.toDomain() }

        val skippedOutcomes = mutableListOf<QuoteItemOutcome.Skipped>()
        val preservedManualIds = mutableListOf<String>()
        val throttledIds = mutableListOf<String>()
        val toFetch = mutableListOf<InvestmentAsset>()

        for (asset in targetAssets) {
            val skipReason = marketQuoteProviderChain.skipReasonOrNull(asset)
            if (skipReason != null) {
                skippedOutcomes += QuoteItemOutcome.Skipped(assetId = asset.id, reason = skipReason)
                continue
            }

            val existing = existingQuotes[asset.id]
            if (existing != null && existing.isManualOverride && !overwriteManualOverrides) {
                preservedManualIds += asset.id
                continue
            }

            val lastRemoteFetchedAt = existing?.takeIf { !it.isManualOverride }?.fetchedAt
            when (throttlePolicy.evaluate(lastRemoteFetchedAt, now, forceRefresh)) {
                is ThrottleDecision.Throttled -> {
                    throttledIds += asset.id
                }
                ThrottleDecision.Allow -> {
                    toFetch += asset
                }
            }
        }

        if (toFetch.isEmpty()) {
            val status = when {
                throttledIds.isNotEmpty() -> MarketSyncStatus.THROTTLED
                else -> MarketSyncStatus.SKIPPED_ONLY
            }
            return@withLock QuoteRefreshSummary(
                status = status,
                skippedOutcomes = skippedOutcomes,
                preservedManualOverrideAssetIds = preservedManualIds,
                throttledAssetIds = throttledIds,
                completedAt = clock.nowEpochMs(),
            )
        }

        val batch = marketQuoteProviderChain.fetchQuotes(toFetch)
        val successfulQuotes = batch.successes.map { it.quote }
        if (successfulQuotes.isNotEmpty()) {
            database.withTransaction {
                marketQuoteDao.upsertAll(successfulQuotes.map { it.toEntity() })
            }
        }

        val allSkipped = skippedOutcomes + batch.skipped
        val failures = batch.failures
        val retainedForFailures = failures.count { existingQuotes.containsKey(it.assetId) }

        val status = when {
            successfulQuotes.isNotEmpty() && failures.isEmpty() -> MarketSyncStatus.UPDATED
            successfulQuotes.isNotEmpty() && failures.isNotEmpty() -> MarketSyncStatus.PARTIAL_SUCCESS
            failures.isNotEmpty() -> MarketSyncStatus.FAILED_CACHE_RETAINED
            throttledIds.isNotEmpty() -> MarketSyncStatus.THROTTLED
            else -> MarketSyncStatus.SKIPPED_ONLY
        }

        QuoteRefreshSummary(
            status = status,
            updatedAssetIds = successfulQuotes.map { it.assetId },
            skippedOutcomes = allSkipped,
            preservedManualOverrideAssetIds = preservedManualIds,
            throttledAssetIds = throttledIds,
            failures = failures,
            retainedCacheCountForFailures = retainedForFailures,
            completedAt = batch.completedAt,
        )
    }

    override suspend fun manualOverrideQuote(
        assetId: String,
        priceDecimal: String,
        currency: Currency?,
        prevCloseDecimal: String?,
        nowEpochMs: Long?,
    ): MarketQuote {
        val asset = investmentAssetDao.getById(assetId)?.toDomain()
            ?: throw IllegalArgumentException("Unknown investment asset id: '$assetId'")
        val latestPrice = UnitPrice.of(priceDecimal)
        require(latestPrice.isPositive) {
            "Manual override price must be strictly positive, got '$priceDecimal'"
        }

        val resolvedCurrency = currency ?: asset.currency
        val existing = marketQuoteDao.getByAssetId(assetId)?.toDomain()
        val resolvedPrevClose = when {
            prevCloseDecimal != null -> UnitPrice.of(prevCloseDecimal).also {
                require(it.isPositive) {
                    "prevCloseDecimal must be strictly positive, got '$prevCloseDecimal'"
                }
            }
            existing != null && existing.priceCurrency == resolvedCurrency -> existing.prevClose
            else -> null
        }

        val changePct = computeDailyChangePctDecimal(
            latest = latestPrice.toBigDecimal(),
            prevClose = resolvedPrevClose?.toBigDecimal(),
        )

        val quote = MarketQuote(
            assetId = assetId,
            latestPrice = latestPrice,
            prevClose = resolvedPrevClose,
            dailyChangePctDecimal = changePct,
            priceCurrency = resolvedCurrency,
            sourceProvider = MarketNetworkContract.MANUAL_OVERRIDE_PROVIDER_ID,
            isManualOverride = true,
            fetchedAt = nowEpochMs ?: clock.nowEpochMs(),
        )
        marketQuoteDao.upsert(quote.toEntity())
        return quote
    }

    override suspend fun clearManualOverride(
        assetId: String,
        refreshImmediately: Boolean,
    ): QuoteItemOutcome? {
        val existing = marketQuoteDao.getByAssetId(assetId)?.toDomain()
        if (existing == null || !existing.isManualOverride) {
            return null
        }
        if (!refreshImmediately) {
            marketQuoteDao.deleteByAssetId(assetId)
            return null
        }

        val summary = refreshMarketQuotes(
            assetIds = setOf(assetId),
            forceRefresh = true,
            overwriteManualOverrides = true,
        )
        val refreshed = marketQuoteDao.getByAssetId(assetId)?.toDomain()
        if (refreshed != null && !refreshed.isManualOverride) {
            return QuoteItemOutcome.Success(assetId = assetId, quote = refreshed)
        }

        // Remote refresh failed or asset is local/manual-only: clear the manual override row.
        marketQuoteDao.deleteByAssetId(assetId)
        return summary.failures.firstOrNull { it.assetId == assetId }
            ?: summary.skippedOutcomes.firstOrNull { it.assetId == assetId }
    }

    override suspend fun refreshAll(
        forceRefresh: Boolean,
        overwriteManualOverrides: Boolean,
    ): MarketSyncReport = coroutineScope {
        val fxDeferred = async { refreshExchangeRates(forceRefresh = forceRefresh) }
        val quotesDeferred = async {
            refreshMarketQuotes(
                forceRefresh = forceRefresh,
                overwriteManualOverrides = overwriteManualOverrides,
            )
        }
        MarketSyncReport(
            fxSummary = fxDeferred.await(),
            quoteSummary = quotesDeferred.await(),
        )
    }

    private fun hasCompleteThreeCurrencyMatrix(rates: List<ExchangeRateEntity>): Boolean {
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

    private fun computeDailyChangePctDecimal(
        latest: BigDecimal,
        prevClose: BigDecimal?,
    ): String? {
        if (prevClose == null || prevClose.compareTo(BigDecimal.ZERO) <= 0) {
            return null
        }
        val pct = latest.subtract(prevClose)
            .multiply(HUNDRED)
            .divide(prevClose, 4, RoundingMode.HALF_UP)
        val scaled = if (pct.scale() > ExchangeRateSnapshot.SCALE) {
            pct.setScale(ExchangeRateSnapshot.SCALE, RoundingMode.HALF_UP)
        } else {
            pct
        }
        return if (scaled.compareTo(BigDecimal.ZERO) == 0) {
            "0"
        } else {
            scaled.stripTrailingZeros().toPlainString()
        }
    }

    companion object {
        private val HUNDRED: BigDecimal = BigDecimal("100")
    }
}
