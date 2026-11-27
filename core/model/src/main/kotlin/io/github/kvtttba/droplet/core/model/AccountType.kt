package io.github.kvtttba.droplet.core.model

import kotlinx.serialization.Serializable

/**
 * Five fundamental account classes in Droplet's double-entry accounting model.
 */
@Serializable
enum class AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    INCOME,
    EXPENSE,
    ;

    val isBalanceSheet: Boolean
        get() = this == ASSET || this == LIABILITY || this == EQUITY

    val isIncomeStatement: Boolean
        get() = this == INCOME || this == EXPENSE

    /**
     * In Droplet's signed zero-sum double-entry convention:
     * ASSET and EXPENSE are debit-normal (+ increases balance).
     * LIABILITY, EQUITY, and INCOME are credit-normal (- increases balance).
     */
    val isDebitNormal: Boolean
        get() = this == ASSET || this == EXPENSE

    val isCreditNormal: Boolean
        get() = !isDebitNormal
}
