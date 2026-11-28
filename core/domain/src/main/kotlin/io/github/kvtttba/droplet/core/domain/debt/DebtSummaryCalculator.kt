package io.github.kvtttba.droplet.core.domain.debt

import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DebtProfile
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class DebtProfileStatus {
    SETTLED,
    ACTIVE,
    DUE_SOON,
    OVERDUE,
}

enum class DebtTransactionKind {
    LEND,
    BORROW,
    COLLECT,
    REPAY,
    BAD_DEBT_WRITE_OFF,
    OTHER,
}

/**
 * Classified single debt-related ledger transaction belonging to a counterparty [DebtProfile].
 */
data class DebtTransactionRecord(
    val transaction: Transaction,
    val kind: DebtTransactionKind,
    val principalAmount: Money,
    val interestAmount: Money,
    val writeOffAmount: Money,
    /**
     * Signed impact on counterparty net balance (`receivable - payable`):
     * positive increases what they owe me (or reduces what I owe them);
     * negative reduces what they owe me (or increases what I owe them).
     */
    val counterpartyNetDelta: Money,
    val currency: Currency,
    val fundingAccountId: String?,
)

/**
 * Pure domain summary for a counterparty [DebtProfile] across its linked
 * `Asset:Receivable` (别人欠我) and `Liability:Payable` (我欠别人) accounts.
 *
 * Algebraic invariant:
 * - `netBalance == receivableBalance - payableBalance`
 *   (positive = counterparty net owes me; negative = I net owe counterparty).
 */
data class DebtProfileSummary(
    val profile: DebtProfile,
    val currency: Currency,
    /** Amount counterparty owes me (signed; positive under normal lending). */
    val receivableBalance: Money,
    /** Amount I owe counterparty (signed magnitude; positive under normal borrowing). */
    val payableBalance: Money,
    /** Net counterparty position = `receivableBalance - payableBalance`. */
    val netBalance: Money,
    val totalLent: Money,
    val totalCollectedPrincipal: Money,
    val totalInterestIncome: Money,
    val totalBadDebtWriteOff: Money,
    val totalBorrowed: Money,
    val totalRepaidPrincipal: Money,
    val totalInterestExpense: Money,
    val transactionCount: Int,
    val dueDate: LocalDate?,
    val daysUntilDue: Long?,
    val overdueDays: Long,
    val isDueToday: Boolean,
    val isInReminderWindow: Boolean,
    val status: DebtProfileStatus,
    val asOfDate: LocalDate,
    val remindDaysBefore: Int,
    val zoneId: ZoneId,
) {
    val hasOutstandingBalance: Boolean
        get() = !receivableBalance.isZero || !payableBalance.isZero

    val isSettled: Boolean
        get() = status == DebtProfileStatus.SETTLED
}

/**
 * Aggregate headline totals across multiple [DebtProfileSummary] items for a given currency.
 */
data class DebtPortfolioCurrencyTotals(
    val currency: Currency,
    val totalReceivable: Money,
    val totalPayable: Money,
    val netBalance: Money,
    val activeCounterpartyCount: Int,
    val overdueCounterpartyCount: Int,
)

/**
 * Pure Kotlin/JVM zero-floating-point aggregator for counterparty lending/borrowing profiles,
 * interest income/expense tracking, bad-debt write-offs, and due-date countdown reminders.
 */
object DebtSummaryCalculator {

    val DEFAULT_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")
    const val DEFAULT_REMIND_DAYS_BEFORE: Int = 3

    fun summarize(
        profile: DebtProfile,
        receivableAccount: Account? = null,
        payableAccount: Account? = null,
        transactions: Iterable<Transaction> = emptyList(),
        accountsById: Map<String, Account> = emptyMap(),
        asOfEpochMs: Long,
        remindDaysBefore: Int = DEFAULT_REMIND_DAYS_BEFORE,
        zoneId: ZoneId = DEFAULT_ZONE_ID,
    ): DebtProfileSummary {
        val asOfDate = Instant.ofEpochMilli(asOfEpochMs).atZone(zoneId).toLocalDate()
        return summarize(
            profile = profile,
            receivableAccount = receivableAccount,
            payableAccount = payableAccount,
            transactions = transactions,
            accountsById = accountsById,
            asOfDate = asOfDate,
            remindDaysBefore = remindDaysBefore,
            zoneId = zoneId,
        )
    }

