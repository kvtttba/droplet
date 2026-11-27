package io.github.kvtttba.droplet.core.network

/**
 * Sanitized error record captured when an individual provider attempt fails before falling back
 * to the next provider in the chain.
 */
data class ProviderAttemptError(
    val providerId: String,
    val reason: String,
)

/**
 * Configurable public base URLs for free market quote & FX providers.
 * All defaults point to official free public endpoints (zero API keys, zero personal URLs);
 * tests override these with local `MockWebServer` URLs so unit tests never access the internet.
 */
data class MarketProviderEndpoints(
    val eastMoneyPushBaseUrl: String = DEFAULT_EASTMONEY_PUSH_BASE_URL,
    val frankfurterBaseUrl: String = DEFAULT_FRANKFURTER_BASE_URL,
    val openExchangeRateBaseUrl: String = DEFAULT_OPEN_ER_API_BASE_URL,
    val tencentQuoteBaseUrl: String = DEFAULT_TENCENT_QUOTE_BASE_URL,
    val sinaQuoteBaseUrl: String = DEFAULT_SINA_QUOTE_BASE_URL,
    val tiantianFundBaseUrl: String = DEFAULT_TIANTIAN_FUND_BASE_URL,
    val eastMoneyFundBaseUrl: String = DEFAULT_EASTMONEY_FUND_BASE_URL,
    val yahooFinanceBaseUrl: String = DEFAULT_YAHOO_FINANCE_BASE_URL,
    val spotXauBaseUrl: String = DEFAULT_SPOT_XAU_BASE_URL,
    val okxBaseUrl: String = DEFAULT_OKX_BASE_URL,
    val binanceBaseUrl: String = DEFAULT_BINANCE_BASE_URL,
    val coinGeckoBaseUrl: String = DEFAULT_COINGECKO_BASE_URL,
) {
    companion object {
        const val DEFAULT_EASTMONEY_PUSH_BASE_URL: String = "https://push2.eastmoney.com"
        const val DEFAULT_FRANKFURTER_BASE_URL: String = "https://api.frankfurter.dev"
        const val DEFAULT_OPEN_ER_API_BASE_URL: String = "https://open.er-api.com"
        const val DEFAULT_TENCENT_QUOTE_BASE_URL: String = "https://qt.gtimg.cn"
        const val DEFAULT_SINA_QUOTE_BASE_URL: String = "https://hq.sinajs.cn"
        const val DEFAULT_TIANTIAN_FUND_BASE_URL: String = "https://fundgz.1234567.com.cn"
        const val DEFAULT_EASTMONEY_FUND_BASE_URL: String = "https://api.fund.eastmoney.com"
        const val DEFAULT_YAHOO_FINANCE_BASE_URL: String = "https://query1.finance.yahoo.com"
        const val DEFAULT_SPOT_XAU_BASE_URL: String = "https://api.gold-api.com"
        const val DEFAULT_OKX_BASE_URL: String = "https://www.okx.com"
        const val DEFAULT_BINANCE_BASE_URL: String = "https://api.binance.com"
        const val DEFAULT_COINGECKO_BASE_URL: String = "https://api.coingecko.com"

        val DEFAULT: MarketProviderEndpoints = MarketProviderEndpoints()
    }
}
