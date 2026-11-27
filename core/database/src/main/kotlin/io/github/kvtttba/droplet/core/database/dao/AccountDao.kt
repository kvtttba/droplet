package io.github.kvtttba.droplet.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import io.github.kvtttba.droplet.core.database.entity.AccountEntity
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Upsert
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(accounts: List<AccountEntity>): List<Long>

    @Query("SELECT * FROM accounts WHERE id = :accountId LIMIT 1")
    suspend fun getById(accountId: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id IN (:accountIds)")
    suspend fun getByIds(accountIds: List<String>): List<AccountEntity>

    @Query(
        """
        SELECT * FROM accounts
        WHERE (:includeArchived = 1 OR is_archived = 0)
          AND (:includeSystem = 1 OR is_system = 0)
        ORDER BY sort_order ASC, created_at ASC, id ASC
        """,
    )
    fun observeAll(
        includeArchived: Boolean = false,
        includeSystem: Boolean = false,
    ): Flow<List<AccountEntity>>

    @Query(
        """
        SELECT * FROM accounts
        WHERE type = :type
          AND (:includeArchived = 1 OR is_archived = 0)
          AND (:includeSystem = 1 OR is_system = 0)
        ORDER BY sort_order ASC, created_at ASC, id ASC
        """,
    )
    fun observeByType(
        type: AccountType,
        includeArchived: Boolean = false,
        includeSystem: Boolean = false,
    ): Flow<List<AccountEntity>>

    @Query(
        """
        SELECT * FROM accounts
        WHERE sub_type IN (:subTypes)
          AND (:includeArchived = 1 OR is_archived = 0)
        ORDER BY sort_order ASC, created_at ASC, id ASC
        """,
    )
    fun observeBySubTypes(
        subTypes: List<AccountSubType>,
        includeArchived: Boolean = false,
    ): Flow<List<AccountEntity>>

    @Query(
        """
        SELECT * FROM accounts
        WHERE type = :type
          AND sub_type = 'CATEGORY'
          AND parent_id IS NULL
          AND (:includeArchived = 1 OR is_archived = 0)
        ORDER BY sort_order ASC, id ASC
        """,
    )
    fun observeRootCategories(
        type: AccountType,
        includeArchived: Boolean = false,
    ): Flow<List<AccountEntity>>

    @Query(
        """
        SELECT * FROM accounts
        WHERE parent_id = :parentId
          AND (:includeArchived = 1 OR is_archived = 0)
        ORDER BY sort_order ASC, id ASC
        """,
    )
    fun observeChildren(
        parentId: String,
        includeArchived: Boolean = false,
    ): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE is_system = 1 ORDER BY id ASC")
    suspend fun getSystemAccounts(): List<AccountEntity>

    @Query("UPDATE accounts SET is_archived = :archived, updated_at = :updatedAt WHERE id = :accountId")
    suspend fun setArchived(accountId: String, archived: Boolean, updatedAt: Long)

    @Query("DELETE FROM accounts WHERE id = :accountId")
    suspend fun deleteById(accountId: String)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun countAll(): Int
}
