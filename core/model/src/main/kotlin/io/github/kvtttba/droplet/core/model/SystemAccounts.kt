package io.github.kvtttba.droplet.core.model

/**
 * Canonical identifiers and descriptors for built-in system equity accounts.
 * Shared across domain engine and database seeding layers.
 */
object SystemAccounts {
    private const val TRADING_ID_PREFIX = "system:equity:trading:"
    private const val OPENING_ID_PREFIX = "system:equity:opening:"

    fun tradingAccountId(currency: Currency): String =
        "$TRADING_ID_PREFIX${currency.code}"

    fun openingBalanceAccountId(currency: Currency): String =
        "$OPENING_ID_PREFIX${currency.code}"

    fun isTradingAccountId(accountId: String): Boolean =
        Currency.entries.any { tradingAccountId(it) == accountId }

    fun tradingCurrencyOrNull(accountId: String): Currency? =
        Currency.entries.firstOrNull { tradingAccountId(it) == accountId }

    fun isOpeningBalanceAccountId(accountId: String): Boolean =
        Currency.entries.any { openingBalanceAccountId(it) == accountId }

    fun tradingAccount(currency: Currency): Account = Account(
        id = tradingAccountId(currency),
        name = "Equity:Trading:${currency.code}",
        type = AccountType.EQUITY,
        subType = AccountSubType.TRADING,
        currency = currency,
        includeInNetWorth = false,
        isSystem = true,
    )

    fun openingBalanceAccount(currency: Currency): Account = Account(
        id = openingBalanceAccountId(currency),
        name = "Equity:OpeningBalance:${currency.code}",
        type = AccountType.EQUITY,
        subType = AccountSubType.OPENING,
        currency = currency,
        includeInNetWorth = false,
        isSystem = true,
    )

    fun allSystemEquityAccounts(): List<Account> =
        Currency.entries.flatMap { currency ->
            listOf(tradingAccount(currency), openingBalanceAccount(currency))
        }
}
