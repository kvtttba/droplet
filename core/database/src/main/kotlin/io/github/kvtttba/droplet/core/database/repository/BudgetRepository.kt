package io.github.kvtttba.droplet.core.database.repository

import androidx.room.withTransaction
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.domain.automation.BudgetAlertLevel
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgress
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgressCalculator
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Budget
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class BudgetOverview(
    val targetMonth: YearMonth,
    val progresses: List<BudgetProgress> = emptyList(),
) {
    val monthlyProgresses: List<BudgetProgress>
        get() = progresses.filter { it.budget.period == BudgetPeriod.MONTHLY }

    val yearlyProgresses: List<BudgetProgress>
        get() = progresses.filter { it.budget.period == BudgetPeriod.YEARLY }

    val monthlyOverall: BudgetProgress?
        get() = monthlyProgresses.firstOrNull { it.isOverall }

    val monthlyCategoryBudgets: List<BudgetProgress>
        get() = monthlyProgresses.filterNot { it.isOverall }

    val yearlyOverall: BudgetProgress?
        get() = yearlyProgresses.firstOrNull { it.isOverall }

    val yearlyCategoryBudgets: List<BudgetProgress>
        get() = yearlyProgresses.filterNot { it.isOverall }

    val enabledCount: Int
        get() = progresses.count { it.budget.isEnabled }

    val warningCount: Int
        get() = progresses.count { it.budget.isEnabled && it.alertLevel == BudgetAlertLevel.WARNING }

    val exceededCount: Int
        get() = progresses.count { it.budget.isEnabled && it.alertLevel == BudgetAlertLevel.EXCEEDED }
}

data class SaveBudgetRequest(
    val id: String? = null,
    val period: BudgetPeriod,
    val categoryAccountId: String? = null,
    val currency: Currency = Currency.DEFAULT,
    val limitAmountMinor: Long,
    val warnThresholdPct: Int = 80,
    val isEnabled: Boolean = true,
)

/**
 * Repository managing monthly/yearly overall and category budgets with real-time
 * reactive spending progress aggregated from double-entry ledger postings.
 */
interface BudgetRepository {

    fun observeBudgetOverview(
        targetMonth: YearMonth? = null,
        includeDisabled: Boolean = true,
    ): Flow<BudgetOverview>

    fun observeMonthlyOverallBudgetProgress(
        month: YearMonth,
        currency: Currency = Currency.DEFAULT,
    ): Flow<BudgetProgress?>

    suspend fun getBudgetOverviewSnapshot(
        asOfEpochMs: Long? = null,
        onlyEnabled: Boolean = true,
    ): BudgetOverview

    suspend fun saveBudget(request: SaveBudgetRequest): Budget

    suspend fun setBudgetEnabled(
        budgetId: String,
        enabled: Boolean,
    )

    suspend fun deleteBudget(budgetId: String)
}

