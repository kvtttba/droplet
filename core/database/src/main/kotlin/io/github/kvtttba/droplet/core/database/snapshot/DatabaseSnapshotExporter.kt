package io.github.kvtttba.droplet.core.database.snapshot

import android.database.sqlite.SQLiteDatabase
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.DropletDatabaseContract
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WalCheckpointResult(
    val busy: Int,
    val logFrames: Int,
    val checkpointedFrames: Int,
) {
    val isFullyCheckpointed: Boolean
        get() = busy == 0 && logFrames == checkpointedFrames
}

data class DatabaseSnapshotMetadata(
    val file: File,
    val sha256: String,
    val sizeBytes: Long,
    val schemaVersion: Int,
    val exportedAt: Long,
    val walCheckpoint: WalCheckpointResult,
)

data class SnapshotIntegrityReport(
    val isIntegrityOk: Boolean,
    val integrityMessage: String,
    val schemaVersion: Int,
)

/**
 * Exports a transactionally consistent, standalone SQLite database file using
 * `PRAGMA wal_checkpoint(TRUNCATE)` followed by `VACUUM INTO` into an atomic temporary file.
 * Designed as the storage foundation for M5.3 WebDAV encrypted backup & safe rollback restore.
 */
class DatabaseSnapshotExporter(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Flushes WAL frames into the main database file and truncates the WAL file when unblocked.
     */
    suspend fun checkpointWal(database: DropletDatabase): WalCheckpointResult =
        withContext(ioDispatcher) {
            val db = database.openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                if (cursor.moveToFirst() && cursor.columnCount >= 3) {
                    WalCheckpointResult(
                        busy = cursor.getInt(0),
                        logFrames = cursor.getInt(1),
                        checkpointedFrames = cursor.getInt(2),
                    )
                } else {
                    WalCheckpointResult(busy = 0, logFrames = 0, checkpointedFrames = 0)
                }
            }
        }

    /**
     * Exports a consistent, compacted standalone SQLite snapshot to [targetFile].
     * Writes to a sibling temporary file, verifies `PRAGMA integrity_check`, then atomically
     * replaces [targetFile].
     */
    suspend fun exportConsistentSnapshot(
        database: DropletDatabase,
        targetFile: File,
        exportedAt: Long = System.currentTimeMillis(),
    ): DatabaseSnapshotMetadata = withContext(ioDispatcher) {
        require(!targetFile.absolutePath.contains('\u0000')) {
            "Snapshot target path must not contain NUL characters"
        }
        val parentDir = targetFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            check(parentDir.mkdirs() || parentDir.exists()) {
                "Failed to create snapshot parent directory: ${parentDir.absolutePath}"
            }
        }

        val tempFile = File(parentDir, "${targetFile.name}.vacuum.tmp")
        if (tempFile.exists()) {
            check(tempFile.delete()) { "Failed to clear stale temporary snapshot: ${tempFile.absolutePath}" }
        }

        val checkpoint = checkpointWal(database)
        val escapedPath = tempFile.absolutePath.replace("'", "''")

        try {
            val writableDb = database.openHelper.writableDatabase
            writableDb.execSQL("VACUUM INTO '$escapedPath'")

            val integrity = verifySnapshotIntegrityInternal(tempFile)
            check(integrity.isIntegrityOk) {
                "Exported snapshot failed SQLite integrity_check: ${integrity.integrityMessage}"
            }

            if (targetFile.exists() && !targetFile.delete()) {
                throw IllegalStateException("Failed to remove existing snapshot target: ${targetFile.absolutePath}")
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            DatabaseSnapshotMetadata(
                file = targetFile,
                sha256 = computeSha256Hex(targetFile),
                sizeBytes = targetFile.length(),
                schemaVersion = integrity.schemaVersion.takeIf { it > 0 }
                    ?: DropletDatabaseContract.SCHEMA_VERSION,
                exportedAt = exportedAt,
                walCheckpoint = checkpoint,
            )
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    /**
     * Opens [snapshotFile] read-only and validates `PRAGMA integrity_check` and `PRAGMA user_version`.
     */
    suspend fun verifySnapshotIntegrity(snapshotFile: File): SnapshotIntegrityReport =
        withContext(ioDispatcher) {
            verifySnapshotIntegrityInternal(snapshotFile)
        }

    private fun verifySnapshotIntegrityInternal(snapshotFile: File): SnapshotIntegrityReport {
        require(snapshotFile.exists() && snapshotFile.isFile) {
            "Snapshot file does not exist: ${snapshotFile.absolutePath}"
        }
        SQLiteDatabase.openDatabase(
            snapshotFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            val integrityMsg = db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
            }
            val schemaVersion = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            return SnapshotIntegrityReport(
                isIntegrityOk = integrityMsg.equals("ok", ignoreCase = true),
                integrityMessage = integrityMsg,
                schemaVersion = schemaVersion,
            )
        }
    }

    private fun computeSha256Hex(file: File): String {
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
