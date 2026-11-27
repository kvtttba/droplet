package io.github.kvtttba.droplet.core.database.repository

import androidx.room.withTransaction
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.dao.AccountCurrencyBalanceRow
import io.github.kvtttba.droplet.core.database.dao.PeriodSummaryRow
import io.github.kvtttba.droplet.core.database.dao.TransactionFilter
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.SeedResult
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LedgerTransactionRecord(
    val transaction: Transaction,
    val tags: List<Tag>,
)

/**
 * Unified local ledger repository abstracting Room DAOs, transactional double-entry validation,
 * idempotent default catalog seeding, and opening-balance creation.
 */
interface LedgerRepository {

    suspend fun ensureSeeded(nowEpochMs: Long = System.currentTimeMillis()): SeedResult

    fun observeAllAccounts(
        includeArchived: Boolean = true,
        includeSystem: Boolean = true,
    ): Flow<List<Account>>

    fun observeOperationalAccounts(includeArchived: Boolean = false): Flow<List<Account>>

    fun observeCategories(
        type: AccountType,
        includeArchived: Boolean = false,
    ): Flow<List<Account>>

    fun observeAccountBalances(
        status: TransactionStatus = TransactionStatus.POSTED,
    ): Flow<List<AccountCurrencyBalanceRow>>

    suspend fun getAccountById(accountId: String): Account?

    suspend fun saveAccount(
        account: Account,
        openingBalance: Money? = null,
        openingExchangeRateOverride: ExchangeRateSnapshot? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    )

    suspend fun setAccountArchived(
        accountId: String,
        archived: Boolean,
        nowEpochMs: Long = System.currentTimeMillis(),
    )

    suspend fun deleteAccountIfUnused(accountId: String): Result<Unit>

    fun observeTags(includeArchived: Boolean = false): Flow<List<Tag>>

    suspend fun saveTag(tag: Tag): Result<Unit>

    suspend fun setTagArchived(
        tagId: String,
        archived: Boolean,
        nowEpochMs: Long = System.currentTimeMillis(),
    )

    suspend fun deleteTag(tagId: String)

    fun observeFilteredTransactions(filter: TransactionFilter): Flow<List<LedgerTransactionRecord>>

    fun observeFilteredPeriodSummary(
        baseCurrency: Currency = Currency.DEFAULT,
        filter: TransactionFilter = TransactionFilter(),
    ): Flow<PeriodSummaryRow>

    fun observePeriodSummary(
        baseCurrency: Currency = Currency.DEFAULT,
        startInclusive: Long? = null,
        endExclusive: Long? = null,
        status: TransactionStatus = TransactionStatus.POSTED,
    ): Flow<PeriodSummaryRow>

    suspend fun getTransactionById(transactionId: String): Transaction?

    suspend fun saveTransaction(
        transaction: Transaction,
        allowArchivedAccounts: Boolean = false,
    )

    suspend fun voidTransaction(
        transactionId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    )

    suspend fun restoreTransaction(
        transactionId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    )

    suspend fun deleteTransaction(transactionId: String)

    fun observeExchangeRateTable(): Flow<ExchangeRateTable>

    suspend fun getExchangeRateTable(): ExchangeRateTable

    suspend fun upsertExchangeRate(rate: CachedExchangeRate)

    companion object {
        val OPERATIONAL_SUB_TYPES: List<AccountSubType> = listOf(
            AccountSubType.CASH,
            AccountSubType.DEBIT_CARD,
            AccountSubType.CREDIT_CARD,
            AccountSubType.STORED_VALUE,
            AccountSubType.INVEST_CASH,
            AccountSubType.INVEST_HOLDING,
            AccountSubType.LOAN,
            AccountSubType.RECEIVABLE,
            AccountSubType.PAYABLE,
        )

        /**
         * Editable offline reference rates used as UI defaults prior to M2.1 market rate refresh.
         */
        fun fallbackReferenceRate(
            from: Currency,
            to: Currency,
            capturedAt: Long = 0L,
        ): ExchangeRateSnapshot {
            if (from == to) return ExchangeRateSnapshot.identity(from, capturedAt)
            val table = ExchangeRateTable.of(
                ExchangeRateSnapshot.of(
                    fromCurrency = Currency.USD,
                    toCurrency = Currency.CNY,
                    rateDecimal = "7.20",
                    source = ExchangeRateSource.USER_OVERRIDE,
                    capturedAt = capturedAt,
                ),
                ExchangeRateSnapshot.of(
                    fromCurrency = Currency.HKD,
                    toCurrency = Currency.CNY,
                    rateDecimal = "0.92",
                    source = ExchangeRateSource.USER_OVERRIDE,
                    capturedAt = capturedAt,
                ),
            )
            return table.requireRate(from, to, capturedAtOverride = capturedAt)
        }
    }
}

