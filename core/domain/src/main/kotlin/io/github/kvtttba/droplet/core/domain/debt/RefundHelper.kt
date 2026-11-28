package io.github.kvtttba.droplet.core.domain.debt

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionSource

data class RefundBuildResult(
    val refundTransaction: Transaction,
    val updatedSummary: ExpenseRefundSummary,
)

/**
 * Pure JVM helper building validated full, partial, or multi-stage linked `REFUND`
 * transactions via [LedgerEngine.buildRefund], strictly enforcing the cumulative refund cap
 * (`cumulativeRefund <= originalExpense`).
 */
object RefundHelper {

    fun buildRefundTransaction(
        ledgerEngine: LedgerEngine,
        originalExpense: Transaction,
        existingLinkedRefunds: List<Transaction>,
        accountsById: Map<String, Account>,
        refundToAccount: Account,
        refundAmount: Money,
        expenseCategoryAccountIdOverride: String? = null,
        expenseCurrencyReductionAmount: Money? = null,
        occurredAt: Long,
        payee: String? = null,
        note: String? = null,
        tagIds: List<String> = emptyList(),
        createdAt: Long = occurredAt,
        updatedAt: Long = occurredAt,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
    ): RefundBuildResult {
        val currentSummary = RefundCalculator.summarize(
            originalExpense = originalExpense,
            linkedRefunds = existingLinkedRefunds,
            accountsById = accountsById,
            expenseCategoryAccountIdOverride = expenseCategoryAccountIdOverride,
        ) ?: throw IllegalArgumentException("仅支持对已入账的支出流水发起退款")

        require(currentSummary.canRefundMore) {
            "该笔支出已全额退款，剩余可退金额为 0"
        }
        validateRefundToAccount(refundToAccount)
        require(refundAmount.isPositive) { "退款金额必须大于 0" }
        require(refundAmount.currency == refundToAccount.currency) {
            "退款金额币种 (${refundAmount.currency.code}) 必须与退回账户币种 (${refundToAccount.currency.code}) 一致"
        }

        val reductionAmount = expenseCurrencyReductionAmount ?: run {
            require(refundAmount.currency == currentSummary.expenseCurrency) {
                "跨币种退款需指定折算冲减的原支出币种 (${currentSummary.expenseCurrency.code}) 金额"
            }
            refundAmount
        }
        require(reductionAmount.isPositive) { "冲减原支出金额必须大于 0" }
        require(reductionAmount.currency == currentSummary.expenseCurrency) {
            "冲减金额币种 (${reductionAmount.currency.code}) 必须与原支出币种 (${currentSummary.expenseCurrency.code}) 一致"
        }
        require(reductionAmount <= currentSummary.remainingRefundableAmount) {
            "本次退款 ($reductionAmount) 超过剩余可退上限 (${currentSummary.remainingRefundableAmount})"
        }

        val expenseCategoryAccount = requireNotNull(accountsById[currentSummary.expenseCategoryAccountId]) {
            "未找到原支出分类科目：${currentSummary.expenseCategoryAccountId}"
        }

        val resolvedPayee = payee?.trim()?.takeIf { it.isNotEmpty() }
            ?: originalExpense.payee?.let { "退款 · $it" }
            ?: "消费退款"
        val resolvedNote = note?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (reductionAmount == currentSummary.remainingRefundableAmount && currentSummary.refundCount == 0) {
                "全额退款"
            } else {
                "第 ${currentSummary.refundCount + 1} 次关联退款"
            }

        val meta = TransactionMeta(
            occurredAt = occurredAt,
            payee = resolvedPayee,
            note = resolvedNote,
            source = TransactionSource.MANUAL,
            refTransactionId = originalExpense.id,
            tagIds = tagIds.ifEmpty { originalExpense.tagIds },
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        val tx = ledgerEngine.buildRefund(
            meta = meta,
            originalTransactionId = originalExpense.id,
            expenseCategoryAccountId = expenseCategoryAccount.id,
            refundToAccountId = refundToAccount.id,
            refundAmount = refundAmount,
            originalExpenseAmount = currentSummary.originalExpenseAmount,
            alreadyRefundedAmount = currentSummary.cumulativeRefundedAmount,
            expenseCurrencyReductionAmount = reductionAmount,
            exchangeRateOverride = exchangeRateOverride,
            rateProviderOverride = rateProviderOverride,
            accounts = accountsById,
        )

        val updatedSummary = requireNotNull(
            RefundCalculator.summarize(
                originalExpense = originalExpense,
                linkedRefunds = existingLinkedRefunds + tx,
                accountsById = accountsById,
                expenseCategoryAccountIdOverride = currentSummary.expenseCategoryAccountId,
            ),
        )

        return RefundBuildResult(
            refundTransaction = tx,
            updatedSummary = updatedSummary,
        )
    }

    private fun validateRefundToAccount(account: Account) {
        require(!account.isArchived) { "退回账户「${account.name}」已归档" }
        val isSupported = (account.type == AccountType.ASSET &&
            account.subType != AccountSubType.REIMBURSEMENT &&
            account.subType != AccountSubType.RECEIVABLE &&
            account.subType != AccountSubType.INVEST_HOLDING) ||
            (account.type == AccountType.LIABILITY && account.subType == AccountSubType.CREDIT_CARD)
        require(isSupported) { "退回账户「${account.name}」需为日常资金账户或信用账户" }
    }
}
