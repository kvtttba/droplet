package io.github.kvtttba.droplet.core.model

import java.math.BigDecimal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {

    @Test
    fun fromDecimalString_usesScale4FixedPointWithoutFloatDrift() {
        val a = Money.fromDecimalString("0.10", Currency.CNY)
        val b = Money.fromDecimalString("0.20", Currency.CNY)
        val sum = a + b

        assertEquals(3000L, sum.amountMinor)
        assertEquals(Money.fromDecimalString("0.30", Currency.CNY), sum)
        assertEquals("0.3000", sum.toPlainDecimalString())
    }

    @Test
    fun fromDecimalString_roundsHalfUpSymmetricallyAt4thDecimal() {
        val positive = Money.fromDecimalString("12.34565", Currency.USD)
        val negative = Money.fromDecimalString("-12.34565", Currency.USD)
        val down = Money.fromDecimalString("12.34564", Currency.USD)

        assertEquals(123457L, positive.amountMinor)
        assertEquals(-123457L, negative.amountMinor)
        assertEquals(123456L, down.amountMinor)
        assertTrue((positive + negative).isZero)
    }

    @Test
    fun arithmetic_preservesZeroSumDoubleEntryInvariant() {
        val expense = Money.fromDecimalString("38.50", Currency.CNY)
        val payment = -expense
        val total = expense + payment

        assertTrue(total.isZero)
        assertEquals(0L, total.amountMinor)
        assertEquals(expense, payment.abs())
        assertTrue(expense.isPositive)
        assertTrue(payment.isNegative)
        assertFalse(total.isPositive)
        assertFalse(total.isNegative)
    }

    @Test
    fun sumOf_accumulatesSameCurrencyExactAmounts() {
        val items = listOf(
            Money.fromDecimalString("10.0001", Currency.HKD),
            Money.fromDecimalString("20.0002", Currency.HKD),
            Money.fromDecimalString("-30.0003", Currency.HKD),
        )
        assertTrue(Money.sumOf(items, Currency.HKD).isZero)
    }

    @Test
    fun compareTo_ordersSameCurrencyAmounts() {
        val small = Money.fromDecimalString("1.00", Currency.CNY)
        val big = Money.fromDecimalString("2.00", Currency.CNY)
        assertTrue(small < big)
        assertEquals(0, small.compareTo(Money.ofMinor(10_000L, Currency.CNY)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun plus_rejectsMixedCurrencies() {
        Money.fromDecimalString("100.00", Currency.CNY) + Money.fromDecimalString("10.00", Currency.USD)
    }

    @Test(expected = IllegalArgumentException::class)
    fun minus_rejectsMixedCurrencies() {
        Money.fromDecimalString("100.00", Currency.CNY) - Money.fromDecimalString("10.00", Currency.HKD)
    }

    @Test(expected = IllegalArgumentException::class)
    fun compareTo_rejectsMixedCurrencies() {
        Money.fromDecimalString("1.00", Currency.USD).compareTo(Money.fromDecimalString("1.00", Currency.HKD))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromDecimalString_rejectsBlankInput() {
        Money.fromDecimalString("   ", Currency.CNY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromDecimalString_rejectsNonNumericInput() {
        Money.fromDecimalString("12.34abc", Currency.CNY)
    }

    @Test(expected = ArithmeticException::class)
    fun plus_detectsLongOverflow() {
        Money(Long.MAX_VALUE, Currency.CNY) + Money(1L, Currency.CNY)
    }

    @Test(expected = ArithmeticException::class)
    fun minus_detectsLongUnderflow() {
        Money(Long.MIN_VALUE, Currency.CNY) - Money(1L, Currency.CNY)
    }

    @Test(expected = ArithmeticException::class)
    fun abs_detectsLongMinValueOverflow() {
        Money(Long.MIN_VALUE, Currency.CNY).abs()
    }

    @Test(expected = ArithmeticException::class)
    fun fromBigDecimal_detectsOverflowBeyondLongMax() {
        Money.fromBigDecimal(BigDecimal("99999999999999999999"), Currency.CNY)
    }

    @Test
    fun jsonSerialization_roundTripsLosslessly() {
        val original = Money.fromDecimalString("123456.7891", Currency.USD)
        val encoded = Json.encodeToString(original)
        val decoded = Json.decodeFromString<Money>(encoded)
        assertEquals(original, decoded)
    }
}
