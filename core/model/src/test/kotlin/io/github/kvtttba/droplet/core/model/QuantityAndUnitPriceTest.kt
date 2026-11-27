package io.github.kvtttba.droplet.core.model

import java.math.BigDecimal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityAndUnitPriceTest {

    @Test
    fun quantity_preserves8DecimalCryptoPrecisionWithoutScientificNotation() {
        val satoshi = Quantity.of("0.00000001")
        val fromSci = Quantity.of("1E-8")

        assertEquals("0.00000001", satoshi.canonical)
        assertEquals(satoshi, fromSci)
        assertEquals("0.00000001", satoshi.toFixedScaleString())
        assertTrue(satoshi.isPositive)
    }

    @Test
    fun quantity_normalizesTrailingZerosAndNegativeZero() {
        assertEquals(Quantity.of("10"), Quantity.of("10.00000000"))
        assertEquals("10", Quantity.of("10.00000000").canonical)
        assertEquals(Quantity.ZERO, Quantity.of("-0.00000000"))
        assertEquals("0", Quantity.of("-0.000000001").canonical)
    }

    @Test
    fun quantity_roundsHalfUpAt9thDecimalAndSupportsSignedArithmetic() {
        val buy = Quantity.of("1.234567895")
        assertEquals("1.2345679", buy.canonical)

        val sell = -Quantity.of("0.2345679")
        val remaining = buy + sell
        assertEquals(Quantity.of("1"), remaining)
        assertTrue(sell.isNegative)
        assertEquals(Quantity.of("0.2345679"), sell.abs())
    }

    @Test
    fun quantity_handlesHugeValuesBeyondLongWithoutOverflow() {
        val huge = Quantity.of("100000000000000000000.12345678")
        val doubled = huge + huge
        assertEquals("200000000000000000000.24691356", doubled.canonical)
    }

    @Test(expected = IllegalArgumentException::class)
    fun quantity_directConstructorRejectsNonCanonicalString() {
        Quantity("10.00")
    }

    @Test(expected = IllegalArgumentException::class)
    fun quantity_directConstructorRejectsScientificNotation() {
        Quantity("1E-8")
    }

    @Test(expected = IllegalArgumentException::class)
    fun quantity_ofRejectsBlankString() {
        Quantity.of("  ")
    }

    @Test
    fun unitPrice_normalizes8DecimalsAndAllowsZeroForAirdrop() {
        val nav = UnitPrice.of("1.23450000")
        assertEquals("1.2345", nav.canonical)
        assertTrue(nav.isPositive)
        assertTrue(UnitPrice.ZERO.isZero)
        assertEquals(UnitPrice.of(BigDecimal("0.00001235")), UnitPrice.of("0.000012345"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unitPrice_rejectsNegativePrice() {
        UnitPrice.of("-0.01")
    }

    @Test(expected = IllegalArgumentException::class)
    fun unitPrice_directConstructorRejectsNonCanonicalString() {
        UnitPrice("220.00")
    }

    @Test
    fun serialization_roundTripsQuantityAndUnitPrice() {
        val qty = Quantity.of("-0.05432109")
        val price = UnitPrice.of("95432.125")

        assertEquals(qty, Json.decodeFromString<Quantity>(Json.encodeToString(qty)))
        assertEquals(price, Json.decodeFromString<UnitPrice>(Json.encodeToString(price)))
    }
}
