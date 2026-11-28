package io.github.kvtttba.droplet.core.sync

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.DropletDatabaseContract
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LocalRollbackSnapshot(
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val createdAtEpochMs: Long,
    val isPreRestoreAuto: Boolean,
)

data class RestoreOutcome(
    val success: Boolean,
    val restoredManifest: BackupManifest? = null,
    val rollbackSnapshot: LocalRollbackSnapshot? = null,
    val restoredTableRowCount: Int = 0,
    val restoredAttachmentCount: Int = 0,
    val message: String,
)

/**
 * Safely restores a verified backup archive into the live `@Singleton` [DropletDatabase] without
 * tearing down Room observers or breaking active Compose/ViewModel `Flow` subscriptions:
 *
 * 1. Decrypts, unpacks, and validates `manifest.json`, SHA-256, schema compatibility, SQLite
 *    `PRAGMA integrity_check`, `PRAGMA foreign_key_check`, and all 16 required tables;
 * 2. Automatically creates a local emergency rollback snapshot under `filesDir/rollback_backups/`
 *    before touching the live database (keeping up to [maxLocalRollbacks] snapshots);
 * 3. Reads all 16 verified business tables from an isolated read-only connection and replaces them
 *    atomically inside `database.runInTransaction` in strict FK topological order (preserving
 *    Android SQLiteConnectionPool WAL state and Room's TEMP invalidation tracking table),
 *    verifying `PRAGMA foreign_key_check` prior to commit so any failure triggers a full rollback;
 * 4. Atomically synchronizes bundled receipt attachments (rolling back both attachments and DB if
 *    attachment copy fails);
 * 5. Flushes WAL & invokes `database.invalidationTracker.refreshVersionsSync()` so every active
 *    Room `Flow` refreshes immediately with the restored ledger state.
 */
