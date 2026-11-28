package io.github.kvtttba.droplet.core.database.repository

import androidx.room.withTransaction
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.entity.toEntity
import io.github.kvtttba.droplet.core.database.entity.toExchangeRateTable
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.automation.RecurringPayload
import io.github.kvtttba.droplet.core.domain.automation.RecurringPayloadCodec
import io.github.kvtttba.droplet.core.domain.automation.RecurringTransactionBuilder
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionSource
import io.github.kvtttba.droplet.core.model.TransactionTemplate
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class TransactionTemplateItem(
    val template: TransactionTemplate,
    val payload: RecurringPayload?,
    val accountName: String,
    val categoryOrTargetName: String,
    val resolvedIcon: String,
    val resolvedColor: String?,
)

data class SaveTemplateRequest(
    val id: String? = null,
    val name: String,
    val payload: RecurringPayload,
    val icon: String? = null,
    val color: String? = null,
    val sortOrder: Int? = null,
)

/**
 * Repository managing quick transaction templates for one-tap direct posting or pre-filling
 * the transaction editor, tracking usage counts and ordering.
 */
interface TemplateRepository {

    fun observeTemplates(): Flow<List<TransactionTemplateItem>>

    suspend fun getTemplateById(templateId: String): TransactionTemplateItem?

    suspend fun saveTemplate(request: SaveTemplateRequest): TransactionTemplate

    suspend fun recordTemplateUsage(
        templateId: String,
        usedAtEpochMs: Long? = null,
    )

    suspend fun updateSortOrder(
        templateId: String,
        sortOrder: Int,
    )

    suspend fun deleteTemplate(templateId: String)

    /**
     * Atomically constructs and posts a balanced double-entry transaction from [templateId]
     * and increments its usage statistics.
     */
    suspend fun postFromTemplate(
        templateId: String,
        occurredAtEpochMs: Long? = null,
    ): Transaction
}

