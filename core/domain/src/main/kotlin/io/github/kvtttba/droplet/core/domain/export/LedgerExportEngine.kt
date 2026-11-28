package io.github.kvtttba.droplet.core.domain.export

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.stats.StatsTagFilterMode
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure Kotlin/JVM multi-format ledger export engine supporting:
 * - Excel workbook (`.xlsx`, lightweight zero-dependency OOXML + ZipOutputStream)
 * - CSV (`.csv`, UTF-8 BOM + RFC-4180 escaping)
 * - Beancount plain-text double-entry ledger (`.beancount`)
 */
class LedgerExportEngine {

    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val fileDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val hundred = BigDecimal.valueOf(100L)

    fun preview(
        request: LedgerExportRequest,
        allTransactions: List<Transaction>,
        accountsById: Map<String, Account>,
        tagsById: Map<String, Tag>,
    ): LedgerExportPreview {
        val filtered = filterTransactions(request, allTransactions)
        var postingCount = 0
        var totalIncomeBaseMinor = 0L
        var totalExpenseBaseMinor = 0L

        for (tx in filtered) {
            postingCount += tx.postings.size
            if (tx.status != TransactionStatus.POSTED) continue
            if (tx.displayType == TransactionDisplayType.REIMBURSE && tx.refTransactionId == null) continue
            for (posting in tx.postings) {
                if (posting.isSystemBalancing) continue
                val account = accountsById[posting.accountId] ?: continue
                if (posting.baseCurrency != request.baseCurrency) continue
                when (account.type) {
                    AccountType.EXPENSE -> {
                        totalExpenseBaseMinor = Math.addExact(totalExpenseBaseMinor, posting.baseAmountMinor)
                    }
                    AccountType.INCOME -> {
                        totalIncomeBaseMinor = Math.addExact(
                            totalIncomeBaseMinor,
                            Math.negateExact(posting.baseAmountMinor),
                        )
                    }
                    else -> Unit
                }
            }
        }

        val operationalAccountCount = accountsById.values.count {
            !it.isSystem && (it.type == AccountType.ASSET || it.type == AccountType.LIABILITY)
        }

        return LedgerExportPreview(
            transactionCount = filtered.size,
            postingCount = postingCount,
            accountCount = operationalAccountCount,
            totalIncomeBase = Money(totalIncomeBaseMinor, request.baseCurrency),
            totalExpenseBase = Money(totalExpenseBaseMinor, request.baseCurrency),
            suggestedFileName = suggestFileName(request),
        )
    }

    fun export(
        request: LedgerExportRequest,
        allTransactions: List<Transaction>,
        accountsById: Map<String, Account>,
        tagsById: Map<String, Tag>,
        exchangeRateProvider: ExchangeRateProvider = ExchangeRateTable.empty(),
    ): LedgerExportArtifact {
        val filtered = filterTransactions(request, allTransactions)
        val postingCount = filtered.sumOf { it.postings.size }
        val fileName = suggestFileName(request)

        val bytes = when (request.format) {
            ExportFormat.CSV -> {
                val rows = buildTransactionRows(filtered, accountsById, tagsById, request.zoneId)
                CsvLedgerExporter.export(rows)
            }

            ExportFormat.XLSX -> {
                val txRows = buildTransactionRows(filtered, accountsById, tagsById, request.zoneId)
                val catRows = buildCategorySummaryRows(filtered, accountsById, request.baseCurrency)
                val acctRows = buildAccountBalanceRows(
                    allTransactions = allTransactions,
                    accountsById = accountsById,
                    baseCurrency = request.baseCurrency,
                    exchangeRateProvider = exchangeRateProvider,
                )
                XlsxLedgerExporter.export(
                    transactionRows = txRows,
                    categoryRows = catRows,
                    accountRows = acctRows,
                )
            }

            ExportFormat.BEANCOUNT -> BeancountLedgerExporter.export(
                transactions = filtered,
                accountsById = accountsById,
                tagsById = tagsById,
                baseCurrency = request.baseCurrency,
                zoneId = request.zoneId,
                generatedAtEpochMs = request.generatedAtEpochMs,
            )
        }

        return LedgerExportArtifact(
            format = request.format,
            fileName = fileName,
            mimeType = request.format.mimeType,
            bytes = bytes,
            transactionCount = filtered.size,
            postingCount = postingCount,
            generatedAtEpochMs = request.generatedAtEpochMs,
        )
    }

