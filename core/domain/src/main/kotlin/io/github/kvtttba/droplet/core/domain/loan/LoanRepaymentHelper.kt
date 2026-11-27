package io.github.kvtttba.droplet.core.domain.loan

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.LoanInstallment
import io.github.kvtttba.droplet.core.model.LoanPlan
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionSource

/**
 * Pure Kotlin/JVM helper building balanced double-entry installment loan repayment and early-payoff
 * transactions via [LedgerEngine.buildLoanRepayment].
 *
 * Generated transactions always satisfy:
 * - `displayType == TransactionDisplayType.LOAN_REPAY`
 * - `Liability:Loan` `+principal` (reduces loan liability)
 * - `Expense:Interest` `+interestOrFee` (omitted when zero)
 * - `paymentAccount` `-(principal + interestOrFee)`
 * - Exact zero-sum per currency (`SUM(posting.amountMinor) == 0L`)
 * - Supports both [TransactionSource.MANUAL] and [TransactionSource.LOAN_AUTO]
 */
object LoanRepaymentHelper {

    fun buildInstallmentRepaymentTransaction(
        ledgerEngine: LedgerEngine,
        plan: LoanPlan,
        installment: LoanInstallment,
        loanAccount: Account,
        paymentAccount: Account,
        interestExpenseAccount: Account,
        occurredAt: Long,
        source: TransactionSource = TransactionSource.MANUAL,
        payee: String? = null,
        note: String? = null,
        tagIds: List<String> = emptyList(),
        createdAt: Long = occurredAt,
        updatedAt: Long = occurredAt,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(installment.loanPlanId == plan.id) {
            "Installment '${installment.id}' does not belong to loan plan '${plan.id}'"
        }
        require(source == TransactionSource.MANUAL || source == TransactionSource.LOAN_AUTO) {
            "Loan repayment source must be MANUAL or LOAN_AUTO, got $source"
        }
        validateLoanAccounts(
            plan = plan,
            loanAccount = loanAccount,
            paymentAccount = paymentAccount,
            interestExpenseAccount = interestExpenseAccount,
        )

        val principalMoney = Money(installment.principalMinor, plan.currency)
        val interestMoney = Money(installment.interestOrFeeMinor, plan.currency)
        val resolvedPayee = payee?.trim()?.takeIf { it.isNotEmpty() }
            ?: "分期还款 · ${plan.name} (${installment.periodNumber}/${plan.totalPeriods})"
        val resolvedNote = note?.trim()?.takeIf { it.isNotEmpty() } ?: when (source) {
            TransactionSource.LOAN_AUTO ->
                "第 ${installment.periodNumber}/${plan.totalPeriods} 期到期自动还款"
            else ->
                "第 ${installment.periodNumber}/${plan.totalPeriods} 期还款"
        }

        return ledgerEngine.buildLoanRepayment(
            meta = TransactionMeta(
                occurredAt = occurredAt,
                payee = resolvedPayee,
                note = resolvedNote,
                source = source,
                tagIds = tagIds,
                createdAt = createdAt,
                updatedAt = updatedAt,
            ),
            loanLiabilityAccountId = loanAccount.id,
            paymentAccountId = paymentAccount.id,
            principalAmount = principalMoney,
            interestOrFeeAmount = interestMoney,
            interestExpenseAccountId = interestExpenseAccount.id.takeIf { interestMoney.isPositive },
            exchangeRateOverride = exchangeRateOverride,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    fun buildEarlyPayoffTransaction(
        ledgerEngine: LedgerEngine,
        plan: LoanPlan,
        quote: EarlyPayoffQuote,
        loanAccount: Account,
        paymentAccount: Account,
        interestExpenseAccount: Account,
        occurredAt: Long,
        source: TransactionSource = TransactionSource.MANUAL,
        payee: String? = null,
        note: String? = null,
        tagIds: List<String> = emptyList(),
        createdAt: Long = occurredAt,
        updatedAt: Long = occurredAt,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(quote.loanPlanId == plan.id) {
            "EarlyPayoffQuote '${quote.loanPlanId}' does not match loan plan '${plan.id}'"
        }
        require(quote.remainingPendingPeriods > 0 && quote.totalPayoffOutflow.isPositive) {
            "No remaining balance to settle early for loan plan '${plan.id}'"
        }
        validateLoanAccounts(
            plan = plan,
            loanAccount = loanAccount,
            paymentAccount = paymentAccount,
            interestExpenseAccount = interestExpenseAccount,
        )

        val resolvedPayee = payee?.trim()?.takeIf { it.isNotEmpty() }
            ?: "提前结清 · ${plan.name}"
        val resolvedNote = note?.trim()?.takeIf { it.isNotEmpty() }
            ?: "提前结清剩余 ${quote.remainingPendingPeriods} 期"

        return ledgerEngine.buildLoanRepayment(
            meta = TransactionMeta(
                occurredAt = occurredAt,
                payee = resolvedPayee,
                note = resolvedNote,
                source = source,
                tagIds = tagIds,
                createdAt = createdAt,
                updatedAt = updatedAt,
            ),
            loanLiabilityAccountId = loanAccount.id,
            paymentAccountId = paymentAccount.id,
            principalAmount = quote.remainingPrincipal,
            interestOrFeeAmount = quote.earlyPayoffFeeOrInterest,
            interestExpenseAccountId = interestExpenseAccount.id.takeIf { quote.earlyPayoffFeeOrInterest.isPositive },
            exchangeRateOverride = exchangeRateOverride,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    private fun validateLoanAccounts(
        plan: LoanPlan,
        loanAccount: Account,
        paymentAccount: Account,
        interestExpenseAccount: Account,
    ) {
        require(loanAccount.id == plan.loanAccountId) {
            "Loan account '${loanAccount.id}' does not match plan.loanAccountId '${plan.loanAccountId}'"
        }
        require(loanAccount.type == AccountType.LIABILITY && loanAccount.subType == AccountSubType.LOAN) {
            "Loan account '${loanAccount.id}' must be a LIABILITY / LOAN account, got ${loanAccount.type} / ${loanAccount.subType}"
        }
        require(!loanAccount.isArchived) {
            "Loan account '${loanAccount.id}' is archived"
        }
        require(loanAccount.currency == plan.currency) {
            "Loan account currency (${loanAccount.currency}) must match loan plan currency (${plan.currency})"
        }
        require(paymentAccount.id != loanAccount.id) {
            "Payment account must differ from loan liability account '${loanAccount.id}'"
        }
        require(!paymentAccount.isArchived) {
            "Payment account '${paymentAccount.id}' is archived"
        }
        val isSupportedPaymentAccount = paymentAccount.type == AccountType.ASSET ||
            (paymentAccount.type == AccountType.LIABILITY && paymentAccount.subType == AccountSubType.CREDIT_CARD)
        require(isSupportedPaymentAccount) {
            "Payment account '${paymentAccount.id}' must be an ASSET or CREDIT_CARD account, got ${paymentAccount.type} / ${paymentAccount.subType}"
        }
        require(paymentAccount.currency == plan.currency) {
            "Payment account currency (${paymentAccount.currency}) must match loan currency (${plan.currency})"
        }
        require(interestExpenseAccount.type == AccountType.EXPENSE) {
            "Interest expense account '${interestExpenseAccount.id}' must be an EXPENSE account, got ${interestExpenseAccount.type}"
        }
        require(!interestExpenseAccount.isArchived) {
            "Interest expense account '${interestExpenseAccount.id}' is archived"
        }
    }
}
