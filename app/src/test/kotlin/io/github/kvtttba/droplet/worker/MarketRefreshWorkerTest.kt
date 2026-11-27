package io.github.kvtttba.droplet.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
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
import io.github.kvtttba.droplet.core.network.quote.QuoteItemOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MarketRefreshWorkerTest {

    private lateinit var context: Context

    private class FakeMarketDataRepository(
        var report: MarketSyncReport,
        var throwError: Exception? = null,
    ) : MarketDataRepository {
        var lastForceRefresh: Boolean? = null
        var lastOverwriteManual: Boolean? = null
        var refreshAllCalls: Int = 0

        override fun observeExchangeRates(): Flow<List<CachedExchangeRate>> = flowOf(emptyList())
        override fun observeExchangeRateTable(): Flow<ExchangeRateTable> = flowOf(ExchangeRateTable.empty())
        override suspend fun getExchangeRateTable(): ExchangeRateTable = ExchangeRateTable.empty()
        override suspend fun saveManualExchangeRate(rate: CachedExchangeRate) = Unit
        override suspend fun refreshExchangeRates(forceRefresh: Boolean): FxRefreshSummary = report.fxSummary
        override fun observeInvestmentAssets(): Flow<List<InvestmentAsset>> = flowOf(emptyList())
        override fun observeMarketQuotes(): Flow<List<MarketQuote>> = flowOf(emptyList())
        override fun observeMarketQuote(assetId: String): Flow<MarketQuote?> = flowOf(null)
        override suspend fun getMarketQuote(assetId: String): MarketQuote? = null
        override suspend fun refreshMarketQuotes(
            assetIds: Set<String>?,
            forceRefresh: Boolean,
            overwriteManualOverrides: Boolean,
        ): QuoteRefreshSummary = report.quoteSummary

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
            refreshAllCalls++
            lastForceRefresh = forceRefresh
            lastOverwriteManual = overwriteManualOverrides
            throwError?.let { throw it }
            return report
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        MarketRefreshWorker.resetRepositoryResolver()
    }

    @Test
    fun doWork_callsThrottledRefreshAllAndReturnsSuccessWithSummaryData() = runTest {
        val fakeRepo = FakeMarketDataRepository(
            report = MarketSyncReport(
                fxSummary = FxRefreshSummary(
                    status = MarketSyncStatus.UPDATED,
                    sourceProvider = "eastmoney",
                    updatedCount = 6,
                    lastSuccessAt = 1_764_216_000_000L,
                ),
                quoteSummary = QuoteRefreshSummary(
                    status = MarketSyncStatus.UPDATED,
                    updatedAssetIds = listOf("asset-1", "asset-2"),
                    completedAt = 1_764_216_001_000L,
                ),
            ),
        )
        MarketRefreshWorker.repositoryResolver = { fakeRepo }

        val worker = TestListenableWorkerBuilder<MarketRefreshWorker>(context).build()
        val result = worker.doWork()

        assertEquals(1, fakeRepo.refreshAllCalls)
        assertFalse(fakeRepo.lastForceRefresh!!)
        assertFalse(fakeRepo.lastOverwriteManual!!)
        assertTrue(result is ListenableWorker.Result.Success)
        val data = (result as ListenableWorker.Result.Success).outputData
        assertEquals(MarketSyncStatus.UPDATED.name, data.getString(MarketRefreshWorker.KEY_FX_STATUS))
        assertEquals(MarketSyncStatus.UPDATED.name, data.getString(MarketRefreshWorker.KEY_QUOTE_STATUS))
        assertEquals(2, data.getInt(MarketRefreshWorker.KEY_UPDATED_QUOTES_COUNT, -1))
        assertEquals(0, data.getInt(MarketRefreshWorker.KEY_FAILED_QUOTES_COUNT, -1))
    }

    @Test
    fun doWork_throttledOrSkipped_returnsSuccessWithoutRetry() = runTest {
        val fakeRepo = FakeMarketDataRepository(
            report = MarketSyncReport(
                fxSummary = FxRefreshSummary(status = MarketSyncStatus.THROTTLED),
                quoteSummary = QuoteRefreshSummary(
                    status = MarketSyncStatus.THROTTLED,
                    throttledAssetIds = listOf("asset-1"),
                    completedAt = 1_764_216_001_000L,
                ),
            ),
        )
        MarketRefreshWorker.repositoryResolver = { fakeRepo }

        val worker = TestListenableWorkerBuilder<MarketRefreshWorker>(context).build()
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun doWork_allFailedWithCacheRetained_retriesThenFailsAfterMaxAttempts() = runTest {
        val fakeRepo = FakeMarketDataRepository(
            report = MarketSyncReport(
                fxSummary = FxRefreshSummary(status = MarketSyncStatus.FAILED_CACHE_RETAINED),
                quoteSummary = QuoteRefreshSummary(
                    status = MarketSyncStatus.FAILED_CACHE_RETAINED,
                    completedAt = 1_764_216_001_000L,
                ),
            ),
        )
        MarketRefreshWorker.repositoryResolver = { fakeRepo }

        val firstAttempt = TestListenableWorkerBuilder<MarketRefreshWorker>(context)
            .setRunAttemptCount(0)
            .build()
        assertEquals(ListenableWorker.Result.retry(), firstAttempt.doWork())

        val exhaustedAttempt = TestListenableWorkerBuilder<MarketRefreshWorker>(context)
            .setRunAttemptCount(MarketRefreshWorker.MAX_RETRY_ATTEMPTS)
            .build()
        assertTrue(exhaustedAttempt.doWork() is ListenableWorker.Result.Failure)
    }

    @Test
    fun doWork_unexpectedException_retriesBeforeFailing() = runTest {
        val fakeRepo = FakeMarketDataRepository(
            report = MarketSyncReport(
                fxSummary = FxRefreshSummary(status = MarketSyncStatus.NO_TARGETS),
                quoteSummary = QuoteRefreshSummary(status = MarketSyncStatus.NO_TARGETS, completedAt = 0L),
            ),
            throwError = IllegalStateException("Transient storage error"),
        )
        MarketRefreshWorker.repositoryResolver = { fakeRepo }

        val worker = TestListenableWorkerBuilder<MarketRefreshWorker>(context)
            .setRunAttemptCount(1)
            .build()
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())

        val finalWorker = TestListenableWorkerBuilder<MarketRefreshWorker>(context)
            .setRunAttemptCount(MarketRefreshWorker.MAX_RETRY_ATTEMPTS)
            .build()
        assertTrue(finalWorker.doWork() is ListenableWorker.Result.Failure)
    }
}
