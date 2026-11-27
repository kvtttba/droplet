package io.github.kvtttba.droplet.core.model

import kotlinx.serialization.Serializable

/**
 * Granular account sub-classification aligned with the `accounts.sub_type` schema.
 */
@Serializable
enum class AccountSubType(
    val allowedAccountTypes: Set<AccountType>,
) {
    CASH(setOf(AccountType.ASSET)),
    DEBIT_CARD(setOf(AccountType.ASSET)),
    CREDIT_CARD(setOf(AccountType.LIABILITY)),
    STORED_VALUE(setOf(AccountType.ASSET)),
    INVEST_CASH(setOf(AccountType.ASSET)),
    INVEST_HOLDING(setOf(AccountType.ASSET)),
    LOAN(setOf(AccountType.LIABILITY)),
    RECEIVABLE(setOf(AccountType.ASSET)),
    PAYABLE(setOf(AccountType.LIABILITY)),
    REIMBURSEMENT(setOf(AccountType.ASSET)),
    CATEGORY(setOf(AccountType.INCOME, AccountType.EXPENSE)),
    TRADING(setOf(AccountType.EQUITY)),
    OPENING(setOf(AccountType.EQUITY)),
}
