package io.github.kvtttba.droplet.core.domain.investment

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.MarketQuote
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Posting
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.model.UnitPrice
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId

enum class PositionValuationSource {
    /** Valued via a non-manual market quote (with FX conversion when quote currency differs). */
    MARKET_QUOTE,

    /** Valued via a manually overridden unit price / bank wealth manual valuation. */
    MANUAL_OVERRIDE,

    /** Valued via local Actual/365 fixed-deposit accrual engine. */
    FIXED_DEPOSIT_ACCRUAL,

    /** No quote available yet; holding falls back to remaining book cost basis. */
    COST_BASIS_FALLBACK,

    /** Position currently holds zero units. */
    EMPTY_POSITION,
}

/**
 * Pure ledger-replayed weighted-average cost & realized performance state for an [InvestmentAsset].
 * All amounts are in the asset's original [currency].
 */
data class PositionLedgerState(
    val assetId: String,
    val currency: Currency,
    val holdingQuantity: Quantity,
    val remainingCostBasis: Money,
    val averageCostPrice: UnitPrice,
    val realizedPnL: Money,
    val totalDividends: Money,
    val totalDividendTax: Money,
    val totalTradeFees: Money,
    val totalFees: Money,
    val cumulativeBuyCost: Money,
    val cumulativeSellProceeds: Money,
    val buyCount: Int,
    val sellCount: Int,
    val dividendCount: Int,
    val lastTransactionAt: Long? = null,
) {
    val hasHolding: Boolean
        get() = holdingQuantity.isPositive
}

/**
 * Pre-trade weighted-average cost carryover calculation for an upcoming sell transaction.
 */
data class SellCostBasisPreview(
    val assetId: String,
    val availableQuantity: Quantity,
    val sellQuantity: Quantity,
    val unitPrice: UnitPrice,
    val grossProceeds: Money,
    val costBasis: Money,
    val estimatedRealizedPnL: Money,
    val remainingQuantityAfterSell: Quantity,
    val remainingCostBasisAfterSell: Money,
    val averageCostPriceBeforeSell: UnitPrice,
    val isFullLiquidation: Boolean,
)

/**
 * Complete mark-to-market valuation for a single [InvestmentAsset], combining its replayed
 * weighted-average cost state with live/manual [MarketQuote], fixed-deposit accrual, or
 * book-cost fallback and multi-currency FX conversion into [baseCurrency].
 */
data class PortfolioPositionValuation(
    val asset: InvestmentAsset,
    val ledgerState: PositionLedgerState,
    val baseCurrency: Currency,
    val valuationSource: PositionValuationSource,
    val marketQuote: MarketQuote?,
    val fixedDepositValuation: FixedDepositValuation?,
    val bankWealthValuationMode: BankWealthValuationMode?,
    val effectiveUnitPrice: UnitPrice?,
    val effectivePrevClose: UnitPrice?,
    val quoteToAssetRate: ExchangeRateSnapshot?,
    val assetToBaseRate: ExchangeRateSnapshot,
    val marketValueOriginal: Money,
    val marketValueBase: Money,
    val costBasisBase: Money,
    val unrealizedPnLOriginal: Money,
    val unrealizedPnLBase: Money,
    val unrealizedReturnPctDecimal: String?,
    val dailyChangeOriginal: Money?,
    val dailyChangeBase: Money?,
    val dailyChangePctDecimal: String?,
    val realizedPnLBase: Money,
    val totalDividendsBase: Money,
    val totalFeesBase: Money,
    val grossTotalReturnOriginal: Money,
    val grossTotalReturnBase: Money,
    val totalReturnOriginal: Money,
    val totalReturnBase: Money,
) {
    val holdingQuantity: Quantity get() = ledgerState.holdingQuantity
    val averageCostPrice: UnitPrice get() = ledgerState.averageCostPrice
    val remainingCostBasis: Money get() = ledgerState.remainingCostBasis
    val realizedPnLOriginal: Money get() = ledgerState.realizedPnL
    val totalDividendsOriginal: Money get() = ledgerState.totalDividends
    val totalFeesOriginal: Money get() = ledgerState.totalFees
    val hasHolding: Boolean get() = ledgerState.hasHolding
}

