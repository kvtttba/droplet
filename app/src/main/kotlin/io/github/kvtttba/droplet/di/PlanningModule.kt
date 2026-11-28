package io.github.kvtttba.droplet.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.repository.BudgetRepository
import io.github.kvtttba.droplet.core.database.repository.LocalBudgetRepository
import io.github.kvtttba.droplet.core.database.repository.LocalRecurringRuleRepository
import io.github.kvtttba.droplet.core.database.repository.LocalTemplateRepository
import io.github.kvtttba.droplet.core.database.repository.RecurringRuleRepository
import io.github.kvtttba.droplet.core.database.repository.TemplateRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.BudgetAndRecurringPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.notification.PlanningAlertNotificationPublisher
import io.github.kvtttba.droplet.notification.PlanningAlertNotifier
import io.github.kvtttba.droplet.worker.BudgetAlertEvaluator
import io.github.kvtttba.droplet.worker.RecurringAndBudgetScheduler
import javax.inject.Singleton

/**
 * Hilt entry point used by [io.github.kvtttba.droplet.worker.RecurringAndBudgetWorker] and
 * compile-time binding verification for M5.1 planning repositories and services.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlanningEntryPoint {
    fun budgetRepository(): BudgetRepository
    fun recurringRuleRepository(): RecurringRuleRepository
    fun templateRepository(): TemplateRepository
    fun budgetAndRecurringPreferencesRepository(): BudgetAndRecurringPreferencesRepository
    fun privacyPreferencesRepository(): PrivacyPreferencesRepository
    fun planningAlertNotifier(): PlanningAlertNotifier
    fun budgetAlertEvaluator(): BudgetAlertEvaluator
    fun recurringAndBudgetScheduler(): RecurringAndBudgetScheduler
    fun networkClock(): NetworkClock
}

@Module
@InstallIn(SingletonComponent::class)
object PlanningModule {

    @Provides
    @Singleton
    fun provideBudgetRepository(
        database: DropletDatabase,
        seeder: DefaultLedgerSeeder,
        clock: NetworkClock,
    ): BudgetRepository = LocalBudgetRepository(
        database = database,
        seeder = seeder,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideRecurringRuleRepository(
        database: DropletDatabase,
        transactionWriter: LedgerTransactionWriter,
        seeder: DefaultLedgerSeeder,
        ledgerEngine: LedgerEngine,
        clock: NetworkClock,
    ): RecurringRuleRepository = LocalRecurringRuleRepository(
        database = database,
        transactionWriter = transactionWriter,
        seeder = seeder,
        ledgerEngine = ledgerEngine,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideTemplateRepository(
        database: DropletDatabase,
        transactionWriter: LedgerTransactionWriter,
        seeder: DefaultLedgerSeeder,
        ledgerEngine: LedgerEngine,
        clock: NetworkClock,
    ): TemplateRepository = LocalTemplateRepository(
        database = database,
        transactionWriter = transactionWriter,
        seeder = seeder,
        ledgerEngine = ledgerEngine,
        clock = clock,
    )

    @Provides
    @Singleton
    fun providePlanningAlertNotifier(
        @ApplicationContext context: Context,
    ): PlanningAlertNotifier = PlanningAlertNotificationPublisher(context)

    @Provides
    @Singleton
    fun provideBudgetAlertEvaluator(
        budgetRepository: BudgetRepository,
        alertPreferencesRepository: BudgetAndRecurringPreferencesRepository,
        privacyPreferencesRepository: PrivacyPreferencesRepository,
        notifier: PlanningAlertNotifier,
        clock: NetworkClock,
    ): BudgetAlertEvaluator = BudgetAlertEvaluator(
        budgetRepository = budgetRepository,
        alertPreferencesRepository = alertPreferencesRepository,
        privacyPreferencesRepository = privacyPreferencesRepository,
        notifier = notifier,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideRecurringAndBudgetScheduler(
        workManager: WorkManager,
    ): RecurringAndBudgetScheduler = RecurringAndBudgetScheduler(workManager)
}
