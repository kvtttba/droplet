package io.github.kvtttba.droplet.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ExchangeRateSnapshotTest {

    @Test
    fun convert_appliesRateWithHalfUpScale4Precision() {
        val usdToCny = ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.2535")
        val converted = usdToCny.convert(Money.fromDecimalString("10.00", Currency.USD))
        assertEquals(Money.fromDecimalString("72.5350", Currency.CNY), converted)

        val negativeConverted = usdToCny.convert(Money.fromDecimalString("-10.00", Currency.USD))
        assertEquals(-converted, negativeConverted)
    }

    @Test
    fun identity_returnsExactSameAmountWithoutDrift() {
        val id = ExchangeRateSnapshot.identity(Currency.CNY)
        val maxNear = Money(Long.MAX_VALUE - 1L, Currency.CNY)
        assertEquals(maxNear, id.convert(maxNear))
        assertEquals("1", id.rateDecimal)
        assertEquals(ExchangeRateSource.IDENTITY, id.source)
    }

    @Test
    fun inverse_computes8DecimalReciprocal() {
        val usdToCny = ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.25")
        val cnyToUsd = usdToCny.inverse()

        assertEquals(Currency.CNY, cnyToUsd.fromCurrency)
        assertEquals(Currency.USD, cnyToUsd.toCurrency)
        assertEquals("0.13793103", cnyToUsd.rateDecimal)
    }

    @Test
    fun impliedFromAmounts_derivesExactAndRepeatingRates() {
        val exact = ExchangeRateSnapshot.impliedFromAmounts(
            fromAmount = Money.fromDecimalString("1000.00", Currency.USD),
            toAmount = Money.fromDecimalString("7250.00", Currency.CNY),
            capturedAt = 42L,
        )
        assertEquals("7.25", exact.rateDecimal)
        assertEquals(ExchangeRateSource.IMPLIED_FROM_AMOUNTS, exact.source)
        assertEquals(42L, exact.capturedAt)

        val repeating = ExchangeRateSnapshot.impliedFromAmounts(
            fromAmount = Money.fromDecimalString("137.93", Currency.USD),
            toAmount = Money.fromDecimalString("1000.00", Currency.CNY),
        )
        assertEquals("7.25005438", repeating.rateDecimal)
    }

    @Test(expected = IllegalArgumentException::class)
    fun of_rejectsZeroRate() {
        ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun of_rejectsNegativeRate() {
        ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "-7.25")
    }

    @Test(expected = IllegalArgumentException::class)
    fun of_rejectsNonOneSameCurrencyRate() {
        ExchangeRateSnapshot.of(Currency.CNY, Currency.CNY, "1.01")
    }

    @Test(expected = IllegalArgumentException::class)
    fun impliedFromAmounts_rejectsZeroSourceAmount() {
        ExchangeRateSnapshot.impliedFromAmounts(
            fromAmount = Money.zero(Currency.USD),
            toAmount = Money.fromDecimalString("100.00", Currency.CNY),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun impliedFromAmounts_rejectsNegativeTargetAmount() {
        ExchangeRateSnapshot.impliedFromAmounts(
            fromAmount = Money.fromDecimalString("10.00", Currency.USD),
            toAmount = Money.fromDecimalString("-72.50", Currency.CNY),
        )
    }

    @Test(expected = ArithmeticException::class)
    fun convert_detectsLargeAmountOverflow() {
        val rate = ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.25")
        rate.convert(Money(Long.MAX_VALUE / 2, Currency.USD))
    }

    @Test(expected = IllegalArgumentException::class)
    fun convert_rejectsWrongInputCurrency() {
        val usdToCny = ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.25")
        usdToCny.convert(Money.fromDecimalString("10.00", Currency.HKD))
    }
}
