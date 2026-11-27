package io.github.kvtttba.droplet.core.domain.ledger

import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Posting
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerValidatorTest {

    private fun posting(
        id: String,
        accountId: String,
        amountMinor: Long,
        currency: Currency = Currency.CNY,
        baseCurrency: Currency = Currency.CNY,
        baseAmountMinor: Long = amountMinor,
        rate: ExchangeRateSnapshot = if (currency == baseCurrency) {
            ExchangeRateSnapshot.identity(currency)
        } else {
            ExchangeRateSnapshot.of(currency, baseCurrency, "7.25")
        },
        investmentAssetId: String? = null,
        quantity: Quantity? = null,
        isSystemBalancing: Boolean = false,
        txId: String = "tx-1",
    ) = Posting(
        id = id,
        transactionId = txId,
        accountId = accountId,
        currency = currency,
        amountMinor = amountMinor,
        baseCurrency = baseCurrency,
        baseAmountMinor = baseAmountMinor,
        exchangeRateSnapshot = rate,
        investmentAssetId = investmentAssetId,
        quantity = quantity,
        isSystemBalancing = isSystemBalancing,
    )

    private fun tx(vararg postings: Posting) = Transaction(
        id = "tx-1",
        occurredAt = 1_700_000_000_000L,
        displayType = TransactionDisplayType.SPLIT,
        postings = postings.toList(),
    )

    @Test
    fun validate_rejectsZeroAndSinglePostingTransactions() {
        val emptyResult = LedgerValidator.validate(tx()) as LedgerValidationResult.Invalid
        assertTrue(emptyResult.errors.any { it == LedgerValidationError.InsufficientPostings(0) })

        val singleResult = LedgerValidator.validate(
            tx(posting("p1", "exp:food", 10_0000L)),
        ) as LedgerValidationResult.Invalid
        assertTrue(singleResult.errors.any { it == LedgerValidationError.InsufficientPostings(1) })
        assertTrue(singleResult.errors.any { it == LedgerValidationError.UnbalancedCurrency(Currency.CNY, 10_0000L) })
    }

    @Test
    fun validate_rejectsZeroAmountAndDuplicatePostingIds() {
        val result = LedgerValidator.validate(
            tx(
                posting("p1", "exp:food", 0L),
                posting("p1", "acc:cash", 0L),
            ),
        ) as LedgerValidationResult.Invalid

        assertTrue(result.errors.any { it is LedgerValidationError.ZeroAmountPosting })
        assertTrue(result.errors.any { it == LedgerValidationError.DuplicatePostingId("p1") })
    }

    @Test
    fun validate_rejectsUnbalancedSingleCurrencyAndDetectsLongOverflow() {
        val unbalanced = LedgerValidator.validate(
            tx(
                posting("p1", "exp:food", 38_5000L),
                posting("p2", "acc:cash", -38_4999L),
            ),
        ) as LedgerValidationResult.Invalid
        assertTrue(unbalanced.errors.contains(LedgerValidationError.UnbalancedCurrency(Currency.CNY, 1L)))

        // Three huge positives that would wrap silently without Math.addExact
        val overflow = LedgerValidator.validate(
            tx(
                posting("p1", "acc:a", Long.MAX_VALUE),
                posting("p2", "acc:b", Long.MAX_VALUE),
                posting("p3", "acc:c", 2L),
            ),
        ) as LedgerValidationResult.Invalid
        assertTrue(overflow.errors.contains(LedgerValidationError.CurrencySumOverflow(Currency.CNY)))
    }

    @Test
    fun validate_rejectsTradingAccountInSingleCurrencyAndInvalidTradingAccountMapping() {
        val singleCcyTrading = LedgerValidator.validate(
            tx(
                posting("p1", "acc:cash", -10_0000L),
                posting(
                    id = "p2",
                    accountId = SystemAccounts.tradingAccountId(Currency.CNY),
                    amountMinor = 10_0000L,
                    isSystemBalancing = true,
                ),
            ),
        ) as LedgerValidationResult.Invalid
        assertTrue(singleCcyTrading.errors.any { it is LedgerValidationError.SystemBalancingInSingleCurrency })

        val wrongTradingTarget = LedgerValidator.validate(
            tx(
                posting("p1", "acc:cny", -725_0000L, Currency.CNY),
                posting(
                    id = "p2",
                    accountId = SystemAccounts.tradingAccountId(Currency.USD),
                    amountMinor = 725_0000L,
                    currency = Currency.CNY,
                    isSystemBalancing = true,
                ),
                posting("p3", "acc:usd", 100_0000L, Currency.USD, Currency.CNY, 725_0000L),
                posting(
                    id = "p4",
                    accountId = SystemAccounts.tradingAccountId(Currency.USD),
                    amountMinor = -100_0000L,
                    currency = Currency.USD,
                    baseCurrency = Currency.CNY,
                    baseAmountMinor = -725_0000L,
                    isSystemBalancing = true,
                ),
            ),
        ) as LedgerValidationResult.Invalid
        assertTrue(wrongTradingTarget.errors.any { it is LedgerValidationError.InvalidTradingAccount })
    }

    @Test
    fun validate_rejectsBaseAmountCorruptionAndMixedBaseCurrencies() {
        val corruptedSameCcy = LedgerValidator.validate(
            tx(
                posting("p1", "exp:food", 10_0000L, baseAmountMinor = 9_0000L),
                posting("p2", "acc:cash", -10_0000L, baseAmountMinor = -10_0000L),
            ),
        ) as LedgerValidationResult.Invalid
        assertTrue(corruptedSameCcy.errors.any { it is LedgerValidationError.BaseCurrencyMismatchOnSameCurrency })

        val flippedSign = LedgerValidator.validate(
            tx(
                posting("p1", "exp:travel", 100_0000L, Currency.USD, Currency.CNY, -725_0000L),
                posting("p2", "acc:usd-card", -100_0000L, Currency.USD, Currency.CNY, -725_0000L),
            ),
        ) as LedgerValidationResult.Invalid
        assertTrue(flippedSign.errors.any { it is LedgerValidationError.BaseAmountSignMismatch })

        val mixedBase = LedgerValidator.validate(
            tx(
                posting("p1", "exp:travel", 100_0000L, Currency.USD, Currency.CNY, 725_0000L),
                posting(
                    id = "p2",
                    accountId = "acc:usd-card",
                    amountMinor = -100_0000L,
                    currency = Currency.USD,
                    baseCurrency = Currency.USD,
                    baseAmountMinor = -100_0000L,
                    rate = ExchangeRateSnapshot.identity(Currency.USD),
                ),
            ),
        ) as LedgerValidationResult.Invalid
        assertTrue(mixedBase.errors.any { it is LedgerValidationError.MixedBaseCurrencies })
    }

    @Test
    fun validate_checksInvestmentQuantityAndAccountRegistryRules() {
        val badQuantity = LedgerValidator.validate(
            tx(
                posting(
                    id = "p1",
                    accountId = "acc:aapl",
                    amountMinor = 2200_0000L,
                    currency = Currency.USD,
                    baseCurrency = Currency.CNY,
                    baseAmountMinor = 15950_0000L,
                    investmentAssetId = "asset:aapl",
                    quantity = Quantity.of("-10"),
                ),
                posting(
                    id = "p2",
                    accountId = "acc:usd-cash",
                    amountMinor = -2200_0000L,
                    currency = Currency.USD,
                    baseCurrency = Currency.CNY,
                    baseAmountMinor = -15950_0000L,
                ),
            ),
        ) as LedgerValidationResult.Invalid
        assertTrue(badQuantity.errors.any { it is LedgerValidationError.InvestmentQuantitySignMismatch })

        val zeroQuantity = LedgerValidator.validate(
            tx(
                posting(
                    id = "p1",
                    accountId = "acc:aapl",
                    amountMinor = 2200_0000L,
                    currency = Currency.USD,
                    baseCurrency = Currency.CNY,
                    baseAmountMinor = 15950_0000L,
                    investmentAssetId = "asset:aapl",
                    quantity = Quantity.ZERO,
                ),
                posting(
                    id = "p2",
                    accountId = "acc:usd-cash",
                    amountMinor = -2200_0000L,
                    currency = Currency.USD,
                    baseCurrency = Currency.CNY,
                    baseAmountMinor = -15950_0000L,
                ),
            ),
        ) as LedgerValidationResult.Invalid
        assertTrue(zeroQuantity.errors.any { it == LedgerValidationError.InvestmentQuantityZero("p1") })

        val accounts = mapOf(
            "exp:food" to Account("exp:food", "餐饮", AccountType.EXPENSE, AccountSubType.CATEGORY, currency = Currency.CNY),
            "acc:usd-card" to Account("acc:usd-card", "美卡", AccountType.LIABILITY, AccountSubType.CREDIT_CARD, currency = Currency.USD),
            "acc:cny-archived" to Account("acc:cny-archived", "旧卡", AccountType.ASSET, AccountSubType.DEBIT_CARD, currency = Currency.CNY, isArchived = true),
        )

        // Expense category with default CNY may legitimately receive USD postings when paying from USD card
        val validForeignExpense = tx(
            posting("p1", "exp:food", 20_0000L, Currency.USD, Currency.CNY, 145_0000L),
            posting("p2", "acc:usd-card", -20_0000L, Currency.USD, Currency.CNY, -145_0000L),
        )
        assertEquals(LedgerValidationResult.Valid, LedgerValidator.validate(validForeignExpense, accounts))

        // Balance-sheet account with mismatched currency + archived account + unknown account must fail
        val badAccountsTx = tx(
            posting("p1", "acc:usd-card", 10_0000L, Currency.CNY),
            posting("p2", "acc:cny-archived", -5_0000L, Currency.CNY),
            posting("p3", "acc:ghost", -5_0000L, Currency.CNY),
        )
        val invalid = LedgerValidator.validate(badAccountsTx, accounts) as LedgerValidationResult.Invalid
        assertTrue(invalid.errors.any { it is LedgerValidationError.AccountCurrencyMismatch })
        assertTrue(invalid.errors.any { it is LedgerValidationError.ArchivedAccountNotAllowed })
        assertTrue(invalid.errors.any { it is LedgerValidationError.UnknownAccount })

        // allowArchivedAccounts = true permits historical re-validation of archived accounts
        val archivedOnly = tx(
            posting("p1", "exp:food", 10_0000L, Currency.CNY),
            posting("p2", "acc:cny-archived", -10_0000L, Currency.CNY),
        )
        assertEquals(
            LedgerValidationResult.Valid,
            LedgerValidator.validate(archivedOnly, accounts, allowArchivedAccounts = true),
        )
    }

    @Test
    fun computeCurrencyImbalances_returnsExactPerCurrencySums() {
        val imbalances = LedgerValidator.computeCurrencyImbalances(
            listOf(
                posting("p1", "exp:a", Money.fromDecimalString("50.25", Currency.CNY).amountMinor),
                posting("p2", "acc:a", Money.fromDecimalString("-30.00", Currency.CNY).amountMinor),
                posting("p3", "acc:usd", Money.fromDecimalString("-10.00", Currency.USD).amountMinor, Currency.USD, Currency.CNY, -72_5000L),
            ),
        )
        assertEquals(Money.fromDecimalString("20.25", Currency.CNY).amountMinor, imbalances[Currency.CNY])
        assertEquals(Money.fromDecimalString("-10.00", Currency.USD).amountMinor, imbalances[Currency.USD])
    }
}
