package io.github.kvtttba.droplet.core.domain.credit

import io.github.kvtttba.droplet.core.domain.investment.InvestmentDecimalMath
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionStatus
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Lifecycle status of a credit card's most recently closed statement.
 */
enum class CreditCardStatementStatus {
    /** Billing day or due day is not yet configured on the credit card account. */
    UNCONFIGURED,

    /** Current closed statement has zero remaining balance due (paid off or no charges). */
    PAID_OFF,

    /** Closed statement has remaining balance due outside the advance reminder window. */
    PENDING,

    /** Closed statement has remaining balance due and today is within `[dueDate - remindDaysBefore, dueDate]`. */
    DUE_SOON,

    /** Closed statement still has remaining balance due and today is strictly after due date. */
    OVERDUE,
}

/**
 * Inclusive calendar-date range paired with half-open `[startEpochMs, endEpochMsExclusive)` bounds
 * in the calculator's [ZoneId].
 */
data class CreditCardDateRange(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val startEpochMs: Long,
    val endEpochMsExclusive: Long,
) {
    init {
        require(!endDate.isBefore(startDate)) {
            "endDate ($endDate) must not be before startDate ($startDate)"
        }
        require(endEpochMsExclusive > startEpochMs) {
            "endEpochMsExclusive ($endEpochMsExclusive) must be greater than startEpochMs ($startEpochMs)"
        }
    }

    operator fun contains(date: LocalDate): Boolean =
        !date.isBefore(startDate) && !date.isAfter(endDate)

    fun containsEpochMs(epochMs: Long): Boolean =
        epochMs in startEpochMs until endEpochMsExclusive
}

/**
 * Partitioned credit card ledger items grouped by statement cycle.
 */
data class CreditCardPartitionedRecords<T>(
    val billed: List<T>,
    val unbilled: List<T>,
    val all: List<T>,
)

/**
 * Complete zero-floating-point billing cycle, quota, and repayment snapshot for a credit card account.
 *
 * Algebraic invariants:
 * - `currentBalance.amountMinor == -currentDebt.amountMinor + overpayment.amountMinor`
 * - `remainingStatementDue + unbilledRemainingDebt == currentDebt` (when billing day is configured)
 * - `availableCredit == max(0, creditLimit + currentBalance)` (when credit limit is configured,
 *   so overpayments increase available spending power and over-limit debt clamps available credit to 0)
 */
data class CreditCardStatementSummary(
    val account: Account,
    val asOfDate: LocalDate,
    val zoneId: ZoneId,
    val currentBalance: Money,
    val currentDebt: Money,
    val overpayment: Money,
    val creditLimit: Money?,
    val usedLimit: Money,
    val availableCredit: Money?,
    val overLimitAmount: Money,
    val utilizationPctDecimal: String?,
    val isBillingScheduleConfigured: Boolean,
    val lastStatementDate: LocalDate?,
    val nextStatementDate: LocalDate?,
    val lastStatementPeriod: CreditCardDateRange?,
    val unbilledPeriod: CreditCardDateRange?,
    val isBillingDayToday: Boolean,
    val daysUntilNextBilling: Int?,
    val currentStatementDueDate: LocalDate?,
    val nextStatementDueDate: LocalDate?,
    val daysUntilCurrentDue: Int?,
    val daysUntilNextDue: Int?,
    val isDueToday: Boolean,
    val overdueDays: Int,
    val effectiveRemindDaysBefore: Int,
    val isInReminderWindow: Boolean,
    val status: CreditCardStatementStatus,
    val statementPeriodCharges: Money,
    val statementPeriodCredits: Money,
    val statementTotalDue: Money,
    val statementPaidAfterClosing: Money,
    val remainingStatementDue: Money,
    val unbilledCharges: Money,
    val unbilledCredits: Money,
    val unbilledRemainingDebt: Money,
    val billedTransactionCount: Int,
    val unbilledTransactionCount: Int,
) {
    /**
     * Most relevant due date for UI countdowns:
     * - While the current closed statement has unpaid balance (including overdue), points to [currentStatementDueDate].
     * - Once paid off and past [currentStatementDueDate], rolls forward to [nextStatementDueDate].
     */
    val displayDueDate: LocalDate?
        get() = when {
            currentStatementDueDate == null -> null
            remainingStatementDue.isPositive -> currentStatementDueDate
            daysUntilCurrentDue != null && daysUntilCurrentDue < 0 -> nextStatementDueDate
            else -> currentStatementDueDate
        }

    val daysUntilDisplayDue: Int?
        get() = when {
            daysUntilCurrentDue == null -> null
            remainingStatementDue.isPositive -> daysUntilCurrentDue
            daysUntilCurrentDue < 0 -> daysUntilNextDue
            else -> daysUntilCurrentDue
        }

    val hasLimit: Boolean
        get() = creditLimit != null && creditLimit.isPositive
}

