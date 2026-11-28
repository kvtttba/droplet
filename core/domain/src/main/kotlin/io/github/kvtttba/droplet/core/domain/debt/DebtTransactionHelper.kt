package io.github.kvtttba.droplet.core.domain.debt

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DebtProfile
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionSource

/**
 * Pure JVM helper translating counterparty debt operations (lend, borrow, partial/full collect
 * with interest, partial/full repay with interest, and bad-debt write-off) into validated
 * double-entry [Transaction]s via [LedgerEngine].
 */
object DebtTransactionHelper {

    fun buildLendTransaction(
        ledgerEngine: LedgerEngine,
        profile: DebtProfile,
        receivableAccount: Account,
        fromAccount: Account,
        amount: Money,
        occurredAt: Long,
        note: String? = null,
        tagIds: List<String> = emptyList(),
        createdAt: Long = occurredAt,
        updatedAt: Long = occurredAt,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
    ): Transaction {
        validateReceivableAccount(profile, receivableAccount)
        validateFundingAccount(fromAccount, receivableAccount.currency, "付款账户")
        require(fromAccount.id != receivableAccount.id) { "付款账户不能与应收账户相同" }
        require(amount.isPositive) { "借出金额必须大于 0" }
        require(amount.currency == receivableAccount.currency) {
            "借出币种 (${amount.currency.code}) 必须与往来币种 (${receivableAccount.currency.code}) 一致"
        }

        val meta = TransactionMeta(
            occurredAt = occurredAt,
            payee = "借出 · ${profile.name}",
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            source = TransactionSource.MANUAL,
            tagIds = tagIds,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
        return ledgerEngine.buildDebtLend(
            meta = meta,
            receivableAccountId = receivableAccount.id,
            fromAccountId = fromAccount.id,
            amount = amount,
            exchangeRateOverride = exchangeRateOverride,
            rateProviderOverride = rateProviderOverride,
            accounts = mapOf(
                receivableAccount.id to receivableAccount,
                fromAccount.id to fromAccount,
            ),
        )
    }

    fun buildBorrowTransaction(
        ledgerEngine: LedgerEngine,
        profile: DebtProfile,
        payableAccount: Account,
        toAccount: Account,
        amount: Money,
        occurredAt: Long,
        note: String? = null,
        tagIds: List<String> = emptyList(),
        createdAt: Long = occurredAt,
        updatedAt: Long = occurredAt,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
    ): Transaction {
        validatePayableAccount(profile, payableAccount)
        validateFundingAccount(toAccount, payableAccount.currency, "收款账户")
        require(toAccount.id != payableAccount.id) { "收款账户不能与应付账户相同" }
        require(amount.isPositive) { "借入金额必须大于 0" }
        require(amount.currency == payableAccount.currency) {
            "借入币种 (${amount.currency.code}) 必须与往来币种 (${payableAccount.currency.code}) 一致"
        }

        val meta = TransactionMeta(
            occurredAt = occurredAt,
            payee = "借入 · ${profile.name}",
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            source = TransactionSource.MANUAL,
            tagIds = tagIds,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
        return ledgerEngine.buildDebtBorrow(
            meta = meta,
            payableAccountId = payableAccount.id,
            toAccountId = toAccount.id,
            amount = amount,
            exchangeRateOverride = exchangeRateOverride,
            rateProviderOverride = rateProviderOverride,
            accounts = mapOf(
                payableAccount.id to payableAccount,
                toAccount.id to toAccount,
            ),
        )
    }

    fun buildCollectTransaction(
        ledgerEngine: LedgerEngine,
        profile: DebtProfile,
        receivableAccount: Account,
        depositAccount: Account,
        principalAmount: Money,
        interestAmount: Money = Money.zero(principalAmount.currency),
        interestIncomeAccount: Account? = null,
        currentReceivableBalance: Money? = null,
        occurredAt: Long,
        note: String? = null,
        tagIds: List<String> = emptyList(),
        createdAt: Long = occurredAt,
        updatedAt: Long = occurredAt,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
    ): Transaction {
        validateReceivableAccount(profile, receivableAccount)
        validateFundingAccount(depositAccount, receivableAccount.currency, "收款账户")
        require(depositAccount.id != receivableAccount.id) { "收款账户不能与应收账户相同" }
        require(principalAmount.isPositive) { "每次收款需包含大于 0 的本金（可同时附带利息收入）" }
        require(!interestAmount.isNegative) { "利息收入不能为负数" }
        require(principalAmount.currency == receivableAccount.currency) {
            "收回本金币种 (${principalAmount.currency.code}) 必须与往来币种 (${receivableAccount.currency.code}) 一致"
        }
        require(interestAmount.currency == receivableAccount.currency) {
            "利息币种 (${interestAmount.currency.code}) 必须与往来币种 (${receivableAccount.currency.code}) 一致"
        }

        if (currentReceivableBalance != null) {
            require(currentReceivableBalance.currency == receivableAccount.currency) {
                "应收余额币种不一致"
            }
            require(principalAmount <= currentReceivableBalance) {
                "收回本金 ($principalAmount) 不能超过当前应收余额 ($currentReceivableBalance)"
            }
        }

        val accountsMap = linkedMapOf(
            receivableAccount.id to receivableAccount,
            depositAccount.id to depositAccount,
        )
        if (interestAmount.isPositive) {
            val incomeAcct = requireNotNull(interestIncomeAccount) {
                "收取利息时必须选择利息收入分类"
            }
            require(!incomeAcct.isArchived) { "利息收入分类「${incomeAcct.name}」已归档" }
            require(incomeAcct.type == AccountType.INCOME) { "「${incomeAcct.name}」必须为收入分类" }
            accountsMap[incomeAcct.id] = incomeAcct
        }

        val meta = TransactionMeta(
            occurredAt = occurredAt,
            payee = "收款 · ${profile.name}",
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            source = TransactionSource.MANUAL,
            tagIds = tagIds,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
        return ledgerEngine.buildDebtCollect(
            meta = meta,
            receivableAccountId = receivableAccount.id,
            depositAccountId = depositAccount.id,
            principalAmount = principalAmount,
            interestAmount = interestAmount,
            interestIncomeAccountId = interestIncomeAccount?.id,
            exchangeRateOverride = exchangeRateOverride,
            rateProviderOverride = rateProviderOverride,
            accounts = accountsMap,
        )
    }

    fun buildRepayTransaction(
        ledgerEngine: LedgerEngine,
        profile: DebtProfile,
        payableAccount: Account,
        paymentAccount: Account,
        principalAmount: Money,
        interestAmount: Money = Money.zero(principalAmount.currency),
        interestExpenseAccount: Account? = null,
        currentPayableBalance: Money? = null,
        occurredAt: Long,
        note: String? = null,
        tagIds: List<String> = emptyList(),
        createdAt: Long = occurredAt,
        updatedAt: Long = occurredAt,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
    ): Transaction {
        validatePayableAccount(profile, payableAccount)
        validateFundingAccount(paymentAccount, payableAccount.currency, "付款账户")
        require(paymentAccount.id != payableAccount.id) { "付款账户不能与应付账户相同" }
        require(principalAmount.isPositive) { "每次还款需包含大于 0 的本金（可同时附带利息支出）" }
        require(!interestAmount.isNegative) { "利息支出不能为负数" }
        require(principalAmount.currency == payableAccount.currency) {
            "归还本金币种 (${principalAmount.currency.code}) 必须与往来币种 (${payableAccount.currency.code}) 一致"
        }
        require(interestAmount.currency == payableAccount.currency) {
            "利息币种 (${interestAmount.currency.code}) 必须与往来币种 (${payableAccount.currency.code}) 一致"
        }

        if (currentPayableBalance != null) {
            require(currentPayableBalance.currency == payableAccount.currency) {
                "应付余额币种不一致"
            }
            require(principalAmount <= currentPayableBalance) {
                "归还本金 ($principalAmount) 不能超过当前应付余额 ($currentPayableBalance)"
            }
        }

        val accountsMap = linkedMapOf(
            payableAccount.id to payableAccount,
            paymentAccount.id to paymentAccount,
        )
        if (interestAmount.isPositive) {
            val expenseAcct = requireNotNull(interestExpenseAccount) {
                "支付利息时必须选择利息支出分类"
            }
            require(!expenseAcct.isArchived) { "利息支出分类「${expenseAcct.name}」已归档" }
            require(expenseAcct.type == AccountType.EXPENSE) { "「${expenseAcct.name}」必须为支出分类" }
            accountsMap[expenseAcct.id] = expenseAcct
        }

        val meta = TransactionMeta(
            occurredAt = occurredAt,
            payee = "还款 · ${profile.name}",
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            source = TransactionSource.MANUAL,
            tagIds = tagIds,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
        return ledgerEngine.buildDebtRepay(
            meta = meta,
            payableAccountId = payableAccount.id,
            paymentAccountId = paymentAccount.id,
            principalAmount = principalAmount,
            interestAmount = interestAmount,
            interestExpenseAccountId = interestExpenseAccount?.id,
            exchangeRateOverride = exchangeRateOverride,
            rateProviderOverride = rateProviderOverride,
            accounts = accountsMap,
        )
    }

    fun buildBadDebtWriteOffTransaction(
        ledgerEngine: LedgerEngine,
        profile: DebtProfile,
        receivableAccount: Account,
        badDebtExpenseAccount: Account,
        amount: Money,
        currentReceivableBalance: Money? = null,
        occurredAt: Long,
        note: String? = null,
        tagIds: List<String> = emptyList(),
        createdAt: Long = occurredAt,
        updatedAt: Long = occurredAt,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
    ): Transaction {
        validateReceivableAccount(profile, receivableAccount)
        require(!badDebtExpenseAccount.isArchived) { "坏账支出分类「${badDebtExpenseAccount.name}」已归档" }
        require(badDebtExpenseAccount.type == AccountType.EXPENSE) {
            "「${badDebtExpenseAccount.name}」必须为支出分类"
        }
        require(amount.isPositive) { "坏账核销金额必须大于 0" }
        require(amount.currency == receivableAccount.currency) {
            "坏账核销币种 (${amount.currency.code}) 必须与往来币种 (${receivableAccount.currency.code}) 一致"
        }
        if (currentReceivableBalance != null) {
            require(currentReceivableBalance.currency == receivableAccount.currency) {
                "应收余额币种不一致"
            }
            require(amount <= currentReceivableBalance) {
                "坏账核销金额 ($amount) 不能超过当前应收余额 ($currentReceivableBalance)"
            }
        }

        val meta = TransactionMeta(
            occurredAt = occurredAt,
            payee = "坏账核销 · ${profile.name}",
            note = note?.trim()?.takeIf { it.isNotEmpty() } ?: "无法收回欠款转坏账损失",
            source = TransactionSource.MANUAL,
            tagIds = tagIds,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
        return ledgerEngine.buildBadDebtWriteOff(
            meta = meta,
            receivableAccountId = receivableAccount.id,
            badDebtExpenseAccountId = badDebtExpenseAccount.id,
            amount = amount,
            exchangeRateOverride = exchangeRateOverride,
            rateProviderOverride = rateProviderOverride,
            accounts = mapOf(
                receivableAccount.id to receivableAccount,
                badDebtExpenseAccount.id to badDebtExpenseAccount,
            ),
        )
    }

    private fun validateReceivableAccount(profile: DebtProfile, account: Account) {
        require(profile.receivableAccountId == account.id) {
            "账户「${account.name}」与往来人「${profile.name}」的应收科目不匹配"
        }
        require(!account.isArchived) { "应收账户「${account.name}」已归档" }
        require(account.type == AccountType.ASSET && account.subType == AccountSubType.RECEIVABLE) {
            "「${account.name}」必须为应收往来账户"
        }
    }

    private fun validatePayableAccount(profile: DebtProfile, account: Account) {
        require(profile.payableAccountId == account.id) {
            "账户「${account.name}」与往来人「${profile.name}」的应付科目不匹配"
        }
        require(!account.isArchived) { "应付账户「${account.name}」已归档" }
        require(account.type == AccountType.LIABILITY && account.subType == AccountSubType.PAYABLE) {
            "「${account.name}」必须为应付往来账户"
        }
    }

    private fun validateFundingAccount(account: Account, currency: Currency, label: String) {
        require(!account.isArchived) { "${label}「${account.name}」已归档" }
        val isSupported = (account.type == AccountType.ASSET &&
            account.subType != AccountSubType.RECEIVABLE &&
            account.subType != AccountSubType.REIMBURSEMENT &&
            account.subType != AccountSubType.INVEST_HOLDING) ||
            (account.type == AccountType.LIABILITY && account.subType == AccountSubType.CREDIT_CARD)
        require(isSupported) { "${label}「${account.name}」需为日常资金账户或信用账户" }
        require(account.currency == currency) {
            "${label}「${account.name}」币种 (${account.currency.code}) 必须与往来币种 (${currency.code}) 一致"
        }
    }
}
