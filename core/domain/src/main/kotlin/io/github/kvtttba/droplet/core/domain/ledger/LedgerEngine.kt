package io.github.kvtttba.droplet.core.domain.ledger

import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Posting
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionSource
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.model.UnitPrice
import java.util.UUID

fun interface IdGenerator {
    fun nextId(): String
}

object UuidIdGenerator : IdGenerator {
    override fun nextId(): String = UUID.randomUUID().toString()
}

data class TransactionMeta(
    val id: String? = null,
    val occurredAt: Long,
    val status: TransactionStatus = TransactionStatus.POSTED,
    val payee: String? = null,
    val note: String? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    val refTransactionId: String? = null,
    val reimburseState: ReimburseState? = null,
    val tagIds: List<String> = emptyList(),
    val createdAt: Long = occurredAt,
    val updatedAt: Long = occurredAt,
)

enum class DiscountMode {
    /** Deduct discount directly from the expense line (net expense). */
    NET_EXPENSE,

    /** Record full gross price on expense and offset discount into a rebate/income account. */
    GROSS_WITH_REBATE_INCOME,
}

enum class TransferFeeMode {
    /** Fee is charged on top of transferred amount (sender pays amount + fee, receiver gets amount). */
    SENDER_EXTRA,

    /** Fee is deducted from transferred amount (sender pays amount, receiver gets amount - fee). */
    DEDUCTED_FROM_AMOUNT,
}

/**
 * Pure JVM double-entry ledger engine providing builders for all Droplet accounting scenarios,
 * automatic three-currency `Equity:Trading:{CCY}` hedging, historical base-currency locking,
 * and strict zero-sum validation.
 */