class LocalBudgetRepository(
    private val database: DropletDatabase,
    private val seeder: DefaultLedgerSeeder,
    private val clock: NetworkClock = NetworkClock.SYSTEM,
    private val zoneId: ZoneId = BudgetProgressCalculator.DEFAULT_ZONE_ID,
) : BudgetRepository {

    private val budgetDao = database.budgetDao()
    private val accountDao = database.accountDao()
    private val transactionDao = database.transactionDao()
    private val exchangeRateDao = database.exchangeRateDao()

    override fun observeBudgetOverview(
        targetMonth: YearMonth?,
        includeDisabled: Boolean,
    ): Flow<BudgetOverview> {
        val nowMs = clock.nowEpochMs()
        val effectiveMonth = targetMonth ?: Instant.ofEpochMilli(nowMs).atZone(zoneId).let {
            YearMonth.of(it.year, it.month)
        }
        val yearRange = BudgetProgressCalculator.yearPeriodRange(
            year = effectiveMonth.year,
            referenceDate = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate(),
            zoneId = zoneId,
        )

        return combine(
            budgetDao.observeAll(onlyEnabled = !includeDisabled),
            accountDao.observeAll(includeArchived = true, includeSystem = false),
            transactionDao.observeInDateRange(
                startInclusive = yearRange.startInclusive,
                endExclusive = yearRange.endExclusive,
                status = TransactionStatus.POSTED,
            ),
            exchangeRateDao.observeAll(),
        ) { budgetEntities, accountEntities, txDetails, rateEntities ->
            val budgets = budgetEntities.map { it.toDomain() }
            val accountsById = accountEntities.associate { it.id to it.toDomain() }
            val transactions = txDetails.map { it.toDomain() }
            val rateProvider = PlanningFallbackRateProvider(rateEntities.toExchangeRateTable())
            val progresses = BudgetProgressCalculator.calculateAll(
                budgets = budgets,
                transactions = transactions,
                accountsById = accountsById,
                asOfEpochMs = clock.nowEpochMs(),
                zoneId = zoneId,
                targetMonthOverride = effectiveMonth,
                rateProvider = rateProvider,
            )
            BudgetOverview(
                targetMonth = effectiveMonth,
                progresses = progresses,
            )
        }
    }

    override fun observeMonthlyOverallBudgetProgress(
        month: YearMonth,
        currency: Currency,
    ): Flow<BudgetProgress?> = observeBudgetOverview(
        targetMonth = month,
        includeDisabled = false,
    ).map { overview ->
        overview.monthlyProgresses.firstOrNull { it.isOverall && it.budget.currency == currency }
            ?: overview.monthlyOverall
    }

    override suspend fun getBudgetOverviewSnapshot(
        asOfEpochMs: Long?,
        onlyEnabled: Boolean,
    ): BudgetOverview {
        val effectiveNow = asOfEpochMs ?: clock.nowEpochMs()
        val today = Instant.ofEpochMilli(effectiveNow).atZone(zoneId).toLocalDate()
        val month = YearMonth.from(today)
        val yearRange = BudgetProgressCalculator.yearPeriodRange(
            year = today.year,
            referenceDate = today,
            zoneId = zoneId,
        )

        val budgets = budgetDao.getAll(onlyEnabled = onlyEnabled).map { it.toDomain() }
        val accountsById = accountDao.getAll(includeArchived = true, includeSystem = false)
            .associate { it.id to it.toDomain() }
        val transactions = transactionDao.getInDateRange(
            startInclusive = yearRange.startInclusive,
            endExclusive = yearRange.endExclusive,
            status = TransactionStatus.POSTED,
        ).map { it.toDomain() }
        val rateProvider = PlanningFallbackRateProvider(exchangeRateDao.getAll().toExchangeRateTable())

        val progresses = BudgetProgressCalculator.calculateAll(
            budgets = budgets,
            transactions = transactions,
            accountsById = accountsById,
            asOfEpochMs = effectiveNow,
            zoneId = zoneId,
            targetMonthOverride = month,
            rateProvider = rateProvider,
        )
        return BudgetOverview(targetMonth = month, progresses = progresses)
    }

    override suspend fun saveBudget(request: SaveBudgetRequest): Budget {
        val now = clock.nowEpochMs()
        seeder.seedIfNeeded(now)

        require(request.limitAmountMinor > 0L) { "预算限额必须大于 0" }
        require(request.warnThresholdPct in 1..100) { "预警比例须在 1% 到 100% 之间" }

        val targetCategoryId = request.categoryAccountId?.trim()?.takeIf { it.isNotEmpty() }
        if (targetCategoryId != null) {
            val category = accountDao.getById(targetCategoryId)?.toDomain()
                ?: throw IllegalArgumentException("未找到所选支出分类")
            require(category.type == AccountType.EXPENSE && !category.isSystem) {
                "预算仅支持绑定支出分类"
            }
        }

        return database.withTransaction {
            val existing = request.id?.let { budgetDao.getById(it) }
            val duplicate = budgetDao.findDuplicate(
                period = request.period,
                categoryAccountId = targetCategoryId,
                currency = request.currency,
            )
            if (duplicate != null && duplicate.id != existing?.id) {
                val scopeLabel = if (targetCategoryId == null) "总预算" else "该分类预算"
                val periodLabel = if (request.period == BudgetPeriod.MONTHLY) "月度" else "年度"
                throw IllegalArgumentException("已存在相同${periodLabel}${scopeLabel}（${request.currency.code}），请直接编辑已有预算")
            }

            val budget = Budget(
                id = existing?.id ?: request.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                period = request.period,
                categoryAccountId = targetCategoryId,
                currency = request.currency,
                limitAmountMinor = request.limitAmountMinor,
                warnThresholdPct = request.warnThresholdPct,
                isEnabled = request.isEnabled,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            budgetDao.upsert(budget.toEntity())
            budget
        }
    }

    override suspend fun setBudgetEnabled(budgetId: String, enabled: Boolean) {
        budgetDao.setEnabled(budgetId = budgetId, enabled = enabled, updatedAt = clock.nowEpochMs())
    }

    override suspend fun deleteBudget(budgetId: String) {
        budgetDao.deleteById(budgetId)
    }
}

internal class PlanningFallbackRateProvider(
    private val primary: ExchangeRateTable,
) : ExchangeRateProvider {
    override fun findRate(
        from: Currency,
        to: Currency,
    ): ExchangeRateSnapshot? {
        if (from == to) return ExchangeRateSnapshot.identity(from)
        return primary.findRate(from, to)
            ?: LedgerRepository.fallbackReferenceRate(from, to)
    }
}