    fun summarize(
        profile: DebtProfile,
        receivableAccount: Account? = null,
        payableAccount: Account? = null,
        transactions: Iterable<Transaction> = emptyList(),
        accountsById: Map<String, Account> = emptyMap(),
        asOfDate: LocalDate,
        remindDaysBefore: Int = DEFAULT_REMIND_DAYS_BEFORE,
        zoneId: ZoneId = DEFAULT_ZONE_ID,
    ): DebtProfileSummary {
        require(remindDaysBefore in 0..31) {
            "remindDaysBefore must be in 0..31, got $remindDaysBefore"
        }

        val resolvedAccounts = buildMap {
            putAll(accountsById)
            receivableAccount?.let { put(it.id, it) }
            payableAccount?.let { put(it.id, it) }
        }
        val recvId = profile.receivableAccountId
        val payId = profile.payableAccountId

        val currency = resolveCurrency(
            profile = profile,
            receivableAccount = receivableAccount ?: recvId?.let { resolvedAccounts[it] },
            payableAccount = payableAccount ?: payId?.let { resolvedAccounts[it] },
        )

        var rawReceivableMinor = 0L
        var rawPayableMinor = 0L
        var totalLentMinor = 0L
        var totalCollectedPrincipalMinor = 0L
        var totalInterestIncomeMinor = 0L
        var totalBadDebtWriteOffMinor = 0L
        var totalBorrowedMinor = 0L
        var totalRepaidPrincipalMinor = 0L
        var totalInterestExpenseMinor = 0L
        var txCount = 0

        for (tx in transactions) {
            if (tx.status != TransactionStatus.POSTED) continue
            val record = classifyTransactionOrNull(
                transaction = tx,
                profile = profile,
                currency = currency,
                accountsById = resolvedAccounts,
            ) ?: continue

            txCount++

            var recvDelta = 0L
            var payDelta = 0L
            for (posting in tx.businessPostings) {
                if (posting.currency != currency) continue
                if (recvId != null && posting.accountId == recvId) {
                    recvDelta = Math.addExact(recvDelta, posting.amountMinor)
                }
                if (payId != null && posting.accountId == payId) {
                    payDelta = Math.addExact(payDelta, posting.amountMinor)
                }
            }
            rawReceivableMinor = Math.addExact(rawReceivableMinor, recvDelta)
            rawPayableMinor = Math.addExact(rawPayableMinor, payDelta)

            when (record.kind) {
                DebtTransactionKind.LEND -> {
                    totalLentMinor = Math.addExact(totalLentMinor, record.principalAmount.amountMinor)
                }
                DebtTransactionKind.BORROW -> {
                    totalBorrowedMinor = Math.addExact(totalBorrowedMinor, record.principalAmount.amountMinor)
                }
                DebtTransactionKind.COLLECT -> {
                    totalCollectedPrincipalMinor = Math.addExact(
                        totalCollectedPrincipalMinor,
                        record.principalAmount.amountMinor,
                    )
                    totalInterestIncomeMinor = Math.addExact(
                        totalInterestIncomeMinor,
                        record.interestAmount.amountMinor,
                    )
                    totalBadDebtWriteOffMinor = Math.addExact(
                        totalBadDebtWriteOffMinor,
                        record.writeOffAmount.amountMinor,
                    )
                }
                DebtTransactionKind.REPAY -> {
                    totalRepaidPrincipalMinor = Math.addExact(
                        totalRepaidPrincipalMinor,
                        record.principalAmount.amountMinor,
                    )
                    totalInterestExpenseMinor = Math.addExact(
                        totalInterestExpenseMinor,
                        record.interestAmount.amountMinor,
                    )
                }
                DebtTransactionKind.BAD_DEBT_WRITE_OFF -> {
                    totalBadDebtWriteOffMinor = Math.addExact(
                        totalBadDebtWriteOffMinor,
                        record.writeOffAmount.amountMinor,
                    )
                }
                DebtTransactionKind.OTHER -> {
                    if (recvDelta > 0L) {
                        totalLentMinor = Math.addExact(totalLentMinor, recvDelta)
                    } else if (recvDelta < 0L) {
                        totalCollectedPrincipalMinor = Math.addExact(
                            totalCollectedPrincipalMinor,
                            Math.negateExact(recvDelta),
                        )
                    }
                    if (payDelta < 0L) {
                        totalBorrowedMinor = Math.addExact(totalBorrowedMinor, Math.negateExact(payDelta))
                    } else if (payDelta > 0L) {
                        totalRepaidPrincipalMinor = Math.addExact(totalRepaidPrincipalMinor, payDelta)
                    }
                }
            }
        }

        val receivableBalance = Money(rawReceivableMinor, currency)
        val payableBalance = Money(Math.negateExact(rawPayableMinor), currency)
        val netBalance = Money(Math.addExact(rawReceivableMinor, rawPayableMinor), currency)
        val hasOutstanding = rawReceivableMinor != 0L || rawPayableMinor != 0L

        val dueDate = profile.defaultDueAt?.let { dueMs ->
            Instant.ofEpochMilli(dueMs).atZone(zoneId).toLocalDate()
        }
        val daysUntilDue = dueDate?.let { ChronoUnit.DAYS.between(asOfDate, it) }
        val overdueDays = if (hasOutstanding && daysUntilDue != null && daysUntilDue < 0L) {
            -daysUntilDue
        } else {
            0L
        }
        val isDueToday = hasOutstanding && daysUntilDue == 0L
        val isInReminderWindow = hasOutstanding &&
            daysUntilDue != null &&
            daysUntilDue <= remindDaysBefore.toLong()

        val status = when {
            !hasOutstanding -> DebtProfileStatus.SETTLED
            daysUntilDue != null && daysUntilDue < 0L -> DebtProfileStatus.OVERDUE
            daysUntilDue != null && daysUntilDue <= remindDaysBefore.toLong() -> DebtProfileStatus.DUE_SOON
            else -> DebtProfileStatus.ACTIVE
        }

        return DebtProfileSummary(
            profile = profile,
            currency = currency,
            receivableBalance = receivableBalance,
            payableBalance = payableBalance,
            netBalance = netBalance,
            totalLent = Money(totalLentMinor, currency),
            totalCollectedPrincipal = Money(totalCollectedPrincipalMinor, currency),
            totalInterestIncome = Money(totalInterestIncomeMinor, currency),
            totalBadDebtWriteOff = Money(totalBadDebtWriteOffMinor, currency),
            totalBorrowed = Money(totalBorrowedMinor, currency),
            totalRepaidPrincipal = Money(totalRepaidPrincipalMinor, currency),
            totalInterestExpense = Money(totalInterestExpenseMinor, currency),
            transactionCount = txCount,
            dueDate = dueDate,
            daysUntilDue = daysUntilDue,
            overdueDays = overdueDays,
            isDueToday = isDueToday,
            isInReminderWindow = isInReminderWindow,
            status = status,
            asOfDate = asOfDate,
            remindDaysBefore = remindDaysBefore,
            zoneId = zoneId,
        )
    }

