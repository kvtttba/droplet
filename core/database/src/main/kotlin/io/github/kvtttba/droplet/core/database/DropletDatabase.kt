package io.github.kvtttba.droplet.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.github.kvtttba.droplet.core.database.converter.DropletTypeConverters
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
import io.github.kvtttba.droplet.core.database.entity.AccountEntity
import io.github.kvtttba.droplet.core.database.entity.AiDraftEntity
import io.github.kvtttba.droplet.core.database.entity.AttachmentEntity
import io.github.kvtttba.droplet.core.database.entity.BudgetEntity
import io.github.kvtttba.droplet.core.database.entity.DebtProfileEntity
import io.github.kvtttba.droplet.core.database.entity.ExchangeRateEntity
import io.github.kvtttba.droplet.core.database.entity.InvestmentAssetEntity
import io.github.kvtttba.droplet.core.database.entity.LoanInstallmentEntity
import io.github.kvtttba.droplet.core.database.entity.LoanPlanEntity
import io.github.kvtttba.droplet.core.database.entity.MarketQuoteEntity
import io.github.kvtttba.droplet.core.database.entity.PostingEntity
import io.github.kvtttba.droplet.core.database.entity.RecurringRuleEntity
import io.github.kvtttba.droplet.core.database.entity.TagEntity
import io.github.kvtttba.droplet.core.database.entity.TemplateEntity
import io.github.kvtttba.droplet.core.database.entity.TransactionEntity
import io.github.kvtttba.droplet.core.database.entity.TransactionTagCrossRef

/**
 * Primary local SQLite/Room database for Droplet double-entry ledger, investments,
 * loans, debts, budgets, automation rules, and AI parsing drafts.
 */
@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        PostingEntity::class,
        TagEntity::class,
        TransactionTagCrossRef::class,
        AttachmentEntity::class,
        InvestmentAssetEntity::class,
        MarketQuoteEntity::class,
        ExchangeRateEntity::class,
        LoanPlanEntity::class,
        LoanInstallmentEntity::class,
        DebtProfileEntity::class,
        BudgetEntity::class,
        RecurringRuleEntity::class,
        TemplateEntity::class,
        AiDraftEntity::class,
    ],
    version = DropletDatabaseContract.SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(DropletTypeConverters::class)
abstract class DropletDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun tagDao(): TagDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun investmentAssetDao(): InvestmentAssetDao
    abstract fun marketQuoteDao(): MarketQuoteDao
    abstract fun exchangeRateDao(): ExchangeRateDao
    abstract fun loanDao(): LoanDao
    abstract fun debtProfileDao(): DebtProfileDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringRuleDao(): RecurringRuleDao
    abstract fun templateDao(): TemplateDao
    abstract fun aiDraftDao(): AiDraftDao

    companion object {

        fun create(
            context: Context,
            databaseName: String = DropletDatabaseContract.DATABASE_NAME,
            allowMainThreadQueries: Boolean = false,
        ): DropletDatabase = Room.databaseBuilder(
            context.applicationContext,
            DropletDatabase::class.java,
            databaseName,
        )
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .apply {
                if (allowMainThreadQueries) {
                    allowMainThreadQueries()
                }
            }
            .build()

        fun createInMemory(
            context: Context,
            allowMainThreadQueries: Boolean = false,
        ): DropletDatabase = Room.inMemoryDatabaseBuilder(
            context.applicationContext,
            DropletDatabase::class.java,
        )
            .apply {
                if (allowMainThreadQueries) {
                    allowMainThreadQueries()
                }
            }
            .build()
    }
}
