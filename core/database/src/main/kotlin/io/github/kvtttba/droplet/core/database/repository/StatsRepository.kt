package io.github.kvtttba.droplet.core.database.repository

import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgress
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgressCalculator
import io.github.kvtttba.droplet.core.domain.stats.StatsAggregator
import io.github.kvtttba.droplet.core.domain.stats.StatsQuery
import io.github.kvtttba.droplet.core.domain.stats.StatsReportSnapshot
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class StatsDashboardData(
    val report: StatsReportSnapshot,
    val availableTags: List<Tag> = emptyList(),
    val monthlyBudgetProgress: BudgetProgress? = null,
    val yearlyBudgetProgress: BudgetProgress? = null,
    val accountsById: Map<String, Account> = emptyMap(),
)

/**
 * Reactive statistics repository aggregating full double-entry ledger history, category rankings,
 * historical net-worth series, monthly income/expense calendar, and live budget progress.
 */
interface StatsRepository {

    fun observeStatsDashboard(query: StatsQuery): Flow<StatsDashboardData>

    suspend fun getStatsDashboardSnapshot(query: StatsQuery): StatsDashboardData
}

class LocalStatsRepository(
    private val database: DropletDatabase,
    private val clock: NetworkClock = NetworkClock.SYSTEM,
    private val zoneId: ZoneId = StatsAggregator.DEFAULT_ZONE_ID,
) : StatsRepository {

    private val transactionDao = database.transactionDao()
    private val accountDao = database.accountDao()
    private val tagDao = database.tagDao()
    private val exchangeRateDao = database.exchangeRateDao()
    private val budgetDao = database.budgetDao()

    override fun observeStatsDashboard(query: StatsQuery): Flow<StatsDashboardData> = combine(
        transactionDao.observeAllByStatuses(listOf(TransactionStatus.POSTED)),
        accountDao.observeAll(includeArchived = true, includeSystem = true),
        tagDao.observeAll(includeArchived = true),
        exchangeRateDao.observeAll(),
        budgetDao.observeAll(onlyEnabled = true),
    ) { txDetails, accountEntities, tagEntities, rateEntities, budgetEntities ->
        val transactions = txDetails.map { it.toDomain() }
        val accountsById = accountEntities.associate { it.id to it.toDomain() }
        val allTags = tagEntities.map { it.toDomain() }
        val tagsById = allTags.associateBy { it.id }
        val rateProvider = PlanningFallbackRateProvider(rateEntities.toExchangeRateTable())
        val budgets = budgetEntities.map { it.toDomain() }

        val report = StatsAggregator.aggregate(
            query = query,
            allTransactions = transactions,
            accountsById = accountsById,
            tagsById = tagsById,
            exchangeRateProvider = rateProvider,
            zoneId = zoneId,
        )

        val progresses = if (budgets.isEmpty()) {
            emptyList()
        } else {
            BudgetProgressCalculator.calculateAll(
                budgets = budgets,
                transactions = transactions,
                accountsById = accountsById,
                asOfEpochMs = clock.nowEpochMs(),
                zoneId = zoneId,
                targetMonthOverride = query.targetMonth,
                rateProvider = rateProvider,
            )
        }

        val monthlyOverall = progresses.firstOrNull {
            it.isOverall && it.budget.period == BudgetPeriod.MONTHLY &&
                it.budget.currency == query.currencyFilter.displayCurrency
        } ?: progresses.firstOrNull { it.isOverall && it.budget.period == BudgetPeriod.MONTHLY }

        val yearlyOverall = progresses.firstOrNull {
            it.isOverall && it.budget.period == BudgetPeriod.YEARLY &&
                it.budget.currency == query.currencyFilter.displayCurrency
        } ?: progresses.firstOrNull { it.isOverall && it.budget.period == BudgetPeriod.YEARLY }

        StatsDashboardData(
            report = report,
            availableTags = allTags.filterNot { it.isArchived },
            monthlyBudgetProgress = monthlyOverall,
            yearlyBudgetProgress = yearlyOverall,
            accountsById = accountsById,
        )
    }

    override suspend fun getStatsDashboardSnapshot(query: StatsQuery): StatsDashboardData {
        val transactions = transactionDao.getAllByStatuses(listOf(TransactionStatus.POSTED))
            .map { it.toDomain() }
        val accountsById = accountDao.getAll(includeArchived = true, includeSystem = true)
            .associate { it.id to it.toDomain() }
        val allTags = tagDao.getAll(includeArchived = true).map { it.toDomain() }
        val tagsById = allTags.associateBy { it.id }
        val rateProvider = PlanningFallbackRateProvider(exchangeRateDao.getAll().toExchangeRateTable())
        val budgets = budgetDao.getAll(onlyEnabled = true).map { it.toDomain() }

        val report = StatsAggregator.aggregate(
            query = query,
            allTransactions = transactions,
            accountsById = accountsById,
            tagsById = tagsById,
            exchangeRateProvider = rateProvider,
            zoneId = zoneId,
        )

        val progresses = if (budgets.isEmpty()) {
            emptyList()
        } else {
            BudgetProgressCalculator.calculateAll(
                budgets = budgets,
                transactions = transactions,
                accountsById = accountsById,
                asOfEpochMs = clock.nowEpochMs(),
                zoneId = zoneId,
                targetMonthOverride = query.targetMonth,
                rateProvider = rateProvider,
            )
        }

        val monthlyOverall = progresses.firstOrNull {
            it.isOverall && it.budget.period == BudgetPeriod.MONTHLY &&
                it.budget.currency == query.currencyFilter.displayCurrency
        } ?: progresses.firstOrNull { it.isOverall && it.budget.period == BudgetPeriod.MONTHLY }

        val yearlyOverall = progresses.firstOrNull {
            it.isOverall && it.budget.period == BudgetPeriod.YEARLY &&
                it.budget.currency == query.currencyFilter.displayCurrency
        } ?: progresses.firstOrNull { it.isOverall && it.budget.period == BudgetPeriod.YEARLY }

        return StatsDashboardData(
            report = report,
            availableTags = allTags.filterNot { it.isArchived },
            monthlyBudgetProgress = monthlyOverall,
            yearlyBudgetProgress = yearlyOverall,
            accountsById = accountsById,
        )
    }
}
