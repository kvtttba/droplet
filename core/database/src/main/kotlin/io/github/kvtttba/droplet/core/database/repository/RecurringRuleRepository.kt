package io.github.kvtttba.droplet.core.database.repository

import androidx.room.withTransaction
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.automation.RecurringPayload
import io.github.kvtttba.droplet.core.domain.automation.RecurringPayloadCodec
import io.github.kvtttba.droplet.core.domain.automation.RecurringScheduleCalculator
import io.github.kvtttba.droplet.core.domain.automation.RecurringTransactionBuilder
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.RecurringFrequency
import io.github.kvtttba.droplet.core.model.RecurringRule
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionSource
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class RecurringRuleItem(
    val rule: RecurringRule,
    val payload: RecurringPayload?,
    val accountName: String,
    val categoryOrTargetName: String,
    val frequencyLabel: String,
    val isDue: Boolean,
    val isPendingConfirmation: Boolean,
    val isTerminated: Boolean,
)

data class SaveRecurringRuleRequest(
    val id: String? = null,
    val name: String,
    val frequency: RecurringFrequency,
    val intervalCount: Int = 1,
    val nextRunAt: Long,
    val endAt: Long? = null,
    val payload: RecurringPayload,
    val autoPost: Boolean = false,
    val isPaused: Boolean = false,
)

data class RecurringExecutionResult(
    val rule: RecurringRule,
    val transaction: Transaction,
    val payload: RecurringPayload,
)

data class AutoPostRecurringFailure(
    val ruleId: String,
    val ruleName: String,
    val reason: String,
)

data class AutoPostRecurringReport(
    val scannedDueRuleCount: Int = 0,
    val postedItems: List<RecurringExecutionResult> = emptyList(),
    val skippedCount: Int = 0,
    val failures: List<AutoPostRecurringFailure> = emptyList(),
) {
    val postedCount: Int
        get() = postedItems.size
}

/**
 * Repository managing scheduled recurring rules with atomic idempotent auto-posting,
 * user confirmation / skip of due reminders, pause/resume, and rule termination.
 */
interface RecurringRuleRepository {

    fun observeRulesWithStatus(asOfEpochMs: Long? = null): Flow<List<RecurringRuleItem>>

    suspend fun getRulesWithStatusSnapshot(asOfEpochMs: Long? = null): List<RecurringRuleItem>

    suspend fun saveRule(request: SaveRecurringRuleRequest): RecurringRule

    suspend fun setPaused(ruleId: String, paused: Boolean)

    suspend fun terminateRule(ruleId: String)

    suspend fun deleteRule(ruleId: String)

    /**
     * Atomically posts one due occurrence of a manual-confirmation rule and advances `nextRunAt`.
     */
    suspend fun confirmAndPostDueRule(
        ruleId: String,
        asOfEpochMs: Long? = null,
    ): RecurringExecutionResult

    /**
     * Skips the current due occurrence without posting a transaction and advances `nextRunAt`.
     */
    suspend fun skipCurrentDueOccurrence(
        ruleId: String,
        asOfEpochMs: Long? = null,
    ): RecurringRule

    /**
     * Posts one transaction immediately from this rule; if the rule is currently due, also advances
     * `nextRunAt` atomically so the occurrence is not double-posted later.
     */
    suspend fun executeRuleNow(
        ruleId: String,
        asOfEpochMs: Long? = null,
    ): RecurringExecutionResult

    /**
     * Scans all active `autoPost = true` due rules and posts due occurrences atomically with
     * compare-and-swap protection against concurrent double-posting.
     */
    suspend fun processDueAutoPostRules(
        asOfEpochMs: Long? = null,
    ): AutoPostRecurringReport
}