class LocalTemplateRepository(
    private val database: DropletDatabase,
    private val transactionWriter: LedgerTransactionWriter,
    private val seeder: DefaultLedgerSeeder,
    private val ledgerEngine: LedgerEngine,
    private val clock: NetworkClock = NetworkClock.SYSTEM,
) : TemplateRepository {

    private val templateDao = database.templateDao()
    private val accountDao = database.accountDao()
    private val exchangeRateDao = database.exchangeRateDao()

    override fun observeTemplates(): Flow<List<TransactionTemplateItem>> = combine(
        templateDao.observeAll(),
        accountDao.observeAll(includeArchived = true, includeSystem = true),
    ) { templateEntities, accountEntities ->
        val accountsById = accountEntities.associate { it.id to it.toDomain() }
        templateEntities.map { entity ->
            toTemplateItem(entity.toDomain(), accountsById)
        }
    }

    override suspend fun getTemplateById(templateId: String): TransactionTemplateItem? {
        val entity = templateDao.getById(templateId) ?: return null
        val accountsById = accountDao.getAll(includeArchived = true, includeSystem = true)
            .associate { it.id to it.toDomain() }
        return toTemplateItem(entity.toDomain(), accountsById)
    }

    override suspend fun saveTemplate(request: SaveTemplateRequest): TransactionTemplate {
        val now = clock.nowEpochMs()
        seeder.seedIfNeeded(now)

        val trimmedName = request.name.trim()
        require(trimmedName.isNotEmpty()) { "快捷模板名称不能为空" }

        val accountsById = accountDao.getAll(includeArchived = true, includeSystem = true)
            .associate { it.id to it.toDomain() }
        val rateProvider = PlanningFallbackRateProvider(exchangeRateDao.getAll().toExchangeRateTable())
        // Validate double-entry constructability before saving
        RecurringTransactionBuilder.buildTransaction(
            payload = request.payload,
            occurredAt = now,
            source = TransactionSource.MANUAL,
            ledgerEngine = ledgerEngine,
            accountsById = accountsById,
            rateProvider = rateProvider,
            defaultReimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
            nowEpochMs = now,
        )

        val encoded = RecurringPayloadCodec.encode(request.payload)
        return database.withTransaction {
            val existing = request.id?.let { templateDao.getById(it) }
            val nextSortOrder = request.sortOrder
                ?: existing?.sortOrder
                ?: (templateDao.getAll().maxOfOrNull { it.sortOrder }?.plus(10) ?: 10)
            val categoryAccount = request.payload.categoryAccountId?.let { accountsById[it] }
            val template = TransactionTemplate(
                id = existing?.id ?: request.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                name = trimmedName,
                displayType = request.payload.displayType,
                templatePayloadJson = encoded,
                icon = request.icon ?: existing?.icon ?: categoryAccount?.icon ?: "bolt",
                color = request.color ?: existing?.color ?: categoryAccount?.color,
                sortOrder = nextSortOrder,
                useCount = existing?.useCount ?: 0,
                lastUsedAt = existing?.lastUsedAt,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            templateDao.upsert(template.toEntity())
            template
        }
    }

    override suspend fun recordTemplateUsage(
        templateId: String,
        usedAtEpochMs: Long?,
    ) {
        val now = usedAtEpochMs ?: clock.nowEpochMs()
        templateDao.recordUsage(templateId = templateId, usedAt = now)
    }

    override suspend fun updateSortOrder(templateId: String, sortOrder: Int) {
        templateDao.updateSortOrder(
            templateId = templateId,
            sortOrder = sortOrder,
            updatedAt = clock.nowEpochMs(),
        )
    }

    override suspend fun deleteTemplate(templateId: String) {
        templateDao.deleteById(templateId)
    }

    override suspend fun postFromTemplate(
        templateId: String,
        occurredAtEpochMs: Long?,
    ): Transaction {
        val now = occurredAtEpochMs ?: clock.nowEpochMs()
        seeder.seedIfNeeded(now)

        return database.withTransaction {
            val entity = templateDao.getById(templateId)
                ?: throw IllegalArgumentException("未找到快捷模板")
            val payload = RecurringPayloadCodec.decode(entity.templatePayloadJson)
            val accountsById = accountDao.getAll(includeArchived = true, includeSystem = true)
                .associate { it.id to it.toDomain() }
            val rateProvider = PlanningFallbackRateProvider(exchangeRateDao.getAll().toExchangeRateTable())
            val tx = RecurringTransactionBuilder.buildTransaction(
                payload = payload,
                occurredAt = now,
                source = TransactionSource.MANUAL,
                ledgerEngine = ledgerEngine,
                accountsById = accountsById,
                rateProvider = rateProvider,
                defaultReimbursementAccountId = DefaultSeedCatalog.AccountIds.REIMBURSEMENT_CNY,
                nowEpochMs = now,
            )
            transactionWriter.saveTransaction(tx)
            templateDao.recordUsage(templateId = templateId, usedAt = now)
            tx
        }
    }

    private fun toTemplateItem(
        template: TransactionTemplate,
        accountsById: Map<String, Account>,
    ): TransactionTemplateItem {
        val payload = RecurringPayloadCodec.decodeOrNull(template.templatePayloadJson)
        val account = payload?.accountId?.let { accountsById[it] }
        val category = payload?.categoryAccountId?.let { accountsById[it] }
        val target = payload?.targetAccountId?.let { accountsById[it] }

        val categoryOrTargetName = when {
            payload == null -> "配置无效"
            payload.isReimbursable -> "待报销垫付"
            target != null -> "转入 ${target.name}"
            category != null -> {
                val parent = category.parentId?.let { accountsById[it] }
                if (parent != null) "${parent.name}·${category.name}" else category.name
            }
            else -> "未分类"
        }

        return TransactionTemplateItem(
            template = template,
            payload = payload,
            accountName = account?.name ?: "未关联账户",
            categoryOrTargetName = categoryOrTargetName,
            resolvedIcon = template.icon ?: category?.icon ?: account?.icon ?: "bolt",
            resolvedColor = template.color ?: category?.color ?: account?.color,
        )
    }
}
