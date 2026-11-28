package io.github.kvtttba.droplet.core.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.DropletDatabaseContract
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.entity.AttachmentEntity
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.security.BackupCryptoException
import io.github.kvtttba.droplet.core.security.WebDavBackupCrypto
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BackupArchiveCodecTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: DropletDatabase
    private lateinit var filesRootDir: File
    private lateinit var codec: BackupArchiveCodec

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dbName = "codec_test_${System.nanoTime()}.db"
        database = DropletDatabase.create(context, databaseName = dbName, allowMainThreadQueries = true)
        filesRootDir = tempFolder.newFolder("files_root")
        codec = BackupArchiveCodec(
            crypto = WebDavBackupCrypto(pbkdf2Iterations = 2_000),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun packAndUnpackEncryptedArchiveWithAttachmentsVerifiesManifestAndHashes() = runTest {
        val nowMs = 1_764_211_200_000L
        DefaultLedgerSeeder(database).seedIfNeeded(nowMs)
        val writer = LedgerTransactionWriter(database)
        val engine = LedgerEngine(baseCurrency = Currency.CNY)
        val tx = engine.buildExpense(
            meta = TransactionMeta(id = "tx-codec-1", occurredAt = nowMs, payee = "测试咖啡店"),
            expenseCategoryAccountId = DefaultSeedCatalog.AccountIds.EXP_FOOD_LUNCH,
            paymentAccountId = DefaultSeedCatalog.AccountIds.DEBIT_CARD,
            amount = Money.fromDecimalString("28.50"),
        )
        writer.saveTransaction(tx)

        // Create a receipt attachment under filesRootDir/receipts/
        val receiptsDir = File(filesRootDir, BackupArchiveCodec.RECEIPTS_DIR_NAME).apply { mkdirs() }
        val receiptFile = File(receiptsDir, "receipt_sample_1.jpg")
        val receiptBytes = "synthetic-jpeg-receipt-bytes".toByteArray(StandardCharsets.UTF_8)
        receiptFile.writeBytes(receiptBytes)
        database.attachmentDao().upsert(
            AttachmentEntity(
                id = "att-1",
                transactionId = tx.id,
                localRelPath = "receipts/receipt_sample_1.jpg",
                sha256 = BackupArchiveCodec.computeSha256Hex(receiptFile),
                mimeType = "image/jpeg",
                sizeBytes = receiptBytes.size.toLong(),
                syncToWebdav = true,
                createdAt = nowMs,
            ),
        )

        val outputDir = tempFolder.newFolder("out")
        val samplePhrase = "demo-archive-phrase-2026"
        val artifact = codec.packBackup(
            database = database,
            filesRootDir = filesRootDir,
            outputDir = outputDir,
            appVersion = "0.1.0-m5.3",
            exportedAtEpochMs = nowMs,
            includeAttachments = true,
            encrypt = true,
            passphrase = samplePhrase,
        )

        assertTrue(artifact.file.exists())
        assertTrue(artifact.fileName.endsWith(".drp"))
        assertTrue(artifact.encrypted)
        assertTrue(artifact.manifest.includesAttachments)
        assertEquals(1, artifact.manifest.attachments.size)
        assertEquals(DropletDatabaseContract.SCHEMA_VERSION, artifact.manifest.schemaVersion)
        assertTrue(codec.isEncryptedPayload(artifact.file.readBytes()))

        // Unpack and verify with right passphrase
        val unpackDir = tempFolder.newFolder("unpack_ok")
        val extracted = codec.unpackAndVerify(
            archiveBytes = artifact.file.readBytes(),
            destinationDir = unpackDir,
            passphrase = samplePhrase,
        )
        assertTrue(extracted.wasEncrypted)
        assertEquals(artifact.manifest.dbSha256, extracted.manifest.dbSha256)
        assertNotNull(extracted.attachmentsRootDir)
        val restoredReceipt = File(extracted.attachmentsRootDir, "receipts/receipt_sample_1.jpg")
        assertTrue(restoredReceipt.exists())
        assertArrayEquals(receiptBytes, restoredReceipt.readBytes())

        // Wrong passphrase must fail cleanly
        try {
            codec.unpackAndVerify(
                archiveBytes = artifact.file.readBytes(),
                destinationDir = tempFolder.newFolder("unpack_bad_pass"),
                passphrase = "demo-wrong-phrase-0000",
            )
            fail("Expected BackupCryptoException for wrong passphrase")
        } catch (_: BackupCryptoException) {
            // expected
        }
    }

    @Test
    fun unpackBlocksZipSlipTraversalAndSha256MismatchAndFutureSchema() = runTest {
        // 1. Zip-Slip attack archive
        val zipSlipBytes = buildRawZip(
            mapOf(
                "../../outside_evil.txt" to "pwn".toByteArray(StandardCharsets.UTF_8),
                "manifest.json" to "{}".toByteArray(StandardCharsets.UTF_8),
            ),
        )
        val slipDest = tempFolder.newFolder("slip_dest")
        try {
            codec.unpackAndVerify(zipSlipBytes, slipDest, passphrase = null)
            fail("Expected Zip-Slip rejection")
        } catch (e: BackupArchiveException) {
            assertTrue(e.message.orEmpty().contains("Zip-Slip"))
        }
        assertFalse(File(tempFolder.root, "outside_evil.txt").exists())

        // 2. Pack valid unencrypted archive, then test schema version incompatibility
        DefaultLedgerSeeder(database).seedIfNeeded(1_764_211_200_000L)
        val plainArtifact = codec.packBackup(
            database = database,
            filesRootDir = filesRootDir,
            outputDir = tempFolder.newFolder("plain_out"),
            appVersion = "0.1.0-m5.3",
            includeAttachments = false,
            encrypt = false,
        )
        assertTrue(plainArtifact.fileName.endsWith(".zip"))
        assertFalse(codec.isEncryptedPayload(plainArtifact.file.readBytes()))

        // Simulate older app that only supports schema version 0 -> rejects v1 snapshot
        try {
            codec.unpackAndVerify(
                archiveBytes = plainArtifact.file.readBytes(),
                destinationDir = tempFolder.newFolder("schema_reject"),
                passphrase = null,
                maxSupportedSchemaVersion = 0,
            )
            fail("Expected incompatible schema version rejection")
        } catch (e: BackupArchiveException) {
            assertTrue(e.message.orEmpty().contains("Schema"))
        }

        // 3. Tampered ledger.db inside plain ZIP fails SHA-256 verification
        val tamperedZip = buildRawZip(
            mapOf(
                BackupManifest.MANIFEST_FILE_NAME to """
                    {
                      "formatVersion": 1,
                      "appVersion": "0.1.0-m5.3",
                      "schemaVersion": 1,
                      "exportedAtEpochMs": 1764211200000,
                      "dbFileName": "ledger.db",
                      "dbSha256": "${"0".repeat(64)}",
                      "dbSizeBytes": 16,
                      "includesAttachments": false,
                      "attachments": []
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8),
                "ledger.db" to "corrupted-sqlite".toByteArray(StandardCharsets.UTF_8),
            ),
        )
        try {
            codec.unpackAndVerify(tamperedZip, tempFolder.newFolder("sha_reject"), passphrase = null)
            fail("Expected SHA-256 mismatch rejection")
        } catch (e: BackupArchiveException) {
            assertTrue(e.message.orEmpty().contains("SHA-256"))
        }
    }

    private fun buildRawZip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
