package io.github.kvtttba.droplet.core.domain.automation

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionSource

/**
 * Pure JVM transaction builder translating a [RecurringPayload] (from either a recurring rule or a
 * quick template) into a strictly balanced double-entry [Transaction] via [LedgerEngine].
 */
object RecurringTransactionBuilder {

    const val DEFAULT_REIMBURSEMENT_ACCOUNT_ID: String = "seed:account:reimbursement:cny"

    fun buildTransaction(
        payload: RecurringPayload,
        occurredAt: Long,
        source: TransactionSource,
        ledgerEngine: LedgerEngine,
        accountsById: Map<String, Account>,
        rateProvider: ExchangeRateProvider? = null,
        defaultReimbursementAccountId: String = DEFAULT_REIMBURSEMENT_ACCOUNT_ID,
        nowEpochMs: Long = occurredAt,
    ): Transaction {
        val activeEngine = if (rateProvider != null) {
            ledgerEngine.withRateProvider(rateProvider)
        } else {
            ledgerEngine
        }

        val primaryAccount = accountsById[payload.accountId]
            ?: throw IllegalArgumentException("未找到关联账户，请检查模板或周期规则配置")
        require(!primaryAccount.isArchived) {
            "关联账户「${primaryAccount.name}」已归档，请更新配置后再入账"
        }

        val meta = TransactionMeta(
            occurredAt = occurredAt,
            payee = payload.payee?.trim()?.takeIf { it.isNotEmpty() },
            note = payload.note?.trim()?.takeIf { it.isNotEmpty() },
            source = source,
            tagIds = payload.tagIds.distinct(),
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        )

        return when (payload.displayType) {
            TransactionDisplayType.EXPENSE -> buildExpenseOrReimburse(
                payload = payload,
                primaryAccount = primaryAccount,
                meta = meta,
                engine = activeEngine,
                accountsById = accountsById,
                defaultReimbursementAccountId = defaultReimbursementAccountId,
            )

            TransactionDisplayType.INCOME -> buildIncome(
                payload = payload,
                primaryAccount = primaryAccount,
                meta = meta,
                engine = activeEngine,
                accountsById = accountsById,
            )

            TransactionDisplayType.TRANSFER -> buildTransfer(
                payload = payload,
                fromAccount = primaryAccount,
                meta = meta,
                engine = activeEngine,
                accountsById = accountsById,
            )

            else -> throw IllegalArgumentException(
                "不支持的交易类型: ${payload.displayType}",
            )
        }
    }

    private fun buildExpenseOrReimburse(
        payload: RecurringPayload,
        primaryAccount: Account,
        meta: TransactionMeta,
        engine: LedgerEngine,
        accountsById: Map<String, Account>,
        defaultReimbursementAccountId: String,
    ): Transaction {
        val amount = payload.amount
        val actualPaid = if (primaryAccount.currency != amount.currency) {
            payload.targetAmount?.takeIf { it.currency == primaryAccount.currency }
                ?: convertOrThrow(amount, primaryAccount.currency, engine.rateProvider, meta.occurredAt)
        } else {
            null
        }

        if (payload.isReimbursable) {
            val reimburseId = payload.reimbursementAccountId ?: defaultReimbursementAccountId
            val reimburseAccount = accountsById[reimburseId]
                ?: throw IllegalArgumentException("未找到系统待报销账户")
            val advanceAmount = if (reimburseAccount.currency == amount.currency) {
                amount
            } else {
                convertOrThrow(amount, reimburseAccount.currency, engine.rateProvider, meta.occurredAt)
            }
            val effectivePaid = if (primaryAccount.currency != advanceAmount.currency) {
                actualPaid ?: convertOrThrow(advanceAmount, primaryAccount.currency, engine.rateProvider, meta.occurredAt)
            } else {
                null
            }
            return engine.buildReimbursableAdvance(
                meta = meta,
                reimbursementAccountId = reimburseId,
                paymentAccountId = primaryAccount.id,
                amount = advanceAmount,
                actualPaidAmount = effectivePaid,
                accounts = accountsById,
            )
        }

        val categoryId = payload.categoryAccountId
            ?: throw IllegalArgumentException("支出分类不能为空")
        val category = accountsById[categoryId]
            ?: throw IllegalArgumentException("未找到支出分类，请检查规则或模板配置")
        require(!category.isArchived) { "支出分类「${category.name}」已归档" }
        require(category.type == AccountType.EXPENSE) { "所选分类「${category.name}」不是支出分类" }

        return engine.buildExpense(
            meta = meta,
            expenseCategoryAccountId = categoryId,
            paymentAccountId = primaryAccount.id,
            amount = amount,
            actualPaidAmount = actualPaid,
            accounts = accountsById,
        )
    }

