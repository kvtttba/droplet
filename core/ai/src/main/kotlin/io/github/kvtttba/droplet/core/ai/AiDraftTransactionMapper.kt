package io.github.kvtttba.droplet.core.ai

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AiDraftInputType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionSource
import io.github.kvtttba.droplet.core.model.TransactionStatus

/**
 * Pure domain mapper converting reviewed [AiParsedTransactionCandidate] entries into strictly
 * balanced double-entry [Transaction] objects via [LedgerEngine].
 *
 * Strictly zero floating-point arithmetic: all amounts and cross-currency conversions operate
 * on fixed-point [Money] (`scale = 4`) and [ExchangeRateSnapshot] (`BigDecimal`).
 */
object AiDraftTransactionMapper {

    fun inputTypeToSource(inputType: AiDraftInputType): TransactionSource = when (inputType) {
        AiDraftInputType.IMAGE -> TransactionSource.AI_IMAGE
        AiDraftInputType.VOICE -> TransactionSource.AI_VOICE
        AiDraftInputType.TEXT -> TransactionSource.AI_TEXT
    }

    /**
     * Validates a candidate against live accounts and returns a clear Chinese error message
     * if required fields are missing or incompatible, or `null` when ready to post.
     */
    fun validateCandidate(
        candidate: AiParsedTransactionCandidate,
        accountsById: Map<String, Account>,
        itemIndex: Int? = null,
    ): String? {
        val prefix = itemIndex?.let { "第 ${it + 1} 笔" }.orEmpty()
        if (candidate.amount.amountMinor <= 0L) {
            return "${prefix}金额必须大于 0"
        }

        val primaryAccountId = candidate.matchedAccountId
        val primaryAccount = primaryAccountId?.let { accountsById[it] }
        if (primaryAccount == null) {
            val label = if (candidate.displayType == TransactionDisplayType.TRANSFER) "转出账户" else "资金账户"
            return "${prefix}缺少$label，请先选择账户"
        }
        if (primaryAccount.isArchived) {
            return "${prefix}所选账户「${primaryAccount.name}」已归档，请更换活跃账户"
        }
        if (primaryAccount.type != AccountType.ASSET && primaryAccount.type != AccountType.LIABILITY) {
            return "${prefix}所选账户「${primaryAccount.name}」不是有效的资产或负债账户"
        }

        return when (candidate.displayType) {
            TransactionDisplayType.EXPENSE -> {
                val category = candidate.matchedCategoryId?.let { accountsById[it] }
                when {
                    category == null -> "${prefix}缺少支出分类，请先选择分类"
                    category.isArchived -> "${prefix}所选分类「${category.name}」已归档，请更换分类"
                    category.type != AccountType.EXPENSE || category.subType != AccountSubType.CATEGORY ->
                        "${prefix}所选分类「${category.name}」不是有效支出分类"
                    else -> null
                }
            }

            TransactionDisplayType.INCOME -> {
                val category = candidate.matchedCategoryId?.let { accountsById[it] }
                when {
                    category == null -> "${prefix}缺少收入分类，请先选择分类"
                    category.isArchived -> "${prefix}所选分类「${category.name}」已归档，请更换分类"
                    category.type != AccountType.INCOME || category.subType != AccountSubType.CATEGORY ->
                        "${prefix}所选分类「${category.name}」不是有效收入分类"
                    else -> null
                }
            }

            TransactionDisplayType.TRANSFER -> {
                val targetAccount = candidate.matchedTargetAccountId?.let { accountsById[it] }
                when {
                    targetAccount == null -> "${prefix}转账缺少转入账户，请先选择目标账户"
                    targetAccount.isArchived -> "${prefix}转入账户「${targetAccount.name}」已归档，请更换账户"
                    targetAccount.type != AccountType.ASSET && targetAccount.type != AccountType.LIABILITY ->
                        "${prefix}转入账户「${targetAccount.name}」不是有效的资产或负债账户"
                    targetAccount.id == primaryAccount.id -> "${prefix}转出账户与转入账户不能相同"
                    primaryAccount.currency == targetAccount.currency &&
                        candidate.currency != primaryAccount.currency ->
                        "${prefix}同币种转账币种（${candidate.currency.code}）与账户币种（${primaryAccount.currency.code}）不一致"
                    else -> null
                }
            }

            TransactionDisplayType.REIMBURSE -> {
                val reimburseAccount = findReimbursementAccount(accountsById, candidate.currency)
                if (reimburseAccount == null) {
                    "${prefix}未找到系统待报销账户，请改用「支出」类型"
                } else {
                    null
                }
            }

            TransactionDisplayType.REFUND,
            TransactionDisplayType.DEBT,
            TransactionDisplayType.LOAN_REPAY,
            TransactionDisplayType.INVEST,
            TransactionDisplayType.SPLIT,
            -> "${prefix}暂不支持在草稿箱直接入账「${displayTypeLabel(candidate.displayType)}」类型，请先切换为支出/收入/转账"
        }
    }