/**
 * Pure Kotlin/JVM investment position replay and mark-to-market valuation engine.
 *
 * Accounting & precision guarantees:
 * - Zero floating-point arithmetic (`Money` 4-decimal `Long` + `BigDecimal` 8-decimal `Quantity`/`UnitPrice`).
 * - Replays only [TransactionStatus.POSTED] [TransactionDisplayType.INVEST] transactions in deterministic
 *   chronological order `(occurredAt, createdAt, id)`.
 * - Weighted-average cost method:
 *   - Buy / DCA adds trade cost to `remainingCostBasis` and shares to `holdingQuantity`.
 *   - Partial sell carries `remainingCostBasis * (sellQty / holdingQty)` (high intermediate precision,
 *     zero residual dust on full liquidation).
 *   - Full liquidation resets `holdingQuantity` and `remainingCostBasis` to exact zero so subsequent
 *     re-buys start with a clean cost basis while preserving cumulative realized P&L, dividends, and fees.
 *   - Overselling throws [OversellPositionException].
 * - Total return identity:
 *   - `grossTotalReturn = unrealizedPnL + realizedPnL + totalDividends`
 *   - `totalReturn = unrealizedPnL + realizedPnL + totalDividends - totalFees` (net of trade fees & dividend tax)
 */
object PortfolioPositionEngine {

