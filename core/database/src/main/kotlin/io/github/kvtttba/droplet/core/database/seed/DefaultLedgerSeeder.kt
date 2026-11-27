package io.github.kvtttba.droplet.core.database.seed

import androidx.room.withTransaction
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.entity.toEntity

data class SeedResult(
    val insertedAccountCount: Int,
    val insertedTagCount: Int,
)

/**
 * Idempotent database initializer that populates system equity accounts
 * ([io.github.kvtttba.droplet.core.model.SystemAccounts]), default accounts,
 * Yimu-style 2-level expense/income categories, and common tags without ever
 * overwriting user modifications.
 */
class DefaultLedgerSeeder(
    private val database: DropletDatabase,
) {
    private val accountDao = database.accountDao()
    private val tagDao = database.tagDao()

    suspend fun seedIfNeeded(nowEpochMs: Long = System.currentTimeMillis()): SeedResult {
        val allAccounts = DefaultSeedCatalog.allSeedAccounts(nowEpochMs).map { it.toEntity() }
        val rootAccounts = allAccounts.filter { it.parentId == null }
        val childAccounts = allAccounts.filter { it.parentId != null }
        val tags = DefaultSeedCatalog.defaultTags(nowEpochMs).map { it.toEntity() }

        return database.withTransaction {
            // Insert root accounts first to satisfy self-referencing parent_id foreign keys.
            val rootResults = accountDao.insertIgnoreAll(rootAccounts)
            val childResults = accountDao.insertIgnoreAll(childAccounts)
            val tagResults = tagDao.insertIgnoreAll(tags)

            SeedResult(
                insertedAccountCount = rootResults.count { it != -1L } + childResults.count { it != -1L },
                insertedTagCount = tagResults.count { it != -1L },
            )
        }
    }
}
