package io.github.kvtttba.droplet.core.database.repository

import androidx.room.withTransaction
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.domain.debt.ExpenseRefundSummary
import io.github.kvtttba.droplet.core.domain.debt.RefundCalculator
import io.github.kvtttba.droplet.core.domain.debt.RefundHelper
import io.github.kvtttba.droplet.core.domain.debt.ReimbursementCalculator
import io.github.kvtttba.droplet.core.domain.debt.ReimbursementItemSummary
import io.github.kvtttba.droplet.core.domain.debt.ReimbursementOverviewSummary
import io.github.kvtttba.droplet.core.domain.debt.ReimbursementSettlementHelper
import io.github.kvtttba.droplet.core.domain.investment.FallbackExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.network.NetworkClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SettleReimbursementRequest(
    val advanceTransactionId: String,
    val depositAccountId: String,
    val settledReimbursementAmount: Money,
    val receivedAmount: Money,
    val shortfallExpenseAccountId: String? = null,
    val surplusIncomeAccountId: String? = null,
    val occurredAt: Long? = null,
    val payee: String? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class SettleReimbursementResult(
    val settlementTransaction: Transaction,
    val updatedAdvanceState: ReimburseState,
    val remainingPendingAfter: Money,
    val shortfallAmount: Money,
    val surplusAmount: Money,
)

data class BatchSettleReimbursementsRequest(
    val advanceTransactionIds: List<String>,
    val depositAccountId: String,
    val occurredAt: Long? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
)

data class BatchSettleReimbursementsResult(
    val settlementTransactions: List<Transaction>,
    val settledCount: Int,
    val totalReceivedByCurrency: Map<Currency, Money>,
)

data class RecordRefundRequest(
    val originalExpenseTransactionId: String,
    val refundToAccountId: String,
    val refundAmount: Money,
    val expenseCategoryAccountIdOverride: String? = null,
    val expenseCurrencyReductionAmount: Money? = null,
    val occurredAt: Long? = null,
    val payee: String? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class RecordRefundResult(
    val refundTransaction: Transaction,
    val updatedRefundSummary: ExpenseRefundSummary,
)

/**
 * Reactive repository for:
 * 1. Reimbursable advance tracking, single settlement (full, partial, shortfall -> personal
 *    expense, surplus -> income), and atomic batch full settlement;
 * 2. Multi-stage linked refunds against `POSTED` `EXPENSE` transactions with cumulative cap
 *    enforcement and net-expense recalculation.
 */
interface ReimbursementAndRefundRepository {

    fun observeReimbursementOverview(): Flow<ReimbursementOverviewSummary>

    fun observeReimbursementItem(advanceTransactionId: String): Flow<ReimbursementItemSummary?>

    suspend fun settleReimbursement(request: SettleReimbursementRequest): SettleReimbursementResult

    suspend fun batchSettleReimbursements(
        request: BatchSettleReimbursementsRequest,
    ): BatchSettleReimbursementsResult

    fun observeExpenseRefundSummary(expenseTransactionId: String): Flow<ExpenseRefundSummary?>

    fun observeRefundSummariesForExpenses(
        expenseTransactions: List<Transaction>,
    ): Flow<Map<String, ExpenseRefundSummary>>

    suspend fun recordRefund(request: RecordRefundRequest): RecordRefundResult
}

class LocalReimbursementAndRefundRepository(
    private val database: DropletDatabase,
    private val transactionWriter: LedgerTransactionWriter,
    private val seeder: DefaultLedgerSeeder,
    private val ledgerEngine: LedgerEngine,
    private val clock: NetworkClock = NetworkClock.SYSTEM,
) : ReimbursementAndRefundRepository {

    private val accountDao = database.accountDao()
    private val transactionDao = database.transactionDao()
    private val exchangeRateDao = database.exchangeRateDao()
    private val seedMutex = Mutex()

    private suspend fun ensureSeeded(nowEpochMs: Long) {
        seedMutex.withLock {
            seeder.seedIfNeeded(nowEpochMs)
        }
    }

    override fun observeReimbursementOverview(): Flow<ReimbursementOverviewSummary> = combine(
        transactionDao.observeAllReimbursementTransactions(TransactionStatus.POSTED),
        accountDao.observeAll(includeArchived = true, includeSystem = true),
    ) { txEntities, accountEntities ->
        val accountsById = accountEntities.associate { it.id to it.toDomain() }
        val transactions = txEntities.map { it.toDomain() }
        ReimbursementCalculator.summarizeAll(
            reimburseTransactions = transactions,
            accountsById = accountsById,
            defaultCurrency = ledgerEngine.baseCurrency,
        )
    }

    override fun observeReimbursementItem(advanceTransactionId: String): Flow<ReimbursementItemSummary?> =
        combine(
            transactionDao.observeById(advanceTransactionId),
            transactionDao.observeLinkedTransactionsByType(
                refTransactionId = advanceTransactionId,
                displayType = TransactionDisplayType.REIMBURSE,
                status = TransactionStatus.POSTED,
            ),
            accountDao.observeAll(includeArchived = true, includeSystem = true),
        ) { advanceEntity, settlementEntities, accountEntities ->
            val advance = advanceEntity?.toDomain() ?: return@combine null
            val accountsById = accountEntities.associate { it.id to it.toDomain() }
            ReimbursementCalculator.summarizeItem(
                advanceTransaction = advance,
                linkedSettlements = settlementEntities.map { it.toDomain() },
                accountsById = accountsById,
            )
        }

    override suspend fun settleReimbursement(
        request: SettleReimbursementRequest,
    ): SettleReimbursementResult {
        val now = clock.nowEpochMs()
        ensureSeeded(now)

        return database.withTransaction {
            val advanceTx = transactionDao.getById(request.advanceTransactionId)?.toDomain()
                ?: throw IllegalArgumentException("未找到待报销垫付流水：${request.advanceTransactionId}")
            val linkedSettlements = transactionDao.getLinkedTransactionsByType(
                refTransactionId = advanceTx.id,
                displayType = TransactionDisplayType.REIMBURSE,
                status = TransactionStatus.POSTED,
            ).map { it.toDomain() }

            val requiredAccountIds = buildSet {
                advanceTx.postings.forEach { add(it.accountId) }
                linkedSettlements.forEach { tx -> tx.postings.forEach { add(it.accountId) } }
                add(request.depositAccountId)
                request.shortfallExpenseAccountId?.let(::add)
                request.surplusIncomeAccountId?.let(::add)
            }.toList()
            val accountsById = accountDao.getByIds(requiredAccountIds).associate { it.id to it.toDomain() }

            val item = ReimbursementCalculator.summarizeItem(
                advanceTransaction = advanceTx,
                linkedSettlements = linkedSettlements,
                accountsById = accountsById,
            ) ?: throw IllegalArgumentException("目标流水不是有效的已入账待报销垫付单")

            val reimbursementAccount = accountsById[item.reimbursementAccountId]
                ?: throw IllegalStateException("未找到待报销科目：${item.reimbursementAccountId}")
            val depositAccount = accountsById[request.depositAccountId]
                ?: throw IllegalArgumentException("未找到报销收款账户：${request.depositAccountId}")
            val shortfallAccount = request.shortfallExpenseAccountId?.let { id ->
                accountsById[id] ?: throw IllegalArgumentException("未找到个人自付支出分类：$id")
            }
            val surplusAccount = request.surplusIncomeAccountId?.let { id ->
                accountsById[id] ?: throw IllegalArgumentException("未找到超额收入分类：$id")
            }

            val occurredAt = request.occurredAt ?: now
            val rateProvider = currentRateProviderWithFallback(occurredAt)

            val plan = ReimbursementSettlementHelper.buildSingleSettlement(
                ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                item = item,
                reimbursementAccount = reimbursementAccount,
                depositAccount = depositAccount,
                settledReimbursementAmount = request.settledReimbursementAmount,
                receivedAmount = request.receivedAmount,
                shortfallExpenseAccount = shortfallAccount,
                surplusIncomeAccount = surplusAccount,
                occurredAt = occurredAt,
                payee = request.payee,
                note = request.note,
                tagIds = request.tagIds,
                createdAt = now,
                updatedAt = now,
                exchangeRateOverride = request.exchangeRateOverride,
                rateProviderOverride = rateProvider,
            )

            transactionWriter.saveTransaction(plan.settlementTransaction)
            transactionDao.updateReimburseState(
                transactionId = advanceTx.id,
                state = plan.nextAdvanceState,
                updatedAt = now,
            )

            SettleReimbursementResult(
                settlementTransaction = plan.settlementTransaction,
                updatedAdvanceState = plan.nextAdvanceState,
                remainingPendingAfter = plan.remainingPendingAfter,
                shortfallAmount = plan.shortfallAmount,
                surplusAmount = plan.surplusAmount,
            )
        }
    }

    override suspend fun batchSettleReimbursements(
        request: BatchSettleReimbursementsRequest,
    ): BatchSettleReimbursementsResult {
        val now = clock.nowEpochMs()
        ensureSeeded(now)
        require(request.advanceTransactionIds.isNotEmpty()) { "请至少选择一笔待报销流水" }

        return database.withTransaction {
            val depositAccount = accountDao.getById(request.depositAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到报销收款账户：${request.depositAccountId}")

            val allReimburseTxs = transactionDao.getAllReimbursementTransactions(TransactionStatus.POSTED)
                .map { it.toDomain() }
            val referencedIds = buildSet {
                add(depositAccount.id)
                allReimburseTxs.forEach { tx -> tx.postings.forEach { add(it.accountId) } }
            }.toList()
            val accountsById = accountDao.getByIds(referencedIds).associate { it.id to it.toDomain() }

            val overview = ReimbursementCalculator.summarizeAll(
                reimburseTransactions = allReimburseTxs,
                accountsById = accountsById,
                defaultCurrency = ledgerEngine.baseCurrency,
            )
            val itemsById = overview.items.associateBy { it.advanceTransaction.id }

            val selectedItems = request.advanceTransactionIds.distinct().map { id ->
                itemsById[id] ?: throw IllegalArgumentException("未找到待报销垫付流水：$id")
            }

            val occurredAt = request.occurredAt ?: now
            val rateProvider = currentRateProviderWithFallback(occurredAt)

            val plans = ReimbursementSettlementHelper.buildBatchFullSettlements(
                ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                items = selectedItems,
                reimbursementAccountsById = accountsById,
                depositAccount = depositAccount,
                occurredAt = occurredAt,
                note = request.note,
                tagIds = request.tagIds,
                createdAt = now,
                updatedAt = now,
                rateProviderOverride = rateProvider,
            )

            transactionWriter.saveTransactions(plans.map { it.settlementTransaction })
            for (plan in plans) {
                transactionDao.updateReimburseState(
                    transactionId = plan.advanceTransactionId,
                    state = plan.nextAdvanceState,
                    updatedAt = now,
                )
            }

            val totalsByCurrency = linkedMapOf<Currency, Long>()
            for (item in selectedItems) {
                val prev = totalsByCurrency[item.currency] ?: 0L
                totalsByCurrency[item.currency] = Math.addExact(prev, item.remainingPendingAmount.amountMinor)
            }

            BatchSettleReimbursementsResult(
                settlementTransactions = plans.map { it.settlementTransaction },
                settledCount = plans.size,
                totalReceivedByCurrency = totalsByCurrency.mapValues { (ccy, minor) -> Money(minor, ccy) },
            )
        }
    }

    override fun observeExpenseRefundSummary(expenseTransactionId: String): Flow<ExpenseRefundSummary?> =
        combine(
            transactionDao.observeById(expenseTransactionId),
            transactionDao.observeLinkedTransactionsByType(
                refTransactionId = expenseTransactionId,
                displayType = TransactionDisplayType.REFUND,
                status = TransactionStatus.POSTED,
            ),
            accountDao.observeAll(includeArchived = true, includeSystem = true),
        ) { expenseEntity, refundEntities, accountEntities ->
            val expense = expenseEntity?.toDomain() ?: return@combine null
            val accountsById = accountEntities.associate { it.id to it.toDomain() }
            RefundCalculator.summarize(
                originalExpense = expense,
                linkedRefunds = refundEntities.map { it.toDomain() },
                accountsById = accountsById,
            )
        }

    override fun observeRefundSummariesForExpenses(
        expenseTransactions: List<Transaction>,
    ): Flow<Map<String, ExpenseRefundSummary>> {
        val eligibleExpenses = expenseTransactions.filter {
            it.status == TransactionStatus.POSTED && it.displayType == TransactionDisplayType.EXPENSE
        }
        if (eligibleExpenses.isEmpty()) {
            return flowOf(emptyMap())
        }
        val parentIds = eligibleExpenses.map { it.id }.distinct()
        return combine(
            transactionDao.observeLinkedTransactionsForParents(
                refTransactionIds = parentIds,
                displayType = TransactionDisplayType.REFUND,
                status = TransactionStatus.POSTED,
            ),
            accountDao.observeAll(includeArchived = true, includeSystem = true),
        ) { refundEntities, accountEntities ->
            val accountsById = accountEntities.associate { it.id to it.toDomain() }
            RefundCalculator.summarizeBatch(
                expenseTransactions = eligibleExpenses,
                linkedRefundTransactions = refundEntities.map { it.toDomain() },
                accountsById = accountsById,
            )
        }
    }

    override suspend fun recordRefund(request: RecordRefundRequest): RecordRefundResult {
        val now = clock.nowEpochMs()
        ensureSeeded(now)

        return database.withTransaction {
            val originalExpense = transactionDao.getById(request.originalExpenseTransactionId)?.toDomain()
                ?: throw IllegalArgumentException("未找到原支出流水：${request.originalExpenseTransactionId}")
            val existingRefunds = transactionDao.getLinkedTransactionsByType(
                refTransactionId = originalExpense.id,
                displayType = TransactionDisplayType.REFUND,
                status = TransactionStatus.POSTED,
            ).map { it.toDomain() }

            val accountIds = buildSet {
                originalExpense.postings.forEach { add(it.accountId) }
                existingRefunds.forEach { tx -> tx.postings.forEach { add(it.accountId) } }
                add(request.refundToAccountId)
                request.expenseCategoryAccountIdOverride?.let(::add)
                Currency.entries.forEach { add(SystemAccounts.tradingAccountId(it)) }
            }.toList()
            val accountsById = accountDao.getByIds(accountIds).associate { it.id to it.toDomain() }

            val refundToAccount = accountsById[request.refundToAccountId]
                ?: throw IllegalArgumentException("未找到退回账户：${request.refundToAccountId}")

            val occurredAt = request.occurredAt ?: now
            val rateProvider = currentRateProviderWithFallback(occurredAt)

            val built = RefundHelper.buildRefundTransaction(
                ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                originalExpense = originalExpense,
                existingLinkedRefunds = existingRefunds,
                accountsById = accountsById,
                refundToAccount = refundToAccount,
                refundAmount = request.refundAmount,
                expenseCategoryAccountIdOverride = request.expenseCategoryAccountIdOverride,
                expenseCurrencyReductionAmount = request.expenseCurrencyReductionAmount,
                occurredAt = occurredAt,
                payee = request.payee,
                note = request.note,
                tagIds = request.tagIds,
                createdAt = now,
                updatedAt = now,
                exchangeRateOverride = request.exchangeRateOverride,
                rateProviderOverride = rateProvider,
            )

            transactionWriter.saveTransaction(built.refundTransaction)
            RecordRefundResult(
                refundTransaction = built.refundTransaction,
                updatedRefundSummary = built.updatedSummary,
            )
        }
    }

    private suspend fun currentRateProviderWithFallback(capturedAt: Long): ExchangeRateProvider {
        val liveRates: ExchangeRateTable = exchangeRateDao.getAll().toExchangeRateTable()
        return FallbackExchangeRateProvider(
            primary = liveRates,
            fallback = object : ExchangeRateProvider {
                override fun findRate(from: Currency, to: Currency): ExchangeRateSnapshot =
                    LedgerRepository.fallbackReferenceRate(from, to, capturedAt)
            },
        )
    }
}