/**
 * Pure Kotlin/JVM credit-card billing cycle, credit-limit, statement balance, and due-reminder calculator.
 *
 * Strictly zero floating-point: uses fixed-point [Money] (`Long`, scale = 4), `BigDecimal`, and `java.time`.
 *
 * Calendar conventions:
 * 1. Month-end clamping: day `29..31` is clamped to `YearMonth.lengthOfMonth()` (e.g. `31 -> Feb 28` or `Feb 29`
 *    in leap years, `Apr 30`).
 * 2. Statement period: `(previousStatementDate, lastStatementDate]` inclusive of `lastStatementDate`, with
 *    unbilled period `(lastStatementDate, nextStatementDate]`.
 * 3. Same-month vs cross-month due day:
 *    - When `dueDay > billingDay`, the statement due date lies in the same calendar month as the statement date.
 *    - When `dueDay <= billingDay`, the statement due date rolls into the following calendar month.
 * 4. Statement balance & post-closing offset:
 *    - On a `LIABILITY / CREDIT_CARD` account, charges are negative postings and repayments/refunds are positive.
 *    - `statementTotalDue = max(0, -closingBalanceAsOfLastStatementDate)` (naturally carries forward any prior
 *      unpaid balance or deducts any prior overpayment credit).
 *    - Post-closing positive postings (`txDate > lastStatementDate`) reduce `statementTotalDue` down to `0`,
 *      giving `remainingStatementDue`.
 * 5. Reminder window:
 *    - Active when `billingDay != null`, `dueDay != null`, `remainingStatementDue > 0`, and
 *      `daysUntilCurrentDue <= effectiveRemindDaysBefore` (covering both `[0, effectiveRemindDaysBefore]`
 *      upcoming days and `< 0` overdue days until paid off).
 */
object CreditCardStatementCalculator {

    const val DEFAULT_REMIND_DAYS_BEFORE: Int = 3
    val DEFAULT_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")

    fun clampDayOfMonth(yearMonth: YearMonth, dayOfMonth: Int): LocalDate {
        require(dayOfMonth in 1..31) {
            "dayOfMonth must be in 1..31, got $dayOfMonth"
        }
        return yearMonth.atDay(minOf(dayOfMonth, yearMonth.lengthOfMonth()))
    }

    /**
     * Resolves the due date corresponding to a statement closing on [statementDate].
     */
    fun dueDateForStatement(
        statementDate: LocalDate,
        billingDay: Int,
        dueDay: Int,
    ): LocalDate {
        require(billingDay in 1..31) { "billingDay must be in 1..31, got $billingDay" }
        require(dueDay in 1..31) { "dueDay must be in 1..31, got $dueDay" }
        val statementMonth = YearMonth.from(statementDate)
        val targetMonth = if (dueDay > billingDay) {
            statementMonth
        } else {
            statementMonth.plusMonths(1)
        }
        return clampDayOfMonth(targetMonth, dueDay)
    }

    fun calculate(
        account: Account,
        transactions: Iterable<Transaction>,
        asOfEpochMs: Long,
        defaultRemindDaysBefore: Int = DEFAULT_REMIND_DAYS_BEFORE,
        zoneId: ZoneId = DEFAULT_ZONE_ID,
    ): CreditCardStatementSummary {
        val asOfDate = Instant.ofEpochMilli(asOfEpochMs).atZone(zoneId).toLocalDate()
        return calculate(
            account = account,
            transactions = transactions,
            asOfDate = asOfDate,
            defaultRemindDaysBefore = defaultRemindDaysBefore,
            zoneId = zoneId,
        )
    }

