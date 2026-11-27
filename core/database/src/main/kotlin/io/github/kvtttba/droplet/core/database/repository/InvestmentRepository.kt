package io.github.kvtttba.droplet.core.database.repository

import androidx.room.withTransaction
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.investment.BankWealthManualValuationResult
import io.github.kvtttba.droplet.core.domain.investment.BankWealthValuationHelper
import io.github.kvtttba.droplet.core.domain.investment.FallbackExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.investment.FixedDepositCalculator
import io.github.kvtttba.droplet.core.domain.investment.OversellPositionException
import io.github.kvtttba.droplet.core.domain.investment.PortfolioPositionEngine
import io.github.kvtttba.droplet.core.domain.investment.PortfolioPositionValuation
import io.github.kvtttba.droplet.core.domain.investment.PortfolioSummary
import io.github.kvtttba.droplet.core.domain.investment.PortfolioSummaryAggregator
import io.github.kvtttba.droplet.core.domain.investment.SellCostBasisPreview
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DepositInterestMode
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionSource
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.model.UnitPrice
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CreateInvestmentAssetRequest(
    val id: String = UUID.randomUUID().toString(),
    val assetClass: AssetClass,
    val symbol: String,
    val name: String,
    val currency: Currency,
    val existingHoldingAccountId: String? = null,
    val holdingAccountName: String? = null,
    val includeInNetWorth: Boolean = true,
    val icon: String? = null,
    val color: String? = null,
    val sortOrder: Int = 100,
    val quoteProviderHint: String? = null,
    val depositRateAnnualDecimal: String? = null,
    val depositStartAt: Long? = null,
    val depositMaturityAt: Long? = null,
    val depositInterestMode: DepositInterestMode? = null,
    val nowEpochMs: Long? = null,
)