    /**
     * Classifies all `POSTED` transactions touching [profile]'s receivable/payable accounts,
     * sorted reverse-chronologically (most recent first) for timeline display.
     */
    fun classifyTransactions(
        profile: DebtProfile,
        currency: Currency,
        transactions: Iterable<Transaction>,
        accountsById: Map<String, Account>,
    ): List<DebtTransactionRecord> = transactions
        .asSequence()
        .filter { it.status == TransactionStatus.POSTED }
        .mapNotNull { tx ->
            classifyTransactionOrNull(
                transaction = tx,
                profile = profile,
                currency = currency,
                accountsById = accountsById,
            )
        }
        .sortedWith(
            compareByDescending<DebtTransactionRecord> { it.transaction.occurredAt }
                .thenByDescending { it.transaction.createdAt }
                .thenByDescending { it.transaction.id },
        )
        .toList()

    /**
     * Classifies a single transaction relative to [profile], or returns null if it does not touch
     * the profile's receivable or payable account.
     */
    fun classifyTransactionOrNull(
        transaction: Transaction,
        profile: DebtProfile,
        currency: Currency,
        accountsById: Map<String, Account>,
    ): DebtTransactionRecord? {
        val recvId = profile.receivableAccountId
        val payId = profile.payableAccountId
        val business = transaction.businessPostings

        var touchesProfile = false
        var recvDelta = 0L
        var payDelta = 0L
        var incomeCreditMinor = 0L
        var expenseDebitMinor = 0L
        var fundingAccountId: String? = null

        for (posting in business) {
            if (recvId != null && posting.accountId == recvId) {
                touchesProfile = true
                if (posting.currency == currency) {
                    recvDelta = Math.addExact(recvDelta, posting.amountMinor)
                }
                continue
            }
            if (payId != null && posting.accountId == payId) {
                touchesProfile = true
                if (posting.currency == currency) {
                    payDelta = Math.addExact(payDelta, posting.amountMinor)
                }
                continue
            }
            val acctType = accountsById[posting.accountId]?.type
            if (posting.currency == currency) {
                when (acctType) {
                    AccountType.INCOME -> {
                        if (posting.amountMinor < 0L) {
                            incomeCreditMinor = Math.addExact(incomeCreditMinor, Math.negateExact(posting.amountMinor))
                        }
                    }
                    AccountType.EXPENSE -> {
                        if (posting.amountMinor > 0L) {
                            expenseDebitMinor = Math.addExact(expenseDebitMinor, posting.amountMinor)
                        }
                    }
                    AccountType.ASSET, AccountType.LIABILITY -> {
                        if (fundingAccountId == null) {
                            fundingAccountId = posting.accountId
                        }
                    }
                    else -> Unit
                }
            }
        }

        if (!touchesProfile) return null

        val netDelta = Math.addExact(recvDelta, payDelta)

        return when {
            recvDelta > 0L && payDelta == 0L -> DebtTransactionRecord(
                transaction = transaction,
                kind = DebtTransactionKind.LEND,
                principalAmount = Money(recvDelta, currency),
                interestAmount = Money.zero(currency),
                writeOffAmount = Money.zero(currency),
                counterpartyNetDelta = Money(netDelta, currency),
                currency = currency,
                fundingAccountId = fundingAccountId,
            )

            recvDelta < 0L && payDelta == 0L -> {
                val recvReduction = Math.negateExact(recvDelta)
                val writeOffMinor = expenseDebitMinor.coerceAtMost(recvReduction)
                val collectedPrincipalMinor = Math.subtractExact(recvReduction, writeOffMinor)
                val kind = if (writeOffMinor > 0L && collectedPrincipalMinor == 0L && fundingAccountId == null) {
                    DebtTransactionKind.BAD_DEBT_WRITE_OFF
                } else {
                    DebtTransactionKind.COLLECT
                }
                DebtTransactionRecord(
                    transaction = transaction,
                    kind = kind,
                    principalAmount = Money(collectedPrincipalMinor, currency),
                    interestAmount = Money(incomeCreditMinor, currency),
                    writeOffAmount = Money(writeOffMinor, currency),
                    counterpartyNetDelta = Money(netDelta, currency),
                    currency = currency,
                    fundingAccountId = fundingAccountId,
                )
            }

            payDelta < 0L && recvDelta == 0L -> DebtTransactionRecord(
                transaction = transaction,
                kind = DebtTransactionKind.BORROW,
                principalAmount = Money(Math.negateExact(payDelta), currency),
                interestAmount = Money.zero(currency),
                writeOffAmount = Money.zero(currency),
                counterpartyNetDelta = Money(netDelta, currency),
                currency = currency,
                fundingAccountId = fundingAccountId,
            )

            payDelta > 0L && recvDelta == 0L -> DebtTransactionRecord(
                transaction = transaction,
                kind = DebtTransactionKind.REPAY,
                principalAmount = Money(payDelta, currency),
                interestAmount = Money(expenseDebitMinor, currency),
                writeOffAmount = Money.zero(currency),
                counterpartyNetDelta = Money(netDelta, currency),
                currency = currency,
                fundingAccountId = fundingAccountId,
            )

            else -> DebtTransactionRecord(
                transaction = transaction,
                kind = DebtTransactionKind.OTHER,
                principalAmount = Money(kotlin.math.abs(netDelta), currency),
                interestAmount = Money(
                    if (incomeCreditMinor > 0L) incomeCreditMinor else expenseDebitMinor,
                    currency,
                ),
                writeOffAmount = Money.zero(currency),
                counterpartyNetDelta = Money(netDelta, currency),
                currency = currency,
                fundingAccountId = fundingAccountId,
            )
        }
    }

