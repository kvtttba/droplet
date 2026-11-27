package io.github.kvtttba.droplet.core.domain.ledger

import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.UnitPrice
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Uncommitted posting line before implicit Trading hedging and base-currency snapshot locking.
 */
data class PostingDraft(
    val accountId: String,
    val amount: Money,
    val baseAmountOverride: Money? = null,
    val exchangeRateOverride: ExchangeRateSnapshot? = null,
    val investmentAssetId: String? = null,
    val quantity: Quantity? = null,
    val unitPrice: UnitPrice? = null,
    val isSystemBalancing: Boolean = false,
) {
    init {
        require(accountId.isNotBlank()) { "PostingDraft accountId must not be blank" }
    }
}

data class ImpliedRatePair(
    val paidToReceived: ExchangeRateSnapshot,
    val receivedToPaid: ExchangeRateSnapshot,
)

data class LockedPostingDraft(
    val draft: PostingDraft,
    val baseCurrency: Currency,
    val baseAmountMinor: Long,
    val exchangeRateSnapshot: ExchangeRateSnapshot,
)

data class HedgedPostingsResult(
    val postings: List<LockedPostingDraft>,
    val impliedRates: Map<Pair<Currency, Currency>, ExchangeRateSnapshot>,
)

/**
 * Handles three-currency (CNY / USD / HKD) implicit `Equity:Trading:{CCY}` auto-hedging,
 * implied exchange rate derivation from actual paid/received amounts, and transaction-time
 * base-currency snapshot locking.
 */
object TradingAutoHedger {

    /**
     * Derives implied rate `fromAmount.currency -> toAmount.currency` from positive amounts.
     */
    fun inferRate(
        fromAmount: Money,
        toAmount: Money,
        capturedAt: Long = 0L,
    ): ExchangeRateSnapshot = ExchangeRateSnapshot.impliedFromAmounts(
        fromAmount = fromAmount,
        toAmount = toAmount,
        capturedAt = capturedAt,
    )

    /**
     * Derives both directions of implied exchange rate from actual paid and received amounts.
     */
    fun inferRatePair(
        paidAmount: Money,
        receivedAmount: Money,
        capturedAt: Long = 0L,
    ): ImpliedRatePair {
        require(paidAmount.isPositive) { "paidAmount must be strictly positive, got $paidAmount" }
        require(receivedAmount.isPositive) { "receivedAmount must be strictly positive, got $receivedAmount" }
        require(paidAmount.currency != receivedAmount.currency) {
            "Cross-currency rate inference requires distinct currencies, got ${paidAmount.currency}"
        }
        return ImpliedRatePair(
            paidToReceived = ExchangeRateSnapshot.impliedFromAmounts(paidAmount, receivedAmount, capturedAt),
            receivedToPaid = ExchangeRateSnapshot.impliedFromAmounts(receivedAmount, paidAmount, capturedAt),
        )
    }