class SafeDatabaseRestoreManager(
    private val database: DropletDatabase,
    private val filesRootDir: File,
    private val archiveCodec: BackupArchiveCodec = BackupArchiveCodec(),
    private val appVersion: String = "0.1.0-m5.3",
    private val maxLocalRollbacks: Int = DEFAULT_MAX_LOCAL_ROLLBACKS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    internal val beforeCommitHook: ((SupportSQLiteDatabase) -> Unit)? = null,
) {
    private val restoreMutex = Mutex()

    private val rollbackDir: File
        get() = File(filesRootDir, ROLLBACK_BACKUPS_DIR_NAME)

    private val receiptsDir: File
        get() = File(filesRootDir, BackupArchiveCodec.RECEIPTS_DIR_NAME)

    /**
     * Creates an unencrypted local safety snapshot inside `filesDir/rollback_backups/` so the user
     * always has an offline restore point (automatically pruned to [maxLocalRollbacks] newest files).
     */
    suspend fun createLocalRollbackSnapshot(
        prefix: String = MANUAL_SNAPSHOT_PREFIX,
    ): LocalRollbackSnapshot = restoreMutex.withLock {
        createLocalRollbackSnapshotLocked(prefix)
    }

    /**
     * Lists all local emergency rollback & manual safety snapshots, newest first.
     */
    suspend fun listLocalRollbackSnapshots(): List<LocalRollbackSnapshot> = withContext(ioDispatcher) {
        val dir = rollbackDir
        if (!dir.exists() || !dir.isDirectory) {
            return@withContext emptyList()
        }
        dir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.name.endsWith(".${BackupArchiveCodec.PLAIN_EXTENSION}", ignoreCase = true) &&
                    (file.name.startsWith("$PRE_RESTORE_PREFIX-") || file.name.startsWith("$MANUAL_SNAPSHOT_PREFIX-"))
            }
            ?.map { file ->
                LocalRollbackSnapshot(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    sizeBytes = file.length(),
                    createdAtEpochMs = file.lastModified(),
                    isPreRestoreAuto = file.name.startsWith("$PRE_RESTORE_PREFIX-"),
                )
            }
            ?.sortedWith(
                compareByDescending<LocalRollbackSnapshot> { it.createdAtEpochMs }
                    .thenByDescending { it.fileName },
            )
            ?: emptyList()
    }

    /**
     * Deletes a local rollback snapshot by filename (path-traversal protected).
     */
    suspend fun deleteLocalRollbackSnapshot(fileName: String): Boolean = withContext(ioDispatcher) {
        val target = resolveSafeRollbackFile(fileName) ?: return@withContext false
        target.exists() && target.isFile && target.delete()
    }

    /**
     * Restores directly from a previously stored local emergency rollback snapshot.
     */
    suspend fun restoreFromLocalRollback(fileName: String): RestoreOutcome = withContext(ioDispatcher) {
        val target = resolveSafeRollbackFile(fileName)
            ?: return@withContext RestoreOutcome(
                success = false,
                message = "本地回滚快照文件名无效",
            )
        if (!target.exists() || !target.isFile) {
            return@withContext RestoreOutcome(
                success = false,
                message = "本地回滚快照不存在或已被清理",
            )
        }
        restoreFromArchiveBytes(
            archiveBytes = target.readBytes(),
            passphrase = null,
            createRollbackBackup = true,
        )
    }

    /**
     * Full safe restore pipeline with pre-restore emergency backup and automatic rollback on failure.
     */
    suspend fun restoreFromArchiveBytes(
        archiveBytes: ByteArray,
        passphrase: String?,
        createRollbackBackup: Boolean = true,
        maxSupportedSchemaVersion: Int = DropletDatabaseContract.SCHEMA_VERSION,
    ): RestoreOutcome = restoreMutex.withLock {
        withContext(ioDispatcher) {
            val stagingRoot = File(
                filesRootDir,
                ".restore-stage-${UUID.randomUUID().toString().replace("-", "")}",
            )
            val receiptsBackupDir = File(
                filesRootDir,
                ".receipts-backup-${UUID.randomUUID().toString().replace("-", "")}",
            )
            var emergencyRollback: LocalRollbackSnapshot? = null

            try {
                // Step 1: Decrypt, unpack, and verify archive before mutating anything on disk.
                val extracted = archiveCodec.unpackAndVerify(
                    archiveBytes = archiveBytes,
                    destinationDir = stagingRoot,
                    passphrase = passphrase,
                    maxSupportedSchemaVersion = maxSupportedSchemaVersion,
                )

                // Step 2: Automatically create a local emergency rollback snapshot prior to replacement.
                if (createRollbackBackup) {
                    emergencyRollback = createLocalRollbackSnapshotLocked(PRE_RESTORE_PREFIX)
                }

                // Step 3: Back up existing local receipts directory if snapshot brings attachments.
                val shouldReplaceAttachments = extracted.manifest.includesAttachments
                if (shouldReplaceAttachments && receiptsDir.exists() && receiptsDir.isDirectory) {
                    copyDirectoryContents(receiptsDir, receiptsBackupDir)
                }

                // Step 4: Replace all 16 business tables atomically in a single SQLite transaction.
                val restoredRowCount = replaceAllTablesAtomically(extracted.databaseFile)

                // Step 5: Sync attachments if included; on any I/O failure restore both receipts & DB!
                val restoredAttachmentCount = if (shouldReplaceAttachments) {
                    try {
                        replaceReceiptsDirectory(
                            extractedAttachmentsRoot = extracted.attachmentsRootDir,
                            manifestAttachments = extracted.manifest.attachments,
                        )
                    } catch (attachmentError: Throwable) {
                        runCatching {
                            restoreReceiptsBackup(receiptsBackupDir)
                        }
                        if (emergencyRollback != null) {
                            runCatching {
                                rollbackDatabaseFromLocalSnapshotLocked(
                                    rollbackFile = File(emergencyRollback.filePath),
                                )
                            }
                        }
                        throw BackupArchiveException(
                            "恢复票据附件失败，已自动回滚账本数据",
                            attachmentError,
                        )
                    }
                } else {
                    0
                }

                // Step 6: Flush WAL and refresh all live Room Flow observers immediately.
                refreshRoomObserversAndVerifyHealth()

                RestoreOutcome(
                    success = true,
                    restoredManifest = extracted.manifest,
                    rollbackSnapshot = emergencyRollback,
                    restoredTableRowCount = restoredRowCount,
                    restoredAttachmentCount = restoredAttachmentCount,
                    message = buildString {
                        append("安全恢复成功（共还原 $restoredRowCount 条记录")
                        if (restoredAttachmentCount > 0) {
                            append("、$restoredAttachmentCount 个票据附件")
                        }
                        append("）")
                        if (emergencyRollback != null) {
                            append("，恢复前已自动生成本地应急回滚包")
                        }
                    },
                )
            } catch (t: Throwable) {
                RestoreOutcome(
                    success = false,
                    rollbackSnapshot = emergencyRollback,
                    message = t.message?.takeIf { it.isNotBlank() } ?: "安全恢复失败，本地账本保持未更改",
                )
            } finally {
                stagingRoot.deleteRecursively()
                receiptsBackupDir.deleteRecursively()
            }
        }
    }

    private suspend fun createLocalRollbackSnapshotLocked(prefix: String): LocalRollbackSnapshot {
        if (!rollbackDir.exists() && !rollbackDir.mkdirs()) {
            throw IOException("无法创建本地应急回滚目录")
        }
        val nowMs = clock()
        val artifact = archiveCodec.packBackup(
            database = database,
            filesRootDir = filesRootDir,
            outputDir = rollbackDir,
            appVersion = appVersion,
            exportedAtEpochMs = nowMs,
            includeAttachments = true,
            encrypt = false,
            passphrase = null,
            fileNamePrefix = prefix,
        )
        artifact.file.setLastModified(nowMs)
        pruneLocalRollbacksLocked()
        return LocalRollbackSnapshot(
            fileName = artifact.fileName,
            filePath = artifact.file.absolutePath,
            sizeBytes = artifact.sizeBytes,
            createdAtEpochMs = nowMs,
            isPreRestoreAuto = prefix == PRE_RESTORE_PREFIX,
        )
    }

    private fun pruneLocalRollbacksLocked() {
        val dir = rollbackDir
        if (!dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.name.endsWith(".${BackupArchiveCodec.PLAIN_EXTENSION}", ignoreCase = true)
            }
            ?.sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenByDescending { it.name },
            )
            ?: return
        if (files.size <= maxLocalRollbacks) return
        files.drop(maxLocalRollbacks).forEach { stale ->
            stale.delete()
        }
    }

    private fun replaceAllTablesAtomically(stagedDbFile: File): Int {
        // Read all rows from the verified staged SQLite file on a completely separate read-only
        // connection. Avoiding ATTACH DATABASE prevents Android's SQLiteConnectionPool from
        // reconfiguring WAL connections and dropping Room's TEMP `room_table_modification_log`
        // tracking table while live Flow observers are active.
        val stagedTables = readAllTablesFromStagedDatabase(stagedDbFile)

        var totalInsertedRows = 0
        database.runInTransaction {
            val db = database.openHelper.writableDatabase

            // Break self-referencing FKs prior to deleting parent tables so DELETEs stay 100%
            // compliant with active foreign-key enforcement (`ON DELETE RESTRICT` on accounts).
            for (table in ALL_TABLES_IN_DELETE_ORDER) {
                when (table) {
                    "transactions" -> db.execSQL("UPDATE `transactions` SET `ref_transaction_id` = NULL")
                    "accounts" -> db.execSQL("UPDATE `accounts` SET `parent_id` = NULL")
                }
                db.execSQL("DELETE FROM `$table`")
            }

            // Insert all 16 tables in strict FK topological order using parameterized bindings.
            for (table in ALL_TABLES_IN_INSERT_ORDER) {
                val staged = stagedTables.getValue(table)
                val mainColumns = queryLiveTableColumns(db, table)
                val commonColumns = mainColumns.filter { it in staged.columns }
                if (commonColumns.isEmpty()) {
                    throw BackupArchiveException("数据表 $table 结构与当前应用不兼容")
                }
                val columnIndices = commonColumns.map { staged.columns.indexOf(it) }
                val selfRefColumn = when (table) {
                    "accounts" -> "parent_id"
                    "transactions" -> "ref_transaction_id"
                    else -> null
                }

                if (selfRefColumn != null && selfRefColumn in commonColumns) {
                    insertSelfReferencingTable(
                        db = db,
                        table = table,
                        columns = commonColumns,
                        sourceColumnIndices = columnIndices,
                        rows = staged.rows,
                        selfRefColumn = selfRefColumn,
                    )
                } else {
                    insertTableRows(
                        db = db,
                        table = table,
                        columns = commonColumns,
                        sourceColumnIndices = columnIndices,
                        rows = staged.rows,
                    )
                }
                totalInsertedRows += staged.rows.size
            }

            // Verify zero foreign-key violations across the entire database before commit.
            db.query("PRAGMA foreign_key_check").use { cursor ->
                if (cursor.moveToFirst()) {
                    val brokenTable = cursor.getString(0)
                    throw BackupArchiveException("恢复数据存在外键约束冲突（表：$brokenTable），已自动回滚")
                }
            }

            beforeCommitHook?.invoke(db)
        }
        return totalInsertedRows
    }

    private data class StagedTableDump(
        val columns: List<String>,
        val rows: List<Array<Any?>>,
    )

    private fun readAllTablesFromStagedDatabase(stagedDbFile: File): Map<String, StagedTableDump> {
        val result = LinkedHashMap<String, StagedTableDump>(ALL_TABLES_IN_INSERT_ORDER.size)
        SQLiteDatabase.openDatabase(
            stagedDbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { srcDb ->
            for (table in ALL_TABLES_IN_INSERT_ORDER) {
                val columns = mutableListOf<String>()
                srcDb.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                    val nameIdx = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        val col = if (nameIdx >= 0) cursor.getString(nameIdx) else cursor.getString(1)
                        if (!col.isNullOrBlank()) {
                            columns += col
                        }
                    }
                }
                if (columns.isEmpty()) {
                    throw BackupArchiveException("快照数据表 $table 缺失列定义")
                }
                val colSql = columns.joinToString(", ") { "`$it`" }
                val rows = mutableListOf<Array<Any?>>()
                srcDb.rawQuery("SELECT $colSql FROM `$table`", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = arrayOfNulls<Any>(columns.size)
                        for (i in columns.indices) {
                            row[i] = when (cursor.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                                else -> cursor.getString(i)
                            }
                        }
                        rows += row
                    }
                }
                result[table] = StagedTableDump(columns = columns, rows = rows)
            }
        }
        return result
    }

    private fun insertTableRows(
        db: SupportSQLiteDatabase,
        table: String,
        columns: List<String>,
        sourceColumnIndices: List<Int>,
        rows: List<Array<Any?>>,
    ) {
        if (rows.isEmpty()) return
        val colsSql = columns.joinToString(", ") { "`$it`" }
        val placeholders = columns.joinToString(", ") { "?" }
        val stmt = db.compileStatement("INSERT INTO `$table` ($colsSql) VALUES ($placeholders)")
        try {
            for (row in rows) {
                stmt.clearBindings()
                for (bindIdx in sourceColumnIndices.indices) {
                    bindValue(stmt, bindIdx + 1, row[sourceColumnIndices[bindIdx]])
                }
                stmt.executeInsert()
            }
        } finally {
            stmt.close()
        }
    }

    private fun insertSelfReferencingTable(
        db: SupportSQLiteDatabase,
        table: String,
        columns: List<String>,
        sourceColumnIndices: List<Int>,
        rows: List<Array<Any?>>,
        selfRefColumn: String,
    ) {
        if (rows.isEmpty()) return
        val idPosInCommon = columns.indexOf("id")
        val refPosInCommon = columns.indexOf(selfRefColumn)
        val colsSql = columns.joinToString(", ") { "`$it`" }
        val placeholders = columns.joinToString(", ") { "?" }

        // Pass 1: insert all rows with self-reference column temporarily NULL so parent ordering
        // never violates active FK checks regardless of hierarchy depth.
        val deferredUpdates = mutableListOf<Pair<String, String>>()
        val insertStmt = db.compileStatement("INSERT INTO `$table` ($colsSql) VALUES ($placeholders)")
        try {
            for (row in rows) {
                insertStmt.clearBindings()
                val idVal = row[sourceColumnIndices[idPosInCommon]] as? String
                val refVal = row[sourceColumnIndices[refPosInCommon]] as? String
                if (idVal != null && refVal != null) {
                    deferredUpdates += idVal to refVal
                }
                for (bindIdx in sourceColumnIndices.indices) {
                    val value = if (bindIdx == refPosInCommon) null else row[sourceColumnIndices[bindIdx]]
                    bindValue(insertStmt, bindIdx + 1, value)
                }
                insertStmt.executeInsert()
            }
        } finally {
            insertStmt.close()
        }

        // Pass 2: restore all self-reference links now that every target row exists.
        if (deferredUpdates.isNotEmpty()) {
            val updateStmt = db.compileStatement("UPDATE `$table` SET `$selfRefColumn` = ? WHERE `id` = ?")
            try {
                for ((rowId, refId) in deferredUpdates) {
                    updateStmt.clearBindings()
                    updateStmt.bindString(1, refId)
                    updateStmt.bindString(2, rowId)
                    updateStmt.executeUpdateDelete()
                }
            } finally {
                updateStmt.close()
            }
        }
    }

    private fun bindValue(stmt: SupportSQLiteStatement, index: Int, value: Any?) {
        when (value) {
            null -> stmt.bindNull(index)
            is Long -> stmt.bindLong(index, value)
            is Int -> stmt.bindLong(index, value.toLong())
            is Double -> stmt.bindDouble(index, value)
            is Float -> stmt.bindDouble(index, value.toDouble())
            is String -> stmt.bindString(index, value)
            is ByteArray -> stmt.bindBlob(index, value)
            else -> stmt.bindString(index, value.toString())
        }
    }

    private fun queryLiveTableColumns(
        db: SupportSQLiteDatabase,
        table: String,
    ): List<String> {
        val cols = mutableListOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                val col = if (nameIdx >= 0) cursor.getString(nameIdx) else cursor.getString(1)
                if (!col.isNullOrBlank()) {
                    cols += col
                }
            }
        }
        return cols
    }

    private suspend fun rollbackDatabaseFromLocalSnapshotLocked(rollbackFile: File) {
        val rollbackStage = File(
            filesRootDir,
            ".rollback-recover-${UUID.randomUUID().toString().replace("-", "")}",
        )
        try {
            val extracted = archiveCodec.unpackAndVerify(
                archiveBytes = rollbackFile.readBytes(),
                destinationDir = rollbackStage,
                passphrase = null,
            )
            replaceAllTablesAtomically(extracted.databaseFile)
            refreshRoomObserversAndVerifyHealth()
        } finally {
            rollbackStage.deleteRecursively()
        }
    }

    private fun refreshRoomObserversAndVerifyHealth() {
        val db = database.openHelper.writableDatabase
        db.query("PRAGMA wal_checkpoint(PASSIVE)").use { /* consume */ }
        val integrity = db.query("PRAGMA integrity_check").use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
        }
        check(integrity.equals("ok", ignoreCase = true)) {
            "Post-restore SQLite integrity check failed: $integrity"
        }
        database.invalidationTracker.refreshVersionsSync()
    }

    private fun replaceReceiptsDirectory(
        extractedAttachmentsRoot: File?,
        manifestAttachments: List<BackupAttachmentManifestEntry>,
    ): Int {
        if (receiptsDir.exists()) {
            receiptsDir.deleteRecursively()
        }
        if (manifestAttachments.isEmpty() || extractedAttachmentsRoot == null) {
            return 0
        }
        if (!receiptsDir.exists() && !receiptsDir.mkdirs()) {
            throw IOException("无法创建票据附件目录")
        }
        var copied = 0
        for (entry in manifestAttachments) {
            val safeRel = BackupArchiveCodec.normalizeAttachmentRelPath(entry.localRelPath) ?: continue
            val srcFile = File(extractedAttachmentsRoot, safeRel)
            val dstFile = BackupArchiveCodec.resolveSafeAttachmentFile(filesRootDir, safeRel) ?: continue
            dstFile.parentFile?.mkdirs()
            srcFile.copyTo(dstFile, overwrite = true)
            copied += 1
        }
        return copied
    }

    private fun restoreReceiptsBackup(backupDir: File) {
        if (receiptsDir.exists()) {
            receiptsDir.deleteRecursively()
        }
        if (backupDir.exists() && backupDir.isDirectory) {
            copyDirectoryContents(backupDir, receiptsDir)
        }
    }

    private fun copyDirectoryContents(sourceDir: File, targetDir: File) {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        sourceDir.listFiles()?.forEach { child ->
            val dst = File(targetDir, child.name)
            if (child.isDirectory) {
                copyDirectoryContents(child, dst)
            } else {
                child.copyTo(dst, overwrite = true)
            }
        }
    }

    private fun resolveSafeRollbackFile(fileName: String): File? {
        val trimmed = fileName.trim()
        if (trimmed.isEmpty() || trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains("..")) {
            return null
        }
        val canonicalRoot = rollbackDir.canonicalFile
        val target = File(rollbackDir, trimmed).canonicalFile
        return if (target.path.startsWith(canonicalRoot.path + File.separator)) target else null
    }

    companion object {
        const val ROLLBACK_BACKUPS_DIR_NAME: String = "rollback_backups"
        const val PRE_RESTORE_PREFIX: String = "pre-restore"
        const val MANUAL_SNAPSHOT_PREFIX: String = "manual-snapshot"
        const val DEFAULT_MAX_LOCAL_ROLLBACKS: Int = 5

        /**
         * Complete 16-table topological insert order matching Room schema v1 foreign keys.
         */
        val ALL_TABLES_IN_INSERT_ORDER: List<String> = listOf(
            "accounts",
            "tags",
            "exchange_rates",
            "recurring_rules",
            "templates",
            "ai_drafts",
            "investment_assets",
            "market_quotes",
            "loan_plans",
            "debt_profiles",
            "budgets",
            "transactions",
            "postings",
            "transaction_tags",
            "attachments",
            "loan_installments",
        )

        val ALL_TABLES_IN_DELETE_ORDER: List<String> = ALL_TABLES_IN_INSERT_ORDER.asReversed()
    }
}
