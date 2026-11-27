package io.github.kvtttba.droplet.core.domain.ledger

import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.SystemAccounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingAutoHedgerTest {

    @Test
    fun inferRatePair_derivesBothDirectionsFromPaidAndReceivedAmounts() {
        val pair = TradingAutoHedger.inferRatePair(
            paidAmount = Money.fromDecimalString("7250.00", Currency.CNY),
            receivedAmount = Money.fromDecimalString("1000.00", Currency.USD),
            capturedAt = 1234L,
        )

        assertEquals("0.13793103", pair.paidToReceived.rateDecimal)
        assertEquals("7.25", pair.receivedToPaid.rateDecimal)
        assertEquals(ExchangeRateSource.IMPLIED_FROM_AMOUNTS, pair.receivedToPaid.source)
        assertEquals(1234L, pair.receivedToPaid.capturedAt)
    }

    @Test
    fun hedgeAndLock_insertsExactTradingLinesForCnyUsdAndEliminatesRoundingDust() {
        // Repeating decimal rate: 1000.00 CNY / 137.93 USD = 7.2500543754... -> 7.25005438
        val result = TradingAutoHedger.hedgeAndLock(
            drafts = listOf(
                PostingDraft("acc:icbc", -Money.fromDecimalString("1000.00", Currency.CNY)),
                PostingDraft("acc:us-broker", Money.fromDecimalString("137.93", Currency.USD)),
            ),
            baseCurrency = Currency.CNY,
            capturedAt = 5000L,
        )

        assertEquals(4, result.postings.size)
        val tradingRows = result.postings.filter { it.draft.isSystemBalancing }
        assertEquals(2, tradingRows.size)

        // Exact zero sum per currency
        for (ccy in listOf(Currency.CNY, Currency.USD)) {
            val sumMinor = result.postings
                .filter { it.draft.amount.currency == ccy }
                .sumOf { it.draft.amount.amountMinor }
            assertEquals(0L, sumMinor)
        }

        // Exact zero base amount sum and exact 1000.00 CNY locked on USD leg (zero 0.0001 rounding dust!)
        val usdLeg = result.postings.first { it.draft.accountId == "acc:us-broker" }
        assertEquals(Money.fromDecimalString("1000.00", Currency.CNY).amountMinor, usdLeg.baseAmountMinor)
        assertEquals("7.25005438", usdLeg.exchangeRateSnapshot.rateDecimal)
        assertEquals(ExchangeRateSource.IMPLIED_FROM_AMOUNTS, usdLeg.exchangeRateSnapshot.source)
        assertEquals(0L, result.postings.sumOf { it.baseAmountMinor })
    }

    @Test
    fun hedgeAndLock_supportsUsdToHkdWithBaseCnyByDerivingMissingRate() {
        // Only USD->CNY is known; HKD->CNY is derived cleanly from the actual 100 USD <-> 780 HKD ratio
        val rates = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.25"),
        )
        val result = TradingAutoHedger.hedgeAndLock(
            drafts = listOf(
                PostingDraft("acc:us-cash", -Money.fromDecimalString("100.00", Currency.USD)),
                PostingDraft("acc:hk-cash", Money.fromDecimalString("780.00", Currency.HKD)),
            ),
            baseCurrency = Currency.CNY,
            rateProvider = rates,
            capturedAt = 6000L,
        )

        assertEquals(4, result.postings.size)
        assertEquals("7.8", result.impliedRates[Currency.USD to Currency.HKD]?.rateDecimal)
        val hkLeg = result.postings.first { it.draft.accountId == "acc:hk-cash" }
        assertEquals("0.92948718", hkLeg.exchangeRateSnapshot.rateDecimal)
        assertEquals(Money.fromDecimalString("725.00", Currency.CNY).amountMinor, hkLeg.baseAmountMinor)
        assertEquals(0L, result.postings.sumOf { it.baseAmountMinor })
    }

    @Test
    fun hedgeAndLock_handlesThreeCurrencySplitSimultaneously() {
        val rates = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.25"),
            ExchangeRateSnapshot.of(Currency.HKD, Currency.CNY, "0.92"),
        )
        val result = TradingAutoHedger.hedgeAndLock(
            drafts = listOf(
                PostingDraft("exp:travel", Money.fromDecimalString("1000.00", Currency.USD)),
                PostingDraft("acc:cny-card", -Money.fromDecimalString("3625.00", Currency.CNY)),
                PostingDraft("acc:hkd-card", -Money.fromDecimalString("3940.22", Currency.HKD)),
            ),
            baseCurrency = Currency.CNY,
            rateProvider = rates,
            capturedAt = 7000L,
        )

        assertEquals(6, result.postings.size)
        val tradingAccounts = result.postings
            .filter { it.draft.isSystemBalancing }
            .map { it.draft.accountId }
            .toSet()
        assertEquals(
            setOf(
                SystemAccounts.tradingAccountId(Currency.USD),
                SystemAccounts.tradingAccountId(Currency.CNY),
                SystemAccounts.tradingAccountId(Currency.HKD),
            ),
            tradingAccounts,
        )
        for (ccy in Currency.entries) {
            assertEquals(
                0L,
                result.postings.filter { it.draft.amount.currency == ccy }.sumOf { it.draft.amount.amountMinor },
            )
        }
        assertEquals(0L, result.postings.sumOf { it.baseAmountMinor })
    }

    @Test
    fun hedgeAndLock_isIdempotentWhenRehedgingEditedDraftsContainingOldSystemLines() {
        val first = TradingAutoHedger.hedgeAndLock(
            drafts = listOf(
                PostingDraft("acc:icbc", -Money.fromDecimalString("7250.00", Currency.CNY)),
                PostingDraft("acc:us-broker", Money.fromDecimalString("1000.00", Currency.USD)),
            ),
            baseCurrency = Currency.CNY,
        )

        // Edit USD amount to 1050 while keeping stale Trading rows from the previous run
        val editedDrafts = first.postings.map {
            if (it.draft.accountId == "acc:us-broker") {
                it.draft.copy(amount = Money.fromDecimalString("1050.00", Currency.USD))
            } else {
                it.draft
            }
        }
        val rehedged = TradingAutoHedger.hedgeAndLock(editedDrafts, baseCurrency = Currency.CNY)

        assertEquals(4, rehedged.postings.size)
        val tradingUsd = rehedged.postings.first { it.draft.accountId == SystemAccounts.tradingAccountId(Currency.USD) }
        assertEquals(-Money.fromDecimalString("1050.00", Currency.USD), tradingUsd.draft.amount)
    }

    @Test
    fun hedgeAndLock_rejectsUnbalancedSingleCurrencyWithoutInsertingTradingLines() {
        val ex = runCatching {
            TradingAutoHedger.hedgeAndLock(
                drafts = listOf(
                    PostingDraft("exp:food", Money.fromDecimalString("50.00", Currency.CNY)),
                    PostingDraft("acc:cash", -Money.fromDecimalString("40.00", Currency.CNY)),
                ),
            )
        }.exceptionOrNull() as LedgerValidationException

        assertTrue(ex.errors.contains(LedgerValidationError.UnbalancedCurrency(Currency.CNY, 10_0000L)))
    }

    @Test
    fun hedgeAndLock_rejectsSameSignMultiCurrencyFlowAndDanglingForeignLeg() {
        // Both currencies positive -> money created out of thin air
        val sameSign = runCatching {
            TradingAutoHedger.hedgeAndLock(
                drafts = listOf(
                    PostingDraft("acc:cny", Money.fromDecimalString("100.00", Currency.CNY)),
                    PostingDraft("acc:usd", Money.fromDecimalString("100.00", Currency.USD)),
                ),
            )
        }.exceptionOrNull() as LedgerValidationException
        assertTrue(sameSign.errors.any { it is LedgerValidationError.InvalidMultiCurrencyFlow })

        // Balanced CNY + dangling +50 USD -> must fail
        val dangling = runCatching {
            TradingAutoHedger.hedgeAndLock(
                drafts = listOf(
                    PostingDraft("exp:cny", Money.fromDecimalString("100.00", Currency.CNY)),
                    PostingDraft("acc:cny", -Money.fromDecimalString("100.00", Currency.CNY)),
                    PostingDraft("acc:usd", Money.fromDecimalString("50.00", Currency.USD)),
                ),
            )
        }.exceptionOrNull() as LedgerValidationException
        assertTrue(dangling.errors.any { it is LedgerValidationError.InvalidMultiCurrencyFlow })
    }

    @Test
    fun hedgeAndLock_rejectsUnhedgedMultiCurrencyWhenAutoHedgeDisabled() {
        val ex = runCatching {
            TradingAutoHedger.hedgeAndLock(
                drafts = listOf(
                    PostingDraft("acc:cny", -Money.fromDecimalString("725.00", Currency.CNY)),
                    PostingDraft("acc:usd", Money.fromDecimalString("100.00", Currency.USD)),
                ),
                autoHedgeMultiCurrency = false,
            )
        }.exceptionOrNull() as LedgerValidationException

        assertEquals(2, ex.errors.size)
        assertTrue(ex.errors.all { it is LedgerValidationError.UnbalancedCurrency })
    }
}
