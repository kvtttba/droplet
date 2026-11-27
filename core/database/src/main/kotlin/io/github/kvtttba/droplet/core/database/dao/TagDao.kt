package io.github.kvtttba.droplet.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import io.github.kvtttba.droplet.core.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(tags: List<TagEntity>): List<Long>

    @Query("SELECT * FROM tags WHERE id = :tagId LIMIT 1")
    suspend fun getById(tagId: String): TagEntity?

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Query("SELECT * FROM tags WHERE id IN (:tagIds)")
    suspend fun getByIds(tagIds: List<String>): List<TagEntity>

    @Query(
        """
        SELECT * FROM tags
        WHERE (:includeArchived = 1 OR is_archived = 0)
        ORDER BY sort_order ASC, created_at ASC, id ASC
        """,
    )
    fun observeAll(includeArchived: Boolean = false): Flow<List<TagEntity>>

    @Query("UPDATE tags SET is_archived = :archived, updated_at = :updatedAt WHERE id = :tagId")
    suspend fun setArchived(tagId: String, archived: Boolean, updatedAt: Long)

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteById(tagId: String)

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun countAll(): Int
}