    /**
     * Aggregates receivable, payable, and net totals grouped by currency across all profiles.
     */
    fun aggregatePortfolioTotals(
        summaries: Iterable<DebtProfileSummary>,
    ): Map<Currency, DebtPortfolioCurrencyTotals> {
        val grouped = summaries.groupBy { it.currency }
        return grouped.mapValues { (currency, list) ->
            var recvMinor = 0L
            var payMinor = 0L
            var activeCount = 0
            var overdueCount = 0
            for (item in list) {
                recvMinor = Math.addExact(recvMinor, item.receivableBalance.amountMinor)
                payMinor = Math.addExact(payMinor, item.payableBalance.amountMinor)
                if (item.hasOutstandingBalance) activeCount++
                if (item.status == DebtProfileStatus.OVERDUE) overdueCount++
            }
            DebtPortfolioCurrencyTotals(
                currency = currency,
                totalReceivable = Money(recvMinor, currency),
                totalPayable = Money(payMinor, currency),
                netBalance = Money(Math.subtractExact(recvMinor, payMinor), currency),
                activeCounterpartyCount = activeCount,
                overdueCounterpartyCount = overdueCount,
            )
        }
    }

    private fun resolveCurrency(
        profile: DebtProfile,
        receivableAccount: Account?,
        payableAccount: Account?,
    ): Currency {
        if (receivableAccount != null && payableAccount != null) {
            require(receivableAccount.currency == payableAccount.currency) {
                "DebtProfile '${profile.id}' receivable currency (${receivableAccount.currency}) " +
                    "differs from payable currency (${payableAccount.currency})"
            }
            return receivableAccount.currency
        }
        return receivableAccount?.currency ?: payableAccount?.currency ?: Currency.DEFAULT
    }
}