    /**
     * Replays all posted [TransactionDisplayType.INVEST] transactions for [asset] chronologically
     * to compute exact weighted-average cost state, realized P&L, dividends, and cumulative fees.
     */
    fun replayLedgerState(
        asset: InvestmentAsset,
        transactions: Iterable<Transaction>,
        accounts: Map<String, Account> = emptyMap(),
        upToEpochMsInclusive: Long? = null,
    ): PositionLedgerState {
        val relevantTransactions = transactions
            .asSequence()
            .filter { tx ->
                tx.status == TransactionStatus.POSTED &&
                    tx.displayType == TransactionDisplayType.INVEST &&
                    (upToEpochMsInclusive == null || tx.occurredAt <= upToEpochMsInclusive) &&
                    tx.postings.any { it.investmentAssetId == asset.id }
            }
            .sortedWith(compareBy<Transaction>({ it.occurredAt }, { it.createdAt }, { it.id }))
            .toList()

        var holdingQtyBd = BigDecimal.ZERO
        var remainingCostMinor = 0L
        var realizedPnLMinor = 0L
        var totalDividendsMinor = 0L
        var totalDividendTaxMinor = 0L
        var totalTradeFeesMinor = 0L
        var cumulativeBuyCostMinor = 0L
        var cumulativeSellProceedsMinor = 0L
        var buyCount = 0
        var sellCount = 0
        var dividendCount = 0
        var lastTxAt: Long? = null

        for (tx in relevantTransactions) {
            lastTxAt = tx.occurredAt
            val businessPostings = tx.businessPostings
            val holdingPostings = businessPostings.filter {
                it.investmentAssetId == asset.id &&
                    it.accountId == asset.accountId &&
                    it.quantity != null
            }

            if (holdingPostings.isNotEmpty()) {
                for (holdingPosting in holdingPostings) {
                    require(holdingPosting.currency == asset.currency) {
                        "Investment posting ${holdingPosting.id} currency (${holdingPosting.currency}) " +
                            "does not match asset '${asset.id}' currency (${asset.currency})"
                    }
                }
                val netHoldingQtyBd = holdingPostings.fold(BigDecimal.ZERO) { acc, p ->
                    acc.add(p.quantity!!.toBigDecimal())
                }
                val netHoldingAmountMinor = holdingPostings.fold(0L) { acc, p ->
                    Math.addExact(acc, p.amountMinor)
                }

                when {
                    netHoldingQtyBd.compareTo(BigDecimal.ZERO) > 0 -> {
                        // BUY / DCA
                        require(netHoldingAmountMinor > 0L) {
                            "Buy posting for asset '${asset.id}' in tx '${tx.id}' must have positive amount"
                        }
                        val feeMinor = extractBuyFeeMinor(
                            postings = businessPostings,
                            holdingAccountId = asset.accountId,
                            assetCurrency = asset.currency,
                            accounts = accounts,
                        )
                        holdingQtyBd = holdingQtyBd.add(netHoldingQtyBd)
                        remainingCostMinor = Math.addExact(remainingCostMinor, netHoldingAmountMinor)
                        cumulativeBuyCostMinor = Math.addExact(cumulativeBuyCostMinor, netHoldingAmountMinor)
                        totalTradeFeesMinor = Math.addExact(totalTradeFeesMinor, feeMinor)
                        buyCount++
                    }

                    netHoldingQtyBd.compareTo(BigDecimal.ZERO) < 0 -> {
                        // SELL or FIXED_DEPOSIT maturity/early withdrawal settlement
                        val sellQtyBd = netHoldingQtyBd.negate()
                        if (sellQtyBd.compareTo(holdingQtyBd) > 0) {
                            throw OversellPositionException(
                                assetId = asset.id,
                                attemptedSellQuantity = Quantity.of(sellQtyBd),
                                availableQuantity = Quantity.of(holdingQtyBd),
                            )
                        }

                        val assetTaggedSidePostings = businessPostings.filter {
                            it.investmentAssetId == asset.id &&
                                it.accountId != asset.accountId &&
                                it.quantity == null &&
                                it.currency == asset.currency
                        }
                        val feeMinor = extractSellOrDividendFeeMinor(
                            postings = businessPostings,
                            assetId = asset.id,
                            holdingAccountId = asset.accountId,
                            assetCurrency = asset.currency,
                            accounts = accounts,
                        )

                        val carriedCostMinor = computeCarriedCostMinor(
                            remainingCostMinor = remainingCostMinor,
                            holdingQtyBd = holdingQtyBd,
                            sellQtyBd = sellQtyBd,
                        )

                        if (asset.assetClass == AssetClass.FIXED_DEPOSIT) {
                            // Fixed deposit settlement returns principal (zero capital gain/loss on principal)
                            // and routes any tagged credit line as interest income.
                            val withdrawnPrincipalMinor = Math.negateExact(netHoldingAmountMinor)
                            val interestMinor = assetTaggedSidePostings.fold(0L) { acc, p ->
                                if (p.amountMinor < 0L) {
                                    Math.addExact(acc, Math.negateExact(p.amountMinor))
                                } else {
                                    acc
                                }
                            }
                            cumulativeSellProceedsMinor = Math.addExact(cumulativeSellProceedsMinor, withdrawnPrincipalMinor)
                            if (interestMinor > 0L) {
                                totalDividendsMinor = Math.addExact(totalDividendsMinor, interestMinor)
                                dividendCount++
                            }
                        } else {
                            // Standard investment sell:
                            // holding = -costBasis, pnl = costBasis - proceeds -> -(holding + pnl) == proceeds
                            val pnlTaggedSumMinor = assetTaggedSidePostings.fold(0L) { acc, p ->
                                Math.addExact(acc, p.amountMinor)
                            }
                            val grossProceedsMinor = Math.negateExact(
                                Math.addExact(netHoldingAmountMinor, pnlTaggedSumMinor),
                            )
                            val sellRealizedMinor = Math.subtractExact(grossProceedsMinor, carriedCostMinor)
                            realizedPnLMinor = Math.addExact(realizedPnLMinor, sellRealizedMinor)
                            cumulativeSellProceedsMinor = Math.addExact(cumulativeSellProceedsMinor, grossProceedsMinor)
                        }

                        holdingQtyBd = if (sellQtyBd.compareTo(holdingQtyBd) == 0) {
                            BigDecimal.ZERO
                        } else {
                            holdingQtyBd.subtract(sellQtyBd)
                        }
                        remainingCostMinor = if (holdingQtyBd.compareTo(BigDecimal.ZERO) == 0) {
                            0L
                        } else {
                            Math.subtractExact(remainingCostMinor, carriedCostMinor)
                        }
                        totalTradeFeesMinor = Math.addExact(totalTradeFeesMinor, feeMinor)
                        sellCount++
                    }
                }
            } else {
                // Cash dividend or fixed-deposit periodic interest payout (no holding quantity change)
                val incomePostings = businessPostings.filter {
                    it.investmentAssetId == asset.id &&
                        it.accountId != asset.accountId &&
                        it.quantity == null &&
                        it.currency == asset.currency &&
                        it.amountMinor < 0L
                }
                if (incomePostings.isNotEmpty()) {
                    val grossDividendMinor = incomePostings.fold(0L) { acc, p ->
                        Math.addExact(acc, Math.negateExact(p.amountMinor))
                    }
                    val taxOrFeeMinor = extractSellOrDividendFeeMinor(
                        postings = businessPostings,
                        assetId = asset.id,
                        holdingAccountId = asset.accountId,
                        assetCurrency = asset.currency,
                        accounts = accounts,
                    )
                    totalDividendsMinor = Math.addExact(totalDividendsMinor, grossDividendMinor)
                    totalDividendTaxMinor = Math.addExact(totalDividendTaxMinor, taxOrFeeMinor)
                    dividendCount++
                }
            }
        }

        val holdingQty = Quantity.of(holdingQtyBd)
        val remainingCost = Money(remainingCostMinor, asset.currency)
        val averageCostPrice = if (holdingQty.isPositive && remainingCostMinor > 0L) {
            val avgBd = remainingCost.toBigDecimal().divide(
                holdingQty.toBigDecimal(),
                UnitPrice.MAX_SCALE,
                RoundingMode.HALF_UP,
            )
            UnitPrice.of(avgBd)
        } else {
            UnitPrice.ZERO
        }

        val totalFeesMinor = Math.addExact(totalTradeFeesMinor, totalDividendTaxMinor)
        return PositionLedgerState(
            assetId = asset.id,
            currency = asset.currency,
            holdingQuantity = holdingQty,
            remainingCostBasis = remainingCost,
            averageCostPrice = averageCostPrice,
            realizedPnL = Money(realizedPnLMinor, asset.currency),
            totalDividends = Money(totalDividendsMinor, asset.currency),
            totalDividendTax = Money(totalDividendTaxMinor, asset.currency),
            totalTradeFees = Money(totalTradeFeesMinor, asset.currency),
            totalFees = Money(totalFeesMinor, asset.currency),
            cumulativeBuyCost = Money(cumulativeBuyCostMinor, asset.currency),
            cumulativeSellProceeds = Money(cumulativeSellProceedsMinor, asset.currency),
            buyCount = buyCount,
            sellCount = sellCount,
            dividendCount = dividendCount,
            lastTransactionAt = lastTxAt,
        )
    }

