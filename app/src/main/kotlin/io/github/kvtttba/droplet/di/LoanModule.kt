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
import io.github.kvtttba.droplet.core.database.repository.LoanRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLoanRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.LoanReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.notification.LoanReminderNotificationPublisher
import io.github.kvtttba.droplet.notification.LoanReminderNotifier
import io.github.kvtttba.droplet.worker.LoanScheduleScheduler
import javax.inject.Singleton

/**
 * Hilt entry point used by [io.github.kvtttba.droplet.worker.LoanScheduleWorker] to resolve
 * singleton repositories, preferences, notifier, and clock without requiring `hilt-work`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LoanScheduleEntryPoint {
    fun loanRepository(): LoanRepository
    fun loanReminderPreferencesRepository(): LoanReminderPreferencesRepository
    fun privacyPreferencesRepository(): PrivacyPreferencesRepository
    fun loanReminderNotifier(): LoanReminderNotifier
    fun loanScheduleScheduler(): LoanScheduleScheduler
    fun networkClock(): NetworkClock
}

@Module
@InstallIn(SingletonComponent::class)
object LoanModule {

    @Provides
    @Singleton
    fun provideLoanRepository(
        database: DropletDatabase,
        transactionWriter: LedgerTransactionWriter,
        seeder: DefaultLedgerSeeder,
        ledgerEngine: LedgerEngine,
        clock: NetworkClock,
    ): LoanRepository = LocalLoanRepository(
        database = database,
        transactionWriter = transactionWriter,
        seeder = seeder,
        ledgerEngine = ledgerEngine,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideLoanReminderNotifier(
        @ApplicationContext context: Context,
    ): LoanReminderNotifier = LoanReminderNotificationPublisher(context)

    @Provides
    @Singleton
    fun provideLoanScheduleScheduler(
        workManager: WorkManager,
    ): LoanScheduleScheduler = LoanScheduleScheduler(workManager)
}
