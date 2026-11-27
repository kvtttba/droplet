package io.github.kvtttba.droplet.core.network

import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolNormalizerTest {

    @Test
    fun normalizeCnStockAndEtf_handlesExchangesAndPrefixes() {
        val moutai = SymbolNormalizer.normalizeCnStockOrEtf("600519", AssetClass.CN_STOCK)!!
        assertEquals("1.600519", moutai.eastMoneySecId)
        assertEquals("sh600519", moutai.tencentCode)
        assertEquals("sh600519", moutai.sinaCode)

        val pingAn = SymbolNormalizer.normalizeCnStockOrEtf("sz000001", AssetClass.CN_STOCK)!!
        assertEquals("0.000001", pingAn.eastMoneySecId)
        assertEquals("sz000001", pingAn.tencentCode)

        val bse = SymbolNormalizer.normalizeCnStockOrEtf("830799", AssetClass.CN_STOCK)!!
        assertEquals("0.830799", bse.eastMoneySecId)
        assertEquals("bj830799", bse.tencentCode)
        assertEquals("bj830799", bse.sinaCode)

        val shEtf = SymbolNormalizer.normalizeCnStockOrEtf("510300.SH", AssetClass.ETF)!!
        assertEquals("1.510300", shEtf.eastMoneySecId)
        val szEtf = SymbolNormalizer.normalizeCnStockOrEtf("159915", AssetClass.ETF)!!
        assertEquals("0.159915", szEtf.eastMoneySecId)

        assertNull(SymbolNormalizer.normalizeCnStockOrEtf("AAPL", AssetClass.CN_STOCK))
    }

    @Test
    fun normalizeHkAndUsStocks_mapsToProviderFormats() {
        val tencent = SymbolNormalizer.normalizeHkStock("700")!!
        assertEquals("00700", tencent.paddedFiveDigit)
        assertEquals("116.00700", tencent.eastMoneySecId)
        assertEquals("hk00700", tencent.tencentCode)
        assertEquals("0700.HK", tencent.yahooSymbol)

        val alibaba = SymbolNormalizer.normalizeHkStock("09988.HK")!!
        assertEquals("9988.HK", alibaba.yahooSymbol)

        val brk = SymbolNormalizer.normalizeUsStock("US.brk.b")!!
        assertEquals("BRK.B", brk.ticker)
        assertEquals("usBRK.B", brk.tencentCode)
        assertEquals("BRK-B", brk.yahooSymbol)
        assertEquals(listOf("105.BRK.B", "106.BRK.B", "107.BRK.B"), brk.eastMoneyCandidateSecIds)
    }

    @Test
    fun normalizeGoldCryptoAndBankWealth_respectsCurrencyAndPublicCodeRules() {
        val sge = SymbolNormalizer.normalizeGold("AU99.99", Currency.CNY)!!
        assertTrue(sge.isDomesticCny)
        assertEquals("118.AU9999", sge.eastMoneySecId)
        assertEquals("gds_AU9999", sge.sinaCode)
        assertEquals(Currency.CNY, sge.quoteCurrency)

        val xau = SymbolNormalizer.normalizeGold("XAUUSD", Currency.USD)!!
        assertFalse(xau.isDomesticCny)
        assertEquals("122.XAU", xau.eastMoneySecId)
        assertEquals("hf_XAU", xau.sinaCode)
        assertEquals(Currency.USD, xau.quoteCurrency)

        assertEquals("BTC", SymbolNormalizer.normalizeCryptoBaseSymbol("btc-usdt"))
        assertEquals("ETH", SymbolNormalizer.normalizeCryptoBaseSymbol("ETHUSDT"))
        assertEquals("SOL", SymbolNormalizer.normalizeCryptoBaseSymbol("SOL/USD"))
        assertEquals("USDT", SymbolNormalizer.normalizeCryptoBaseSymbol("USDT"))
        assertEquals("bitcoin", SymbolNormalizer.coinGeckoIdFor("BTC"))
        assertEquals("tether", SymbolNormalizer.coinGeckoIdFor("USDT"))

        val publicWealth = InvestmentAsset(
            id = "w1",
            accountId = "acc",
            assetClass = AssetClass.BANK_WEALTH,
            symbol = "000198",
            name = "货币理财",
            currency = Currency.CNY,
        )
        assertTrue(SymbolNormalizer.hasPublicBankWealthQuoteCode(publicWealth))

        val manualCodeWealth = publicWealth.copy(symbol = "CMB-2026A")
        assertFalse(SymbolNormalizer.hasPublicBankWealthQuoteCode(manualCodeWealth))

        val explicitManualHint = publicWealth.copy(quoteProviderHint = "manual")
        assertFalse(SymbolNormalizer.hasPublicBankWealthQuoteCode(explicitManualHint))
    }
}
