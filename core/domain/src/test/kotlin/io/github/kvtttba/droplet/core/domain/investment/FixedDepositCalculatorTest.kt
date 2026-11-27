package io.github.kvtttba.droplet.core.domain.investment

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.IdGenerator
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidator
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DepositInterestMode
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedDepositCalculatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun epochOf(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun depositAsset(
        mode: DepositInterestMode,
        rate: String = "0.03",
        start: LocalDate = LocalDate.of(2025, 1, 1),
        maturity: LocalDate = LocalDate.of(2026, 1, 1),
    ): InvestmentAsset = InvestmentAsset(
        id = "dep:1",
        accountId = "acc:hold:dep1",
        assetClass = AssetClass.FIXED_DEPOSIT,
        symbol = "CMB-DEP",
        name = "招行定期存款",
        currency = Currency.CNY,
        depositRateAnnualDecimal = rate,
        depositStartAt = start.atStartOfDay(zone).toInstant().toEpochMilli(),
        depositMaturityAt = maturity.atStartOfDay(zone).toInstant().toEpochMilli(),
        depositInterestMode = mode,
    )

    @Test
    fun simpleAtMaturity_coversBeforeStartMidTermMaturityDayAndOverdue() {
        val asset = depositAsset(
            mode = DepositInterestMode.SIMPLE_AT_MATURITY,
            rate = "0.0365",
            start = LocalDate.of(2025, 1, 1),
            maturity = LocalDate.of(2026, 1, 1), // 365 days
        )
        val principal = Money.fromDecimalString("100000.00", Currency.CNY)

        // 1. Before start (2024-12-22 -> 10 days before start)
        val before = FixedDepositCalculator.calculate(asset, principal, epochOf(2024, 12, 22), zoneId = zone)
        assertEquals(FixedDepositStatus.NOT_STARTED, before.status)
        assertFalse(before.isMatured)
        assertEquals(0L, before.elapsedDays)
        assertEquals(10L, before.daysUntilStart)
        assertEquals(375L, before.remainingDays)
        assertEquals(Money.zero(Currency.CNY), before.accruedInterest)
        assertEquals(principal, before.currentTotalValue)
        assertEquals(Money.fromDecimalString("3650.00", Currency.CNY), before.expectedTotalInterest)
        assertEquals(Money.fromDecimalString("103650.00", Currency.CNY), before.expectedMaturityTotalValue)
        assertEquals(Money.zero(Currency.CNY), before.dailyAccrual)
        assertNull(before.estimatedMonthlyPayout)

        // 2. Mid-term (after exactly 100 days -> 2025-04-11; 100000 * 0.0365 * 100 / 365 = ¥1,000.00)
        val mid = FixedDepositCalculator.calculate(asset, principal, epochOf(2025, 4, 11), zoneId = zone)
        assertEquals(FixedDepositStatus.ACCRUING, mid.status)
        assertFalse(mid.isMatured)
        assertEquals(100L, mid.elapsedDays)
        assertEquals(265L, mid.remainingDays)
        assertEquals(Money.fromDecimalString("1000.00", Currency.CNY), mid.accruedInterest)
        assertEquals(Money.fromDecimalString("101000.00", Currency.CNY), mid.currentTotalValue)
        assertEquals(Money.fromDecimalString("10.00", Currency.CNY), mid.dailyAccrual)

        // 3. Exact maturity date (2026-01-01)
        val matured = FixedDepositCalculator.calculate(asset, principal, epochOf(2026, 1, 1), zoneId = zone)
        assertEquals(FixedDepositStatus.MATURED, matured.status)
        assertTrue(matured.isMatured)
        assertEquals(365L, matured.elapsedDays)
        assertEquals(0L, matured.remainingDays)
        assertEquals(0L, matured.overdueDays)
        assertEquals(Money.fromDecimalString("3650.00", Currency.CNY), matured.accruedInterest)
        assertEquals(matured.expectedMaturityTotalValue, matured.currentTotalValue)
        assertEquals(Money.zero(Currency.CNY), matured.dailyAccrual)

        // 4. Overdue (+30 days past maturity) -> interest stays capped at contract maturity
        val overdue = FixedDepositCalculator.calculate(asset, principal, epochOf(2026, 1, 31), zoneId = zone)
        assertEquals(FixedDepositStatus.MATURED, overdue.status)
        assertTrue(overdue.isMatured)
        assertEquals(365L, overdue.elapsedDays)
        assertEquals(30L, overdue.overdueDays)
        assertEquals(Money.fromDecimalString("3650.00", Currency.CNY), overdue.accruedInterest)
    }

    @Test
    fun compoundAnnual_computesExactMultiYearCompoundingAndIntraYearLinearAccrual() {
        // 2-year deposit: 2025-01-01 to 2027-01-01 (730 exact days), 3% annual compounded
        val asset = depositAsset(
            mode = DepositInterestMode.COMPOUND_ANNUAL,
            rate = "0.03",
            start = LocalDate.of(2025, 1, 1),
            maturity = LocalDate.of(2027, 1, 1),
        )
        val principal = Money.fromDecimalString("100000.00", Currency.CNY)

        // After 1 exact year (365 days): 100000 * 0.03 = 3000.00
        val year1 = FixedDepositCalculator.calculate(asset, principal, epochOf(2026, 1, 1), zoneId = zone)
        assertEquals(365L, year1.elapsedDays)
        assertEquals(Money.fromDecimalString("3000.00", Currency.CNY), year1.accruedInterest)

        // At 2-year maturity (730 days): 100000 * (1.03^2 - 1) = 6090.00 (¥90 more than simple 6000!)
        val year2 = FixedDepositCalculator.calculate(asset, principal, epochOf(2027, 1, 1), zoneId = zone)
        assertEquals(730L, year2.elapsedDays)
        assertEquals(Money.fromDecimalString("6090.00", Currency.CNY), year2.accruedInterest)
        assertEquals(Money.fromDecimalString("106090.00", Currency.CNY), year2.expectedMaturityTotalValue)

        // Mid year 2 (day 365 + 73 = 438 days -> 2026-03-15):
        // 100000 * (1.03 * (1 + 0.03 * 73 / 365) - 1) = 100000 * (1.03 * 1.006 - 1) = 3618.00 exact!
        val midYear2 = FixedDepositCalculator.calculate(asset, principal, epochOf(2026, 3, 15), zoneId = zone)
        assertEquals(438L, midYear2.elapsedDays)
        assertEquals(Money.fromDecimalString("3618.00", Currency.CNY), midYear2.accruedInterest)
    }

    @Test
    fun monthlyPayout_tracksMonthlyCashflowAndDeductsAlreadyPaidInterest() {
        val asset = depositAsset(
            mode = DepositInterestMode.MONTHLY_PAYOUT,
            rate = "0.024",
            start = LocalDate.of(2025, 1, 1),
            maturity = LocalDate.of(2026, 1, 1),
        )
        val principal = Money.fromDecimalString("100000.00", Currency.CNY)

        // 2025-04-01 = 90 days elapsed, 3 completed monthly periods
        // Accrued = 100000 * 0.024 * 90 / 365 = 591.7808
        // Estimated monthly payout = 100000 * 0.024 / 12 = 200.00
        val alreadyPaid = Money.fromDecimalString("400.00", Currency.CNY) // 2 months already paid to debit card
        val valuation = FixedDepositCalculator.calculate(
            asset = asset,
            principal = principal,
            asOfEpochMs = epochOf(2025, 4, 1),
            alreadyPaidInterest = alreadyPaid,
            zoneId = zone,
        )

        assertEquals(3, valuation.completedMonthlyPeriods)
        assertEquals(Money.fromDecimalString("200.00", Currency.CNY), valuation.estimatedMonthlyPayout)
        assertEquals(Money.fromDecimalString("591.7808", Currency.CNY), valuation.accruedInterest)
        assertEquals(alreadyPaid, valuation.alreadyPaidInterest)
        assertEquals(Money.fromDecimalString("191.7808", Currency.CNY), valuation.unpaidAccruedInterest)
        assertEquals(Money.fromDecimalString("100191.7808", Currency.CNY), valuation.currentTotalValue)

        // If alreadyPaid exceeds accrued (e.g. bank rounded up early payout), unpaid clamps at 0
        val overpaid = FixedDepositCalculator.calculate(
            asset = asset,
            principal = principal,
            asOfEpochMs = epochOf(2025, 4, 1),
            alreadyPaidInterest = Money.fromDecimalString("650.00", Currency.CNY),
            zoneId = zone,
        )
        assertEquals(Money.zero(Currency.CNY), overpaid.unpaidAccruedInterest)
        assertEquals(principal, overpaid.currentTotalValue)
    }

    @Test
    fun zeroRateZeroPrincipalAndLeapYearBoundaries_areExact() {
        val zeroRateAsset = depositAsset(
            mode = DepositInterestMode.SIMPLE_AT_MATURITY,
            rate = "0",
            start = LocalDate.of(2025, 1, 1),
            maturity = LocalDate.of(2026, 1, 1),
        )
        val zeroRateVal = FixedDepositCalculator.calculate(
            asset = zeroRateAsset,
            principal = Money.fromDecimalString("50000.00", Currency.CNY),
            asOfEpochMs = epochOf(2025, 7, 1),
            zoneId = zone,
        )
        assertEquals(Money.zero(Currency.CNY), zeroRateVal.accruedInterest)
        assertEquals(Money.zero(Currency.CNY), zeroRateVal.expectedTotalInterest)
        assertEquals(Money.fromDecimalString("50000.00", Currency.CNY), zeroRateVal.currentTotalValue)

        val zeroPrincipalVal = FixedDepositCalculator.calculate(
            asset = depositAsset(DepositInterestMode.COMPOUND_ANNUAL, rate = "0.03"),
            principal = Money.zero(Currency.CNY),
            asOfEpochMs = epochOf(2025, 7, 1),
            zoneId = zone,
        )
        assertEquals(Money.zero(Currency.CNY), zeroPrincipalVal.accruedInterest)
        assertEquals(Money.zero(Currency.CNY), zeroPrincipalVal.currentTotalValue)

        // Leap year 2024-01-01 -> 2025-01-01 spans 366 actual days on ACT/365 basis
        val leapAsset = depositAsset(
            mode = DepositInterestMode.SIMPLE_AT_MATURITY,
            rate = "0.0365",
            start = LocalDate.of(2024, 1, 1),
            maturity = LocalDate.of(2025, 1, 1),
        )
        val leapVal = FixedDepositCalculator.calculate(
            asset = leapAsset,
            principal = Money.fromDecimalString("100000.00", Currency.CNY),
            asOfEpochMs = epochOf(2025, 1, 1),
            zoneId = zone,
        )
        assertEquals(366L, leapVal.totalTermDays)
        assertEquals(366L, leapVal.elapsedDays)
        // 100000 * 0.0365 * 366 / 365 = ¥3,660.00 (vs ¥3,650.00 in a 365-day year)
        assertEquals(Money.fromDecimalString("3660.00", Currency.CNY), leapVal.expectedTotalInterest)
    }

    @Test
    fun buildSettlementTransaction_producesBalancedDoubleEntryForMaturityAndEarlyWithdrawal() {
        val seq = AtomicInteger(0)
        val engine = LedgerEngine(
            baseCurrency = Currency.CNY,
            rateProvider = ExchangeRateTable.empty(),
            idGenerator = IdGenerator { "tx-${seq.incrementAndGet()}" },
        )
        val accounts = (
            SystemAccounts.allSystemEquityAccounts() + listOf(
                Account("acc:hold:dep1", "定期持仓", AccountType.ASSET, AccountSubType.INVEST_HOLDING, currency = Currency.CNY),
                Account("acc:debit", "储蓄卡", AccountType.ASSET, AccountSubType.DEBIT_CARD, currency = Currency.CNY),
                Account("inc:interest", "利息收入", AccountType.INCOME, AccountSubType.CATEGORY, currency = Currency.CNY),
                Account("exp:fee", "利息手续费", AccountType.EXPENSE, AccountSubType.CATEGORY, currency = Currency.CNY),
            )
            ).associateBy { it.id }

        // Maturity settlement: ¥50,000 principal + ¥1,500 interest -> ¥51,500 cash
        val maturityTx = FixedDepositCalculator.buildSettlementTransaction(
            ledgerEngine = engine,
            meta = TransactionMeta(occurredAt = epochOf(2026, 1, 1)),
            assetId = "dep:1",
            holdingAccountId = "acc:hold:dep1",
            cashAccountId = "acc:debit",
            interestIncomeAccountId = "inc:interest",
            withdrawnPrincipal = Money.fromDecimalString("50000.00", Currency.CNY),
            interestAmount = Money.fromDecimalString("1500.00", Currency.CNY),
            accounts = accounts,
        )
        assertTrue(LedgerValidator.validate(maturityTx, accounts).isValid)
        assertEquals(TransactionDisplayType.INVEST, maturityTx.displayType)
        assertEquals(Money.fromDecimalString("-50000.00", Currency.CNY), maturityTx.postings.first { it.accountId == "acc:hold:dep1" }.money)
        assertEquals(Quantity.of("-50000"), maturityTx.postings.first { it.accountId == "acc:hold:dep1" }.quantity)
        assertEquals(Money.fromDecimalString("-1500.00", Currency.CNY), maturityTx.postings.first { it.accountId == "inc:interest" }.money)
        assertEquals(Money.fromDecimalString("51500.00", Currency.CNY), maturityTx.postings.first { it.accountId == "acc:debit" }.money)

        // Early withdrawal with penalty fee: ¥20,000 principal + ¥80 demand interest - ¥20 fee -> ¥20,060 cash
        val earlyTx = FixedDepositCalculator.buildSettlementTransaction(
            ledgerEngine = engine,
            meta = TransactionMeta(occurredAt = epochOf(2025, 6, 1)),
            assetId = "dep:1",
            holdingAccountId = "acc:hold:dep1",
            cashAccountId = "acc:debit",
            interestIncomeAccountId = "inc:interest",
            withdrawnPrincipal = Money.fromDecimalString("20000.00", Currency.CNY),
            interestAmount = Money.fromDecimalString("80.00", Currency.CNY),
            penaltyOrTaxFee = Money.fromDecimalString("20.00", Currency.CNY),
            penaltyFeeExpenseAccountId = "exp:fee",
            accounts = accounts,
        )
        assertTrue(LedgerValidator.validate(earlyTx, accounts).isValid)
        assertEquals(4, earlyTx.postings.size)
        assertEquals(Money.fromDecimalString("20060.00", Currency.CNY), earlyTx.postings.first { it.accountId == "acc:debit" }.money)
        assertEquals(Money.fromDecimalString("20.00", Currency.CNY), earlyTx.postings.first { it.accountId == "exp:fee" }.money)
    }
}
