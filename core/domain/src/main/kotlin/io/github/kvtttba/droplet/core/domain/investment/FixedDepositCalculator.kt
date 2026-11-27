package io.github.kvtttba.droplet.core.domain.investment

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.PostingDraft
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DepositInterestMode
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.UnitPrice
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class FixedDepositStatus {
    /** Valuation date precedes the deposit's interest start date. */
    NOT_STARTED,

    /** Deposit is actively accruing interest between start date (inclusive) and maturity date (exclusive). */
    ACCRUING,

    /** Valuation date has reached or passed maturity date. */
    MATURED,
}

/**
 * Pure deterministic fixed-deposit valuation snapshot computed on the Actual/365 day-count basis.
 */
data class FixedDepositValuation(
    val assetId: String,
    val principal: Money,
    val annualRateDecimal: String,
    val interestMode: DepositInterestMode,
    val startDate: LocalDate,
    val maturityDate: LocalDate,
    val asOfDate: LocalDate,
    val totalTermDays: Long,
    val elapsedDays: Long,
    val remainingDays: Long,
    val daysUntilStart: Long,
    val overdueDays: Long,
    val status: FixedDepositStatus,
    val accruedInterest: Money,
    val alreadyPaidInterest: Money,
    val unpaidAccruedInterest: Money,
    val expectedTotalInterest: Money,
    val currentTotalValue: Money,
    val expectedMaturityTotalValue: Money,
    val dailyAccrual: Money,
    val estimatedMonthlyPayout: Money? = null,
    val completedMonthlyPeriods: Int = 0,
    val dayCountBasis: String = FixedDepositCalculator.DAY_COUNT_BASIS,
) {
    val isMatured: Boolean
        get() = status == FixedDepositStatus.MATURED
}

/**
 * Local zero-floating-point fixed-deposit accrual calculator and double-entry settlement helper.
 *
 * Day-count convention (**Actual/365**):
 * - Calendar day spans use exact [ChronoUnit.DAYS] in [zoneId] (e.g. a leap year spans 366 actual days),
 *   divided by a fixed 365-day annual basis (`ACT/365`).
 * - Interest accrual is capped at `maturityDate` (overdue days do not automatically renew contract rate).
 * - Mode formulas (strict `BigDecimal` arithmetic, intermediate scale = 16, `HALF_UP` to `Money` scale = 4):
 *   1. [DepositInterestMode.SIMPLE_AT_MATURITY]:
 *      `interest(d) = principal * annualRate * (d / 365)`
 *   2. [DepositInterestMode.COMPOUND_ANNUAL]:
 *      Split `d = 365 * fullYears + remainderDays`;
 *      `factor(d) = (1 + annualRate)^fullYears * (1 + annualRate * remainderDays / 365) - 1`;
 *      `interest(d) = principal * factor(d)` (exact annual compounding with linear intra-year accrual, zero floats).
 *   3. [DepositInterestMode.MONTHLY_PAYOUT]:
 *      Non-compounding simple daily accrual `principal * annualRate * (d / 365)` with
 *      `estimatedMonthlyPayout = principal * annualRate / 12` and `completedMonthlyPeriods` calendar months.
 *      `unpaidAccruedInterest = max(0, accruedInterest - alreadyPaidInterest)` so paid-out monthly interest
 *      already sitting in a cash account is never double-counted in [FixedDepositValuation.currentTotalValue].
 */
object FixedDepositCalculator {

    const val DAY_COUNT_BASIS: String = "ACT/365"
    val DEFAULT_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")

    private val DAYS_PER_YEAR: BigDecimal = BigDecimal("365")
    private val MONTHS_PER_YEAR: BigDecimal = BigDecimal("12")

    fun calculate(
        asset: InvestmentAsset,
        principal: Money,
        asOfEpochMs: Long,
        alreadyPaidInterest: Money = Money.zero(principal.currency),
        zoneId: ZoneId = DEFAULT_ZONE_ID,
    ): FixedDepositValuation {
        require(asset.assetClass == AssetClass.FIXED_DEPOSIT) {
            "FixedDepositCalculator only supports FIXED_DEPOSIT assets, got ${asset.assetClass}"
        }
        require(principal.currency == asset.currency) {
            "Principal currency (${principal.currency}) must match asset currency (${asset.currency})"
        }
        val rateDecimal = requireNotNull(asset.depositRateAnnualDecimal) {
            "Fixed deposit '${asset.id}' is missing depositRateAnnualDecimal"
        }
        val startAt = requireNotNull(asset.depositStartAt) {
            "Fixed deposit '${asset.id}' is missing depositStartAt"
        }
        val maturityAt = requireNotNull(asset.depositMaturityAt) {
            "Fixed deposit '${asset.id}' is missing depositMaturityAt"
        }
        val mode = requireNotNull(asset.depositInterestMode) {
            "Fixed deposit '${asset.id}' is missing depositInterestMode"
        }

        val startDate = Instant.ofEpochMilli(startAt).atZone(zoneId).toLocalDate()
        val maturityDate = Instant.ofEpochMilli(maturityAt).atZone(zoneId).toLocalDate()
        val asOfDate = Instant.ofEpochMilli(asOfEpochMs).atZone(zoneId).toLocalDate()

        return calculateByDates(
            assetId = asset.id,
            currency = asset.currency,
            principal = principal,
            annualRateDecimal = rateDecimal,
            startDate = startDate,
            maturityDate = maturityDate,
            asOfDate = asOfDate,
            interestMode = mode,
            alreadyPaidInterest = alreadyPaidInterest,
        )
    }

