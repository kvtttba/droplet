package io.github.kvtttba.droplet.core.sync

import android.database.sqlite.SQLiteDatabase
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.DropletDatabaseContract
import io.github.kvtttba.droplet.core.database.snapshot.DatabaseSnapshotExporter
import io.github.kvtttba.droplet.core.security.BackupCryptoException
import io.github.kvtttba.droplet.core.security.WebDavBackupCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupAttachmentManifestEntry(
    val localRelPath: String,
    val sha256: String,
    val sizeBytes: Long,
)

@Serializable
data class BackupManifest(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val appVersion: String,
    val schemaVersion: Int,
    val exportedAtEpochMs: Long,
    val dbFileName: String = DEFAULT_DB_FILE_NAME,
    val dbSha256: String,
    val dbSizeBytes: Long,
    val includesAttachments: Boolean = false,
    val attachments: List<BackupAttachmentManifestEntry> = emptyList(),
) {
    companion object {
        const val CURRENT_FORMAT_VERSION: Int = 1
        const val DEFAULT_DB_FILE_NAME: String = "ledger.db"
        const val MANIFEST_FILE_NAME: String = "manifest.json"
        const val ATTACHMENTS_ZIP_PREFIX: String = "attachments/"
    }
}

data class PackedBackupArtifact(
    val file: File,
    val fileName: String,
    val manifest: BackupManifest,
    val encrypted: Boolean,
    val sizeBytes: Long,
)

data class ExtractedBackupSnapshot(
    val workingDir: File,
    val manifest: BackupManifest,
    val databaseFile: File,
    val attachmentsRootDir: File?,
    val wasEncrypted: Boolean,
)

class BackupArchiveException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Packs consistent Room SQLite snapshots (via [DatabaseSnapshotExporter]) alongside `manifest.json`
 * and optional receipt attachments into a standard ZIP archive, optionally wrapping the archive
 * with [WebDavBackupCrypto] `DRP1` authenticated encryption.
 *
 * Also unpacks and rigorously validates incoming archives against:
 * - DRP1 AEAD authentication & passphrase verification;
 * - Zip-Slip path traversal and Zip-Bomb bounds;
 * - Manifest format & Room schema version compatibility;
 * - SHA-256 verification of `ledger.db` and every bundled attachment;
 * - SQLite `PRAGMA integrity_check`, `PRAGMA foreign_key_check`, and full 16-table presence.
 */
