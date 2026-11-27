package io.github.kvtttba.droplet.core.database.repository

import androidx.room.withTransaction
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.dao.TransactionWithDetails
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.credit.CreditCardRepaymentHelper
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementCalculator
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementSummary
import io.github.kvtttba.droplet.core.domain.investment.FallbackExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionSource
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CreditCardRepaymentRequest(
    val creditCardAccountId: String,
    val paymentAccountId: String,
    val repaymentAmount: Money,
    val paidFromAccountAmount: Money? = null,
    val fee: Money? = null,
    val feeExpenseAccountId: String = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
    val occurredAt: Long,
    val payee: String? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val source: TransactionSource = TransactionSource.MANUAL,
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class UpdateCreditCardParametersRequest(
    val accountId: String,
    val creditLimitMinor: Long?,
    val billingDay: Int?,
    val dueDay: Int?,
    val remindDaysBefore: Int?,
)

data class CreditCardDetailSnapshot(
    val summary: CreditCardStatementSummary,
    val billedTransactions: List<LedgerTransactionRecord>,
    val unbilledTransactions: List<LedgerTransactionRecord>,
    val allTransactions: List<LedgerTransactionRecord>,
)

/**
 * Reactive credit-card billing, statement-transaction, parameter-update, and one-tap repayment repository.
 */
interface CreditCardRepository {

    fun observeCreditCardSummaries(
        includeArchived: Boolean = false,
        defaultRemindDaysBefore: Int = CreditCardStatementCalculator.DEFAULT_REMIND_DAYS_BEFORE,
        asOfEpochMs: Long? = null,
    ): Flow<List<CreditCardStatementSummary>>

    fun observeCreditCardDetail(
        accountId: String,
        defaultRemindDaysBefore: Int = CreditCardStatementCalculator.DEFAULT_REMIND_DAYS_BEFORE,
        asOfEpochMs: Long? = null,
    ): Flow<CreditCardDetailSnapshot?>

    fun observeAccountTransactions(
        accountId: String,
        status: TransactionStatus = TransactionStatus.POSTED,
    ): Flow<List<LedgerTransactionRecord>>

    suspend fun getCreditCardSummariesSnapshot(
        includeArchived: Boolean = false,
        defaultRemindDaysBefore: Int = CreditCardStatementCalculator.DEFAULT_REMIND_DAYS_BEFORE,
        asOfEpochMs: Long? = null,
    ): List<CreditCardStatementSummary>

    suspend fun recordRepayment(request: CreditCardRepaymentRequest): Transaction

    suspend fun updateCreditCardParameters(request: UpdateCreditCardParametersRequest): Account
}

class LocalCreditCardRepository(
    private val database: DropletDatabase,
    private val transactionWriter: LedgerTransactionWriter,
    private val seeder: DefaultLedgerSeeder,
    private val ledgerEngine: LedgerEngine,
    private val clock: NetworkClock = NetworkClock.SYSTEM,
    private val zoneId: ZoneId = CreditCardStatementCalculator.DEFAULT_ZONE_ID,
) : CreditCardRepository {

    private val accountDao = database.accountDao()
    private val transactionDao = database.transactionDao()
    private val exchangeRateDao = database.exchangeRateDao()
    private val seedMutex = Mutex()

    private suspend fun ensureSeeded(nowEpochMs: Long) {
        seedMutex.withLock {
            seeder.seedIfNeeded(nowEpochMs)
        }
    }

    override fun observeCreditCardSummaries(
        includeArchived: Boolean,
        defaultRemindDaysBefore: Int,
        asOfEpochMs: Long?,
    ): Flow<List<CreditCardStatementSummary>> = combine(
        accountDao.observeCreditCardAccounts(includeArchived = includeArchived),
        transactionDao.observeTransactionsForCreditCards(TransactionStatus.POSTED),
    ) { cardEntities, txDetails ->
        val effectiveAsOf = asOfEpochMs ?: clock.nowEpochMs()
        val domainTxs = txDetails.map { it.toDomain() }
        cardEntities.map { entity ->
            val card = entity.toDomain()
            CreditCardStatementCalculator.calculate(
                account = card,
                transactions = domainTxs,
                asOfEpochMs = effectiveAsOf,
                defaultRemindDaysBefore = defaultRemindDaysBefore,
                zoneId = zoneId,
            )
        }
    }

    override fun observeCreditCardDetail(
        accountId: String,
        defaultRemindDaysBefore: Int,
        asOfEpochMs: Long?,
    ): Flow<CreditCardDetailSnapshot?> = combine(
        accountDao.observeById(accountId),
        transactionDao.observeTransactionsForAccount(accountId, TransactionStatus.POSTED),
    ) { accountEntity, txDetails ->
        val account = accountEntity?.toDomain() ?: return@combine null
        if (account.type != AccountType.LIABILITY || account.subType != AccountSubType.CREDIT_CARD) {
            return@combine null
        }
        val effectiveAsOf = asOfEpochMs ?: clock.nowEpochMs()
        val records = txDetails.map { it.toRecord() }
        val summary = CreditCardStatementCalculator.calculate(
            account = account,
            transactions = records.map { it.transaction },
            asOfEpochMs = effectiveAsOf,
            defaultRemindDaysBefore = defaultRemindDaysBefore,
            zoneId = zoneId,
        )
        val partitioned = CreditCardStatementCalculator.partitionRecords(
            records = records,
            summary = summary,
            transactionSelector = { it.transaction },
        )
        CreditCardDetailSnapshot(
            summary = summary,
            billedTransactions = partitioned.billed,
            unbilledTransactions = partitioned.unbilled,
            allTransactions = partitioned.all,
        )
    }

    override fun observeAccountTransactions(
        accountId: String,
        status: TransactionStatus,
    ): Flow<List<LedgerTransactionRecord>> = transactionDao
        .observeTransactionsForAccount(accountId = accountId, status = status)
        .map { details -> details.map { it.toRecord() } }

    override suspend fun getCreditCardSummariesSnapshot(
        includeArchived: Boolean,
        defaultRemindDaysBefore: Int,
        asOfEpochMs: Long?,
    ): List<CreditCardStatementSummary> {
        val effectiveAsOf = asOfEpochMs ?: clock.nowEpochMs()
        val cards = accountDao.getCreditCardAccounts(includeArchived = includeArchived)
            .map { it.toDomain() }
        if (cards.isEmpty()) return emptyList()
        val domainTxs = transactionDao.getTransactionsForCreditCards(TransactionStatus.POSTED)
            .map { it.toDomain() }
        return cards.map { card ->
            CreditCardStatementCalculator.calculate(
                account = card,
                transactions = domainTxs,
                asOfEpochMs = effectiveAsOf,
                defaultRemindDaysBefore = defaultRemindDaysBefore,
                zoneId = zoneId,
            )
        }
    }

    override suspend fun recordRepayment(request: CreditCardRepaymentRequest): Transaction {
        val now = clock.nowEpochMs()
        ensureSeeded(now)

        return database.withTransaction {
            val creditCard = accountDao.getById(request.creditCardAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到信用卡账户：${request.creditCardAccountId}")
            val paymentAccount = accountDao.getById(request.paymentAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到付款账户：${request.paymentAccountId}")

            val rateProvider = currentRateProviderWithFallback(request.occurredAt)
            val tx = CreditCardRepaymentHelper.buildRepaymentTransaction(
                ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                meta = TransactionMeta(
                    occurredAt = request.occurredAt,
                    payee = request.payee,
                    note = request.note,
                    source = request.source,
                    tagIds = request.tagIds,
                    createdAt = now,
                    updatedAt = now,
                ),
                creditCardAccount = creditCard,
                paymentAccount = paymentAccount,
                repaymentAmount = request.repaymentAmount,
                paidFromAccountAmount = request.paidFromAccountAmount,
                fee = request.fee,
                feeExpenseAccountId = request.feeExpenseAccountId.takeIf { request.fee != null },
                exchangeRateOverride = request.exchangeRateOverride,
                rateProviderOverride = rateProvider,
            )

            transactionWriter.saveTransaction(tx)
            tx
        }
    }

    override suspend fun updateCreditCardParameters(
        request: UpdateCreditCardParametersRequest,
    ): Account {
        val now = clock.nowEpochMs()
        return database.withTransaction {
            val existing = accountDao.getById(request.accountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到信用卡账户：${request.accountId}")
            require(existing.type == AccountType.LIABILITY && existing.subType == AccountSubType.CREDIT_CARD) {
                "账户「${existing.name}」不是信用账户"
            }
            val updated = existing.copy(
                creditLimitMinor = request.creditLimitMinor,
                billingDay = request.billingDay,
                dueDay = request.dueDay,
                remindDaysBefore = request.remindDaysBefore,
                updatedAt = now,
            )
            accountDao.upsert(updated.toEntity())
            updated
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

    private fun TransactionWithDetails.toRecord(): LedgerTransactionRecord = LedgerTransactionRecord(
        transaction = toDomain(),
        tags = tags
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
            .map { it.toDomain() },
    )
}