    /**
     * Converts a single reviewed [AiParsedTransactionCandidate] into a strictly balanced
     * double-entry [Transaction] using [ledgerEngine].
     */
    fun mapToTransaction(
        candidate: AiParsedTransactionCandidate,
        source: TransactionSource,
        accountsById: Map<String, Account>,
        ledgerEngine: LedgerEngine,
        rateProvider: ExchangeRateProvider = ledgerEngine.rateProvider,
        nowEpochMs: Long = candidate.occurredAtEpochMs,
        itemIndex: Int? = null,
    ): Result<Transaction> = runCatching {
        val validationError = validateCandidate(
            candidate = candidate,
            accountsById = accountsById,
            itemIndex = itemIndex,
        )
        if (validationError != null) {
            throw IllegalArgumentException(validationError)
        }

        val effectiveRates = FallbackAwareRateProvider(
            primary = rateProvider,
            capturedAt = candidate.occurredAtEpochMs,
        )
        val engineWithRates = ledgerEngine.withRateProvider(effectiveRates)

        val meta = TransactionMeta(
            occurredAt = candidate.occurredAtEpochMs,
            status = TransactionStatus.POSTED,
            payee = candidate.payee?.trim()?.takeIf { it.isNotEmpty() },
            note = candidate.note?.trim()?.takeIf { it.isNotEmpty() },
            source = source,
            tagIds = candidate.matchedTagIds.distinct(),
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        )

        val primaryAccount = accountsById.getValue(candidate.matchedAccountId!!)

        when (candidate.displayType) {
            TransactionDisplayType.EXPENSE -> {
                val actualPaid = if (primaryAccount.currency != candidate.currency) {
                    convertPositive(
                        amount = candidate.amount,
                        targetCurrency = primaryAccount.currency,
                        rateProvider = effectiveRates,
                    )
                } else {
                    null
                }
                engineWithRates.buildExpense(
                    meta = meta,
                    expenseCategoryAccountId = candidate.matchedCategoryId!!,
                    paymentAccountId = primaryAccount.id,
                    amount = candidate.amount,
                    actualPaidAmount = actualPaid,
                    accounts = accountsById,
                )
            }

            TransactionDisplayType.INCOME -> {
                val actualReceived = if (primaryAccount.currency != candidate.currency) {
                    convertPositive(
                        amount = candidate.amount,
                        targetCurrency = primaryAccount.currency,
                        rateProvider = effectiveRates,
                    )
                } else {
                    null
                }
                engineWithRates.buildIncome(
                    meta = meta,
                    incomeCategoryAccountId = candidate.matchedCategoryId!!,
                    depositAccountId = primaryAccount.id,
                    amount = candidate.amount,
                    actualReceivedAmount = actualReceived,
                    accounts = accountsById,
                )
            }

            TransactionDisplayType.TRANSFER -> {
                val targetAccount = accountsById.getValue(candidate.matchedTargetAccountId!!)
                if (primaryAccount.currency == targetAccount.currency) {
                    engineWithRates.buildTransfer(
                        meta = meta,
                        fromAccountId = primaryAccount.id,
                        toAccountId = targetAccount.id,
                        amount = candidate.amount,
                        accounts = accountsById,
                    )
                } else {
                    val paidAmount = if (candidate.currency == primaryAccount.currency) {
                        candidate.amount
                    } else {
                        convertPositive(
                            amount = candidate.amount,
                            targetCurrency = primaryAccount.currency,
                            rateProvider = effectiveRates,
                        )
                    }
                    val receivedAmount = if (candidate.currency == targetAccount.currency) {
                        candidate.amount
                    } else {
                        convertPositive(
                            amount = candidate.amount,
                            targetCurrency = targetAccount.currency,
                            rateProvider = effectiveRates,
                        )
                    }
                    engineWithRates.buildCrossCurrencyTransfer(
                        meta = meta,
                        fromAccountId = primaryAccount.id,
                        toAccountId = targetAccount.id,
                        paidAmount = paidAmount,
                        receivedAmount = receivedAmount,
                        accounts = accountsById,
                    )
                }
            }

            TransactionDisplayType.REIMBURSE -> {
                val reimburseAccount = findReimbursementAccount(accountsById, candidate.currency)
                    ?: throw IllegalArgumentException("未找到系统待报销账户")
                val actualPaid = if (primaryAccount.currency != candidate.currency) {
                    convertPositive(
                        amount = candidate.amount,
                        targetCurrency = primaryAccount.currency,
                        rateProvider = effectiveRates,
                    )
                } else {
                    null
                }
                engineWithRates.buildReimbursableAdvance(
                    meta = meta,
                    reimbursementAccountId = reimburseAccount.id,
                    paymentAccountId = primaryAccount.id,
                    amount = candidate.amount,
                    actualPaidAmount = actualPaid,
                    accounts = accountsById,
                )
            }

            else -> throw IllegalArgumentException(
                "暂不支持直接入账「${displayTypeLabel(candidate.displayType)}」类型",
            )
        }
    }