    /**
     * Computes exact weighted-average cost basis carryover and estimated realized P&L before
     * committing a sell transaction, throwing [OversellPositionException] if [sellQuantity]
     * exceeds currently held units.
     */
    fun previewSell(
        asset: InvestmentAsset,
        transactions: Iterable<Transaction>,
        sellQuantity: Quantity,
        unitPrice: UnitPrice,
        grossProceeds: Money? = null,
        accounts: Map<String, Account> = emptyMap(),
        asOfEpochMs: Long? = null,
    ): SellCostBasisPreview {
        require(sellQuantity.isPositive) {
            "Sell quantity must be strictly positive, got ${sellQuantity.canonical}"
        }
        require(unitPrice.isPositive) {
            "Sell unitPrice must be strictly positive, got ${unitPrice.canonical}"
        }
        val state = replayLedgerState(
            asset = asset,
            transactions = transactions,
            accounts = accounts,
            upToEpochMsInclusive = asOfEpochMs,
        )
        return previewSellFromState(
            state = state,
            sellQuantity = sellQuantity,
            unitPrice = unitPrice,
            grossProceeds = grossProceeds,
        )
    }

    fun previewSellFromState(
        state: PositionLedgerState,
        sellQuantity: Quantity,
        unitPrice: UnitPrice,
        grossProceeds: Money? = null,
    ): SellCostBasisPreview {
        require(sellQuantity.isPositive) {
            "Sell quantity must be strictly positive, got ${sellQuantity.canonical}"
        }
        require(unitPrice.isPositive) {
            "Sell unitPrice must be strictly positive, got ${unitPrice.canonical}"
        }
        val availableBd = state.holdingQuantity.toBigDecimal()
        val sellBd = sellQuantity.toBigDecimal()
        if (sellBd.compareTo(availableBd) > 0) {
            throw OversellPositionException(
                assetId = state.assetId,
                attemptedSellQuantity = sellQuantity,
                availableQuantity = state.holdingQuantity,
            )
        }

        val resolvedProceeds = grossProceeds ?: Money.fromBigDecimal(
            sellBd.multiply(unitPrice.toBigDecimal()),
            state.currency,
        )
        require(resolvedProceeds.currency == state.currency) {
            "grossProceeds currency (${resolvedProceeds.currency}) must match asset currency (${state.currency})"
        }
        require(resolvedProceeds.isPositive) {
            "grossProceeds must be strictly positive, got $resolvedProceeds"
        }

        val isFullLiquidation = sellBd.compareTo(availableBd) == 0
        val carriedCostMinor = computeCarriedCostMinor(
            remainingCostMinor = state.remainingCostBasis.amountMinor,
            holdingQtyBd = availableBd,
            sellQtyBd = sellBd,
        )
        val carriedCost = Money(carriedCostMinor, state.currency)
        val remainingQty = if (isFullLiquidation) {
            Quantity.ZERO
        } else {
            Quantity.of(availableBd.subtract(sellBd))
        }
        val remainingCost = if (isFullLiquidation) {
            Money.zero(state.currency)
        } else {
            Money(Math.subtractExact(state.remainingCostBasis.amountMinor, carriedCostMinor), state.currency)
        }

        return SellCostBasisPreview(
            assetId = state.assetId,
            availableQuantity = state.holdingQuantity,
            sellQuantity = sellQuantity,
            unitPrice = unitPrice,
            grossProceeds = resolvedProceeds,
            costBasis = carriedCost,
            estimatedRealizedPnL = resolvedProceeds - carriedCost,
            remainingQuantityAfterSell = remainingQty,
            remainingCostBasisAfterSell = remainingCost,
            averageCostPriceBeforeSell = state.averageCostPrice,
            isFullLiquidation = isFullLiquidation,
        )
    }

