package io.github.kvtttba.droplet.core.network

import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.InvestmentAsset

internal enum class CnExchange(
    val eastMoneyMarket: String,
    val tencentPrefix: String,
    val sinaPrefix: String,
) {
    SH("1", "sh", "sh"),
    SZ("0", "sz", "sz"),
    BJ("0", "bj", "bj"),
}

internal data class CnEquitySymbol(
    val exchange: CnExchange,
    val code: String,
) {
    val eastMoneySecId: String get() = "${exchange.eastMoneyMarket}.$code"
    val tencentCode: String get() = "${exchange.tencentPrefix}$code"
    val sinaCode: String get() = "${exchange.sinaPrefix}$code"
}

internal data class HkEquitySymbol(
    val paddedFiveDigit: String,
) {
    val eastMoneySecId: String get() = "116.$paddedFiveDigit"
    val tencentCode: String get() = "hk$paddedFiveDigit"
    val yahooSymbol: String
        get() {
            val trimmed = if (paddedFiveDigit.length == 5 && paddedFiveDigit.startsWith("0")) {
                paddedFiveDigit.substring(1)
            } else {
                paddedFiveDigit
            }
            return "$trimmed.HK"
        }
}

internal data class UsEquitySymbol(
    val ticker: String,
) {
    val eastMoneyCandidateSecIds: List<String>
        get() = listOf("105.$ticker", "106.$ticker", "107.$ticker")
    val tencentCode: String get() = "us$ticker"
    val yahooSymbol: String get() = ticker.replace('.', '-')
}

internal data class GoldInstrument(
    val isDomesticCny: Boolean,
    val eastMoneySecId: String,
    val sinaCode: String,
    val quoteCurrency: Currency,
)

/**
 * Pure symbol normalizer mapping user-entered security codes across all 9 [AssetClass] categories
 * to provider-specific wire identifiers.
 */
internal object SymbolNormalizer {

    private val SIX_DIGIT_REGEX = Regex("^\\d{6}$")
    private val FIVE_OR_FEWER_DIGITS = Regex("^\\d{1,5}$")

    private val COIN_GECKO_IDS: Map<String, String> = mapOf(
        "BTC" to "bitcoin",
        "XBT" to "bitcoin",
        "ETH" to "ethereum",
        "USDT" to "tether",
        "USDC" to "usd-coin",
        "SOL" to "solana",
        "BNB" to "binancecoin",
        "XRP" to "ripple",
        "DOGE" to "dogecoin",
        "ADA" to "cardano",
        "TON" to "the-open-network",
        "TRX" to "tron",
        "AVAX" to "avalanche-2",
        "DOT" to "polkadot",
        "LINK" to "chainlink",
        "LTC" to "litecoin",
        "BCH" to "bitcoin-cash",
        "SUI" to "sui",
        "ARB" to "arbitrum",
        "OP" to "optimism",
    )

    fun normalizeCnStockOrEtf(rawSymbol: String, assetClass: AssetClass): CnEquitySymbol? {
        val upper = rawSymbol.trim().uppercase()
        if (upper.isEmpty()) return null

        val explicitExchange: CnExchange?
        val code: String
        when {
            upper.startsWith("SH") -> {
                explicitExchange = CnExchange.SH
                code = upper.removePrefix("SH").removePrefix(".")
            }
            upper.startsWith("SZ") -> {
                explicitExchange = CnExchange.SZ
                code = upper.removePrefix("SZ").removePrefix(".")
            }
            upper.startsWith("BJ") -> {
                explicitExchange = CnExchange.BJ
                code = upper.removePrefix("BJ").removePrefix(".")
            }
            upper.endsWith(".SH") || upper.endsWith(".SS") -> {
                explicitExchange = CnExchange.SH
                code = upper.substringBefore('.')
            }
            upper.endsWith(".SZ") -> {
                explicitExchange = CnExchange.SZ
                code = upper.substringBefore('.')
            }
            upper.endsWith(".BJ") -> {
                explicitExchange = CnExchange.BJ
                code = upper.substringBefore('.')
            }
            else -> {
                explicitExchange = null
                code = upper
            }
        }

        if (!SIX_DIGIT_REGEX.matches(code)) return null

        val exchange = explicitExchange ?: inferCnExchange(code, assetClass)
        return CnEquitySymbol(exchange = exchange, code = code)
    }

    private fun inferCnExchange(code: String, assetClass: AssetClass): CnExchange {
        if (assetClass == AssetClass.ETF) {
            return when {
                code.startsWith("5") -> CnExchange.SH
                else -> CnExchange.SZ
            }
        }
        return when {
            code.startsWith("6") || code.startsWith("900") || code.startsWith("5") -> CnExchange.SH
            code.startsWith("4") || code.startsWith("8") || code.startsWith("92") -> CnExchange.BJ
            else -> CnExchange.SZ
        }
    }

    fun normalizeCnFundCode(rawSymbol: String): String? {
        val cleaned = rawSymbol.trim().uppercase()
            .removePrefix("OF")
            .removePrefix("JJ")
            .removeSuffix(".OF")
        return cleaned.takeIf { SIX_DIGIT_REGEX.matches(it) }
    }

