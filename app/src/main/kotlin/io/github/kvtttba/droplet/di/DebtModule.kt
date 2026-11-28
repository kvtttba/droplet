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
import io.github.kvtttba.droplet.core.database.repository.DebtRepository
import io.github.kvtttba.droplet.core.database.repository.LocalDebtRepository
import io.github.kvtttba.droplet.core.database.repository.LocalReimbursementAndRefundRepository
import io.github.kvtttba.droplet.core.database.repository.ReimbursementAndRefundRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.DebtReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.notification.DebtReminderNotificationPublisher
import io.github.kvtttba.droplet.notification.DebtReminderNotifier
import io.github.kvtttba.droplet.worker.DebtReminderScheduler
import javax.inject.Singleton

/**
 * Hilt entry point used by [io.github.kvtttba.droplet.worker.DebtReminderWorker] (and for
 * compile-time Dagger graph validation of M3.3 debt / reimbursement / refund singletons)
 * without requiring `hilt-work`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebtReminderEntryPoint {
    fun debtRepository(): DebtRepository
    fun reimbursementAndRefundRepository(): ReimbursementAndRefundRepository
    fun debtReminderPreferencesRepository(): DebtReminderPreferencesRepository
    fun privacyPreferencesRepository(): PrivacyPreferencesRepository
    fun debtReminderNotifier(): DebtReminderNotifier
    fun debtReminderScheduler(): DebtReminderScheduler
    fun networkClock(): NetworkClock
}

@Module
@InstallIn(SingletonComponent::class)
object DebtModule {

    @Provides
    @Singleton
    fun provideDebtRepository(
        database: DropletDatabase,
        transactionWriter: LedgerTransactionWriter,
        seeder: DefaultLedgerSeeder,
        ledgerEngine: LedgerEngine,
        clock: NetworkClock,
    ): DebtRepository = LocalDebtRepository(
        database = database,
        transactionWriter = transactionWriter,
        seeder = seeder,
        ledgerEngine = ledgerEngine,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideReimbursementAndRefundRepository(
        database: DropletDatabase,
        transactionWriter: LedgerTransactionWriter,
        seeder: DefaultLedgerSeeder,
        ledgerEngine: LedgerEngine,
        clock: NetworkClock,
    ): ReimbursementAndRefundRepository = LocalReimbursementAndRefundRepository(
        database = database,
        transactionWriter = transactionWriter,
        seeder = seeder,
        ledgerEngine = ledgerEngine,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideDebtReminderNotifier(
        @ApplicationContext context: Context,
    ): DebtReminderNotifier = DebtReminderNotificationPublisher(context)

    @Provides
    @Singleton
    fun provideDebtReminderScheduler(
        workManager: WorkManager,
    ): DebtReminderScheduler = DebtReminderScheduler(workManager)
}
