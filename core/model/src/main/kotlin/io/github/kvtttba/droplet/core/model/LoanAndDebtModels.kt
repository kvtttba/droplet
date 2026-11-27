package io.github.kvtttba.droplet.core.model

import java.math.BigDecimal
import kotlinx.serialization.Serializable

@Serializable
enum class LoanMethod {
    EQUAL_INSTALLMENT,
    EQUAL_PRINCIPAL,
    FIXED_FEE,
}

@Serializable
enum class InstallmentStatus {
    PENDING,
    PAID,
}

/**
 * Installment loan plan definition supporting equal installment, equal principal,
 * and fixed-fee credit card/consumer installment schedules.
 */
@Serializable
data class LoanPlan(
    val id: String,
    val name: String,
    val method: LoanMethod,
    val currency: Currency = Currency.CNY,
    val totalPrincipalMinor: Long,
    val totalPeriods: Int,
    val annualRateDecimal: String? = null,
    val fixedFeePerPeriodMinor: Long? = null,
    val fixedFeeRateDecimal: String? = null,
    val firstDueAt: Long,
    val monthlyRepayDay: Int,
    val loanAccountId: String,
    val paymentAccountId: String,
    val interestExpenseAccountId: String,
    val autoPost: Boolean = false,
    val remindDaysBefore: Int? = null,
    val isArchived: Boolean = false,
    val note: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "LoanPlan id must not be blank" }
        require(name.isNotBlank()) { "LoanPlan name must not be blank" }
        require(totalPrincipalMinor > 0L) { "totalPrincipalMinor must be positive, got $totalPrincipalMinor" }
        require(totalPeriods > 0) { "totalPeriods must be positive, got $totalPeriods" }
        require(monthlyRepayDay in 1..31) { "monthlyRepayDay must be in 1..31, got $monthlyRepayDay" }
        require(loanAccountId.isNotBlank()) { "loanAccountId must not be blank" }
        require(paymentAccountId.isNotBlank()) { "paymentAccountId must not be blank" }
        require(interestExpenseAccountId.isNotBlank()) { "interestExpenseAccountId must not be blank" }
        if (annualRateDecimal != null) {
            validateNonNegativeRate("annualRateDecimal", annualRateDecimal)
        }
        if (fixedFeePerPeriodMinor != null) {
            require(fixedFeePerPeriodMinor >= 0L) {
                "fixedFeePerPeriodMinor must be non-negative, got $fixedFeePerPeriodMinor"
            }
        }
        if (fixedFeeRateDecimal != null) {
            validateNonNegativeRate("fixedFeeRateDecimal", fixedFeeRateDecimal)
        }
        if (remindDaysBefore != null) {
            require(remindDaysBefore in 0..31) {
                "remindDaysBefore must be in 0..31, got $remindDaysBefore"
            }
        }
    }

    val totalPrincipal: Money
        get() = Money(totalPrincipalMinor, currency)
}

/**
 * Single scheduled period row of a [LoanPlan].
 */
@Serializable
data class LoanInstallment(
    val id: String,
    val loanPlanId: String,
    val periodNumber: Int,
    val dueAt: Long,
    val principalMinor: Long,
    val interestOrFeeMinor: Long,
    val remainingPrincipalMinor: Long,
    val status: InstallmentStatus = InstallmentStatus.PENDING,
    val transactionId: String? = null,
    val paidAt: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "LoanInstallment id must not be blank" }
        require(loanPlanId.isNotBlank()) { "LoanInstallment loanPlanId must not be blank" }
        require(periodNumber > 0) { "periodNumber must be positive, got $periodNumber" }
        require(principalMinor >= 0L) { "principalMinor must be non-negative, got $principalMinor" }
        require(interestOrFeeMinor >= 0L) { "interestOrFeeMinor must be non-negative, got $interestOrFeeMinor" }
        require(remainingPrincipalMinor >= 0L) {
            "remainingPrincipalMinor must be non-negative, got $remainingPrincipalMinor"
        }
    }

    val totalDueMinor: Long
        get() = Math.addExact(principalMinor, interestOrFeeMinor)
}

/**
 * Counterparty profile for personal lending (receivable) and borrowing (payable) tracking.
 */
@Serializable
data class DebtProfile(
    val id: String,
    val name: String,
    val receivableAccountId: String? = null,
    val payableAccountId: String? = null,
    val defaultDueAt: Long? = null,
    val annualInterestRateDecimal: String? = null,
    val note: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "DebtProfile id must not be blank" }
        require(name.isNotBlank()) { "DebtProfile name must not be blank" }
        if (annualInterestRateDecimal != null) {
            validateNonNegativeRate("annualInterestRateDecimal", annualInterestRateDecimal)
        }
    }
}

private fun validateNonNegativeRate(fieldName: String, decimal: String) {
    val parsed = BigDecimal(decimal)
    require(parsed.compareTo(BigDecimal.ZERO) >= 0) {
        "$fieldName must be non-negative, got '$decimal'"
    }
    val expected = DecimalCanonicalizer.normalize(parsed, ExchangeRateSnapshot.SCALE)
    require(decimal == expected) {
        "$fieldName must be canonical decimal: expected '$expected', got '$decimal'"
    }
}
