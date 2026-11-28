package io.github.kvtttba.droplet.core.domain.debt

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionSource

data class ReimbursementSettlementPlan(
    val advanceTransactionId: String,
    val settlementTransaction: Transaction,
    val nextAdvanceState: ReimburseState,
    val remainingPendingAfter: Money,
    val shortfallAmount: Money,
    val surplusAmount: Money,
)

/**
 * Pure JVM helper building balanced double-entry reimbursement settlement transactions for:
 * - Full exact reimbursement (`received == settled == remaining`)
 * - Partial reimbursement (`received == settled < remaining`, leaving remainder `PARTIAL`)
 * - Shortfall turned into personal expense (`received < settled`, diff -> `Expense:*`)
 * - Surplus reimbursement (`received > settled`, diff -> `Income:*`)
 * - Batch full settlement across multiple selected pending advances
 */
object ReimbursementSettlementHelper {

    fun buildSingleSettlement(
        ledgerEngine: LedgerEngine,
        item: ReimbursementItemSummary,
        reimbursementAccount: Account,
        depositAccount: Account,
        settledReimbursementAmount: Money,
        receivedAmount: Money,
        shortfallExpenseAccount: Account? = null,
        surplusIncomeAccount: Account? = null,
        occurredAt: Long,
        payee: String? = null,
        note: String? = null,
        tagIds: List<String> = emptyList(),
        createdAt: Long = occurredAt,
        updatedAt: Long = occurredAt,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
    ): ReimbursementSettlementPlan {
        require(item.isPending && item.remainingPendingAmount.isPositive) {
            "该笔垫付已全部报销结清，不可重复冲抵"
        }
        require(reimbursementAccount.id == item.reimbursementAccountId) {
            "待报销科目与原垫付流水不匹配"
        }
        validateDepositAccount(depositAccount, item.currency)
        require(depositAccount.id != reimbursementAccount.id) {
            "报销收款账户不能与待报销科目相同"
        }
        require(settledReimbursementAmount.isPositive) { "本次冲抵待报销金额必须大于 0" }
        require(settledReimbursementAmount.currency == item.currency) {
            "冲抵币种 (${settledReimbursementAmount.currency.code}) 必须与待报销币种 (${item.currency.code}) 一致"
        }
        require(settledReimbursementAmount <= item.remainingPendingAmount) {
            "本次冲抵金额 ($settledReimbursementAmount) 不能超过剩余待报销金额 (${item.remainingPendingAmount})"
        }
        require(!receivedAmount.isNegative) { "实际到账金额不能为负数" }
        require(receivedAmount.currency == item.currency) {
            "到账币种 (${receivedAmount.currency.code}) 必须与待报销币种 (${item.currency.code}) 一致"
        }

        val accountsMap = linkedMapOf(
            reimbursementAccount.id to reimbursementAccount,
            depositAccount.id to depositAccount,
        )

        val shortfallAmount = if (receivedAmount < settledReimbursementAmount) {
            val expAcct = requireNotNull(shortfallExpenseAccount) {
                "实报少于本次冲抵金额时，请选择差额转个人自付的支出分类"
            }
            require(!expAcct.isArchived) { "支出分类「${expAcct.name}」已归档" }
            require(expAcct.type == AccountType.EXPENSE) { "「${expAcct.name}」必须为支出分类" }
            accountsMap[expAcct.id] = expAcct
            settledReimbursementAmount - receivedAmount
        } else {
            Money.zero(item.currency)
        }

        val surplusAmount = if (receivedAmount > settledReimbursementAmount) {
            val incAcct = requireNotNull(surplusIncomeAccount) {
                "实报超出本次冲抵金额时，请选择超额部分的收入分类"
            }
            require(!incAcct.isArchived) { "收入分类「${incAcct.name}」已归档" }
            require(incAcct.type == AccountType.INCOME) { "「${incAcct.name}」必须为收入分类" }
            accountsMap[incAcct.id] = incAcct
            receivedAmount - settledReimbursementAmount
        } else {
            Money.zero(item.currency)
        }

        val remainingAfter = item.remainingPendingAmount - settledReimbursementAmount
        val nextState = if (remainingAfter.isZero) ReimburseState.SETTLED else ReimburseState.PARTIAL

        val resolvedPayee = payee?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.advanceTransaction.payee?.let { "报销到账 · $it" }
            ?: "报销到账"
        val resolvedNote = note?.trim()?.takeIf { it.isNotEmpty() }
            ?: when {
                shortfallAmount.isPositive -> "差额 $shortfallAmount 转个人自付"
                surplusAmount.isPositive -> "含报销补贴 $surplusAmount"
                remainingAfter.isPositive -> "部分报销到账，剩余待报销 $remainingAfter"
                else -> "全额报销到账"
            }

        val meta = TransactionMeta(
            occurredAt = occurredAt,
            payee = resolvedPayee,
            note = resolvedNote,
            source = TransactionSource.MANUAL,
            tagIds = tagIds,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        val tx = ledgerEngine.buildReimbursementSettlement(
            meta = meta,
            advanceTransactionId = item.advanceTransaction.id,
            reimbursementAccountId = reimbursementAccount.id,
            depositAccountId = depositAccount.id,
            settledReimbursementAmount = settledReimbursementAmount,
            receivedAmount = receivedAmount,
            shortfallExpenseAccountId = shortfallExpenseAccount?.id,
            surplusIncomeAccountId = surplusIncomeAccount?.id,
            remainingPendingAfterSettlement = remainingAfter,
            exchangeRateOverride = exchangeRateOverride,
            rateProviderOverride = rateProviderOverride,
            accounts = accountsMap,
        )

        return ReimbursementSettlementPlan(
            advanceTransactionId = item.advanceTransaction.id,
            settlementTransaction = tx,
            nextAdvanceState = nextState,
            remainingPendingAfter = remainingAfter,
            shortfallAmount = shortfallAmount,
            surplusAmount = surplusAmount,
        )
    }

    /**
     * Builds atomic per-item full settlement plans for a batch of selected pending advances
     * (preserving 1-to-1 `refTransactionId` audit traceability for every settled advance).
     */
    fun buildBatchFullSettlements(
        ledgerEngine: LedgerEngine,
        items: List<ReimbursementItemSummary>,
        reimbursementAccountsById: Map<String, Account>,
        depositAccount: Account,
        occurredAt: Long,
        note: String? = null,
        tagIds: List<String> = emptyList(),
        createdAt: Long = occurredAt,
        updatedAt: Long = occurredAt,
        rateProviderOverride: ExchangeRateProvider? = null,
    ): List<ReimbursementSettlementPlan> {
        require(items.isNotEmpty()) { "请至少选择一笔待报销流水" }
        val distinctIds = items.map { it.advanceTransaction.id }.distinct()
        require(distinctIds.size == items.size) { "批量报销列表包含重复流水" }

        return items.map { item ->
            val reimburseAcct = requireNotNull(reimbursementAccountsById[item.reimbursementAccountId]) {
                "未找到待报销科目：${item.reimbursementAccountId}"
            }
            buildSingleSettlement(
                ledgerEngine = ledgerEngine,
                item = item,
                reimbursementAccount = reimburseAcct,
                depositAccount = depositAccount,
                settledReimbursementAmount = item.remainingPendingAmount,
                receivedAmount = item.remainingPendingAmount,
                occurredAt = occurredAt,
                note = note?.trim()?.takeIf { it.isNotEmpty() } ?: "批量报销全额到账",
                tagIds = tagIds,
                createdAt = createdAt,
                updatedAt = updatedAt,
                rateProviderOverride = rateProviderOverride,
            )
        }
    }

    private fun validateDepositAccount(account: Account, expectedCurrency: Currency) {
        require(!account.isArchived) { "收款账户「${account.name}」已归档" }
        val isSupported = (account.type == AccountType.ASSET &&
            account.subType != AccountSubType.REIMBURSEMENT &&
            account.subType != AccountSubType.RECEIVABLE &&
            account.subType != AccountSubType.INVEST_HOLDING) ||
            (account.type == AccountType.LIABILITY && account.subType == AccountSubType.CREDIT_CARD)
        require(isSupported) { "收款账户「${account.name}」需为日常资金账户或信用账户" }
        require(account.currency == expectedCurrency) {
            "收款账户「${account.name}」币种 (${account.currency.code}) 必须与待报销币种 (${expectedCurrency.code}) 一致"
        }
    }
}
