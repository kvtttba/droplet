package io.github.kvtttba.droplet.core.database.repository

import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.domain.export.ExportFormat
import io.github.kvtttba.droplet.core.domain.export.LedgerExportArtifact
import io.github.kvtttba.droplet.core.domain.export.LedgerExportEngine
import io.github.kvtttba.droplet.core.domain.export.LedgerExportPreview
import io.github.kvtttba.droplet.core.domain.export.LedgerExportRequest
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class ExportedFileResult(
    val format: ExportFormat,
    val file: File,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val transactionCount: Int,
    val postingCount: Int,
    val generatedAtEpochMs: Long,
)

/**
 * Repository querying Room ledger snapshots and producing CSV, lightweight OOXML `.xlsx`,
 * and Beancount plain-text export artifacts or local files.
 */
interface LedgerExportRepository {

    fun observeAvailableTags(): Flow<List<Tag>>

    fun observeExportPreview(request: LedgerExportRequest): Flow<LedgerExportPreview>

    suspend fun exportBytes(request: LedgerExportRequest): LedgerExportArtifact

    suspend fun exportToLocalFile(
        request: LedgerExportRequest,
        outputDirectory: File,
    ): ExportedFileResult
}

class LocalLedgerExportRepository(
    private val database: DropletDatabase,
    private val exportEngine: LedgerExportEngine = LedgerExportEngine(),
    private val clock: NetworkClock = NetworkClock.SYSTEM,
) : LedgerExportRepository {

    private val transactionDao = database.transactionDao()
    private val accountDao = database.accountDao()
    private val tagDao = database.tagDao()
    private val exchangeRateDao = database.exchangeRateDao()

    override fun observeAvailableTags(): Flow<List<Tag>> =
        tagDao.observeAll(includeArchived = false).map { list -> list.map { it.toDomain() } }

    override fun observeExportPreview(request: LedgerExportRequest): Flow<LedgerExportPreview> {
        val statuses = if (request.includeVoided) {
            listOf(TransactionStatus.POSTED, TransactionStatus.VOID)
        } else {
            listOf(TransactionStatus.POSTED)
        }
        return combine(
            transactionDao.observeAllByStatuses(statuses),
            accountDao.observeAll(includeArchived = true, includeSystem = true),
            tagDao.observeAll(includeArchived = true),
        ) { txDetails, accountEntities, tagEntities ->
            val transactions = txDetails.map { it.toDomain() }
            val accountsById = accountEntities.associate { it.id to it.toDomain() }
            val tagsById = tagEntities.associate { it.id to it.toDomain() }
            exportEngine.preview(
                request = request,
                allTransactions = transactions,
                accountsById = accountsById,
                tagsById = tagsById,
            )
        }
    }

    override suspend fun exportBytes(request: LedgerExportRequest): LedgerExportArtifact =
        withContext(Dispatchers.IO) {
            val effectiveRequest = request.copy(generatedAtEpochMs = clock.nowEpochMs())
            val statuses = if (effectiveRequest.includeVoided) {
                listOf(TransactionStatus.POSTED, TransactionStatus.VOID)
            } else {
                listOf(TransactionStatus.POSTED)
            }
            val transactions = transactionDao.getAllByStatuses(statuses).map { it.toDomain() }
            val accountsById = accountDao.getAll(includeArchived = true, includeSystem = true)
                .associate { it.id to it.toDomain() }
            val tagsById = tagDao.getAll(includeArchived = true)
                .associate { it.id to it.toDomain() }
            val rateProvider = PlanningFallbackRateProvider(
                exchangeRateDao.getAll().toExchangeRateTable(),
            )
            exportEngine.export(
                request = effectiveRequest,
                allTransactions = transactions,
                accountsById = accountsById,
                tagsById = tagsById,
                exchangeRateProvider = rateProvider,
            )
        }

    override suspend fun exportToLocalFile(
        request: LedgerExportRequest,
        outputDirectory: File,
    ): ExportedFileResult = withContext(Dispatchers.IO) {
        val artifact = exportBytes(request)
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }
        val safeName = sanitizeOutputFileName(artifact.fileName)
        val targetFile = File(outputDirectory, safeName)
        val canonicalDir = outputDirectory.canonicalFile
        val canonicalTarget = targetFile.canonicalFile
        require(canonicalTarget.path.startsWith(canonicalDir.path + File.separator)) {
            "Unsafe export path rejected"
        }
        val tempFile = File(outputDirectory, "$safeName.tmp")
        tempFile.writeBytes(artifact.bytes)
        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        ExportedFileResult(
            format = artifact.format,
            file = targetFile,
            fileName = safeName,
            mimeType = artifact.mimeType,
            sizeBytes = targetFile.length(),
            transactionCount = artifact.transactionCount,
            postingCount = artifact.postingCount,
            generatedAtEpochMs = artifact.generatedAtEpochMs,
        )
    }

    private fun sanitizeOutputFileName(name: String): String {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return leaf.ifBlank { "droplet-export.dat" }
    }
}