class LedgerEngine(
    val baseCurrency: Currency = Currency.DEFAULT,
    val rateProvider: ExchangeRateProvider = ExchangeRateTable.empty(),
    private val idGenerator: IdGenerator = UuidIdGenerator,
) {

    fun withBaseCurrency(baseCurrency: Currency): LedgerEngine =
        LedgerEngine(baseCurrency = baseCurrency, rateProvider = rateProvider, idGenerator = idGenerator)

    fun withRateProvider(rateProvider: ExchangeRateProvider): LedgerEngine =
        LedgerEngine(baseCurrency = baseCurrency, rateProvider = rateProvider, idGenerator = idGenerator)

    /**
     * Builds an expense transaction with optional discount (net or rebate-income mode),
     * optional fee, and optional cross-currency actual payment amount.
     */
    fun buildExpense(
        meta: TransactionMeta,
        expenseCategoryAccountId: String,
        paymentAccountId: String,
        amount: Money,
        discount: Money? = null,
        discountMode: DiscountMode = DiscountMode.NET_EXPENSE,
        discountIncomeAccountId: String? = null,
        fee: Money? = null,
        feeExpenseAccountId: String? = null,
        actualPaidAmount: Money? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        requirePositive(amount, "Expense amount")
        discount?.let {
            requirePositive(it, "Discount")
            requireSameCurrency(it, amount.currency, "Discount")
        }
        fee?.let {
            requirePositive(it, "Fee")
            require(!feeExpenseAccountId.isNullOrBlank()) {
                "feeExpenseAccountId is required when fee is provided"
            }
        }

        val drafts = mutableListOf<PostingDraft>()
        val netExpenseInExpenseCurrency = if (discount != null) {
            when (discountMode) {
                DiscountMode.NET_EXPENSE -> {
                    require(discount < amount) {
                        "Discount ($discount) must be strictly less than expense amount ($amount) in NET_EXPENSE mode"
                    }
                    val net = amount - discount
                    drafts += PostingDraft(
                        accountId = expenseCategoryAccountId,
                        amount = net,
                        exchangeRateOverride = exchangeRateOverride,
                    )
                    net
                }
                DiscountMode.GROSS_WITH_REBATE_INCOME -> {
                    require(discount <= amount) {
                        "Discount ($discount) must not exceed expense amount ($amount)"
                    }
                    require(!discountIncomeAccountId.isNullOrBlank()) {
                        "discountIncomeAccountId is required in GROSS_WITH_REBATE_INCOME mode"
                    }
                    drafts += PostingDraft(
                        accountId = expenseCategoryAccountId,
                        amount = amount,
                        exchangeRateOverride = exchangeRateOverride,
                    )
                    drafts += PostingDraft(
                        accountId = discountIncomeAccountId,
                        amount = -discount,
                        exchangeRateOverride = exchangeRateOverride,
                    )
                    amount - discount
                }
            }
        } else {
            drafts += PostingDraft(
                accountId = expenseCategoryAccountId,
                amount = amount,
                exchangeRateOverride = exchangeRateOverride,
            )
            amount
        }

        if (actualPaidAmount != null && actualPaidAmount.currency != amount.currency) {
            requirePositive(actualPaidAmount, "Actual paid amount")
            require(netExpenseInExpenseCurrency.isPositive) {
                "Cross-currency expense requires positive net expense after discount"
            }
            val totalPayment = if (fee != null) {
                if (fee.currency == actualPaidAmount.currency) {
                    drafts += PostingDraft(accountId = feeExpenseAccountId!!, amount = fee)
                    actualPaidAmount + fee
                } else {
                    requireSameCurrency(fee, amount.currency, "Fee")
                    drafts += PostingDraft(
                        accountId = feeExpenseAccountId!!,
                        amount = fee,
                        exchangeRateOverride = exchangeRateOverride,
                    )
                    actualPaidAmount
                }
            } else {
                actualPaidAmount
            }
            drafts += PostingDraft(accountId = paymentAccountId, amount = -totalPayment)
        } else {
            val feeInCurrency = fee?.also {
                requireSameCurrency(it, amount.currency, "Fee")
                drafts += PostingDraft(
                    accountId = feeExpenseAccountId!!,
                    amount = it,
                    exchangeRateOverride = exchangeRateOverride,
                )
            }
            val expectedPayment = if (feeInCurrency != null) {
                netExpenseInExpenseCurrency + feeInCurrency
            } else {
                netExpenseInExpenseCurrency
            }
            if (actualPaidAmount != null) {
                require(actualPaidAmount == expectedPayment) {
                    "Same-currency actualPaidAmount ($actualPaidAmount) must equal expected payment ($expectedPayment)"
                }
            }
            if (!expectedPayment.isZero) {
                drafts += PostingDraft(
                    accountId = paymentAccountId,
                    amount = -expectedPayment,
                    exchangeRateOverride = exchangeRateOverride,
                )
            }
        }

        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.EXPENSE,
            drafts = drafts,
            autoHedgeMultiCurrency = true,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds an income transaction with optional deducted fee and optional cross-currency deposit.
     */
    fun buildIncome(
        meta: TransactionMeta,
        incomeCategoryAccountId: String,
        depositAccountId: String,
        amount: Money,
        fee: Money? = null,
        feeExpenseAccountId: String? = null,
        actualReceivedAmount: Money? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        requirePositive(amount, "Income amount")
        fee?.let {
            requirePositive(it, "Fee")
            require(!feeExpenseAccountId.isNullOrBlank()) {
                "feeExpenseAccountId is required when fee is provided"
            }
        }

        val drafts = mutableListOf<PostingDraft>()
        drafts += PostingDraft(
            accountId = incomeCategoryAccountId,
            amount = -amount,
            exchangeRateOverride = exchangeRateOverride,
        )

        if (actualReceivedAmount != null && actualReceivedAmount.currency != amount.currency) {
            requirePositive(actualReceivedAmount, "Actual received amount")
            if (fee != null) {
                if (fee.currency == actualReceivedAmount.currency) {
                    drafts += PostingDraft(accountId = feeExpenseAccountId!!, amount = fee)
                    drafts += PostingDraft(accountId = depositAccountId, amount = actualReceivedAmount)
                } else {
                    requireSameCurrency(fee, amount.currency, "Fee")
                    require(fee < amount) { "Fee ($fee) must be less than income amount ($amount)" }
                    drafts += PostingDraft(
                        accountId = feeExpenseAccountId!!,
                        amount = fee,
                        exchangeRateOverride = exchangeRateOverride,
                    )
                    drafts += PostingDraft(accountId = depositAccountId, amount = actualReceivedAmount)
                }
            } else {
                drafts += PostingDraft(accountId = depositAccountId, amount = actualReceivedAmount)
            }
        } else {
            val netDeposit = if (fee != null) {
                requireSameCurrency(fee, amount.currency, "Fee")
                require(fee < amount) { "Fee ($fee) must be strictly less than income amount ($amount)" }
                drafts += PostingDraft(
                    accountId = feeExpenseAccountId!!,
                    amount = fee,
                    exchangeRateOverride = exchangeRateOverride,
                )
                amount - fee
            } else {
                amount
            }
            if (actualReceivedAmount != null) {
                require(actualReceivedAmount == netDeposit) {
                    "Same-currency actualReceivedAmount ($actualReceivedAmount) must equal net deposit ($netDeposit)"
                }
            }
            drafts += PostingDraft(
                accountId = depositAccountId,
                amount = netDeposit,
                exchangeRateOverride = exchangeRateOverride,
            )
        }

        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.INCOME,
            drafts = drafts,
            autoHedgeMultiCurrency = true,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds a same-currency transfer or credit-card repayment, with optional transfer fee.
     */
    fun buildTransfer(
        meta: TransactionMeta,
        fromAccountId: String,
        toAccountId: String,
        amount: Money,
        fee: Money? = null,
        feeExpenseAccountId: String? = null,
        feeMode: TransferFeeMode = TransferFeeMode.SENDER_EXTRA,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(fromAccountId != toAccountId) {
            "Transfer source and destination accounts must be distinct ('$fromAccountId')"
        }
        requirePositive(amount, "Transfer amount")

        val drafts = mutableListOf<PostingDraft>()
        if (fee != null) {
            requirePositive(fee, "Transfer fee")
            requireSameCurrency(fee, amount.currency, "Transfer fee")
            require(!feeExpenseAccountId.isNullOrBlank()) {
                "feeExpenseAccountId is required when fee is provided"
            }
            when (feeMode) {
                TransferFeeMode.SENDER_EXTRA -> {
                    drafts += PostingDraft(
                        accountId = fromAccountId,
                        amount = -(amount + fee),
                        exchangeRateOverride = exchangeRateOverride,
                    )
                    drafts += PostingDraft(
                        accountId = toAccountId,
                        amount = amount,
                        exchangeRateOverride = exchangeRateOverride,
                    )
                    drafts += PostingDraft(
                        accountId = feeExpenseAccountId,
                        amount = fee,
                        exchangeRateOverride = exchangeRateOverride,
                    )
                }
                TransferFeeMode.DEDUCTED_FROM_AMOUNT -> {
                    require(fee < amount) {
                        "Deducted fee ($fee) must be strictly less than transfer amount ($amount)"
                    }
                    drafts += PostingDraft(
                        accountId = fromAccountId,
                        amount = -amount,
                        exchangeRateOverride = exchangeRateOverride,
                    )
                    drafts += PostingDraft(
                        accountId = toAccountId,
                        amount = amount - fee,
                        exchangeRateOverride = exchangeRateOverride,
                    )
                    drafts += PostingDraft(
                        accountId = feeExpenseAccountId,
                        amount = fee,
                        exchangeRateOverride = exchangeRateOverride,
                    )
                }
            }
        } else {
            drafts += PostingDraft(
                accountId = fromAccountId,
                amount = -amount,
                exchangeRateOverride = exchangeRateOverride,
            )
            drafts += PostingDraft(
                accountId = toAccountId,
                amount = amount,
                exchangeRateOverride = exchangeRateOverride,
            )
        }

        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.TRANSFER,
            drafts = drafts,
            autoHedgeMultiCurrency = false,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds a cross-currency transfer / FX exchange (e.g. CNY -> USD or USD -> HKD).
     * Automatically infers exchange rate from [paidAmount] and [receivedAmount] and inserts
     * implicit `Equity:Trading:{CCY}` balancing lines so every currency sums to zero.
     */
    fun buildCrossCurrencyTransfer(
        meta: TransactionMeta,
        fromAccountId: String,
        toAccountId: String,
        paidAmount: Money,
        receivedAmount: Money,
        fee: Money? = null,
        feeExpenseAccountId: String? = null,
        feeMode: TransferFeeMode = TransferFeeMode.SENDER_EXTRA,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(fromAccountId != toAccountId) {
            "Transfer source and destination accounts must be distinct ('$fromAccountId')"
        }
        requirePositive(paidAmount, "Paid amount")
        requirePositive(receivedAmount, "Received amount")
        require(paidAmount.currency != receivedAmount.currency) {
            "Cross-currency transfer requires distinct currencies, got ${paidAmount.currency}"
        }

        val drafts = mutableListOf<PostingDraft>()
        if (fee != null) {
            requirePositive(fee, "Transfer fee")
            require(!feeExpenseAccountId.isNullOrBlank()) {
                "feeExpenseAccountId is required when fee is provided"
            }
            when (fee.currency) {
                paidAmount.currency -> {
                    when (feeMode) {
                        TransferFeeMode.SENDER_EXTRA -> {
                            drafts += PostingDraft(fromAccountId, -(paidAmount + fee))
                            drafts += PostingDraft(feeExpenseAccountId, fee)
                            drafts += PostingDraft(toAccountId, receivedAmount)
                        }
                        TransferFeeMode.DEDUCTED_FROM_AMOUNT -> {
                            require(fee < paidAmount) {
                                "Deducted fee ($fee) must be strictly less than paidAmount ($paidAmount)"
                            }
                            drafts += PostingDraft(fromAccountId, -paidAmount)
                            drafts += PostingDraft(feeExpenseAccountId, fee)
                            drafts += PostingDraft(toAccountId, receivedAmount)
                        }
                    }
                }
                receivedAmount.currency -> {
                    when (feeMode) {
                        TransferFeeMode.SENDER_EXTRA -> {
                            drafts += PostingDraft(fromAccountId, -paidAmount)
                            drafts += PostingDraft(toAccountId, receivedAmount)
                            drafts += PostingDraft(feeExpenseAccountId, fee)
                        }
                        TransferFeeMode.DEDUCTED_FROM_AMOUNT -> {
                            require(fee < receivedAmount) {
                                "Deducted fee ($fee) must be strictly less than receivedAmount ($receivedAmount)"
                            }
                            drafts += PostingDraft(fromAccountId, -paidAmount)
                            drafts += PostingDraft(toAccountId, receivedAmount - fee)
                            drafts += PostingDraft(feeExpenseAccountId, fee)
                        }
                    }
                }
                else -> throw IllegalArgumentException(
                    "Cross-currency transfer fee currency (${fee.currency}) must match either " +
                        "paid currency (${paidAmount.currency}) or received currency (${receivedAmount.currency})",
                )
            }
        } else {
            drafts += PostingDraft(fromAccountId, -paidAmount)
            drafts += PostingDraft(toAccountId, receivedAmount)
        }

        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.TRANSFER,
            drafts = drafts,
            autoHedgeMultiCurrency = true,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds a full, partial, or multi-stage refund linked to an original expense transaction.
     */
    fun buildRefund(
        meta: TransactionMeta,
        originalTransactionId: String,
        expenseCategoryAccountId: String,
        refundToAccountId: String,
        refundAmount: Money,
        originalExpenseAmount: Money? = null,
        alreadyRefundedAmount: Money? = null,
        expenseCurrencyReductionAmount: Money? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(originalTransactionId.isNotBlank()) {
            "originalTransactionId is required for a refund transaction"
        }
        requirePositive(refundAmount, "Refund amount")

        val expenseReduction = expenseCurrencyReductionAmount ?: refundAmount
        requirePositive(expenseReduction, "Expense reduction amount")

        if (originalExpenseAmount != null) {
            requirePositive(originalExpenseAmount, "Original expense amount")
            requireSameCurrency(originalExpenseAmount, expenseReduction.currency, "Original expense amount")
            val prior = alreadyRefundedAmount ?: Money.zero(originalExpenseAmount.currency)
            require(!prior.isNegative) { "alreadyRefundedAmount must be non-negative, got $prior" }
            requireSameCurrency(prior, originalExpenseAmount.currency, "Already refunded amount")
            require(prior + expenseReduction <= originalExpenseAmount) {
                "Cumulative refund (${prior + expenseReduction}) exceeds original expense ($originalExpenseAmount)"
            }
        }

        val drafts = listOf(
            PostingDraft(
                accountId = refundToAccountId,
                amount = refundAmount,
                exchangeRateOverride = if (refundAmount.currency == expenseReduction.currency) {
                    exchangeRateOverride
                } else {
                    null
                },
            ),
            PostingDraft(
                accountId = expenseCategoryAccountId,
                amount = -expenseReduction,
                exchangeRateOverride = exchangeRateOverride,
            ),
        )

        return commitTransaction(
            meta = meta.copy(refTransactionId = originalTransactionId),
            displayType = TransactionDisplayType.REFUND,
            drafts = drafts,
            autoHedgeMultiCurrency = true,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds a reimbursable advance (recorded as `Asset:Reimbursement`, never inflating personal expense).
     */
    fun buildReimbursableAdvance(
        meta: TransactionMeta,
        reimbursementAccountId: String,
        paymentAccountId: String,
        amount: Money,
        actualPaidAmount: Money? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        requirePositive(amount, "Reimbursable advance amount")
        val paid = actualPaidAmount ?: amount
        requirePositive(paid, "Actual paid amount")
        if (paid.currency == amount.currency) {
            require(paid == amount) {
                "Same-currency actualPaidAmount ($paid) must equal advance amount ($amount)"
            }
        }

        val drafts = listOf(
            PostingDraft(
                accountId = reimbursementAccountId,
                amount = amount,
                exchangeRateOverride = exchangeRateOverride,
            ),
            PostingDraft(
                accountId = paymentAccountId,
                amount = -paid,
                exchangeRateOverride = if (paid.currency == amount.currency) exchangeRateOverride else null,
            ),
        )

        return commitTransaction(
            meta = meta.copy(reimburseState = ReimburseState.PENDING),
            displayType = TransactionDisplayType.REIMBURSE,
            drafts = drafts,
            autoHedgeMultiCurrency = true,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds a reimbursement settlement that clears [settledReimbursementAmount] from `Asset:Reimbursement`,
     * deposits [receivedAmount] into [depositAccountId], and routes any shortfall to [shortfallExpenseAccountId]
     * (or surplus to [surplusIncomeAccountId]).
     */
    fun buildReimbursementSettlement(
        meta: TransactionMeta,
        advanceTransactionId: String,
        reimbursementAccountId: String,
        depositAccountId: String,
        settledReimbursementAmount: Money,
        receivedAmount: Money,
        shortfallExpenseAccountId: String? = null,
        surplusIncomeAccountId: String? = null,
        remainingPendingAfterSettlement: Money = Money.zero(settledReimbursementAmount.currency),
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(advanceTransactionId.isNotBlank()) {
            "advanceTransactionId is required for reimbursement settlement"
        }
        requirePositive(settledReimbursementAmount, "Settled reimbursement amount")
        require(!receivedAmount.isNegative) {
            "Received reimbursement amount must be non-negative, got $receivedAmount"
        }
        requireSameCurrency(receivedAmount, settledReimbursementAmount.currency, "Received amount")
        require(!remainingPendingAfterSettlement.isNegative) {
            "remainingPendingAfterSettlement must be non-negative, got $remainingPendingAfterSettlement"
        }
        requireSameCurrency(
            remainingPendingAfterSettlement,
            settledReimbursementAmount.currency,
            "Remaining pending amount",
        )

        val drafts = mutableListOf<PostingDraft>()
        if (receivedAmount.isPositive) {
            drafts += PostingDraft(
                accountId = depositAccountId,
                amount = receivedAmount,
                exchangeRateOverride = exchangeRateOverride,
            )
        }

        when {
            receivedAmount < settledReimbursementAmount -> {
                require(!shortfallExpenseAccountId.isNullOrBlank()) {
                    "shortfallExpenseAccountId is required when receivedAmount ($receivedAmount) < settled ($settledReimbursementAmount)"
                }
                val shortfall = settledReimbursementAmount - receivedAmount
                drafts += PostingDraft(
                    accountId = shortfallExpenseAccountId,
                    amount = shortfall,
                    exchangeRateOverride = exchangeRateOverride,
                )
            }
            receivedAmount > settledReimbursementAmount -> {
                require(!surplusIncomeAccountId.isNullOrBlank()) {
                    "surplusIncomeAccountId is required when receivedAmount ($receivedAmount) > settled ($settledReimbursementAmount)"
                }
                val surplus = receivedAmount - settledReimbursementAmount
                drafts += PostingDraft(
                    accountId = surplusIncomeAccountId,
                    amount = -surplus,
                    exchangeRateOverride = exchangeRateOverride,
                )
            }
        }

        drafts += PostingDraft(
            accountId = reimbursementAccountId,
            amount = -settledReimbursementAmount,
            exchangeRateOverride = exchangeRateOverride,
        )

        val state = if (remainingPendingAfterSettlement.isZero) {
            ReimburseState.SETTLED
        } else {
            ReimburseState.PARTIAL
        }

        return commitTransaction(
            meta = meta.copy(
                refTransactionId = advanceTransactionId,
                reimburseState = state,
            ),
            displayType = TransactionDisplayType.REIMBURSE,
            drafts = drafts,
            autoHedgeMultiCurrency = false,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds a lending transaction (`Asset:Receivable` +, `fromAccount` -).
     */
    fun buildDebtLend(
        meta: TransactionMeta,
        receivableAccountId: String,
        fromAccountId: String,
        amount: Money,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        requirePositive(amount, "Lend amount")
        val drafts = listOf(
            PostingDraft(receivableAccountId, amount, exchangeRateOverride = exchangeRateOverride),
            PostingDraft(fromAccountId, -amount, exchangeRateOverride = exchangeRateOverride),
        )
        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.DEBT,
            drafts = drafts,
            autoHedgeMultiCurrency = false,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds a borrowing transaction (`toAccount` +, `Liability:Payable` -).
     */
    fun buildDebtBorrow(
        meta: TransactionMeta,
        payableAccountId: String,
        toAccountId: String,
        amount: Money,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        requirePositive(amount, "Borrow amount")
        val drafts = listOf(
            PostingDraft(toAccountId, amount, exchangeRateOverride = exchangeRateOverride),
            PostingDraft(payableAccountId, -amount, exchangeRateOverride = exchangeRateOverride),
        )
        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.DEBT,
            drafts = drafts,
            autoHedgeMultiCurrency = false,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds a partial or full debt collection with optional interest income.
     */
    fun buildDebtCollect(
        meta: TransactionMeta,
        receivableAccountId: String,
        depositAccountId: String,
        principalAmount: Money,
        interestAmount: Money = Money.zero(principalAmount.currency),
        interestIncomeAccountId: String? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(!principalAmount.isNegative) { "Principal amount must be non-negative, got $principalAmount" }
        require(!interestAmount.isNegative) { "Interest amount must be non-negative, got $interestAmount" }
        requireSameCurrency(interestAmount, principalAmount.currency, "Interest amount")
        val totalReceived = principalAmount + interestAmount
        requirePositive(totalReceived, "Total debt collection amount")

        val drafts = mutableListOf<PostingDraft>()
        drafts += PostingDraft(depositAccountId, totalReceived, exchangeRateOverride = exchangeRateOverride)
        if (principalAmount.isPositive) {
            drafts += PostingDraft(receivableAccountId, -principalAmount, exchangeRateOverride = exchangeRateOverride)
        }
        if (interestAmount.isPositive) {
            require(!interestIncomeAccountId.isNullOrBlank()) {
                "interestIncomeAccountId is required when interestAmount is positive"
            }
            drafts += PostingDraft(interestIncomeAccountId, -interestAmount, exchangeRateOverride = exchangeRateOverride)
        }

        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.DEBT,
            drafts = drafts,
            autoHedgeMultiCurrency = false,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds a partial or full repayment of borrowed debt with optional interest expense.
     */
    fun buildDebtRepay(
        meta: TransactionMeta,
        payableAccountId: String,
        paymentAccountId: String,
        principalAmount: Money,
        interestAmount: Money = Money.zero(principalAmount.currency),
        interestExpenseAccountId: String? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(!principalAmount.isNegative) { "Principal amount must be non-negative, got $principalAmount" }
        require(!interestAmount.isNegative) { "Interest amount must be non-negative, got $interestAmount" }
        requireSameCurrency(interestAmount, principalAmount.currency, "Interest amount")
        val totalPaid = principalAmount + interestAmount
        requirePositive(totalPaid, "Total debt repayment amount")

        val drafts = mutableListOf<PostingDraft>()
        if (principalAmount.isPositive) {
            drafts += PostingDraft(payableAccountId, principalAmount, exchangeRateOverride = exchangeRateOverride)
        }
        if (interestAmount.isPositive) {
            require(!interestExpenseAccountId.isNullOrBlank()) {
                "interestExpenseAccountId is required when interestAmount is positive"
            }
            drafts += PostingDraft(interestExpenseAccountId, interestAmount, exchangeRateOverride = exchangeRateOverride)
        }
        drafts += PostingDraft(paymentAccountId, -totalPaid, exchangeRateOverride = exchangeRateOverride)

        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.DEBT,
            drafts = drafts,
            autoHedgeMultiCurrency = false,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds a bad-debt write-off converting uncollectible receivable into bad-debt loss expense.
     */
    fun buildBadDebtWriteOff(
        meta: TransactionMeta,
        receivableAccountId: String,
        badDebtExpenseAccountId: String,
        amount: Money,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        requirePositive(amount, "Bad debt write-off amount")
        val drafts = listOf(
            PostingDraft(badDebtExpenseAccountId, amount, exchangeRateOverride = exchangeRateOverride),
            PostingDraft(receivableAccountId, -amount, exchangeRateOverride = exchangeRateOverride),
        )
        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.DEBT,
            drafts = drafts,
            autoHedgeMultiCurrency = false,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds an installment loan repayment splitting principal (`Liability:Loan` +) and
     * interest/fee (`Expense:Interest` +) against payment account (`-`).
     */
    fun buildLoanRepayment(
        meta: TransactionMeta,
        loanLiabilityAccountId: String,
        paymentAccountId: String,
        principalAmount: Money,
        interestOrFeeAmount: Money = Money.zero(principalAmount.currency),
        interestExpenseAccountId: String? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(!principalAmount.isNegative) { "Principal amount must be non-negative, got $principalAmount" }
        require(!interestOrFeeAmount.isNegative) {
            "Interest/fee amount must be non-negative, got $interestOrFeeAmount"
        }
        requireSameCurrency(interestOrFeeAmount, principalAmount.currency, "Interest/fee amount")
        val totalPayment = principalAmount + interestOrFeeAmount
        requirePositive(totalPayment, "Total loan repayment amount")

        val drafts = mutableListOf<PostingDraft>()
        if (principalAmount.isPositive) {
            drafts += PostingDraft(loanLiabilityAccountId, principalAmount, exchangeRateOverride = exchangeRateOverride)
        }
        if (interestOrFeeAmount.isPositive) {
            require(!interestExpenseAccountId.isNullOrBlank()) {
                "interestExpenseAccountId is required when interestOrFeeAmount is positive"
            }
            drafts += PostingDraft(interestExpenseAccountId, interestOrFeeAmount, exchangeRateOverride = exchangeRateOverride)
        }
        drafts += PostingDraft(paymentAccountId, -totalPayment, exchangeRateOverride = exchangeRateOverride)

        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.LOAN_REPAY,
            drafts = drafts,
            autoHedgeMultiCurrency = false,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds an investment buy / DCA entry linking holding quantity & unit price with cash account.
     */
    fun buildInvestmentBuy(
        meta: TransactionMeta,
        holdingAccountId: String,
        cashAccountId: String,
        investmentAssetId: String,
        quantity: Quantity,
        unitPrice: UnitPrice,
        currency: Currency,
        totalAmount: Money? = null,
        fee: Money? = null,
        feeExpenseAccountId: String? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(investmentAssetId.isNotBlank()) { "investmentAssetId must not be blank" }
        require(quantity.isPositive) { "Buy quantity must be strictly positive, got $quantity" }
        require(unitPrice.isPositive) { "Buy unitPrice must be strictly positive, got $unitPrice" }

        val tradeAmount = totalAmount ?: Money.fromBigDecimal(
            quantity.toBigDecimal().multiply(unitPrice.toBigDecimal()),
            currency,
        )
        requirePositive(tradeAmount, "Investment buy amount")
        requireSameCurrency(tradeAmount, currency, "Investment buy amount")

        val drafts = mutableListOf<PostingDraft>()
        drafts += PostingDraft(
            accountId = holdingAccountId,
            amount = tradeAmount,
            exchangeRateOverride = exchangeRateOverride,
            investmentAssetId = investmentAssetId,
            quantity = quantity,
            unitPrice = unitPrice,
        )

        val totalCashOut = if (fee != null) {
            requirePositive(fee, "Investment buy fee")
            requireSameCurrency(fee, currency, "Investment buy fee")
            require(!feeExpenseAccountId.isNullOrBlank()) {
                "feeExpenseAccountId is required when fee is provided"
            }
            drafts += PostingDraft(feeExpenseAccountId, fee, exchangeRateOverride = exchangeRateOverride)
            tradeAmount + fee
        } else {
            tradeAmount
        }

        drafts += PostingDraft(cashAccountId, -totalCashOut, exchangeRateOverride = exchangeRateOverride)

        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.INVEST,
            drafts = drafts,
            autoHedgeMultiCurrency = false,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds an investment sell entry (recording negative [quantity] on holding), optionally
     * separating realized gain/loss when [costBasis] and [realizedPnLAccountId] are supplied.
     */
    fun buildInvestmentSell(
        meta: TransactionMeta,
        holdingAccountId: String,
        cashAccountId: String,
        investmentAssetId: String,
        quantity: Quantity,
        unitPrice: UnitPrice,
        currency: Currency,
        grossProceeds: Money? = null,
        fee: Money? = null,
        feeExpenseAccountId: String? = null,
        costBasis: Money? = null,
        realizedPnLAccountId: String? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(investmentAssetId.isNotBlank()) { "investmentAssetId must not be blank" }
        require(quantity.isPositive) { "Sell quantity magnitude must be strictly positive, got $quantity" }
        require(unitPrice.isPositive) { "Sell unitPrice must be strictly positive, got $unitPrice" }

        val proceeds = grossProceeds ?: Money.fromBigDecimal(
            quantity.toBigDecimal().multiply(unitPrice.toBigDecimal()),
            currency,
        )
        requirePositive(proceeds, "Investment sell proceeds")
        requireSameCurrency(proceeds, currency, "Investment sell proceeds")

        val drafts = mutableListOf<PostingDraft>()
        val netCash = if (fee != null) {
            requirePositive(fee, "Investment sell fee")
            requireSameCurrency(fee, currency, "Investment sell fee")
            require(!feeExpenseAccountId.isNullOrBlank()) {
                "feeExpenseAccountId is required when fee is provided"
            }
            drafts += PostingDraft(feeExpenseAccountId, fee, exchangeRateOverride = exchangeRateOverride)
            proceeds - fee
        } else {
            proceeds
        }

        if (!netCash.isZero) {
            drafts += PostingDraft(cashAccountId, netCash, exchangeRateOverride = exchangeRateOverride)
        }

        if (costBasis != null) {
            requirePositive(costBasis, "Cost basis")
            requireSameCurrency(costBasis, currency, "Cost basis")
            drafts += PostingDraft(
                accountId = holdingAccountId,
                amount = -costBasis,
                exchangeRateOverride = exchangeRateOverride,
                investmentAssetId = investmentAssetId,
                quantity = -quantity,
                unitPrice = unitPrice,
            )
            // Gain: proceeds > costBasis -> realizedPnL = costBasis - proceeds < 0 (credit Income)
            // Loss: proceeds < costBasis -> realizedPnL = costBasis - proceeds > 0 (debit Expense)
            val pnlAmount = costBasis - proceeds
            if (!pnlAmount.isZero) {
                require(!realizedPnLAccountId.isNullOrBlank()) {
                    "realizedPnLAccountId is required when costBasis ($costBasis) differs from proceeds ($proceeds)"
                }
                drafts += PostingDraft(
                    accountId = realizedPnLAccountId,
                    amount = pnlAmount,
                    exchangeRateOverride = exchangeRateOverride,
                    investmentAssetId = investmentAssetId,
                )
            }
        } else {
            drafts += PostingDraft(
                accountId = holdingAccountId,
                amount = -proceeds,
                exchangeRateOverride = exchangeRateOverride,
                investmentAssetId = investmentAssetId,
                quantity = -quantity,
                unitPrice = unitPrice,
            )
        }

        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.INVEST,
            drafts = drafts,
            autoHedgeMultiCurrency = false,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds a cash dividend transaction with optional withholding tax/fee.
     */
    fun buildInvestmentDividend(
        meta: TransactionMeta,
        cashAccountId: String,
        dividendIncomeAccountId: String,
        investmentAssetId: String,
        amount: Money,
        taxOrFee: Money? = null,
        taxExpenseAccountId: String? = null,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(investmentAssetId.isNotBlank()) { "investmentAssetId must not be blank" }
        requirePositive(amount, "Dividend amount")

        val drafts = mutableListOf<PostingDraft>()
        val netCash = if (taxOrFee != null) {
            requirePositive(taxOrFee, "Dividend tax/fee")
            requireSameCurrency(taxOrFee, amount.currency, "Dividend tax/fee")
            require(taxOrFee < amount) {
                "Dividend tax/fee ($taxOrFee) must be strictly less than gross dividend ($amount)"
            }
            require(!taxExpenseAccountId.isNullOrBlank()) {
                "taxExpenseAccountId is required when taxOrFee is provided"
            }
            drafts += PostingDraft(taxExpenseAccountId, taxOrFee, exchangeRateOverride = exchangeRateOverride)
            amount - taxOrFee
        } else {
            amount
        }

        drafts += PostingDraft(cashAccountId, netCash, exchangeRateOverride = exchangeRateOverride)
        drafts += PostingDraft(
            accountId = dividendIncomeAccountId,
            amount = -amount,
            exchangeRateOverride = exchangeRateOverride,
            investmentAssetId = investmentAssetId,
        )

        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.INVEST,
            drafts = drafts,
            autoHedgeMultiCurrency = false,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Builds an opening balance entry for an ASSET or LIABILITY account balanced against
     * `Equity:OpeningBalance:{CCY}`.
     */
    fun buildOpeningBalance(
        meta: TransactionMeta,
        accountId: String,
        accountType: AccountType,
        balance: Money,
        exchangeRateOverride: ExchangeRateSnapshot? = null,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction {
        require(accountType == AccountType.ASSET || accountType == AccountType.LIABILITY) {
            "Opening balance only applies to ASSET or LIABILITY accounts, got $accountType"
        }
        requirePositive(balance, "Opening balance")

        val signedAccountAmount = if (accountType == AccountType.ASSET) balance else -balance
        val openingEquityId = SystemAccounts.openingBalanceAccountId(balance.currency)
        val drafts = listOf(
            PostingDraft(accountId, signedAccountAmount, exchangeRateOverride = exchangeRateOverride),
            PostingDraft(openingEquityId, -signedAccountAmount, exchangeRateOverride = exchangeRateOverride),
        )

        return commitTransaction(
            meta = meta,
            displayType = TransactionDisplayType.SPLIT,
            drafts = drafts,
            autoHedgeMultiCurrency = false,
            rateProviderOverride = rateProviderOverride,
            accounts = accounts,
        )
    }

    /**
     * Direct pass-through builder for Professional Split Mode: supports arbitrary multi-line
     * single-currency or multi-currency postings with full validation and optional Trading hedging.
     */
    fun buildCustomSplit(
        meta: TransactionMeta,
        postings: List<PostingDraft>,
        displayType: TransactionDisplayType = TransactionDisplayType.SPLIT,
        autoHedgeMultiCurrency: Boolean = true,
        rateProviderOverride: ExchangeRateProvider? = null,
        accounts: Map<String, Account>? = null,
    ): Transaction = commitTransaction(
        meta = meta,
        displayType = displayType,
        drafts = postings,
        autoHedgeMultiCurrency = autoHedgeMultiCurrency,
        rateProviderOverride = rateProviderOverride,
        accounts = accounts,
    )

    private fun commitTransaction(
        meta: TransactionMeta,
        displayType: TransactionDisplayType,
        drafts: List<PostingDraft>,
        autoHedgeMultiCurrency: Boolean,
        rateProviderOverride: ExchangeRateProvider?,
        accounts: Map<String, Account>?,
    ): Transaction {
        val txId = meta.id ?: idGenerator.nextId()
        val effectiveRates = rateProviderOverride ?: rateProvider

        val hedged = TradingAutoHedger.hedgeAndLock(
            drafts = drafts,
            baseCurrency = baseCurrency,
            rateProvider = effectiveRates,
            capturedAt = meta.occurredAt,
            autoHedgeMultiCurrency = autoHedgeMultiCurrency,
        )

        val postings = hedged.postings.map { locked ->
            Posting(
                id = idGenerator.nextId(),
                transactionId = txId,
                accountId = locked.draft.accountId,
                currency = locked.draft.amount.currency,
                amountMinor = locked.draft.amount.amountMinor,
                baseCurrency = locked.baseCurrency,
                baseAmountMinor = locked.baseAmountMinor,
                exchangeRateSnapshot = locked.exchangeRateSnapshot,
                investmentAssetId = locked.draft.investmentAssetId,
                quantity = locked.draft.quantity,
                unitPrice = locked.draft.unitPrice,
                isSystemBalancing = locked.draft.isSystemBalancing,
            )
        }

        val transaction = Transaction(
            id = txId,
            occurredAt = meta.occurredAt,
            displayType = displayType,
            status = meta.status,
            payee = meta.payee,
            note = meta.note,
            source = meta.source,
            refTransactionId = meta.refTransactionId,
            reimburseState = meta.reimburseState,
            tagIds = meta.tagIds,
            postings = postings,
            createdAt = meta.createdAt,
            updatedAt = meta.updatedAt,
        )

        LedgerValidator.requireValid(transaction, accounts = accounts)
        return transaction
    }

    private fun requirePositive(money: Money, label: String) {
        require(money.isPositive) { "$label must be strictly positive, got $money" }
    }

    private fun requireSameCurrency(money: Money, expected: Currency, label: String) {
        require(money.currency == expected) {
            "$label currency (${money.currency}) must match expected currency ($expected)"
        }
    }
}
