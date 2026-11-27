package io.github.kvtttba.droplet.core.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.database.snapshot.DatabaseSnapshotExporter
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidator
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.CachedExchangeRate
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DatabaseSnapshotExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var sourceDb: DropletDatabase
    private lateinit var sourceDbName: String
    private lateinit var writer: LedgerTransactionWriter
    private lateinit var exporter: DatabaseSnapshotExporter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sourceDbName = "test_wal_source_${System.nanoTime()}.db"
        sourceDb = DropletDatabase.create(
            context = context,
            databaseName = sourceDbName,
            allowMainThreadQueries = true,
        )
        writer = LedgerTransactionWriter(sourceDb)
        exporter = DatabaseSnapshotExporter(ioDispatcher = Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        sourceDb.close()
        context.deleteDatabase(sourceDbName)
    }

    @Test
    fun exportConsistentSnapshot_flushesWalProducesStandaloneVerifiedDatabaseAndSupportsReExport() = runTest {
        DefaultLedgerSeeder(sourceDb).seedIfNeeded(nowEpochMs = 1_000L)
        sourceDb.accountDao().upsert(
            Account(
                id = "acc:usd_cash",
                name = "美元现金",
                type = AccountType.ASSET,
                subType = AccountSubType.CASH,
                currency = Currency.USD,
            ).toEntity(),
        )
        sourceDb.exchangeRateDao().upsert(
            CachedExchangeRate(Currency.USD, Currency.CNY, "7.25", "mock", 1_000L).toEntity(),
        )

        val engine = LedgerEngine(
            baseCurrency = Currency.CNY,
            rateProvider = ExchangeRateTable.of(
                ExchangeRateSnapshot.of(Currency.USD, Currency.CNY, "7.25", ExchangeRateSource.MARKET_CACHE, 1_000L),
            ),
        )
        val expenseTx = engine.buildExpense(
            meta = TransactionMeta(
                id = "tx-snap-1",
                occurredAt = 10_000L,
                payee = "快照测试商户",
                tagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY, DefaultSeedCatalog.TagIds.FAMILY),
            ),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("68.50"),
        )
        val fxTx = engine.buildCrossCurrencyTransfer(
            meta = TransactionMeta(id = "tx-snap-2", occurredAt = 20_000L),
            fromAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            toAccountId = "acc:usd_cash",
            paidAmount = Money.fromDecimalString("725.00", Currency.CNY),
            receivedAmount = Money.fromDecimalString("100.00", Currency.USD),
        )
        writer.saveTransactions(listOf(expenseTx, fxTx))

        val snapshotFile = File(tempFolder.newFolder("snapshots"), "ledger.db")
        val metadata = exporter.exportConsistentSnapshot(
            database = sourceDb,
            targetFile = snapshotFile,
            exportedAt = 30_000L,
        )

        assertTrue(snapshotFile.exists())
        assertTrue(metadata.sizeBytes > 0L)
        assertEquals(64, metadata.sha256.length)
        assertTrue(metadata.sha256.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(DropletDatabaseContract.SCHEMA_VERSION, metadata.schemaVersion)
        assertEquals(30_000L, metadata.exportedAt)
        assertEquals(0, metadata.walCheckpoint.busy)
        assertFalse(File(snapshotFile.parentFile, "${snapshotFile.name}.vacuum.tmp").exists())

        val integrity = exporter.verifySnapshotIntegrity(snapshotFile)
        assertTrue(integrity.isIntegrityOk)
        assertEquals(DropletDatabaseContract.SCHEMA_VERSION, integrity.schemaVersion)

        // Open standalone exported snapshot independently (zero -wal/-shm files present)
        val restoredDbName = "restored_verify_${System.nanoTime()}.db"
        val restoredDb = Room.databaseBuilder(context, DropletDatabase::class.java, restoredDbName)
            .createFromFile(snapshotFile)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(sourceDb.accountDao().countAll(), restoredDb.accountDao().countAll())
            assertEquals(2, restoredDb.transactionDao().countTransactions())
            assertEquals(6, restoredDb.transactionDao().countPostings()) // 2 + 4 (with 2 Trading lines)

            val restoredTx1 = restoredDb.transactionDao().getById("tx-snap-1")?.toDomain()
            val restoredTx2 = restoredDb.transactionDao().getById("tx-snap-2")?.toDomain()
            assertNotNull(restoredTx1)
            assertNotNull(restoredTx2)
            assertEquals("快照测试商户", restoredTx1!!.payee)
            assertEquals(setOf(DefaultSeedCatalog.TagIds.WORKDAY, DefaultSeedCatalog.TagIds.FAMILY), restoredTx1.tagIds.toSet())
            LedgerValidator.requireValid(restoredTx1)
            LedgerValidator.requireValid(restoredTx2!!)

            val restoredRate = restoredDb.exchangeRateDao().getRate(Currency.USD, Currency.CNY)
            assertEquals("7.25", restoredRate?.rateDecimal)
        } finally {
            restoredDb.close()
            context.deleteDatabase(restoredDbName)
        }

        // Write a third transaction and re-export over the same target file
        val tx3 = engine.buildIncome(
            meta = TransactionMeta(id = "tx-snap-3", occurredAt = 40_000L),
            incomeCategoryAccountId = DefaultSeedCatalog.AccountIds.INC_WORK_SALARY,
            depositAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("5000.00"),
        )
        writer.saveTransaction(tx3)

        val secondMetadata = exporter.exportConsistentSnapshot(
            database = sourceDb,
            targetFile = snapshotFile,
            exportedAt = 50_000L,
        )
        assertNotEquals(metadata.sha256, secondMetadata.sha256)
        assertTrue(exporter.verifySnapshotIntegrity(snapshotFile).isIntegrityOk)

        val restoredDb2Name = "restored_verify_2_${System.nanoTime()}.db"
        val restoredDb2 = Room.databaseBuilder(context, DropletDatabase::class.java, restoredDb2Name)
            .createFromFile(snapshotFile)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(3, restoredDb2.transactionDao().countTransactions())
            assertNotNull(restoredDb2.transactionDao().getById("tx-snap-3"))
        } finally {
            restoredDb2.close()
            context.deleteDatabase(restoredDb2Name)
        }
    }

    @Test
    fun exportConsistentSnapshot_createsNestedDirectoriesEscapesQuotesAndCheckpointsWal() = runTest {
        DefaultLedgerSeeder(sourceDb).seedIfNeeded(nowEpochMs = 1_000L)

        val checkpoint = exporter.checkpointWal(sourceDb)
        assertEquals(0, checkpoint.busy)
        assertTrue(checkpoint.isFullyCheckpointed)

        val nestedTarget = File(tempFolder.root, "nested/dir/user's_backup.db")
        assertFalse(nestedTarget.parentFile!!.exists())

        val meta = exporter.exportConsistentSnapshot(
            database = sourceDb,
            targetFile = nestedTarget,
            exportedAt = 99_000L,
        )
        assertTrue(nestedTarget.exists())
        assertTrue(meta.sizeBytes > 0L)
        assertTrue(exporter.verifySnapshotIntegrity(nestedTarget).isIntegrityOk)
    }
}