    /**
     * Evaluates a single [asset] end-to-end: replays posted ledger transactions, applies
     * [MarketQuote] / [FixedDepositCalculator] / [BankWealthValuationHelper] valuation,
     * and converts all metrics to [baseCurrency] via [exchangeRateProvider].
     */
    fun evaluatePosition(
        asset: InvestmentAsset,
        transactions: Iterable<Transaction>,
        marketQuote: MarketQuote? = null,
        baseCurrency: Currency = Currency.DEFAULT,
        exchangeRateProvider: ExchangeRateProvider = ExchangeRateTable.empty(),
        accounts: Map<String, Account> = emptyMap(),
        asOfEpochMs: Long = 0L,
        zoneId: ZoneId = FixedDepositCalculator.DEFAULT_ZONE_ID,
    ): PortfolioPositionValuation {
        val ledgerState = replayLedgerState(
            asset = asset,
            transactions = transactions,
            accounts = accounts,
        )
        return evaluatePositionFromState(
            asset = asset,
            ledgerState = ledgerState,
            marketQuote = marketQuote,
            baseCurrency = baseCurrency,
            exchangeRateProvider = exchangeRateProvider,
            asOfEpochMs = asOfEpochMs,
            zoneId = zoneId,
        )
    }

    fun evaluatePositionFromState(
        asset: InvestmentAsset,
        ledgerState: PositionLedgerState,
        marketQuote: MarketQuote? = null,
        baseCurrency: Currency = Currency.DEFAULT,
        exchangeRateProvider: ExchangeRateProvider = ExchangeRateTable.empty(),
        asOfEpochMs: Long = 0L,
        zoneId: ZoneId = FixedDepositCalculator.DEFAULT_ZONE_ID,
    ): PortfolioPositionValuation {
        val assetToBaseRate = exchangeRateProvider.requireRate(asset.currency, baseCurrency)
        val bankWealthMode = if (asset.assetClass == AssetClass.BANK_WEALTH) {
            BankWealthValuationHelper.resolveValuationMode(asset, marketQuote)
        } else {
            null
        }

        if (asset.assetClass == AssetClass.FIXED_DEPOSIT) {
            val depositVal = if (
                asset.depositRateAnnualDecimal != null &&
                asset.depositStartAt != null &&
                asset.depositMaturityAt != null &&
                asset.depositInterestMode != null
            ) {
                FixedDepositCalculator.calculate(
                    asset = asset,
                    principal = ledgerState.remainingCostBasis,
                    asOfEpochMs = asOfEpochMs,
                    alreadyPaidInterest = if (asset.depositInterestMode == io.github.kvtttba.droplet.core.model.DepositInterestMode.MONTHLY_PAYOUT) {
                        ledgerState.totalDividends
                    } else {
                        Money.zero(asset.currency)
                    },
                    zoneId = zoneId,
                )
            } else {
                null
            }

            val valuationSource = when {
                !ledgerState.hasHolding -> PositionValuationSource.EMPTY_POSITION
                depositVal != null -> PositionValuationSource.FIXED_DEPOSIT_ACCRUAL
                else -> PositionValuationSource.COST_BASIS_FALLBACK
            }
            val marketValueOriginal = when {
                !ledgerState.hasHolding -> Money.zero(asset.currency)
                depositVal != null -> depositVal.currentTotalValue
                else -> ledgerState.remainingCostBasis
            }
            val dailyChangeOriginal = when {
                !ledgerState.hasHolding -> Money.zero(asset.currency)
                depositVal != null -> depositVal.dailyAccrual
                else -> null
            }
            val dailyChangePct = if (
                dailyChangeOriginal != null &&
                ledgerState.remainingCostBasis.isPositive &&
                depositVal != null
            ) {
                InvestmentDecimalMath.percentageOrNull(
                    numerator = dailyChangeOriginal.toBigDecimal(),
                    denominator = ledgerState.remainingCostBasis.toBigDecimal(),
                )
            } else {
                null
            }

            return assembleValuation(
                asset = asset,
                ledgerState = ledgerState,
                baseCurrency = baseCurrency,
                valuationSource = valuationSource,
                marketQuote = marketQuote,
                fixedDepositValuation = depositVal,
                bankWealthValuationMode = null,
                effectiveUnitPrice = if (ledgerState.hasHolding) UnitPrice.of("1") else null,
                effectivePrevClose = null,
                quoteToAssetRate = null,
                assetToBaseRate = assetToBaseRate,
                marketValueOriginal = marketValueOriginal,
                dailyChangeOriginal = dailyChangeOriginal,
                dailyChangePctDecimal = dailyChangePct,
            )
        }

        // Standard market-quoted & bank-wealth assets
        if (marketQuote != null) {
            require(marketQuote.assetId == asset.id) {
                "MarketQuote assetId '${marketQuote.assetId}' does not match asset '${asset.id}'"
            }
            val quoteToAssetRate = exchangeRateProvider.requireRate(marketQuote.priceCurrency, asset.currency)
            val rateBd = quoteToAssetRate.toBigDecimal()
            val effectiveLatestPriceBd = marketQuote.latestPrice.toBigDecimal().multiply(rateBd)
            val effectiveLatestPrice = UnitPrice.of(effectiveLatestPriceBd)
            val effectivePrevCloseBd = marketQuote.prevClose?.toBigDecimal()?.multiply(rateBd)
            val effectivePrevClose = effectivePrevCloseBd?.let { UnitPrice.of(it) }

            val valuationSource = when {
                !ledgerState.hasHolding -> PositionValuationSource.EMPTY_POSITION
                marketQuote.isManualOverride -> PositionValuationSource.MANUAL_OVERRIDE
                else -> PositionValuationSource.MARKET_QUOTE
            }

            val marketValueOriginal = if (ledgerState.hasHolding) {
                Money.fromBigDecimal(
                    ledgerState.holdingQuantity.toBigDecimal().multiply(effectiveLatestPriceBd),
                    asset.currency,
                )
            } else {
                Money.zero(asset.currency)
            }

            val resolvedDailyPct = marketQuote.dailyChangePctDecimal ?: effectivePrevCloseBd?.let { prevBd ->
                if (prevBd.compareTo(BigDecimal.ZERO) > 0) {
                    InvestmentDecimalMath.percentageOrNull(
                        numerator = effectiveLatestPriceBd.subtract(prevBd),
                        denominator = prevBd,
                    )
                } else {
                    null
                }
            }

            val dailyChangeOriginal = when {
                !ledgerState.hasHolding -> Money.zero(asset.currency)
                effectivePrevCloseBd != null && effectivePrevCloseBd.compareTo(BigDecimal.ZERO) > 0 -> {
                    val prevValue = Money.fromBigDecimal(
                        ledgerState.holdingQuantity.toBigDecimal().multiply(effectivePrevCloseBd),
                        asset.currency,
                    )
                    marketValueOriginal - prevValue
                }
                resolvedDailyPct != null -> {
                    val pctRatio = BigDecimal(resolvedDailyPct).divide(
                        InvestmentDecimalMath.HUNDRED,
                        InvestmentDecimalMath.INTERMEDIATE_SCALE,
                        RoundingMode.HALF_UP,
                    )
                    val factor = BigDecimal.ONE.add(pctRatio)
                    if (factor.compareTo(BigDecimal.ZERO) > 0) {
                        val prevValue = Money.fromBigDecimal(
                            marketValueOriginal.toBigDecimal().divide(
                                factor,
                                InvestmentDecimalMath.INTERMEDIATE_SCALE,
                                RoundingMode.HALF_UP,
                            ),
                            asset.currency,
                        )
                        marketValueOriginal - prevValue
                    } else {
                        null
                    }
                }
                else -> null
            }

            return assembleValuation(
                asset = asset,
                ledgerState = ledgerState,
                baseCurrency = baseCurrency,
                valuationSource = valuationSource,
                marketQuote = marketQuote,
                fixedDepositValuation = null,
                bankWealthValuationMode = bankWealthMode,
                effectiveUnitPrice = effectiveLatestPrice,
                effectivePrevClose = effectivePrevClose,
                quoteToAssetRate = quoteToAssetRate,
                assetToBaseRate = assetToBaseRate,
                marketValueOriginal = marketValueOriginal,
                dailyChangeOriginal = dailyChangeOriginal,
                dailyChangePctDecimal = resolvedDailyPct,
            )
        }

        // No market quote cached yet -> fall back to remaining book cost basis
        val valuationSource = if (ledgerState.hasHolding) {
            PositionValuationSource.COST_BASIS_FALLBACK
        } else {
            PositionValuationSource.EMPTY_POSITION
        }
        val marketValueOriginal = if (ledgerState.hasHolding) {
            ledgerState.remainingCostBasis
        } else {
            Money.zero(asset.currency)
        }

        return assembleValuation(
            asset = asset,
            ledgerState = ledgerState,
            baseCurrency = baseCurrency,
            valuationSource = valuationSource,
            marketQuote = null,
            fixedDepositValuation = null,
            bankWealthValuationMode = bankWealthMode,
            effectiveUnitPrice = if (ledgerState.hasHolding) ledgerState.averageCostPrice else null,
            effectivePrevClose = null,
            quoteToAssetRate = null,
            assetToBaseRate = assetToBaseRate,
            marketValueOriginal = marketValueOriginal,
            dailyChangeOriginal = null,
            dailyChangePctDecimal = null,
        )
    }