    fun calculate(
        account: Account,
        transactions: Iterable<Transaction>,
        asOfDate: LocalDate,
        defaultRemindDaysBefore: Int = DEFAULT_REMIND_DAYS_BEFORE,
        zoneId: ZoneId = DEFAULT_ZONE_ID,
    ): CreditCardStatementSummary {
        require(account.type == AccountType.LIABILITY && account.subType == AccountSubType.CREDIT_CARD) {
            "CreditCardStatementCalculator requires a LIABILITY / CREDIT_CARD account, " +
                "got ${account.type} / ${account.subType}"
        }
        require(defaultRemindDaysBefore in 0..31) {
            "defaultRemindDaysBefore must be in 0..31, got $defaultRemindDaysBefore"
        }

        val currency = account.currency
        val effectiveRemindDays = account.remindDaysBefore ?: defaultRemindDaysBefore
        val billingDay = account.billingDay
        val dueDay = account.dueDay

        // Resolve statement dates & periods when billingDay is configured
        val lastStatementDate: LocalDate?
        val previousStatementDate: LocalDate?
        val nextStatementDate: LocalDate?
        val lastStatementPeriod: CreditCardDateRange?
        val unbilledPeriod: CreditCardDateRange?
        val isBillingDayToday: Boolean
        val daysUntilNextBilling: Int?

        if (billingDay != null) {
            val currentMonth = YearMonth.from(asOfDate)
            val thisMonthBillingDate = clampDayOfMonth(currentMonth, billingDay)
            if (!asOfDate.isBefore(thisMonthBillingDate)) {
                lastStatementDate = thisMonthBillingDate
                previousStatementDate = clampDayOfMonth(currentMonth.minusMonths(1), billingDay)
                nextStatementDate = clampDayOfMonth(currentMonth.plusMonths(1), billingDay)
            } else {
                lastStatementDate = clampDayOfMonth(currentMonth.minusMonths(1), billingDay)
                previousStatementDate = clampDayOfMonth(currentMonth.minusMonths(2), billingDay)
                nextStatementDate = thisMonthBillingDate
            }
            lastStatementPeriod = buildDateRange(
                startDate = previousStatementDate.plusDays(1),
                endDate = lastStatementDate,
                zoneId = zoneId,
            )
            unbilledPeriod = buildDateRange(
                startDate = lastStatementDate.plusDays(1),
                endDate = nextStatementDate,
                zoneId = zoneId,
            )
            isBillingDayToday = asOfDate == lastStatementDate
            daysUntilNextBilling = ChronoUnit.DAYS.between(asOfDate, nextStatementDate).toInt()
        } else {
            lastStatementDate = null
            previousStatementDate = null
            nextStatementDate = null
            lastStatementPeriod = null
            unbilledPeriod = null
            isBillingDayToday = false
            daysUntilNextBilling = null
        }

        val currentStatementDueDate: LocalDate?
        val nextStatementDueDate: LocalDate?
        val daysUntilCurrentDue: Int?
        val daysUntilNextDue: Int?

        if (billingDay != null && dueDay != null && lastStatementDate != null && nextStatementDate != null) {
            currentStatementDueDate = dueDateForStatement(lastStatementDate, billingDay, dueDay)
            nextStatementDueDate = dueDateForStatement(nextStatementDate, billingDay, dueDay)
            daysUntilCurrentDue = ChronoUnit.DAYS.between(asOfDate, currentStatementDueDate).toInt()
            daysUntilNextDue = ChronoUnit.DAYS.between(asOfDate, nextStatementDueDate).toInt()
        } else {
            currentStatementDueDate = null
            nextStatementDueDate = null
            daysUntilCurrentDue = null
            daysUntilNextDue = null
        }

        // Accumulate POSTED card postings strictly with exact Long arithmetic
        var totalBalanceMinor = 0L
        var closingBalanceMinor = 0L
        var statementPeriodChargesMinor = 0L
        var statementPeriodCreditsMinor = 0L
        var postClosingCreditsMinor = 0L
        var unbilledChargesMinor = 0L
        var billedTxCount = 0
        var unbilledTxCount = 0

        for (tx in transactions) {
            if (tx.status != TransactionStatus.POSTED) continue
            val deltaMinor = netPostingDeltaMinor(tx, account)
            if (deltaMinor == 0L) continue

            totalBalanceMinor = Math.addExact(totalBalanceMinor, deltaMinor)

            if (lastStatementDate != null && lastStatementPeriod != null) {
                val txDate = Instant.ofEpochMilli(tx.occurredAt).atZone(zoneId).toLocalDate()
                if (!txDate.isAfter(lastStatementDate)) {
                    closingBalanceMinor = Math.addExact(closingBalanceMinor, deltaMinor)
                    if (txDate in lastStatementPeriod) {
                        billedTxCount++
                        if (deltaMinor < 0L) {
                            statementPeriodChargesMinor = Math.addExact(
                                statementPeriodChargesMinor,
                                Math.negateExact(deltaMinor),
                            )
                        } else {
                            statementPeriodCreditsMinor = Math.addExact(statementPeriodCreditsMinor, deltaMinor)
                        }
                    }
                } else {
                    unbilledTxCount++
                    if (deltaMinor < 0L) {
                        unbilledChargesMinor = Math.addExact(
                            unbilledChargesMinor,
                            Math.negateExact(deltaMinor),
                        )
                    } else {
                        postClosingCreditsMinor = Math.addExact(postClosingCreditsMinor, deltaMinor)
                    }
                }
            } else {
                unbilledTxCount++
                if (deltaMinor < 0L) {
                    unbilledChargesMinor = Math.addExact(
                        unbilledChargesMinor,
                        Math.negateExact(deltaMinor),
                    )
                } else {
                    postClosingCreditsMinor = Math.addExact(postClosingCreditsMinor, deltaMinor)
                }
            }
        }

        val currentBalance = Money(totalBalanceMinor, currency)
        val currentDebtMinor = if (totalBalanceMinor < 0L) Math.negateExact(totalBalanceMinor) else 0L
        val overpaymentMinor = if (totalBalanceMinor > 0L) totalBalanceMinor else 0L
        val currentDebt = Money(currentDebtMinor, currency)
        val overpayment = Money(overpaymentMinor, currency)

        val creditLimit = account.creditLimit
        val availableCredit: Money?
        val overLimitAmount: Money
        val utilizationPctDecimal: String?

        if (creditLimit != null) {
            val rawAvailableMinor = Math.addExact(creditLimit.amountMinor, totalBalanceMinor)
            availableCredit = Money(rawAvailableMinor.coerceAtLeast(0L), currency)
            val overMinor = Math.subtractExact(currentDebtMinor, creditLimit.amountMinor).coerceAtLeast(0L)
            overLimitAmount = Money(overMinor, currency)
            utilizationPctDecimal = if (creditLimit.isPositive) {
                InvestmentDecimalMath.percentageOrNull(
                    numerator = currentDebt.toBigDecimal(),
                    denominator = creditLimit.toBigDecimal(),
                    scale = 2,
                )
            } else {
                null
            }
        } else {
            availableCredit = null
            overLimitAmount = Money.zero(currency)
            utilizationPctDecimal = null
        }

        val statementTotalDueMinor = if (lastStatementDate != null && closingBalanceMinor < 0L) {
            Math.negateExact(closingBalanceMinor)
        } else {
            0L
        }
        val statementPaidAfterClosingMinor = if (lastStatementDate != null) {
            postClosingCreditsMinor
        } else {
            0L
        }
        val remainingStatementDueMinor = if (lastStatementDate != null) {
            Math.subtractExact(statementTotalDueMinor, statementPaidAfterClosingMinor).coerceAtLeast(0L)
        } else {
            0L
        }
        val unbilledRemainingDebtMinor = Math.subtractExact(currentDebtMinor, remainingStatementDueMinor)
            .coerceAtLeast(0L)

        val isScheduleConfigured = billingDay != null && dueDay != null
        val status: CreditCardStatementStatus
        val overdueDays: Int
        val isDueToday: Boolean
        val isInReminderWindow: Boolean

        if (!isScheduleConfigured || daysUntilCurrentDue == null) {
            status = CreditCardStatementStatus.UNCONFIGURED
            overdueDays = 0
            isDueToday = false
            isInReminderWindow = false
        } else if (remainingStatementDueMinor == 0L) {
            status = CreditCardStatementStatus.PAID_OFF
            overdueDays = 0
            isDueToday = false
            isInReminderWindow = false
        } else if (daysUntilCurrentDue < 0) {
            status = CreditCardStatementStatus.OVERDUE
            overdueDays = Math.negateExact(daysUntilCurrentDue)
            isDueToday = false
            isInReminderWindow = true
        } else if (daysUntilCurrentDue <= effectiveRemindDays) {
            status = CreditCardStatementStatus.DUE_SOON
            overdueDays = 0
            isDueToday = daysUntilCurrentDue == 0
            isInReminderWindow = true
        } else {
            status = CreditCardStatementStatus.PENDING
            overdueDays = 0
            isDueToday = false
            isInReminderWindow = false
        }

        return CreditCardStatementSummary(
            account = account,
            asOfDate = asOfDate,
            zoneId = zoneId,
            currentBalance = currentBalance,
            currentDebt = currentDebt,
            overpayment = overpayment,
            creditLimit = creditLimit,
            usedLimit = currentDebt,
            availableCredit = availableCredit,
            overLimitAmount = overLimitAmount,
            utilizationPctDecimal = utilizationPctDecimal,
            isBillingScheduleConfigured = isScheduleConfigured,
            lastStatementDate = lastStatementDate,
            nextStatementDate = nextStatementDate,
            lastStatementPeriod = lastStatementPeriod,
            unbilledPeriod = unbilledPeriod,
            isBillingDayToday = isBillingDayToday,
            daysUntilNextBilling = daysUntilNextBilling,
            currentStatementDueDate = currentStatementDueDate,
            nextStatementDueDate = nextStatementDueDate,
            daysUntilCurrentDue = daysUntilCurrentDue,
            daysUntilNextDue = daysUntilNextDue,
            isDueToday = isDueToday,
            overdueDays = overdueDays,
            effectiveRemindDaysBefore = effectiveRemindDays,
            isInReminderWindow = isInReminderWindow,
            status = status,
            statementPeriodCharges = Money(statementPeriodChargesMinor, currency),
            statementPeriodCredits = Money(statementPeriodCreditsMinor, currency),
            statementTotalDue = Money(statementTotalDueMinor, currency),
            statementPaidAfterClosing = Money(statementPaidAfterClosingMinor, currency),
            remainingStatementDue = Money(remainingStatementDueMinor, currency),
            unbilledCharges = Money(unbilledChargesMinor, currency),
            unbilledCredits = Money(statementPaidAfterClosingMinor, currency),
            unbilledRemainingDebt = Money(unbilledRemainingDebtMinor, currency),
            billedTransactionCount = billedTxCount,
            unbilledTransactionCount = unbilledTxCount,
        )
    }

