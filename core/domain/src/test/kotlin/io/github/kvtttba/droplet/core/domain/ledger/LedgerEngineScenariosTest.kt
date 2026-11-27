package io.github.kvtttba.droplet.core.domain.ledger

import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionSource
import io.github.kvtttba.droplet.core.model.UnitPrice
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerEngineScenariosTest {

    private val seq = AtomicLong(1L)
    private val deterministicIds = IdGenerator { "id-${seq.getAndIncrement()}" }

    private val defaultRates = ExchangeRateTable.of(
        ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.25", capturedAt = 1_700_000_000_000L),
        ExchangeRateSnapshot.of(Currency.HKD, Currency.CNY, "0.93", capturedAt = 1_700_000_000_000L),
    )

    private val engine = LedgerEngine(
        baseCurrency = Currency.CNY,
        rateProvider = defaultRates,
        idGenerator = deterministicIds,
    )

    private val sampleAccounts: Map<String, Account> = (
        listOf(
            Account("acc:debit:icbc", "工行储蓄卡", AccountType.ASSET, AccountSubType.DEBIT_CARD, currency = Currency.CNY),
            Account("acc:credit:cmb", "招行信用卡", AccountType.LIABILITY, AccountSubType.CREDIT_CARD, currency = Currency.CNY, creditLimitMinor = 50000_0000L, billingDay = 5, dueDay = 23),
            Account("acc:credit:us", "美卡", AccountType.LIABILITY, AccountSubType.CREDIT_CARD, currency = Currency.USD, creditLimitMinor = 10000_0000L),
            Account("acc:invest:us-cash", "美股现金", AccountType.ASSET, AccountSubType.INVEST_CASH, currency = Currency.USD),
            Account("acc:invest:hk-cash", "港股现金", AccountType.ASSET, AccountSubType.INVEST_CASH, currency = Currency.HKD),
            Account("acc:invest:aapl", "AAPL持仓", AccountType.ASSET, AccountSubType.INVEST_HOLDING, currency = Currency.USD),
            Account("acc:invest:btc", "BTC持仓", AccountType.ASSET, AccountSubType.INVEST_HOLDING, currency = Currency.USD),
            Account("acc:reimburse", "待报销", AccountType.ASSET, AccountSubType.REIMBURSEMENT, currency = Currency.CNY),
            Account("acc:loan:mortgage", "房贷", AccountType.LIABILITY, AccountSubType.LOAN, currency = Currency.CNY),
            Account("acc:debt:zhangsan", "借出:张三", AccountType.ASSET, AccountSubType.RECEIVABLE, currency = Currency.CNY),
            Account("acc:debt:lisi", "借入:李四", AccountType.LIABILITY, AccountSubType.PAYABLE, currency = Currency.CNY),
            Account("exp:food:lunch", "餐饮:午餐", AccountType.EXPENSE, AccountSubType.CATEGORY, currency = Currency.CNY),
            Account("exp:shopping", "购物", AccountType.EXPENSE, AccountSubType.CATEGORY, currency = Currency.CNY),
            Account("exp:travel", "差旅", AccountType.EXPENSE, AccountSubType.CATEGORY, currency = Currency.CNY),
            Account("exp:fee", "手续费", AccountType.EXPENSE, AccountSubType.CATEGORY, currency = Currency.CNY),
            Account("exp:interest", "利息手续费", AccountType.EXPENSE, AccountSubType.CATEGORY, currency = Currency.CNY),
            Account("exp:bad-debt", "坏账损失", AccountType.EXPENSE, AccountSubType.CATEGORY, currency = Currency.CNY),
            Account("exp:tax", "税费", AccountType.EXPENSE, AccountSubType.CATEGORY, currency = Currency.CNY),
            Account("inc:salary", "工资", AccountType.INCOME, AccountSubType.CATEGORY, currency = Currency.CNY),
            Account("inc:rebate", "优惠返现", AccountType.INCOME, AccountSubType.CATEGORY, currency = Currency.CNY),
            Account("inc:interest", "利息收入", AccountType.INCOME, AccountSubType.CATEGORY, currency = Currency.CNY),
            Account("inc:dividend", "投资分红", AccountType.INCOME, AccountSubType.CATEGORY, currency = Currency.CNY),
            Account("inc:invest-pnl", "投资收益", AccountType.INCOME, AccountSubType.CATEGORY, currency = Currency.CNY),
            Account("inc:reimburse-surplus", "报销补贴", AccountType.INCOME, AccountSubType.CATEGORY, currency = Currency.CNY),
        ) + SystemAccounts.allSystemEquityAccounts()
    ).associateBy { it.id }

    private fun meta(occurredAt: Long = 1_700_000_000_000L, payee: String? = null, note: String? = null) =
        TransactionMeta(occurredAt = occurredAt, payee = payee, note = note)

    private fun assertZeroSumPerCurrency(tx: Transaction) {
        assertEquals(LedgerValidationResult.Valid, LedgerValidator.validate(tx, sampleAccounts))
        for (ccy in tx.currencies) {
            val sum = tx.postings.filter { it.currency == ccy }.sumOf { it.amountMinor }
            assertEquals("Currency ${ccy.code} must sum to zero", 0L, sum)
        }
    }

    @Test
    fun expense_normalAndDiscountNetAndRebateAndFeeAndCrossCurrency() {
        // 1. 普通消费 ¥38.50 餐饮
        val lunch = engine.buildExpense(
            meta = meta(payee = "食堂"),
            expenseCategoryAccountId = "exp:food:lunch",
            paymentAccountId = "acc:credit:cmb",
            amount = Money.fromDecimalString("38.50", Currency.CNY),
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(lunch)
        assertEquals(TransactionDisplayType.EXPENSE, lunch.displayType)
        assertEquals(38_5000L, lunch.postings.first { it.accountId == "exp:food:lunch" }.amountMinor)
        assertEquals(-38_5000L, lunch.postings.first { it.accountId == "acc:credit:cmb" }.amountMinor)

        // 2. 满减 ¥100 - ¥15 = ¥85（默认净支出模式）
        val netDiscount = engine.buildExpense(
            meta = meta(),
            expenseCategoryAccountId = "exp:shopping",
            paymentAccountId = "acc:debit:icbc",
            amount = Money.fromDecimalString("100.00", Currency.CNY),
            discount = Money.fromDecimalString("15.00", Currency.CNY),
            discountMode = DiscountMode.NET_EXPENSE,
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(netDiscount)
        assertEquals(2, netDiscount.postings.size)
        assertEquals(85_0000L, netDiscount.postings.first { it.accountId == "exp:shopping" }.amountMinor)
        assertEquals(-85_0000L, netDiscount.postings.first { it.accountId == "acc:debit:icbc" }.amountMinor)

        // 3. 原价 ¥100 + 返现 ¥15 + 手续费 ¥2 = 实付 ¥87（返现收入模式）
        val rebateWithFee = engine.buildExpense(
            meta = meta(),
            expenseCategoryAccountId = "exp:shopping",
            paymentAccountId = "acc:debit:icbc",
            amount = Money.fromDecimalString("100.00", Currency.CNY),
            discount = Money.fromDecimalString("15.00", Currency.CNY),
            discountMode = DiscountMode.GROSS_WITH_REBATE_INCOME,
            discountIncomeAccountId = "inc:rebate",
            fee = Money.fromDecimalString("2.00", Currency.CNY),
            feeExpenseAccountId = "exp:fee",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(rebateWithFee)
        assertEquals(4, rebateWithFee.postings.size)
        assertEquals(100_0000L, rebateWithFee.postings.first { it.accountId == "exp:shopping" }.amountMinor)
        assertEquals(-15_0000L, rebateWithFee.postings.first { it.accountId == "inc:rebate" }.amountMinor)
        assertEquals(2_0000L, rebateWithFee.postings.first { it.accountId == "exp:fee" }.amountMinor)
        assertEquals(-87_0000L, rebateWithFee.postings.first { it.accountId == "acc:debit:icbc" }.amountMinor)

        // 4. 100% 全额券抵扣（实付 0 不产生零金额分录）
        val fullCoupon = engine.buildExpense(
            meta = meta(),
            expenseCategoryAccountId = "exp:shopping",
            paymentAccountId = "acc:debit:icbc",
            amount = Money.fromDecimalString("50.00", Currency.CNY),
            discount = Money.fromDecimalString("50.00", Currency.CNY),
            discountMode = DiscountMode.GROSS_WITH_REBATE_INCOME,
            discountIncomeAccountId = "inc:rebate",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(fullCoupon)
        assertEquals(2, fullCoupon.postings.size)

        // 5. 跨币种消费：HK$200 餐饮刷 CNY 招行卡入账 ¥186.00 + 外币交易费 ¥2.79
        val crossExpense = engine.buildExpense(
            meta = meta(payee = "茶餐厅"),
            expenseCategoryAccountId = "exp:food:lunch",
            paymentAccountId = "acc:credit:cmb",
            amount = Money.fromDecimalString("200.00", Currency.HKD),
            actualPaidAmount = Money.fromDecimalString("186.00", Currency.CNY),
            fee = Money.fromDecimalString("2.79", Currency.CNY),
            feeExpenseAccountId = "exp:fee",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(crossExpense)
        assertTrue(crossExpense.isMultiCurrency)
        assertEquals(2, crossExpense.systemBalancingPostings.size)
        val foodPosting = crossExpense.postings.first { it.accountId == "exp:food:lunch" }
        assertEquals(Currency.HKD, foodPosting.currency)
        assertEquals(Money.fromDecimalString("186.00", Currency.CNY), foodPosting.baseMoney)
        assertEquals("0.93", foodPosting.exchangeRateSnapshot.rateDecimal)
        assertEquals(
            Money.fromDecimalString("-188.79", Currency.CNY),
            crossExpense.postings.first { it.accountId == "acc:credit:cmb" }.money,
        )
    }

    @Test
    fun income_sameCurrencyWithFeeAndCrossCurrencyDeposit() {
        val salary = engine.buildIncome(
            meta = meta(payee = "公司"),
            incomeCategoryAccountId = "inc:salary",
            depositAccountId = "acc:debit:icbc",
            amount = Money.fromDecimalString("20000.00", Currency.CNY),
            fee = Money.fromDecimalString("10.00", Currency.CNY),
            feeExpenseAccountId = "exp:fee",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(salary)
        assertEquals(-20000_0000L, salary.postings.first { it.accountId == "inc:salary" }.amountMinor)
        assertEquals(19990_0000L, salary.postings.first { it.accountId == "acc:debit:icbc" }.amountMinor)
        assertEquals(10_0000L, salary.postings.first { it.accountId == "exp:fee" }.amountMinor)

        // USD 收入 $1000 直接入账 CNY 银行卡 ¥7220（自动反推汇率 7.22）
        val fxIncome = engine.buildIncome(
            meta = meta(),
            incomeCategoryAccountId = "inc:salary",
            depositAccountId = "acc:debit:icbc",
            amount = Money.fromDecimalString("1000.00", Currency.USD),
            actualReceivedAmount = Money.fromDecimalString("7220.00", Currency.CNY),
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(fxIncome)
        val incPosting = fxIncome.postings.first { it.accountId == "inc:salary" }
        assertEquals("7.22", incPosting.exchangeRateSnapshot.rateDecimal)
        assertEquals(Money.fromDecimalString("-7220.00", Currency.CNY), incPosting.baseMoney)
    }

    @Test
    fun transfer_sameCurrencyAndCrossCurrencyFxWithFees() {
        // 1. 信用卡还款 ¥3000（无手续费）
        val repay = engine.buildTransfer(
            meta = meta(),
            fromAccountId = "acc:debit:icbc",
            toAccountId = "acc:credit:cmb",
            amount = Money.fromDecimalString("3000.00", Currency.CNY),
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(repay)
        assertFalse(repay.isMultiCurrency)

        // 2. 同币种转账含手续费（两种收费模式）
        val extraFee = engine.buildTransfer(
            meta = meta(),
            fromAccountId = "acc:debit:icbc",
            toAccountId = "acc:credit:cmb",
            amount = Money.fromDecimalString("1000.00", Currency.CNY),
            fee = Money.fromDecimalString("2.00", Currency.CNY),
            feeExpenseAccountId = "exp:fee",
            feeMode = TransferFeeMode.SENDER_EXTRA,
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(extraFee)
        assertEquals(-1002_0000L, extraFee.postings.first { it.accountId == "acc:debit:icbc" }.amountMinor)
        assertEquals(1000_0000L, extraFee.postings.first { it.accountId == "acc:credit:cmb" }.amountMinor)

        val deductedFee = engine.buildTransfer(
            meta = meta(),
            fromAccountId = "acc:debit:icbc",
            toAccountId = "acc:credit:cmb",
            amount = Money.fromDecimalString("1000.00", Currency.CNY),
            fee = Money.fromDecimalString("2.00", Currency.CNY),
            feeExpenseAccountId = "exp:fee",
            feeMode = TransferFeeMode.DEDUCTED_FROM_AMOUNT,
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(deductedFee)
        assertEquals(-1000_0000L, deductedFee.postings.first { it.accountId == "acc:debit:icbc" }.amountMinor)
        assertEquals(998_0000L, deductedFee.postings.first { it.accountId == "acc:credit:cmb" }.amountMinor)

        // 3. 跨币种换汇：工行 ¥7250 换 $1000 进美股现金户
        val fx = engine.buildCrossCurrencyTransfer(
            meta = meta(),
            fromAccountId = "acc:debit:icbc",
            toAccountId = "acc:invest:us-cash",
            paidAmount = Money.fromDecimalString("7250.00", Currency.CNY),
            receivedAmount = Money.fromDecimalString("1000.00", Currency.USD),
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(fx)
        assertEquals(4, fx.postings.size)
        assertEquals(2, fx.businessPostings.size)
        assertEquals(2, fx.systemBalancingPostings.size)
        assertEquals(
            "7.25",
            fx.postings.first { it.accountId == "acc:invest:us-cash" }.exchangeRateSnapshot.rateDecimal,
        )

        // 4. 跨币种跨境汇款含汇款手续费（¥50 CNY 额外付，不污染 7.25 成交汇率）
        val fxWithCnyFee = engine.buildCrossCurrencyTransfer(
            meta = meta(),
            fromAccountId = "acc:debit:icbc",
            toAccountId = "acc:invest:us-cash",
            paidAmount = Money.fromDecimalString("7250.00", Currency.CNY),
            receivedAmount = Money.fromDecimalString("1000.00", Currency.USD),
            fee = Money.fromDecimalString("50.00", Currency.CNY),
            feeExpenseAccountId = "exp:fee",
            feeMode = TransferFeeMode.SENDER_EXTRA,
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(fxWithCnyFee)
        assertEquals(-7300_0000L, fxWithCnyFee.postings.first { it.accountId == "acc:debit:icbc" }.amountMinor)
        assertEquals(
            "7.25",
            fxWithCnyFee.postings.first { it.accountId == "acc:invest:us-cash" }.exchangeRateSnapshot.rateDecimal,
        )

        // 5. 跨币种换汇（USD -> HKD，入账扣 HK$20 手续费）
        val usdToHkd = engine.buildCrossCurrencyTransfer(
            meta = meta(),
            fromAccountId = "acc:invest:us-cash",
            toAccountId = "acc:invest:hk-cash",
            paidAmount = Money.fromDecimalString("100.00", Currency.USD),
            receivedAmount = Money.fromDecimalString("780.00", Currency.HKD),
            fee = Money.fromDecimalString("20.00", Currency.HKD),
            feeExpenseAccountId = "exp:fee",
            feeMode = TransferFeeMode.DEDUCTED_FROM_AMOUNT,
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(usdToHkd)
        assertEquals(760_0000L, usdToHkd.postings.first { it.accountId == "acc:invest:hk-cash" }.amountMinor)
    }

    @Test
    fun refund_supportsMultiplePartialRefundsAndPreventsOverRefund() {
        val original = engine.buildExpense(
            meta = meta(occurredAt = 1000L, payee = "商城"),
            expenseCategoryAccountId = "exp:shopping",
            paymentAccountId = "acc:debit:icbc",
            amount = Money.fromDecimalString("200.00", Currency.CNY),
            accounts = sampleAccounts,
        )
        val refund1 = engine.buildRefund(
            meta = meta(occurredAt = 2000L),
            originalTransactionId = original.id,
            expenseCategoryAccountId = "exp:shopping",
            refundToAccountId = "acc:debit:icbc",
            refundAmount = Money.fromDecimalString("50.00", Currency.CNY),
            originalExpenseAmount = Money.fromDecimalString("200.00", Currency.CNY),
            alreadyRefundedAmount = Money.zero(Currency.CNY),
            accounts = sampleAccounts,
        )
        val refund2 = engine.buildRefund(
            meta = meta(occurredAt = 3000L),
            originalTransactionId = original.id,
            expenseCategoryAccountId = "exp:shopping",
            refundToAccountId = "acc:debit:icbc",
            refundAmount = Money.fromDecimalString("80.00", Currency.CNY),
            originalExpenseAmount = Money.fromDecimalString("200.00", Currency.CNY),
            alreadyRefundedAmount = Money.fromDecimalString("50.00", Currency.CNY),
            accounts = sampleAccounts,
        )

        assertZeroSumPerCurrency(refund1)
        assertZeroSumPerCurrency(refund2)
        assertEquals(original.id, refund1.refTransactionId)
        assertEquals(TransactionDisplayType.REFUND, refund1.displayType)

        val net = LedgerAggregator.netExpenseAfterRefunds(
            originalExpense = original,
            refunds = listOf(refund1, refund2),
            expenseAccountId = "exp:shopping",
        )
        assertEquals(Money.fromDecimalString("70.00", Currency.CNY), net.originalCurrencyNet)
        assertEquals(Money.fromDecimalString("70.00", Currency.CNY), net.baseCurrencyNet)
        assertEquals(2, net.refundCount)

        // Third refund of ¥70.01 must fail over-refund guard
        val overRefund = runCatching {
            engine.buildRefund(
                meta = meta(occurredAt = 4000L),
                originalTransactionId = original.id,
                expenseCategoryAccountId = "exp:shopping",
                refundToAccountId = "acc:debit:icbc",
                refundAmount = Money.fromDecimalString("70.01", Currency.CNY),
                originalExpenseAmount = Money.fromDecimalString("200.00", Currency.CNY),
                alreadyRefundedAmount = Money.fromDecimalString("130.00", Currency.CNY),
            )
        }
        assertTrue(overRefund.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun reimbursement_advanceDoesNotInflateExpenseAndSettlementHandlesShortfallAndSurplus() {
        // 垫付 ¥500
        val advance = engine.buildReimbursableAdvance(
            meta = meta(occurredAt = 1000L),
            reimbursementAccountId = "acc:reimburse",
            paymentAccountId = "acc:credit:cmb",
            amount = Money.fromDecimalString("500.00", Currency.CNY),
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(advance)
        assertEquals(ReimburseState.PENDING, advance.reimburseState)
        assertTrue(advance.postings.none { it.accountId.startsWith("exp:") })

        // 实报 ¥450，差额 ¥50 转个人差旅支出，冲平 ¥500 待报销
        val settlement = engine.buildReimbursementSettlement(
            meta = meta(occurredAt = 2000L),
            advanceTransactionId = advance.id,
            reimbursementAccountId = "acc:reimburse",
            depositAccountId = "acc:debit:icbc",
            settledReimbursementAmount = Money.fromDecimalString("500.00", Currency.CNY),
            receivedAmount = Money.fromDecimalString("450.00", Currency.CNY),
            shortfallExpenseAccountId = "exp:travel",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(settlement)
        assertEquals(ReimburseState.SETTLED, settlement.reimburseState)
        assertEquals(advance.id, settlement.refTransactionId)
        assertEquals(450_0000L, settlement.postings.first { it.accountId == "acc:debit:icbc" }.amountMinor)
        assertEquals(50_0000L, settlement.postings.first { it.accountId == "exp:travel" }.amountMinor)
        assertEquals(-500_0000L, settlement.postings.first { it.accountId == "acc:reimburse" }.amountMinor)

        // 待报销账户余额归零
        val pendingBalance = LedgerAggregator.accountBalanceInOriginalCurrency(
            listOf(advance, settlement),
            "acc:reimburse",
            Currency.CNY,
        )
        assertTrue(pendingBalance.isZero)

        // 100% 驳回转个人自付（不产生 0 元收款分录）+ 部分结清状态验证
        val rejected = engine.buildReimbursementSettlement(
            meta = meta(occurredAt = 3000L),
            advanceTransactionId = advance.id,
            reimbursementAccountId = "acc:reimburse",
            depositAccountId = "acc:debit:icbc",
            settledReimbursementAmount = Money.fromDecimalString("100.00", Currency.CNY),
            receivedAmount = Money.zero(Currency.CNY),
            shortfallExpenseAccountId = "exp:travel",
            remainingPendingAfterSettlement = Money.fromDecimalString("100.00", Currency.CNY),
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(rejected)
        assertEquals(2, rejected.postings.size)
        assertEquals(ReimburseState.PARTIAL, rejected.reimburseState)

        // 超额报销补贴（实报 ¥520 冲平 ¥500）
        val surplus = engine.buildReimbursementSettlement(
            meta = meta(occurredAt = 4000L),
            advanceTransactionId = advance.id,
            reimbursementAccountId = "acc:reimburse",
            depositAccountId = "acc:debit:icbc",
            settledReimbursementAmount = Money.fromDecimalString("500.00", Currency.CNY),
            receivedAmount = Money.fromDecimalString("520.00", Currency.CNY),
            surplusIncomeAccountId = "inc:reimburse-surplus",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(surplus)
        assertEquals(-20_0000L, surplus.postings.first { it.accountId == "inc:reimburse-surplus" }.amountMinor)
    }

    @Test
    fun debt_lendCollectWithInterestBadDebtWriteOffAndBorrowRepay() {
        // 借出张三 ¥1000
        val lend = engine.buildDebtLend(
            meta = meta(occurredAt = 1000L, payee = "张三"),
            receivableAccountId = "acc:debt:zhangsan",
            fromAccountId = "acc:debit:icbc",
            amount = Money.fromDecimalString("1000.00", Currency.CNY),
            accounts = sampleAccounts,
        )
        // 张三分次还款：本金 ¥400 + 利息 ¥20
        val collect = engine.buildDebtCollect(
            meta = meta(occurredAt = 2000L, payee = "张三"),
            receivableAccountId = "acc:debt:zhangsan",
            depositAccountId = "acc:debit:icbc",
            principalAmount = Money.fromDecimalString("400.00", Currency.CNY),
            interestAmount = Money.fromDecimalString("20.00", Currency.CNY),
            interestIncomeAccountId = "inc:interest",
            accounts = sampleAccounts,
        )
        // 剩余 ¥600 坏账核销
        val writeOff = engine.buildBadDebtWriteOff(
            meta = meta(occurredAt = 3000L, payee = "张三"),
            receivableAccountId = "acc:debt:zhangsan",
            badDebtExpenseAccountId = "exp:bad-debt",
            amount = Money.fromDecimalString("600.00", Currency.CNY),
            accounts = sampleAccounts,
        )

        assertZeroSumPerCurrency(lend)
        assertZeroSumPerCurrency(collect)
        assertZeroSumPerCurrency(writeOff)
        assertEquals(420_0000L, collect.postings.first { it.accountId == "acc:debit:icbc" }.amountMinor)
        assertEquals(-400_0000L, collect.postings.first { it.accountId == "acc:debt:zhangsan" }.amountMinor)
        assertEquals(-20_0000L, collect.postings.first { it.accountId == "inc:interest" }.amountMinor)
        assertTrue(
            LedgerAggregator.accountBalanceInOriginalCurrency(
                listOf(lend, collect, writeOff),
                "acc:debt:zhangsan",
                Currency.CNY,
            ).isZero,
        )

        // 借入李四 ¥2000 -> 还本 ¥2000 + 息 ¥30
        val borrow = engine.buildDebtBorrow(
            meta = meta(occurredAt = 4000L, payee = "李四"),
            payableAccountId = "acc:debt:lisi",
            toAccountId = "acc:debit:icbc",
            amount = Money.fromDecimalString("2000.00", Currency.CNY),
            accounts = sampleAccounts,
        )
        val repay = engine.buildDebtRepay(
            meta = meta(occurredAt = 5000L, payee = "李四"),
            payableAccountId = "acc:debt:lisi",
            paymentAccountId = "acc:debit:icbc",
            principalAmount = Money.fromDecimalString("2000.00", Currency.CNY),
            interestAmount = Money.fromDecimalString("30.00", Currency.CNY),
            interestExpenseAccountId = "exp:interest",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(borrow)
        assertZeroSumPerCurrency(repay)
        assertTrue(
            LedgerAggregator.accountBalanceInOriginalCurrency(
                listOf(borrow, repay),
                "acc:debt:lisi",
                Currency.CNY,
            ).isZero,
        )
    }

    @Test
    fun loanRepayment_splitsPrincipalAndInterestAndSupportsZeroInterest() {
        val installment1 = engine.buildLoanRepayment(
            meta = meta(occurredAt = 1000L).copy(source = TransactionSource.LOAN_AUTO),
            loanLiabilityAccountId = "acc:loan:mortgage",
            paymentAccountId = "acc:debit:icbc",
            principalAmount = Money.fromDecimalString("1000.00", Currency.CNY),
            interestOrFeeAmount = Money.fromDecimalString("50.00", Currency.CNY),
            interestExpenseAccountId = "exp:interest",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(installment1)
        assertEquals(TransactionDisplayType.LOAN_REPAY, installment1.displayType)
        assertEquals(1000_0000L, installment1.postings.first { it.accountId == "acc:loan:mortgage" }.amountMinor)
        assertEquals(50_0000L, installment1.postings.first { it.accountId == "exp:interest" }.amountMinor)
        assertEquals(-1050_0000L, installment1.postings.first { it.accountId == "acc:debit:icbc" }.amountMinor)

        val zeroInterest = engine.buildLoanRepayment(
            meta = meta(occurredAt = 2000L),
            loanLiabilityAccountId = "acc:loan:mortgage",
            paymentAccountId = "acc:debit:icbc",
            principalAmount = Money.fromDecimalString("500.00", Currency.CNY),
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(zeroInterest)
        assertEquals(2, zeroInterest.postings.size)
    }

    @Test
    fun investment_buySellWithRealizedPnLAndCrypto8DecimalsAndDividend() {
        // 1. 买入 10 股 AAPL @ $220（含 $1 手续费）
        val buyAapl = engine.buildInvestmentBuy(
            meta = meta(occurredAt = 1000L),
            holdingAccountId = "acc:invest:aapl",
            cashAccountId = "acc:invest:us-cash",
            investmentAssetId = "asset:aapl",
            quantity = Quantity.of("10"),
            unitPrice = UnitPrice.of("220"),
            currency = Currency.USD,
            fee = Money.fromDecimalString("1.00", Currency.USD),
            feeExpenseAccountId = "exp:fee",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(buyAapl)
        val buyHolding = buyAapl.postings.first { it.accountId == "acc:invest:aapl" }
        assertEquals(Money.fromDecimalString("2200.00", Currency.USD), buyHolding.money)
        assertEquals(Quantity.of("10"), buyHolding.quantity)
        assertEquals(UnitPrice.of("220"), buyHolding.unitPrice)
        assertEquals(Money.fromDecimalString("15950.00", Currency.CNY), buyHolding.baseMoney)

        // 2. 买入 0.00123456 BTC @ $95000.12345678（支持 8 位份额与单价精度）
        val buyBtc = engine.buildInvestmentBuy(
            meta = meta(occurredAt = 2000L),
            holdingAccountId = "acc:invest:btc",
            cashAccountId = "acc:invest:us-cash",
            investmentAssetId = "asset:btc",
            quantity = Quantity.of("0.00123456"),
            unitPrice = UnitPrice.of("95000.12345678"),
            currency = Currency.USD,
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(buyBtc)
        assertEquals("0.00123456", buyBtc.postings.first { it.accountId == "acc:invest:btc" }.quantityDecimal)

        // 3. 卖出 5 股 AAPL @ $250（成本 $1100，实现收益 $150，手续费 $1）
        val sellWithGain = engine.buildInvestmentSell(
            meta = meta(occurredAt = 3000L),
            holdingAccountId = "acc:invest:aapl",
            cashAccountId = "acc:invest:us-cash",
            investmentAssetId = "asset:aapl",
            quantity = Quantity.of("5"),
            unitPrice = UnitPrice.of("250"),
            currency = Currency.USD,
            fee = Money.fromDecimalString("1.00", Currency.USD),
            feeExpenseAccountId = "exp:fee",
            costBasis = Money.fromDecimalString("1100.00", Currency.USD),
            realizedPnLAccountId = "inc:invest-pnl",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(sellWithGain)
        val sellHolding = sellWithGain.postings.first { it.accountId == "acc:invest:aapl" }
        assertEquals(Quantity.of("-5"), sellHolding.quantity)
        assertEquals(Money.fromDecimalString("-1100.00", Currency.USD), sellHolding.money)
        assertEquals(
            Money.fromDecimalString("-150.00", Currency.USD),
            sellWithGain.postings.first { it.accountId == "inc:invest-pnl" }.money,
        )
        assertEquals(
            Money.fromDecimalString("1249.00", Currency.USD),
            sellWithGain.postings.first { it.accountId == "acc:invest:us-cash" }.money,
        )

        // 4. 简单卖出模式（不拆实现盈亏）
        val simpleSell = engine.buildInvestmentSell(
            meta = meta(occurredAt = 3500L),
            holdingAccountId = "acc:invest:aapl",
            cashAccountId = "acc:invest:us-cash",
            investmentAssetId = "asset:aapl",
            quantity = Quantity.of("5"),
            unitPrice = UnitPrice.of("240"),
            currency = Currency.USD,
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(simpleSell)
        assertEquals(2, simpleSell.postings.size)

        // 5. AAPL 现金分红 $15（含 10% 预扣税 $1.50）
        val dividend = engine.buildInvestmentDividend(
            meta = meta(occurredAt = 4000L),
            cashAccountId = "acc:invest:us-cash",
            dividendIncomeAccountId = "inc:dividend",
            investmentAssetId = "asset:aapl",
            amount = Money.fromDecimalString("15.00", Currency.USD),
            taxOrFee = Money.fromDecimalString("1.50", Currency.USD),
            taxExpenseAccountId = "exp:tax",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(dividend)
        assertEquals(Money.fromDecimalString("13.50", Currency.USD), dividend.postings.first { it.accountId == "acc:invest:us-cash" }.money)
        assertEquals(Money.fromDecimalString("1.50", Currency.USD), dividend.postings.first { it.accountId == "exp:tax" }.money)
        val divIncome = dividend.postings.first { it.accountId == "inc:dividend" }
        assertEquals(Money.fromDecimalString("-15.00", Currency.USD), divIncome.money)
        assertEquals("asset:aapl", divIncome.investmentAssetId)
    }

    @Test
    fun openingBalanceAndCustomSplit_validateProfessionalMultiSplitEntries() {
        val assetOpening = engine.buildOpeningBalance(
            meta = meta(),
            accountId = "acc:debit:icbc",
            accountType = AccountType.ASSET,
            balance = Money.fromDecimalString("50000.00", Currency.CNY),
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(assetOpening)
        assertEquals(50000_0000L, assetOpening.postings.first { it.accountId == "acc:debit:icbc" }.amountMinor)

        val liabilityOpening = engine.buildOpeningBalance(
            meta = meta(),
            accountId = "acc:credit:cmb",
            accountType = AccountType.LIABILITY,
            balance = Money.fromDecimalString("3200.00", Currency.CNY),
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(liabilityOpening)
        assertEquals(-3200_0000L, liabilityOpening.postings.first { it.accountId == "acc:credit:cmb" }.amountMinor)

        // 工资单专业多拆分录：应发 ¥25000 = 实发银行卡 ¥19500 + 个税 ¥2000 + 手续/社保代扣 ¥3500
        val payrollSplit = engine.buildCustomSplit(
            meta = meta(payee = "工资单"),
            postings = listOf(
                PostingDraft("inc:salary", -Money.fromDecimalString("25000.00", Currency.CNY)),
                PostingDraft("acc:debit:icbc", Money.fromDecimalString("19500.00", Currency.CNY)),
                PostingDraft("exp:tax", Money.fromDecimalString("2000.00", Currency.CNY)),
                PostingDraft("exp:fee", Money.fromDecimalString("3500.00", Currency.CNY)),
            ),
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(payrollSplit)
        assertEquals(4, payrollSplit.postings.size)
    }

    @Test
    fun historicalBaseSnapshotLock_doesNotDriftWhenExchangeRatesChangeLater() {
        val ratesAtT1 = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.20", capturedAt = 1000L),
        )
        val ratesAtT2 = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.80", capturedAt = 9000L),
        )

        val engineAtT1 = engine.withRateProvider(ratesAtT1)
        val fundUsd = engineAtT1.buildOpeningBalance(
            meta = meta(occurredAt = 1000L),
            accountId = "acc:invest:us-cash",
            accountType = AccountType.ASSET,
            balance = Money.fromDecimalString("1000.00", Currency.USD),
            accounts = sampleAccounts,
        )
        val usdDinner = engineAtT1.buildExpense(
            meta = meta(occurredAt = 2000L),
            expenseCategoryAccountId = "exp:food:lunch",
            paymentAccountId = "acc:invest:us-cash",
            amount = Money.fromDecimalString("100.00", Currency.USD),
            accounts = sampleAccounts,
        )

        val ledger = listOf(fundUsd, usdDinner)

        // 历史收支报表永远锁定交易当时汇率 7.20 -> ¥720.00 CNY，不受日后汇率涨至 7.80 影响
        val historicalFoodCny = LedgerAggregator.historicalBaseAmountSum(ledger, "exp:food:lunch", Currency.CNY)
        assertEquals(Money.fromDecimalString("720.00", Currency.CNY), historicalFoodCny)
        assertEquals("7.2", usdDinner.postings.first { it.accountId == "exp:food:lunch" }.exchangeRateSnapshot.rateDecimal)

        // 外币资产当前估值按最新汇率 7.80 实时折算：剩余 $900 * 7.80 = ¥7020.00 CNY
        val liveCashCny = LedgerAggregator.markToMarketBalance(
            transactions = ledger,
            accountId = "acc:invest:us-cash",
            accountCurrency = Currency.USD,
            targetCurrency = Currency.CNY,
            latestRates = ratesAtT2,
        )
        assertEquals(Money.fromDecimalString("7020.00", Currency.CNY), liveCashCny)

        // 用户手动覆写汇率的单笔交易锁定 USER_OVERRIDE 快照
        val manualRateDinner = engineAtT1.buildExpense(
            meta = meta(occurredAt = 3000L),
            expenseCategoryAccountId = "exp:food:lunch",
            paymentAccountId = "acc:credit:us",
            amount = Money.fromDecimalString("50.00", Currency.USD),
            exchangeRateOverride = ExchangeRateSnapshot.of(
                Currency.USD,
                Currency.CNY,
                "7.35",
                source = ExchangeRateSource.USER_OVERRIDE,
                capturedAt = 3000L,
            ),
            accounts = sampleAccounts,
        )
        assertEquals(
            Money.fromDecimalString("367.50", Currency.CNY),
            manualRateDinner.postings.first { it.accountId == "exp:food:lunch" }.baseMoney,
        )
    }

    @Test
    fun builders_rejectNegativeAndZeroAmountsAcrossScenarios() {
        val zeroCny = Money.zero(Currency.CNY)
        val negCny = Money.fromDecimalString("-10.00", Currency.CNY)

        assertTrue(
            runCatching {
                engine.buildExpense(meta(), "exp:food:lunch", "acc:debit:icbc", zeroCny)
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                engine.buildExpense(meta(), "exp:food:lunch", "acc:debit:icbc", negCny)
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                engine.buildIncome(meta(), "inc:salary", "acc:debit:icbc", zeroCny)
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                engine.buildTransfer(meta(), "acc:debit:icbc", "acc:debit:icbc", Money.fromDecimalString("10", Currency.CNY))
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                engine.buildCrossCurrencyTransfer(
                    meta(),
                    "acc:debit:icbc",
                    "acc:invest:us-cash",
                    negCny,
                    Money.fromDecimalString("10", Currency.USD),
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                engine.buildLoanRepayment(meta(), "acc:loan:mortgage", "acc:debit:icbc", zeroCny, zeroCny)
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                engine.buildDebtCollect(meta(), "acc:debt:zhangsan", "acc:debit:icbc", negCny)
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                engine.buildInvestmentBuy(
                    meta = meta(),
                    holdingAccountId = "acc:invest:aapl",
                    cashAccountId = "acc:invest:us-cash",
                    investmentAssetId = "asset:aapl",
                    quantity = Quantity.ZERO,
                    unitPrice = UnitPrice.of("220"),
                    currency = Currency.USD,
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                engine.buildCustomSplit(
                    meta = meta(),
                    postings = listOf(
                        PostingDraft("exp:food:lunch", Money.fromDecimalString("100", Currency.CNY)),
                        PostingDraft("acc:debit:icbc", -Money.fromDecimalString("99", Currency.CNY)),
                    ),
                )
            }.exceptionOrNull() is LedgerValidationException,
        )
        assertTrue(
            runCatching {
                engine.buildExpense(
                    meta = meta(),
                    expenseCategoryAccountId = "exp:shopping",
                    paymentAccountId = "acc:debit:icbc",
                    amount = Money.fromDecimalString("50", Currency.CNY),
                    discount = Money.fromDecimalString("50", Currency.CNY),
                    discountMode = DiscountMode.NET_EXPENSE,
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                engine.buildIncome(
                    meta = meta(),
                    incomeCategoryAccountId = "inc:salary",
                    depositAccountId = "acc:debit:icbc",
                    amount = Money.fromDecimalString("50", Currency.CNY),
                    fee = Money.fromDecimalString("50", Currency.CNY),
                    feeExpenseAccountId = "exp:fee",
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun investmentSell_handlesRealizedLossAndBreakEvenAndZeroInterestEdgeCases() {
        // 卖出亏损：成本 $1100，卖出 $950，亏损 $150 计入借方（正数）
        val sellLoss = engine.buildInvestmentSell(
            meta = meta(occurredAt = 5000L),
            holdingAccountId = "acc:invest:aapl",
            cashAccountId = "acc:invest:us-cash",
            investmentAssetId = "asset:aapl",
            quantity = Quantity.of("5"),
            unitPrice = UnitPrice.of("190"),
            currency = Currency.USD,
            costBasis = Money.fromDecimalString("1100.00", Currency.USD),
            realizedPnLAccountId = "inc:invest-pnl",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(sellLoss)
        assertEquals(
            Money.fromDecimalString("150.00", Currency.USD),
            sellLoss.postings.first { it.accountId == "inc:invest-pnl" }.money,
        )

        // 平价卖出：成本 == 收入，不产生零金额盈亏分录
        val sellBreakEven = engine.buildInvestmentSell(
            meta = meta(occurredAt = 5100L),
            holdingAccountId = "acc:invest:aapl",
            cashAccountId = "acc:invest:us-cash",
            investmentAssetId = "asset:aapl",
            quantity = Quantity.of("5"),
            unitPrice = UnitPrice.of("220"),
            currency = Currency.USD,
            costBasis = Money.fromDecimalString("1100.00", Currency.USD),
            realizedPnLAccountId = "inc:invest-pnl",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(sellBreakEven)
        assertEquals(2, sellBreakEven.postings.size)

        // 仅收息 / 仅付息宽限期
        val interestOnlyCollect = engine.buildDebtCollect(
            meta = meta(),
            receivableAccountId = "acc:debt:zhangsan",
            depositAccountId = "acc:debit:icbc",
            principalAmount = Money.zero(Currency.CNY),
            interestAmount = Money.fromDecimalString("50.00", Currency.CNY),
            interestIncomeAccountId = "inc:interest",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(interestOnlyCollect)
        assertEquals(2, interestOnlyCollect.postings.size)

        val interestOnlyLoan = engine.buildLoanRepayment(
            meta = meta(),
            loanLiabilityAccountId = "acc:loan:mortgage",
            paymentAccountId = "acc:debit:icbc",
            principalAmount = Money.zero(Currency.CNY),
            interestOrFeeAmount = Money.fromDecimalString("300.00", Currency.CNY),
            interestExpenseAccountId = "exp:interest",
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(interestOnlyLoan)
        assertEquals(2, interestOnlyLoan.postings.size)
    }

    @Test
    fun baseCurrencySwitchToUsd_andDraftVoidExclusionInAggregator() {
        // 本位币切换为 USD：CNY 消费与 CNY->HKD 换汇全部锁定 USD 本位币快照
        val usdBaseEngine = engine.withBaseCurrency(Currency.USD)
        val cnyLunch = usdBaseEngine.buildExpense(
            meta = meta(occurredAt = 1000L),
            expenseCategoryAccountId = "exp:food:lunch",
            paymentAccountId = "acc:debit:icbc",
            amount = Money.fromDecimalString("72.50", Currency.CNY),
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(cnyLunch)
        assertEquals(Currency.USD, cnyLunch.postings.first().baseCurrency)
        assertEquals(
            Money.fromDecimalString("10.00", Currency.USD),
            cnyLunch.postings.first { it.accountId == "exp:food:lunch" }.baseMoney,
        )

        val cnyToHkd = usdBaseEngine.buildCrossCurrencyTransfer(
            meta = meta(occurredAt = 2000L),
            fromAccountId = "acc:debit:icbc",
            toAccountId = "acc:invest:hk-cash",
            paidAmount = Money.fromDecimalString("930.00", Currency.CNY),
            receivedAmount = Money.fromDecimalString("1000.00", Currency.HKD),
            accounts = sampleAccounts,
        )
        assertZeroSumPerCurrency(cnyToHkd)
        assertTrue(cnyToHkd.postings.all { it.baseCurrency == Currency.USD })
        assertEquals(0L, cnyToHkd.postings.sumOf { it.baseAmountMinor })

        // DRAFT 与 VOID 交易不计入余额、历史本位币报表与净支出
        val draftExpense = engine.buildExpense(
            meta = meta(occurredAt = 3000L).copy(status = io.github.kvtttba.droplet.core.model.TransactionStatus.DRAFT),
            expenseCategoryAccountId = "exp:shopping",
            paymentAccountId = "acc:debit:icbc",
            amount = Money.fromDecimalString("999.00", Currency.CNY),
            accounts = sampleAccounts,
        )
        val postedExpense = engine.buildExpense(
            meta = meta(occurredAt = 3100L),
            expenseCategoryAccountId = "exp:shopping",
            paymentAccountId = "acc:debit:icbc",
            amount = Money.fromDecimalString("100.00", Currency.CNY),
            accounts = sampleAccounts,
        )
        val voidRefund = engine.buildRefund(
            meta = meta(occurredAt = 3200L).copy(status = io.github.kvtttba.droplet.core.model.TransactionStatus.VOID),
            originalTransactionId = postedExpense.id,
            expenseCategoryAccountId = "exp:shopping",
            refundToAccountId = "acc:debit:icbc",
            refundAmount = Money.fromDecimalString("40.00", Currency.CNY),
            accounts = sampleAccounts,
        )

        val allTxs = listOf(draftExpense, postedExpense, voidRefund)
        assertEquals(
            Money.fromDecimalString("100.00", Currency.CNY),
            LedgerAggregator.historicalBaseAmountSum(allTxs, "exp:shopping", Currency.CNY),
        )
        val net = LedgerAggregator.netExpenseAfterRefunds(postedExpense, listOf(voidRefund), "exp:shopping")
        assertEquals(Money.fromDecimalString("100.00", Currency.CNY), net.originalCurrencyNet)
        assertEquals(0, net.refundCount)
    }
}