    /**
     * Batch-evaluates a collection of [assets] against the full ledger transaction stream.
     */
    fun evaluatePositions(
        assets: Iterable<InvestmentAsset>,
        transactions: Iterable<Transaction>,
        quotesByAssetId: Map<String, MarketQuote> = emptyMap(),
        baseCurrency: Currency = Currency.DEFAULT,
        exchangeRateProvider: ExchangeRateProvider = ExchangeRateTable.empty(),
        accounts: Map<String, Account> = emptyMap(),
        asOfEpochMs: Long = 0L,
        zoneId: ZoneId = FixedDepositCalculator.DEFAULT_ZONE_ID,
    ): List<PortfolioPositionValuation> {
        val postedInvestTxsByAssetId = hashMapOf<String, MutableList<Transaction>>()
        for (tx in transactions) {
            if (tx.status != TransactionStatus.POSTED || tx.displayType != TransactionDisplayType.INVEST) {
                continue
            }
            val assetIds = tx.postings.mapNotNullTo(linkedSetOf()) { it.investmentAssetId }
            for (assetId in assetIds) {
                postedInvestTxsByAssetId.getOrPut(assetId) { mutableListOf() }.add(tx)
            }
        }
        return assets.map { asset ->
            evaluatePosition(
                asset = asset,
                transactions = postedInvestTxsByAssetId[asset.id].orEmpty(),
                marketQuote = quotesByAssetId[asset.id],
                baseCurrency = baseCurrency,
                exchangeRateProvider = exchangeRateProvider,
                accounts = accounts,
                asOfEpochMs = asOfEpochMs,
                zoneId = zoneId,
            )
        }
    }

