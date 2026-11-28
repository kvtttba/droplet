package io.github.kvtttba.droplet.core.database.repository

import androidx.room.withTransaction
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.debt.DebtProfileSummary
import io.github.kvtttba.droplet.core.domain.debt.DebtSummaryCalculator
import io.github.kvtttba.droplet.core.domain.debt.DebtTransactionHelper
import io.github.kvtttba.droplet.core.domain.debt.DebtTransactionRecord
import io.github.kvtttba.droplet.core.domain.investment.FallbackExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DebtProfile
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CreateDebtProfileRequest(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val currency: Currency = Currency.CNY,
    val existingReceivableAccountId: String? = null,
    val existingPayableAccountId: String? = null,
    val includeInNetWorth: Boolean = true,
    val defaultDueAt: Long? = null,
    val annualInterestRateDecimal: String? = null,
    val note: String? = null,
    val nowEpochMs: Long? = null,
)

data class DebtProfileCreatedResult(
    val profile: DebtProfile,
    val receivableAccount: Account,
    val payableAccount: Account,
)

data class UpdateDebtProfileRequest(
    val profileId: String,
    val name: String? = null,
    val defaultDueAt: Long? = null,
    val clearDefaultDueAt: Boolean = false,
    val annualInterestRateDecimal: String? = null,
    val clearAnnualInterestRate: Boolean = false,
    val note: String? = null,
    val clearNote: Boolean = false,
)

