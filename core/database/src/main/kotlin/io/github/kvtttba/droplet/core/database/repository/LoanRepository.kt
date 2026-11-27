package io.github.kvtttba.droplet.core.database.repository

import androidx.room.withTransaction
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.investment.FallbackExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.domain.loan.EarlyPayoffQuote
import io.github.kvtttba.droplet.core.domain.loan.LoanPlanSummary
import io.github.kvtttba.droplet.core.domain.loan.LoanRepaymentHelper
import io.github.kvtttba.droplet.core.domain.loan.LoanScheduleCalculator
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.InstallmentStatus
import io.github.kvtttba.droplet.core.model.LoanInstallment
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.LoanPlan
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionSource
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CreateLoanPlanRequest(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val method: LoanMethod,
    val currency: Currency = Currency.CNY,
    val totalPrincipalMinor: Long,
    val totalPeriods: Int,
    val annualRateDecimal: String? = null,
    val fixedFeePerPeriodMinor: Long? = null,
    val fixedFeeRateDecimal: String? = null,
    val firstDueAt: Long,
    val monthlyRepayDay: Int,
    val existingLoanAccountId: String? = null,
    val recordOpeningBalance: Boolean = existingLoanAccountId == null,
    val includeInNetWorth: Boolean = true,
    val paymentAccountId: String,
    val interestExpenseAccountId: String = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
    val autoPost: Boolean = false,
    val remindDaysBefore: Int? = 3,
    val note: String? = null,
    val nowEpochMs: Long? = null,
)

data class LoanPlanCreatedResult(
    val plan: LoanPlan,
    val loanAccount: Account,
    val installments: List<LoanInstallment>,
    val openingTransaction: Transaction?,
)

data class LoanDetailSnapshot(
    val summary: LoanPlanSummary,
    val loanAccount: Account,
    val paymentAccount: Account?,
    val interestExpenseAccount: Account?,
    val loanAccountBalance: Money,
)

data class UpdateLoanPlanParametersRequest(
    val planId: String,
    val name: String? = null,
    val paymentAccountId: String? = null,
    val interestExpenseAccountId: String? = null,
    val autoPost: Boolean? = null,
    val remindDaysBefore: Int? = null,
    val clearRemindDaysBefore: Boolean = false,
    val note: String? = null,
    val clearNote: Boolean = false,
)

