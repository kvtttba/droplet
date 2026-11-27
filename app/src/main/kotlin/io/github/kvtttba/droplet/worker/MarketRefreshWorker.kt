package io.github.kvtttba.droplet.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import io.github.kvtttba.droplet.core.database.repository.MarketDataRepository
import io.github.kvtttba.droplet.core.database.repository.MarketSyncReport
import io.github.kvtttba.droplet.core.database.repository.MarketSyncStatus
import io.github.kvtttba.droplet.di.MarketDataEntryPoint
import kotlinx.coroutines.CancellationException

/**
 * Periodic background worker refreshing cached CNY/USD/HKD exchange rates and market quotes.
 *
 * Resolves [MarketDataRepository] via Hilt [EntryPointAccessors] against [MarketDataEntryPoint]
 * so background refreshes reuse the exact same proxy-aware OkHttp client, 5-minute throttle,
 * cache-retention policy, and manual-override protection without introducing `hilt-work`.
 */
class MarketRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = repositoryResolver(applicationContext)
        return try {
            val report = repository.refreshAll(
                forceRefresh = false,
                overwriteManualOverrides = false,
            )
            val outputData = report.toOutputData()
            val fxFailed = report.fxSummary.status == MarketSyncStatus.FAILED_CACHE_RETAINED
            val quotesFailed = report.quoteSummary.status == MarketSyncStatus.FAILED_CACHE_RETAINED
            val anyFreshUpdate = report.fxSummary.status == MarketSyncStatus.UPDATED ||
                report.quoteSummary.status == MarketSyncStatus.UPDATED ||
                report.quoteSummary.status == MarketSyncStatus.PARTIAL_SUCCESS

            if (!anyFreshUpdate && (fxFailed || quotesFailed)) {
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure(outputData)
                }
            } else {
                Result.success(outputData)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun MarketSyncReport.toOutputData(): Data = workDataOf(
        KEY_FX_STATUS to fxSummary.status.name,
        KEY_QUOTE_STATUS to quoteSummary.status.name,
        KEY_UPDATED_QUOTES_COUNT to quoteSummary.updatedAssetIds.size,
        KEY_FAILED_QUOTES_COUNT to quoteSummary.failures.size,
        KEY_COMPLETED_AT to quoteSummary.completedAt,
    )

    companion object {
        const val MAX_RETRY_ATTEMPTS: Int = 3
        const val KEY_FX_STATUS: String = "fx_status"
        const val KEY_QUOTE_STATUS: String = "quote_status"
        const val KEY_UPDATED_QUOTES_COUNT: String = "updated_quotes_count"
        const val KEY_FAILED_QUOTES_COUNT: String = "failed_quotes_count"
        const val KEY_COMPLETED_AT: String = "completed_at"

        internal var repositoryResolver: (Context) -> MarketDataRepository = ::defaultRepositoryResolver

        private fun defaultRepositoryResolver(context: Context): MarketDataRepository =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                MarketDataEntryPoint::class.java,
            ).marketDataRepository()

        internal fun resetRepositoryResolver() {
            repositoryResolver = ::defaultRepositoryResolver
        }
    }
}
