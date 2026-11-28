package io.github.kvtttba.droplet.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.kvtttba.droplet.core.database.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {

    @Upsert
    suspend fun upsert(attachment: AttachmentEntity)

    @Upsert
    suspend fun upsertAll(attachments: List<AttachmentEntity>)

    @Query("SELECT * FROM attachments WHERE id = :attachmentId LIMIT 1")
    suspend fun getById(attachmentId: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE transaction_id = :transactionId ORDER BY created_at ASC, id ASC")
    fun observeByTransactionId(transactionId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE sync_to_webdav = 1 ORDER BY created_at ASC, id ASC")
    fun observeSyncEnabled(): Flow<List<AttachmentEntity>>

    @Query("DELETE FROM attachments WHERE id = :attachmentId")
    suspend fun deleteById(attachmentId: String)

    @Query("SELECT COUNT(*) FROM attachments")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM attachments WHERE local_rel_path = :localRelPath")
    suspend fun countByLocalRelPath(localRelPath: String): Int
}