class LocalRecurringRuleRepository(
    private val database: DropletDatabase,
    private val transactionWriter: LedgerTransactionWriter,
    private val seeder: DefaultLedgerSeeder,
    private val ledgerEngine: LedgerEngine,
    private val clock: NetworkClock = NetworkClock.SYSTEM,
    private val zoneId: ZoneId = RecurringScheduleCalculator.DEFAULT_ZONE_ID,
) : RecurringRuleRepository {

    private val ruleDao = database.recurringRuleDao()
    private val accountDao = database.accountDao()
    private val exchangeRateDao = database.exchangeRateDao()

    override fun observeRulesWithStatus(asOfEpochMs: Long?): Flow<List<RecurringRuleItem>> = combine(
        ruleDao.observeAll(),
        accountDao.observeAll(includeArchived = true, includeSystem = true),
    ) { ruleEntities, accountEntities ->
        val effectiveNow = asOfEpochMs ?: clock.nowEpochMs()
        val accountsById = accountEntities.associate { it.id to it.toDomain() }
        ruleEntities.map { entity ->
            toRuleItem(entity.toDomain(), accountsById, effectiveNow)
        }
    }

    override suspend fun getRulesWithStatusSnapshot(asOfEpochMs: Long?): List<RecurringRuleItem> {
        val effectiveNow = asOfEpochMs ?: clock.nowEpochMs()
        val accountsById = accountDao.getAll(includeArchived = true, includeSystem = true)
            .associate { it.id to it.toDomain() }
        return ruleDao.getAll().map { entity ->
            toRuleItem(entity.toDomain(), accountsById, effectiveNow)
        }
    }

    override suspend fun saveRule(request: SaveRecurringRuleRequest): RecurringRule {
        val now = clock.nowEpochMs()
        seeder.seedIfNeeded(now)

        val trimmedName = request.name.trim()
        require(trimmedName.isNotEmpty()) { "周期规则名称不能为空" }
        require(request.intervalCount > 0) { "重复间隔必须大于 0" }
        require(request.frequency != RecurringFrequency.CRON) { "当前仅支持按天 / 周 / 月 / 年周期规则" }
        if (request.endAt != null) {
            require(request.endAt >= request.nextRunAt) { "结束日期不能早于下次执行日期" }
        }

        val accountsById = accountDao.getAll(includeArchived = true, includeSystem = true)
            .associate { it.id to it.toDomain() }
        // Validate payload references eagerly using real accounts
        val rateProvider = PlanningFallbackRateProvider(exchangeRateDao.getAll().toExchangeRateTable())
        RecurringTransactionBuilder.buildTransaction(
            payload = request.payload,
            occurredAt = request.nextRunAt,
            source = TransactionSource.RECURRING,
            ledgerEngine = ledgerEngine,
            accountsById = accountsById,
            rateProvider = rateProvider,
            defaultReimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
            nowEpochMs = now,
        )

        val encodedPayload = RecurringPayloadCodec.encode(request.payload)
        return database.withTransaction {
            val existing = request.id?.let { ruleDao.getById(it) }
            val rule = RecurringRule(
                id = existing?.id ?: request.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                name = trimmedName,
                frequency = request.frequency,
                intervalCount = request.intervalCount,
                cronExpression = null,
                nextRunAt = request.nextRunAt,
                endAt = request.endAt,
                templatePayloadJson = encodedPayload,
                autoPost = request.autoPost,
                isPaused = request.isPaused,
                lastRunAt = existing?.lastRunAt,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            ruleDao.upsert(rule.toEntity())
            rule
        }
    }

    override suspend fun setPaused(ruleId: String, paused: Boolean) {
        ruleDao.setPaused(ruleId = ruleId, paused = paused, updatedAt = clock.nowEpochMs())
    }

    override suspend fun terminateRule(ruleId: String) {
        val now = clock.nowEpochMs()
        database.withTransaction {
            val existing = ruleDao.getById(ruleId) ?: return@withTransaction
            val endAt = minOf(now, existing.nextRunAt - 1L)
            ruleDao.terminateRule(ruleId = ruleId, endAt = endAt, updatedAt = now)
        }
    }

    override suspend fun deleteRule(ruleId: String) {
        ruleDao.deleteById(ruleId)
    }

    override suspend fun confirmAndPostDueRule(
        ruleId: String,
        asOfEpochMs: Long?,
    ): RecurringExecutionResult {
        val now = asOfEpochMs ?: clock.nowEpochMs()
        seeder.seedIfNeeded(now)

        return database.withTransaction {
            val rule = ruleDao.getById(ruleId)?.toDomain()
                ?: throw IllegalArgumentException("未找到周期规则")
            require(!rule.isPaused) { "规则「${rule.name}」已暂停，无法确认入账" }
            require(!RecurringScheduleCalculator.isTerminated(rule)) { "规则「${rule.name}」已终止" }
            require(rule.nextRunAt <= now) { "规则「${rule.name}」尚未到期" }

            val payload = RecurringPayloadCodec.decode(rule.templatePayloadJson)
            val nextRunAt = RecurringScheduleCalculator.computeNextRunAt(
                currentRunAtEpochMs = rule.nextRunAt,
                frequency = rule.frequency,
                intervalCount = rule.intervalCount,
                zoneId = zoneId,
            )
            val updatedRows = ruleDao.advanceScheduleIfExpectedNextRun(
                ruleId = rule.id,
                expectedNextRunAt = rule.nextRunAt,
                lastRunAt = rule.nextRunAt,
                nextRunAt = nextRunAt,
                updatedAt = now,
            )
            require(updatedRows == 1) { "该期周期记账已被处理，无需重复入账" }

            val accountsById = accountDao.getAll(includeArchived = true, includeSystem = true)
                .associate { it.id to it.toDomain() }
            val rateProvider = PlanningFallbackRateProvider(exchangeRateDao.getAll().toExchangeRateTable())
            val tx = RecurringTransactionBuilder.buildTransaction(
                payload = payload,
                occurredAt = rule.nextRunAt,
                source = TransactionSource.RECURRING,
                ledgerEngine = ledgerEngine,
                accountsById = accountsById,
                rateProvider = rateProvider,
                defaultReimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
                nowEpochMs = now,
            )
            transactionWriter.saveTransaction(tx)

            RecurringExecutionResult(
                rule = rule.copy(lastRunAt = rule.nextRunAt, nextRunAt = nextRunAt, updatedAt = now),
                transaction = tx,
                payload = payload,
            )
        }
    }

    override suspend fun skipCurrentDueOccurrence(
        ruleId: String,
        asOfEpochMs: Long?,
    ): RecurringRule {
        val now = asOfEpochMs ?: clock.nowEpochMs()
        return database.withTransaction {
            val rule = ruleDao.getById(ruleId)?.toDomain()
                ?: throw IllegalArgumentException("未找到周期规则")
            require(!rule.isPaused) { "规则「${rule.name}」已暂停" }
            require(!RecurringScheduleCalculator.isTerminated(rule)) { "规则「${rule.name}」已终止" }
            val nextRunAt = RecurringScheduleCalculator.computeNextRunAt(
                currentRunAtEpochMs = rule.nextRunAt,
                frequency = rule.frequency,
                intervalCount = rule.intervalCount,
                zoneId = zoneId,
            )
            val updatedRows = ruleDao.advanceScheduleIfExpectedNextRun(
                ruleId = rule.id,
                expectedNextRunAt = rule.nextRunAt,
                lastRunAt = null,
                nextRunAt = nextRunAt,
                updatedAt = now,
            )
            require(updatedRows == 1) { "该期周期规则已被处理" }
            rule.copy(nextRunAt = nextRunAt, updatedAt = now)
        }
    }

    override suspend fun executeRuleNow(
        ruleId: String,
        asOfEpochMs: Long?,
    ): RecurringExecutionResult {
        val now = asOfEpochMs ?: clock.nowEpochMs()
        seeder.seedIfNeeded(now)

        return database.withTransaction {
            val rule = ruleDao.getById(ruleId)?.toDomain()
                ?: throw IllegalArgumentException("未找到周期规则")
            val payload = RecurringPayloadCodec.decode(rule.templatePayloadJson)
            val accountsById = accountDao.getAll(includeArchived = true, includeSystem = true)
                .associate { it.id to it.toDomain() }
            val rateProvider = PlanningFallbackRateProvider(exchangeRateDao.getAll().toExchangeRateTable())

            val shouldAdvance = !rule.isPaused && !RecurringScheduleCalculator.isTerminated(rule) && rule.nextRunAt <= now
            val nextRunAt = if (shouldAdvance) {
                RecurringScheduleCalculator.computeNextRunAt(
                    currentRunAtEpochMs = rule.nextRunAt,
                    frequency = rule.frequency,
                    intervalCount = rule.intervalCount,
                    zoneId = zoneId,
                )
            } else {
                rule.nextRunAt
            }

            if (shouldAdvance) {
                val updated = ruleDao.advanceScheduleIfExpectedNextRun(
                    ruleId = rule.id,
                    expectedNextRunAt = rule.nextRunAt,
                    lastRunAt = now,
                    nextRunAt = nextRunAt,
                    updatedAt = now,
                )
                require(updated == 1) { "该期周期规则已被处理，无需重复入账" }
            } else {
                ruleDao.advanceSchedule(
                    ruleId = rule.id,
                    lastRunAt = now,
                    nextRunAt = rule.nextRunAt,
                    updatedAt = now,
                )
            }

            val tx = RecurringTransactionBuilder.buildTransaction(
                payload = payload,
                occurredAt = now,
                source = TransactionSource.RECURRING,
                ledgerEngine = ledgerEngine,
                accountsById = accountsById,
                rateProvider = rateProvider,
                defaultReimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
                nowEpochMs = now,
            )
            transactionWriter.saveTransaction(tx)

            RecurringExecutionResult(
                rule = rule.copy(lastRunAt = now, nextRunAt = nextRunAt, updatedAt = now),
                transaction = tx,
                payload = payload,
            )
        }
    }

    override suspend fun processDueAutoPostRules(asOfEpochMs: Long?): AutoPostRecurringReport {
        val now = asOfEpochMs ?: clock.nowEpochMs()
        seeder.seedIfNeeded(now)

        val dueAutoRules = ruleDao.getDueRules(now)
            .map { it.toDomain() }
            .filter { it.autoPost }
        if (dueAutoRules.isEmpty()) {
            return AutoPostRecurringReport()
        }

        val postedItems = mutableListOf<RecurringExecutionResult>()
        val failures = mutableListOf<AutoPostRecurringFailure>()
        var skippedCount = 0

        for (initialRule in dueAutoRules) {
            runCatching {
                val plan = RecurringScheduleCalculator.collectDuePlan(
                    rule = initialRule,
                    asOfEpochMs = now,
                    zoneId = zoneId,
                )
                if (plan.dueTimestamps.isEmpty()) {
                    skippedCount++
                    return@runCatching
                }

                database.withTransaction {
                    val liveRule = ruleDao.getById(initialRule.id)?.toDomain()
                    if (liveRule == null || liveRule.isPaused || !liveRule.autoPost || liveRule.nextRunAt != initialRule.nextRunAt) {
                        skippedCount++
                        return@withTransaction
                    }

                    val payload = RecurringPayloadCodec.decode(liveRule.templatePayloadJson)
                    val lastDueAt = plan.dueTimestamps.last()
                    val updatedRows = ruleDao.advanceScheduleIfExpectedNextRun(
                        ruleId = liveRule.id,
                        expectedNextRunAt = liveRule.nextRunAt,
                        lastRunAt = lastDueAt,
                        nextRunAt = plan.nextRunAtAfter,
                        updatedAt = now,
                    )
                    if (updatedRows == 0) {
                        skippedCount++
                        return@withTransaction
                    }

                    val accountsById = accountDao.getAll(includeArchived = true, includeSystem = true)
                        .associate { it.id to it.toDomain() }
                    val rateProvider = PlanningFallbackRateProvider(exchangeRateDao.getAll().toExchangeRateTable())
                    val updatedRule = liveRule.copy(
                        lastRunAt = lastDueAt,
                        nextRunAt = plan.nextRunAtAfter,
                        updatedAt = now,
                    )

                    val transactions = plan.dueTimestamps.map { occurrenceMs ->
                        RecurringTransactionBuilder.buildTransaction(
                            payload = payload,
                            occurredAt = occurrenceMs,
                            source = TransactionSource.RECURRING,
                            ledgerEngine = ledgerEngine,
                            accountsById = accountsById,
                            rateProvider = rateProvider,
                            defaultReimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
                            nowEpochMs = now,
                        )
                    }
                    transactionWriter.saveTransactions(transactions)
                    transactions.forEach { tx ->
                        postedItems += RecurringExecutionResult(
                            rule = updatedRule,
                            transaction = tx,
                            payload = payload,
                        )
                    }
                }
            }.onFailure { err ->
                failures += AutoPostRecurringFailure(
                    ruleId = initialRule.id,
                    ruleName = initialRule.name,
                    reason = err.message ?: "自动入账失败",
                )
            }
        }

        return AutoPostRecurringReport(
            scannedDueRuleCount = dueAutoRules.size,
            postedItems = postedItems,
            skippedCount = skippedCount,
            failures = failures,
        )
    }

    private fun toRuleItem(
        rule: RecurringRule,
        accountsById: Map<String, Account>,
        nowMs: Long,
    ): RecurringRuleItem {
        val payload = RecurringPayloadCodec.decodeOrNull(rule.templatePayloadJson)
        val accountName = payload?.accountId?.let { accountsById[it]?.name } ?: "未关联账户"
        val categoryOrTargetName = when {
            payload == null -> "配置无效"
            payload.isReimbursable -> "待报销垫付"
            payload.targetAccountId != null -> "转入 ${accountsById[payload.targetAccountId]?.name ?: "目标账户"}"
            payload.categoryAccountId != null -> formatCategoryLabel(accountsById[payload.categoryAccountId], accountsById)
            else -> "未分类"
        }
        return RecurringRuleItem(
            rule = rule,
            payload = payload,
            accountName = accountName,
            categoryOrTargetName = categoryOrTargetName,
            frequencyLabel = RecurringScheduleCalculator.formatFrequencyLabel(rule.frequency, rule.intervalCount),
            isDue = RecurringScheduleCalculator.isDue(rule, nowMs),
            isPendingConfirmation = RecurringScheduleCalculator.isPendingConfirmation(rule, nowMs),
            isTerminated = RecurringScheduleCalculator.isTerminated(rule),
        )
    }

    private fun formatCategoryLabel(
        category: Account?,
        accountsById: Map<String, Account>,
    ): String {
        if (category == null) return "未知分类"
        val parent = category.parentId?.let { accountsById[it] }
        return if (parent != null) "${parent.name}·${category.name}" else category.name
    }
}