    private fun findReimbursementAccount(
        accountsById: Map<String, Account>,
        preferredCurrency: Currency,
    ): Account? {
        val candidates = accountsById.values.filter {
            !it.isArchived && it.subType == AccountSubType.REIMBURSEMENT
        }
        return candidates.firstOrNull { it.currency == preferredCurrency } ?: candidates.firstOrNull()
    }

    private fun convertPositive(
        amount: Money,
        targetCurrency: Currency,
        rateProvider: ExchangeRateProvider,
    ): Money {
        if (amount.currency == targetCurrency) return amount
        val snapshot = rateProvider.findRate(amount.currency, targetCurrency)
            ?: throw IllegalArgumentException(
                "缺少 ${amount.currency.code} -> ${targetCurrency.code} 汇率，请先刷新汇率或切换同币种账户",
            )
        val converted = snapshot.convert(amount)
        require(converted.isPositive) {
            "折算后的 ${targetCurrency.code} 金额必须大于 0"
        }
        return converted
    }

    private fun displayTypeLabel(type: TransactionDisplayType): String = when (type) {
        TransactionDisplayType.EXPENSE -> "支出"
        TransactionDisplayType.INCOME -> "收入"
        TransactionDisplayType.TRANSFER -> "转账"
        TransactionDisplayType.REIMBURSE -> "待报销"
        TransactionDisplayType.REFUND -> "退款"
        TransactionDisplayType.DEBT -> "借贷"
        TransactionDisplayType.LOAN_REPAY -> "分期还款"
        TransactionDisplayType.INVEST -> "投资"
        TransactionDisplayType.SPLIT -> "多拆分录"
    }

    private class FallbackAwareRateProvider(
        private val primary: ExchangeRateProvider,
        private val capturedAt: Long,
    ) : ExchangeRateProvider {
        override fun findRate(from: Currency, to: Currency): ExchangeRateSnapshot? {
            if (from == to) return ExchangeRateSnapshot.identity(from, capturedAt)
            primary.findRate(from, to)?.let { return it }
            return when (from to to) {
                Currency.USD to Currency.CNY -> ExchangeRateSnapshot.of(
                    Currency.USD,
                    Currency.CNY,
                    "7.20",
                    ExchangeRateSource.USER_OVERRIDE,
                    capturedAt,
                )
                Currency.CNY to Currency.USD -> ExchangeRateSnapshot.of(
                    Currency.USD,
                    Currency.CNY,
                    "7.20",
                    ExchangeRateSource.USER_OVERRIDE,
                    capturedAt,
                ).inverse(capturedAt = capturedAt)
                Currency.HKD to Currency.CNY -> ExchangeRateSnapshot.of(
                    Currency.HKD,
                    Currency.CNY,
                    "0.92",
                    ExchangeRateSource.USER_OVERRIDE,
                    capturedAt,
                )
                Currency.CNY to Currency.HKD -> ExchangeRateSnapshot.of(
                    Currency.HKD,
                    Currency.CNY,
                    "0.92",
                    ExchangeRateSource.USER_OVERRIDE,
                    capturedAt,
                ).inverse(capturedAt = capturedAt)
                Currency.USD to Currency.HKD -> ExchangeRateSnapshot.of(
                    Currency.USD,
                    Currency.HKD,
                    "7.82608696",
                    ExchangeRateSource.USER_OVERRIDE,
                    capturedAt,
                )
                Currency.HKD to Currency.USD -> ExchangeRateSnapshot.of(
                    Currency.USD,
                    Currency.HKD,
                    "7.82608696",
                    ExchangeRateSource.USER_OVERRIDE,
                    capturedAt,
                ).inverse(capturedAt = capturedAt)
                else -> null
            }
        }
    }
}