class LocalLedgerRepository(
    private val database: DropletDatabase,
    private val transactionWriter: LedgerTransactionWriter,
    private val seeder: DefaultLedgerSeeder,
    private val ledgerEngine: LedgerEngine,
) : LedgerRepository {

    private val accountDao = database.accountDao()
    private val transactionDao = database.transactionDao()
    private val tagDao = database.tagDao()
    private val exchangeRateDao = database.exchangeRateDao()
    private val seedMutex = Mutex()

    override suspend fun ensureSeeded(nowEpochMs: Long): SeedResult =
        seedMutex.withLock {
            seeder.seedIfNeeded(nowEpochMs)
        }

    override fun observeAllAccounts(
        includeArchived: Boolean,
        includeSystem: Boolean,
    ): Flow<List<Account>> = accountDao
        .observeAll(includeArchived = includeArchived, includeSystem = includeSystem)
        .map { entities -> entities.map { it.toDomain() } }

    override fun observeOperationalAccounts(includeArchived: Boolean): Flow<List<Account>> =
        accountDao
            .observeBySubTypes(
                subTypes = LedgerRepository.OPERATIONAL_SUB_TYPES,
                includeArchived = includeArchived,
            )
            .map { entities ->
                entities
                    .filterNot { it.isSystem }
                    .map { it.toDomain() }
            }

    override fun observeCategories(
        type: AccountType,
        includeArchived: Boolean,
    ): Flow<List<Account>> = accountDao
        .observeByType(
            type = type,
            includeArchived = includeArchived,
            includeSystem = false,
        )
        .map { entities ->
            entities
                .filter { it.subType == AccountSubType.CATEGORY }
                .map { it.toDomain() }
        }

    override fun observeAccountBalances(
        status: TransactionStatus,
    ): Flow<List<AccountCurrencyBalanceRow>> =
        transactionDao.observeAccountOriginalBalances(status = status)

    override suspend fun getAccountById(accountId: String): Account? =
        accountDao.getById(accountId)?.toDomain()

    override suspend fun saveAccount(
        account: Account,
        openingBalance: Money?,
        openingExchangeRateOverride: ExchangeRateSnapshot?,
        nowEpochMs: Long,
    ) {
        ensureSeeded(nowEpochMs)
        val cachedRates = exchangeRateDao.getAll().toExchangeRateTable()

        database.withTransaction {
            accountDao.upsert(account.toEntity())

            if (openingBalance != null && openingBalance.isPositive) {
                require(openingBalance.currency == account.currency) {
                    "期初余额币种 (${openingBalance.currency.code}) 必须与账户币种 (${account.currency.code}) 一致"
                }
                val rateOverride = when {
                    account.currency == ledgerEngine.baseCurrency -> null
                    openingExchangeRateOverride != null -> openingExchangeRateOverride
                    cachedRates.findRate(account.currency, ledgerEngine.baseCurrency) != null -> null
                    else -> LedgerRepository.fallbackReferenceRate(
                        from = account.currency,
                        to = ledgerEngine.baseCurrency,
                        capturedAt = nowEpochMs,
                    )
                }
                val openingTx = ledgerEngine
                    .withRateProvider(cachedRates)
                    .buildOpeningBalance(
                        meta = TransactionMeta(
                            occurredAt = nowEpochMs,
                            payee = "期初余额",
                            note = "${account.name} 期初建账",
                            createdAt = nowEpochMs,
                            updatedAt = nowEpochMs,
                        ),
                        accountId = account.id,
                        accountType = account.type,
                        balance = openingBalance,
                        exchangeRateOverride = rateOverride,
                    )
                transactionWriter.saveTransaction(openingTx)
            }
        }
    }

    override suspend fun setAccountArchived(
        accountId: String,
        archived: Boolean,
        nowEpochMs: Long,
    ) {
        val existing = accountDao.getById(accountId) ?: return
        require(!existing.isSystem) { "系统内置科目不可归档" }
        accountDao.setArchived(accountId = accountId, archived = archived, updatedAt = nowEpochMs)
    }

    override suspend fun deleteAccountIfUnused(accountId: String): Result<Unit> = runCatching {
        val existing = accountDao.getById(accountId)
            ?: error("未找到目标科目")
        require(!existing.isSystem) { "系统内置科目不可删除" }
        val childCount = accountDao.countChildren(accountId)
        require(childCount == 0) { "该分类下仍有 $childCount 个子分类，请先移除或归档子分类" }
        val postingCount = transactionDao.countPostingsForAccount(accountId)
        require(postingCount == 0) { "该科目已关联 $postingCount 条记账分录，不可删除，请改用「归档」" }
        accountDao.deleteById(accountId)
    }

    override fun observeTags(includeArchived: Boolean): Flow<List<Tag>> = tagDao
        .observeAll(includeArchived = includeArchived)
        .map { entities -> entities.map { it.toDomain() } }

    override suspend fun saveTag(tag: Tag): Result<Unit> = runCatching {
        val trimmedName = tag.name.trim()
        require(trimmedName.isNotEmpty()) { "标签名称不能为空" }
        val conflict = tagDao.getByName(trimmedName)
        require(conflict == null || conflict.id == tag.id) {
            "标签「$trimmedName」已存在"
        }
        tagDao.upsert(tag.copy(name = trimmedName).toEntity())
    }

    override suspend fun setTagArchived(
        tagId: String,
        archived: Boolean,
        nowEpochMs: Long,
    ) {
        tagDao.setArchived(tagId = tagId, archived = archived, updatedAt = nowEpochMs)
    }

    override suspend fun deleteTag(tagId: String) {
        tagDao.deleteById(tagId)
    }

    override fun observeFilteredTransactions(
        filter: TransactionFilter,
    ): Flow<List<LedgerTransactionRecord>> = transactionDao
        .observeFiltered(filter)
        .map { list ->
            list.map { details ->
                LedgerTransactionRecord(
                    transaction = details.toDomain(),
                    tags = details.tags
                        .sortedWith(compareBy({ it.sortOrder }, { it.name }))
                        .map { it.toDomain() },
                )
            }
        }

    override fun observeFilteredPeriodSummary(
        baseCurrency: Currency,
        filter: TransactionFilter,
    ): Flow<PeriodSummaryRow> = transactionDao.observeFilteredPeriodSummary(
        baseCurrency = baseCurrency,
        filter = filter,
    )

    override fun observePeriodSummary(
        baseCurrency: Currency,
        startInclusive: Long?,
        endExclusive: Long?,
        status: TransactionStatus,
    ): Flow<PeriodSummaryRow> = transactionDao.observePeriodSummary(
        baseCurrency = baseCurrency,
        startInclusive = startInclusive,
        endExclusive = endExclusive,
        status = status,
    )

    override suspend fun getTransactionById(transactionId: String): Transaction? =
        transactionDao.getById(transactionId)?.toDomain()

    override suspend fun saveTransaction(
        transaction: Transaction,
        allowArchivedAccounts: Boolean,
    ) {
        ensureSeeded(transaction.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis())
        transactionWriter.saveTransaction(
            transaction = transaction,
            allowArchivedAccounts = allowArchivedAccounts,
        )
    }

    override suspend fun voidTransaction(
        transactionId: String,
        nowEpochMs: Long,
    ) {
        transactionWriter.voidTransaction(transactionId = transactionId, updatedAt = nowEpochMs)
    }

    override suspend fun restoreTransaction(
        transactionId: String,
        nowEpochMs: Long,
    ) {
        transactionWriter.restoreTransaction(transactionId = transactionId, updatedAt = nowEpochMs)
    }

    override suspend fun deleteTransaction(transactionId: String) {
        transactionWriter.deleteTransaction(transactionId)
    }

    override fun observeExchangeRateTable(): Flow<ExchangeRateTable> =
        exchangeRateDao.observeAll().map { it.toExchangeRateTable() }

    override suspend fun getExchangeRateTable(): ExchangeRateTable =
        exchangeRateDao.getAll().toExchangeRateTable()

    override suspend fun upsertExchangeRate(rate: CachedExchangeRate) {
        exchangeRateDao.upsert(rate.toEntity())
    }
}
