package io.github.kvtttba.droplet.core.database

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.repository.BatchSettleReimbursementsRequest
import io.github.kvtttba.droplet.core.database.repository.CreateDebtProfileRequest
import io.github.kvtttba.droplet.core.database.repository.DebtBadDebtWriteOffRequest
import io.github.kvtttba.droplet.core.database.repository.DebtBorrowRequest
import io.github.kvtttba.droplet.core.database.repository.DebtCollectRequest
import io.github.kvtttba.droplet.core.database.repository.DebtLendRequest
import io.github.kvtttba.droplet.core.database.repository.DebtRepayRequest
import io.github.kvtttba.droplet.core.database.repository.LocalDebtRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LocalReimbursementAndRefundRepository
import io.github.kvtttba.droplet.core.database.repository.RecordRefundRequest
import io.github.kvtttba.droplet.core.database.repository.SettleReimbursementRequest
import io.github.kvtttba.droplet.core.database.repository.UpdateDebtProfileRequest
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.debt.DebtProfileStatus
import io.github.kvtttba.droplet.core.domain.debt.DebtSummaryCalculator
import io.github.kvtttba.droplet.core.domain.investment.PortfolioSummaryAggregator
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DebtAndReimbursementRepositoryTest {

    private lateinit var database: DropletDatabase
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var debtRepository: LocalDebtRepository
    private lateinit var reimburseRefundRepository: LocalReimbursementAndRefundRepository
    private lateinit var engine: LedgerEngine

    private val zoneId = DebtSummaryCalculator.DEFAULT_ZONE_ID
    private val fixedNowMs = LocalDate.of(2026, 11, 28)
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        engine = LedgerEngine(baseCurrency = Currency.CNY)
        val writer = LedgerTransactionWriter(database)
        val seeder = DefaultLedgerSeeder(database)
        val clock = NetworkClock { fixedNowMs }

        ledgerRepository = LocalLedgerRepository(database, writer, seeder, engine)
        debtRepository = LocalDebtRepository(database, writer, seeder, engine, clock, zoneId)
        reimburseRefundRepository = LocalReimbursementAndRefundRepository(database, writer, seeder, engine, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun debtRepository_fullLifecycleReflectsInPortfolioSummaryAndGuardsDeletion() = runBlocking {
        ledgerRepository.ensureSeeded(fixedNowMs)

        val dueMs = LocalDate.of(2026, 11, 30).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val created = debtRepository.createDebtProfile(
            CreateDebtProfileRequest(
                name = "张三",
                currency = Currency.CNY,
                defaultDueAt = dueMs,
                annualInterestRateDecimal = "0.05",
                note = "周转借款",
            ),
        )
        val profileId = created.profile.id
        assertNotNull(database.accountDao().getById(created.receivableAccount.id))
        assertNotNull(database.accountDao().getById(created.payableAccount.id))

        // Lend ¥1,000 -> receivable ¥1,000, status DUE_SOON (2 days)
        debtRepository.recordLend(
            DebtLendRequest(
                profileId = profileId,
                fromAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                amount = Money.fromDecimalString("1000.00", Currency.CNY),
            ),
        )

        val detailAfterLend = requireNotNull(debtRepository.observeDebtDetail(profileId).first())
        assertEquals(Money.fromDecimalString("1000.00", Currency.CNY), detailAfterLend.summary.receivableBalance)
        assertEquals(DebtProfileStatus.DUE_SOON, detailAfterLend.summary.status)
        assertTrue(detailAfterLend.summary.isInReminderWindow)

        // PortfolioSummary should see the receivable asset (+1,000) and debit card (-1,000), net worth 0
        val allAccounts = ledgerRepository.observeAllAccounts(includeArchived = false, includeSystem = true).first()
        val balancesById = ledgerRepository.observeAccountBalances().first()
            .associate { it.accountId to Money(it.balanceMinor, it.currency) }
        val portfolio = PortfolioSummaryAggregator.aggregate(
            accounts = allAccounts,
            accountBalancesById = balancesById,
            positions = emptyList(),
            baseCurrency = Currency.CNY,
        )
        assertEquals(Money.fromDecimalString("1000.00", Currency.CNY), portfolio.totalAssetsBase)
        assertEquals(Money.fromDecimalString("1000.00", Currency.CNY), portfolio.totalLiabilitiesBase)
        assertEquals(Money.zero(Currency.CNY), portfolio.netWorthBase)

        // Cannot delete profile that already has postings
        val deleteUsed = debtRepository.deleteDebtProfileIfUnused(profileId)
        assertTrue(deleteUsed.isFailure)

        // Partial collect ¥400 principal + ¥20 interest income
        debtRepository.recordCollect(
            DebtCollectRequest(
                profileId = profileId,
                depositAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                principalAmount = Money.fromDecimalString("400.00", Currency.CNY),
                interestAmount = Money.fromDecimalString("20.00", Currency.CNY),
            ),
        )

        // Bad-debt write-off remaining ¥600
        debtRepository.recordBadDebtWriteOff(
            DebtBadDebtWriteOffRequest(
                profileId = profileId,
                amount = Money.fromDecimalString("600.00", Currency.CNY),
            ),
        )

        // Borrow ¥500 and repay ¥500 + ¥10 interest
        debtRepository.recordBorrow(
            DebtBorrowRequest(
                profileId = profileId,
                toAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                amount = Money.fromDecimalString("500.00", Currency.CNY),
            ),
        )
        debtRepository.recordRepay(
            DebtRepayRequest(
                profileId = profileId,
                paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                principalAmount = Money.fromDecimalString("500.00", Currency.CNY),
                interestAmount = Money.fromDecimalString("10.00", Currency.CNY),
            ),
        )

        val finalDetail = requireNotNull(debtRepository.observeDebtDetail(profileId).first())
        assertEquals(DebtProfileStatus.SETTLED, finalDetail.summary.status)
        assertEquals(Money.zero(Currency.CNY), finalDetail.summary.receivableBalance)
        assertEquals(Money.zero(Currency.CNY), finalDetail.summary.payableBalance)
        assertEquals(Money.fromDecimalString("20.00", Currency.CNY), finalDetail.summary.totalInterestIncome)
        assertEquals(Money.fromDecimalString("10.00", Currency.CNY), finalDetail.summary.totalInterestExpense)
        assertEquals(Money.fromDecimalString("600.00", Currency.CNY), finalDetail.summary.totalBadDebtWriteOff)
        assertEquals(5, finalDetail.transactions.size)

        // Rename profile cascades to dedicated receivable/payable account names
        debtRepository.updateDebtProfile(
            UpdateDebtProfileRequest(profileId = profileId, name = "张老三", clearDefaultDueAt = true),
        )
        assertEquals("张老三 · 应收", database.accountDao().getById(created.receivableAccount.id)?.name)
        assertEquals("张老三 · 应付", database.accountDao().getById(created.payableAccount.id)?.name)

        // Unused profile can be cleanly deleted along with its dedicated accounts
        val unused = debtRepository.createDebtProfile(CreateDebtProfileRequest(name = "临时往来人"))
        assertTrue(debtRepository.deleteDebtProfileIfUnused(unused.profile.id).isSuccess)
        assertEquals(null, database.debtProfileDao().getById(unused.profile.id))
        assertEquals(null, database.accountDao().getById(unused.receivableAccount.id))
    }

    @Test
    fun reimbursementSettlement_syncsParentStateOnSettleVoidRestoreAndBatch() = runBlocking {
        ledgerRepository.ensureSeeded(fixedNowMs)

        val advance1 = engine.buildReimbursableAdvance(
            meta = TransactionMeta(occurredAt = fixedNowMs, payee = "出差高铁"),
            reimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
            paymentAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
            amount = Money.fromDecimalString("500.00", Currency.CNY),
        )
        val advance2 = engine.buildReimbursableAdvance(
            meta = TransactionMeta(occurredAt = fixedNowMs + 1000L, payee = "差旅酒店"),
            reimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
            paymentAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
            amount = Money.fromDecimalString("800.00", Currency.CNY),
        )
        ledgerRepository.saveTransaction(advance1)
        ledgerRepository.saveTransaction(advance2)

        val initialOverview = reimburseRefundRepository.observeReimbursementOverview().first()
        assertEquals(2, initialOverview.pendingCount)
        assertEquals(Money.fromDecimalString("1300.00", Currency.CNY), initialOverview.primaryPendingTotal)

        // Single settlement with shortfall (¥450 received, ¥50 personal expense -> SETTLED)
        val settled1 = reimburseRefundRepository.settleReimbursement(
            SettleReimbursementRequest(
                advanceTransactionId = advance1.id,
                depositAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                settledReimbursementAmount = Money.fromDecimalString("500.00", Currency.CNY),
                receivedAmount = Money.fromDecimalString("450.00", Currency.CNY),
                shortfallExpenseAccountId = DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_LONG_DISTANCE,
            ),
        )
        assertEquals(ReimburseState.SETTLED, settled1.updatedAdvanceState)
        assertEquals(ReimburseState.SETTLED, ledgerRepository.getTransactionById(advance1.id)?.reimburseState)

        // Voiding the settlement automatically rolls parent advance1 state back to PENDING
        ledgerRepository.voidTransaction(settled1.settlementTransaction.id, fixedNowMs + 2000L)
        assertEquals(ReimburseState.PENDING, ledgerRepository.getTransactionById(advance1.id)?.reimburseState)

        // Restoring the settlement transitions parent advance1 back to SETTLED
        ledgerRepository.restoreTransaction(settled1.settlementTransaction.id, fixedNowMs + 3000L)
        assertEquals(ReimburseState.SETTLED, ledgerRepository.getTransactionById(advance1.id)?.reimburseState)

        // Batch settle remaining advance2
        val batch = reimburseRefundRepository.batchSettleReimbursements(
            BatchSettleReimbursementsRequest(
                advanceTransactionIds = listOf(advance2.id),
                depositAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            ),
        )
        assertEquals(1, batch.settledCount)

        val finalOverview = reimburseRefundRepository.observeReimbursementOverview().first()
        assertEquals(0, finalOverview.pendingCount)
        assertEquals(2, finalOverview.settledCount)
        assertEquals(Money.zero(Currency.CNY), finalOverview.primaryPendingTotal)
    }

    @Test
    fun multiStageRefundRepository_updatesNetExpenseAndMonthlyPeriodSummary() = runBlocking {
        ledgerRepository.ensureSeeded(fixedNowMs)

        val expense = engine.buildExpense(
            meta = TransactionMeta(occurredAt = fixedNowMs, payee = "数码网购"),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_SHOPPING_DIGITAL,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("300.00", Currency.CNY),
        )
        ledgerRepository.saveTransaction(expense)

        val firstRefund = reimburseRefundRepository.recordRefund(
            RecordRefundRequest(
                originalExpenseTransactionId = expense.id,
                refundToAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                refundAmount = Money.fromDecimalString("100.00", Currency.CNY),
                note = "部分退货",
            ),
        )
        assertEquals(Money.fromDecimalString("200.00", Currency.CNY), firstRefund.updatedRefundSummary.netExpenseAmount)

        val secondRefund = reimburseRefundRepository.recordRefund(
            RecordRefundRequest(
                originalExpenseTransactionId = expense.id,
                refundToAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                refundAmount = Money.fromDecimalString("80.00", Currency.CNY),
                note = "退运费险",
            ),
        )
        assertEquals(Money.fromDecimalString("180.00", Currency.CNY), secondRefund.updatedRefundSummary.cumulativeRefundedAmount)
        assertEquals(Money.fromDecimalString("120.00", Currency.CNY), secondRefund.updatedRefundSummary.netExpenseAmount)
        assertEquals(2, secondRefund.updatedRefundSummary.refundCount)

        val observedSummary = requireNotNull(
            reimburseRefundRepository.observeExpenseRefundSummary(expense.id).first(),
        )
        assertEquals(Money.fromDecimalString("120.00", Currency.CNY), observedSummary.remainingRefundableAmount)

        // Monthly period expense summary should reflect net ¥120.00
        val periodSummary = ledgerRepository.observePeriodSummary(
            baseCurrency = Currency.CNY,
            startInclusive = fixedNowMs - 86_400_000L,
            endExclusive = fixedNowMs + 86_400_000L,
        ).first()
        assertEquals(Money.fromDecimalString("120.00", Currency.CNY).amountMinor, periodSummary.netExpenseBaseMinor)

        // Exceeding remaining ¥120.00 must fail
        var failedOverRefund = false
        try {
            reimburseRefundRepository.recordRefund(
                RecordRefundRequest(
                    originalExpenseTransactionId = expense.id,
                    refundToAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                    refundAmount = Money.fromDecimalString("120.01", Currency.CNY),
                ),
            )
        } catch (_: IllegalArgumentException) {
            failedOverRefund = true
        }
        assertTrue(failedOverRefund)
        assertFalse(observedSummary.isFullyRefunded)
    }
}
