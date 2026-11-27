package io.github.kvtttba.droplet.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExtendedDomainModelsTest {

    @Test
    fun tagAndAttachment_validateInvariants() {
        val tag = Tag(id = "t1", name = "出差", color = "#42A5F5", sortOrder = 1)
        assertEquals("出差", tag.name)
        assertThrows(IllegalArgumentException::class.java) { Tag(id = "", name = "出差") }
        assertThrows(IllegalArgumentException::class.java) { Tag(id = "t1", name = "   ") }

        val attachment = Attachment(
            id = "att1",
            transactionId = "tx1",
            localRelPath = "receipts/2026/mock_receipt.jpg",
            sha256 = "0".repeat(64),
            mimeType = "image/jpeg",
            sizeBytes = 2048L,
            syncToWebdav = true,
        )
        assertEquals(2048L, attachment.sizeBytes)
        assertThrows(IllegalArgumentException::class.java) {
            attachment.copy(sizeBytes = -1L)
        }
    }

    @Test
    fun investmentModels_enforceCanonicalDecimalsAndConvertRates() {
        val deposit = InvestmentAsset(
            id = "asset-dep-1",
            accountId = "acc-hold-1",
            assetClass = AssetClass.FIXED_DEPOSIT,
            symbol = "DEP3Y",
            name = "三年期定期存款",
            currency = Currency.CNY,
            depositRateAnnualDecimal = "0.0215",
            depositStartAt = 1_000L,
            depositMaturityAt = 2_000L,
            depositInterestMode = DepositInterestMode.SIMPLE_AT_MATURITY,
        )
        assertEquals("0.0215", deposit.depositRateAnnualDecimal)

        assertThrows(IllegalArgumentException::class.java) {
            deposit.copy(depositRateAnnualDecimal = "0.02150") // non-canonical trailing zero
        }
        assertThrows(IllegalArgumentException::class.java) {
            deposit.copy(depositMaturityAt = 500L) // before start
        }

        val quote = MarketQuote(
            assetId = "asset-dep-1",
            latestPrice = UnitPrice.of("225.50"),
            prevClose = UnitPrice.of("220.00"),
            dailyChangePctDecimal = "2.5",
            priceCurrency = Currency.USD,
            sourceProvider = "mock-feed",
            fetchedAt = 5_000L,
        )
        assertEquals("225.5", quote.latestPrice.canonical)
        assertThrows(IllegalArgumentException::class.java) {
            quote.copy(dailyChangePctDecimal = "2.50")
        }

        val cached = CachedExchangeRate(
            fromCurrency = Currency.USD,
            toCurrency = Currency.CNY,
            rateDecimal = "7.25",
            sourceProvider = "mock-fx",
            fetchedAt = 9_000L,
        )
        val snapshot = cached.toSnapshot()
        assertEquals(Currency.USD, snapshot.fromCurrency)
        assertEquals(Currency.CNY, snapshot.toCurrency)
        assertEquals("7.25", snapshot.rateDecimal)
        assertEquals(ExchangeRateSource.MARKET_CACHE, snapshot.source)
        assertEquals(9_000L, snapshot.capturedAt)

        assertThrows(IllegalArgumentException::class.java) {
            CachedExchangeRate(Currency.USD, Currency.USD, "1.01", "mock", 1L)
        }
    }

    @Test
    fun loanDebtBudgetRecurringTemplateAndAiDraft_validateInvariants() {
        val plan = LoanPlan(
            id = "loan-1",
            name = "房贷测试",
            method = LoanMethod.EQUAL_INSTALLMENT,
            currency = Currency.CNY,
            totalPrincipalMinor = Money.fromDecimalString("100000.00").amountMinor,
            totalPeriods = 12,
            annualRateDecimal = "0.0385",
            firstDueAt = 10_000L,
            monthlyRepayDay = 15,
            loanAccountId = "acc-loan",
            paymentAccountId = "acc-bank",
            interestExpenseAccountId = "acc-int",
        )
        assertEquals(1_000_000_000L, plan.totalPrincipal.amountMinor)
        assertThrows(IllegalArgumentException::class.java) { plan.copy(monthlyRepayDay = 32) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(totalPrincipalMinor = 0L) }

        val inst = LoanInstallment(
            id = "inst-1",
            loanPlanId = "loan-1",
            periodNumber = 1,
            dueAt = 10_000L,
            principalMinor = 80_000_000L,
            interestOrFeeMinor = 3_200_000L,
            remainingPrincipalMinor = 920_000_000L,
        )
        assertEquals(83_200_000L, inst.totalDueMinor)

        val debt = DebtProfile(
            id = "debt-1",
            name = "测试往来人",
            annualInterestRateDecimal = "0.04",
        )
        assertEquals("0.04", debt.annualInterestRateDecimal)
        assertThrows(IllegalArgumentException::class.java) {
            debt.copy(annualInterestRateDecimal = "-0.01")
        }

        val budget = Budget(
            id = "b1",
            period = BudgetPeriod.MONTHLY,
            limitAmountMinor = Money.fromDecimalString("5000.00").amountMinor,
            warnThresholdPct = 80,
        )
        assertEquals(50_000_000L, budget.limitAmount.amountMinor)
        assertThrows(IllegalArgumentException::class.java) { budget.copy(warnThresholdPct = 0) }
        assertThrows(IllegalArgumentException::class.java) { budget.copy(warnThresholdPct = 101) }

        val cronRule = RecurringRule(
            id = "r1",
            name = "房租",
            frequency = RecurringFrequency.CRON,
            cronExpression = "0 9 1 * *",
            nextRunAt = 100L,
            templatePayloadJson = """{"amount":"3500.00"}""",
        )
        assertEquals("0 9 1 * *", cronRule.cronExpression)
        assertThrows(IllegalArgumentException::class.java) {
            cronRule.copy(cronExpression = null)
        }

        val template = TransactionTemplate(
            id = "tpl-1",
            name = "地铁通勤",
            displayType = TransactionDisplayType.EXPENSE,
            templatePayloadJson = """{"amount":"6.00"}""",
        )
        assertEquals(0, template.useCount)
        assertThrows(IllegalArgumentException::class.java) { template.copy(useCount = -1) }

        val draft = AiDraft(id = "ai-1", inputType = AiDraftInputType.TEXT, rawText = "午饭 35 元")
        assertEquals(AiDraftStatus.PENDING_ONLINE, draft.status)
    }
}
