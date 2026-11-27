package io.github.kvtttba.droplet.core.database

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.repository.CreditCardRepaymentRequest
import io.github.kvtttba.droplet.core.database.repository.LocalCreditCardRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLedgerRepository
import io.github.kvtttba.droplet.core.database.repository.UpdateCreditCardParametersRequest
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementStatus
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
class CreditCardRepositoryTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var database: DropletDatabase
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var creditCardRepository: LocalCreditCardRepository
    private lateinit var ledgerEngine: LedgerEngine

    private var fakeNowMs: Long = LocalDate.of(2026, 11, 23)
        .atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private fun epochOn(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        val seeder = DefaultLedgerSeeder(database)
        val writer = LedgerTransactionWriter(database)
        ledgerEngine = LedgerEngine(baseCurrency = Currency.CNY)
        val clock = NetworkClock { fakeNowMs }

        ledgerRepository = LocalLedgerRepository(
            database = database,
            transactionWriter = writer,
            seeder = seeder,
            ledgerEngine = ledgerEngine,
        )
        creditCardRepository = LocalCreditCardRepository(
            database = database,
            transactionWriter = writer,
            seeder = seeder,
            ledgerEngine = ledgerEngine,
            clock = clock,
            zoneId = zone,
        )
        ledgerRepository.ensureSeeded(fakeNowMs)
        ledgerRepository.upsertExchangeRate(
            CachedExchangeRate(Currency.USD, Currency.CNY, "7.2", "manual", fakeNowMs),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun reactiveSummariesDetailAndOneTapRepayment_reconcileRoomFlowsAtomically() = runTest {
        val cardId = DefaultSeedCatalog.AccountIds.CREDIT_CARD

        // 1. Update seeded credit card parameters
        val updatedCard = creditCardRepository.updateCreditCardParameters(
            UpdateCreditCardParametersRequest(
                accountId = cardId,
                creditLimitMinor = Money.fromDecimalString("20000.00", Currency.CNY).amountMinor,
                billingDay = 5,
                dueDay = 25,
                remindDaysBefore = 3,
            ),
        )
        assertEquals(5, updatedCard.billingDay)
        assertEquals(25, updatedCard.dueDay)
        assertEquals(3, updatedCard.remindDaysBefore)

        // 2. Record 1 billed expense (Oct 20: ¥1200) and 1 unbilled expense (Nov 15: ¥350)
        ledgerRepository.saveTransaction(
            ledgerEngine.buildExpense(
                meta = TransactionMeta(occurredAt = epochOn(2026, 10, 20), payee = "十月餐饮"),
                expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_DINNER,
                paymentAccountId = cardId,
                amount = Money.fromDecimalString("1200.00", Currency.CNY),
            ),
        )
        ledgerRepository.saveTransaction(
            ledgerEngine.buildExpense(
                meta = TransactionMeta(occurredAt = epochOn(2026, 11, 15), payee = "十一月交通"),
                expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TAXI,
                paymentAccountId = cardId,
                amount = Money.fromDecimalString("350.00", Currency.CNY),
            ),
        )

        // 3. Observe summary & detail as of 2026-11-23 (2 days before due day -> DUE_SOON)
        val summaries = creditCardRepository.observeCreditCardSummaries(asOfEpochMs = fakeNowMs).first()
        val cardSummary = summaries.single { it.account.id == cardId }
        assertEquals(CreditCardStatementStatus.DUE_SOON, cardSummary.status)
        assertTrue(cardSummary.isInReminderWindow)
        assertEquals(Money.fromDecimalString("1200.00", Currency.CNY), cardSummary.remainingStatementDue)
        assertEquals(Money.fromDecimalString("350.00", Currency.CNY), cardSummary.unbilledRemainingDebt)
        assertEquals(Money.fromDecimalString("1550.00", Currency.CNY), cardSummary.currentDebt)
        assertEquals(Money.fromDecimalString("18450.00", Currency.CNY), cardSummary.availableCredit)

        val detailBefore = creditCardRepository.observeCreditCardDetail(cardId, asOfEpochMs = fakeNowMs).first()
        assertNotNull(detailBefore)
        assertEquals(1, detailBefore!!.billedTransactions.size)
        assertEquals("十月餐饮", detailBefore.billedTransactions.single().transaction.payee)
        assertEquals(1, detailBefore.unbilledTransactions.size)
        assertEquals("十一月交通", detailBefore.unbilledTransactions.single().transaction.payee)
        assertEquals(2, detailBefore.allTransactions.size)

        // 4. One-tap repayment of billed statement due (¥1,200) from Debit Card
        creditCardRepository.recordRepayment(
            CreditCardRepaymentRequest(
                creditCardAccountId = cardId,
                paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                repaymentAmount = Money.fromDecimalString("1200.00", Currency.CNY),
                occurredAt = fakeNowMs,
                note = "还清上期账单",
            ),
        )

        val detailAfter = creditCardRepository.observeCreditCardDetail(cardId, asOfEpochMs = fakeNowMs).first()!!
        assertEquals(CreditCardStatementStatus.PAID_OFF, detailAfter.summary.status)
        assertFalse(detailAfter.summary.isInReminderWindow)
        assertEquals(Money.zero(Currency.CNY), detailAfter.summary.remainingStatementDue)
        assertEquals(Money.fromDecimalString("1200.00", Currency.CNY), detailAfter.summary.statementPaidAfterClosing)
        assertEquals(Money.fromDecimalString("350.00", Currency.CNY), detailAfter.summary.currentDebt)
        assertEquals(Money.fromDecimalString("19650.00", Currency.CNY), detailAfter.summary.availableCredit)
        assertEquals(2, detailAfter.unbilledTransactions.size)
        assertEquals(3, detailAfter.allTransactions.size)

        // Snapshot API matches reactive Flow
        val snapshot = creditCardRepository.getCreditCardSummariesSnapshot(asOfEpochMs = fakeNowMs)
        assertEquals(detailAfter.summary, snapshot.single { it.account.id == cardId })
    }

    @Test
    fun crossCurrencyRepayment_writesBalancedTradingPostingsAtomically() = runTest {
        val usdCard = Account(
            id = "acct:usd_visa",
            name = "美元 Visa",
            type = AccountType.LIABILITY,
            subType = AccountSubType.CREDIT_CARD,
            currency = Currency.USD,
            creditLimitMinor = Money.fromDecimalString("5000.00", Currency.USD).amountMinor,
            billingDay = 10,
            dueDay = 28,
            remindDaysBefore = 3,
            createdAt = fakeNowMs,
            updatedAt = fakeNowMs,
        )
        ledgerRepository.saveAccount(
            account = usdCard,
            openingBalance = Money.fromDecimalString("100.00", Currency.USD),
            nowEpochMs = epochOn(2026, 11, 1),
        )

        val tx = creditCardRepository.recordRepayment(
            CreditCardRepaymentRequest(
                creditCardAccountId = usdCard.id,
                paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                repaymentAmount = Money.fromDecimalString("100.00", Currency.USD),
                paidFromAccountAmount = Money.fromDecimalString("725.00", Currency.CNY),
                occurredAt = fakeNowMs,
            ),
        )
        assertTrue(tx.postings.any { it.accountId == SystemAccounts.tradingAccountId(Currency.USD) })
        assertTrue(tx.postings.any { it.accountId == SystemAccounts.tradingAccountId(Currency.CNY) })

        val detail = creditCardRepository.observeCreditCardDetail(usdCard.id, asOfEpochMs = fakeNowMs).first()!!
        assertEquals(Money.zero(Currency.USD), detail.summary.currentDebt)
        assertEquals(CreditCardStatementStatus.PAID_OFF, detail.summary.status)
    }
}