    fun hasPublicBankWealthQuoteCode(asset: InvestmentAsset): Boolean {
        if (asset.assetClass != AssetClass.BANK_WEALTH) return false
        val hint = asset.quoteProviderHint?.trim()
        if (hint != null && hint.equals(MarketNetworkContract.MANUAL_OVERRIDE_PROVIDER_ID, ignoreCase = true)) {
            return false
        }
        val code = normalizeCnFundCode(asset.symbol) ?: return false
        return code.isNotEmpty()
    }

    fun normalizeHkStock(rawSymbol: String): HkEquitySymbol? {
        val cleaned = rawSymbol.trim().uppercase()
            .removePrefix("HK.")
            .removePrefix("HK")
            .removeSuffix(".HK")
        if (!FIVE_OR_FEWER_DIGITS.matches(cleaned)) return null
        val numeric = cleaned.toIntOrNull() ?: return null
        if (numeric <= 0) return null
        return HkEquitySymbol(paddedFiveDigit = cleaned.padStart(5, '0'))
    }

    fun normalizeUsStock(rawSymbol: String): UsEquitySymbol? {
        val cleaned = rawSymbol.trim().uppercase()
            .removePrefix("US.")
            .removePrefix("US:")
            .removeSuffix(".US")
        if (cleaned.isEmpty()) return null
        val valid = cleaned.all { it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' }
        if (!valid || !cleaned.first().isLetter()) return null
        return UsEquitySymbol(ticker = cleaned)
    }

    fun normalizeGold(rawSymbol: String, assetCurrency: Currency): GoldInstrument? {
        val compact = rawSymbol.trim().uppercase()
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "")
        if (compact.isEmpty()) return null

        return when {
            compact == "AU9999" || compact == "AU99.99" || compact == "SGEAU9999" -> GoldInstrument(
                isDomesticCny = true,
                eastMoneySecId = "118.AU9999",
                sinaCode = "gds_AU9999",
                quoteCurrency = Currency.CNY,
            )
            compact == "AU9995" || compact == "AU99.95" || compact == "SGEAU9995" -> GoldInstrument(
                isDomesticCny = true,
                eastMoneySecId = "118.AU9995",
                sinaCode = "gds_AU9995",
                quoteCurrency = Currency.CNY,
            )
            compact == "AUTD" || compact == "AU(T+D)" || compact == "AUT+D" -> GoldInstrument(
                isDomesticCny = true,
                eastMoneySecId = "118.Au(T+D)",
                sinaCode = "gds_AUTD",
                quoteCurrency = Currency.CNY,
            )
            compact == "XAU" || compact == "XAUUSD" || compact == "XAU/USD" ||
                compact == "XAUUSD=X" || compact == "GOLD" -> GoldInstrument(
                isDomesticCny = false,
                eastMoneySecId = "122.XAU",
                sinaCode = "hf_XAU",
                quoteCurrency = Currency.USD,
            )
            compact == "GC=F" || compact == "GC00Y" || compact == "COMEXGOLD" -> GoldInstrument(
                isDomesticCny = false,
                eastMoneySecId = "101.GC00Y",
                sinaCode = "hf_GC",
                quoteCurrency = Currency.USD,
            )
            assetCurrency == Currency.CNY -> GoldInstrument(
                isDomesticCny = true,
                eastMoneySecId = "118.AU9999",
                sinaCode = "gds_AU9999",
                quoteCurrency = Currency.CNY,
            )
            else -> GoldInstrument(
                isDomesticCny = false,
                eastMoneySecId = "122.XAU",
                sinaCode = "hf_XAU",
                quoteCurrency = Currency.USD,
            )
        }
    }

    fun normalizeCryptoBaseSymbol(rawSymbol: String): String? {
        var upper = rawSymbol.trim().uppercase()
        if (upper.isEmpty()) return null

        // Handle explicit pair separators: BTC-USDT, BTC/USD, BTC_USDT
        for (sep in listOf('-', '/', '_')) {
            if (upper.contains(sep)) {
                upper = upper.substringBefore(sep)
                break
            }
        }
        // Handle concatenated pairs like BTCUSDT or ETHUSD (but preserve USDT / USDC itself)
        if (upper != "USDT" && upper.endsWith("USDT") && upper.length > 4) {
            upper = upper.removeSuffix("USDT")
        } else if (upper != "USDC" && upper.endsWith("USDC") && upper.length > 4) {
            upper = upper.removeSuffix("USDC")
        } else if (upper != "USD" && upper.endsWith("USD") && upper.length > 3) {
            upper = upper.removeSuffix("USD")
        }

        if (upper.isEmpty() || !upper.all { it in 'A'..'Z' || it in '0'..'9' }) {
            return null
        }
        return upper
    }

    fun coinGeckoIdFor(baseSymbol: String): String =
        COIN_GECKO_IDS[baseSymbol.uppercase()] ?: baseSymbol.lowercase()
}