    fun suggestFileName(request: LedgerExportRequest): String {
        val zone = request.zoneId
        val today = Instant.ofEpochMilli(request.generatedAtEpochMs).atZone(zone).toLocalDate()
        val scopeSuffix = when (request.scope) {
            ExportScope.ALL_TIME -> "all-${fileDateFormatter.format(today)}"
            ExportScope.CURRENT_MONTH -> {
                val ym = request.startDate?.let { YearMonth.from(it) } ?: YearMonth.from(today)
                "%04d%02d".format(ym.year, ym.monthValue)
            }
            ExportScope.CURRENT_YEAR -> {
                val year = request.startDate?.year ?: today.year
                "%04d".format(year)
            }
            ExportScope.CUSTOM_RANGE -> {
                val start = request.startDate ?: today.withDayOfMonth(1)
                val end = request.endDate ?: today
                "${fileDateFormatter.format(start)}-${fileDateFormatter.format(end)}"
            }
        }
        return "droplet-ledger-$scopeSuffix.${request.format.extension}"
    }

    internal fun filterTransactions(
        request: LedgerExportRequest,
        allTransactions: List<Transaction>,
    ): List<Transaction> {
        val (startMs, endMsExclusive) = resolveRangeEpochBounds(request)
        val allowedStatuses = if (request.includeVoided) {
            setOf(TransactionStatus.POSTED, TransactionStatus.VOID)
        } else {
            setOf(TransactionStatus.POSTED)
        }

        return allTransactions
            .filter { tx ->
                tx.status in allowedStatuses &&
                    (startMs == null || tx.occurredAt >= startMs) &&
                    (endMsExclusive == null || tx.occurredAt < endMsExclusive) &&
                    matchesTags(tx, request.selectedTagIds, request.tagFilterMode)
            }
            .sortedWith(compareByDescending<Transaction>({ it.occurredAt }).thenByDescending { it.createdAt }.thenByDescending { it.id })
    }

    private fun resolveRangeEpochBounds(request: LedgerExportRequest): Pair<Long?, Long?> {
        val zone = request.zoneId
        val today = Instant.ofEpochMilli(request.generatedAtEpochMs).atZone(zone).toLocalDate()
        return when (request.scope) {
            ExportScope.ALL_TIME -> null to null
            ExportScope.CURRENT_MONTH -> {
                val ym = request.startDate?.let { YearMonth.from(it) } ?: YearMonth.from(today)
                val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                start to end
            }
            ExportScope.CURRENT_YEAR -> {
                val year = request.startDate?.year ?: today.year
                val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
                start to end
            }
            ExportScope.CUSTOM_RANGE -> {
                val rawStart = request.startDate
                val rawEnd = request.endDate
                val (s, e) = if (rawStart != null && rawEnd != null && rawEnd.isBefore(rawStart)) {
                    rawEnd to rawStart
                } else {
                    rawStart to rawEnd
                }
                val startMs = s?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
                val endMs = e?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
                startMs to endMs
            }
        }
    }

    private fun matchesTags(
        tx: Transaction,
        selectedTagIds: Set<String>,
        mode: StatsTagFilterMode,
    ): Boolean {
        if (selectedTagIds.isEmpty()) return true
        val txTags = tx.tagIds.toSet()
        return when (mode) {
            StatsTagFilterMode.ANY -> selectedTagIds.any { it in txTags }
            StatsTagFilterMode.ALL -> selectedTagIds.all { it in txTags }
        }
    }