    private fun assembleValuation(
        asset: InvestmentAsset,
        ledgerState: PositionLedgerState,
        baseCurrency: Currency,
        valuationSource: PositionValuationSource,
        marketQuote: MarketQuote?,
        fixedDepositValuation: FixedDepositValuation?,
        bankWealthValuationMode: BankWealthValuationMode?,
        effectiveUnitPrice: UnitPrice?,
        effectivePrevClose: UnitPrice?,
        quoteToAssetRate: ExchangeRateSnapshot?,
        assetToBaseRate: ExchangeRateSnapshot,
        marketValueOriginal: Money,
        dailyChangeOriginal: Money?,
        dailyChangePctDecimal: String?,
    ): PortfolioPositionValuation {
        val unrealizedPnLOriginal = marketValueOriginal - ledgerState.remainingCostBasis
        val unrealizedReturnPct = if (ledgerState.remainingCostBasis.isPositive) {
            InvestmentDecimalMath.percentageOrNull(
                numerator = unrealizedPnLOriginal.toBigDecimal(),
                denominator = ledgerState.remainingCostBasis.toBigDecimal(),
            )
        } else {
            null
        }

        val grossTotalReturnOriginal = unrealizedPnLOriginal +
            ledgerState.realizedPnL +
            ledgerState.totalDividends
        val totalReturnOriginal = grossTotalReturnOriginal - ledgerState.totalFees

        val marketValueBase = assetToBaseRate.convert(marketValueOriginal)
        val costBasisBase = assetToBaseRate.convert(ledgerState.remainingCostBasis)
        val unrealizedPnLBase = assetToBaseRate.convert(unrealizedPnLOriginal)
        val dailyChangeBase = dailyChangeOriginal?.let { assetToBaseRate.convert(it) }
        val realizedPnLBase = assetToBaseRate.convert(ledgerState.realizedPnL)
        val totalDividendsBase = assetToBaseRate.convert(ledgerState.totalDividends)
        val totalFeesBase = assetToBaseRate.convert(ledgerState.totalFees)
        val grossTotalReturnBase = assetToBaseRate.convert(grossTotalReturnOriginal)
        val totalReturnBase = assetToBaseRate.convert(totalReturnOriginal)

        return PortfolioPositionValuation(
            asset = asset,
            ledgerState = ledgerState,
            baseCurrency = baseCurrency,
            valuationSource = valuationSource,
            marketQuote = marketQuote,
            fixedDepositValuation = fixedDepositValuation,
            bankWealthValuationMode = bankWealthValuationMode,
            effectiveUnitPrice = effectiveUnitPrice,
            effectivePrevClose = effectivePrevClose,
            quoteToAssetRate = quoteToAssetRate,
            assetToBaseRate = assetToBaseRate,
            marketValueOriginal = marketValueOriginal,
            marketValueBase = marketValueBase,
            costBasisBase = costBasisBase,
            unrealizedPnLOriginal = unrealizedPnLOriginal,
            unrealizedPnLBase = unrealizedPnLBase,
            unrealizedReturnPctDecimal = unrealizedReturnPct,
            dailyChangeOriginal = dailyChangeOriginal,
            dailyChangeBase = dailyChangeBase,
            dailyChangePctDecimal = dailyChangePctDecimal,
            realizedPnLBase = realizedPnLBase,
            totalDividendsBase = totalDividendsBase,
            totalFeesBase = totalFeesBase,
            grossTotalReturnOriginal = grossTotalReturnOriginal,
            grossTotalReturnBase = grossTotalReturnBase,
            totalReturnOriginal = totalReturnOriginal,
            totalReturnBase = totalReturnBase,
        )
    }

