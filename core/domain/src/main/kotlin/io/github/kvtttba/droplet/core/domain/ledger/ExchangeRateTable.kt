package io.github.kvtttba.droplet.core.domain.ledger

import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import java.math.BigDecimal
import java.math.RoundingMode

interface ExchangeRateProvider {
    fun findRate(from: Currency, to: Currency): ExchangeRateSnapshot?

    fun requireRate(
        from: Currency,
        to: Currency,
        capturedAtOverride: Long? = null,
    ): ExchangeRateSnapshot {
        val found = findRate(from, to) ?: throw MissingExchangeRateException(from, to)
        return if (capturedAtOverride != null && found.capturedAt != capturedAtOverride) {
            found.copy(capturedAt = capturedAtOverride)
        } else {
            found
        }
    }
}

/**
 * Immutable exchange rate table supporting direct lookup, automatic inversion,
 * and high-precision triangulation across CNY / USD / HKD.
 */
class ExchangeRateTable(
    rates: Iterable<ExchangeRateSnapshot> = emptyList(),
) : ExchangeRateProvider {

    private val directRates: Map<Pair<Currency, Currency>, ExchangeRateSnapshot> =
        buildMap {
            for (snapshot in rates) {
                if (snapshot.fromCurrency != snapshot.toCurrency) {
                    put(snapshot.fromCurrency to snapshot.toCurrency, snapshot)
                }
            }
        }

    override fun findRate(from: Currency, to: Currency): ExchangeRateSnapshot? {
        if (from == to) {
            return ExchangeRateSnapshot.identity(from)
        }
        directRates[from to to]?.let { return it }
        directRates[to to from]?.let { return it.inverse() }

        // 1-hop triangulation across the third currency (CNY / USD / HKD)
        for (bridge in Currency.entries) {
            if (bridge == from || bridge == to) continue
            val leg1 = findDirectOrInverseRaw(from, bridge) ?: continue
            val leg2 = findDirectOrInverseRaw(bridge, to) ?: continue
            val combined = leg1.rate.multiply(leg2.rate)
            val source = if (
                leg1.source == ExchangeRateSource.IMPLIED_FROM_AMOUNTS ||
                leg2.source == ExchangeRateSource.IMPLIED_FROM_AMOUNTS
            ) {
                ExchangeRateSource.IMPLIED_FROM_AMOUNTS
            } else if (
                leg1.source == ExchangeRateSource.USER_OVERRIDE ||
                leg2.source == ExchangeRateSource.USER_OVERRIDE
            ) {
                ExchangeRateSource.USER_OVERRIDE
            } else {
                ExchangeRateSource.MARKET_CACHE
            }
            val capturedAt = maxOf(leg1.capturedAt, leg2.capturedAt)
            return ExchangeRateSnapshot.of(
                fromCurrency = from,
                toCurrency = to,
                rate = combined,
                source = source,
                capturedAt = capturedAt,
            )
        }
        return null
    }

    fun withOverride(snapshot: ExchangeRateSnapshot): ExchangeRateTable {
        if (snapshot.fromCurrency == snapshot.toCurrency) return this
        val updated = directRates.toMutableMap()
        updated.remove(snapshot.toCurrency to snapshot.fromCurrency)
        updated[snapshot.fromCurrency to snapshot.toCurrency] = snapshot
        return ExchangeRateTable(updated.values)
    }

    fun withOverrides(snapshots: Iterable<ExchangeRateSnapshot>): ExchangeRateTable {
        var current = this
        for (snapshot in snapshots) {
            current = current.withOverride(snapshot)
        }
        return current
    }

    private fun findDirectOrInverseRaw(from: Currency, to: Currency): RawLeg? {
        directRates[from to to]?.let { direct ->
            return RawLeg(direct.toBigDecimal(), direct.source, direct.capturedAt)
        }
        directRates[to to from]?.let { reverse ->
            val inverted = BigDecimal.ONE.divide(
                reverse.toBigDecimal(),
                TRIANGULATION_INTERMEDIATE_SCALE,
                RoundingMode.HALF_UP,
            )
            return RawLeg(inverted, reverse.source, reverse.capturedAt)
        }
        return null
    }

    private data class RawLeg(
        val rate: BigDecimal,
        val source: ExchangeRateSource,
        val capturedAt: Long,
    )

    companion object {
        private const val TRIANGULATION_INTERMEDIATE_SCALE: Int = 16

        fun empty(): ExchangeRateTable = ExchangeRateTable()

        fun of(vararg rates: ExchangeRateSnapshot): ExchangeRateTable =
            ExchangeRateTable(rates.toList())
    }
}
