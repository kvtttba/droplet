package io.github.kvtttba.droplet.core.database.repository

import androidx.room.withTransaction
import io.github.kvtttba.droplet.core.ai.AiDraftAttachmentRef
import io.github.kvtttba.droplet.core.ai.AiDraftBatchCodec
import io.github.kvtttba.droplet.core.ai.AiDraftTransactionMapper
import io.github.kvtttba.droplet.core.ai.AiParsedDraftBatch
import io.github.kvtttba.droplet.core.ai.AiParsedTransactionCandidate
import io.github.kvtttba.droplet.core.ai.ReceiptImageProcessor
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.domain.ledger.IdGenerator
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.UuidIdGenerator
import io.github.kvtttba.droplet.core.model.AiDraft
import io.github.kvtttba.droplet.core.model.AiDraftInputType
import io.github.kvtttba.droplet.core.model.AiDraftStatus
import io.github.kvtttba.droplet.core.model.Attachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Result of atomically committing reviewed candidates from an [AiDraft] into double-entry ledger tables.
 */
data class AiDraftBatchCommitResult(
    val draftId: String,
    val committedTransactionIds: List<String>,
    val attachedReceiptCount: Int,
)

/**
 * Local Room-backed repository for AI capture drafts (`ai_drafts`) and receipt attachments (`attachments`).
 * Keeps Room Schema `v1` unchanged and guarantees all-or-nothing transactional batch commits.
 */
interface AiDraftRepository {

    fun observeActiveDrafts(): Flow<List<AiDraft>>

    fun observeActiveDraftCount(): Flow<Int>

    fun observeDraft(draftId: String): Flow<AiDraft?>

    suspend fun getDraft(draftId: String): AiDraft?