    private fun computeCarriedCostMinor(
        remainingCostMinor: Long,
        holdingQtyBd: BigDecimal,
        sellQtyBd: BigDecimal,
    ): Long {
        if (remainingCostMinor <= 0L) return 0L
        if (sellQtyBd.compareTo(holdingQtyBd) == 0) {
            return remainingCostMinor
        }
        val proportionalCostBd = BigDecimal.valueOf(remainingCostMinor, Money.SCALE)
            .multiply(sellQtyBd)
            .divide(holdingQtyBd, InvestmentDecimalMath.INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
        val rawMinor = Money.fromBigDecimal(proportionalCostBd, Currency.CNY).amountMinor
        return when {
            rawMinor > remainingCostMinor -> remainingCostMinor
            rawMinor <= 0L && remainingCostMinor > 0L -> 1L
            else -> rawMinor
        }
    }

    private fun extractBuyFeeMinor(
        postings: List<Posting>,
        holdingAccountId: String,
        assetCurrency: Currency,
        accounts: Map<String, Account>,
    ): Long {
        var feeMinor = 0L
        for (posting in postings) {
            if (posting.accountId == holdingAccountId) continue
            if (posting.currency != assetCurrency || posting.amountMinor <= 0L) continue
            val account = accounts[posting.accountId]
            if (account != null && account.type != AccountType.EXPENSE) continue
            feeMinor = Math.addExact(feeMinor, posting.amountMinor)
        }
        return feeMinor
    }

    private fun extractSellOrDividendFeeMinor(
        postings: List<Posting>,
        assetId: String,
        holdingAccountId: String,
        assetCurrency: Currency,
        accounts: Map<String, Account>,
    ): Long {
        val candidates = postings.filter {
            it.accountId != holdingAccountId &&
                it.investmentAssetId != assetId &&
                it.currency == assetCurrency &&
                it.amountMinor > 0L
        }
        if (candidates.isEmpty()) return 0L

        if (accounts.isNotEmpty()) {
            var feeMinor = 0L
            for (posting in candidates) {
                if (accounts[posting.accountId]?.type == AccountType.EXPENSE) {
                    feeMinor = Math.addExact(feeMinor, posting.amountMinor)
                }
            }
            return feeMinor
        }

        // Fallback when caller did not pass an Account registry:
        // LedgerEngine.buildInvestmentSell & buildInvestmentDividend always emit [fee/tax, cash]
        // when a fee/tax is present alongside a cash inflow.
        return if (candidates.size >= 2) {
            candidates.dropLast(1).fold(0L) { acc, p -> Math.addExact(acc, p.amountMinor) }
        } else {
            0L
        }
    }
}
