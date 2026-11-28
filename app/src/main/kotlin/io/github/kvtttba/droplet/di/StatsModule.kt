package io.github.kvtttba.droplet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.repository.LedgerExportRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLedgerExportRepository
import io.github.kvtttba.droplet.core.database.repository.LocalStatsRepository
import io.github.kvtttba.droplet.core.database.repository.StatsRepository
import io.github.kvtttba.droplet.core.domain.export.LedgerExportEngine
import io.github.kvtttba.droplet.core.network.NetworkClock
import javax.inject.Singleton

/**
 * Hilt entry point for compile-time verification of M5.2 statistics and ledger export bindings.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface StatsEntryPoint {
    fun statsRepository(): StatsRepository
    fun ledgerExportRepository(): LedgerExportRepository
    fun ledgerExportEngine(): LedgerExportEngine
}

@Module
@InstallIn(SingletonComponent::class)
object StatsModule {

    @Provides
    @Singleton
    fun provideLedgerExportEngine(): LedgerExportEngine = LedgerExportEngine()

    @Provides
    @Singleton
    fun provideStatsRepository(
        database: DropletDatabase,
        clock: NetworkClock,
    ): StatsRepository = LocalStatsRepository(
        database = database,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideLedgerExportRepository(
        database: DropletDatabase,
        exportEngine: LedgerExportEngine,
        clock: NetworkClock,
    ): LedgerExportRepository = LocalLedgerExportRepository(
        database = database,
        exportEngine = exportEngine,
        clock = clock,
    )
}