data class InvestmentBuyRequest(
    val assetId: String,
    val cashAccountId: String,
    val quantity: Quantity,
    val unitPrice: UnitPrice,
    val totalAmount: Money? = null,
    val fee: Money? = null,
    val feeExpenseAccountId: String = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
    val isDca: Boolean = false,
    val occurredAt: Long,
    val payee: String? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val source: TransactionSource = TransactionSource.MANUAL,
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class InvestmentSellRequest(
    val assetId: String,
    val cashAccountId: String,
    val quantity: Quantity,
    val unitPrice: UnitPrice,
    val grossProceeds: Money? = null,
    val fee: Money? = null,
    val feeExpenseAccountId: String = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
    val realizedGainIncomeAccountId: String = DefaultSeedCatalog.AccountIds.INC_INVEST_CAPITAL_GAIN,
    val realizedLossExpenseAccountId: String = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INVEST_LOSS,
    val occurredAt: Long,
    val payee: String? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val source: TransactionSource = TransactionSource.MANUAL,
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class InvestmentSellResult(
    val transaction: Transaction,
    val preview: SellCostBasisPreview,
)

data class InvestmentDividendRequest(
    val assetId: String,
    val cashAccountId: String,
    val amount: Money,
    val taxOrFee: Money? = null,
    val dividendIncomeAccountId: String = DefaultSeedCatalog.AccountIds.INC_INVEST_DIVIDEND,
    val taxExpenseAccountId: String = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
    val occurredAt: Long,
    val payee: String? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val source: TransactionSource = TransactionSource.MANUAL,
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class OpenFixedDepositRequest(
    val assetRequest: CreateInvestmentAssetRequest,
    val cashAccountId: String,
    val principal: Money,
    val occurredAt: Long? = null,
    val payee: String? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val source: TransactionSource = TransactionSource.MANUAL,
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class FixedDepositOpenResult(
    val asset: InvestmentAsset,
    val transaction: Transaction,
)

data class FixedDepositSettlementRequest(
    val assetId: String,
    val cashAccountId: String,
    /** When null, settles the entire remaining principal (full maturity settlement). */
    val withdrawnPrincipal: Money? = null,
    /** When null, defaults to the locally calculated unpaid accrued interest as of [occurredAt]. */
    val actualInterestAmount: Money? = null,
    val interestIncomeAccountId: String = DefaultSeedCatalog.AccountIds.INC_INVEST_INTEREST,
    val penaltyOrTaxFee: Money? = null,
    val penaltyFeeExpenseAccountId: String = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
    val occurredAt: Long,
    val payee: String? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val source: TransactionSource = TransactionSource.MANUAL,
    val archiveHoldingAccountOnFullSettlement: Boolean = false,
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class FixedDepositInterestPayoutRequest(
    val assetId: String,
    val cashAccountId: String,
    val interestAmount: Money,
    val taxOrFee: Money? = null,
    val interestIncomeAccountId: String = DefaultSeedCatalog.AccountIds.INC_INVEST_INTEREST,
    val taxExpenseAccountId: String = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
    val occurredAt: Long,
    val payee: String? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val source: TransactionSource = TransactionSource.MANUAL,
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

/**
 * Investment business repository coordinating Room DAOs, [LedgerTransactionWriter], [LedgerEngine],
 * [MarketDataRepository], and pure domain investment valuation engines.
 */
interface InvestmentRepository {

    fun observeInvestmentAssets(includeArchived: Boolean = false): Flow<List<InvestmentAsset>>

    suspend fun getInvestmentAsset(assetId: String): InvestmentAsset?

    suspend fun createInvestmentAsset(request: CreateInvestmentAssetRequest): InvestmentAsset

    suspend fun updateInvestmentAsset(
        asset: InvestmentAsset,
        nowEpochMs: Long? = null,
    ): InvestmentAsset

    suspend fun setInvestmentAssetArchived(
        assetId: String,
        archived: Boolean,
        nowEpochMs: Long? = null,
    )

    suspend fun deleteInvestmentAssetIfUnused(assetId: String): Result<Unit>

    suspend fun recordBuy(request: InvestmentBuyRequest): Transaction

    suspend fun recordDcaBuy(request: InvestmentBuyRequest): Transaction =
        recordBuy(request.copy(isDca = true))

    suspend fun previewSell(
        assetId: String,
        quantity: Quantity,
        unitPrice: UnitPrice,
        grossProceeds: Money? = null,
        asOfEpochMs: Long? = null,
    ): SellCostBasisPreview

    suspend fun recordSell(request: InvestmentSellRequest): InvestmentSellResult

    suspend fun recordDividend(request: InvestmentDividendRequest): Transaction

    suspend fun openFixedDeposit(request: OpenFixedDepositRequest): FixedDepositOpenResult

    suspend fun recordFixedDepositSettlement(request: FixedDepositSettlementRequest): Transaction

    suspend fun recordFixedDepositInterestPayout(request: FixedDepositInterestPayoutRequest): Transaction

    suspend fun updateBankWealthByUnitPrice(
        assetId: String,
        manualUnitPrice: UnitPrice,
        prevCloseOverride: UnitPrice? = null,
        updatedAtEpochMs: Long? = null,
    ): BankWealthManualValuationResult

    suspend fun updateBankWealthByProfitAmount(
        assetId: String,
        latestProfitAmount: Money,
        prevCloseOverride: UnitPrice? = null,
        updatedAtEpochMs: Long? = null,
    ): BankWealthManualValuationResult

    suspend fun updateBankWealthByTotalValue(
        assetId: String,
        currentTotalValue: Money,
        prevCloseOverride: UnitPrice? = null,
        updatedAtEpochMs: Long? = null,
    ): BankWealthManualValuationResult

    fun observeAssetTransactions(assetId: String): Flow<List<Transaction>>

    fun observePortfolioPositions(
        baseCurrency: Currency = Currency.DEFAULT,
        includeZeroPositions: Boolean = true,
        includeArchived: Boolean = false,
        asOfEpochMs: Long? = null,
    ): Flow<List<PortfolioPositionValuation>>

    fun observePosition(
        assetId: String,
        baseCurrency: Currency = Currency.DEFAULT,
        asOfEpochMs: Long? = null,
    ): Flow<PortfolioPositionValuation?>

    fun observePortfolioSummary(
        baseCurrency: Currency = Currency.DEFAULT,
        asOfEpochMs: Long? = null,
    ): Flow<PortfolioSummary>
}

class LocalInvestmentRepository(
    private val database: DropletDatabase,
    private val transactionWriter: LedgerTransactionWriter,
    private val seeder: DefaultLedgerSeeder,
    private val ledgerEngine: LedgerEngine,
    private val marketDataRepository: MarketDataRepository,
    private val clock: NetworkClock = NetworkClock.SYSTEM,
    private val zoneId: ZoneId = FixedDepositCalculator.DEFAULT_ZONE_ID,
) : InvestmentRepository {

    private val accountDao = database.accountDao()
    private val investmentAssetDao = database.investmentAssetDao()
    private val marketQuoteDao = database.marketQuoteDao()
    private val transactionDao = database.transactionDao()
    private val exchangeRateDao = database.exchangeRateDao()
    private val seedMutex = Mutex()

    private suspend fun ensureSeeded(nowEpochMs: Long) {
        seedMutex.withLock {
            seeder.seedIfNeeded(nowEpochMs)
        }
    }

    override fun observeInvestmentAssets(includeArchived: Boolean): Flow<List<InvestmentAsset>> =
        combine(
            investmentAssetDao.observeAll(),
            accountDao.observeAll(includeArchived = true, includeSystem = true),
        ) { assetEntities, accountEntities ->
            val accountsById = accountEntities.associateBy { it.id }
            assetEntities
                .filter { entity ->
                    includeArchived || accountsById[entity.accountId]?.isArchived != true
                }
                .map { it.toDomain() }
        }

    override suspend fun getInvestmentAsset(assetId: String): InvestmentAsset? =
        investmentAssetDao.getById(assetId)?.toDomain()

    override suspend fun createInvestmentAsset(
        request: CreateInvestmentAssetRequest,
    ): InvestmentAsset {
        val now = request.nowEpochMs ?: clock.nowEpochMs()
        ensureSeeded(now)
        validateAssetMetadata(
            assetClass = request.assetClass,
            depositRateAnnualDecimal = request.depositRateAnnualDecimal,
            depositStartAt = request.depositStartAt,
            depositMaturityAt = request.depositMaturityAt,
            depositInterestMode = request.depositInterestMode,
        )

        return database.withTransaction {
            createInvestmentAssetInTx(request = request, now = now)
        }
    }

    override suspend fun updateInvestmentAsset(
        asset: InvestmentAsset,
        nowEpochMs: Long?,
    ): InvestmentAsset {
        val now = nowEpochMs ?: clock.nowEpochMs()
        validateAssetMetadata(
            assetClass = asset.assetClass,
            depositRateAnnualDecimal = asset.depositRateAnnualDecimal,
            depositStartAt = asset.depositStartAt,
            depositMaturityAt = asset.depositMaturityAt,
            depositInterestMode = asset.depositInterestMode,
        )

        return database.withTransaction {
            val existing = investmentAssetDao.getById(asset.id)?.toDomain()
                ?: throw IllegalArgumentException("Unknown investment asset id: '${asset.id}'")
            val holdingAccount = accountDao.getById(asset.accountId)?.toDomain()
                ?: throw IllegalArgumentException("Unknown holding account id: '${asset.accountId}'")
            require(
                holdingAccount.type == AccountType.ASSET &&
                    holdingAccount.subType == AccountSubType.INVEST_HOLDING,
            ) {
                "Account '${asset.accountId}' must be an ASSET / INVEST_HOLDING account"
            }
            require(holdingAccount.currency == asset.currency) {
                "Holding account currency (${holdingAccount.currency}) must match asset currency (${asset.currency})"
            }

            if (existing.accountId != asset.accountId || existing.currency != asset.currency) {
                val postingCount = transactionDao.countPostingsForInvestmentAsset(asset.id)
                require(postingCount == 0) {
                    "Cannot change holding account or currency for asset '${asset.id}' once $postingCount postings exist"
                }
            }

            val updated = asset.copy(
                createdAt = existing.createdAt,
                updatedAt = now,
            )
            investmentAssetDao.upsert(updated.toEntity())
            updated
        }
    }

    override suspend fun setInvestmentAssetArchived(
        assetId: String,
        archived: Boolean,
        nowEpochMs: Long?,
    ) {
        val now = nowEpochMs ?: clock.nowEpochMs()
        database.withTransaction {
            val asset = investmentAssetDao.getById(assetId)?.toDomain()
                ?: throw IllegalArgumentException("Unknown investment asset id: '$assetId'")
            val siblings = investmentAssetDao.getByAccountId(asset.accountId)
            if (archived && siblings.size > 1) {
                throw IllegalStateException(
                    "Holding account '${asset.accountId}' is shared by ${siblings.size} assets; archive the holding account explicitly",
                )
            }
            accountDao.setArchived(
                accountId = asset.accountId,
                archived = archived,
                updatedAt = now,
            )
        }
    }

    override suspend fun deleteInvestmentAssetIfUnused(assetId: String): Result<Unit> = runCatching {
        database.withTransaction {
            val asset = investmentAssetDao.getById(assetId)?.toDomain()
                ?: error("未找到目标投资标的")
            val assetPostings = transactionDao.countPostingsForInvestmentAsset(assetId)
            require(assetPostings == 0) {
                "该投资标的已关联 $assetPostings 条交易分录，不可删除，请改用「归档」"
            }
            val linkedAssets = investmentAssetDao.countByAccountId(asset.accountId)
            investmentAssetDao.deleteById(assetId)
            if (linkedAssets <= 1) {
                val account = accountDao.getById(asset.accountId)
                val accountPostings = transactionDao.countPostingsForAccount(asset.accountId)
                if (account != null && !account.isSystem && accountPostings == 0) {
                    accountDao.deleteById(asset.accountId)
                }
            }
        }
    }

    override suspend fun recordBuy(request: InvestmentBuyRequest): Transaction {
        val now = clock.nowEpochMs()
        ensureSeeded(now)

        return database.withTransaction {
            val asset = investmentAssetDao.getById(request.assetId)?.toDomain()
                ?: throw IllegalArgumentException("Unknown investment asset id: '${request.assetId}'")
            val cashAccount = accountDao.getById(request.cashAccountId)?.toDomain()
                ?: throw IllegalArgumentException("Unknown cash account id: '${request.cashAccountId}'")
            require(!cashAccount.isArchived) {
                "Cash account '${request.cashAccountId}' is archived"
            }
            require(cashAccount.currency == asset.currency) {
                "Cash account currency (${cashAccount.currency}) must match asset currency (${asset.currency}); " +
                    "record FX conversion into a ${asset.currency} cash account first"
            }

            val rateProvider = currentRateProviderWithFallback(request.occurredAt)
            val resolvedNote = request.note ?: if (request.isDca) "定投 ${asset.name}" else null
            val resolvedPayee = request.payee ?: asset.name

            val tx = ledgerEngine
                .withRateProvider(rateProvider)
                .buildInvestmentBuy(
                    meta = TransactionMeta(
                        occurredAt = request.occurredAt,
                        payee = resolvedPayee,
                        note = resolvedNote,
                        source = request.source,
                        tagIds = request.tagIds,
                        createdAt = now,
                        updatedAt = now,
                    ),
                    holdingAccountId = asset.accountId,
                    cashAccountId = request.cashAccountId,
                    investmentAssetId = asset.id,
                    quantity = request.quantity,
                    unitPrice = request.unitPrice,
                    currency = asset.currency,
                    totalAmount = request.totalAmount,
                    fee = request.fee,
                    feeExpenseAccountId = request.feeExpenseAccountId.takeIf { request.fee != null },
                    exchangeRateOverride = request.exchangeRateOverride,
                )

            transactionWriter.saveTransaction(tx)
            tx
        }
    }

    override suspend fun previewSell(
        assetId: String,
        quantity: Quantity,
        unitPrice: UnitPrice,
        grossProceeds: Money?,
        asOfEpochMs: Long?,
    ): SellCostBasisPreview {
        val asset = investmentAssetDao.getById(assetId)?.toDomain()
            ?: throw IllegalArgumentException("Unknown investment asset id: '$assetId'")
        val existingTxs = transactionDao.getInvestmentTransactionsForAsset(assetId)
            .map { it.toDomain() }
        val accountsById = loadReferencedAccounts(existingTxs)
        return PortfolioPositionEngine.previewSell(
            asset = asset,
            transactions = existingTxs,
            sellQuantity = quantity,
            unitPrice = unitPrice,
            grossProceeds = grossProceeds,
            accounts = accountsById,
            asOfEpochMs = asOfEpochMs,
        )
    }

    override suspend fun recordSell(request: InvestmentSellRequest): InvestmentSellResult {
        val now = clock.nowEpochMs()
        ensureSeeded(now)

        return database.withTransaction {
            val asset = investmentAssetDao.getById(request.assetId)?.toDomain()
                ?: throw IllegalArgumentException("Unknown investment asset id: '${request.assetId}'")
            val cashAccount = accountDao.getById(request.cashAccountId)?.toDomain()
                ?: throw IllegalArgumentException("Unknown cash account id: '${request.cashAccountId}'")
            require(!cashAccount.isArchived) {
                "Cash account '${request.cashAccountId}' is archived"
            }
            require(cashAccount.currency == asset.currency) {
                "Cash account currency (${cashAccount.currency}) must match asset currency (${asset.currency})"
            }

            val existingTxs = transactionDao.getInvestmentTransactionsForAsset(asset.id)
                .map { it.toDomain() }
            val accountsById = loadReferencedAccounts(existingTxs)

            val preview = PortfolioPositionEngine.previewSell(
                asset = asset,
                transactions = existingTxs,
                sellQuantity = request.quantity,
                unitPrice = request.unitPrice,
                grossProceeds = request.grossProceeds,
                accounts = accountsById,
                asOfEpochMs = request.occurredAt,
            )

            val realizedPnLAccountId = when {
                preview.estimatedRealizedPnL.isPositive -> request.realizedGainIncomeAccountId
                preview.estimatedRealizedPnL.isNegative -> request.realizedLossExpenseAccountId
                else -> null
            }

            val rateProvider = currentRateProviderWithFallback(request.occurredAt)
            val tx = ledgerEngine
                .withRateProvider(rateProvider)
                .buildInvestmentSell(
                    meta = TransactionMeta(
                        occurredAt = request.occurredAt,
                        payee = request.payee ?: asset.name,
                        note = request.note,
                        source = request.source,
                        tagIds = request.tagIds,
                        createdAt = now,
                        updatedAt = now,
                    ),
                    holdingAccountId = asset.accountId,
                    cashAccountId = request.cashAccountId,
                    investmentAssetId = asset.id,
                    quantity = request.quantity,
                    unitPrice = request.unitPrice,
                    currency = asset.currency,
                    grossProceeds = preview.grossProceeds,
                    fee = request.fee,
                    feeExpenseAccountId = request.feeExpenseAccountId.takeIf { request.fee != null },
                    costBasis = preview.costBasis,
                    realizedPnLAccountId = realizedPnLAccountId,
                    exchangeRateOverride = request.exchangeRateOverride,
                )

            // Guard chronological consistency if the sell is backdated before later sells.
            val allAccountsForReplay = accountsById + loadReferencedAccounts(listOf(tx))
            PortfolioPositionEngine.replayLedgerState(
                asset = asset,
                transactions = existingTxs + tx,
                accounts = allAccountsForReplay,
            )

            transactionWriter.saveTransaction(tx)
            InvestmentSellResult(transaction = tx, preview = preview)
        }
    }

    override suspend fun recordDividend(request: InvestmentDividendRequest): Transaction {
        val now = clock.nowEpochMs()
        ensureSeeded(now)

        return database.withTransaction {
            val asset = investmentAssetDao.getById(request.assetId)?.toDomain()
                ?: throw IllegalArgumentException("Unknown investment asset id: '${request.assetId}'")
            val cashAccount = accountDao.getById(request.cashAccountId)?.toDomain()
                ?: throw IllegalArgumentException("Unknown cash account id: '${request.cashAccountId}'")
            require(!cashAccount.isArchived) {
                "Cash account '${request.cashAccountId}' is archived"
            }
            require(cashAccount.currency == asset.currency) {
                "Cash account currency (${cashAccount.currency}) must match asset currency (${asset.currency})"
            }
            require(request.amount.currency == asset.currency) {
                "Dividend amount currency (${request.amount.currency}) must match asset currency (${asset.currency})"
            }

            val rateProvider = currentRateProviderWithFallback(request.occurredAt)
            val tx = ledgerEngine
                .withRateProvider(rateProvider)
                .buildInvestmentDividend(
                    meta = TransactionMeta(
                        occurredAt = request.occurredAt,
                        payee = request.payee ?: "${asset.name} 分红",
                        note = request.note,
                        source = request.source,
                        tagIds = request.tagIds,
                        createdAt = now,
                        updatedAt = now,
                    ),
                    cashAccountId = request.cashAccountId,
                    dividendIncomeAccountId = request.dividendIncomeAccountId,
                    investmentAssetId = asset.id,
                    amount = request.amount,
                    taxOrFee = request.taxOrFee,
                    taxExpenseAccountId = request.taxExpenseAccountId.takeIf { request.taxOrFee != null },
                    exchangeRateOverride = request.exchangeRateOverride,
                )

            transactionWriter.saveTransaction(tx)
            tx
        }
    }

    override suspend fun openFixedDeposit(
        request: OpenFixedDepositRequest,
    ): FixedDepositOpenResult {
        require(request.assetRequest.assetClass == AssetClass.FIXED_DEPOSIT) {
            "openFixedDeposit requires AssetClass.FIXED_DEPOSIT, got ${request.assetRequest.assetClass}"
        }
        require(request.principal.isPositive) {
            "Fixed deposit principal must be strictly positive, got ${request.principal}"
        }
        require(request.principal.currency == request.assetRequest.currency) {
            "Principal currency (${request.principal.currency}) must match asset currency (${request.assetRequest.currency})"
        }
        val now = request.assetRequest.nowEpochMs ?: clock.nowEpochMs()
        val occurredAt = request.occurredAt ?: request.assetRequest.depositStartAt ?: now
        ensureSeeded(now)
        validateAssetMetadata(
            assetClass = request.assetRequest.assetClass,
            depositRateAnnualDecimal = request.assetRequest.depositRateAnnualDecimal,
            depositStartAt = request.assetRequest.depositStartAt,
            depositMaturityAt = request.assetRequest.depositMaturityAt,
            depositInterestMode = request.assetRequest.depositInterestMode,
        )

        return database.withTransaction {
            val cashAccount = accountDao.getById(request.cashAccountId)?.toDomain()
                ?: throw IllegalArgumentException("Unknown cash account id: '${request.cashAccountId}'")
            require(!cashAccount.isArchived) {
                "Cash account '${request.cashAccountId}' is archived"
            }
            require(cashAccount.currency == request.principal.currency) {
                "Cash account currency (${cashAccount.currency}) must match fixed deposit currency (${request.principal.currency})"
            }

            val asset = createInvestmentAssetInTx(request = request.assetRequest, now = now)
            val rateProvider = currentRateProviderWithFallback(occurredAt)
            val tx = ledgerEngine
                .withRateProvider(rateProvider)
                .buildInvestmentBuy(
                    meta = TransactionMeta(
                        occurredAt = occurredAt,
                        payee = request.payee ?: asset.name,
                        note = request.note ?: "存入定期存款 ${asset.name}",
                        source = request.source,
                        tagIds = request.tagIds,
                        createdAt = now,
                        updatedAt = now,
                    ),
                    holdingAccountId = asset.accountId,
                    cashAccountId = request.cashAccountId,
                    investmentAssetId = asset.id,
                    quantity = Quantity.of(request.principal.toBigDecimal()),
                    unitPrice = UnitPrice.of("1"),
                    currency = asset.currency,
                    totalAmount = request.principal,
                    exchangeRateOverride = request.exchangeRateOverride,
                )

            transactionWriter.saveTransaction(tx)
            FixedDepositOpenResult(asset = asset, transaction = tx)
        }
    }

    override suspend fun recordFixedDepositSettlement(
        request: FixedDepositSettlementRequest,
    ): Transaction {
        val now = clock.nowEpochMs()
        ensureSeeded(now)

        return database.withTransaction {
            val asset = investmentAssetDao.getById(request.assetId)?.toDomain()
                ?: throw IllegalArgumentException("Unknown investment asset id: '${request.assetId}'")
            require(asset.assetClass == AssetClass.FIXED_DEPOSIT) {
                "recordFixedDepositSettlement only supports FIXED_DEPOSIT assets, got ${asset.assetClass}"
            }
            val cashAccount = accountDao.getById(request.cashAccountId)?.toDomain()
                ?: throw IllegalArgumentException("Unknown cash account id: '${request.cashAccountId}'")
            require(!cashAccount.isArchived) {
                "Cash account '${request.cashAccountId}' is archived"
            }
            require(cashAccount.currency == asset.currency) {
                "Cash account currency (${cashAccount.currency}) must match deposit currency (${asset.currency})"
            }

            val existingTxs = transactionDao.getInvestmentTransactionsForAsset(asset.id)
                .map { it.toDomain() }
            val accountsById = loadReferencedAccounts(existingTxs)
            val state = PortfolioPositionEngine.replayLedgerState(
                asset = asset,
                transactions = existingTxs,
                accounts = accountsById,
                upToEpochMsInclusive = request.occurredAt,
            )
            require(state.remainingCostBasis.isPositive && state.holdingQuantity.isPositive) {
                "Fixed deposit '${asset.id}' has no active principal remaining to settle"
            }

            val currentPrincipal = state.remainingCostBasis
            val withdrawnPrincipal = request.withdrawnPrincipal ?: currentPrincipal
            require(withdrawnPrincipal.currency == asset.currency) {
                "withdrawnPrincipal currency (${withdrawnPrincipal.currency}) must match deposit currency (${asset.currency})"
            }
            require(withdrawnPrincipal.isPositive) {
                "withdrawnPrincipal must be strictly positive, got $withdrawnPrincipal"
            }
            if (withdrawnPrincipal > currentPrincipal) {
                throw OversellPositionException(
                    assetId = asset.id,
                    attemptedSellQuantity = Quantity.of(withdrawnPrincipal.toBigDecimal()),
                    availableQuantity = Quantity.of(currentPrincipal.toBigDecimal()),
                )
            }

            val isFullSettlement = withdrawnPrincipal == currentPrincipal
            val settledQuantity = if (isFullSettlement) {
                state.holdingQuantity
            } else {
                val proportionalQtyBd = state.holdingQuantity.toBigDecimal()
                    .multiply(withdrawnPrincipal.toBigDecimal())
                    .divide(currentPrincipal.toBigDecimal(), Quantity.MAX_SCALE, RoundingMode.HALF_UP)
                Quantity.of(proportionalQtyBd)
            }

            val resolvedInterest = request.actualInterestAmount ?: run {
                val alreadyPaid = if (asset.depositInterestMode == DepositInterestMode.MONTHLY_PAYOUT) {
                    state.totalDividends
                } else {
                    Money.zero(asset.currency)
                }
                FixedDepositCalculator.calculate(
                    asset = asset,
                    principal = withdrawnPrincipal,
                    asOfEpochMs = request.occurredAt,
                    alreadyPaidInterest = alreadyPaid,
                    zoneId = zoneId,
                ).unpaidAccruedInterest
            }

            val rateProvider = currentRateProviderWithFallback(request.occurredAt)
            val tx = FixedDepositCalculator.buildSettlementTransaction(
                ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                meta = TransactionMeta(
                    occurredAt = request.occurredAt,
                    payee = request.payee ?: asset.name,
                    note = request.note ?: if (isFullSettlement) "定期到期结息" else "定期提前支取",
                    source = request.source,
                    tagIds = request.tagIds,
                    createdAt = now,
                    updatedAt = now,
                ),
                assetId = asset.id,
                holdingAccountId = asset.accountId,
                cashAccountId = request.cashAccountId,
                interestIncomeAccountId = request.interestIncomeAccountId,
                withdrawnPrincipal = withdrawnPrincipal,
                interestAmount = resolvedInterest,
                settledQuantity = settledQuantity,
                penaltyOrTaxFee = request.penaltyOrTaxFee,
                penaltyFeeExpenseAccountId = request.penaltyFeeExpenseAccountId.takeIf {
                    request.penaltyOrTaxFee != null
                },
                exchangeRateOverride = request.exchangeRateOverride,
            )

            // Guard timeline consistency in case settlement is backdated
            val allAccountsForReplay = accountsById + loadReferencedAccounts(listOf(tx))
            PortfolioPositionEngine.replayLedgerState(
                asset = asset,
                transactions = existingTxs + tx,
                accounts = allAccountsForReplay,
            )

            transactionWriter.saveTransaction(tx)

            if (isFullSettlement && request.archiveHoldingAccountOnFullSettlement) {
                val siblings = investmentAssetDao.getByAccountId(asset.accountId)
                if (siblings.size <= 1) {
                    accountDao.setArchived(asset.accountId, archived = true, updatedAt = now)
                }
            }
            tx
        }
    }

    override suspend fun recordFixedDepositInterestPayout(
        request: FixedDepositInterestPayoutRequest,
    ): Transaction {
        val now = clock.nowEpochMs()
        ensureSeeded(now)

        return database.withTransaction {
            val asset = investmentAssetDao.getById(request.assetId)?.toDomain()
                ?: throw IllegalArgumentException("Unknown investment asset id: '${request.assetId}'")
            require(asset.assetClass == AssetClass.FIXED_DEPOSIT) {
                "recordFixedDepositInterestPayout only supports FIXED_DEPOSIT assets, got ${asset.assetClass}"
            }
            val cashAccount = accountDao.getById(request.cashAccountId)?.toDomain()
                ?: throw IllegalArgumentException("Unknown cash account id: '${request.cashAccountId}'")
            require(!cashAccount.isArchived) {
                "Cash account '${request.cashAccountId}' is archived"
            }
            require(cashAccount.currency == asset.currency) {
                "Cash account currency (${cashAccount.currency}) must match deposit currency (${asset.currency})"
            }

            val rateProvider = currentRateProviderWithFallback(request.occurredAt)
            val tx = FixedDepositCalculator.buildMonthlyInterestPayoutTransaction(
                ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                meta = TransactionMeta(
                    occurredAt = request.occurredAt,
                    payee = request.payee ?: "${asset.name} 付息",
                    note = request.note ?: "定期按期付息",
                    source = request.source,
                    tagIds = request.tagIds,
                    createdAt = now,
                    updatedAt = now,
                ),
                assetId = asset.id,
                cashAccountId = request.cashAccountId,
                interestIncomeAccountId = request.interestIncomeAccountId,
                interestAmount = request.interestAmount,
                taxOrFee = request.taxOrFee,
                taxExpenseAccountId = request.taxExpenseAccountId.takeIf { request.taxOrFee != null },
                exchangeRateOverride = request.exchangeRateOverride,
            )
            transactionWriter.saveTransaction(tx)
            tx
        }
    }

    override suspend fun updateBankWealthByUnitPrice(
        assetId: String,
        manualUnitPrice: UnitPrice,
        prevCloseOverride: UnitPrice?,
        updatedAtEpochMs: Long?,
    ): BankWealthManualValuationResult {
        val now = updatedAtEpochMs ?: clock.nowEpochMs()
        val (asset, state, existingQuote) = loadBankWealthContext(assetId)
        val result = BankWealthValuationHelper.deriveQuoteFromUnitPrice(
            asset = asset,
            holdingQuantity = state.holdingQuantity,
            costBasis = state.remainingCostBasis,
            manualUnitPrice = manualUnitPrice,
            updatedAtEpochMs = now,
            previousQuote = existingQuote,
            prevCloseOverride = prevCloseOverride,
        )
        val persistedQuote = marketDataRepository.manualOverrideQuote(
            assetId = asset.id,
            priceDecimal = result.impliedUnitPrice.canonical,
            currency = asset.currency,
            prevCloseDecimal = result.quote.prevClose?.canonical,
            nowEpochMs = now,
        )
        return result.copy(quote = persistedQuote)
    }

    override suspend fun updateBankWealthByProfitAmount(
        assetId: String,
        latestProfitAmount: Money,
        prevCloseOverride: UnitPrice?,
        updatedAtEpochMs: Long?,
    ): BankWealthManualValuationResult {
        val now = updatedAtEpochMs ?: clock.nowEpochMs()
        val (asset, state, existingQuote) = loadBankWealthContext(assetId)
        val result = BankWealthValuationHelper.deriveQuoteFromProfitAmount(
            asset = asset,
            holdingQuantity = state.holdingQuantity,
            costBasis = state.remainingCostBasis,
            latestProfitAmount = latestProfitAmount,
            updatedAtEpochMs = now,
            previousQuote = existingQuote,
            prevCloseOverride = prevCloseOverride,
        )
        val persistedQuote = marketDataRepository.manualOverrideQuote(
            assetId = asset.id,
            priceDecimal = result.impliedUnitPrice.canonical,
            currency = asset.currency,
            prevCloseDecimal = result.quote.prevClose?.canonical,
            nowEpochMs = now,
        )
        return result.copy(quote = persistedQuote)
    }

    override suspend fun updateBankWealthByTotalValue(
        assetId: String,
        currentTotalValue: Money,
        prevCloseOverride: UnitPrice?,
        updatedAtEpochMs: Long?,
    ): BankWealthManualValuationResult {
        val now = updatedAtEpochMs ?: clock.nowEpochMs()
        val (asset, state, existingQuote) = loadBankWealthContext(assetId)
        val result = BankWealthValuationHelper.deriveQuoteFromTotalValue(
            asset = asset,
            holdingQuantity = state.holdingQuantity,
            costBasis = state.remainingCostBasis,
            currentTotalValue = currentTotalValue,
            updatedAtEpochMs = now,
            previousQuote = existingQuote,
            prevCloseOverride = prevCloseOverride,
        )
        val persistedQuote = marketDataRepository.manualOverrideQuote(
            assetId = asset.id,
            priceDecimal = result.impliedUnitPrice.canonical,
            currency = asset.currency,
            prevCloseDecimal = result.quote.prevClose?.canonical,
            nowEpochMs = now,
        )
        return result.copy(quote = persistedQuote)
    }

    override fun observeAssetTransactions(assetId: String): Flow<List<Transaction>> =
        transactionDao.observeInvestmentTransactionsForAsset(assetId)
            .map { detailsList -> detailsList.map { it.toDomain() } }

    override fun observePortfolioPositions(
        baseCurrency: Currency,
        includeZeroPositions: Boolean,
        includeArchived: Boolean,
        asOfEpochMs: Long?,
    ): Flow<List<PortfolioPositionValuation>> = combine(
        investmentAssetDao.observeAll(),
        accountDao.observeAll(includeArchived = true, includeSystem = true),
        transactionDao.observeInvestmentTransactions(TransactionStatus.POSTED),
        marketQuoteDao.observeAll(),
        marketDataRepository.observeExchangeRateTable(),
    ) { assetEntities, accountEntities, txDetails, quoteEntities, liveRates ->
        val accountsById = accountEntities.associate { it.id to it.toDomain() }
        val assets = assetEntities
            .filter { entity ->
                includeArchived || accountsById[entity.accountId]?.isArchived != true
            }
            .map { it.toDomain() }
        val transactions = txDetails.map { it.toDomain() }
        val quotesByAssetId = quoteEntities.associate { it.assetId to it.toDomain() }
        val effectiveAsOf = asOfEpochMs ?: clock.nowEpochMs()
        val rateProvider = wrapWithFallbackRates(liveRates, effectiveAsOf)

        val evaluated = PortfolioPositionEngine.evaluatePositions(
            assets = assets,
            transactions = transactions,
            quotesByAssetId = quotesByAssetId,
            baseCurrency = baseCurrency,
            exchangeRateProvider = rateProvider,
            accounts = accountsById,
            asOfEpochMs = effectiveAsOf,
            zoneId = zoneId,
        )
        if (includeZeroPositions) {
            evaluated
        } else {
            evaluated.filter { it.hasHolding }
        }
    }

    override fun observePosition(
        assetId: String,
        baseCurrency: Currency,
        asOfEpochMs: Long?,
    ): Flow<PortfolioPositionValuation?> = observePortfolioPositions(
        baseCurrency = baseCurrency,
        includeZeroPositions = true,
        includeArchived = true,
        asOfEpochMs = asOfEpochMs,
    ).map { list -> list.firstOrNull { it.asset.id == assetId } }

    override fun observePortfolioSummary(
        baseCurrency: Currency,
        asOfEpochMs: Long?,
    ): Flow<PortfolioSummary> {
        val accountsAndBalancesFlow = combine(
            accountDao.observeAll(includeArchived = true, includeSystem = true),
            transactionDao.observeAccountOriginalBalances(TransactionStatus.POSTED),
        ) { accountEntities, balanceRows ->
            accountEntities.map { it.toDomain() } to balanceRows
        }

        val investmentsAndMarketFlow = combine(
            investmentAssetDao.observeAll(),
            transactionDao.observeInvestmentTransactions(TransactionStatus.POSTED),
            marketQuoteDao.observeAll(),
            marketDataRepository.observeExchangeRateTable(),
        ) { assetEntities, txDetails, quoteEntities, liveRates ->
            InvestmentMarketSnapshot(
                assets = assetEntities.map { it.toDomain() },
                transactions = txDetails.map { it.toDomain() },
                quotesByAssetId = quoteEntities.associate { it.assetId to it.toDomain() },
                liveRates = liveRates,
            )
        }

        return combine(accountsAndBalancesFlow, investmentsAndMarketFlow) { (accounts, balanceRows), market ->
            val accountsById = accounts.associateBy { it.id }
            val effectiveAsOf = asOfEpochMs ?: clock.nowEpochMs()
            val rateProvider = wrapWithFallbackRates(market.liveRates, effectiveAsOf)

            val balancesById = buildMap<String, Money> {
                for (account in accounts) {
                    val row = balanceRows.firstOrNull {
                        it.accountId == account.id && it.currency == account.currency
                    }
                    put(
                        account.id,
                        Money(row?.balanceMinor ?: 0L, account.currency),
                    )
                }
            }

            val positions = PortfolioPositionEngine.evaluatePositions(
                assets = market.assets,
                transactions = market.transactions,
                quotesByAssetId = market.quotesByAssetId,
                baseCurrency = baseCurrency,
                exchangeRateProvider = rateProvider,
                accounts = accountsById,
                asOfEpochMs = effectiveAsOf,
                zoneId = zoneId,
            )

            PortfolioSummaryAggregator.aggregate(
                accounts = accounts,
                accountBalancesById = balancesById,
                positions = positions,
                baseCurrency = baseCurrency,
                exchangeRateProvider = rateProvider,
            )
        }
    }

    private suspend fun createInvestmentAssetInTx(
        request: CreateInvestmentAssetRequest,
        now: Long,
    ): InvestmentAsset {
        val holdingAccountId = if (request.existingHoldingAccountId != null) {
            val existingAccount = accountDao.getById(request.existingHoldingAccountId)?.toDomain()
                ?: throw IllegalArgumentException(
                    "Unknown existingHoldingAccountId: '${request.existingHoldingAccountId}'",
                )
            require(
                existingAccount.type == AccountType.ASSET &&
                    existingAccount.subType == AccountSubType.INVEST_HOLDING,
            ) {
                "Linked holding account '${existingAccount.id}' must be ASSET / INVEST_HOLDING"
            }
            require(!existingAccount.isArchived) {
                "Linked holding account '${existingAccount.id}' is archived"
            }
            require(existingAccount.currency == request.currency) {
                "Holding account currency (${existingAccount.currency}) must match asset currency (${request.currency})"
            }
            existingAccount.id
        } else {
            val generatedAccountId = "holding:${request.id}"
            val holdingAccount = Account(
                id = generatedAccountId,
                name = request.holdingAccountName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "${request.name.trim()} 持仓",
                type = AccountType.ASSET,
                subType = AccountSubType.INVEST_HOLDING,
                currency = request.currency,
                icon = request.icon ?: defaultIconForAssetClass(request.assetClass),
                color = request.color ?: defaultColorForAssetClass(request.assetClass),
                includeInNetWorth = request.includeInNetWorth,
                isArchived = false,
                isSystem = false,
                sortOrder = request.sortOrder,
                createdAt = now,
                updatedAt = now,
            )
            accountDao.upsert(holdingAccount.toEntity())
            holdingAccount.id
        }

        val asset = InvestmentAsset(
            id = request.id,
            accountId = holdingAccountId,
            assetClass = request.assetClass,
            symbol = request.symbol.trim(),
            name = request.name.trim(),
            currency = request.currency,
            quoteProviderHint = request.quoteProviderHint?.trim()?.takeIf { it.isNotEmpty() },
            depositRateAnnualDecimal = request.depositRateAnnualDecimal,
            depositStartAt = request.depositStartAt,
            depositMaturityAt = request.depositMaturityAt,
            depositInterestMode = request.depositInterestMode,
            createdAt = now,
            updatedAt = now,
        )
        investmentAssetDao.upsert(asset.toEntity())
        return asset
    }

    private suspend fun loadBankWealthContext(
        assetId: String,
    ): Triple<InvestmentAsset, io.github.kvtttba.droplet.core.domain.investment.PositionLedgerState, io.github.kvtttba.droplet.core.model.MarketQuote?> {
        val asset = investmentAssetDao.getById(assetId)?.toDomain()
            ?: throw IllegalArgumentException("Unknown investment asset id: '$assetId'")
        require(asset.assetClass == AssetClass.BANK_WEALTH) {
            "Manual bank wealth valuation only supports BANK_WEALTH assets, got ${asset.assetClass}"
        }
        val existingTxs = transactionDao.getInvestmentTransactionsForAsset(assetId)
            .map { it.toDomain() }
        val accountsById = loadReferencedAccounts(existingTxs)
        val state = PortfolioPositionEngine.replayLedgerState(
            asset = asset,
            transactions = existingTxs,
            accounts = accountsById,
        )
        val existingQuote = marketQuoteDao.getByAssetId(assetId)?.toDomain()
        return Triple(asset, state, existingQuote)
    }

    private suspend fun loadReferencedAccounts(
        transactions: List<Transaction>,
    ): Map<String, Account> {
        val ids = transactions.flatMap { tx -> tx.postings.map { it.accountId } }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return accountDao.getByIds(ids).associate { it.id to it.toDomain() }
    }

    private suspend fun currentRateProviderWithFallback(capturedAt: Long): ExchangeRateProvider {
        val liveRates = exchangeRateDao.getAll().toExchangeRateTable()
        return wrapWithFallbackRates(liveRates, capturedAt)
    }

    private fun wrapWithFallbackRates(
        liveRates: ExchangeRateTable,
        capturedAt: Long,
    ): ExchangeRateProvider = FallbackExchangeRateProvider(
        primary = liveRates,
        fallback = object : ExchangeRateProvider {
            override fun findRate(from: Currency, to: Currency): ExchangeRateSnapshot =
                LedgerRepository.fallbackReferenceRate(from, to, capturedAt)
        },
    )

    private fun validateAssetMetadata(
        assetClass: AssetClass,
        depositRateAnnualDecimal: String?,
        depositStartAt: Long?,
        depositMaturityAt: Long?,
        depositInterestMode: DepositInterestMode?,
    ) {
        if (assetClass == AssetClass.FIXED_DEPOSIT) {
            require(!depositRateAnnualDecimal.isNullOrBlank()) {
                "FIXED_DEPOSIT asset requires depositRateAnnualDecimal"
            }
            require(depositStartAt != null) {
                "FIXED_DEPOSIT asset requires depositStartAt"
            }
            require(depositMaturityAt != null) {
                "FIXED_DEPOSIT asset requires depositMaturityAt"
            }
            require(depositInterestMode != null) {
                "FIXED_DEPOSIT asset requires depositInterestMode"
            }
            val parsedRate = BigDecimal(depositRateAnnualDecimal)
            require(parsedRate.compareTo(BigDecimal.ZERO) >= 0) {
                "depositRateAnnualDecimal must be non-negative"
            }
        }
    }

    private fun defaultIconForAssetClass(assetClass: AssetClass): String = when (assetClass) {
        AssetClass.FIXED_DEPOSIT -> "savings"
        AssetClass.BANK_WEALTH -> "account_balance"
        AssetClass.GOLD -> "payments"
        else -> "trending_up"
    }

    private fun defaultColorForAssetClass(assetClass: AssetClass): String = when (assetClass) {
        AssetClass.CN_STOCK, AssetClass.ETF -> "#D84315"
        AssetClass.CN_FUND -> "#00897B"
        AssetClass.HK_STOCK -> "#1565C0"
        AssetClass.US_STOCK -> "#3949AB"
        AssetClass.GOLD -> "#F9A825"
        AssetClass.CRYPTO -> "#6A1B9A"
        AssetClass.FIXED_DEPOSIT -> "#2E7D32"
        AssetClass.BANK_WEALTH -> "#00695C"
    }

    private data class InvestmentMarketSnapshot(
        val assets: List<InvestmentAsset>,
        val transactions: List<Transaction>,
        val quotesByAssetId: Map<String, io.github.kvtttba.droplet.core.model.MarketQuote>,
        val liveRates: ExchangeRateTable,
    )
}