data class RepayInstallmentRequest(
    val loanPlanId: String,
    val installmentId: String,
    val paymentAccountIdOverride: String? = null,
    val occurredAt: Long? = null,
    val payee: String? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val source: TransactionSource = TransactionSource.MANUAL,
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class EarlyPayoffLoanRequest(
    val loanPlanId: String,
    val paymentAccountIdOverride: String? = null,
    val extraFeeOrInterest: Money? = null,
    val occurredAt: Long? = null,
    val payee: String? = null,
    val note: String? = null,
    val tagIds: List<String> = emptyList(),
    val archiveAfterPayoff: Boolean = false,
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
)

data class EarlyPayoffLoanResult(
    val transaction: Transaction,
    val settledInstallmentIds: List<String>,
    val quote: EarlyPayoffQuote,
)

data class AutoPostedInstallmentItem(
    val plan: LoanPlan,
    val installment: LoanInstallment,
    val transactionId: String,
)

data class AutoPostInstallmentFailure(
    val loanPlanId: String,
    val installmentId: String,
    val periodNumber: Int,
    val reason: String,
)

data class AutoPostDueInstallmentsReport(
    val scannedDueCount: Int,
    val postedItems: List<AutoPostedInstallmentItem>,
    val skippedCount: Int,
    val failures: List<AutoPostInstallmentFailure>,
) {
    val postedCount: Int
        get() = postedItems.size
}

/**
 * Reactive repository coordinating installment loan plans, linked `LOAN` liability accounts,
 * scheduled installments, manual/automatic idempotent double-entry repayments, early payoff,
 * and archiving/safe deletion.
 */
interface LoanRepository {

    fun observeLoanSummaries(
        includeArchived: Boolean = false,
        asOfEpochMs: Long? = null,
    ): Flow<List<LoanPlanSummary>>

    fun observeLoanDetail(
        planId: String,
        asOfEpochMs: Long? = null,
    ): Flow<LoanDetailSnapshot?>

    suspend fun getLoanSummariesSnapshot(
        includeArchived: Boolean = false,
        asOfEpochMs: Long? = null,
    ): List<LoanPlanSummary>

    suspend fun createLoanPlan(request: CreateLoanPlanRequest): LoanPlanCreatedResult

    suspend fun updateLoanPlanParameters(request: UpdateLoanPlanParametersRequest): LoanPlan

    suspend fun repayInstallment(request: RepayInstallmentRequest): Transaction

    suspend fun autoPostDueInstallments(asOfEpochMs: Long? = null): AutoPostDueInstallmentsReport

    suspend fun earlyPayoffLoan(request: EarlyPayoffLoanRequest): EarlyPayoffLoanResult

    suspend fun setLoanPlanArchived(
        planId: String,
        archived: Boolean,
        nowEpochMs: Long? = null,
    )

    suspend fun deleteLoanPlanIfUnused(planId: String): Result<Unit>
}

class LocalLoanRepository(
    private val database: DropletDatabase,
    private val transactionWriter: LedgerTransactionWriter,
    private val seeder: DefaultLedgerSeeder,
    private val ledgerEngine: LedgerEngine,
    private val clock: NetworkClock = NetworkClock.SYSTEM,
    private val zoneId: ZoneId = LoanScheduleCalculator.DEFAULT_ZONE_ID,
) : LoanRepository {

    private val accountDao = database.accountDao()
    private val loanDao = database.loanDao()
    private val transactionDao = database.transactionDao()
    private val exchangeRateDao = database.exchangeRateDao()
    private val seedMutex = Mutex()

    private suspend fun ensureSeeded(nowEpochMs: Long) {
        seedMutex.withLock {
            seeder.seedIfNeeded(nowEpochMs)
        }
    }

    override fun observeLoanSummaries(
        includeArchived: Boolean,
        asOfEpochMs: Long?,
    ): Flow<List<LoanPlanSummary>> = combine(
        loanDao.observePlans(includeArchived = includeArchived),
        loanDao.observeAllInstallments(),
    ) { planEntities, installmentEntities ->
        val effectiveAsOf = asOfEpochMs ?: clock.nowEpochMs()
        val installmentsByPlan = installmentEntities
            .map { it.toDomain() }
            .groupBy { it.loanPlanId }
        planEntities.map { entity ->
            val plan = entity.toDomain()
            LoanScheduleCalculator.summarize(
                plan = plan,
                installments = installmentsByPlan[plan.id].orEmpty(),
                asOfEpochMs = effectiveAsOf,
                zoneId = zoneId,
            )
        }
    }

    override fun observeLoanDetail(
        planId: String,
        asOfEpochMs: Long?,
    ): Flow<LoanDetailSnapshot?> = combine(
        loanDao.observePlanById(planId),
        loanDao.observeInstallmentsForPlan(planId),
        accountDao.observeAll(includeArchived = true, includeSystem = true),
        transactionDao.observeAccountOriginalBalances(TransactionStatus.POSTED),
    ) { planEntity, installmentEntities, accountEntities, balanceRows ->
        val plan = planEntity?.toDomain() ?: return@combine null
        val accountsById = accountEntities.associate { it.id to it.toDomain() }
        val loanAccount = accountsById[plan.loanAccountId] ?: return@combine null
        val paymentAccount = accountsById[plan.paymentAccountId]
        val interestAccount = accountsById[plan.interestExpenseAccountId]
        val balanceMinor = balanceRows
            .firstOrNull { it.accountId == loanAccount.id && it.currency == loanAccount.currency }
            ?.balanceMinor ?: 0L
        val effectiveAsOf = asOfEpochMs ?: clock.nowEpochMs()
        val summary = LoanScheduleCalculator.summarize(
            plan = plan,
            installments = installmentEntities.map { it.toDomain() },
            asOfEpochMs = effectiveAsOf,
            zoneId = zoneId,
        )
        LoanDetailSnapshot(
            summary = summary,
            loanAccount = loanAccount,
            paymentAccount = paymentAccount,
            interestExpenseAccount = interestAccount,
            loanAccountBalance = Money(balanceMinor, loanAccount.currency),
        )
    }

    override suspend fun getLoanSummariesSnapshot(
        includeArchived: Boolean,
        asOfEpochMs: Long?,
    ): List<LoanPlanSummary> {
        val effectiveAsOf = asOfEpochMs ?: clock.nowEpochMs()
        val plans = loanDao.getPlans(includeArchived = includeArchived).map { it.toDomain() }
        if (plans.isEmpty()) return emptyList()
        val installmentsByPlan = loanDao.getAllInstallments()
            .map { it.toDomain() }
            .groupBy { it.loanPlanId }
        return plans.map { plan ->
            LoanScheduleCalculator.summarize(
                plan = plan,
                installments = installmentsByPlan[plan.id].orEmpty(),
                asOfEpochMs = effectiveAsOf,
                zoneId = zoneId,
            )
        }
    }

    override suspend fun createLoanPlan(request: CreateLoanPlanRequest): LoanPlanCreatedResult {
        val trimmedName = request.name.trim()
        require(trimmedName.isNotEmpty()) { "请输入分期贷款名称" }
        require(request.totalPrincipalMinor > 0L) { "贷款本金必须大于 0" }
        require(request.totalPeriods > 0) { "分期期数必须大于 0" }
        require(request.monthlyRepayDay in 1..31) { "每月扣款日需在 1–31 之间" }
        if (request.remindDaysBefore != null) {
            require(request.remindDaysBefore in 0..31) { "提前提醒天数需在 0–31 之间" }
        }

        val now = request.nowEpochMs ?: clock.nowEpochMs()
        ensureSeeded(now)

        return database.withTransaction {
            val paymentAccount = accountDao.getById(request.paymentAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到付款账户：${request.paymentAccountId}")
            validatePaymentAccount(paymentAccount, request.currency)

            val interestAccount = accountDao.getById(request.interestExpenseAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到利息/手续费支出分类：${request.interestExpenseAccountId}")
            validateInterestExpenseAccount(interestAccount)

            val loanAccount: Account = if (request.existingLoanAccountId != null) {
                val existing = accountDao.getById(request.existingLoanAccountId)?.toDomain()
                    ?: throw IllegalArgumentException("未找到贷款负债账户：${request.existingLoanAccountId}")
                require(existing.type == AccountType.LIABILITY && existing.subType == AccountSubType.LOAN) {
                    "所选贷款账户「${existing.name}」必须为分期贷款负债账户"
                }
                require(!existing.isArchived) { "贷款账户「${existing.name}」已归档" }
                require(existing.currency == request.currency) {
                    "贷款账户币种 (${existing.currency.code}) 必须与贷款币种 (${request.currency.code}) 一致"
                }
                existing
            } else {
                val created = Account(
                    id = "loan:${request.id}",
                    name = trimmedName,
                    type = AccountType.LIABILITY,
                    subType = AccountSubType.LOAN,
                    currency = request.currency,
                    icon = "receipt_long",
                    color = "#AD1457",
                    includeInNetWorth = request.includeInNetWorth,
                    isArchived = false,
                    isSystem = false,
                    sortOrder = 200,
                    createdAt = now,
                    updatedAt = now,
                )
                accountDao.upsert(created.toEntity())
                created
            }

            require(paymentAccount.id != loanAccount.id) {
                "付款账户不能与贷款负债账户相同"
            }

            val plan = LoanPlan(
                id = request.id,
                name = trimmedName,
                method = request.method,
                currency = request.currency,
                totalPrincipalMinor = request.totalPrincipalMinor,
                totalPeriods = request.totalPeriods,
                annualRateDecimal = request.annualRateDecimal,
                fixedFeePerPeriodMinor = request.fixedFeePerPeriodMinor,
                fixedFeeRateDecimal = request.fixedFeeRateDecimal,
                firstDueAt = request.firstDueAt,
                monthlyRepayDay = request.monthlyRepayDay,
                loanAccountId = loanAccount.id,
                paymentAccountId = paymentAccount.id,
                interestExpenseAccountId = interestAccount.id,
                autoPost = request.autoPost,
                remindDaysBefore = request.remindDaysBefore,
                isArchived = false,
                note = request.note?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = now,
                updatedAt = now,
            )

            val installments = LoanScheduleCalculator.generateSchedule(plan = plan, zoneId = zoneId)
            loanDao.upsertPlanWithInstallments(
                plan = plan.toEntity(),
                installments = installments.map { it.toEntity() },
            )

            val openingTx = if (request.recordOpeningBalance) {
                val rateProvider = currentRateProviderWithFallback(now)
                val tx = ledgerEngine
                    .withRateProvider(rateProvider)
                    .buildOpeningBalance(
                        meta = TransactionMeta(
                            occurredAt = now,
                            payee = "期初余额",
                            note = "分期贷款建账 · ${plan.name}",
                            createdAt = now,
                            updatedAt = now,
                        ),
                        accountId = loanAccount.id,
                        accountType = AccountType.LIABILITY,
                        balance = Money(plan.totalPrincipalMinor, plan.currency),
                    )
                transactionWriter.saveTransaction(tx)
                tx
            } else {
                null
            }

            LoanPlanCreatedResult(
                plan = plan,
                loanAccount = loanAccount,
                installments = installments,
                openingTransaction = openingTx,
            )
        }
    }

    override suspend fun updateLoanPlanParameters(
        request: UpdateLoanPlanParametersRequest,
    ): LoanPlan {
        val now = clock.nowEpochMs()
        return database.withTransaction {
            val existing = loanDao.getPlanById(request.planId)?.toDomain()
                ?: throw IllegalArgumentException("未找到分期贷款：${request.planId}")

            val resolvedName = request.name?.trim()?.also {
                require(it.isNotEmpty()) { "贷款名称不能为空" }
            } ?: existing.name

            val resolvedPaymentId = request.paymentAccountId ?: existing.paymentAccountId
            val paymentAccount = accountDao.getById(resolvedPaymentId)?.toDomain()
                ?: throw IllegalArgumentException("未找到付款账户：$resolvedPaymentId")
            validatePaymentAccount(paymentAccount, existing.currency)
            require(paymentAccount.id != existing.loanAccountId) { "付款账户不能与贷款负债账户相同" }

            val resolvedInterestId = request.interestExpenseAccountId ?: existing.interestExpenseAccountId
            val interestAccount = accountDao.getById(resolvedInterestId)?.toDomain()
                ?: throw IllegalArgumentException("未找到利息分类：$resolvedInterestId")
            validateInterestExpenseAccount(interestAccount)

            val resolvedRemindDays = when {
                request.clearRemindDaysBefore -> null
                request.remindDaysBefore != null -> {
                    require(request.remindDaysBefore in 0..31) { "提前提醒天数需在 0–31 之间" }
                    request.remindDaysBefore
                }
                else -> existing.remindDaysBefore
            }

            val resolvedNote = when {
                request.clearNote -> null
                request.note != null -> request.note.trim().takeIf { it.isNotEmpty() }
                else -> existing.note
            }

            val updated = existing.copy(
                name = resolvedName,
                paymentAccountId = resolvedPaymentId,
                interestExpenseAccountId = resolvedInterestId,
                autoPost = request.autoPost ?: existing.autoPost,
                remindDaysBefore = resolvedRemindDays,
                note = resolvedNote,
                updatedAt = now,
            )
            loanDao.upsertPlan(updated.toEntity())

            // Keep exclusive loan liability account name synchronized when user renames the loan
            if (resolvedName != existing.name && loanDao.countPlansForLoanAccount(existing.loanAccountId) == 1) {
                accountDao.getById(existing.loanAccountId)?.let { acctEntity ->
                    accountDao.upsert(acctEntity.copy(name = resolvedName, updatedAt = now))
                }
            }
            updated
        }
    }

    override suspend fun repayInstallment(request: RepayInstallmentRequest): Transaction {
        val now = clock.nowEpochMs()
        ensureSeeded(now)

        return database.withTransaction {
            val plan = loanDao.getPlanById(request.loanPlanId)?.toDomain()
                ?: throw IllegalArgumentException("未找到分期贷款：${request.loanPlanId}")
            require(!plan.isArchived) { "该分期贷款已归档，请先恢复后再操作" }

            val installment = loanDao.getInstallmentById(request.installmentId)?.toDomain()
                ?: throw IllegalArgumentException("未找到目标期次：${request.installmentId}")
            require(installment.loanPlanId == plan.id) {
                "目标期次不属于贷款「${plan.name}」"
            }
            require(installment.status == InstallmentStatus.PENDING && installment.transactionId == null) {
                "第 ${installment.periodNumber} 期已入账，不可重复还款"
            }

            val loanAccount = accountDao.getById(plan.loanAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到贷款负债账户：${plan.loanAccountId}")
            val paymentAccountId = request.paymentAccountIdOverride ?: plan.paymentAccountId
            val paymentAccount = accountDao.getById(paymentAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到付款账户：$paymentAccountId")
            val interestAccount = accountDao.getById(plan.interestExpenseAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到利息分类：${plan.interestExpenseAccountId}")

            val occurredAt = request.occurredAt ?: now
            val rateProvider = currentRateProviderWithFallback(occurredAt)
            val tx = LoanRepaymentHelper.buildInstallmentRepaymentTransaction(
                ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                plan = plan,
                installment = installment,
                loanAccount = loanAccount,
                paymentAccount = paymentAccount,
                interestExpenseAccount = interestAccount,
                occurredAt = occurredAt,
                source = request.source,
                payee = request.payee,
                note = request.note,
                tagIds = request.tagIds,
                createdAt = now,
                updatedAt = now,
                exchangeRateOverride = request.exchangeRateOverride,
                rateProviderOverride = rateProvider,
            )

            transactionWriter.saveTransaction(tx)
            val affectedRows = loanDao.markInstallmentPaidIfPending(
                installmentId = installment.id,
                transactionId = tx.id,
                paidAt = occurredAt,
            )
            if (affectedRows != 1) {
                throw IllegalStateException("第 ${installment.periodNumber} 期状态已变更，请刷新后重试")
            }
            tx
        }
    }

    override suspend fun autoPostDueInstallments(asOfEpochMs: Long?): AutoPostDueInstallmentsReport {
        val effectiveNow = asOfEpochMs ?: clock.nowEpochMs()
        ensureSeeded(effectiveNow)

        val autoPlans = loanDao.getActiveAutoPostPlans().map { it.toDomain() }
        if (autoPlans.isEmpty()) {
            return AutoPostDueInstallmentsReport(
                scannedDueCount = 0,
                postedItems = emptyList(),
                skippedCount = 0,
                failures = emptyList(),
            )
        }

        val asOfDate = Instant.ofEpochMilli(effectiveNow).atZone(zoneId).toLocalDate()
        var scannedDueCount = 0
        var skippedCount = 0
        val postedItems = mutableListOf<AutoPostedInstallmentItem>()
        val failures = mutableListOf<AutoPostInstallmentFailure>()

        for (plan in autoPlans) {
            val dueInstallments = loanDao.getInstallmentsForPlan(plan.id)
                .map { it.toDomain() }
                .filter { inst ->
                    if (inst.status != InstallmentStatus.PENDING || inst.transactionId != null) {
                        false
                    } else {
                        val dueDate = Instant.ofEpochMilli(inst.dueAt).atZone(zoneId).toLocalDate()
                        !dueDate.isAfter(asOfDate)
                    }
                }

            for (candidate in dueInstallments) {
                scannedDueCount++
                val outcome = runCatching {
                    database.withTransaction {
                        val freshPlan = loanDao.getPlanById(plan.id)?.toDomain()
                        if (freshPlan == null || freshPlan.isArchived || !freshPlan.autoPost) {
                            return@withTransaction null
                        }
                        val freshInst = loanDao.getInstallmentById(candidate.id)?.toDomain()
                        if (freshInst == null ||
                            freshInst.status != InstallmentStatus.PENDING ||
                            freshInst.transactionId != null
                        ) {
                            return@withTransaction null
                        }

                        val loanAccount = accountDao.getById(freshPlan.loanAccountId)?.toDomain()
                            ?: throw IllegalStateException("未找到贷款负债账户：${freshPlan.loanAccountId}")
                        val paymentAccount = accountDao.getById(freshPlan.paymentAccountId)?.toDomain()
                            ?: throw IllegalStateException("未找到付款账户：${freshPlan.paymentAccountId}")
                        val interestAccount = accountDao.getById(freshPlan.interestExpenseAccountId)?.toDomain()
                            ?: throw IllegalStateException("未找到利息分类：${freshPlan.interestExpenseAccountId}")

                        val rateProvider = currentRateProviderWithFallback(freshInst.dueAt)
                        val tx = LoanRepaymentHelper.buildInstallmentRepaymentTransaction(
                            ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                            plan = freshPlan,
                            installment = freshInst,
                            loanAccount = loanAccount,
                            paymentAccount = paymentAccount,
                            interestExpenseAccount = interestAccount,
                            occurredAt = freshInst.dueAt,
                            source = TransactionSource.LOAN_AUTO,
                            createdAt = effectiveNow,
                            updatedAt = effectiveNow,
                            rateProviderOverride = rateProvider,
                        )

                        transactionWriter.saveTransaction(tx)
                        val updatedRows = loanDao.markInstallmentPaidIfPending(
                            installmentId = freshInst.id,
                            transactionId = tx.id,
                            paidAt = effectiveNow,
                        )
                        if (updatedRows != 1) {
                            throw IllegalStateException("期次状态已并发更新，回滚防重复入账")
                        }

                        val paidInst = freshInst.copy(
                            status = InstallmentStatus.PAID,
                            transactionId = tx.id,
                            paidAt = effectiveNow,
                        )
                        AutoPostedInstallmentItem(
                            plan = freshPlan,
                            installment = paidInst,
                            transactionId = tx.id,
                        )
                    }
                }

                outcome.fold(
                    onSuccess = { posted ->
                        if (posted != null) {
                            postedItems += posted
                        } else {
                            skippedCount++
                        }
                    },
                    onFailure = { error ->
                        failures += AutoPostInstallmentFailure(
                            loanPlanId = plan.id,
                            installmentId = candidate.id,
                            periodNumber = candidate.periodNumber,
                            reason = error.message ?: error.javaClass.simpleName,
                        )
                    },
                )
            }
        }

        return AutoPostDueInstallmentsReport(
            scannedDueCount = scannedDueCount,
            postedItems = postedItems,
            skippedCount = skippedCount,
            failures = failures,
        )
    }

    override suspend fun earlyPayoffLoan(request: EarlyPayoffLoanRequest): EarlyPayoffLoanResult {
        val now = clock.nowEpochMs()
        ensureSeeded(now)

        return database.withTransaction {
            val plan = loanDao.getPlanById(request.loanPlanId)?.toDomain()
                ?: throw IllegalArgumentException("未找到分期贷款：${request.loanPlanId}")
            require(!plan.isArchived) { "该分期贷款已归档，请先恢复后再操作" }

            val allInstallments = loanDao.getInstallmentsForPlan(plan.id).map { it.toDomain() }
            val pendingInstallments = allInstallments.filter { it.status == InstallmentStatus.PENDING }
            require(pendingInstallments.isNotEmpty()) {
                "贷款「${plan.name}」已全部结清，无需重复提前结清"
            }

            val extraFee = request.extraFeeOrInterest ?: Money.zero(plan.currency)
            val quote = LoanScheduleCalculator.calculateEarlyPayoff(
                plan = plan,
                installments = allInstallments,
                extraFeeOrInterest = extraFee,
            )

            val loanAccount = accountDao.getById(plan.loanAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到贷款负债账户：${plan.loanAccountId}")
            val paymentAccountId = request.paymentAccountIdOverride ?: plan.paymentAccountId
            val paymentAccount = accountDao.getById(paymentAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到付款账户：$paymentAccountId")
            val interestAccount = accountDao.getById(plan.interestExpenseAccountId)?.toDomain()
                ?: throw IllegalArgumentException("未找到利息分类：${plan.interestExpenseAccountId}")

            val occurredAt = request.occurredAt ?: now
            val rateProvider = currentRateProviderWithFallback(occurredAt)
            val tx = LoanRepaymentHelper.buildEarlyPayoffTransaction(
                ledgerEngine = ledgerEngine.withRateProvider(rateProvider),
                plan = plan,
                quote = quote,
                loanAccount = loanAccount,
                paymentAccount = paymentAccount,
                interestExpenseAccount = interestAccount,
                occurredAt = occurredAt,
                source = TransactionSource.MANUAL,
                payee = request.payee,
                note = request.note,
                tagIds = request.tagIds,
                createdAt = now,
                updatedAt = now,
                exchangeRateOverride = request.exchangeRateOverride,
                rateProviderOverride = rateProvider,
            )

            transactionWriter.saveTransaction(tx)

            // Mark all remaining pending installments PAID, waiving future scheduled interest
            // and attributing any early-payoff extra interest/fee to the first settled period.
            val settledEntities = pendingInstallments.mapIndexed { index, inst ->
                inst.copy(
                    interestOrFeeMinor = if (index == 0) extraFee.amountMinor else 0L,
                    status = InstallmentStatus.PAID,
                    transactionId = tx.id,
                    paidAt = occurredAt,
                ).toEntity()
            }
            loanDao.upsertInstallments(settledEntities)

            if (request.archiveAfterPayoff) {
                loanDao.setPlanArchived(planId = plan.id, archived = true, updatedAt = now)
                if (loanDao.countPlansForLoanAccount(plan.loanAccountId) <= 1) {
                    accountDao.setArchived(accountId = plan.loanAccountId, archived = true, updatedAt = now)
                }
            }

            EarlyPayoffLoanResult(
                transaction = tx,
                settledInstallmentIds = pendingInstallments.map { it.id },
                quote = quote,
            )
        }
    }

    override suspend fun setLoanPlanArchived(
        planId: String,
        archived: Boolean,
        nowEpochMs: Long?,
    ) {
        val now = nowEpochMs ?: clock.nowEpochMs()
        database.withTransaction {
            val plan = loanDao.getPlanById(planId)?.toDomain()
                ?: throw IllegalArgumentException("未找到分期贷款：$planId")
            loanDao.setPlanArchived(planId = planId, archived = archived, updatedAt = now)
            if (loanDao.countPlansForLoanAccount(plan.loanAccountId) <= 1) {
                accountDao.setArchived(accountId = plan.loanAccountId, archived = archived, updatedAt = now)
            }
        }
    }

    override suspend fun deleteLoanPlanIfUnused(planId: String): Result<Unit> = runCatching {
        database.withTransaction {
            val plan = loanDao.getPlanById(planId)?.toDomain()
                ?: error("未找到分期贷款")
            val paidOrLinkedCount = loanDao.countPaidOrLinkedInstallmentsForPlan(planId)
            require(paidOrLinkedCount == 0) {
                "该分期贷款已有 $paidOrLinkedCount 期已还或关联流水，不可删除，请改用「归档」"
            }

            val linkedPlanCount = loanDao.countPlansForLoanAccount(plan.loanAccountId)
            loanDao.deletePlanById(planId)

            if (linkedPlanCount <= 1) {
                val loanAccount = accountDao.getById(plan.loanAccountId)?.toDomain()
                if (loanAccount != null && !loanAccount.isSystem) {
                    val accountTxs = transactionDao.getTransactionsForAccount(
                        accountId = loanAccount.id,
                        status = TransactionStatus.POSTED,
                    ).map { it.toDomain() }
                    val openingEquityId = SystemAccounts.openingBalanceAccountId(loanAccount.currency)
                    val isOpeningOnly = accountTxs.size == 1 &&
                        accountTxs.single().displayType == TransactionDisplayType.SPLIT &&
                        accountTxs.single().payee == "期初余额" &&
                        accountTxs.single().postings.any { it.accountId == openingEquityId }

                    if (isOpeningOnly) {
                        transactionDao.deleteById(accountTxs.single().id)
                    }
                    val remainingPostings = transactionDao.countPostingsForAccount(loanAccount.id)
                    if (remainingPostings == 0) {
                        accountDao.deleteById(loanAccount.id)
                    }
                }
            }
        }
    }

    private fun validatePaymentAccount(account: Account, loanCurrency: Currency) {
        require(!account.isArchived) { "付款账户「${account.name}」已归档" }
        val isSupported = account.type == AccountType.ASSET ||
            (account.type == AccountType.LIABILITY && account.subType == AccountSubType.CREDIT_CARD)
        require(isSupported) { "付款账户「${account.name}」需为资金账户或信用账户" }
        require(account.currency == loanCurrency) {
            "付款账户「${account.name}」币种 (${account.currency.code}) 必须与贷款币种 (${loanCurrency.code}) 一致"
        }
    }

    private fun validateInterestExpenseAccount(account: Account) {
        require(!account.isArchived) { "利息支出分类「${account.name}」已归档" }
        require(account.type == AccountType.EXPENSE) {
            "「${account.name}」必须为支出分类"
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
