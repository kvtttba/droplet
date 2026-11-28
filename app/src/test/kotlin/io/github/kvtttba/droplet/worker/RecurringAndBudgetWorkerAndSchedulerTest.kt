package io.github.kvtttba.droplet.worker

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.repository.LocalBudgetRepository
import io.github.kvtttba.droplet.core.database.repository.LocalRecurringRuleRepository
import io.github.kvtttba.droplet.core.database.repository.SaveBudgetRequest
import io.github.kvtttba.droplet.core.database.repository.SaveRecurringRuleRequest
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.automation.BudgetAlertLevel
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgress
import io.github.kvtttba.droplet.core.domain.automation.RecurringPayload
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.RecurringFrequency
import io.github.kvtttba.droplet.core.model.RecurringRule
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.AppThemePreference
import io.github.kvtttba.droplet.core.security.BudgetAndRecurringPreferences
import io.github.kvtttba.droplet.core.security.BudgetAndRecurringPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferences
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.core.security.RelockTimeout
import io.github.kvtttba.droplet.notification.PlanningAlertNotifier
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RecurringAndBudgetWorkerAndSchedulerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var context: Context
    private lateinit var database: DropletDatabase

    private class NoOpWorker(
        appContext: Context,
        params: WorkerParameters,
    ) : Worker(appContext, params) {
        override fun doWork(): Result = Result.success()
    }

    private class FakeAlertPrefsRepository(
        initial: BudgetAndRecurringPreferences = BudgetAndRecurringPreferences(),
    ) : BudgetAndRecurringPreferencesRepository {
        private val state = MutableStateFlow(initial)
        override val preferences: Flow<BudgetAndRecurringPreferences> = state
        override suspend fun currentPreferences(): BudgetAndRecurringPreferences = state.value
        override suspend fun setBudgetAlertsEnabled(enabled: Boolean) {
            state.value = state.value.copy(budgetAlertsEnabled = enabled)
        }
        override suspend fun setRecurringRemindersEnabled(enabled: Boolean) {
            state.value = state.value.copy(recurringRemindersEnabled = enabled)
        }
        override suspend fun markBudgetAlertsSent(alertKeys: Collection<String>) {
            state.value = state.value.copy(sentBudgetAlertKeys = state.value.sentBudgetAlertKeys + alertKeys)
        }
        override suspend fun markRecurringRemindersSent(reminderKeys: Collection<String>) {
            state.value = state.value.copy(sentRecurringReminderKeys = state.value.sentRecurringReminderKeys + reminderKeys)
        }
        override suspend fun clearBudgetAlertHistory(budgetIdPrefix: String?) {
            state.value = state.value.copy(sentBudgetAlertKeys = emptySet())
        }
        override suspend fun clearRecurringReminderHistory(ruleIdPrefix: String?) {
            state.value = state.value.copy(sentRecurringReminderKeys = emptySet())
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

    private class RecordingPlanningNotifier(
        var allowPost: Boolean = true,
    ) : PlanningAlertNotifier {
        val budgetAlerts = mutableListOf<Pair<BudgetProgress, Boolean>>()
        val autoPostNotices = mutableListOf<Triple<RecurringRule, RecurringPayload, Boolean>>()
        val pendingReminders = mutableListOf<Triple<RecurringRule, RecurringPayload, Boolean>>()

        override fun ensureNotificationChannel() = Unit
        override fun canPostNotifications(): Boolean = allowPost

        override fun publishBudgetAlert(progress: BudgetProgress, hideAmounts: Boolean): Boolean {
            if (!allowPost) return false
            budgetAlerts += progress to hideAmounts
            return true
        }

        override fun publishRecurringAutoPosted(
            rule: RecurringRule,
            payload: RecurringPayload,
            hideAmounts: Boolean,
        ): Boolean {
            if (!allowPost) return false
            autoPostNotices += Triple(rule, payload, hideAmounts)
            return true
        }

        override fun publishRecurringPendingConfirmation(
            rule: RecurringRule,
            payload: RecurringPayload,
            hideAmounts: Boolean,
            zoneId: ZoneId,
        ): Boolean {
            if (!allowPost) return false
            pendingReminders += Triple(rule, payload, hideAmounts)
            return true
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
    }

    @After
    fun tearDown() {
        RecurringAndBudgetWorker.resetDependenciesResolver()
        database.close()
    }

    @Test
    fun worker_autoPostsDueRuleTriggersBudgetWarningAndDeduplicatesSubsequentRun() = runTest {
        val nowMs = LocalDate.of(2026, 11, 20).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val clock = NetworkClock { nowMs }
        val seeder = DefaultLedgerSeeder(database)
        val writer = LedgerTransactionWriter(database)
        val engine = LedgerEngine(baseCurrency = Currency.CNY)
        seeder.seedIfNeeded(nowMs)

        val budgetRepo = LocalBudgetRepository(database, seeder, clock, zone)
        val recurringRepo = LocalRecurringRuleRepository(database, writer, seeder, engine, clock, zone)
        val alertPrefs = FakeAlertPrefsRepository()
        val privacyPrefs = FakePrivacyPrefsRepository(PrivacyPreferences(amountsHidden = true))
        val notifier = RecordingPlanningNotifier(allowPost = true)
        val evaluator = BudgetAlertEvaluator(budgetRepo, alertPrefs, privacyPrefs, notifier, clock)

        // Monthly overall budget ¥1,000 (80% warning)
        budgetRepo.saveBudget(
            SaveBudgetRequest(
                period = BudgetPeriod.MONTHLY,
                categoryAccountId = null,
                limitAmountMinor = Money.fromDecimalString("1000.00").amountMinor,
                warnThresholdPct = 80,
            ),
        )
        // Auto-post rule of ¥850 due today -> crosses 80% warning!
        recurringRepo.saveRule(
            SaveRecurringRuleRequest(
                name = "物业宽带",
                frequency = RecurringFrequency.MONTHLY,
                nextRunAt = nowMs,
                payload = RecurringPayload(
                    displayType = TransactionDisplayType.EXPENSE,
                    amountMinor = Money.fromDecimalString("850.00").amountMinor,
                    categoryAccountId = DefaultSeedCatalog.AccountIds.EXP_HOUSING_PROPERTY_NET,
                    accountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                ),
                autoPost = true,
            ),
        )
        // Manual confirmation rule due today
        recurringRepo.saveRule(
            SaveRecurringRuleRequest(
                name = "健身房月卡",
                frequency = RecurringFrequency.MONTHLY,
                nextRunAt = nowMs,
                payload = RecurringPayload(
                    displayType = TransactionDisplayType.EXPENSE,
                    amountMinor = Money.fromDecimalString("200.00").amountMinor,
                    categoryAccountId = DefaultSeedCatalog.AccountIds.EXP_ENTERTAINMENT_FITNESS,
                    accountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
                ),
                autoPost = false,
            ),
        )

        RecurringAndBudgetWorker.dependenciesResolver = {
            RecurringAndBudgetWorkerDependencies(
                recurringRuleRepository = recurringRepo,
                budgetAlertEvaluator = evaluator,
                alertPreferencesRepository = alertPrefs,
                privacyPreferencesRepository = privacyPrefs,
                notifier = notifier,
                clock = clock,
                zoneId = zone,
            )
        }

        // First run: auto-posts ¥850, notifies pending manual rule, AND fires 80% budget warning with hideAmounts=true
        val run1 = TestListenableWorkerBuilder<RecurringAndBudgetWorker>(context).build().doWork()
        assertTrue(run1 is ListenableWorker.Result.Success)
        val data1 = (run1 as ListenableWorker.Result.Success).outputData
        assertEquals(RecurringAndBudgetWorker.STATUS_AUTO_POSTED_AND_NOTIFIED, data1.getString(RecurringAndBudgetWorker.KEY_STATUS))
        assertEquals(1, data1.getInt(RecurringAndBudgetWorker.KEY_RECURRING_AUTO_POSTED_COUNT, -1))
        assertEquals(1, data1.getInt(RecurringAndBudgetWorker.KEY_RECURRING_PENDING_REMINDED_COUNT, -1))
        assertEquals(1, data1.getInt(RecurringAndBudgetWorker.KEY_BUDGET_ALERT_NOTIFIED_COUNT, -1))
        assertEquals(1, notifier.autoPostNotices.size)
        assertTrue(notifier.autoPostNotices.single().third)
        assertEquals(1, notifier.pendingReminders.size)
        assertTrue(notifier.pendingReminders.single().third)
        assertEquals(1, notifier.budgetAlerts.size)
        assertEquals(BudgetAlertLevel.WARNING, notifier.budgetAlerts.single().first.alertLevel)
        assertTrue(notifier.budgetAlerts.single().second)

        // Second run same day: strictly idempotent (0 auto-posts, 0 duplicate notifications)
        val run2 = TestListenableWorkerBuilder<RecurringAndBudgetWorker>(context).build().doWork()
        val data2 = (run2 as ListenableWorker.Result.Success).outputData
        assertEquals(RecurringAndBudgetWorker.STATUS_ALREADY_NOTIFIED, data2.getString(RecurringAndBudgetWorker.KEY_STATUS))
        assertEquals(0, data2.getInt(RecurringAndBudgetWorker.KEY_RECURRING_AUTO_POSTED_COUNT, -1))
        assertEquals(0, data2.getInt(RecurringAndBudgetWorker.KEY_RECURRING_PENDING_REMINDED_COUNT, -1))
        assertEquals(0, data2.getInt(RecurringAndBudgetWorker.KEY_BUDGET_ALERT_NOTIFIED_COUNT, -1))
        assertEquals(2, data2.getInt(RecurringAndBudgetWorker.KEY_SKIPPED_DUPLICATE_COUNT, -1))

        // Confirm manual rule (+¥200 -> total ¥1,050 -> EXCEEDED) and evaluate -> fires 100% exceeded alert once!
        val manualId = recurringRepo.getRulesWithStatusSnapshot(nowMs).first { it.isPendingConfirmation }.rule.id
        recurringRepo.confirmAndPostDueRule(manualId, nowMs)
        val exceededEval = evaluator.evaluateAndNotify(nowMs)
        assertEquals(1, exceededEval.notifiedCount)
        assertEquals(BudgetAlertLevel.EXCEEDED, notifier.budgetAlerts.last().first.alertLevel)
    }

    @Test
    fun scheduler_enqueuesDailyOfflinePeriodicWorkAndCancelsWhenDisabled() {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = NoOpWorker(appContext, workerParameters)
                },
            )
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val workManager = WorkManager.getInstance(context)
        val scheduler = RecurringAndBudgetScheduler(workManager)

        scheduler.syncSchedule(shouldSchedule = true)
        val infos = workManager.getWorkInfosForUniqueWork(RecurringAndBudgetScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.NOT_REQUIRED, info.constraints.requiredNetworkType)
        assertEquals(
            TimeUnit.HOURS.toMillis(RecurringAndBudgetScheduler.REPEAT_INTERVAL_HOURS),
            info.periodicityInfo?.repeatIntervalMillis,
        )
        assertTrue(RecurringAndBudgetScheduler.WORK_TAG in info.tags)

        scheduler.syncSchedule(shouldSchedule = false)
        val cancelled = workManager.getWorkInfosForUniqueWork(RecurringAndBudgetScheduler.UNIQUE_WORK_NAME).get()
        assertTrue(cancelled.all { it.state == WorkInfo.State.CANCELLED })
    }
}
