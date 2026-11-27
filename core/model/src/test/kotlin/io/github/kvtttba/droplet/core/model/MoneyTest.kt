package io.github.kvtttba.droplet.core.model

import org.junit.Assert.assertEquals
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
    }

    @Test
    fun fromDecimalString_roundsHalfUpAt4thDecimal() {
        val m = Money.fromDecimalString("12.34565", Currency.USD)
        assertEquals(123457L, m.amountMinor)
    }

    @Test
    fun arithmetic_preservesZeroSumDoubleEntryInvariant() {
        val expense = Money.fromDecimalString("38.50", Currency.CNY)
        val payment = -expense
        val total = expense + payment

        assertTrue(total.isZero)
        assertEquals(0L, total.amountMinor)
    }

    @Test(expected = IllegalArgumentException::class)
    fun plus_rejectsMixedCurrencies() {
        val cny = Money.fromDecimalString("100.00", Currency.CNY)
        val usd = Money.fromDecimalString("10.00", Currency.USD)
        cny + usd
    }
}