    /**
     * Strips stale system balancing rows (making re-hedging idempotent), validates per-currency
     * flow invariants, inserts implicit `Equity:Trading:{CCY}` lines when multi-currency legs
     * are unbalanced, and locks `baseAmountMinor` + `exchangeRateSnapshot` for every posting.
     */
    fun hedgeAndLock(
        drafts: List<PostingDraft>,
        baseCurrency: Currency = Currency.DEFAULT,
        rateProvider: ExchangeRateProvider = ExchangeRateTable.empty(),
        capturedAt: Long = 0L,
        autoHedgeMultiCurrency: Boolean = true,
    ): HedgedPostingsResult {
        val businessDrafts = drafts.filterNot { it.isSystemBalancing }
        if (businessDrafts.size < 2) {
            throw LedgerValidationException(
                listOf(LedgerValidationError.InsufficientPostings(businessDrafts.size)),
            )
        }

        val zeroErrors = mutableListOf<LedgerValidationError>()
        for ((index, draft) in businessDrafts.withIndex()) {
            if (draft.amount.isZero) {
                zeroErrors += LedgerValidationError.ZeroAmountPosting(
                    postingId = "draft-$index",
                    accountId = draft.accountId,
                )
            }
            draft.exchangeRateOverride?.let { override ->
                require(override.fromCurrency == draft.amount.currency) {
                    "exchangeRateOverride.fromCurrency (${override.fromCurrency}) must match posting currency (${draft.amount.currency})"
                }
                require(override.toCurrency == baseCurrency) {
                    "exchangeRateOverride.toCurrency (${override.toCurrency}) must match baseCurrency ($baseCurrency)"
                }
            }
            draft.baseAmountOverride?.let { override ->
                require(override.currency == baseCurrency) {
                    "baseAmountOverride.currency (${override.currency}) must match baseCurrency ($baseCurrency)"
                }
            }
        }
        if (zeroErrors.isNotEmpty()) {
            throw LedgerValidationException(zeroErrors)
        }

        val netByCurrency = linkedMapOf<Currency, Long>()
        for (draft in businessDrafts) {
            val currency = draft.amount.currency
            val current = netByCurrency[currency] ?: 0L
            netByCurrency[currency] = try {
                Math.addExact(current, draft.amount.amountMinor)
            } catch (_: ArithmeticException) {
                throw LedgerValidationException(listOf(LedgerValidationError.CurrencySumOverflow(currency)))
            }
        }

        // Single-currency path: NEVER auto-hedge; must already sum to zero.
        if (netByCurrency.size == 1) {
            val (currency, netMinor) = netByCurrency.entries.first()
            if (netMinor != 0L) {
                throw LedgerValidationException(
                    listOf(LedgerValidationError.UnbalancedCurrency(currency, netMinor)),
                )
            }
            val locked = businessDrafts.map { draft ->
                lockSingleDraft(
                    draft = draft,
                    baseCurrency = baseCurrency,
                    rateProvider = rateProvider,
                    capturedAt = capturedAt,
                    exactBaseOverride = null,
                )
            }
            return HedgedPostingsResult(postings = locked, impliedRates = emptyMap())
        }

        // Multi-currency path
        val nonZeroImbalances = netByCurrency.filterValues { it != 0L }
        if (nonZeroImbalances.isEmpty()) {
            val locked = businessDrafts.map { draft ->
                lockSingleDraft(
                    draft = draft,
                    baseCurrency = baseCurrency,
                    rateProvider = rateProvider,
                    capturedAt = capturedAt,
                    exactBaseOverride = null,
                )
            }
            return HedgedPostingsResult(postings = locked, impliedRates = emptyMap())
        }

        if (!autoHedgeMultiCurrency) {
            val errors = nonZeroImbalances.map { (currency, imbalance) ->
                LedgerValidationError.UnbalancedCurrency(currency, imbalance)
            }
            throw LedgerValidationException(errors)
        }

        val hasPositive = nonZeroImbalances.values.any { it > 0L }
        val hasNegative = nonZeroImbalances.values.any { it < 0L }
        if (!hasPositive || !hasNegative) {
            throw LedgerValidationException(
                listOf(LedgerValidationError.InvalidMultiCurrencyFlow(nonZeroImbalances)),
            )
        }

        val impliedRates = linkedMapOf<Pair<Currency, Currency>, ExchangeRateSnapshot>()
        val exactSinglePostingBaseOverrides = mutableMapOf<Currency, Long>()
        var effectiveRateProvider: ExchangeRateProvider = rateProvider

        // Two-currency exchange: derive exact implied rate pair directly from net legs
        if (nonZeroImbalances.size == 2) {
            val posEntry = nonZeroImbalances.entries.first { it.value > 0L }
            val negEntry = nonZeroImbalances.entries.first { it.value < 0L }
            val posCcy = posEntry.key
            val posMinor = posEntry.value
            val negCcy = negEntry.key
            val negMinorAbs = try {
                Math.negateExact(negEntry.value)
            } catch (_: ArithmeticException) {
                throw LedgerValidationException(listOf(LedgerValidationError.CurrencySumOverflow(negCcy)))
            }

            val posToNeg = ExchangeRateSnapshot.impliedFromAmounts(
                fromAmount = Money(posMinor, posCcy),
                toAmount = Money(negMinorAbs, negCcy),
                capturedAt = capturedAt,
            )
            val negToPos = ExchangeRateSnapshot.impliedFromAmounts(
                fromAmount = Money(negMinorAbs, negCcy),
                toAmount = Money(posMinor, posCcy),
                capturedAt = capturedAt,
            )
            impliedRates[posCcy to negCcy] = posToNeg
            impliedRates[negCcy to posCcy] = negToPos

            effectiveRateProvider = buildTwoCurrencyRateProvider(
                delegate = rateProvider,
                baseCurrency = baseCurrency,
                posCcy = posCcy,
                posMinor = posMinor,
                negCcy = negCcy,
                negMinorAbs = negMinorAbs,
                posToNeg = posToNeg,
                negToPos = negToPos,
                capturedAt = capturedAt,
            )

            // When one exchanged currency IS baseCurrency and the counterpart currency has a single
            // un-overridden business posting, lock exact baseAmountMinor to eliminate rounding dust.
            if (negCcy == baseCurrency) {
                val posDrafts = businessDrafts.filter { it.amount.currency == posCcy }
                if (posDrafts.size == 1 &&
                    posDrafts[0].exchangeRateOverride == null &&
                    posDrafts[0].baseAmountOverride == null
                ) {
                    exactSinglePostingBaseOverrides[posCcy] = negMinorAbs
                }
            } else if (posCcy == baseCurrency) {
                val negDrafts = businessDrafts.filter { it.amount.currency == negCcy }
                if (negDrafts.size == 1 &&
                    negDrafts[0].exchangeRateOverride == null &&
                    negDrafts[0].baseAmountOverride == null
                ) {
                    exactSinglePostingBaseOverrides[negCcy] = Math.negateExact(posMinor)
                }
            }
        }

        val lockedBusiness = businessDrafts.map { draft ->
            lockSingleDraft(
                draft = draft,
                baseCurrency = baseCurrency,
                rateProvider = effectiveRateProvider,
                capturedAt = capturedAt,
                exactBaseOverride = exactSinglePostingBaseOverrides[draft.amount.currency],
            )
        }

        // Append implicit Equity:Trading:{CCY} balancing postings for every unbalanced currency
        val lockedTrading = nonZeroImbalances.map { (currency, netMinor) ->
            val tradingMinor = try {
                Math.negateExact(netMinor)
            } catch (_: ArithmeticException) {
                throw LedgerValidationException(listOf(LedgerValidationError.CurrencySumOverflow(currency)))
            }
            val currencyBusinessLocked = lockedBusiness.filter { it.draft.amount.currency == currency }
            var businessBaseSum = 0L
            for (item in currencyBusinessLocked) {
                businessBaseSum = try {
                    Math.addExact(businessBaseSum, item.baseAmountMinor)
                } catch (_: ArithmeticException) {
                    throw LedgerValidationException(listOf(LedgerValidationError.CurrencySumOverflow(baseCurrency)))
                }
            }
            val tradingBaseMinor = try {
                Math.negateExact(businessBaseSum)
            } catch (_: ArithmeticException) {
                throw LedgerValidationException(listOf(LedgerValidationError.CurrencySumOverflow(baseCurrency)))
            }

            val distinctSnapshots = currencyBusinessLocked.mapTo(linkedSetOf()) { it.exchangeRateSnapshot }
            val tradingSnapshot = if (distinctSnapshots.size == 1) {
                distinctSnapshots.first()
            } else {
                effectiveRateProvider.requireRate(currency, baseCurrency, capturedAtOverride = capturedAt)
            }

            LockedPostingDraft(
                draft = PostingDraft(
                    accountId = SystemAccounts.tradingAccountId(currency),
                    amount = Money(tradingMinor, currency),
                    isSystemBalancing = true,
                ),
                baseCurrency = baseCurrency,
                baseAmountMinor = tradingBaseMinor,
                exchangeRateSnapshot = tradingSnapshot,
            )
        }

        return HedgedPostingsResult(
            postings = lockedBusiness + lockedTrading,
            impliedRates = impliedRates,
        )
    }

