package io.github.kvtttba.droplet.core.database

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.SystemAccounts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DefaultLedgerSeederTest {

    private lateinit var database: DropletDatabase
    private lateinit var seeder: DefaultLedgerSeeder

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        seeder = DefaultLedgerSeeder(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun seedIfNeeded_populatesSystemEquityAccountsDefaultAccountsTwoLevelCategoriesAndTags() = runTest {
        val firstSeed = seeder.seedIfNeeded(nowEpochMs = 1_700_000_000_000L)
        val expectedAccounts = DefaultSeedCatalog.allSeedAccounts(1_700_000_000_000L)
        val expectedTags = DefaultSeedCatalog.defaultTags(1_700_000_000_000L)

        assertEquals(expectedAccounts.size, firstSeed.insertedAccountCount)
        assertEquals(expectedTags.size, firstSeed.insertedTagCount)
        assertEquals(expectedAccounts.size, database.accountDao().countAll())
        assertEquals(expectedTags.size, database.tagDao().countAll())

        // Verify all 6 canonical SystemAccounts exist with exact IDs & properties
        for (currency in Currency.entries) {
            val trading = database.accountDao().getById(SystemAccounts.tradingAccountId(currency))?.toDomain()
            assertNotNull("Missing trading account for $currency", trading)
            assertEquals(AccountType.EQUITY, trading!!.type)
            assertEquals(AccountSubType.TRADING, trading.subType)
            assertEquals(currency, trading.currency)
            assertTrue(trading.isSystem)
            assertFalse(trading.includeInNetWorth)

            val opening = database.accountDao().getById(SystemAccounts.openingBalanceAccountId(currency))?.toDomain()
            assertNotNull("Missing opening account for $currency", opening)
            assertEquals(AccountType.EQUITY, opening!!.type)
            assertEquals(AccountSubType.OPENING, opening.subType)
            assertEquals(currency, opening.currency)
            assertTrue(opening.isSystem)
            assertFalse(opening.includeInNetWorth)
        }

        // Verify 2-level expense & income categories hierarchy
        val expenseRoots = database.accountDao().observeRootCategories(AccountType.EXPENSE).first()
        assertEquals(10, expenseRoots.size)
        val foodChildren = database.accountDao().observeChildren(DefaultSeedCatalog.AccountIds.EXP_FOOD).first()
        assertEquals(7, foodChildren.size)
        assertTrue(foodChildren.all { it.parentId == DefaultSeedCatalog.AccountIds.EXP_FOOD })
        assertTrue(foodChildren.all { it.type == AccountType.EXPENSE && it.subType == AccountSubType.CATEGORY })

        val incomeRoots = database.accountDao().observeRootCategories(AccountType.INCOME).first()
        assertEquals(3, incomeRoots.size)
        val workChildren = database.accountDao().observeChildren(DefaultSeedCatalog.AccountIds.INC_WORK).first()
        assertEquals(3, workChildren.size)

        // Verify default operational accounts are visible when system accounts are excluded
        val activeNonSystemAssets = database.accountDao()
            .observeByType(AccountType.ASSET, includeArchived = false, includeSystem = false)
            .first()
        assertEquals(4, activeNonSystemAssets.size) // 现金、储蓄卡、微信零钱、支付宝 (待报销 is system)

        // Verify system reimbursement account exists
        val reimbursement = database.accountDao()
            .getById(DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY)
        assertNotNull(reimbursement)
        assertEquals(AccountSubType.REIMBURSEMENT, reimbursement!!.subType)
        assertTrue(reimbursement.isSystem)
    }

    @Test
    fun seedIfNeeded_isIdempotentAndPreservesUserModifications() = runTest {
        seeder.seedIfNeeded(nowEpochMs = 1_000L)
        val totalAccountsAfterFirst = database.accountDao().countAll()
        val totalTagsAfterFirst = database.tagDao().countAll()

        // User customizes a seeded account and a seeded tag
        val debitCard = database.accountDao().getById(DefaultSeedCatalog.AccountIds.DEBIT_CARD)!!
        database.accountDao().upsert(debitCard.copy(name = "招行储蓄卡", updatedAt = 2_000L))
        database.accountDao().setArchived(DefaultSeedCatalog.AccountIds.CASH, archived = true, updatedAt = 2_000L)

        val travelTag = database.tagDao().getById(DefaultSeedCatalog.TagIds.TRAVEL)!!
        database.tagDao().upsert(travelTag.copy(name = "深度游", color = "#000000", updatedAt = 2_000L))

        // Delete one unused child category & one unused tag to simulate partial recovery
        database.accountDao().deleteById(DefaultSeedCatalog.AccountIds.EXP_OTHER_MISC)
        database.tagDao().deleteById(DefaultSeedCatalog.TagIds.IMPULSE)

        // Second run should only restore the 1 deleted account and 1 deleted tag
        val secondSeed = seeder.seedIfNeeded(nowEpochMs = 3_000L)
        assertEquals(1, secondSeed.insertedAccountCount)
        assertEquals(1, secondSeed.insertedTagCount)
        assertEquals(totalAccountsAfterFirst, database.accountDao().countAll())
        assertEquals(totalTagsAfterFirst, database.tagDao().countAll())

        // Third run inserts nothing
        val thirdSeed = seeder.seedIfNeeded(nowEpochMs = 4_000L)
        assertEquals(0, thirdSeed.insertedAccountCount)
        assertEquals(0, thirdSeed.insertedTagCount)

        // User edits remain preserved
        assertEquals("招行储蓄卡", database.accountDao().getById(DefaultSeedCatalog.AccountIds.DEBIT_CARD)!!.name)
        assertTrue(database.accountDao().getById(DefaultSeedCatalog.AccountIds.CASH)!!.isArchived)
        assertEquals("深度游", database.tagDao().getById(DefaultSeedCatalog.TagIds.TRAVEL)!!.name)
    }

    @Test
    fun seedCatalog_hasUniqueIdsTagNamesAndStrictTwoLevelCategoryTree() {
        val accounts = DefaultSeedCatalog.allSeedAccounts(1_000L)
        val tags = DefaultSeedCatalog.defaultTags(1_000L)

        // All IDs and tag names must be globally unique
        assertEquals(accounts.size, accounts.map { it.id }.toSet().size)
        assertEquals(tags.size, tags.map { it.id }.toSet().size)
        assertEquals(tags.size, tags.map { it.name }.toSet().size)

        // Every child category references an existing root category of identical AccountType (strict 2 levels)
        val byId = accounts.associateBy { it.id }
        val children = accounts.filter { it.parentId != null }
        assertTrue(children.isNotEmpty())
        for (child in children) {
            val parent = byId[child.parentId]
            assertNotNull("Missing parent ${child.parentId} for ${child.id}", parent)
            assertEquals("Parent ${parent!!.id} must be root (2-level tree)", null, parent.parentId)
            assertEquals(parent.type, child.type)
            assertEquals(AccountSubType.CATEGORY, parent.subType)
            assertEquals(AccountSubType.CATEGORY, child.subType)
            assertFalse(child.includeInNetWorth)
        }
    }
}
