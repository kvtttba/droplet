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
import io.github.kvtttba.droplet.core.database.repository.CreditCardRepository
import io.github.kvtttba.droplet.core.database.repository.LocalCreditCardRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.notification.CreditCardReminderNotificationPublisher
import io.github.kvtttba.droplet.notification.CreditCardReminderNotifier
import io.github.kvtttba.droplet.worker.CreditCardReminderScheduler
import javax.inject.Singleton

/**
 * Hilt entry point used by [io.github.kvtttba.droplet.worker.CreditCardReminderWorker] to resolve
 * singleton repositories, preferences, notifier, and clock without requiring `hilt-work`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CreditCardReminderEntryPoint {
    fun creditCardRepository(): CreditCardRepository
    fun creditCardReminderPreferencesRepository(): CreditCardReminderPreferencesRepository
    fun privacyPreferencesRepository(): PrivacyPreferencesRepository
    fun creditCardReminderNotifier(): CreditCardReminderNotifier
    fun creditCardReminderScheduler(): CreditCardReminderScheduler
    fun networkClock(): NetworkClock
}

@Module
@InstallIn(SingletonComponent::class)
object CreditCardModule {

    @Provides
    @Singleton
    fun provideCreditCardRepository(
        database: DropletDatabase,
        transactionWriter: LedgerTransactionWriter,
        seeder: DefaultLedgerSeeder,
        ledgerEngine: LedgerEngine,
        clock: NetworkClock,
    ): CreditCardRepository = LocalCreditCardRepository(
        database = database,
        transactionWriter = transactionWriter,
        seeder = seeder,
        ledgerEngine = ledgerEngine,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideCreditCardReminderNotifier(
        @ApplicationContext context: Context,
    ): CreditCardReminderNotifier = CreditCardReminderNotificationPublisher(context)

    @Provides
    @Singleton
    fun provideCreditCardReminderScheduler(
        workManager: WorkManager,
    ): CreditCardReminderScheduler = CreditCardReminderScheduler(workManager)
}
