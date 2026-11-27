package io.github.kvtttba.droplet.core.domain

import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyAmountFormatterTest {

    @Test
    fun format_showsCurrencyThousandsAndTwoDecimalsWhenVisible() {
        val amount = Money.fromDecimalString("186420.50", Currency.CNY)
        assertEquals("¥186,420.50", PrivacyAmountFormatter.format(amount, hidden = false))
    }

    @Test
    fun format_supportsPositiveAndNegativeSignsAcrossCurrencies() {
        val positiveUsd = Money.fromDecimalString("15.00", Currency.USD)
        val negativeHkd = Money.fromDecimalString("-1234.567", Currency.HKD)

        assertEquals("+$15.00", PrivacyAmountFormatter.format(positiveUsd, showPositiveSign = true))
        assertEquals("-HK$1,234.57", PrivacyAmountFormatter.format(negativeHkd))
    }

    @Test
    fun format_masksDigitsWhenPrivacyHiddenEnabled() {
        val amount = Money.fromDecimalString("99999.99", Currency.CNY)
        assertEquals("¥ ****", PrivacyAmountFormatter.format(amount, hidden = true))
        assertEquals("****", PrivacyAmountFormatter.format(amount, hidden = true, showCurrencySymbol = false))
    }
}