    private fun buildTransactionRows(
        transactions: List<Transaction>,
        accountsById: Map<String, Account>,
        tagsById: Map<String, Tag>,
        zoneId: ZoneId,
    ): List<ExportTransactionRow> = transactions.map { tx ->
        val dateTimeText = dateTimeFormatter.format(Instant.ofEpochMilli(tx.occurredAt).atZone(zoneId))
        val businessPostings = tx.businessPostings

        val categoryPosting = businessPostings.firstOrNull { p ->
            val type = accountsById[p.accountId]?.type
            type == AccountType.EXPENSE || type == AccountType.INCOME
        }
        val balanceSheetPostings = businessPostings.filter { p ->
            val type = accountsById[p.accountId]?.type
            type == AccountType.ASSET || type == AccountType.LIABILITY
        }

        val categoryAccount = categoryPosting?.let { accountsById[it.accountId] }
        val categoryPath = when {
            categoryAccount != null -> formatCategoryPath(categoryAccount, accountsById)
            tx.displayType == TransactionDisplayType.TRANSFER -> "转账/还款"
            tx.displayType == TransactionDisplayType.REIMBURSE -> "待报销"
            tx.displayType == TransactionDisplayType.DEBT -> "往来借贷"
            tx.displayType == TransactionDisplayType.INVEST -> "投资交易"
            tx.displayType == TransactionDisplayType.LOAN_REPAY -> "分期还款"
            else -> "多拆分录"
        }

        val accountName = when {
            balanceSheetPostings.size == 1 -> accountsById[balanceSheetPostings[0].accountId]?.name ?: balanceSheetPostings[0].accountId
            balanceSheetPostings.size >= 2 -> {
                val from = balanceSheetPostings.firstOrNull { it.amountMinor < 0L } ?: balanceSheetPostings[0]
                val to = balanceSheetPostings.firstOrNull { it.amountMinor > 0L && it.id != from.id } ?: balanceSheetPostings[1]
                val fromName = accountsById[from.accountId]?.name ?: from.accountId
                val toName = accountsById[to.accountId]?.name ?: to.accountId
                "$fromName → $toName"
            }
            else -> businessPostings.firstOrNull()?.let { accountsById[it.accountId]?.name } ?: ""
        }

        val primaryPosting = categoryPosting
            ?: balanceSheetPostings.firstOrNull { it.amountMinor < 0L }
            ?: balanceSheetPostings.firstOrNull()
            ?: businessPostings.first()

        // Signed amount convention for flat spreadsheet readability:
        // Expense => negative, Income => positive, Refund => positive (reduces expense), Transfer => positive magnitude.
        val signedOriginalMoney = when (accountsById[primaryPosting.accountId]?.type) {
            AccountType.EXPENSE -> -primaryPosting.money
            AccountType.INCOME -> -primaryPosting.money
            else -> primaryPosting.money.abs()
        }
        val signedBaseMoney = when (accountsById[primaryPosting.accountId]?.type) {
            AccountType.EXPENSE -> -primaryPosting.baseMoney
            AccountType.INCOME -> -primaryPosting.baseMoney
            else -> primaryPosting.baseMoney.abs()
        }

        val tagsJoined = tx.tagIds.mapNotNull { tagsById[it]?.name }.joinToString(" / ")

        ExportTransactionRow(
            dateTimeText = dateTimeText,
            displayTypeLabel = displayTypeLabel(tx.displayType),
            categoryPath = categoryPath,
            accountName = accountName,
            amountDecimal = ExportDecimalFormatter.formatMoney(signedOriginalMoney),
            currencyCode = signedOriginalMoney.currency.code,
            baseAmountDecimal = ExportDecimalFormatter.formatMoney(signedBaseMoney),
            baseCurrencyCode = signedBaseMoney.currency.code,
            exchangeRateDecimal = primaryPosting.exchangeRateSnapshot.rateDecimal,
            payee = tx.payee?.trim().orEmpty(),
            tagsJoined = tagsJoined,
            statusLabel = statusLabel(tx.status),
            note = tx.note?.trim().orEmpty(),
            transactionId = tx.id,
            originalTransaction = tx,
        )
    }

