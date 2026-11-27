package io.github.kvtttba.droplet.core.database

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidationError
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidationException
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidator
import io.github.kvtttba.droplet.core.domain.ledger.PostingDraft
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Attachment
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.InvestmentAsset
import io.github.kvtttba.droplet.core.model.LoanInstallment
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.LoanPlan
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Posting
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.UnitPrice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LedgerTransactionWriterAndIntegrityTest {

    private lateinit var database: DropletDatabase
    private lateinit var writer: LedgerTransactionWriter
    private lateinit var engine: LedgerEngine

    private val usdCashId = "acc:usd_cash"
    private val btcHoldingId = "acc:btc_holding"
    private val btcAssetId = "asset:btc"

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        DefaultLedgerSeeder(database).seedIfNeeded(nowEpochMs = 1_000L)
        writer = LedgerTransactionWriter(database)

        database.accountDao().upsertAll(
            listOf(
                Account(
                    id = usdCashId,
                    name = "美股美元资金户",
                    type = AccountType.ASSET,
                    subType = AccountSubType.INVEST_CASH,
                    currency = Currency.USD,
                ).toEntity(),
                Account(
                    id = btcHoldingId,
                    name = "BTC 持仓",
                    type = AccountType.ASSET,
                    subType = AccountSubType.INVEST_HOLDING,
                    currency = Currency.USD,
                ).toEntity(),
            ),
        )
        database.investmentAssetDao().upsert(
            InvestmentAsset(
                id = btcAssetId,
                accountId = btcHoldingId,
                assetClass = AssetClass.CRYPTO,
                symbol = "BTC",
                name = "Bitcoin",
                currency = Currency.USD,
            ).toEntity(),
        )

        val rates = ExchangeRateTable.of(
            ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.25", ExchangeRateSource.MARKET_CACHE, 1_000L),
            ExchangeRateSnapshot.of(Currency.HKD, Currency.CNY, "0.93", ExchangeRateSource.MARKET_CACHE, 1_000L),
        )
        engine = LedgerEngine(baseCurrency = Currency.CNY, rateProvider = rates)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun saveTransaction_roundTripsMultiCurrencyFxAndCryptoPrecisionLosslessly() = runTest {
        val fxTx = engine.buildCrossCurrencyTransfer(
            meta = TransactionMeta(
                id = "tx-fx-1",
                occurredAt = 10_000L,
                payee = "购汇测试",
                note = "CNY -> USD",
                tagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY, DefaultSeedCatalog.TagIds.TRAVEL),
            ),
            fromAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            toAccountId = usdCashId,
            paidAmount = Money.fromDecimalString("7250.00", Currency.CNY),
            receivedAmount = Money.fromDecimalString("1000.00", Currency.USD),
        )
        writer.saveTransaction(fxTx)

        val loadedFx = database.transactionDao().getById("tx-fx-1")!!.toDomain()
        assertEquals(fxTx.id, loadedFx.id)
        assertEquals(fxTx.occurredAt, loadedFx.occurredAt)
        assertEquals(fxTx.tagIds.toSet(), loadedFx.tagIds.toSet())
        assertEquals(4, loadedFx.postings.size)
        assertEquals(2, loadedFx.systemBalancingPostings.size)
        assertEquals(fxTx.postings.associateBy { it.id }, loadedFx.postings.associateBy { it.id })

        val cryptoBuy = engine.buildInvestmentBuy(
            meta = TransactionMeta(id = "tx-btc-1", occurredAt = 20_000L, note = "定投 BTC"),
            holdingAccountId = btcHoldingId,
            cashAccountId = usdCashId,
            investmentAssetId = btcAssetId,
            quantity = Quantity.of("0.01234567"),
            unitPrice = UnitPrice.of("65432.12345678"),
            currency = Currency.USD,
        )
        writer.saveTransaction(cryptoBuy)

        val loadedBuy = database.transactionDao().getById("tx-btc-1")!!.toDomain()
        val holdingPosting = loadedBuy.postings.single { it.accountId == btcHoldingId }
        assertEquals("0.01234567", holdingPosting.quantity?.canonical)
        assertEquals("65432.12345678", holdingPosting.unitPrice?.canonical)
        assertEquals(btcAssetId, holdingPosting.investmentAssetId)
        assertEquals(ExchangeRateSource.MARKET_CACHE, holdingPosting.exchangeRateSnapshot.source)
        assertEquals("7.25", holdingPosting.exchangeRateSnapshot.rateDecimal)
    }

    @Test
    fun saveTransaction_rejectsUnbalancedAndInvalidTransactionsWithFullRollback() = runTest {
        val idRate = ExchangeRateSnapshot.identity(Currency.CNY, 10_000L)

        // 1. Unbalanced single-currency transaction
        val unbalanced = Transaction(
            id = "tx-bad-unbalanced",
            occurredAt = 10_000L,
            displayType = TransactionDisplayType.EXPENSE,
            tagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY),
            postings = listOf(
                Posting("p1", "tx-bad-unbalanced", DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH, Currency.CNY, 385_000L, Currency.CNY, 385_000L, idRate),
                Posting("p2", "tx-bad-unbalanced", DefaultSeedCatalog.AccountIds.DEBIT_CARD, Currency.CNY, -380_000L, Currency.CNY, -380_000L, idRate),
            ),
        )
        try {
            writer.saveTransaction(unbalanced)
            fail("Expected LedgerValidationException for unbalanced transaction")
        } catch (e: LedgerValidationException) {
            assertTrue(e.errors.any { it is LedgerValidationError.UnbalancedCurrency })
        }
        assertEquals(0, database.transactionDao().countTransactions())
        assertEquals(0, database.transactionDao().countPostings())
        assertEquals(0, database.transactionDao().countTransactionTags())

        // 2. Single-posting transaction (< 2 postings)
        val singlePosting = Transaction(
            id = "tx-bad-single",
            occurredAt = 10_000L,
            displayType = TransactionDisplayType.EXPENSE,
            postings = listOf(
                Posting("p1", "tx-bad-single", DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH, Currency.CNY, 100_000L, Currency.CNY, 100_000L, idRate),
            ),
        )
        try {
            writer.saveTransaction(singlePosting)
            fail("Expected LedgerValidationException for single posting")
        } catch (e: LedgerValidationException) {
            assertTrue(e.errors.any { it is LedgerValidationError.InsufficientPostings })
        }

        // 3. Unknown account ID
        val unknownAccountTx = engine.buildExpense(
            meta = TransactionMeta(id = "tx-bad-acc", occurredAt = 10_000L),
            expenseCategoryAccountId = "acc:does_not_exist",
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("25.00"),
        )
        try {
            writer.saveTransaction(unknownAccountTx)
            fail("Expected LedgerValidationException for unknown account")
        } catch (e: LedgerValidationException) {
            assertTrue(e.errors.any { it is LedgerValidationError.UnknownAccount })
        }

        // 4. Currency mismatch against balance-sheet account (USD posting on CNY debit card)
        val currencyMismatchTx = engine.buildExpense(
            meta = TransactionMeta(id = "tx-bad-ccy", occurredAt = 10_000L),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("10.00", Currency.USD),
        )
        try {
            writer.saveTransaction(currencyMismatchTx)
            fail("Expected LedgerValidationException for account currency mismatch")
        } catch (e: LedgerValidationException) {
            assertTrue(e.errors.any { it is LedgerValidationError.AccountCurrencyMismatch })
        }

        // 5. Archived account rejected by default, allowed when explicitly permitted
        database.accountDao().setArchived(DefaultSeedCatalog.AccountIds.CASH, archived = true, updatedAt = 2_000L)
        val archivedTx = engine.buildExpense(
            meta = TransactionMeta(id = "tx-archived-acc", occurredAt = 10_000L),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
            paymentAccountId = DefaultSeedCatalog.AccountIds.CASH,
            amount = Money.fromDecimalString("15.00"),
        )
        try {
            writer.saveTransaction(archivedTx)
            fail("Expected LedgerValidationException for archived account")
        } catch (e: LedgerValidationException) {
            assertTrue(e.errors.any { it is LedgerValidationError.ArchivedAccountNotAllowed })
        }
        assertEquals(0, database.transactionDao().countTransactions())

        writer.saveTransaction(archivedTx, allowArchivedAccounts = true)
        assertEquals(1, database.transactionDao().countTransactions())
        writer.deleteTransaction("tx-archived-acc")

        // 6. Foreign key failure on nonexistent tagId rolls back header & postings atomically
        val badTagTx = engine.buildExpense(
            meta = TransactionMeta(
                id = "tx-bad-tag",
                occurredAt = 10_000L,
                tagIds = listOf("tag:nonexistent"),
            ),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("20.00"),
        )
        try {
            writer.saveTransaction(badTagTx)
            fail("Expected SQLiteConstraintException for missing tag FK")
        } catch (_: SQLiteConstraintException) {
            // Expected rollback
        }
        assertEquals(0, database.transactionDao().countTransactions())
        assertEquals(0, database.transactionDao().countPostings())
        assertEquals(0, database.transactionDao().countTransactionTags())

        // 7. Batch save rolls back valid first tx when second tx fails validation
        val validTx = engine.buildExpense(
            meta = TransactionMeta(id = "tx-batch-ok", occurredAt = 10_000L),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("18.00"),
        )
        try {
            writer.saveTransactions(listOf(validTx, unbalanced))
            fail("Expected batch rollback on second invalid transaction")
        } catch (_: LedgerValidationException) {
            // Expected rollback
        }
        assertEquals(0, database.transactionDao().countTransactions())
        assertEquals(0, database.transactionDao().countPostings())
    }

    @Test
    fun updateTransaction_preservesAttachmentsAndChildReferencesWithoutCascadeDelete() = runTest {
        val original = engine.buildExpense(
            meta = TransactionMeta(
                id = "tx-orig",
                occurredAt = 10_000L,
                payee = "咖啡店",
                tagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY),
            ),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_SNACKS,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("100.00"),
        )
        writer.saveTransaction(original)

        database.attachmentDao().upsert(
            Attachment(
                id = "att-1",
                transactionId = "tx-orig",
                localRelPath = "receipts/coffee_01.jpg",
                sha256 = "a".repeat(64),
                mimeType = "image/jpeg",
                sizeBytes = 4096L,
            ).toEntity(),
        )

        val refund = engine.buildRefund(
            meta = TransactionMeta(id = "tx-refund", occurredAt = 15_000L),
            originalTransactionId = "tx-orig",
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_SNACKS,
            refundToAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            refundAmount = Money.fromDecimalString("20.00"),
        )
        writer.saveTransaction(refund)

        // Update original transaction (different note, updated tags, updated amount)
        val updated = engine.buildExpense(
            meta = TransactionMeta(
                id = "tx-orig",
                occurredAt = 10_000L,
                payee = "精品咖啡店",
                note = "修改备注",
                tagIds = listOf(DefaultSeedCatalog.TagIds.FAMILY),
                updatedAt = 16_000L,
            ),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_SNACKS,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("120.00"),
        )
        writer.saveTransaction(updated)

        val reloadedOrig = database.transactionDao().getById("tx-orig")!!
        assertEquals("精品咖啡店", reloadedOrig.transaction.payee)
        assertEquals(listOf(DefaultSeedCatalog.TagIds.FAMILY), reloadedOrig.tags.map { it.id })
        // Attachment MUST survive update!
        assertEquals(1, reloadedOrig.attachments.size)
        assertEquals("att-1", reloadedOrig.attachments.single().id)
        // Refund ref_transaction_id MUST survive update!
        assertEquals("tx-orig", database.transactionDao().getById("tx-refund")!!.transaction.refTransactionId)
    }

    @Test
    fun foreignKeys_enforceCascadeSetNullAndRestrictCorrectly() = runTest {
        val loanAccountId = "acc:loan:car"
        database.accountDao().upsert(
            Account(
                id = loanAccountId,
                name = "车贷负债",
                type = AccountType.LIABILITY,
                subType = AccountSubType.LOAN,
                currency = Currency.CNY,
            ).toEntity(),
        )

        val orig = engine.buildExpense(
            meta = TransactionMeta(
                id = "tx-parent",
                occurredAt = 10_000L,
                tagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY, DefaultSeedCatalog.TagIds.FAMILY),
            ),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_SHOPPING_DAILY,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("200.00"),
        )
        writer.saveTransaction(orig)

        database.attachmentDao().upsert(
            Attachment(
                id = "att-parent",
                transactionId = "tx-parent",
                localRelPath = "receipts/r1.png",
                sha256 = "b".repeat(64),
                mimeType = "image/png",
                sizeBytes = 1024L,
            ).toEntity(),
        )

        val refund = engine.buildRefund(
            meta = TransactionMeta(id = "tx-child-refund", occurredAt = 12_000L),
            originalTransactionId = "tx-parent",
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_SHOPPING_DAILY,
            refundToAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            refundAmount = Money.fromDecimalString("50.00"),
        )
        writer.saveTransaction(refund)

        val loanRepayTx = engine.buildLoanRepayment(
            meta = TransactionMeta(id = "tx-loan-1", occurredAt = 13_000L),
            loanLiabilityAccountId = loanAccountId,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            principalAmount = Money.fromDecimalString("1000.00"),
            interestOrFeeAmount = Money.fromDecimalString("50.00"),
            interestExpenseAccountId = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
        )
        writer.saveTransaction(loanRepayTx)

        database.loanDao().upsertPlanWithInstallments(
            plan = LoanPlan(
                id = "plan-car",
                name = "车贷",
                method = LoanMethod.EQUAL_INSTALLMENT,
                totalPrincipalMinor = Money.fromDecimalString("12000.00").amountMinor,
                totalPeriods = 12,
                annualRateDecimal = "0.04",
                firstDueAt = 13_000L,
                monthlyRepayDay = 10,
                loanAccountId = loanAccountId,
                paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                interestExpenseAccountId = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
            ).toEntity(),
            installments = listOf(
                LoanInstallment(
                    id = "inst-car-1",
                    loanPlanId = "plan-car",
                    periodNumber = 1,
                    dueAt = 13_000L,
                    principalMinor = Money.fromDecimalString("1000.00").amountMinor,
                    interestOrFeeMinor = Money.fromDecimalString("50.00").amountMinor,
                    remainingPrincipalMinor = Money.fromDecimalString("11000.00").amountMinor,
                    transactionId = "tx-loan-1",
                ).toEntity(),
            ),
        )

        // RESTRICT: cannot delete account referenced by postings
        try {
            database.accountDao().deleteById(DefaultSeedCatalog.AccountIds.EXP_SHOPPING_DAILY)
            fail("Expected SQLiteConstraintException when deleting account with postings")
        } catch (_: SQLiteConstraintException) {
            // Expected RESTRICT
        }

        // RESTRICT: cannot delete parent category while child categories exist
        try {
            database.accountDao().deleteById(DefaultSeedCatalog.AccountIds.EXP_FOOD)
            fail("Expected SQLiteConstraintException when deleting parent category with children")
        } catch (_: SQLiteConstraintException) {
            // Expected RESTRICT
        }

        // CASCADE on Tag delete: removes transaction_tags links without deleting the transaction
        database.tagDao().deleteById(DefaultSeedCatalog.TagIds.FAMILY)
        val afterTagDelete = database.transactionDao().getById("tx-parent")!!
        assertEquals(listOf(DefaultSeedCatalog.TagIds.WORKDAY), afterTagDelete.tags.map { it.id })

        // CASCADE + SET_NULL on Transaction delete
        writer.deleteTransaction("tx-parent")
        assertNull(database.transactionDao().getById("tx-parent"))
        assertNull(database.attachmentDao().getById("att-parent"))
        // Refund survives with refTransactionId cleared via SET NULL
        val survivingRefund = database.transactionDao().getById("tx-child-refund")
        assertNotNull(survivingRefund)
        assertNull(survivingRefund!!.transaction.refTransactionId)

        // SET_NULL on LoanInstallment when repayment transaction is deleted
        writer.deleteTransaction("tx-loan-1")
        val installmentAfterTxDelete = database.loanDao().observeInstallmentsForPlan("plan-car").first().single()
        assertNull(installmentAfterTxDelete.transactionId)

        // CASCADE on LoanPlan delete removes installments
        database.loanDao().deletePlanById("plan-car")
        assertEquals(0, database.loanDao().countInstallmentsForPlan("plan-car"))
    }

    @Test
    fun openingBalanceDebtInvestAndThreeCurrencySplit_persistRoundTripAndVoid() = runTest {
        val hkdCashId = "acc:hkd_cash"
        val recvId = "acc:recv:bob"
        val payId = "acc:pay:carol"
        database.accountDao().upsertAll(
            listOf(
                Account(hkdCashId, "港币现金", AccountType.ASSET, AccountSubType.CASH, currency = Currency.HKD).toEntity(),
                Account(recvId, "应收-Bob", AccountType.ASSET, AccountSubType.RECEIVABLE, currency = Currency.CNY).toEntity(),
                Account(payId, "应付-Carol", AccountType.LIABILITY, AccountSubType.PAYABLE, currency = Currency.CNY).toEntity(),
            ),
        )

        val txs = listOf(
            // Opening balance reusing SystemAccounts.openingBalanceAccountId(CNY)
            engine.buildOpeningBalance(
                meta = TransactionMeta(id = "tx-open-1", occurredAt = 1_000L),
                accountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                accountType = AccountType.ASSET,
                balance = Money.fromDecimalString("50000.00"),
            ),
            // Lending + partial collection with interest + bad-debt write-off
            engine.buildDebtLend(
                meta = TransactionMeta(id = "tx-lend-1", occurredAt = 2_000L),
                receivableAccountId = recvId,
                fromAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                amount = Money.fromDecimalString("1000.00"),
            ),
            engine.buildDebtCollect(
                meta = TransactionMeta(id = "tx-collect-1", occurredAt = 3_000L),
                receivableAccountId = recvId,
                depositAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                principalAmount = Money.fromDecimalString("400.00"),
                interestAmount = Money.fromDecimalString("20.00"),
                interestIncomeAccountId = DefaultSeedCatalog.AccountIds.INC_INVEST_INTEREST,
            ),
            engine.buildBadDebtWriteOff(
                meta = TransactionMeta(id = "tx-baddebt-1", occurredAt = 4_000L),
                receivableAccountId = recvId,
                badDebtExpenseAccountId = DefaultSeedCatalog.AccountIds.EXP_FINANCE_BAD_DEBT,
                amount = Money.fromDecimalString("600.00"),
            ),
            // Borrowing + repayment with interest
            engine.buildDebtBorrow(
                meta = TransactionMeta(id = "tx-borrow-1", occurredAt = 5_000L),
                payableAccountId = payId,
                toAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                amount = Money.fromDecimalString("2000.00"),
            ),
            engine.buildDebtRepay(
                meta = TransactionMeta(id = "tx-repay-1", occurredAt = 6_000L),
                payableAccountId = payId,
                paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                principalAmount = Money.fromDecimalString("2000.00"),
                interestAmount = Money.fromDecimalString("30.00"),
                interestExpenseAccountId = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
            ),
            // Investment sell with realized gain + cash dividend with tax
            engine.buildInvestmentSell(
                meta = TransactionMeta(id = "tx-sell-1", occurredAt = 7_000L),
                holdingAccountId = btcHoldingId,
                cashAccountId = usdCashId,
                investmentAssetId = btcAssetId,
                quantity = Quantity.of("0.00500000"),
                unitPrice = UnitPrice.of("70000.00"),
                currency = Currency.USD,
                costBasis = Money.fromDecimalString("300.00", Currency.USD),
                realizedPnLAccountId = DefaultSeedCatalog.AccountIds.INC_INVEST_CAPITAL_GAIN,
            ),
            engine.buildInvestmentDividend(
                meta = TransactionMeta(id = "tx-div-1", occurredAt = 8_000L),
                cashAccountId = usdCashId,
                dividendIncomeAccountId = DefaultSeedCatalog.AccountIds.INC_INVEST_DIVIDEND,
                investmentAssetId = btcAssetId,
                amount = Money.fromDecimalString("50.00", Currency.USD),
                taxOrFee = Money.fromDecimalString("5.00", Currency.USD),
                taxExpenseAccountId = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
            ),
            // 3-currency custom split (CNY + USD + HKD -> 3 implicit Equity:Trading:{CCY} lines)
            engine.buildCustomSplit(
                meta = TransactionMeta(id = "tx-3ccy-1", occurredAt = 9_000L, note = "三币种混合消费"),
                postings = listOf(
                    PostingDraft(DefaultSeedCatalog.AccountIds.EXP_ENTERTAINMENT_TRAVEL, Money.fromDecimalString("1000.00", Currency.CNY)),
                    PostingDraft(usdCashId, Money.fromDecimalString("-100.00", Currency.USD)),
                    PostingDraft(hkdCashId, Money.fromDecimalString("-300.00", Currency.HKD)),
                ),
            ),
        )

        writer.saveTransactions(txs)
        assertEquals(txs.size, database.transactionDao().countTransactions())

        val reloaded3Ccy = database.transactionDao().getById("tx-3ccy-1")!!.toDomain()
        assertEquals(6, reloaded3Ccy.postings.size)
        assertEquals(3, reloaded3Ccy.systemBalancingPostings.size)
        LedgerValidator.requireValid(reloaded3Ccy)

        // Receivable & payable balances both settle to 0 after full lifecycle
        val recvBal = database.transactionDao().observeSingleAccountOriginalBalances(recvId).first().single().balanceMinor
        val payBal = database.transactionDao().observeSingleAccountOriginalBalances(payId).first().single().balanceMinor
        assertEquals(0L, recvBal)
        assertEquals(0L, payBal)

        // Soft-voiding bad-debt write-off restores receivable balance to +600.00
        writer.voidTransaction("tx-baddebt-1", updatedAt = 10_000L)
        val recvAfterVoid = database.transactionDao().observeSingleAccountOriginalBalances(recvId).first().single().balanceMinor
        assertEquals(Money.fromDecimalString("600.00").amountMinor, recvAfterVoid)
    }
}