    private fun buildIncome(
        payload: RecurringPayload,
        primaryAccount: Account,
        meta: TransactionMeta,
        engine: LedgerEngine,
        accountsById: Map<String, Account>,
    ): Transaction {
        val categoryId = payload.categoryAccountId
            ?: throw IllegalArgumentException("收入分类不能为空")
        val category = accountsById[categoryId]
            ?: throw IllegalArgumentException("未找到收入分类，请检查规则或模板配置")
        require(!category.isArchived) { "收入分类「${category.name}」已归档" }
        require(category.type == AccountType.INCOME) { "所选分类「${category.name}」不是收入分类" }

        val amount = payload.amount
        val actualReceived = if (primaryAccount.currency != amount.currency) {
            payload.targetAmount?.takeIf { it.currency == primaryAccount.currency }
                ?: convertOrThrow(amount, primaryAccount.currency, engine.rateProvider, meta.occurredAt)
        } else {
            null
        }

        return engine.buildIncome(
            meta = meta,
            incomeCategoryAccountId = categoryId,
            depositAccountId = primaryAccount.id,
            amount = amount,
            actualReceivedAmount = actualReceived,
            accounts = accountsById,
        )
    }

    private fun buildTransfer(
        payload: RecurringPayload,
        fromAccount: Account,
        meta: TransactionMeta,
        engine: LedgerEngine,
        accountsById: Map<String, Account>,
    ): Transaction {
        val targetId = payload.targetAccountId
            ?: throw IllegalArgumentException("转入账户不能为空")
        val toAccount = accountsById[targetId]
            ?: throw IllegalArgumentException("未找到转入账户，请检查规则或模板配置")
        require(!toAccount.isArchived) { "转入账户「${toAccount.name}」已归档" }

        val paidAmount = if (payload.currency == fromAccount.currency) {
            payload.amount
        } else {
            convertOrThrow(payload.amount, fromAccount.currency, engine.rateProvider, meta.occurredAt)
        }

        return if (fromAccount.currency == toAccount.currency) {
            engine.buildTransfer(
                meta = meta,
                fromAccountId = fromAccount.id,
                toAccountId = toAccount.id,
                amount = paidAmount,
                accounts = accountsById,
            )
        } else {
            val receivedAmount = payload.targetAmount?.takeIf { it.currency == toAccount.currency }
                ?: convertOrThrow(paidAmount, toAccount.currency, engine.rateProvider, meta.occurredAt)
            engine.buildCrossCurrencyTransfer(
                meta = meta,
                fromAccountId = fromAccount.id,
                toAccountId = toAccount.id,
                paidAmount = paidAmount,
                receivedAmount = receivedAmount,
                accounts = accountsById,
            )
        }
    }

    private fun convertOrThrow(
        amount: Money,
        targetCurrency: io.github.kvtttba.droplet.core.model.Currency,
        rateProvider: ExchangeRateProvider,
        capturedAt: Long,
    ): Money {
        val rate = rateProvider.findRate(amount.currency, targetCurrency)?.copy(capturedAt = capturedAt)
            ?: throw IllegalArgumentException(
                "缺少 ${amount.currency.code} → ${targetCurrency.code} 汇率，请先刷新汇率或指定到账金额",
            )
        return rate.convert(amount)
    }
}