data class DebtLendRequest(
    val profileId: String,
    val fromAccountId: String,
    val amount: Money,
    val occurredAt: Long? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class DebtBorrowRequest(
    val profileId: String,
    val toAccountId: String,
    val amount: Money,
    val occurredAt: Long? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class DebtCollectRequest(
    val profileId: String,
    val depositAccountId: String,
    val principalAmount: Money,
    val interestAmount: Money = Money.zero(principalAmount.currency),
    val interestIncomeAccountId: String = DefaultSeedCatalog.AccountIds.INC_INVEST_INTEREST,
    val occurredAt: Long? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class DebtRepayRequest(
    val profileId: String,
    val paymentAccountId: String,
    val principalAmount: Money,
    val interestAmount: Money = Money.zero(principalAmount.currency),
    val interestExpenseAccountId: String = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
    val occurredAt: Long? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class DebtBadDebtWriteOffRequest(
    val profileId: String,
    val amount: Money,
    val badDebtExpenseAccountId: String = DefaultSeedCatalog.AccountIds.EXP_FINANCE_BAD_DEBT,
    val occurredAt: Long? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class DebtDetailSnapshot(
    val summary: DebtProfileSummary,
    val receivableAccount: Account?,
    val payableAccount: Account?,
    val transactions: List<DebtTransactionRecord>,
)

/**
 * Reactive repository coordinating counterparty [DebtProfile]s, linked `RECEIVABLE` & `PAYABLE`
 * accounts, atomic double-entry lend/borrow/collect/repay/bad-debt-write-off mutations,
 * and due-date reminder snapshots.
 */
interface DebtRepository {

    fun observeDebtSummaries(
        remindDaysBefore: Int = DebtSummaryCalculator.DEFAULT_REMIND_DAYS_BEFORE,
        asOfEpochMs: Long? = null,
    ): Flow<List<DebtProfileSummary>>

    fun observeDebtDetail(
        profileId: String,
        remindDaysBefore: Int = DebtSummaryCalculator.DEFAULT_REMIND_DAYS_BEFORE,
        asOfEpochMs: Long? = null,
    ): Flow<DebtDetailSnapshot?>

    suspend fun getDebtSummariesSnapshot(
        remindDaysBefore: Int = DebtSummaryCalculator.DEFAULT_REMIND_DAYS_BEFORE,
        asOfEpochMs: Long? = null,
    ): List<DebtProfileSummary>

    suspend fun createDebtProfile(request: CreateDebtProfileRequest): DebtProfileCreatedResult

    suspend fun updateDebtProfile(request: UpdateDebtProfileRequest): DebtProfile

    suspend fun recordLend(request: DebtLendRequest): Transaction

    suspend fun recordBorrow(request: DebtBorrowRequest): Transaction

    suspend fun recordCollect(request: DebtCollectRequest): Transaction

    suspend fun recordRepay(request: DebtRepayRequest): Transaction

    suspend fun recordBadDebtWriteOff(request: DebtBadDebtWriteOffRequest): Transaction

    suspend fun deleteDebtProfileIfUnused(profileId: String): Result<Unit>
}

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDebtRepository(
    private val database: DropletDatabase,
    private val transactionWriter: LedgerTransactionWriter,
    private val seeder: DefaultLedgerSeeder,
    private val ledgerEngine: LedgerEngine,
    private val clock: NetworkClock = NetworkClock.SYSTEM,
    private val zoneId: ZoneId = DebtSummaryCalculator.DEFAULT_ZONE_ID,
) : DebtRepository {

    private val accountDao = database.accountDao()
    private val debtProfileDao = database.debtProfileDao()
    private val transactionDao = database.transactionDao()
    private val exchangeRateDao = database.exchangeRateDao()
    private val seedMutex = Mutex()

    private suspend fun ensureSeeded(nowEpochMs: Long) {
        seedMutex.withLock {
            seeder.seedIfNeeded(nowEpochMs)
        }
    }

    override fun observeDebtSummaries(
        remindDaysBefore: Int,
        asOfEpochMs: Long?,
    ): Flow<List<DebtProfileSummary>> = combine(
        debtProfileDao.observeAll(),
        accountDao.observeAll(includeArchived = true, includeSystem = true),
        transactionDao.observeTransactionsForDebtAccounts(TransactionStatus.POSTED),
    ) { profileEntities, accountEntities, txEntities ->
        val effectiveAsOf = asOfEpochMs ?: clock.nowEpochMs()
        val accountsById = accountEntities.associate { it.id to it.toDomain() }
        val transactions = txEntities.map { it.toDomain() }

        profileEntities
            .map { entity ->
                val profile = entity.toDomain()
                DebtSummaryCalculator.summarize(
                    profile = profile,
                    receivableAccount = profile.receivableAccountId?.let { accountsById[it] },
                    payableAccount = profile.payableAccountId?.let { accountsById[it] },
                    transactions = transactions,
                    accountsById = accountsById,
                    asOfEpochMs = effectiveAsOf,
                    remindDaysBefore = remindDaysBefore,
                    zoneId = zoneId,
                )
            }
            .sortedWith(
                compareByDescending<DebtProfileSummary> { it.hasOutstandingBalance }
                    .thenBy { it.dueDate ?: java.time.LocalDate.MAX }
                    .thenBy { it.profile.name },
            )
    }

    override fun observeDebtDetail(
        profileId: String,
        remindDaysBefore: Int,
        asOfEpochMs: Long?,
    ): Flow<DebtDetailSnapshot?> = debtProfileDao.observeById(profileId).flatMapLatest { profileEntity ->
        if (profileEntity == null) {
            flowOf(null)
        } else {
            val profile = profileEntity.toDomain()
            val accountIds = listOfNotNull(profile.receivableAccountId, profile.payableAccountId)
            val txFlow = if (accountIds.isEmpty()) {
                flowOf(emptyList())
            } else {
                transactionDao.observeTransactionsForAccounts(accountIds, TransactionStatus.POSTED)
            }
            combine(
                accountDao.observeAll(includeArchived = true, includeSystem = true),
                txFlow,
            ) { accountEntities, txEntities ->
                val effectiveAsOf = asOfEpochMs ?: clock.nowEpochMs()
                val accountsById = accountEntities.associate { it.id to it.toDomain() }
                val transactions = txEntities.map { it.toDomain() }
                val recvAccount = profile.receivableAccountId?.let { accountsById[it] }
                val payAccount = profile.payableAccountId?.let { accountsById[it] }
                val summary = DebtSummaryCalculator.summarize(
                    profile = profile,
                    receivableAccount = recvAccount,
                    payableAccount = payAccount,
                    transactions = transactions,
                    accountsById = accountsById,
                    asOfEpochMs = effectiveAsOf,
                    remindDaysBefore = remindDaysBefore,
                    zoneId = zoneId,
                )
                val records = DebtSummaryCalculator.classifyTransactions(
                    profile = profile,
                    currency = summary.currency,
                    transactions = transactions,
                    accountsById = accountsById,
                )
                DebtDetailSnapshot(
                    summary = summary,
                    receivableAccount = recvAccount,
                    payableAccount = payAccount,
                    transactions = records,
                )
            }
        }
    }

    override suspend fun getDebtSummariesSnapshot(
        remindDaysBefore: Int,
        asOfEpochMs: Long?,
    ): List<DebtProfileSummary> {
        val effectiveAsOf = asOfEpochMs ?: clock.nowEpochMs()
        val profiles = debtProfileDao.getAll().map { it.toDomain() }
        if (profiles.isEmpty()) return emptyList()
        val transactions = transactionDao.getTransactionsForDebtAccounts(TransactionStatus.POSTED)
            .map { it.toDomain() }
        val referencedIds = buildSet {
            profiles.forEach { p ->
                p.receivableAccountId?.let(::add)
                p.payableAccountId?.let(::add)
            }
            transactions.forEach { tx -> tx.postings.forEach { add(it.accountId) } }
        }.toList()
        val accountsById = if (referencedIds.isEmpty()) {
            emptyMap()
        } else {
            accountDao.getByIds(referencedIds).associate { it.id to it.toDomain() }
        }
        return profiles.map { profile ->
            DebtSummaryCalculator.summarize(
                profile = profile,
                receivableAccount = profile.receivableAccountId?.let { accountsById[it] },
                payableAccount = profile.payableAccountId?.let { accountsById[it] },
                transactions = transactions,
                accountsById = accountsById,
                asOfEpochMs = effectiveAsOf,
                remindDaysBefore = remindDaysBefore,
                zoneId = zoneId,
            )
        }
    }

    override suspend fun createDebtProfile(request: CreateDebtProfileRequest): DebtProfileCreatedResult {
        val trimmedName = request.name.trim()
        require(trimmedName.isNotEmpty()) { "请输入往来人名称" }

        val now = request.nowEpochMs ?: clock.nowEpochMs()
        ensureSeeded(now)

        return database.withTransaction {
            val existingByName = debtProfileDao.getByName(trimmedName)
            require(existingByName == null || existingByName.id == request.id) {
                "往来人「$trimmedName」已存在"
            }

            val receivableAccount: Account = if (request.existingReceivableAccountId != null) {
                val acct = accountDao.getById(request.existingReceivableAccountId)?.toDomain()
                    ?: throw IllegalArgumentException("未找到应收往来账户：${request.existingReceivableAccountId}")
                require(acct.type == AccountType.ASSET && acct.subType == AccountSubType.RECEIVABLE) {
                    "「${acct.name}」必须为应收往来账户"
                }
                require(acct.currency == request.currency) {
                    "应收账户币种 (${acct.currency.code}) 必须与往来币种 (${request.currency.code}) 一致"
                }
                acct
            } else {
                val created = Account(
                    id = "debt:recv:${request.id}",
                    name = "$trimmedName · 应收",
                    type = AccountType.ASSET,
                    subType = AccountSubType.RECEIVABLE,
                    currency = request.currency,
                    icon = "card_giftcard",
                    color = "#00897B",
                    includeInNetWorth = request.includeInNetWorth,
                    isArchived = false,
                    isSystem = false,
                    sortOrder = 300,
                    createdAt = now,
                    updatedAt = now,
                )
                accountDao.upsert(created.toEntity())
                created
            }

            val payableAccount: Account = if (request.existingPayableAccountId != null) {
                val acct = accountDao.getById(request.existingPayableAccountId)?.toDomain()
                    ?: throw IllegalArgumentException("未找到应付往来账户：${request.existingPayableAccountId}")
                require(acct.type == AccountType.LIABILITY && acct.subType == AccountSubType.PAYABLE) {
                    "「${acct.name}」必须为应付往来账户"
                }
                require(acct.currency == request.currency) {
                    "应付账户币种 (${acct.currency.code}) 必须与往来币种 (${request.currency.code}) 一致"
                }
                acct
            } else {
                val created = Account(
                    id = "debt:pay:${request.id}",
                    name = "$trimmedName · 应付",
                    type = AccountType.LIABILITY,
                    subType = AccountSubType.PAYABLE,
                    currency = request.currency,
                    icon = "card_giftcard",
                    color = "#F4511E",
                    includeInNetWorth = request.includeInNetWorth,
                    isArchived = false,
                    isSystem = false,
                    sortOrder = 310,
                    createdAt = now,
                    updatedAt = now,
                )
                accountDao.upsert(created.toEntity())
                created
            }

            val profile = DebtProfile(
                id = request.id,
                name = trimmedName,
                receivableAccountId = receivableAccount.id,
                payableAccountId = payableAccount.id,
                defaultDueAt = request.defaultDueAt,
                annualInterestRateDecimal = request.annualInterestRateDecimal,
                note = request.note?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = now,
                updatedAt = now,
            )
            debtProfileDao.upsert(profile.toEntity())

            DebtProfileCreatedResult(
                profile = profile,
                receivableAccount = receivableAccount,
                payableAccount = payableAccount,
            )
        }
    }

    override suspend fun updateDebtProfile(request: UpdateDebtProfileRequest): DebtProfile {
        val now = clock.nowEpochMs()
        return database.withTransaction {
            val existing = debtProfileDao.getById(request.profileId)?.toDomain()
                ?: throw IllegalArgumentException("未找到往来人：${request.profileId}")

            val resolvedName = request.name?.trim()?.also { name ->
                require(name.isNotEmpty()) { "往来人名称不能为空" }
                val conflict = debtProfileDao.getByName(name)
                require(conflict == null || conflict.id == existing.id) {
                    "往来人「$name」已存在"
                }
            } ?: existing.name

            val resolvedDueAt = when {
                request.clearDefaultDueAt -> null
                request.defaultDueAt != null -> request.defaultDueAt
                else -> existing.defaultDueAt
            }
            val resolvedRate = when {
                request.clearAnnualInterestRate -> null
                request.annualInterestRateDecimal != null -> request.annualInterestRateDecimal
                else -> existing.annualInterestRateDecimal
            }
            val resolvedNote = when {
                request.clearNote -> null
                request.note != null -> request.note.trim().takeIf { it.isNotEmpty() }
                else -> existing.note
            }

            val updated = existing.copy(
                name = resolvedName,
                defaultDueAt = resolvedDueAt,
                annualInterestRateDecimal = resolvedRate,
                note = resolvedNote,
                updatedAt = now,
            )
            debtProfileDao.upsert(updated.toEntity())

            if (resolvedName != existing.name) {
                existing.receivableAccountId?.let { recvId ->
                    if (debtProfileDao.countProfilesReferencingAccount(recvId) == 1) {
                        accountDao.getById(recvId)?.let { entity ->
                            accountDao.upsert(entity.copy(name = "$resolvedName · 应收", updatedAt = now))
                        }
                    }
                }
                existing.payableAccountId?.let { payId ->
                    if (debtProfileDao.countProfilesReferencingAccount(payId) == 1) {
                        accountDao.getById(payId)?.let { entity ->
                            accountDao.upsert(entity.copy(name = "$resolvedName · 应付", updatedAt = now))
                        }
                    }
                }
            }

            updated
        }
    }

    override suspend fun recordLend(request: DebtLendRequest): Transaction {
        val now = clock.nowEpochMs()
        ensureSeeded(now)
        return database.withTransaction {
            val profile = requireProfile(request.profileId)
            val receivableAccount = requireReceivableAccount(profile)
            val fromAccount = accountDao.getById(request.fromAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到付款账户：${request.fromAccountId}")
            val occurredAt = request.occurredAt ?: now
            val rateProvider = currentRateProviderWithFallback(occurredAt)

            val tx = DebtTransactionHelper.buildLendTransaction(
                ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                profile = profile,
                receivableAccount = receivableAccount,
                fromAccount = fromAccount,
                amount = request.amount,
                occurredAt = occurredAt,
                note = request.note,
                tagIds = request.tagIds,
                createdAt = now,
                updatedAt = now,
                exchangeRateOverride = request.exchangeRateOverride,
                rateProviderOverride = rateProvider,
            )
            transactionWriter.saveTransaction(tx)
            tx
        }
    }

    override suspend fun recordBorrow(request: DebtBorrowRequest): Transaction {
        val now = clock.nowEpochMs()
        ensureSeeded(now)
        return database.withTransaction {
            val profile = requireProfile(request.profileId)
            val payableAccount = requirePayableAccount(profile)
            val toAccount = accountDao.getById(request.toAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到收款账户：${request.toAccountId}")
            val occurredAt = request.occurredAt ?: now
            val rateProvider = currentRateProviderWithFallback(occurredAt)

            val tx = DebtTransactionHelper.buildBorrowTransaction(
                ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                profile = profile,
                payableAccount = payableAccount,
                toAccount = toAccount,
                amount = request.amount,
                occurredAt = occurredAt,
                note = request.note,
                tagIds = request.tagIds,
                createdAt = now,
                updatedAt = now,
                exchangeRateOverride = request.exchangeRateOverride,
                rateProviderOverride = rateProvider,
            )
            transactionWriter.saveTransaction(tx)
            tx
        }
    }

    override suspend fun recordCollect(request: DebtCollectRequest): Transaction {
        val now = clock.nowEpochMs()
        ensureSeeded(now)
        return database.withTransaction {
            val profile = requireProfile(request.profileId)
            val receivableAccount = requireReceivableAccount(profile)
            val depositAccount = accountDao.getById(request.depositAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到收款账户：${request.depositAccountId}")
            val interestAccount = if (request.interestAmount.isPositive) {
                accountDao.getById(request.interestIncomeAccountId)?.toDomain()
                    ?: throw IllegalArgumentException("未找到利息收入分类：${request.interestIncomeAccountId}")
            } else {
                null
            }
            val currentReceivableBalance = computeCurrentReceivableBalance(receivableAccount)
            val occurredAt = request.occurredAt ?: now
            val rateProvider = currentRateProviderWithFallback(occurredAt)

            val tx = DebtTransactionHelper.buildCollectTransaction(
                ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                profile = profile,
                receivableAccount = receivableAccount,
                depositAccount = depositAccount,
                principalAmount = request.principalAmount,
                interestAmount = request.interestAmount,
                interestIncomeAccount = interestAccount,
                currentReceivableBalance = currentReceivableBalance,
                occurredAt = occurredAt,
                note = request.note,
                tagIds = request.tagIds,
                createdAt = now,
                updatedAt = now,
                exchangeRateOverride = request.exchangeRateOverride,
                rateProviderOverride = rateProvider,
            )
            transactionWriter.saveTransaction(tx)
            tx
        }
    }

    override suspend fun recordRepay(request: DebtRepayRequest): Transaction {
        val now = clock.nowEpochMs()
        ensureSeeded(now)
        return database.withTransaction {
            val profile = requireProfile(request.profileId)
            val payableAccount = requirePayableAccount(profile)
            val paymentAccount = accountDao.getById(request.paymentAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到付款账户：${request.paymentAccountId}")
            val interestAccount = if (request.interestAmount.isPositive) {
                accountDao.getById(request.interestExpenseAccountId)?.toDomain()
                    ?: throw IllegalArgumentException("未找到利息支出分类：${request.interestExpenseAccountId}")
            } else {
                null
            }
            val currentPayableBalance = computeCurrentPayableBalance(payableAccount)
            val occurredAt = request.occurredAt ?: now
            val rateProvider = currentRateProviderWithFallback(occurredAt)

            val tx = DebtTransactionHelper.buildRepayTransaction(
                ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                profile = profile,
                payableAccount = payableAccount,
                paymentAccount = paymentAccount,
                principalAmount = request.principalAmount,
                interestAmount = request.interestAmount,
                interestExpenseAccount = interestAccount,
                currentPayableBalance = currentPayableBalance,
                occurredAt = occurredAt,
                note = request.note,
                tagIds = request.tagIds,
                createdAt = now,
                updatedAt = now,
                exchangeRateOverride = request.exchangeRateOverride,
                rateProviderOverride = rateProvider,
            )
            transactionWriter.saveTransaction(tx)
            tx
        }
    }

    override suspend fun recordBadDebtWriteOff(request: DebtBadDebtWriteOffRequest): Transaction {
        val now = clock.nowEpochMs()
        ensureSeeded(now)
        return database.withTransaction {
            val profile = requireProfile(request.profileId)
            val receivableAccount = requireReceivableAccount(profile)
            val badDebtExpenseAccount = accountDao.getById(request.badDebtExpenseAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到坏账损失分类：${request.badDebtExpenseAccountId}")
            val currentReceivableBalance = computeCurrentReceivableBalance(receivableAccount)
            val occurredAt = request.occurredAt ?: now
            val rateProvider = currentRateProviderWithFallback(occurredAt)

            val tx = DebtTransactionHelper.buildBadDebtWriteOffTransaction(
                ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                profile = profile,
                receivableAccount = receivableAccount,
                badDebtExpenseAccount = badDebtExpenseAccount,
                amount = request.amount,
                currentReceivableBalance = currentReceivableBalance,
                occurredAt = occurredAt,
                note = request.note,
                tagIds = request.tagIds,
                createdAt = now,
                updatedAt = now,
                exchangeRateOverride = request.exchangeRateOverride,
                rateProviderOverride = rateProvider,
            )
            transactionWriter.saveTransaction(tx)
            tx
        }
    }

    override suspend fun deleteDebtProfileIfUnused(profileId: String): Result<Unit> = runCatching {
        database.withTransaction {
            val profile = debtProfileDao.getById(profileId)?.toDomain()
                ?: error("未找到往来人")
            val recvPostings = profile.receivableAccountId?.let { transactionDao.countPostingsForAccount(it) } ?: 0
            val payPostings = profile.payableAccountId?.let { transactionDao.countPostingsForAccount(it) } ?: 0
            val totalPostings = recvPostings + payPostings
            require(totalPostings == 0) {
                "该往来人已有 $totalPostings 条借贷分录，不可删除"
            }

            val recvAccountId = profile.receivableAccountId
            val payAccountId = profile.payableAccountId
            val recvRefs = recvAccountId?.let {
                debtProfileDao.countProfilesReferencingAccount(it)
            } ?: 0
            val payRefs = payAccountId?.let {
                debtProfileDao.countProfilesReferencingAccount(it)
            } ?: 0

            debtProfileDao.deleteById(profileId)

            if (recvAccountId != null && recvRefs <= 1) {
                val acct = accountDao.getById(recvAccountId)
                if (acct != null && !acct.isSystem) {
                    accountDao.deleteById(acct.id)
                }
            }
            if (payAccountId != null && payRefs <= 1) {
                val acct = accountDao.getById(payAccountId)
                if (acct != null && !acct.isSystem) {
                    accountDao.deleteById(acct.id)
                }
            }
        }
    }

    private suspend fun requireProfile(profileId: String): DebtProfile =
        debtProfileDao.getById(profileId)?.toDomain()
            ?: throw IllegalArgumentException("未找到往来人：$profileId")

    private suspend fun requireReceivableAccount(profile: DebtProfile): Account {
        val id = profile.receivableAccountId
            ?: throw IllegalStateException("往来人「${profile.name}」未绑定应收账户")
        return accountDao.getById(id)?.toDomain()
            ?: throw IllegalStateException("未找到应收账户：$id")
    }

    private suspend fun requirePayableAccount(profile: DebtProfile): Account {
        val id = profile.payableAccountId
            ?: throw IllegalStateException("往来人「${profile.name}」未绑定应付账户")
        return accountDao.getById(id)?.toDomain()
            ?: throw IllegalStateException("未找到应付账户：$id")
    }

    private suspend fun computeCurrentReceivableBalance(receivableAccount: Account): Money {
        val txs = transactionDao.getTransactionsForAccount(receivableAccount.id, TransactionStatus.POSTED)
            .map { it.toDomain() }
        var total = 0L
        for (tx in txs) {
            for (posting in tx.businessPostings) {
                if (posting.accountId == receivableAccount.id && posting.currency == receivableAccount.currency) {
                    total = Math.addExact(total, posting.amountMinor)
                }
            }
        }
        return Money(total.coerceAtLeast(0L), receivableAccount.currency)
    }

    private suspend fun computeCurrentPayableBalance(payableAccount: Account): Money {
        val txs = transactionDao.getTransactionsForAccount(payableAccount.id, TransactionStatus.POSTED)
            .map { it.toDomain() }
        var raw = 0L
        for (tx in txs) {
            for (posting in tx.businessPostings) {
                if (posting.accountId == payableAccount.id && posting.currency == payableAccount.currency) {
                    raw = Math.addExact(raw, posting.amountMinor)
                }
            }
        }
        return Money(Math.negateExact(raw).coerceAtLeast(0L), payableAccount.currency)
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
