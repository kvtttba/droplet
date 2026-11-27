package io.github.kvtttba.droplet.core.model

import kotlinx.serialization.Serializable

/**
 * Unified ledger account entity covering cash/bank/credit/investment/loan/debt/reimbursement
 * accounts, income/expense categories, and system equity accounts.
 */
@Serializable
data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val subType: AccountSubType,
    val parentId: String? = null,
    val currency: Currency = Currency.CNY,
    val icon: String? = null,
    val color: String? = null,
    val includeInNetWorth: Boolean = true,
    val isArchived: Boolean = false,
    val isSystem: Boolean = false,
    val creditLimitMinor: Long? = null,
    val billingDay: Int? = null,
    val dueDay: Int? = null,
    val remindDaysBefore: Int? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "Account id must not be blank" }
        require(name.isNotBlank()) { "Account name must not be blank" }
        require(type in subType.allowedAccountTypes) {
            "Account subType $subType is incompatible with account type $type"
        }
        val hasCreditFields = creditLimitMinor != null ||
            billingDay != null ||
            dueDay != null ||
            remindDaysBefore != null
        if (hasCreditFields) {
            require(subType == AccountSubType.CREDIT_CARD) {
                "Credit card attributes are only valid for CREDIT_CARD accounts, got $subType"
            }
        }
        if (creditLimitMinor != null) {
            require(creditLimitMinor >= 0L) {
                "Credit limit must be non-negative, got $creditLimitMinor"
            }
        }
        if (billingDay != null) {
            require(billingDay in 1..31) {
                "Billing day must be in 1..31, got $billingDay"
            }
        }
        if (dueDay != null) {
            require(dueDay in 1..31) {
                "Due day must be in 1..31, got $dueDay"
            }
        }
        if (remindDaysBefore != null) {
            require(remindDaysBefore in 0..31) {
                "Remind days before must be in 0..31, got $remindDaysBefore"
            }
        }
    }

    val creditLimit: Money?
        get() = creditLimitMinor?.let { Money(it, currency) }
}
