package io.github.kvtttba.droplet.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.dao.AccountDao
import io.github.kvtttba.droplet.core.database.dao.AiDraftDao
import io.github.kvtttba.droplet.core.database.dao.AttachmentDao
import io.github.kvtttba.droplet.core.database.dao.BudgetDao
import io.github.kvtttba.droplet.core.database.dao.DebtProfileDao
import io.github.kvtttba.droplet.core.database.dao.ExchangeRateDao
import io.github.kvtttba.droplet.core.database.dao.InvestmentAssetDao
import io.github.kvtttba.droplet.core.database.dao.LoanDao
import io.github.kvtttba.droplet.core.database.dao.MarketQuoteDao
import io.github.kvtttba.droplet.core.database.dao.RecurringRuleDao
import io.github.kvtttba.droplet.core.database.dao.TagDao
import io.github.kvtttba.droplet.core.database.dao.TemplateDao
import io.github.kvtttba.droplet.core.database.dao.TransactionDao
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLedgerRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.model.Currency
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object LedgerDataModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideDropletDatabase(
        @ApplicationContext context: Context,
    ): DropletDatabase = DropletDatabase.create(context)

    @Provides
    fun provideAccountDao(database: DropletDatabase): AccountDao = database.accountDao()

    @Provides
    fun provideTransactionDao(database: DropletDatabase): TransactionDao = database.transactionDao()

    @Provides
    fun provideTagDao(database: DropletDatabase): TagDao = database.tagDao()

    @Provides
    fun provideAttachmentDao(database: DropletDatabase): AttachmentDao = database.attachmentDao()

    @Provides
    fun provideInvestmentAssetDao(database: DropletDatabase): InvestmentAssetDao =
        database.investmentAssetDao()

    @Provides
    fun provideMarketQuoteDao(database: DropletDatabase): MarketQuoteDao =
        database.marketQuoteDao()

    @Provides
    fun provideExchangeRateDao(database: DropletDatabase): ExchangeRateDao =
        database.exchangeRateDao()

    @Provides
    fun provideLoanDao(database: DropletDatabase): LoanDao = database.loanDao()

    @Provides
    fun provideDebtProfileDao(database: DropletDatabase): DebtProfileDao =
        database.debtProfileDao()

    @Provides
    fun provideBudgetDao(database: DropletDatabase): BudgetDao = database.budgetDao()

    @Provides
    fun provideRecurringRuleDao(database: DropletDatabase): RecurringRuleDao =
        database.recurringRuleDao()

    @Provides
    fun provideTemplateDao(database: DropletDatabase): TemplateDao = database.templateDao()

    @Provides
    fun provideAiDraftDao(database: DropletDatabase): AiDraftDao = database.aiDraftDao()

    @Provides
    @Singleton
    fun provideLedgerTransactionWriter(
        database: DropletDatabase,
    ): LedgerTransactionWriter = LedgerTransactionWriter(database)

    @Provides
    @Singleton
    fun provideDefaultLedgerSeeder(
        database: DropletDatabase,
    ): DefaultLedgerSeeder = DefaultLedgerSeeder(database)

    @Provides
    @Singleton
    fun provideLedgerEngine(): LedgerEngine = LedgerEngine(baseCurrency = Currency.DEFAULT)

    @Provides
    @Singleton
    fun provideLedgerRepository(
        database: DropletDatabase,
        transactionWriter: LedgerTransactionWriter,
        seeder: DefaultLedgerSeeder,
        ledgerEngine: LedgerEngine,
    ): LedgerRepository = LocalLedgerRepository(
        database = database,
        transactionWriter = transactionWriter,
        seeder = seeder,
        ledgerEngine = ledgerEngine,
    )
}
