package io.github.kvtttba.droplet.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.kvtttba.droplet.core.database.repository.AutoPostDueInstallmentsReport
import io.github.kvtttba.droplet.core.database.repository.AutoPostedInstallmentItem
import io.github.kvtttba.droplet.core.database.repository.CreateLoanPlanRequest
import io.github.kvtttba.droplet.core.database.repository.EarlyPayoffLoanRequest
import io.github.kvtttba.droplet.core.database.repository.EarlyPayoffLoanResult
import io.github.kvtttba.droplet.core.database.repository.LoanDetailSnapshot
import io.github.kvtttba.droplet.core.database.repository.LoanPlanCreatedResult
import io.github.kvtttba.droplet.core.database.repository.LoanRepository
import io.github.kvtttba.droplet.core.database.repository.RepayInstallmentRequest
import io.github.kvtttba.droplet.core.database.repository.UpdateLoanPlanParametersRequest
import io.github.kvtttba.droplet.core.domain.loan.LoanPlanSummary
import io.github.kvtttba.droplet.core.domain.loan.LoanScheduleCalculator
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.InstallmentStatus
import io.github.kvtttba.droplet.core.model.LoanInstallment
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.LoanPlan
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.AppThemePreference
import io.github.kvtttba.droplet.core.security.LoanReminderPreferences
import io.github.kvtttba.droplet.core.security.LoanReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferences
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.core.security.RelockTimeout
import io.github.kvtttba.droplet.notification.LoanReminderNotifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LoanScheduleWorkerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var context: Context

    private inner class StatefulFakeLoanRepository(
        plans: List<LoanPlan>,
    ) : LoanRepository {
        val planList = plans.toMutableList()
        val installmentsByPlan = plans.associate { plan ->
            plan.id to LoanScheduleCalculator.generateSchedule(plan, zoneId = zone).toMutableList()
        }.toMutableMap()
        var throwError: Exception? = null

        override fun observeLoanSummaries(includeArchived: Boolean, asOfEpochMs: Long?): Flow<List<LoanPlanSummary>> =
            flowOf(emptyList())

        override fun observeLoanDetail(planId: String, asOfEpochMs: Long?): Flow<LoanDetailSnapshot?> =
            flowOf(null)

        override suspend fun getLoanSummariesSnapshot(includeArchived: Boolean, asOfEpochMs: Long?): List<LoanPlanSummary> {
            throwError?.let { throw it }
            val asOf = Instant.ofEpochMilli(asOfEpochMs ?: 0L).atZone(zone).toLocalDate()
            return planList.filter { includeArchived || !it.isArchived }.map { plan ->
                LoanScheduleCalculator.summarize(
                    plan = plan,
                    installments = installmentsByPlan[plan.id].orEmpty(),
                    asOfDate = asOf,
                    zoneId = zone,
                )
            }
        }

        override suspend fun autoPostDueInstallments(asOfEpochMs: Long?): AutoPostDueInstallmentsReport {
            throwError?.let { throw it }
            val nowMs = asOfEpochMs ?: 0L
            val asOfDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val posted = mutableListOf<AutoPostedInstallmentItem>()
            var scanned = 0
            for (plan in planList.filter { it.autoPost && !it.isArchived }) {
                val list = installmentsByPlan[plan.id] ?: continue
                for (i in list.indices) {
                    val inst = list[i]
                    val dueDate = Instant.ofEpochMilli(inst.dueAt).atZone(zone).toLocalDate()
                    if (inst.status == InstallmentStatus.PENDING &&
                        inst.transactionId == null &&
                        !dueDate.isAfter(asOfDate)
                    ) {
                        scanned++
                        val txId = "auto-tx-${inst.id}"
                        val paid = inst.copy(status = InstallmentStatus.PAID, transactionId = txId, paidAt = nowMs)
                        list[i] = paid
                        posted += AutoPostedInstallmentItem(plan, paid, txId)
                    }
                }
            }
            return AutoPostDueInstallmentsReport(
                scannedDueCount = scanned,
                postedItems = posted,
                skippedCount = 0,
                failures = emptyList(),
            )
        }

        override suspend fun createLoanPlan(request: CreateLoanPlanRequest): LoanPlanCreatedResult = error("unused")
        override suspend fun updateLoanPlanParameters(request: UpdateLoanPlanParametersRequest): LoanPlan = error("unused")
        override suspend fun repayInstallment(request: RepayInstallmentRequest): Transaction = error("unused")
        override suspend fun earlyPayoffLoan(request: EarlyPayoffLoanRequest): EarlyPayoffLoanResult = error("unused")
        override suspend fun setLoanPlanArchived(planId: String, archived: Boolean, nowEpochMs: Long?) = Unit
        override suspend fun deleteLoanPlanIfUnused(planId: String): Result<Unit> = Result.success(Unit)
    }

    private class FakeLoanReminderPrefsRepository(
        initial: LoanReminderPreferences = LoanReminderPreferences(),
    ) : LoanReminderPreferencesRepository {
        private val state = MutableStateFlow(initial)
        override val preferences: Flow<LoanReminderPreferences> = state
        override suspend fun currentPreferences(): LoanReminderPreferences = state.value
        override suspend fun setRemindersEnabled(enabled: Boolean) {
            state.value = state.value.copy(remindersEnabled = enabled)
        }

        override suspend fun markNotifiedOnDate(installmentIds: Collection<String>, localDateIso: String) {
            val map = state.value.lastNotifiedDateByInstallmentId.toMutableMap()
            installmentIds.forEach { map[it] = localDateIso }
            state.value = state.value.copy(lastNotifiedDateByInstallmentId = map)
        }

        override suspend fun clearNotificationHistory(installmentId: String?) {
            state.value = state.value.copy(
                lastNotifiedDateByInstallmentId = if (installmentId == null) {
                    emptyMap()
                } else {
                    state.value.lastNotifiedDateByInstallmentId - installmentId
                },
            )
        }
    }

    private class FakePrivacyPrefsRepository(
        initial: PrivacyPreferences = PrivacyPreferences(),
    ) : PrivacyPreferencesRepository {
        private val state = MutableStateFlow(initial)
        override val preferences: Flow<PrivacyPreferences> = state
        override suspend fun setBiometricLockEnabled(enabled: Boolean) = Unit
        override suspend fun setRelockTimeout(timeout: RelockTimeout) = Unit
        override suspend fun setFlagSecureEnabled(enabled: Boolean) = Unit
        override suspend fun setAmountsHidden(hidden: Boolean) {
            state.value = state.value.copy(amountsHidden = hidden)
        }

        override suspend fun toggleAmountsHidden(): Boolean = state.value.amountsHidden
        override suspend fun setThemeMode(themeMode: AppThemePreference) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
    }

    private class RecordingLoanNotifier(
        var allowPost: Boolean = true,
    ) : LoanReminderNotifier {
        val dueReminders = mutableListOf<Triple<LoanPlanSummary, LoanInstallment, Boolean>>()
        val autoPostNotices = mutableListOf<Triple<LoanPlan, LoanInstallment, Boolean>>()
        override fun ensureNotificationChannel() = Unit
        override fun canPostNotifications(): Boolean = allowPost
        override fun publishDueReminder(summary: LoanPlanSummary, installment: LoanInstallment, hideAmounts: Boolean): Boolean {
            if (!allowPost) return false
            dueReminders += Triple(summary, installment, hideAmounts)
            return true
        }

        override fun publishAutoPostedNotice(plan: LoanPlan, installment: LoanInstallment, hideAmounts: Boolean): Boolean {
            if (!allowPost) return false
            autoPostNotices += Triple(plan, installment, hideAmounts)
            return true
        }
    }

    private fun planOf(
        id: String,
        name: String,
        firstDue: LocalDate,
        autoPost: Boolean,
        remindDaysBefore: Int?,
    ): LoanPlan = LoanPlan(
        id = id,
        name = name,
        method = LoanMethod.FIXED_FEE,
        currency = Currency.CNY,
        totalPrincipalMinor = Money.fromDecimalString("6000.00", Currency.CNY).amountMinor,
        totalPeriods = 6,
        fixedFeePerPeriodMinor = Money.fromDecimalString("30.00", Currency.CNY).amountMinor,
        firstDueAt = firstDue.atStartOfDay(zone).toInstant().toEpochMilli(),
        monthlyRepayDay = firstDue.dayOfMonth,
        loanAccountId = "loan:$id",
        paymentAccountId = "acct:debit",
        interestExpenseAccountId = "cat:interest",
        autoPost = autoPost,
        remindDaysBefore = remindDaysBefore,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        LoanScheduleWorker.resetDependenciesResolver()
    }

    @Test
    fun doWork_autoPostsDuePlanAndSendsSameDayDeduplicatedRemindersWithPrivacyMask() = runTest {
        var currentDay = LocalDate.of(2026, 11, 25)
        val autoPlan = planOf(
            id = "loan:auto",
            name = "自动还款车贷",
            firstDue = LocalDate.of(2026, 11, 25), // Due today -> auto-posted!
            autoPost = true,
            remindDaysBefore = 3,
        )
        val manualReminderPlan = planOf(
            id = "loan:remind",
            name = "手动确认分期",
            firstDue = LocalDate.of(2026, 11, 27), // 2 days away -> inside 3-day reminder window
            autoPost = false,
            remindDaysBefore = 3,
        )
        val futurePlan = planOf(
            id = "loan:future",
            name = "远期分期",
            firstDue = LocalDate.of(2026, 12, 15), // Outside reminder window
            autoPost = false,
            remindDaysBefore = 3,
        )

        val repo = StatefulFakeLoanRepository(listOf(autoPlan, manualReminderPlan, futurePlan))
        val reminderPrefs = FakeLoanReminderPrefsRepository()
        val privacyPrefs = FakePrivacyPrefsRepository(PrivacyPreferences(amountsHidden = true))
        val notifier = RecordingLoanNotifier(allowPost = true)

        LoanScheduleWorker.dependenciesResolver = {
            LoanScheduleWorkerDependencies(
                loanRepository = repo,
                loanReminderPreferencesRepository = reminderPrefs,
                privacyPreferencesRepository = privacyPrefs,
                notifier = notifier,
                clock = NetworkClock { currentDay.atTime(9, 0).atZone(zone).toInstant().toEpochMilli() },
                zoneId = zone,
            )
        }

        // First run on 2026-11-25: auto-posts loan:auto p1 AND notifies loan:remind p1 with hideAmounts=true
        val run1 = TestListenableWorkerBuilder<LoanScheduleWorker>(context).build().doWork()
        assertTrue(run1 is ListenableWorker.Result.Success)
        val data1 = (run1 as ListenableWorker.Result.Success).outputData
        assertEquals(LoanScheduleWorker.STATUS_AUTO_POSTED_AND_NOTIFIED, data1.getString(LoanScheduleWorker.KEY_STATUS))
        assertEquals(1, data1.getInt(LoanScheduleWorker.KEY_AUTO_POSTED_COUNT, -1))
        assertEquals(1, data1.getInt(LoanScheduleWorker.KEY_NOTIFIED_COUNT, -1))
        assertEquals(0, data1.getInt(LoanScheduleWorker.KEY_SKIPPED_DUPLICATE_COUNT, -1))
        assertEquals(1, notifier.autoPostNotices.size)
        assertTrue(notifier.autoPostNotices.single().third) // hideAmounts=true
        assertEquals(1, notifier.dueReminders.size)
        assertEquals("loan:remind", notifier.dueReminders.single().first.plan.id)
        assertTrue(notifier.dueReminders.single().third) // hideAmounts=true

        // Second run on SAME day 2026-11-25: auto-post is 0 (idempotent) AND reminder is deduplicated!
        val run2 = TestListenableWorkerBuilder<LoanScheduleWorker>(context).build().doWork()
        val data2 = (run2 as ListenableWorker.Result.Success).outputData
        assertEquals(LoanScheduleWorker.STATUS_ALREADY_NOTIFIED_TODAY, data2.getString(LoanScheduleWorker.KEY_STATUS))
        assertEquals(0, data2.getInt(LoanScheduleWorker.KEY_AUTO_POSTED_COUNT, -1))
        assertEquals(0, data2.getInt(LoanScheduleWorker.KEY_NOTIFIED_COUNT, -1))
        assertEquals(1, data2.getInt(LoanScheduleWorker.KEY_SKIPPED_DUPLICATE_COUNT, -1))
        assertEquals(1, notifier.dueReminders.size)

        // Next day 2026-11-26: manualReminderPlan still pending -> notifies again!
        currentDay = LocalDate.of(2026, 11, 26)
        val run3 = TestListenableWorkerBuilder<LoanScheduleWorker>(context).build().doWork()
        val data3 = (run3 as ListenableWorker.Result.Success).outputData
        assertEquals(LoanScheduleWorker.STATUS_NOTIFIED, data3.getString(LoanScheduleWorker.KEY_STATUS))
        assertEquals(1, data3.getInt(LoanScheduleWorker.KEY_NOTIFIED_COUNT, -1))
        assertEquals(2, notifier.dueReminders.size)
    }

    @Test
    fun doWork_permissionDeniedDoesNotStampDedupAndErrorsRetryThenFail() = runTest {
        val today = LocalDate.of(2026, 11, 26)
        val remindPlan = planOf(
            id = "loan:remind",
            name = "待提醒分期",
            firstDue = LocalDate.of(2026, 11, 27),
            autoPost = false,
            remindDaysBefore = 3,
        )
        val repo = StatefulFakeLoanRepository(listOf(remindPlan))
        val reminderPrefs = FakeLoanReminderPrefsRepository()
        val notifier = RecordingLoanNotifier(allowPost = false)

        LoanScheduleWorker.dependenciesResolver = {
            LoanScheduleWorkerDependencies(
                loanRepository = repo,
                loanReminderPreferencesRepository = reminderPrefs,
                privacyPreferencesRepository = FakePrivacyPrefsRepository(),
                notifier = notifier,
                clock = NetworkClock { today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli() },
                zoneId = zone,
            )
        }

        val denied = TestListenableWorkerBuilder<LoanScheduleWorker>(context).build().doWork()
        val deniedData = (denied as ListenableWorker.Result.Success).outputData
        assertEquals(LoanScheduleWorker.STATUS_PERMISSION_DENIED, deniedData.getString(LoanScheduleWorker.KEY_STATUS))
        assertFalse(reminderPrefs.currentPreferences().wasNotifiedOn("loan:remind:p1", "2026-11-26"))

        // Unexpected exception -> retry below max attempts, failure at max attempts
        repo.throwError = IllegalStateException("DB read error")
        val retryRun = TestListenableWorkerBuilder<LoanScheduleWorker>(context)
            .setRunAttemptCount(1)
            .build()
            .doWork()
        assertEquals(ListenableWorker.Result.retry(), retryRun)

        val finalRun = TestListenableWorkerBuilder<LoanScheduleWorker>(context)
            .setRunAttemptCount(LoanScheduleWorker.MAX_RETRY_ATTEMPTS)
            .build()
            .doWork()
        assertTrue(finalRun is ListenableWorker.Result.Failure)
    }
}
