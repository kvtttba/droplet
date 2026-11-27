package io.github.kvtttba.droplet.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountAndModelTest {

    @Test
    fun currency_fromCode_isCaseInsensitiveAndRejectsUnknown() {
        assertEquals(Currency.CNY, Currency.fromCode("cny"))
        assertEquals(Currency.USD, Currency.fromCode(" USD "))
        assertEquals(Currency.HKD, Currency.fromCode("Hkd"))
        assertNull(Currency.fromCodeOrNull("EUR"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun currency_fromCode_throwsForUnsupportedCode() {
        Currency.fromCode("JPY")
    }

    @Test
    fun accountType_exposesAccountingClassifications() {
        assertTrue(AccountType.ASSET.isBalanceSheet && AccountType.ASSET.isDebitNormal)
        assertTrue(AccountType.LIABILITY.isBalanceSheet && AccountType.LIABILITY.isCreditNormal)
        assertTrue(AccountType.EQUITY.isBalanceSheet && AccountType.EQUITY.isCreditNormal)
        assertTrue(AccountType.INCOME.isIncomeStatement && AccountType.INCOME.isCreditNormal)
        assertTrue(AccountType.EXPENSE.isIncomeStatement && AccountType.EXPENSE.isDebitNormal)
    }

    @Test
    fun account_validatesCreditCardAttributesAndCompatibleSubTypes() {
        val card = Account(
            id = "acc:cmb-credit",
            name = "招行信用卡",
            type = AccountType.LIABILITY,
            subType = AccountSubType.CREDIT_CARD,
            currency = Currency.CNY,
            creditLimitMinor = Money.fromDecimalString("50000.00", Currency.CNY).amountMinor,
            billingDay = 5,
            dueDay = 23,
            remindDaysBefore = 3,
        )
        assertEquals(Money.fromDecimalString("50000.00", Currency.CNY), card.creditLimit)

        val encoded = Json.encodeToString(card)
        assertEquals(card, Json.decodeFromString<Account>(encoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun account_rejectsIncompatibleTypeAndSubType() {
        Account(
            id = "acc:bad",
            name = "Bad",
            type = AccountType.ASSET,
            subType = AccountSubType.CREDIT_CARD,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun account_rejectsCreditLimitOnNonCreditCardAccount() {
        Account(
            id = "acc:debit",
            name = "储蓄卡",
            type = AccountType.ASSET,
            subType = AccountSubType.DEBIT_CARD,
            creditLimitMinor = 100_0000L,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun account_rejectsInvalidBillingDay() {
        Account(
            id = "acc:cc",
            name = "信用卡",
            type = AccountType.LIABILITY,
            subType = AccountSubType.CREDIT_CARD,
            billingDay = 32,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun account_rejectsNegativeCreditLimit() {
        Account(
            id = "acc:cc",
            name = "信用卡",
            type = AccountType.LIABILITY,
            subType = AccountSubType.CREDIT_CARD,
            creditLimitMinor = -1L,
        )
    }

    @Test
    fun systemAccounts_providesDeterministicTradingAndOpeningEquityAccounts() {
        val all = SystemAccounts.allSystemEquityAccounts()
        assertEquals(Currency.entries.size * 2, all.size)

        for (currency in Currency.entries) {
            val trading = SystemAccounts.tradingAccount(currency)
            assertEquals(AccountType.EQUITY, trading.type)
            assertEquals(AccountSubType.TRADING, trading.subType)
            assertTrue(trading.isSystem)
            assertFalse(trading.includeInNetWorth)
            assertTrue(SystemAccounts.isTradingAccountId(trading.id))
            assertEquals(currency, SystemAccounts.tradingCurrencyOrNull(trading.id))

            val opening = SystemAccounts.openingBalanceAccount(currency)
            assertEquals(AccountType.EQUITY, opening.type)
            assertEquals(AccountSubType.OPENING, opening.subType)
            assertTrue(SystemAccounts.isOpeningBalanceAccountId(opening.id))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun posting_rejectsMismatchedExchangeRateFromCurrency() {
        Posting(
            id = "p1",
            transactionId = "tx1",
            accountId = "acc:usd",
            currency = Currency.USD,
            amountMinor = 10_0000L,
            baseCurrency = Currency.CNY,
            baseAmountMinor = 72_5000L,
            exchangeRateSnapshot = ExchangeRateSnapshot.of(Currency.HKD, Currency.CNY, "0.93"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun posting_rejectsQuantityWithoutInvestmentAssetId() {
        Posting(
            id = "p1",
            transactionId = "tx1",
            accountId = "acc:aapl",
            currency = Currency.USD,
            amountMinor = 2200_0000L,
            baseCurrency = Currency.USD,
            baseAmountMinor = 2200_0000L,
            exchangeRateSnapshot = ExchangeRateSnapshot.identity(Currency.USD),
            quantity = Quantity.of("10"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun transaction_rejectsPostingReferencingDifferentTransactionId() {
        val posting = Posting(
            id = "p1",
            transactionId = "tx-other",
            accountId = "acc:1",
            currency = Currency.CNY,
            amountMinor = 10_0000L,
            baseCurrency = Currency.CNY,
            baseAmountMinor = 10_0000L,
            exchangeRateSnapshot = ExchangeRateSnapshot.identity(Currency.CNY),
        )
        Transaction(
            id = "tx-1",
            occurredAt = 1_700_000_000_000L,
            displayType = TransactionDisplayType.EXPENSE,
            postings = listOf(posting),
        )
    }

    @Test
    fun transaction_jsonSerialization_roundTripsFullAggregate() {
        val p1 = Posting(
            id = "p1",
            transactionId = "tx-1",
            accountId = "exp:food",
            currency = Currency.CNY,
            amountMinor = 38_5000L,
            baseCurrency = Currency.CNY,
            baseAmountMinor = 38_5000L,
            exchangeRateSnapshot = ExchangeRateSnapshot.identity(Currency.CNY, capturedAt = 1000L),
        )
        val p2 = Posting(
            id = "p2",
            transactionId = "tx-1",
            accountId = "acc:cmb",
            currency = Currency.CNY,
            amountMinor = -38_5000L,
            baseCurrency = Currency.CNY,
            baseAmountMinor = -38_5000L,
            exchangeRateSnapshot = ExchangeRateSnapshot.identity(Currency.CNY, capturedAt = 1000L),
        )
        val tx = Transaction(
            id = "tx-1",
            occurredAt = 1000L,
            displayType = TransactionDisplayType.EXPENSE,
            status = TransactionStatus.POSTED,
            payee = "午餐店",
            note = "工作日午餐",
            source = TransactionSource.MANUAL,
            tagIds = listOf("tag:workday"),
            postings = listOf(p1, p2),
        )

        val decoded = Json.decodeFromString<Transaction>(Json.encodeToString(tx))
        assertEquals(tx, decoded)
        assertEquals(2, decoded.businessPostings.size)
        assertTrue(decoded.systemBalancingPostings.isEmpty())
        assertFalse(decoded.isMultiCurrency)
    }
}
