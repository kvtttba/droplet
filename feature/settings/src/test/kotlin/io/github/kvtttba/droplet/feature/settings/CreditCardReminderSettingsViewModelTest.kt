package io.github.kvtttba.droplet.feature.settings

import io.github.kvtttba.droplet.core.database.repository.CreditCardDetailSnapshot
import io.github.kvtttba.droplet.core.database.repository.CreditCardRepaymentRequest
import io.github.kvtttba.droplet.core.database.repository.CreditCardRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerTransactionRecord
import io.github.kvtttba.droplet.core.database.repository.UpdateCreditCardParametersRequest
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementCalculator
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementSummary
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferences
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferencesRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreditCardReminderSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeReminderPrefsRepo : CreditCardReminderPreferencesRepository {
        val state = MutableStateFlow(CreditCardReminderPreferences())
        override val preferences: Flow<CreditCardReminderPreferences> = state
        override suspend fun currentPreferences(): CreditCardReminderPreferences = state.value
        override suspend fun setRemindersEnabled(enabled: Boolean) {
            state.value = state.value.copy(remindersEnabled = enabled)
        }

        override suspend fun setDefaultRemindDaysBefore(days: Int) {
            state.value = state.value.copy(defaultRemindDaysBefore = days)
        }

        override suspend fun markNotifiedOnDate(accountIds: Collection<String>, localDateIso: String) = Unit
        override suspend fun clearNotificationHistory(accountId: String?) = Unit
    }

    private class FakeCreditCardRepo : CreditCardRepository {
        val observedDefaultDays = mutableListOf<Int>()
        private val card = Account(
            id = "acct:cc",
            name = "招行信用卡",
            type = AccountType.LIABILITY,
            subType = AccountSubType.CREDIT_CARD,
            currency = Currency.CNY,
            creditLimitMinor = Money.fromDecimalString("30000.00", Currency.CNY).amountMinor,
            billingDay = 5,
            dueDay = 25,
            remindDaysBefore = null,
        )

        override fun observeCreditCardSummaries(
            includeArchived: Boolean,
            defaultRemindDaysBefore: Int,
            asOfEpochMs: Long?,
        ): Flow<List<CreditCardStatementSummary>> {
            observedDefaultDays += defaultRemindDaysBefore
            return MutableStateFlow(
                listOf(
                    CreditCardStatementCalculator.calculate(
                        account = card,
                        transactions = emptyList(),
                        asOfDate = LocalDate.of(2026, 11, 23),
                        defaultRemindDaysBefore = defaultRemindDaysBefore,
                    ),
                ),
            )
        }

        override fun observeCreditCardDetail(
            accountId: String,
            defaultRemindDaysBefore: Int,
            asOfEpochMs: Long?,
        ): Flow<CreditCardDetailSnapshot?> = MutableStateFlow(null)

        override fun observeAccountTransactions(
            accountId: String,
            status: TransactionStatus,
        ): Flow<List<LedgerTransactionRecord>> = MutableStateFlow(emptyList())

        override suspend fun getCreditCardSummariesSnapshot(
            includeArchived: Boolean,
            defaultRemindDaysBefore: Int,
            asOfEpochMs: Long?,
        ): List<CreditCardStatementSummary> = emptyList()

        override suspend fun recordRepayment(request: CreditCardRepaymentRequest): Transaction = error("unused")
        override suspend fun updateCreditCardParameters(request: UpdateCreditCardParametersRequest): Account = error("unused")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun togglesRemindersAndPropagatesDefaultRemindDaysToCardSummaries() = runTest(testDispatcher) {
        val prefs = FakeReminderPrefsRepo()
        val cards = FakeCreditCardRepo()
        val vm = CreditCardReminderSettingsViewModel(prefs, cards)

        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(vm.uiState.value.remindersEnabled)
        assertEquals(3, vm.uiState.value.defaultRemindDaysBefore)
        assertEquals(3, vm.uiState.value.creditCardSummaries.single().effectiveRemindDaysBefore)

        vm.setDefaultRemindDaysBefore(5)
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.defaultRemindDaysBefore)
        assertEquals(5, vm.uiState.value.creditCardSummaries.single().effectiveRemindDaysBefore)

        vm.setRemindersEnabled(false)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.remindersEnabled)

        collector.cancel()
    }
}