    /**
     * Computes the net signed posting delta for [account] inside [transaction], ignoring implicit
     * `Equity:Trading` balancing lines and postings in other currencies.
     */
    fun netPostingDeltaMinor(transaction: Transaction, account: Account): Long {
        var delta = 0L
        for (posting in transaction.postings) {
            if (!posting.isSystemBalancing &&
                posting.accountId == account.id &&
                posting.currency == account.currency
            ) {
                delta = Math.addExact(delta, posting.amountMinor)
            }
        }
        return delta
    }

    /**
     * Partitions credit-card transaction records into billed (within [CreditCardStatementSummary.lastStatementPeriod]),
     * unbilled (strictly after [CreditCardStatementSummary.lastStatementDate]), and all posted records.
     */
    fun <T> partitionRecords(
        records: List<T>,
        summary: CreditCardStatementSummary,
        transactionSelector: (T) -> Transaction,
    ): CreditCardPartitionedRecords<T> {
        val postedForCard = records
            .filter { item ->
                val tx = transactionSelector(item)
                tx.status == TransactionStatus.POSTED &&
                    netPostingDeltaMinor(tx, summary.account) != 0L
            }
            .sortedWith(
                compareByDescending<T> { transactionSelector(it).occurredAt }
                    .thenByDescending { transactionSelector(it).createdAt }
                    .thenByDescending { transactionSelector(it).id },
            )

        val lastDate = summary.lastStatementDate
        val billedRange = summary.lastStatementPeriod
        if (lastDate == null || billedRange == null) {
            return CreditCardPartitionedRecords(
                billed = emptyList(),
                unbilled = postedForCard,
                all = postedForCard,
            )
        }

        val billed = mutableListOf<T>()
        val unbilled = mutableListOf<T>()
        for (item in postedForCard) {
            val txDate = Instant.ofEpochMilli(transactionSelector(item).occurredAt)
                .atZone(summary.zoneId)
                .toLocalDate()
            when {
                txDate in billedRange -> billed += item
                txDate.isAfter(lastDate) -> unbilled += item
            }
        }
        return CreditCardPartitionedRecords(
            billed = billed,
            unbilled = unbilled,
            all = postedForCard,
        )
    }

    private fun buildDateRange(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId,
    ): CreditCardDateRange = CreditCardDateRange(
        startDate = startDate,
        endDate = endDate,
        startEpochMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        endEpochMsExclusive = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
    )
}
