package io.github.kvtttba.droplet.core.ai

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidator
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AiDraftInputType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDraftTransactionMapperTest {

    private val nowMs = 1_795_840_200_000L

    private val accountsById: Map<String, Account> = buildMap {
        SystemAccounts.allSystemEquityAccounts().forEach { put(it.id, it) }
        listOf(
            Account(
                id = "acc:reimburse",
                name = "待报销",
                type = AccountType.ASSET,
                subType = AccountSubType.REIMBURSEMENT,
                currency = Currency.CNY,
                isSystem = true,
            ),
            Account(
                id = "acc:wechat",
                name = "微信零钱",
                type = AccountType.ASSET,
                subType = AccountSubType.DEBIT_CARD,
                currency = Currency.CNY,
            ),
            Account(
                id = "acc:credit_cny",
                name = "招行信用卡",
                type = AccountType.LIABILITY,
                subType = AccountSubType.CREDIT_CARD,
                currency = Currency.CNY,
            ),
            Account(
                id = "acc:usd_cash",
                name = "美元资金户",
                type = AccountType.ASSET,
                subType = AccountSubType.INVEST_CASH,
                currency = Currency.USD,
            ),
            Account(
                id = "acc:archived",
                name = "旧银行卡",
                type = AccountType.ASSET,
                subType = AccountSubType.DEBIT_CARD,
                currency = Currency.CNY,
                isArchived = true,
            ),
            Account(
                id = "cat:exp:lunch",
                name = "午餐",
                type = AccountType.EXPENSE,
                subType = AccountSubType.CATEGORY,
                currency = Currency.CNY,
            ),
            Account(
                id = "cat:inc:salary",
                name = "工资薪金",
                type = AccountType.INCOME,
                subType = AccountSubType.CATEGORY,
                currency = Currency.CNY,
            ),
        ).forEach { put(it.id, it) }
    }

    private val rates = ExchangeRateTable.of(
        ExchangeRateSnapshot.of(
            fromCurrency = Currency.USD,
            toCurrency = Currency.CNY,
            rateDecimal = "7.25000000",
            source = ExchangeRateSource.MARKET_CACHE,
            capturedAt = nowMs,
        ),
    )

    private val engine = LedgerEngine(baseCurrency = Currency.CNY, rateProvider = rates)

    @Test
    fun mapExpense_sameCurrency_producesBalancedDoubleEntryWithAiSourceAndTags() {
        val candidate = AiParsedTransactionCandidate(
            displayType = TransactionDisplayType.EXPENSE,
            occurredAtEpochMs = nowMs,
            amount = Money.fromDecimalString("38.50", Currency.CNY),
            currency = Currency.CNY,
            matchedCategoryId = "cat:exp:lunch",
            matchedAccountId = "acc:wechat",
            payee = "简餐馆",
            matchedTagIds = listOf("tag:workday"),
            note = "工作日午饭",
        )

        val tx = AiDraftTransactionMapper.mapToTransaction(
            candidate = candidate,
            source = AiDraftTransactionMapper.inputTypeToSource(AiDraftInputType.TEXT),
            accountsById = accountsById,
            ledgerEngine = engine,
            rateProvider = rates,
            nowEpochMs = nowMs,
        ).getOrThrow()

        assertEquals(TransactionDisplayType.EXPENSE, tx.displayType)
        assertEquals(TransactionSource.AI_TEXT, tx.source)
        assertEquals("简餐馆", tx.payee)
        assertEquals(listOf("tag:workday"), tx.tagIds)
        assertEquals(0L, tx.postings.sumOf { it.amountMinor })
        LedgerValidator.requireValid(tx, accountsById)
    }

    @Test
    fun mapExpense_crossCurrencyUsdOnCnyCard_autoHedgesWithTradingAccounts() {
        val candidate = AiParsedTransactionCandidate(
            displayType = TransactionDisplayType.EXPENSE,
            occurredAtEpochMs = nowMs,
            amount = Money.fromDecimalString("10.00", Currency.USD),
            currency = Currency.USD,
            matchedCategoryId = "cat:exp:lunch",
            matchedAccountId = "acc:credit_cny",
            payee = "Cloud Service",
        )

        val tx = AiDraftTransactionMapper.mapToTransaction(
            candidate = candidate,
            source = AiDraftTransactionMapper.inputTypeToSource(AiDraftInputType.IMAGE),
            accountsById = accountsById,
            ledgerEngine = engine,
            rateProvider = rates,
            nowEpochMs = nowMs,
        ).getOrThrow()

        assertEquals(TransactionSource.AI_IMAGE, tx.source)
        // Each currency sums strictly to zero via Equity:Trading:{CCY}
        tx.postings.groupBy { it.currency }.forEach { (_, lines) ->
            assertEquals(0L, lines.sumOf { it.amountMinor })
        }
        assertTrue(tx.postings.any { it.isSystemBalancing && it.accountId == SystemAccounts.tradingAccountId(Currency.USD) })
        assertTrue(tx.postings.any { it.isSystemBalancing && it.accountId == SystemAccounts.tradingAccountId(Currency.CNY) })
        LedgerValidator.requireValid(tx, accountsById)
    }

    @Test
    fun mapIncomeAndCrossCurrencyTransferAndReimburse_allPassLedgerValidator() {
        val incomeCandidate = AiParsedTransactionCandidate(
            displayType = TransactionDisplayType.INCOME,
            occurredAtEpochMs = nowMs,
            amount = Money.fromDecimalString("12800.00", Currency.CNY),
            currency = Currency.CNY,
            matchedCategoryId = "cat:inc:salary",
            matchedAccountId = "acc:wechat",
            payee = "工资",
        )
        val incomeTx = AiDraftTransactionMapper.mapToTransaction(
            candidate = incomeCandidate,
            source = AiDraftTransactionMapper.inputTypeToSource(AiDraftInputType.VOICE),
            accountsById = accountsById,
            ledgerEngine = engine,
            rateProvider = rates,
        ).getOrThrow()
        assertEquals(TransactionSource.AI_VOICE, incomeTx.source)
        LedgerValidator.requireValid(incomeTx, accountsById)

        val fxTransferCandidate = AiParsedTransactionCandidate(
            displayType = TransactionDisplayType.TRANSFER,
            occurredAtEpochMs = nowMs,
            amount = Money.fromDecimalString("100.00", Currency.USD),
            currency = Currency.USD,
            matchedAccountId = "acc:wechat",
            matchedTargetAccountId = "acc:usd_cash",
            note = "购汇",
        )
        val transferTx = AiDraftTransactionMapper.mapToTransaction(
            candidate = fxTransferCandidate,
            source = TransactionSource.AI_TEXT,
            accountsById = accountsById,
            ledgerEngine = engine,
            rateProvider = rates,
        ).getOrThrow()
        assertEquals(TransactionDisplayType.TRANSFER, transferTx.displayType)
        transferTx.postings.groupBy { it.currency }.forEach { (_, lines) ->
            assertEquals(0L, lines.sumOf { it.amountMinor })
        }
        LedgerValidator.requireValid(transferTx, accountsById)

        val reimburseCandidate = AiParsedTransactionCandidate(
            displayType = TransactionDisplayType.REIMBURSE,
            occurredAtEpochMs = nowMs,
            amount = Money.fromDecimalString("320.00", Currency.CNY),
            currency = Currency.CNY,
            matchedAccountId = "acc:credit_cny",
            payee = "高铁票",
        )
        val reimburseTx = AiDraftTransactionMapper.mapToTransaction(
            candidate = reimburseCandidate,
            source = TransactionSource.AI_IMAGE,
            accountsById = accountsById,
            ledgerEngine = engine,
            rateProvider = rates,
        ).getOrThrow()
        assertEquals(ReimburseState.PENDING, reimburseTx.reimburseState)
        LedgerValidator.requireValid(reimburseTx, accountsById)
    }

    @Test
    fun validateCandidate_rejectsMissingFieldsArchivedAccountSameTransferAndUnsupportedType() {
        val missingCat = AiParsedTransactionCandidate(
            displayType = TransactionDisplayType.EXPENSE,
            occurredAtEpochMs = nowMs,
            amount = Money.fromDecimalString("15.00", Currency.CNY),
            currency = Currency.CNY,
            matchedCategoryId = null,
            matchedAccountId = "acc:wechat",
        )
        val missingCatError = AiDraftTransactionMapper.validateCandidate(missingCat, accountsById, itemIndex = 0)
        assertNotNull(missingCatError)
        assertTrue(missingCatError!!.contains("分类"))

        val archivedAccount = missingCat.copy(
            matchedCategoryId = "cat:exp:lunch",
            matchedAccountId = "acc:archived",
        )
        assertTrue(
            AiDraftTransactionMapper.validateCandidate(archivedAccount, accountsById)!!.contains("已归档"),
        )

        val selfTransfer = AiParsedTransactionCandidate(
            displayType = TransactionDisplayType.TRANSFER,
            occurredAtEpochMs = nowMs,
            amount = Money.fromDecimalString("100.00", Currency.CNY),
            currency = Currency.CNY,
            matchedAccountId = "acc:wechat",
            matchedTargetAccountId = "acc:wechat",
        )
        assertTrue(
            AiDraftTransactionMapper.validateCandidate(selfTransfer, accountsById)!!.contains("不能相同"),
        )

        val unsupportedInvest = missingCat.copy(displayType = TransactionDisplayType.INVEST)
        assertTrue(
            AiDraftTransactionMapper.validateCandidate(unsupportedInvest, accountsById)!!.contains("切换为支出/收入/转账"),
        )

        val valid = missingCat.copy(matchedCategoryId = "cat:exp:lunch")
        assertNull(AiDraftTransactionMapper.validateCandidate(valid, accountsById))
    }
}