    private fun buildCategorySummaryRows(
        transactions: List<Transaction>,
        accountsById: Map<String, Account>,
        baseCurrency: Currency,
    ): List<ExportCategorySummaryRow> {
        val expenseTotals = linkedMapOf<String, Pair<Long, MutableSet<String>>>()
        val incomeTotals = linkedMapOf<String, Pair<Long, MutableSet<String>>>()

        for (tx in transactions) {
            if (tx.status != TransactionStatus.POSTED) continue
            if (tx.displayType == TransactionDisplayType.REIMBURSE && tx.refTransactionId == null) continue
            for (posting in tx.postings) {
                if (posting.isSystemBalancing) continue
                if (posting.baseCurrency != baseCurrency) continue
                val account = accountsById[posting.accountId] ?: continue
                if (account.type != AccountType.EXPENSE && account.type != AccountType.INCOME) continue

                val root = resolveRootCategory(account, accountsById)
                when (account.type) {
                    AccountType.EXPENSE -> {
                        val (prev, ids) = expenseTotals[root.id] ?: (0L to linkedSetOf())
                        ids += tx.id
                        expenseTotals[root.id] = Math.addExact(prev, posting.baseAmountMinor) to ids
                    }
                    AccountType.INCOME -> {
                        val (prev, ids) = incomeTotals[root.id] ?: (0L to linkedSetOf())
                        ids += tx.id
                        incomeTotals[root.id] = Math.addExact(prev, Math.negateExact(posting.baseAmountMinor)) to ids
                    }
                    else -> Unit
                }
            }
        }

        val rows = mutableListOf<ExportCategorySummaryRow>()
        rows += toCategoryRows("支出", expenseTotals, accountsById, baseCurrency)
        rows += toCategoryRows("收入", incomeTotals, accountsById, baseCurrency)
        return rows
    }

    private fun toCategoryRows(
        directionLabel: String,
        totals: Map<String, Pair<Long, MutableSet<String>>>,
        accountsById: Map<String, Account>,
        baseCurrency: Currency,
    ): List<ExportCategorySummaryRow> {
        val positiveEntries = totals.entries
            .filter { it.value.first > 0L }
            .sortedByDescending { it.value.first }
        if (positiveEntries.isEmpty()) return emptyList()

        var grandTotal = 0L
        for (entry in positiveEntries) {
            grandTotal = Math.addExact(grandTotal, entry.value.first)
        }
        val grandTotalBd = BigDecimal.valueOf(grandTotal)

        return positiveEntries.map { (rootId, pair) ->
            val (amountMinor, txIds) = pair
            val pct = BigDecimal.valueOf(amountMinor)
                .multiply(hundred)
                .divide(grandTotalBd, 1, RoundingMode.HALF_UP)
                .toPlainString()
            ExportCategorySummaryRow(
                directionLabel = directionLabel,
                categoryName = accountsById[rootId]?.name ?: rootId,
                amountDecimal = ExportDecimalFormatter.formatMoney(Money(amountMinor, baseCurrency)),
                currencyCode = baseCurrency.code,
                sharePctDecimal = pct,
                transactionCount = txIds.size,
            )
        }
    }

