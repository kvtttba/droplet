package io.github.kvtttba.droplet

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.repository.BudgetRepository
import io.github.kvtttba.droplet.core.database.repository.DebtRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LoanRepository
import io.github.kvtttba.droplet.core.database.repository.RecurringRuleRepository
import io.github.kvtttba.droplet.core.security.BudgetAndRecurringPreferencesRepository
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.DebtReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.MarketSyncPreferencesRepository
import io.github.kvtttba.droplet.core.security.WebDavSyncPreferencesRepository
import io.github.kvtttba.droplet.di.ApplicationScope
import io.github.kvtttba.droplet.notification.CreditCardReminderNotifier
import io.github.kvtttba.droplet.notification.DebtReminderNotifier
import io.github.kvtttba.droplet.notification.LoanReminderNotifier
import io.github.kvtttba.droplet.notification.PlanningAlertNotifier
import io.github.kvtttba.droplet.security.AppLockCoordinator
import io.github.kvtttba.droplet.worker.BudgetAlertEvaluator
import io.github.kvtttba.droplet.worker.CreditCardReminderScheduler
import io.github.kvtttba.droplet.worker.DebtReminderScheduler
import io.github.kvtttba.droplet.worker.LoanScheduleScheduler
import io.github.kvtttba.droplet.worker.MarketSyncScheduler
import io.github.kvtttba.droplet.worker.RecurringAndBudgetScheduler
import io.github.kvtttba.droplet.worker.WebDavSyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class DropletApplication : Application() {

    @Inject
    lateinit var ledgerRepository: LedgerRepository

    @Inject
    lateinit var appLockCoordinator: AppLockCoordinator

    @Inject
    lateinit var marketSyncPreferencesRepository: MarketSyncPreferencesRepository

    @Inject
    lateinit var marketSyncScheduler: MarketSyncScheduler

    @Inject
    lateinit var creditCardReminderPreferencesRepository: CreditCardReminderPreferencesRepository

    @Inject
    lateinit var creditCardReminderNotifier: CreditCardReminderNotifier

    @Inject
    lateinit var creditCardReminderScheduler: CreditCardReminderScheduler

    @Inject
    lateinit var loanRepository: LoanRepository

    @Inject
    lateinit var loanReminderNotifier: LoanReminderNotifier

    @Inject
    lateinit var loanScheduleScheduler: LoanScheduleScheduler

    @Inject
    lateinit var debtRepository: DebtRepository

    @Inject
    lateinit var debtReminderPreferencesRepository: DebtReminderPreferencesRepository

    @Inject
    lateinit var debtReminderNotifier: DebtReminderNotifier

    @Inject
    lateinit var debtReminderScheduler: DebtReminderScheduler

    @Inject
    lateinit var ledgerTransactionWriter: LedgerTransactionWriter

    @Inject
    lateinit var budgetRepository: BudgetRepository

    @Inject
    lateinit var recurringRuleRepository: RecurringRuleRepository

    @Inject
    lateinit var budgetAndRecurringPreferencesRepository: BudgetAndRecurringPreferencesRepository

    @Inject
    lateinit var planningAlertNotifier: PlanningAlertNotifier

    @Inject
    lateinit var budgetAlertEvaluator: BudgetAlertEvaluator

    @Inject
    lateinit var recurringAndBudgetScheduler: RecurringAndBudgetScheduler

    @Inject
    lateinit var webDavSyncPreferencesRepository: WebDavSyncPreferencesRepository

    @Inject
    lateinit var webDavSyncScheduler: WebDavSyncScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appLockCoordinator.onAppForegrounded()
        }

        override fun onStop(owner: LifecycleOwner) {
            appLockCoordinator.onAppBackgrounded()
        }
    }

    override fun onCreate() {
        super.onCreate()
        appLockCoordinator.start()
        creditCardReminderNotifier.ensureNotificationChannel()
        loanReminderNotifier.ensureNotificationChannel()
        debtReminderNotifier.ensureNotificationChannel()
        planningAlertNotifier.ensureNotificationChannel()
        ledgerTransactionWriter.addOnTransactionCommittedListener {
            applicationScope.launch {
                runCatching { budgetAlertEvaluator.evaluateAndNotify() }
            }
            applicationScope.launch {
                runCatching {
                    val webDavPrefs = webDavSyncPreferencesRepository.currentPreferences()
                    if (webDavPrefs.autoSyncEnabled && webDavPrefs.isReadyForBackup) {
                        webDavSyncScheduler.scheduleDebouncedBackup(webDavPrefs)
                    }
                }
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        applicationScope.launch {
            ledgerRepository.ensureSeeded()
        }
        applicationScope.launch {
            marketSyncPreferencesRepository.preferences.collect { prefs ->
                marketSyncScheduler.syncSchedule(prefs)
            }
        }
        applicationScope.launch {
            creditCardReminderPreferencesRepository.preferences
                .map { it.remindersEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    creditCardReminderScheduler.syncSchedule(remindersEnabled = enabled)
                }
        }
        applicationScope.launch {
            loanRepository.observeLoanSummaries(includeArchived = false)
                .map { summaries ->
                    summaries.any { summary ->
                        summary.remainingPeriods > 0 &&
                            (summary.plan.autoPost || summary.plan.remindDaysBefore != null)
                    }
                }
                .distinctUntilChanged()
                .collect { hasScheduledLoans ->
                    loanScheduleScheduler.syncSchedule(hasScheduledLoans = hasScheduledLoans)
                }
        }
        applicationScope.launch {
            combine(
                debtReminderPreferencesRepository.preferences.map { it.remindersEnabled }.distinctUntilChanged(),
                debtRepository.observeDebtSummaries().map { summaries ->
                    summaries.any { it.hasOutstandingBalance && it.profile.defaultDueAt != null }
                }.distinctUntilChanged(),
            ) { enabled, hasDueDebts ->
                enabled && hasDueDebts
            }.distinctUntilChanged().collect { shouldSchedule ->
                debtReminderScheduler.syncSchedule(shouldSchedule = shouldSchedule)
            }
        }
        applicationScope.launch {
            combine(
                budgetRepository.observeBudgetOverview(includeDisabled = false)
                    .map { it.enabledCount > 0 }
                    .distinctUntilChanged(),
                recurringRuleRepository.observeRulesWithStatus()
                    .map { rules -> rules.any { !it.rule.isPaused && !it.isTerminated } }
                    .distinctUntilChanged(),
                budgetAndRecurringPreferencesRepository.preferences
                    .map { it.budgetAlertsEnabled || it.recurringRemindersEnabled }
                    .distinctUntilChanged(),
            ) { hasEnabledBudgets, hasActiveRecurringRules, anyAlertsEnabled ->
                hasActiveRecurringRules || (hasEnabledBudgets && anyAlertsEnabled)
            }.distinctUntilChanged().collect { shouldSchedule ->
                recurringAndBudgetScheduler.syncSchedule(shouldSchedule = shouldSchedule)
            }
        }
        applicationScope.launch {
            webDavSyncPreferencesRepository.preferences
                .distinctUntilChanged { old, new ->
                    old.isReadyForBackup == new.isReadyForBackup &&
                        old.autoSyncEnabled == new.autoSyncEnabled &&
                        old.dailyBackupEnabled == new.dailyBackupEnabled &&
                        old.wifiOnly == new.wifiOnly
                }
                .collect { prefs ->
                    webDavSyncScheduler.syncDailySchedule(prefs)
                }
        }
    }
}