    suspend fun createDraft(
        inputType: AiDraftInputType,
        rawText: String? = null,
        attachments: List<AiDraftAttachmentRef> = emptyList(),
        status: AiDraftStatus = AiDraftStatus.PENDING_ONLINE,
        parsedBatch: AiParsedDraftBatch? = null,
        errorMessage: String? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AiDraft

    suspend fun updateDraftState(
        draftId: String,
        status: AiDraftStatus,
        parsedBatch: AiParsedDraftBatch? = null,
        errorMessage: String? = null,
        rawText: String? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AiDraft?

    suspend fun saveEditedCandidates(
        draftId: String,
        candidates: List<AiParsedTransactionCandidate>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AiDraft?

    suspend fun deleteDraft(draftId: String)

    /**
     * Atomically validates, converts, and commits all [selectedCandidates] via [LedgerEngine] and
     * [LedgerTransactionWriter], links any local receipt attachments, and marks the draft `COMMITTED`
     * inside a single SQLite transaction. Any validation or DB failure rolls back the entire batch.
     */
    suspend fun commitDraftBatch(
        draftId: String,
        selectedCandidates: List<AiParsedTransactionCandidate>,
        attachReceiptsToTransactions: Boolean = true,
        syncAttachmentsToWebdav: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Result<AiDraftBatchCommitResult>

    companion object {
        val ACTIVE_STATUSES: List<AiDraftStatus> = listOf(
            AiDraftStatus.PENDING_ONLINE,
            AiDraftStatus.PARSING,
            AiDraftStatus.READY,
            AiDraftStatus.FAILED,
        )
    }
}

class LocalAiDraftRepository(
    private val database: DropletDatabase,
    private val transactionWriter: LedgerTransactionWriter,
    private val seeder: DefaultLedgerSeeder,
    private val ledgerEngine: LedgerEngine,
    private val receiptImageProcessor: ReceiptImageProcessor? = null,
    private val idGenerator: IdGenerator = UuidIdGenerator,
) : AiDraftRepository {

    private val aiDraftDao = database.aiDraftDao()
    private val accountDao = database.accountDao()
    private val attachmentDao = database.attachmentDao()
    private val exchangeRateDao = database.exchangeRateDao()

    override fun observeActiveDrafts(): Flow<List<AiDraft>> =
        aiDraftDao.observeByStatuses(AiDraftRepository.ACTIVE_STATUSES)
            .map { entities -> entities.map { it.toDomain() } }

    override fun observeActiveDraftCount(): Flow<Int> =
        aiDraftDao.observeCountByStatuses(AiDraftRepository.ACTIVE_STATUSES)

    override fun observeDraft(draftId: String): Flow<AiDraft?> =
        aiDraftDao.observeById(draftId).map { it?.toDomain() }

    override suspend fun getDraft(draftId: String): AiDraft? =
        aiDraftDao.getById(draftId)?.toDomain()

    override suspend fun createDraft(
        inputType: AiDraftInputType,
        rawText: String?,
        attachments: List<AiDraftAttachmentRef>,
        status: AiDraftStatus,
        parsedBatch: AiParsedDraftBatch?,
        errorMessage: String?,
        nowEpochMs: Long,
    ): AiDraft {
        val draft = AiDraft(
            id = idGenerator.nextId(),
            inputType = inputType,
            rawText = rawText?.trim()?.takeIf { it.isNotEmpty() },
            attachmentPathsJson = attachments.takeIf { it.isNotEmpty() }
                ?.let(AiDraftBatchCodec::encodeAttachments),
            status = status,
            parsedItemsJson = parsedBatch?.let(AiDraftBatchCodec::encodeBatch),
            errorMessage = errorMessage?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        )
        aiDraftDao.upsert(draft.toEntity())
        return draft
    }

    override suspend fun updateDraftState(
        draftId: String,
        status: AiDraftStatus,
        parsedBatch: AiParsedDraftBatch?,
        errorMessage: String?,
        rawText: String?,
        nowEpochMs: Long,
    ): AiDraft? {
        val existing = aiDraftDao.getById(draftId)?.toDomain() ?: return null
        val updated = existing.copy(
            rawText = rawText?.trim()?.takeIf { it.isNotEmpty() } ?: existing.rawText,
            status = status,
            parsedItemsJson = parsedBatch?.let(AiDraftBatchCodec::encodeBatch) ?: existing.parsedItemsJson,
            errorMessage = errorMessage?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = nowEpochMs,
        )
        aiDraftDao.upsert(updated.toEntity())
        return updated
    }

    override suspend fun saveEditedCandidates(
        draftId: String,
        candidates: List<AiParsedTransactionCandidate>,
        nowEpochMs: Long,
    ): AiDraft? {
        val existing = aiDraftDao.getById(draftId)?.toDomain() ?: return null
        val encodedBatch = AiDraftBatchCodec.encodeBatch(
            AiParsedDraftBatch(
                items = candidates,
            ),
        )
        val nextStatus = when {
            existing.status == AiDraftStatus.COMMITTED -> AiDraftStatus.COMMITTED
            candidates.isNotEmpty() -> AiDraftStatus.READY
            else -> AiDraftStatus.PENDING_ONLINE
        }
        val updated = existing.copy(
            status = nextStatus,
            parsedItemsJson = encodedBatch,
            errorMessage = null,
            updatedAt = nowEpochMs,
        )
        aiDraftDao.upsert(updated.toEntity())
        return updated
    }

    override suspend fun deleteDraft(draftId: String) {
        val existing = aiDraftDao.getById(draftId)?.toDomain() ?: return
        aiDraftDao.deleteById(draftId)
        if (existing.status != AiDraftStatus.COMMITTED && receiptImageProcessor != null) {
            val refs = AiDraftBatchCodec.decodeAttachments(existing.attachmentPathsJson)
            val unreferenced = refs.filter { ref ->
                attachmentDao.countByLocalRelPath(ref.localRelPath) == 0
            }
            if (unreferenced.isNotEmpty()) {
                receiptImageProcessor.deleteReceiptFiles(unreferenced)
            }
        }
    }

    override suspend fun commitDraftBatch(
        draftId: String,
        selectedCandidates: List<AiParsedTransactionCandidate>,
        attachReceiptsToTransactions: Boolean,
        syncAttachmentsToWebdav: Boolean,
        nowEpochMs: Long,
    ): Result<AiDraftBatchCommitResult> = runCatching {
        require(selectedCandidates.isNotEmpty()) { "请至少勾选 1 笔待确认草稿再入账" }
        seeder.seedIfNeeded(nowEpochMs)

        database.withTransaction {
            val draft = aiDraftDao.getById(draftId)?.toDomain()
                ?: throw IllegalArgumentException("未找到对应草稿，可能已被删除")
            require(draft.status != AiDraftStatus.COMMITTED) {
                "该草稿已完成入账，请勿重复提交"
            }

            val allAccountsById = accountDao.getAll(
                includeArchived = true,
                includeSystem = true,
            ).associate { entity -> entity.id to entity.toDomain() }

            val rateTable = exchangeRateDao.getAll().toExchangeRateTable()
            val txSource = AiDraftTransactionMapper.inputTypeToSource(draft.inputType)

            val builtTransactions = selectedCandidates.mapIndexed { index, candidate ->
                AiDraftTransactionMapper.mapToTransaction(
                    candidate = candidate,
                    source = txSource,
                    accountsById = allAccountsById,
                    ledgerEngine = ledgerEngine,
                    rateProvider = rateTable,
                    nowEpochMs = nowEpochMs,
                    itemIndex = index,
                ).getOrThrow()
            }

            // Atomic validation + persistence inside the enclosing SQLite transaction.
            transactionWriter.saveTransactions(
                transactions = builtTransactions,
                allowArchivedAccounts = false,
            )

            val attachmentRefs = if (attachReceiptsToTransactions) {
                AiDraftBatchCodec.decodeAttachments(draft.attachmentPathsJson)
            } else {
                emptyList()
            }

            var linkedAttachmentCount = 0
            if (attachmentRefs.isNotEmpty()) {
                val attachmentEntities = buildList {
                    builtTransactions.forEach { tx ->
                        attachmentRefs.forEach { ref ->
                            add(
                                Attachment(
                                    id = idGenerator.nextId(),
                                    transactionId = tx.id,
                                    localRelPath = ref.localRelPath,
                                    sha256 = ref.sha256,
                                    mimeType = ref.mimeType,
                                    sizeBytes = ref.sizeBytes,
                                    syncToWebdav = syncAttachmentsToWebdav,
                                    createdAt = nowEpochMs,
                                ).toEntity(),
                            )
                        }
                    }
                }
                attachmentDao.upsertAll(attachmentEntities)
                linkedAttachmentCount = attachmentEntities.size
            }

            val committedBatchJson = AiDraftBatchCodec.encodeBatch(
                AiParsedDraftBatch(items = selectedCandidates),
            )
            aiDraftDao.updateParsingState(
                draftId = draft.id,
                status = AiDraftStatus.COMMITTED,
                parsedItemsJson = committedBatchJson,
                errorMessage = null,
                updatedAt = nowEpochMs,
            )

            AiDraftBatchCommitResult(
                draftId = draft.id,
                committedTransactionIds = builtTransactions.map { it.id },
                attachedReceiptCount = linkedAttachmentCount,
            )
        }
    }
}