    private fun lockSingleDraft(
        draft: PostingDraft,
        baseCurrency: Currency,
        rateProvider: ExchangeRateProvider,
        capturedAt: Long,
        exactBaseOverride: Long?,
    ): LockedPostingDraft {
        val postingCurrency = draft.amount.currency
        val rateSnapshot = when {
            postingCurrency == baseCurrency -> ExchangeRateSnapshot.identity(baseCurrency, capturedAt)
            draft.exchangeRateOverride != null -> draft.exchangeRateOverride
            else -> rateProvider.requireRate(
                from = postingCurrency,
                to = baseCurrency,
                capturedAtOverride = capturedAt,
            )
        }

        val baseAmountMinor = when {
            postingCurrency == baseCurrency -> {
                if (draft.baseAmountOverride != null) {
                    require(draft.baseAmountOverride.amountMinor == draft.amount.amountMinor) {
                        "Same-currency baseAmountOverride (${draft.baseAmountOverride}) must equal amount (${draft.amount})"
                    }
                }
                draft.amount.amountMinor
            }
            draft.baseAmountOverride != null -> draft.baseAmountOverride.amountMinor
            exactBaseOverride != null -> exactBaseOverride
            else -> rateSnapshot.convert(draft.amount).amountMinor
        }

        return LockedPostingDraft(
            draft = draft,
            baseCurrency = baseCurrency,
            baseAmountMinor = baseAmountMinor,
            exchangeRateSnapshot = rateSnapshot,
        )
    }

