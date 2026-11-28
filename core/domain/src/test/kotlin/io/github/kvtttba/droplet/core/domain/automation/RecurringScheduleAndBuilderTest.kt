package io.github.kvtttba.droplet.core.domain.automation

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidator
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.RecurringFrequency
import io.github.kvtttba.droplet.core.model.RecurringRule
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringScheduleAndBuilderTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private val debit = Account("acct:debit", "储蓄卡", AccountType.ASSET, AccountSubType.DEBIT_CARD)
    private val usdCard = Account("acct:usd", "美元卡", AccountType.ASSET, AccountSubType.DEBIT_CARD, currency = Currency.USD)
    private val credit = Account("acct:credit", "信用卡", AccountType.LIABILITY, AccountSubType.CREDIT_CARD)
    private val reimburse = Account(
        RecurringTransactionBuilder.DEFAULT_REIMBURSEMENT_ACCOUNT_ID,
        "待报销",
        AccountType.ASSET,
        AccountSubType.REIMBURSEMENT,
        isSystem = true,
    )
    private val rentCat = Account("cat:rent", "房租", AccountType.EXPENSE, AccountSubType.CATEGORY)
    private val salaryCat = Account("cat:salary", "工资", AccountType.INCOME, AccountSubType.CATEGORY)

    private val accountsById: Map<String, Account> = (
        SystemAccounts.allSystemEquityAccounts() +
            listOf(debit, usdCard, credit, reimburse, rentCat, salaryCat)
        ).associateBy { it.id }

    @Test
    fun advanceDate_handlesDailyWeeklyMonthEndClampingAndLeapYear() {
        assertEquals(
            LocalDate.of(2026, 3, 4),
            RecurringScheduleCalculator.advanceDate(LocalDate.of(2026, 3, 1), RecurringFrequency.DAILY, 3),
        )
        assertEquals(
            LocalDate.of(2026, 3, 15),
            RecurringScheduleCalculator.advanceDate(LocalDate.of(2026, 3, 1), RecurringFrequency.WEEKLY, 2),
        )
        // Jan 31 -> Feb 28 (non-leap 2026) -> Mar 31 when anchored at 31
        val feb = RecurringScheduleCalculator.advanceDate(
            LocalDate.of(2026, 1, 31),
            RecurringFrequency.MONTHLY,
            1,
            anchorDayOfMonth = 31,
        )
        assertEquals(LocalDate.of(2026, 2, 28), feb)
        val mar = RecurringScheduleCalculator.advanceDate(
            feb,
            RecurringFrequency.MONTHLY,
            1,
            anchorDayOfMonth = 31,
        )
        assertEquals(LocalDate.of(2026, 3, 31), mar)
        // Leap year 2028 Feb 29 -> 2029 Feb 28 -> 2032 Feb 29 when anchored at 29
        val leapNext = RecurringScheduleCalculator.advanceDate(
            LocalDate.of(2028, 2, 29),
            RecurringFrequency.YEARLY,
            1,
            anchorDayOfMonth = 29,
        )
        assertEquals(LocalDate.of(2029, 2, 28), leapNext)
        val nextLeap = RecurringScheduleCalculator.advanceDate(
            LocalDate.of(2028, 2, 29),
            RecurringFrequency.YEARLY,
            4,
            anchorDayOfMonth = 29,
        )
        assertEquals(LocalDate.of(2032, 2, 29), nextLeap)
    }

    @Test
    fun collectDuePlan_respectsEndAtPausedAndAnchorDayAcrossMultipleMissedMonths() {
        val jan31 = LocalDate.of(2026, 1, 31).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val endMar31 = LocalDate.of(2026, 3, 31).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        val asOfApr15 = LocalDate.of(2026, 4, 15).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

        val payloadJson = RecurringPayloadCodec.encode(
            RecurringPayload(
                displayType = TransactionDisplayType.EXPENSE,
                amountMinor = Money.fromDecimalString("3500.00").amountMinor,
                categoryAccountId = rentCat.id,
                accountId = debit.id,
                payee = "房东",
            ),
        )
        val rule = RecurringRule(
            id = "rec:rent",
            name = "每月房租",
            frequency = RecurringFrequency.MONTHLY,
            intervalCount = 1,
            nextRunAt = jan31,
            endAt = endMar31,
            templatePayloadJson = payloadJson,
            autoPost = true,
        )

        val plan = RecurringScheduleCalculator.collectDuePlan(rule, asOfEpochMs = asOfApr15, zoneId = zone)
        val dueDates = plan.dueTimestamps.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
            ),
            dueDates,
        )
        assertEquals(
            LocalDate.of(2026, 4, 30),
            Instant.ofEpochMilli(plan.nextRunAtAfter).atZone(zone).toLocalDate(),
        )
        assertTrue(RecurringScheduleCalculator.isTerminated(rule.copy(nextRunAt = plan.nextRunAtAfter)))

        // Paused rule returns zero due occurrences
        val pausedPlan = RecurringScheduleCalculator.collectDuePlan(
            rule.copy(isPaused = true),
            asOfEpochMs = asOfApr15,
            zoneId = zone,
        )
        assertTrue(pausedPlan.dueTimestamps.isEmpty())
        assertFalse(RecurringScheduleCalculator.isDue(rule.copy(isPaused = true), asOfApr15))
    }

    @Test
    fun payloadCodecAndTransactionBuilder_produceBalancedDoubleEntryTransactions() {
        val rates = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.20", ExchangeRateSource.USER_OVERRIDE, 1_000L),
        )
        val engine = LedgerEngine(baseCurrency = Currency.CNY, rateProvider = rates)
        val occurredAt = LocalDate.of(2026, 11, 15).atTime(8, 30).atZone(zone).toInstant().toEpochMilli()

        // 1. Expense (RECURRING)
        val expensePayload = RecurringPayload(
            displayType = TransactionDisplayType.EXPENSE,
            amountMinor = Money.fromDecimalString("6.00").amountMinor,
            currency = Currency.CNY,
            categoryAccountId = rentCat.id,
            accountId = debit.id,
            payee = "地铁出行",
            tagIds = listOf("tag:workday"),
            note = "早高峰",
        )
        val decodedExpense = RecurringPayloadCodec.decode(RecurringPayloadCodec.encode(expensePayload))
        assertEquals(expensePayload, decodedExpense)

        val expenseTx = RecurringTransactionBuilder.buildTransaction(
            payload = decodedExpense,
            occurredAt = occurredAt,
            source = TransactionSource.RECURRING,
            ledgerEngine = engine,
            accountsById = accountsById,
        )
        LedgerValidator.requireValid(expenseTx, accountsById)
        assertEquals(TransactionSource.RECURRING, expenseTx.source)
        assertEquals(TransactionDisplayType.EXPENSE, expenseTx.displayType)
        assertEquals(listOf("tag:workday"), expenseTx.tagIds)

        // 2. Reimbursable expense (MANUAL template)
        val reimbPayload = RecurringPayload(
            displayType = TransactionDisplayType.EXPENSE,
            amountMinor = Money.fromDecimalString("120.00").amountMinor,
            accountId = credit.id,
            payee = "出差打车",
            isReimbursable = true,
        )
        val reimbTx = RecurringTransactionBuilder.buildTransaction(
            payload = reimbPayload,
            occurredAt = occurredAt,
            source = TransactionSource.MANUAL,
            ledgerEngine = engine,
            accountsById = accountsById,
        )
        LedgerValidator.requireValid(reimbTx, accountsById)
        assertEquals(TransactionDisplayType.REIMBURSE, reimbTx.displayType)
        assertEquals(ReimburseState.PENDING, reimbTx.reimburseState)

        // 3. Income
        val incomePayload = RecurringPayload(
            displayType = TransactionDisplayType.INCOME,
            amountMinor = Money.fromDecimalString("25000.00").amountMinor,
            categoryAccountId = salaryCat.id,
            accountId = debit.id,
            payee = "每月薪资",
        )
        val incomeTx = RecurringTransactionBuilder.buildTransaction(
            payload = incomePayload,
            occurredAt = occurredAt,
            source = TransactionSource.RECURRING,
            ledgerEngine = engine,
            accountsById = accountsById,
        )
        LedgerValidator.requireValid(incomeTx, accountsById)
        assertEquals(TransactionDisplayType.INCOME, incomeTx.displayType)

        // 4. Cross-currency Transfer (CNY -> USD auto-hedged via Equity:Trading)
        val transferPayload = RecurringPayload(
            displayType = TransactionDisplayType.TRANSFER,
            amountMinor = Money.fromDecimalString("720.00", Currency.CNY).amountMinor,
            currency = Currency.CNY,
            accountId = debit.id,
            targetAccountId = usdCard.id,
            targetAmountMinor = Money.fromDecimalString("100.00", Currency.USD).amountMinor,
            targetCurrency = Currency.USD,
            note = "定投换汇",
        )
        val transferTx = RecurringTransactionBuilder.buildTransaction(
            payload = transferPayload,
            occurredAt = occurredAt,
            source = TransactionSource.RECURRING,
            ledgerEngine = engine,
            accountsById = accountsById,
        )
        LedgerValidator.requireValid(transferTx, accountsById)
        assertTrue(transferTx.isMultiCurrency)
        assertEquals(4, transferTx.postings.size)
    }
}