    fun calculateByDates(
        assetId: String,
        currency: Currency,
        principal: Money,
        annualRateDecimal: String,
        startDate: LocalDate,
        maturityDate: LocalDate,
        asOfDate: LocalDate,
        interestMode: DepositInterestMode,
        alreadyPaidInterest: Money = Money.zero(currency),
    ): FixedDepositValuation {
        require(assetId.isNotBlank()) { "assetId must not be blank" }
        require(principal.currency == currency) {
            "Principal currency (${principal.currency}) must match deposit currency ($currency)"
        }
        require(!principal.isNegative) {
            "Fixed deposit principal must be non-negative, got $principal"
        }
        require(alreadyPaidInterest.currency == currency) {
            "alreadyPaidInterest currency (${alreadyPaidInterest.currency}) must match deposit currency ($currency)"
        }
        require(!alreadyPaidInterest.isNegative) {
            "alreadyPaidInterest must be non-negative, got $alreadyPaidInterest"
        }
        require(!maturityDate.isBefore(startDate)) {
            "maturityDate ($maturityDate) must not precede startDate ($startDate)"
        }

        val annualRate = BigDecimal(annualRateDecimal)
        require(annualRate.compareTo(BigDecimal.ZERO) >= 0) {
            "Annual deposit rate must be non-negative, got '$annualRateDecimal'"
        }
        val canonicalRate = InvestmentDecimalMath.normalize(annualRate, ExchangeRateSnapshot.SCALE)

        val totalTermDays = ChronoUnit.DAYS.between(startDate, maturityDate)
        val status = when {
            asOfDate.isBefore(startDate) -> FixedDepositStatus.NOT_STARTED
            asOfDate.isBefore(maturityDate) -> FixedDepositStatus.ACCRUING
            else -> FixedDepositStatus.MATURED
        }

        val daysUntilStart = if (asOfDate.isBefore(startDate)) {
            ChronoUnit.DAYS.between(asOfDate, startDate)
        } else {
            0L
        }
        val overdueDays = if (asOfDate.isAfter(maturityDate)) {
            ChronoUnit.DAYS.between(maturityDate, asOfDate)
        } else {
            0L
        }
        val elapsedDays = when (status) {
            FixedDepositStatus.NOT_STARTED -> 0L
            FixedDepositStatus.ACCRUING -> ChronoUnit.DAYS.between(startDate, asOfDate)
            FixedDepositStatus.MATURED -> totalTermDays
        }
        val remainingDays = if (asOfDate.isBefore(maturityDate)) {
            ChronoUnit.DAYS.between(asOfDate, maturityDate)
        } else {
            0L
        }

        val principalBd = principal.toBigDecimal()
        val accruedInterest = computeInterest(
            principalBd = principalBd,
            annualRate = annualRate,
            days = elapsedDays,
            mode = interestMode,
            currency = currency,
        )
        val expectedTotalInterest = computeInterest(
            principalBd = principalBd,
            annualRate = annualRate,
            days = totalTermDays,
            mode = interestMode,
            currency = currency,
        )

        val unpaidAccruedInterest = if (accruedInterest > alreadyPaidInterest) {
            accruedInterest - alreadyPaidInterest
        } else {
            Money.zero(currency)
        }

        val currentTotalValue = principal + unpaidAccruedInterest
        val expectedMaturityTotalValue = principal + expectedTotalInterest

        val dailyAccrual = if (status == FixedDepositStatus.ACCRUING && principal.isPositive && annualRate.compareTo(BigDecimal.ZERO) > 0) {
            val nextDayInterest = computeInterest(
                principalBd = principalBd,
                annualRate = annualRate,
                days = minOf(elapsedDays + 1L, totalTermDays),
                mode = interestMode,
                currency = currency,
            )
            if (nextDayInterest > accruedInterest) {
                nextDayInterest - accruedInterest
            } else {
                Money.zero(currency)
            }
        } else {
            Money.zero(currency)
        }

        val estimatedMonthlyPayout = if (interestMode == DepositInterestMode.MONTHLY_PAYOUT) {
            val monthlyBd = principalBd
                .multiply(annualRate)
                .divide(MONTHS_PER_YEAR, InvestmentDecimalMath.INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
            Money.fromBigDecimal(monthlyBd, currency)
        } else {
            null
        }

        val completedMonthlyPeriods = if (interestMode == DepositInterestMode.MONTHLY_PAYOUT) {
            when (status) {
                FixedDepositStatus.NOT_STARTED -> 0
                FixedDepositStatus.ACCRUING -> ChronoUnit.MONTHS.between(startDate, asOfDate).toInt().coerceAtLeast(0)
                FixedDepositStatus.MATURED -> ChronoUnit.MONTHS.between(startDate, maturityDate).toInt().coerceAtLeast(0)
            }
        } else {
            0
        }

        return FixedDepositValuation(
            assetId = assetId,
            principal = principal,
            annualRateDecimal = canonicalRate,
            interestMode = interestMode,
            startDate = startDate,
            maturityDate = maturityDate,
            asOfDate = asOfDate,
            totalTermDays = totalTermDays,
            elapsedDays = elapsedDays,
            remainingDays = remainingDays,
            daysUntilStart = daysUntilStart,
            overdueDays = overdueDays,
            status = status,
            accruedInterest = accruedInterest,
            alreadyPaidInterest = alreadyPaidInterest,
            unpaidAccruedInterest = unpaidAccruedInterest,
            expectedTotalInterest = expectedTotalInterest,
            currentTotalValue = currentTotalValue,
            expectedMaturityTotalValue = expectedMaturityTotalValue,
            dailyAccrual = dailyAccrual,
            estimatedMonthlyPayout = estimatedMonthlyPayout,
            completedMonthlyPeriods = completedMonthlyPeriods,
        )
    }

    /**
     * Builds a balanced double-entry transaction for fixed-deposit maturity settlement or
     * partial/full early withdrawal:
     * - Credits holding account by `-withdrawnPrincipal` with negative [settledQuantity]
     * - Credits [interestIncomeAccountId] by `-interestAmount` (when positive) tagged with [assetId]
     * - Debits [penaltyFeeExpenseAccountId] by `+penaltyOrTaxFee` (when positive)
     * - Debits [cashAccountId] by `+(withdrawnPrincipal + interestAmount - penaltyOrTaxFee)`
     */
    fun buildSettlementTransaction(
        ledgerEngine: LedgerEngine,
        meta: TransactionMeta,
        assetId: String,
        holdingAccountId: String,
        cashAccountId: String,
        interestIncomeAccountId: String,
        withdrawnPrincipal: Money,
        interestAmount: Money = Money.zero(withdrawnPrincipal.currency),
        settledQuantity: Quantity = Quantity.of(withdrawnPrincipal.toBigDecimal()),
        unitPrice: UnitPrice? = null,
        penaltyOrTaxFee: Money? = null,
        penaltyFeeExpenseAccountId: String? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(assetId.isNotBlank()) { "assetId must not be blank" }
        require(withdrawnPrincipal.isPositive) {
            "withdrawnPrincipal must be strictly positive, got $withdrawnPrincipal"
        }
        require(!interestAmount.isNegative) {
            "interestAmount must be non-negative, got $interestAmount"
        }
        require(interestAmount.currency == withdrawnPrincipal.currency) {
            "interestAmount currency (${interestAmount.currency}) must match principal currency (${withdrawnPrincipal.currency})"
        }
        require(settledQuantity.isPositive) {
            "settledQuantity must be strictly positive, got $settledQuantity"
        }

        val resolvedUnitPrice = unitPrice ?: UnitPrice.of(
            withdrawnPrincipal.toBigDecimal().divide(
                settledQuantity.toBigDecimal(),
                UnitPrice.MAX_SCALE,
                RoundingMode.HALF_UP,
            ),
        )
        require(resolvedUnitPrice.isPositive) {
            "Resolved fixed deposit settlement unitPrice must be strictly positive"
        }

        val grossTotal = withdrawnPrincipal + interestAmount
        val netCash = if (penaltyOrTaxFee != null) {
            require(penaltyOrTaxFee.isPositive) {
                "penaltyOrTaxFee must be strictly positive when provided, got $penaltyOrTaxFee"
            }
            require(penaltyOrTaxFee.currency == withdrawnPrincipal.currency) {
                "penaltyOrTaxFee currency (${penaltyOrTaxFee.currency}) must match principal currency (${withdrawnPrincipal.currency})"
            }
            require(!penaltyFeeExpenseAccountId.isNullOrBlank()) {
                "penaltyFeeExpenseAccountId is required when penaltyOrTaxFee is provided"
            }
            require(penaltyOrTaxFee < grossTotal) {
                "penaltyOrTaxFee ($penaltyOrTaxFee) must be strictly less than gross settlement ($grossTotal)"
            }
            grossTotal - penaltyOrTaxFee
        } else {
            grossTotal
        }

        val drafts = mutableListOf<PostingDraft>()
        drafts += PostingDraft(
            accountId = holdingAccountId,
            amount = -withdrawnPrincipal,
            exchangeRateOverride = exchangeRateOverride,
            investmentAssetId = assetId,
            quantity = -settledQuantity,
            unitPrice = resolvedUnitPrice,
        )
        if (interestAmount.isPositive) {
            require(interestIncomeAccountId.isNotBlank()) {
                "interestIncomeAccountId must not be blank when interestAmount is positive"
            }
            drafts += PostingDraft(
                accountId = interestIncomeAccountId,
                amount = -interestAmount,
                exchangeRateOverride = exchangeRateOverride,
                investmentAssetId = assetId,
            )
        }
        if (penaltyOrTaxFee != null) {
            drafts += PostingDraft(
                accountId = penaltyFeeExpenseAccountId!!,
                amount = penaltyOrTaxFee,
                exchangeRateOverride = exchangeRateOverride,
            )
        }
        drafts += PostingDraft(
            accountId = cashAccountId,
            amount = netCash,
            exchangeRateOverride = exchangeRateOverride,
        )

        return ledgerEngine.buildCustomSplit(
            meta = meta,
            postings = drafts,
            displayType = TransactionDisplayType.INVEST,
            autoHedgeMultiCurrency = false,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds a periodic interest payout transaction for [DepositInterestMode.MONTHLY_PAYOUT] deposits.
     */
    fun buildMonthlyInterestPayoutTransaction(
        ledgerEngine: LedgerEngine,
        meta: TransactionMeta,
        assetId: String,
        cashAccountId: String,
        interestIncomeAccountId: String,
        interestAmount: Money,
        taxOrFee: Money? = null,
        taxExpenseAccountId: String? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction = ledgerEngine.buildInvestmentDividend(
        meta = meta,
        cashAccountId = cashAccountId,
        dividendIncomeAccountId = interestIncomeAccountId,
        investmentAssetId = assetId,
        amount = interestAmount,
        taxOrFee = taxOrFee,
        taxExpenseAccountId = taxExpenseAccountId,
        exchangeRateOverride = exchangeRateOverride,
        rateProviderOverride = rateProviderOverride,
        accounts = accounts,
    )

    private fun computeInterest(
        principalBd: BigDecimal,
        annualRate: BigDecimal,
        days: Long,
        mode: DepositInterestMode,
        currency: Currency,
    ): Money {
        if (days <= 0L || principalBd.compareTo(BigDecimal.ZERO) == 0 || annualRate.compareTo(BigDecimal.ZERO) == 0) {
            return Money.zero(currency)
        }
        val interestBd = when (mode) {
            DepositInterestMode.SIMPLE_AT_MATURITY,
            DepositInterestMode.MONTHLY_PAYOUT,
            -> {
                principalBd
                    .multiply(annualRate)
                    .multiply(BigDecimal.valueOf(days))
                    .divide(DAYS_PER_YEAR, InvestmentDecimalMath.INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
            }
            DepositInterestMode.COMPOUND_ANNUAL -> {
                val fullYears = (days / 365L).toInt()
                val remainderDays = days % 365L
                val onePlusRate = BigDecimal.ONE.add(annualRate)
                val fullYearFactor = if (fullYears > 0) {
                    onePlusRate.pow(fullYears)
                } else {
                    BigDecimal.ONE
                }
                val partialYearFactor = if (remainderDays > 0L) {
                    val partialRate = annualRate
                        .multiply(BigDecimal.valueOf(remainderDays))
                        .divide(DAYS_PER_YEAR, InvestmentDecimalMath.INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
                    BigDecimal.ONE.add(partialRate)
                } else {
                    BigDecimal.ONE
                }
                val totalFactor = fullYearFactor
                    .multiply(partialYearFactor)
                    .subtract(BigDecimal.ONE)
                principalBd.multiply(totalFactor)
            }
        }
        return Money.fromBigDecimal(interestBd, currency)
    }
}