class BackupArchiveCodec(
    private val snapshotExporter: DatabaseSnapshotExporter = DatabaseSnapshotExporter(),
    private val crypto: WebDavBackupCrypto = WebDavBackupCrypto(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * Produces a consistent snapshot archive file inside [outputDir].
     */
    suspend fun packBackup(
        database: DropletDatabase,
        filesRootDir: File,
        outputDir: File,
        appVersion: String,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
        includeAttachments: Boolean = false,
        encrypt: Boolean = true,
        passphrase: String? = null,
        fileNamePrefix: String = BACKUP_FILE_PREFIX,
    ): PackedBackupArtifact = withContext(ioDispatcher) {
        if (encrypt && passphrase.isNullOrBlank()) {
            throw BackupArchiveException("已启用端到端加密，请先设置备份加密口令")
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw BackupArchiveException("无法创建备份输出目录")
        }

        val stagingDir = File(outputDir, ".pack-stage-${UUID.randomUUID().toString().replace("-", "")}")
        if (!stagingDir.mkdirs()) {
            throw BackupArchiveException("无法创建快照打包临时目录")
        }

        try {
            val dbFile = File(stagingDir, BackupManifest.DEFAULT_DB_FILE_NAME)
            val snapshotMeta = snapshotExporter.exportConsistentSnapshot(
                database = database,
                targetFile = dbFile,
                exportedAt = exportedAtEpochMs,
            )

            val bundledAttachments = if (includeAttachments) {
                collectSafeAttachmentFiles(database = database, filesRootDir = filesRootDir)
            } else {
                emptyList()
            }

            val manifest = BackupManifest(
                formatVersion = BackupManifest.CURRENT_FORMAT_VERSION,
                appVersion = appVersion.ifBlank { "unknown" },
                schemaVersion = snapshotMeta.schemaVersion,
                exportedAtEpochMs = exportedAtEpochMs,
                dbFileName = BackupManifest.DEFAULT_DB_FILE_NAME,
                dbSha256 = snapshotMeta.sha256,
                dbSizeBytes = snapshotMeta.sizeBytes,
                includesAttachments = includeAttachments,
                attachments = bundledAttachments.map { it.manifestEntry },
            )

            val zipBytes = createZipArchiveBytes(
                manifest = manifest,
                dbFile = dbFile,
                attachments = bundledAttachments,
            )

            val finalBytes = if (encrypt) {
                crypto.encrypt(plaintext = zipBytes, passphrase = requireNotNull(passphrase))
            } else {
                zipBytes
            }

            val extension = if (encrypt) ENCRYPTED_EXTENSION else PLAIN_EXTENSION
            val fileName = buildSnapshotFileName(
                prefix = fileNamePrefix,
                timestampMs = exportedAtEpochMs,
                extension = extension,
            )
            val targetFile = File(outputDir, fileName)
            val tempOutput = File(outputDir, "$fileName.tmp")
            try {
                tempOutput.writeBytes(finalBytes)
                if (targetFile.exists() && !targetFile.delete()) {
                    throw IOException("Failed to overwrite existing backup target")
                }
                if (!tempOutput.renameTo(targetFile)) {
                    tempOutput.copyTo(targetFile, overwrite = true)
                    tempOutput.delete()
                }
            } finally {
                if (tempOutput.exists()) {
                    tempOutput.delete()
                }
            }

            PackedBackupArtifact(
                file = targetFile,
                fileName = fileName,
                manifest = manifest,
                encrypted = encrypt,
                sizeBytes = targetFile.length(),
            )
        } catch (e: BackupArchiveException) {
            throw e
        } catch (e: BackupCryptoException) {
            throw e
        } catch (t: Throwable) {
            throw BackupArchiveException("生成一致性备份快照失败：${t.message ?: "内部错误"}", t)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /**
     * Decrypts (when DRP1-encrypted), safely unpacks into [destinationDir], and validates the full
     * snapshot manifest, database SHA-256, SQLite integrity, foreign keys, required tables, and attachments.
     */
    suspend fun unpackAndVerify(
        archiveBytes: ByteArray,
        destinationDir: File,
        passphrase: String? = null,
        maxSupportedSchemaVersion: Int = DropletDatabaseContract.SCHEMA_VERSION,
    ): ExtractedBackupSnapshot = withContext(ioDispatcher) {
        if (archiveBytes.isEmpty()) {
            throw BackupArchiveException("备份文件为空")
        }
        val wasEncrypted = crypto.isDrp1Encrypted(archiveBytes)
        val zipBytes = if (wasEncrypted) {
            if (passphrase.isNullOrBlank()) {
                throw BackupArchiveException("该快照已使用 DRP1 端到端加密，请输入正确的解密口令")
            }
            crypto.decrypt(payload = archiveBytes, passphrase = passphrase)
        } else {
            archiveBytes
        }

        if (destinationDir.exists()) {
            destinationDir.deleteRecursively()
        }
        if (!destinationDir.mkdirs()) {
            throw BackupArchiveException("无法创建恢复临时解压目录")
        }

        try {
            extractZipSafely(zipBytes = zipBytes, destinationDir = destinationDir)

            val manifestFile = File(destinationDir, BackupManifest.MANIFEST_FILE_NAME)
            if (!manifestFile.exists() || !manifestFile.isFile) {
                throw BackupArchiveException("备份包缺少 manifest.json 元数据文件")
            }
            val manifest = try {
                json.decodeFromString(
                    BackupManifest.serializer(),
                    manifestFile.readText(StandardCharsets.UTF_8),
                )
            } catch (t: Throwable) {
                throw BackupArchiveException("备份元数据 manifest.json 解析失败", t)
            }

            validateManifestMetadata(manifest, maxSupportedSchemaVersion)

            val dbFile = resolveSafeChild(destinationDir, manifest.dbFileName)
                ?: throw BackupArchiveException("备份元数据中的数据库文件名非法")
            if (!dbFile.exists() || !dbFile.isFile) {
                throw BackupArchiveException("备份包缺少数据库文件 ${manifest.dbFileName}")
            }

            val actualDbSha = computeSha256Hex(dbFile)
            if (!actualDbSha.equals(manifest.dbSha256, ignoreCase = true)) {
                throw BackupArchiveException("数据库文件 SHA-256 校验不匹配，快照可能已损坏或被篡改")
            }
            if (manifest.dbSizeBytes > 0L && dbFile.length() != manifest.dbSizeBytes) {
                throw BackupArchiveException("数据库文件体积与元数据声明不一致")
            }

            verifyStagedSqliteDatabase(
                dbFile = dbFile,
                expectedSchemaVersion = manifest.schemaVersion,
                maxSupportedSchemaVersion = maxSupportedSchemaVersion,
            )

            val attachmentsRoot = if (manifest.includesAttachments) {
                verifyBundledAttachments(destinationDir, manifest.attachments)
            } else {
                null
            }

            ExtractedBackupSnapshot(
                workingDir = destinationDir,
                manifest = manifest,
                databaseFile = dbFile,
                attachmentsRootDir = attachmentsRoot,
                wasEncrypted = wasEncrypted,
            )
        } catch (t: Throwable) {
            destinationDir.deleteRecursively()
            when (t) {
                is BackupArchiveException, is BackupCryptoException -> throw t
                else -> throw BackupArchiveException("快照校验失败：${t.message ?: "格式异常"}", t)
            }
        }
    }

    fun isEncryptedPayload(bytes: ByteArray): Boolean = crypto.isDrp1Encrypted(bytes)

    private data class BundledAttachmentFile(
        val manifestEntry: BackupAttachmentManifestEntry,
        val sourceFile: File,
    )

    private fun collectSafeAttachmentFiles(
        database: DropletDatabase,
        filesRootDir: File,
    ): List<BundledAttachmentFile> {
        val relPaths = LinkedHashSet<String>()
        val db = database.openHelper.readableDatabase
        db.query("SELECT DISTINCT local_rel_path FROM attachments ORDER BY created_at ASC, id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val rel = cursor.getString(0)?.trim().orEmpty()
                if (rel.isNotEmpty()) {
                    relPaths += rel
                }
            }
        }

        // Also include any receipt images residing directly under filesRootDir/receipts so pending AI draft
        // attachments remain consistent if restored.
        val receiptsDir = File(filesRootDir, RECEIPTS_DIR_NAME)
        if (receiptsDir.exists() && receiptsDir.isDirectory) {
            receiptsDir.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    relPaths += "$RECEIPTS_DIR_NAME/${file.name}"
                }
        }

        val result = mutableListOf<BundledAttachmentFile>()
        for (relPath in relPaths) {
            val safeFile = resolveSafeAttachmentFile(filesRootDir, relPath) ?: continue
            if (!safeFile.exists() || !safeFile.isFile) continue
            val normalizedRel = normalizeAttachmentRelPath(relPath) ?: continue
            result += BundledAttachmentFile(
                manifestEntry = BackupAttachmentManifestEntry(
                    localRelPath = normalizedRel,
                    sha256 = computeSha256Hex(safeFile),
                    sizeBytes = safeFile.length(),
                ),
                sourceFile = safeFile,
            )
        }
        return result
    }

    private fun createZipArchiveBytes(
        manifest: BackupManifest,
        dbFile: File,
        attachments: List<BundledAttachmentFile>,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zos ->
            val manifestBytes = json.encodeToString(BackupManifest.serializer(), manifest)
                .toByteArray(StandardCharsets.UTF_8)
            zos.putNextEntry(ZipEntry(BackupManifest.MANIFEST_FILE_NAME))
            zos.write(manifestBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(manifest.dbFileName))
            FileInputStream(dbFile).use { input ->
                input.copyTo(zos)
            }
            zos.closeEntry()

            for (item in attachments) {
                val entryName = "${BackupManifest.ATTACHMENTS_ZIP_PREFIX}${item.manifestEntry.localRelPath}"
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(item.sourceFile).use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun extractZipSafely(
        zipBytes: ByteArray,
        destinationDir: File,
    ) {
        val canonicalDestRoot = destinationDir.canonicalFile
        val rootPrefix = canonicalDestRoot.path + File.separator
        var totalEntries = 0
        var totalExtractedBytes = 0L
        val buffer = ByteArray(8 * 1024)

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                totalEntries += 1
                if (totalEntries > MAX_ZIP_ENTRIES) {
                    throw BackupArchiveException("备份压缩包条目数超过安全上限")
                }

                val rawName = entry.name.replace('\\', '/')
                if (rawName.isBlank() ||
                    rawName.startsWith("/") ||
                    rawName.contains('\u0000') ||
                    rawName.split('/').any { it == ".." }
                ) {
                    throw BackupArchiveException("检测到非法压缩路径，已拒绝解包（Zip-Slip 防护）")
                }

                val outFile = File(destinationDir, rawName).canonicalFile
                if (!outFile.path.startsWith(rootPrefix) && outFile != canonicalDestRoot) {
                    throw BackupArchiveException("检测到越界解压路径，已拒绝解包（Zip-Slip 防护）")
                }

                if (entry.isDirectory) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw BackupArchiveException("无法创建解压子目录")
                    }
                } else {
                    val parent = outFile.parentFile
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw BackupArchiveException("无法创建解压目标目录")
                    }
                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            totalExtractedBytes += read
                            if (totalExtractedBytes > MAX_EXTRACTED_BYTES) {
                                throw BackupArchiveException("备份包解压总大小超出安全上限")
                            }
                            fos.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
            }
        }

        if (totalEntries == 0) {
            throw BackupArchiveException("备份文件不是有效的 ZIP 压缩包")
        }
    }

    private fun validateManifestMetadata(
        manifest: BackupManifest,
        maxSupportedSchemaVersion: Int,
    ) {
        if (manifest.formatVersion != BackupManifest.CURRENT_FORMAT_VERSION) {
            throw BackupArchiveException("不支持的备份格式版本：v${manifest.formatVersion}")
        }
        if (manifest.schemaVersion <= 0 || manifest.schemaVersion > maxSupportedSchemaVersion) {
            throw BackupArchiveException(
                "备份数据库 Schema 版本（v${manifest.schemaVersion}）不兼容当前应用（最高支持 v$maxSupportedSchemaVersion）",
            )
        }
        if (manifest.dbFileName.isBlank() ||
            manifest.dbFileName.contains('/') ||
            manifest.dbFileName.contains('\\') ||
            manifest.dbFileName.contains("..")
        ) {
            throw BackupArchiveException("备份元数据数据库文件名非法")
        }
        if (manifest.dbSha256.length != SHA256_HEX_LENGTH) {
            throw BackupArchiveException("备份元数据缺少有效的 SHA-256 校验值")
        }
    }

    private fun verifyStagedSqliteDatabase(
        dbFile: File,
        expectedSchemaVersion: Int,
        maxSupportedSchemaVersion: Int,
    ) {
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
            }
            if (!integrity.equals("ok", ignoreCase = true)) {
                throw BackupArchiveException("暂存数据库完整性检查失败：$integrity")
            }

            val actualUserVersion = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            if (actualUserVersion <= 0 || actualUserVersion > maxSupportedSchemaVersion) {
                throw BackupArchiveException(
                    "数据库实际版本（v$actualUserVersion）超出当前应用兼容范围（v$maxSupportedSchemaVersion）",
                )
            }
            if (actualUserVersion != expectedSchemaVersion) {
                throw BackupArchiveException(
                    "数据库实际版本（v$actualUserVersion）与 manifest 声明（v$expectedSchemaVersion）不一致",
                )
            }

            val existingTables = mutableSetOf<String>()
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    existingTables += cursor.getString(0)
                }
            }
            val missingTables = SafeDatabaseRestoreManager.ALL_TABLES_IN_INSERT_ORDER
                .filterNot { it in existingTables }
            if (missingTables.isNotEmpty()) {
                throw BackupArchiveException(
                    "快照数据库缺少必需的数据表：${missingTables.joinToString(", ")}",
                )
            }

            db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val offendingTable = cursor.getString(0)
                    throw BackupArchiveException(
                        "快照数据库存在外键完整性错误（表：$offendingTable），已中止恢复",
                    )
                }
            }
        }
    }

    private fun verifyBundledAttachments(
        destinationDir: File,
        entries: List<BackupAttachmentManifestEntry>,
    ): File {
        val attachmentsRoot = File(destinationDir, BackupManifest.ATTACHMENTS_ZIP_PREFIX.trimEnd('/'))
        for (entry in entries) {
            val safeRel = normalizeAttachmentRelPath(entry.localRelPath)
                ?: throw BackupArchiveException("附件清单包含非法相对路径：${entry.localRelPath}")
            val file = resolveSafeChild(attachmentsRoot, safeRel)
                ?: throw BackupArchiveException("附件清单路径越界：${entry.localRelPath}")
            if (!file.exists() || !file.isFile) {
                throw BackupArchiveException("备份包缺少元数据声明的附件文件：$safeRel")
            }
            val actualSha = computeSha256Hex(file)
            if (!actualSha.equals(entry.sha256, ignoreCase = true)) {
                throw BackupArchiveException("附件文件校验失败：$safeRel")
            }
        }
        return attachmentsRoot
    }

    companion object {
        const val BACKUP_FILE_PREFIX: String = "droplet-backup"
        const val ENCRYPTED_EXTENSION: String = "drp"
        const val PLAIN_EXTENSION: String = "zip"
        const val RECEIPTS_DIR_NAME: String = "receipts"

        private const val SHA256_HEX_LENGTH: Int = 64
        private const val MAX_ZIP_ENTRIES: Int = 4_096
        private const val MAX_EXTRACTED_BYTES: Long = 256L * 1024L * 1024L

        private val FILE_TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US)
                .withZone(ZoneOffset.UTC)

        fun buildSnapshotFileName(
            prefix: String,
            timestampMs: Long,
            extension: String,
        ): String {
            val ts = FILE_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestampMs))
            return "$prefix-$ts.$extension"
        }

        fun normalizeAttachmentRelPath(localRelPath: String): String? {
            val normalized = localRelPath.trim().replace('\\', '/')
            if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains('\u0000')) {
                return null
            }
            val segments = normalized.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
                return null
            }
            if (segments.first() != RECEIPTS_DIR_NAME) {
                return null
            }
            return segments.joinToString("/")
        }

        fun resolveSafeAttachmentFile(filesRootDir: File, localRelPath: String): File? {
            val normalized = normalizeAttachmentRelPath(localRelPath) ?: return null
            val receiptsRoot = File(filesRootDir, RECEIPTS_DIR_NAME).canonicalFile
            val target = File(filesRootDir, normalized).canonicalFile
            return if (target.path.startsWith(receiptsRoot.path + File.separator)) target else null
        }

        private fun resolveSafeChild(rootDir: File, relativePath: String): File? {
            val normalized = relativePath.trim().replace('\\', '/')
            if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains('\u0000')) {
                return null
            }
            if (normalized.split('/').any { it == ".." }) return null
            val canonicalRoot = rootDir.canonicalFile
            val target = File(rootDir, normalized).canonicalFile
            return if (target.path.startsWith(canonicalRoot.path + File.separator)) target else null
        }

        internal fun computeSha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