    private fun buildAccountBalanceRows(
        allTransactions: List<Transaction>,
        accountsById: Map<String, Account>,
        baseCurrency: Currency,
        exchangeRateProvider: ExchangeRateProvider,
    ): List<ExportAccountBalanceRow> {
        val balancesByAccount = hashMapOf<String, Long>()
        for (tx in allTransactions) {
            if (tx.status != TransactionStatus.POSTED) continue
            for (posting in tx.postings) {
                if (posting.isSystemBalancing) continue
                val account = accountsById[posting.accountId] ?: continue
                if (account.currency != posting.currency) continue
                balancesByAccount[account.id] = Math.addExact(
                    balancesByAccount[account.id] ?: 0L,
                    posting.amountMinor,
                )
            }
        }

        return accountsById.values
            .filter { !it.isSystem && (it.type == AccountType.ASSET || it.type == AccountType.LIABILITY) }
            .sortedWith(compareBy<Account>({ it.isArchived }, { it.type.ordinal }, { it.sortOrder }, { it.id }))
            .map { account ->
                val origMoney = Money(balancesByAccount[account.id] ?: 0L, account.currency)
                val baseMoney = if (account.currency == baseCurrency) {
                    origMoney
                } else {
                    exchangeRateProvider.findRate(account.currency, baseCurrency)
                        ?.convert(origMoney)
                        ?: Money.zero(baseCurrency)
                }
                ExportAccountBalanceRow(
                    accountName = account.name,
                    typeLabel = if (account.type == AccountType.ASSET) "资产" else "负债",
                    subTypeLabel = subTypeLabel(account.subType),
                    currencyCode = account.currency.code,
                    originalBalanceDecimal = ExportDecimalFormatter.formatMoney(origMoney),
                    baseBalanceDecimal = ExportDecimalFormatter.formatMoney(baseMoney),
                    baseCurrencyCode = baseCurrency.code,
                    includeInNetWorthLabel = if (account.includeInNetWorth) "是" else "否",
                    statusLabel = if (account.isArchived) "已归档" else "启用中",
                )
            }
    }

    private fun formatCategoryPath(
        category: Account,
        accountsById: Map<String, Account>,
    ): String {
        val parent = category.parentId?.let { accountsById[it] }
        return if (parent != null) "${parent.name}/${category.name}" else category.name
    }

    private fun resolveRootCategory(
        category: Account,
        accountsById: Map<String, Account>,
    ): Account {
        var current = category
        val visited = hashSetOf(current.id)
        while (current.parentId != null) {
            val parent = accountsById[current.parentId] ?: break
            if (!visited.add(parent.id)) break
            current = parent
        }
        return current
    }

    private fun displayTypeLabel(type: TransactionDisplayType): String = when (type) {
        TransactionDisplayType.EXPENSE -> "支出"
        TransactionDisplayType.INCOME -> "收入"
        TransactionDisplayType.TRANSFER -> "转账"
        TransactionDisplayType.LOAN_REPAY -> "分期还款"
        TransactionDisplayType.DEBT -> "往来借贷"
        TransactionDisplayType.REFUND -> "退款"
        TransactionDisplayType.REIMBURSE -> "报销"
        TransactionDisplayType.INVEST -> "投资"
        TransactionDisplayType.SPLIT -> "多拆分录"
    }

    private fun statusLabel(status: TransactionStatus): String = when (status) {
        TransactionStatus.POSTED -> "已入账"
        TransactionStatus.DRAFT -> "草稿"
        TransactionStatus.VOID -> "已作废"
    }

    private fun subTypeLabel(subType: AccountSubType): String = when (subType) {
        AccountSubType.CASH -> "现金"
        AccountSubType.DEBIT_CARD -> "储蓄卡"
        AccountSubType.CREDIT_CARD -> "信用账户"
        AccountSubType.STORED_VALUE -> "储值账户"
        AccountSubType.INVEST_CASH -> "投资资金户"
        AccountSubType.INVEST_HOLDING -> "投资持仓"
        AccountSubType.LOAN -> "分期贷款"
        AccountSubType.RECEIVABLE -> "应收借出"
        AccountSubType.PAYABLE -> "应付借入"
        AccountSubType.REIMBURSEMENT -> "待报销"
        AccountSubType.CATEGORY -> "收支分类"
        AccountSubType.TRADING -> "币种兑换"
        AccountSubType.OPENING -> "期初余额"
    }
}
