package io.github.kvtttba.droplet.core.domain.ledger

import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExchangeRateTableTest {

    @Test
    fun findRate_supportsIdentityDirectInverseAndThreeCurrencyTriangulation() {
        val table = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.25", capturedAt = 100L),
            ExchangeRateSnapshot.of(Currency.HKD, Currency.CNY, "0.9295", capturedAt = 200L),
        )

        assertEquals("1", table.requireRate(Currency.CNY, Currency.CNY).rateDecimal)
        assertEquals("7.25", table.requireRate(Currency.USD, Currency.CNY).rateDecimal)
        assertEquals("0.13793103", table.requireRate(Currency.CNY, Currency.USD).rateDecimal)

        // USD -> HKD triangulated via CNY: 7.25 / 0.9295 = 7.799892415277... -> 7.79989242
        val usdToHkd = table.requireRate(Currency.USD, Currency.HKD)
        assertEquals("7.79989242", usdToHkd.rateDecimal)
        assertEquals(200L, usdToHkd.capturedAt)

        // HKD -> USD triangulated via CNY: 0.9295 / 7.25 = 0.12820689655... -> 0.1282069
        val hkdToUsd = table.requireRate(Currency.HKD, Currency.USD)
        assertEquals("0.1282069", hkdToUsd.rateDecimal)
    }

    @Test
    fun withOverride_replacesExistingAndClearsStaleInverse() {
        val initial = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.20"),
        )
        val overridden = initial.withOverride(
            ExchangeRateSnapshot.of(
                Currency.CNY,
                Currency.USD,
                "0.125",
                source = ExchangeRateSource.USER_OVERRIDE,
            ),
        )

        assertEquals("0.125", overridden.requireRate(Currency.CNY, Currency.USD).rateDecimal)
        assertEquals("8", overridden.requireRate(Currency.USD, Currency.CNY).rateDecimal)
        // Original table remains immutable
        assertEquals("7.2", initial.requireRate(Currency.USD, Currency.CNY).rateDecimal)
    }

    @Test
    fun findRate_returnsNullWhenMissingAndRequireThrows() {
        val empty = ExchangeRateTable.empty()
        assertNull(empty.findRate(Currency.USD, Currency.CNY))
    }

    @Test(expected = MissingExchangeRateException::class)
    fun requireRate_throwsMissingExchangeRateException() {
        ExchangeRateTable.empty().requireRate(Currency.HKD, Currency.USD)
    }
}