    private fun buildTwoCurrencyRateProvider(
        delegate: ExchangeRateProvider,
        baseCurrency: Currency,
        posCcy: Currency,
        posMinor: Long,
        negCcy: Currency,
        negMinorAbs: Long,
        posToNeg: ExchangeRateSnapshot,
        negToPos: ExchangeRateSnapshot,
        capturedAt: Long,
    ): ExchangeRateProvider {
        val overrides = linkedMapOf<Pair<Currency, Currency>, ExchangeRateSnapshot>()

        if (negCcy == baseCurrency) {
            overrides[posCcy to baseCurrency] = posToNeg
        } else if (posCcy == baseCurrency) {
            overrides[negCcy to baseCurrency] = negToPos
        } else {
            // Neither exchanged currency is baseCurrency (e.g. USD <-> HKD with base CNY).
            // If only one leg has a rate to baseCurrency, derive the other using the exact cross ratio.
            val posToBase = delegate.findRate(posCcy, baseCurrency)
            val negToBase = delegate.findRate(negCcy, baseCurrency)
            if (posToBase == null && negToBase != null) {
                val crossRatio = BigDecimal.valueOf(negMinorAbs, Money.SCALE)
                    .divide(BigDecimal.valueOf(posMinor, Money.SCALE), 16, RoundingMode.HALF_UP)
                val derived = crossRatio.multiply(negToBase.toBigDecimal())
                overrides[posCcy to baseCurrency] = ExchangeRateSnapshot.of(
                    fromCurrency = posCcy,
                    toCurrency = baseCurrency,
                    rate = derived,
                    source = ExchangeRateSource.IMPLIED_FROM_AMOUNTS,
                    capturedAt = capturedAt,
                )
            } else if (negToBase == null && posToBase != null) {
                val crossRatio = BigDecimal.valueOf(posMinor, Money.SCALE)
                    .divide(BigDecimal.valueOf(negMinorAbs, Money.SCALE), 16, RoundingMode.HALF_UP)
                val derived = crossRatio.multiply(posToBase.toBigDecimal())
                overrides[negCcy to baseCurrency] = ExchangeRateSnapshot.of(
                    fromCurrency = negCcy,
                    toCurrency = baseCurrency,
                    rate = derived,
                    source = ExchangeRateSource.IMPLIED_FROM_AMOUNTS,
                    capturedAt = capturedAt,
                )
            }
        }

        if (overrides.isEmpty()) return delegate
        return object : ExchangeRateProvider {
            override fun findRate(from: Currency, to: Currency): ExchangeRateSnapshot? =
                overrides[from to to] ?: delegate.findRate(from, to)
        }
    }
}
