package io.github.kvtttba.droplet.core.network

import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared strict-decimal parsing and sanitization utilities for `:core:network`.
 * Strictly forbids Float/Double conversions for all prices, NAVs, FX rates, and percentages.
 */
internal object NetworkDecimalParser {

    val lenientJson: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val HUNDRED: BigDecimal = BigDecimal("100")

    fun parseDecimalOrNull(raw: String?): BigDecimal? {
        if (raw == null) return null
        val cleaned = raw.trim().replace(",", "").removeSuffix("%").trim()
        if (cleaned.isEmpty() || cleaned == "-" || cleaned == "--" || cleaned.equals("null", ignoreCase = true)) {
            return null
        }
        if (cleaned.equals("NaN", ignoreCase = true) || cleaned.contains("Infinity", ignoreCase = true)) {
            return null
        }
        return try {
            BigDecimal(cleaned)
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun parsePositiveDecimalOrNull(raw: String?): BigDecimal? {
        val parsed = parseDecimalOrNull(raw) ?: return null
        return parsed.takeIf { it.compareTo(BigDecimal.ZERO) > 0 }
    }

    fun JsonElement?.rawPrimitiveContentOrNull(): String? {
        if (this == null || this is JsonNull) return null
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.content
    }

    fun JsonElement?.positiveDecimalOrNull(): BigDecimal? =
        parsePositiveDecimalOrNull(rawPrimitiveContentOrNull())

    fun JsonElement?.signedDecimalOrNull(): BigDecimal? =
        parseDecimalOrNull(rawPrimitiveContentOrNull())

    fun canonicalDecimal(
        value: BigDecimal,
        maxScale: Int = ExchangeRateSnapshot.SCALE,
        roundingMode: RoundingMode = RoundingMode.HALF_UP,
    ): String {
        val scaled = if (value.scale() > maxScale) {
            value.setScale(maxScale, roundingMode)
        } else {
            value
        }
        if (scaled.compareTo(BigDecimal.ZERO) == 0) {
            return "0"
        }
        return scaled.stripTrailingZeros().toPlainString()
    }

    fun computeDailyChangePctDecimal(
        latest: BigDecimal,
        prevClose: BigDecimal?,
        scale: Int = 4,
    ): String? {
        if (prevClose == null || prevClose.compareTo(BigDecimal.ZERO) <= 0) {
            return null
        }
        val pct = latest.subtract(prevClose)
            .multiply(HUNDRED)
            .divide(prevClose, scale, RoundingMode.HALF_UP)
        return canonicalDecimal(pct, maxScale = ExchangeRateSnapshot.SCALE)
    }

    fun sanitizeErrorMessage(error: Throwable): String {
        val simpleName = error::class.simpleName ?: "Exception"
        val rawMsg = error.message?.trim().orEmpty()
        if (rawMsg.isEmpty()) return simpleName
        // Avoid accidentally echoing URLs with credentials or long payloads
        val compact = rawMsg.lineSequence().firstOrNull().orEmpty().take(160)
        return "$simpleName: $compact"
    }
}
