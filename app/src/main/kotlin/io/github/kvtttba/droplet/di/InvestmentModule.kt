package io.github.kvtttba.droplet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.repository.InvestmentRepository
import io.github.kvtttba.droplet.core.database.repository.LocalInvestmentRepository
import io.github.kvtttba.droplet.core.database.repository.MarketDataRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.network.NetworkClock
import javax.inject.Singleton

/**
 * Hilt entry point exposing [InvestmentRepository] for upcoming M2.3 Assets UI / workers
 * and forcing compile-time Dagger graph validation for [InvestmentModule].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface InvestmentEntryPoint {
    fun investmentRepository(): InvestmentRepository
}

@Module
@InstallIn(SingletonComponent::class)
object InvestmentModule {

    @Provides
    @Singleton
    fun provideInvestmentRepository(
        database: DropletDatabase,
        transactionWriter: LedgerTransactionWriter,
        seeder: DefaultLedgerSeeder,
        ledgerEngine: LedgerEngine,
        marketDataRepository: MarketDataRepository,
        clock: NetworkClock,
    ): InvestmentRepository = LocalInvestmentRepository(
        database = database,
        transactionWriter = transactionWriter,
        seeder = seeder,
        ledgerEngine = ledgerEngine,
        marketDataRepository = marketDataRepository,
        clock = clock,
    )
}
