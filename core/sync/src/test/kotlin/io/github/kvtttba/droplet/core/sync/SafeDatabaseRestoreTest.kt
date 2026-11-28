package io.github.kvtttba.droplet.core.sync

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.entity.AttachmentEntity
import io.github.kvtttba.droplet.core.database.entity.BudgetEntity
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.BudgetPeriod
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.security.DataStoreWebDavSyncPreferencesRepository
import io.github.kvtttba.droplet.core.security.KeystoreSecretCipher
import io.github.kvtttba.droplet.core.security.WebDavBackupCrypto
import io.github.kvtttba.droplet.core.security.WebDavConfigSaveInput
import io.github.kvtttba.droplet.core.security.WebDavSecretUpdateAction
import java.io.File
import java.nio.charset.StandardCharsets
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SafeDatabaseRestoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: DropletDatabase
    private lateinit var filesRootDir: File
    private lateinit var archiveCodec: BackupArchiveCodec
    private var currentTimeMs: Long = 1_764_211_200_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dbName = "safe_restore_test_${System.nanoTime()}.db"
        database = DropletDatabase.create(context, databaseName = dbName, allowMainThreadQueries = true)
        filesRootDir = tempFolder.newFolder("app_files")
        archiveCodec = BackupArchiveCodec(
            crypto = WebDavBackupCrypto(pbkdf2Iterations = 2_000),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun safeRestoreRestoresAllLedgerDataAttachmentsCreatesEmergencyRollbackAndRefreshesLiveRoomFlow() = runBlocking {
        val seeder = DefaultLedgerSeeder(database)
        val writer = LedgerTransactionWriter(database)
        val engine = LedgerEngine(baseCurrency = Currency.CNY)
        seeder.seedIfNeeded(currentTimeMs)

        // 1. Seed initial baseline ledger: expense ¥88.50 + budget ¥3,000 + receipt attachment
        val originalTx = engine.buildExpense(
            meta = TransactionMeta(
                id = "tx-baseline-1",
                occurredAt = currentTimeMs,
                payee = "基线餐厅",
                tagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY),
            ),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("88.50"),
        )
        writer.saveTransaction(originalTx)
        database.budgetDao().upsert(
            BudgetEntity(
                id = "budget-baseline",
                period = BudgetPeriod.MONTHLY,
                categoryAccountId = null,
                currency = Currency.CNY,
                limitAmountMinor = Money.fromDecimalString("3000.00").amountMinor,
                warnThresholdPct = 80,
                isEnabled = true,
                createdAt = currentTimeMs,
                updatedAt = currentTimeMs,
            ),
        )
        val receiptsDir = File(filesRootDir, BackupArchiveCodec.RECEIPTS_DIR_NAME).apply { mkdirs() }
        val baselineReceipt = File(receiptsDir, "receipt_baseline.jpg")
        baselineReceipt.writeBytes("baseline-receipt-payload".toByteArray(StandardCharsets.UTF_8))
        database.attachmentDao().upsert(
            AttachmentEntity(
                id = "att-baseline",
                transactionId = originalTx.id,
                localRelPath = "receipts/receipt_baseline.jpg",
                sha256 = BackupArchiveCodec.computeSha256Hex(baselineReceipt),
                mimeType = "image/jpeg",
                sizeBytes = baselineReceipt.length(),
                syncToWebdav = true,
                createdAt = currentTimeMs,
            ),
        )

        val samplePhrase = "demo-restore-phrase-2026"
        val backupDir = tempFolder.newFolder("backup_artifacts")
        val baselineArchive = archiveCodec.packBackup(
            database = database,
            filesRootDir = filesRootDir,
            outputDir = backupDir,
            appVersion = "0.1.0-m5.3",
            exportedAtEpochMs = currentTimeMs,
            includeAttachments = true,
            encrypt = true,
            passphrase = samplePhrase,
        )

        // 2. Mutate live database destructively: delete baseline tx, add new tx, delete receipt file
        currentTimeMs += 60_000L
        writer.deleteTransaction(originalTx.id)
        val mutatedTx = engine.buildExpense(
            meta = TransactionMeta(id = "tx-mutated-2", occurredAt = currentTimeMs, payee = "误操作消费"),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_SHOPPING_DAILY,
            paymentAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
            amount = Money.fromDecimalString("999.00"),
        )
        writer.saveTransaction(mutatedTx)
        baselineReceipt.delete()
        assertEquals(listOf("tx-mutated-2"), database.transactionDao().getAllByStatuses(listOf(TransactionStatus.POSTED)).map { it.transaction.id })

        // Subscribe a live Room Flow BEFORE restoring to verify the singleton DB stays alive & emits updates!
        val observedPayees = mutableListOf<List<String>>()
        val collectorJob = launch(Dispatchers.IO) {
            database.transactionDao().observeAllByStatuses(listOf(TransactionStatus.POSTED)).collect { list ->
                observedPayees += list.mapNotNull { it.transaction.payee }
            }
        }

        try {
            val restoreManager = SafeDatabaseRestoreManager(
                database = database,
                filesRootDir = filesRootDir,
                archiveCodec = archiveCodec,
                appVersion = "0.1.0-m5.3",
                clock = { currentTimeMs },
            )

            // 3. Execute safe restore from encrypted DRP1 backup
            currentTimeMs += 60_000L
            val outcome = restoreManager.restoreFromArchiveBytes(
                archiveBytes = baselineArchive.file.readBytes(),
                passphrase = samplePhrase,
                createRollbackBackup = true,
            )

            assertTrue("Expected restore success: ${outcome.message}", outcome.success)
            assertNotNull("Pre-restore emergency rollback must be created automatically", outcome.rollbackSnapshot)
            assertTrue(outcome.rollbackSnapshot!!.isPreRestoreAuto)
            assertTrue(File(outcome.rollbackSnapshot!!.filePath).exists())
            assertEquals(1, outcome.restoredAttachmentCount)

            // Verify live Room database now matches baseline 100%
            val restoredTxs = database.transactionDao().getAllByStatuses(listOf(TransactionStatus.POSTED))
            assertEquals(1, restoredTxs.size)
            assertEquals("tx-baseline-1", restoredTxs.single().transaction.id)
            assertEquals("基线餐厅", restoredTxs.single().transaction.payee)
            assertEquals(listOf(DefaultSeedCatalog.TagIds.WORKDAY), restoredTxs.single().tags.map { it.id })
            assertEquals(1, database.budgetDao().observeAll().first().size)
            assertTrue("Baseline receipt must be restored to disk", baselineReceipt.exists())
            assertEquals("baseline-receipt-payload", baselineReceipt.readText(StandardCharsets.UTF_8))

            // Confirm live Flow emitted updated restored data automatically
            withTimeout(5_000L) {
                database.transactionDao().observeAllByStatuses(listOf(TransactionStatus.POSTED)).first { txs ->
                    txs.map { it.transaction.id } == listOf("tx-baseline-1")
                }
            }

            // 4. Verify we can roll back again using the auto-created pre-restore emergency snapshot!
            currentTimeMs += 60_000L
            val undoOutcome = restoreManager.restoreFromLocalRollback(outcome.rollbackSnapshot!!.fileName)
            assertTrue("Undo via local emergency rollback must succeed: ${undoOutcome.message}", undoOutcome.success)
            val revertedTxs = database.transactionDao().getAllByStatuses(listOf(TransactionStatus.POSTED))
            assertEquals(listOf("tx-mutated-2"), revertedTxs.map { it.transaction.id })
        } finally {
            collectorJob.cancel()
        }
    }

    @Test
    fun midTransactionRestoreFailureRollsBackCompletelyWithZeroLedgerDamage() = runBlocking {
        DefaultLedgerSeeder(database).seedIfNeeded(currentTimeMs)
        val writer = LedgerTransactionWriter(database)
        val engine = LedgerEngine(baseCurrency = Currency.CNY)

        // Seed snapshot state (tx A)
        val txA = engine.buildExpense(
            meta = TransactionMeta(id = "tx-snap-a", occurredAt = currentTimeMs, payee = "快照交易A"),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("20.00"),
        )
        writer.saveTransaction(txA)
        val snapArtifact = archiveCodec.packBackup(
            database = database,
            filesRootDir = filesRootDir,
            outputDir = tempFolder.newFolder("snap_a"),
            appVersion = "0.1.0-m5.3",
            encrypt = false,
        )

        // Add tx B to live database before attempting restore
        val txB = engine.buildExpense(
            meta = TransactionMeta(id = "tx-live-b", occurredAt = currentTimeMs + 5_000L, payee = "现存交易B"),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TRANSIT,
            paymentAccountId = DefaultSeedCatalog.AccountIds.WECHAT,
            amount = Money.fromDecimalString("55.00"),
        )
        writer.saveTransaction(txB)

        // Inject deterministic mid-transaction failure right before SQLite commit
        val failingRestoreManager = SafeDatabaseRestoreManager(
            database = database,
            filesRootDir = filesRootDir,
            archiveCodec = archiveCodec,
            appVersion = "0.1.0-m5.3",
            clock = { currentTimeMs + 10_000L },
            beforeCommitHook = { _ ->
                throw IllegalStateException("Simulated disk/commit fault during restore")
            },
        )

        val failedOutcome = failingRestoreManager.restoreFromArchiveBytes(
            archiveBytes = snapArtifact.file.readBytes(),
            passphrase = null,
            createRollbackBackup = true,
        )
        assertFalse(failedOutcome.success)
        assertNotNull("Emergency rollback package is still preserved", failedOutcome.rollbackSnapshot)

        // Live database must remain 100% intact with both txA and txB!
        val liveIds = database.transactionDao().getAllByStatuses(listOf(TransactionStatus.POSTED))
            .map { it.transaction.id }
            .toSet()
        assertEquals(setOf("tx-snap-a", "tx-live-b"), liveIds)
    }

    @Test
    fun orchestratorEndToEndBackupPruneAndRemoteRestoreAgainstMockWebDavServer() = runBlocking {
        // In-memory WebDAV MockWebServer
        val remoteStore = LinkedHashMap<String, ByteArray>()
        val mockServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when (request.method) {
                        "MKCOL" -> MockResponse().setResponseCode(201)
                        "PUT" -> {
                            val name = path.substringAfterLast('/')
                            remoteStore[name] = request.body.readByteArray()
                            MockResponse().setResponseCode(201)
                        }
                        "GET" -> {
                            val name = path.substringAfterLast('/')
                            val bytes = remoteStore[name]
                                ?: return MockResponse().setResponseCode(404)
                            MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))
                        }
                        "DELETE" -> {
                            val name = path.substringAfterLast('/')
                            remoteStore.remove(name)
                            MockResponse().setResponseCode(204)
                        }
                        "PROPFIND" -> {
                            val entriesXml = remoteStore.entries.joinToString("\n") { (name, bytes) ->
                                """
                                <d:response>
                                  <d:href>/dav/droplet-backups/$name</d:href>
                                  <d:propstat><d:prop>
                                    <d:getcontentlength>${bytes.size}</d:getcontentlength>
                                  </d:prop></d:propstat>
                                </d:response>
                                """.trimIndent()
                            }
                            MockResponse()
                                .setResponseCode(207)
                                .setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">$entriesXml</d:multistatus>""")
                        }
                        else -> MockResponse().setResponseCode(405)
                    }
                }
            }
            start()
        }

        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val prefsStore = PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tempFolder.newFolder("orch_prefs"), "webdav.preferences_pb")
            }
            val prefsRepo = DataStoreWebDavSyncPreferencesRepository(
                dataStore = prefsStore,
                secretCipher = KeystoreSecretCipher { aesKey },
            )
            prefsRepo.saveConnectionConfig(
                WebDavConfigSaveInput(
                    serverUrl = mockServer.url("/dav").toString().trimEnd('/'),
                    username = "demo-user",
                    remoteDirectory = "droplet-backups",
                    passwordAction = WebDavSecretUpdateAction.Replace("demo-dav-pass-2026"),
                ),
            )
            prefsRepo.saveBackupPassphrase(WebDavSecretUpdateAction.Replace("demo-orch-phrase-2026"))
            prefsRepo.setRetentionCount(2)

            DefaultLedgerSeeder(database).seedIfNeeded(currentTimeMs)
            val writer = LedgerTransactionWriter(database)
            val engine = LedgerEngine(baseCurrency = Currency.CNY)
            writer.saveTransaction(
                engine.buildExpense(
                    meta = TransactionMeta(id = "tx-orch-1", occurredAt = currentTimeMs, payee = "云端备份笔1"),
                    expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
                    paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
                    amount = Money.fromDecimalString("42.00"),
                ),
            )

            val restoreManager = SafeDatabaseRestoreManager(
                database = database,
                filesRootDir = filesRootDir,
                archiveCodec = archiveCodec,
                appVersion = "0.1.0-m5.3",
                clock = { currentTimeMs },
            )
            val orchestrator = DefaultWebDavBackupOrchestrator(
                database = database,
                filesRootDir = filesRootDir,
                preferencesRepository = prefsRepo,
                webDavClient = OkHttpWebDavClient(DropletHttpClientFactory()),
                archiveCodec = archiveCodec,
                restoreManager = restoreManager,
                appVersion = "0.1.0-m5.3",
                clock = { currentTimeMs },
            )

            // Upload 3 backups with retention=2 -> oldest automatically pruned
            val b1 = orchestrator.backupNow(BackupTrigger.MANUAL)
            assertTrue(b1.success)
            currentTimeMs += 5_000L
            val b2 = orchestrator.backupNow(BackupTrigger.DEBOUNCED_AUTO)
            assertTrue(b2.success)
            currentTimeMs += 5_000L
            val b3 = orchestrator.backupNow(BackupTrigger.DAILY_PERIODIC)
            assertTrue(b3.success)
            assertEquals(1, b3.prunedFileNames.size)
            assertEquals(2, remoteStore.size)

            // Wipe local transaction and restore latest remote encrypted snapshot
            writer.deleteTransaction("tx-orch-1")
            assertTrue(database.transactionDao().getAllByStatuses(listOf(TransactionStatus.POSTED)).isEmpty())

            val remoteList = orchestrator.listRemoteSnapshots().getOrThrow()
            assertEquals(2, remoteList.size)
            val restoreRes = orchestrator.restoreRemoteSnapshot(remoteList.first().fileName)
            assertTrue("Remote restore must succeed: ${restoreRes.message}", restoreRes.success)
            assertEquals(
                listOf("tx-orch-1"),
                database.transactionDao().getAllByStatuses(listOf(TransactionStatus.POSTED)).map { it.transaction.id },
            )
            assertNotNull(prefsRepo.currentPreferences().lastRestoreAt)
        } finally {
            storeScope.coroutineContext[Job]?.cancelAndJoin()
            mockServer.shutdown()
        }
    }
}
