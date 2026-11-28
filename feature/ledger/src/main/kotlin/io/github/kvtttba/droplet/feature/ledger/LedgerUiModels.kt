package io.github.kvtttba.droplet.feature.ledger

import io.github.kvtttba.droplet.core.database.dao.TagFilterMode
import io.github.kvtttba.droplet.core.database.repository.LedgerTransactionRecord
import io.github.kvtttba.droplet.core.database.repository.TransactionTemplateItem
import io.github.kvtttba.droplet.core.domain.automation.BudgetProgress
import io.github.kvtttba.droplet.core.domain.debt.ExpenseRefundSummary
import io.github.kvtttba.droplet.core.domain.debt.ReimbursementItemSummary
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.ReimburseState
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class LedgerFilterState(
    val keyword: String = "",
    val selectedTagIds: Set<String> = emptySet(),
    val tagFilterMode: TagFilterMode = TagFilterMode.ANY,
    val selectedAccountId: String? = null,
    val selectedCategoryId: String? = null,
    val pendingReimburseOnly: Boolean = false,
    val includeVoid: Boolean = false,
) {
    val activeFilterCount: Int
        get() = (if (keyword.isNotBlank()) 1 else 0) +
            selectedTagIds.size +
            (if (selectedAccountId != null) 1 else 0) +
            (if (selectedCategoryId != null) 1 else 0) +
            (if (pendingReimburseOnly) 1 else 0) +
            (if (includeVoid) 1 else 0)

    val hasExtraFilters: Boolean
        get() = activeFilterCount > 0
}

data class CategoryGroupOption(
    val parent: Account,
    val children: List<Account>,
)

data class LedgerPostingDetail(
    val id: String,
    val accountId: String,
    val accountName: String,
    val accountType: AccountType?,
    val amount: Money,
    val baseAmount: Money,
    val rateDecimal: String,
    val isSystemBalancing: Boolean,
)

enum class LedgerAmountTone {
    EXPENSE,
    INCOME,
    NEUTRAL,
    MUTED,
}

data class LedgerTransactionItem(
    val id: String,
    val occurredAt: Long,
    val timeLabel: String,
    val dateTimeLabel: String,
    val displayType: TransactionDisplayType,
    val status: TransactionStatus,
    val title: String,
    val subtitle: String,
    val accountLabel: String,
    val payee: String?,
    val note: String?,
    val iconKey: String,
    val colorHex: String?,
    val primaryAmount: Money,
    val secondaryBaseAmount: Money? = null,
    val crossCurrencyTargetAmount: Money? = null,
    val amountTone: LedgerAmountTone,
    val showPositiveSign: Boolean,
    val statusBadge: String? = null,
    val isReimburseBadge: Boolean = false,
    val tags: List<Tag> = emptyList(),
    val postings: List<LedgerPostingDetail> = emptyList(),
    val isMultiCurrency: Boolean = false,
    val isOpeningBalance: Boolean = false,
    val refTransactionId: String? = null,
    val reimburseState: ReimburseState? = null,
    val refundSummary: ExpenseRefundSummary? = null,
    val reimbursementSummary: ReimbursementItemSummary? = null,
)

data class LedgerDayGroup(
    val date: LocalDate,
    val dateHeader: String,
    val dayExpenseBase: Money,
    val dayIncomeBase: Money,
    val items: List<LedgerTransactionItem>,
)

data class LedgerUiState(
    val selectedMonth: YearMonth = YearMonth.now(),
    val baseCurrency: Currency = Currency.DEFAULT,
    val monthlyBalance: Money = Money.zero(Currency.DEFAULT),
    val monthlyIncome: Money = Money.zero(Currency.DEFAULT),
    val monthlyExpense: Money = Money.zero(Currency.DEFAULT),
    val monthlyOverallBudget: BudgetProgress? = null,
    val quickTemplates: List<TransactionTemplateItem> = emptyList(),
    val transactionCount: Int = 0,
    val filterState: LedgerFilterState = LedgerFilterState(),
    val dayGroups: List<LedgerDayGroup> = emptyList(),
    val availableAccounts: List<Account> = emptyList(),
    val refundEligibleAccounts: List<Account> = emptyList(),
    val accountsById: Map<String, Account> = emptyMap(),
    val expenseCategoryGroups: List<CategoryGroupOption> = emptyList(),
    val incomeCategoryGroups: List<CategoryGroupOption> = emptyList(),
    val availableTags: List<Tag> = emptyList(),
    val isLoading: Boolean = false,
    val isSubmittingRefund: Boolean = false,
    val userMessage: String? = null,
) {
    val monthLabel: String
        get() = LedgerDateFormats.formatYearMonth(selectedMonth)
}

object LedgerDateFormats {

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

    private val dateOnlyFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)

    fun formatYearMonth(month: YearMonth): String =
        "${month.year}年${month.monthValue}月"

    fun formatDayHeader(date: LocalDate): String =
        "${date.monthValue}月${date.dayOfMonth}日 · ${weekdayLabel(date.dayOfWeek)}"

    fun formatTime(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zoneId).format(timeFormatter)

    fun formatDateTime(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zoneId).format(dateTimeFormatter)

    fun formatDate(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zoneId).format(dateOnlyFormatter)

    fun monthRangeEpochMs(
        month: YearMonth,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Pair<Long, Long> {
        val start = month.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return start to end
    }

    private fun weekdayLabel(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }
}

object LedgerItemMapper {

    fun buildCategoryGroups(categories: List<Account>): List<CategoryGroupOption> {
        val roots = categories
            .filter { it.parentId == null }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
        val childrenByParent = categories
            .filter { it.parentId != null }
            .groupBy { it.parentId!! }
        return roots.map { root ->
            CategoryGroupOption(
                parent = root,
                children = childrenByParent[root.id]
                    .orEmpty()
                    .sortedWith(compareBy({ it.sortOrder }, { it.name })),
            )
        }
    }

    fun groupByDay(
        records: List<LedgerTransactionRecord>,
        accountsById: Map<String, Account>,
        baseCurrency: Currency = Currency.DEFAULT,
        zoneId: ZoneId = ZoneId.systemDefault(),
        refundSummariesByExpenseId: Map<String, ExpenseRefundSummary> = emptyMap(),
        reimbursementSummariesByAdvanceId: Map<String, ReimbursementItemSummary> = emptyMap(),
    ): List<LedgerDayGroup> {
        if (records.isEmpty()) return emptyList()

        val grouped = records.groupBy { record ->
            Instant.ofEpochMilli(record.transaction.occurredAt)
                .atZone(zoneId)
                .toLocalDate()
        }

        return grouped.entries
            .sortedByDescending { it.key }
            .map { (date, dayRecords) ->
                var dayExpenseMinor = 0L
                var dayIncomeMinor = 0L
                val items = dayRecords.map { record ->
                    if (record.transaction.status == TransactionStatus.POSTED) {
                        for (posting in record.transaction.businessPostings) {
                            if (posting.baseCurrency != baseCurrency) continue
                            val accountType = accountsById[posting.accountId]?.type
                            when (accountType) {
                                AccountType.EXPENSE -> {
                                    dayExpenseMinor = Math.addExact(dayExpenseMinor, posting.baseAmountMinor)
                                }
                                AccountType.INCOME -> {
                                    dayIncomeMinor = Math.addExact(dayIncomeMinor, -posting.baseAmountMinor)
                                }
                                else -> Unit
                            }
                        }
                    }
                    toItem(
                        record = record,
                        accountsById = accountsById,
                        baseCurrency = baseCurrency,
                        zoneId = zoneId,
                        refundSummary = refundSummariesByExpenseId[record.transaction.id],
                        reimbursementSummary = reimbursementSummariesByAdvanceId[record.transaction.id],
                    )
                }

                LedgerDayGroup(
                    date = date,
                    dateHeader = LedgerDateFormats.formatDayHeader(date),
                    dayExpenseBase = Money(dayExpenseMinor, baseCurrency),
                    dayIncomeBase = Money(dayIncomeMinor, baseCurrency),
                    items = items,
                )
            }
    }

    fun toItem(
        record: LedgerTransactionRecord,
        accountsById: Map<String, Account>,
        baseCurrency: Currency = Currency.DEFAULT,
        zoneId: ZoneId = ZoneId.systemDefault(),
        refundSummary: ExpenseRefundSummary? = null,
        reimbursementSummary: ReimbursementItemSummary? = null,
    ): LedgerTransactionItem {
        val tx = record.transaction
        val business = tx.businessPostings
        val isVoid = tx.status == TransactionStatus.VOID
        val isOpening = business.any { SystemAccounts.isOpeningBalanceAccountId(it.accountId) }

        val postingDetails = tx.postings.map { posting ->
            val account = accountsById[posting.accountId]
            val fullName = account?.let { formatAccountDisplayName(it, accountsById) }
                ?: posting.accountId
            LedgerPostingDetail(
                id = posting.id,
                accountId = posting.accountId,
                accountName = fullName,
                accountType = account?.type,
                amount = posting.money,
                baseAmount = posting.baseMoney,
                rateDecimal = posting.exchangeRateSnapshot.rateDecimal,
                isSystemBalancing = posting.isSystemBalancing,
            )
        }

        val expensePostings = business.filter { accountsById[it.accountId]?.type == AccountType.EXPENSE }
        val incomePostings = business.filter { accountsById[it.accountId]?.type == AccountType.INCOME }
        val balanceSheetPostings = business.filter {
            val type = accountsById[it.accountId]?.type
            type == AccountType.ASSET || type == AccountType.LIABILITY
        }

        val effectiveReimburseState = reimbursementSummary?.derivedState ?: tx.reimburseState
        val isReimburseSettlement = tx.displayType == TransactionDisplayType.REIMBURSE && tx.refTransactionId != null

        val statusBadge = when {
            isVoid -> "已作废"
            isReimburseSettlement -> "报销到账"
            effectiveReimburseState == ReimburseState.PENDING -> "待报销"
            effectiveReimburseState == ReimburseState.PARTIAL -> "部分报销"
            effectiveReimburseState == ReimburseState.SETTLED -> "已报销"
            tx.displayType == TransactionDisplayType.EXPENSE && refundSummary?.isFullyRefunded == true -> "已全额退款"
            tx.displayType == TransactionDisplayType.EXPENSE && refundSummary?.hasRefunds == true ->
                if (refundSummary.refundCount > 1) "已退 ${refundSummary.refundCount} 笔" else "部分退款"
            tx.displayType == TransactionDisplayType.REFUND -> "退款"
            tx.displayType == TransactionDisplayType.DEBT -> "借贷"
            isOpening -> "期初"
            tx.displayType == TransactionDisplayType.SPLIT -> "多拆"
            else -> null
        }

        val isReimburseBadge = !isVoid && (effectiveReimburseState != null || isReimburseSettlement)

        val primaryAccount: Account?
        val title: String
        val accountLabel: String
        val iconKey: String
        val colorHex: String?
        val primaryAmount: Money
        val secondaryBase: Money?
        val crossTarget: Money?
        val tone: LedgerAmountTone
        val showPositiveSign: Boolean

        when {
            isOpening -> {
                val targetPosting = balanceSheetPostings.firstOrNull() ?: business.first()
                primaryAccount = accountsById[targetPosting.accountId]
                title = "期初余额 · ${primaryAccount?.name ?: "账户"}"
                accountLabel = primaryAccount?.name ?: "期初权益"
                iconKey = primaryAccount?.icon ?: "account_balance_wallet"
                colorHex = primaryAccount?.color
                primaryAmount = targetPosting.money
                secondaryBase = if (targetPosting.currency != baseCurrency) targetPosting.baseMoney else null
                crossTarget = null
                tone = if (isVoid) LedgerAmountTone.MUTED else LedgerAmountTone.NEUTRAL
                showPositiveSign = primaryAmount.isPositive
            }

            tx.displayType == TransactionDisplayType.EXPENSE -> {
                val mainExpense = expensePostings.maxByOrNull { it.amountMinor } ?: business.first()
                val mainCategory = accountsById[mainExpense.accountId]
                val paymentPosting = balanceSheetPostings.minByOrNull { it.amountMinor }
                val paymentAccount = paymentPosting?.let { accountsById[it.accountId] }
                primaryAccount = mainCategory
                val categoryName = mainCategory?.let { formatCategoryShortName(it, accountsById) } ?: "支出"
                title = buildTitle(categoryName, tx.payee)
                accountLabel = paymentAccount?.name ?: "多账户"
                iconKey = mainCategory?.icon ?: "restaurant"
                colorHex = mainCategory?.color
                val displayMoney = if (
                    paymentPosting != null &&
                    paymentPosting.currency == mainExpense.currency &&
                    expensePostings.size == 1 &&
                    incomePostings.isEmpty()
                ) {
                    Money(paymentPosting.amountMinor, paymentPosting.currency)
                } else {
                    Money(-mainExpense.amountMinor, mainExpense.currency)
                }
                primaryAmount = displayMoney
                secondaryBase = if (mainExpense.currency != baseCurrency) {
                    Money(-mainExpense.baseAmountMinor, baseCurrency)
                } else {
                    null
                }
                crossTarget = if (paymentPosting != null && paymentPosting.currency != mainExpense.currency) {
                    paymentPosting.money
                } else {
                    null
                }
                tone = if (isVoid) LedgerAmountTone.MUTED else LedgerAmountTone.EXPENSE
                showPositiveSign = false
            }

            tx.displayType == TransactionDisplayType.INCOME -> {
                val mainIncome = incomePostings.minByOrNull { it.amountMinor } ?: business.first()
                val mainCategory = accountsById[mainIncome.accountId]
                val depositPosting = balanceSheetPostings.maxByOrNull { it.amountMinor }
                val depositAccount = depositPosting?.let { accountsById[it.accountId] }
                primaryAccount = mainCategory
                val categoryName = mainCategory?.let { formatCategoryShortName(it, accountsById) } ?: "收入"
                title = buildTitle(categoryName, tx.payee)
                accountLabel = depositAccount?.name ?: "多账户"
                iconKey = mainCategory?.icon ?: "trending_up"
                colorHex = mainCategory?.color
                val grossIncomeMinor = -mainIncome.amountMinor
                primaryAmount = Money(grossIncomeMinor, mainIncome.currency)
                secondaryBase = if (mainIncome.currency != baseCurrency) {
                    Money(-mainIncome.baseAmountMinor, baseCurrency)
                } else {
                    null
                }
                crossTarget = if (depositPosting != null && depositPosting.currency != mainIncome.currency) {
                    depositPosting.money
                } else {
                    null
                }
                tone = if (isVoid) LedgerAmountTone.MUTED else LedgerAmountTone.INCOME
                showPositiveSign = true
            }

            tx.displayType == TransactionDisplayType.TRANSFER -> {
                val outPosting = balanceSheetPostings.minByOrNull { it.amountMinor } ?: business.first()
                val inPosting = balanceSheetPostings.maxByOrNull { it.amountMinor } ?: business.last()
                val fromAccount = accountsById[outPosting.accountId]
                val toAccount = accountsById[inPosting.accountId]
                primaryAccount = fromAccount
                val actionLabel = if (toAccount?.subType == AccountSubType.CREDIT_CARD) "还款" else "转账"
                val routeText = "${fromAccount?.name ?: "转出"} → ${toAccount?.name ?: "转入"}"
                title = if (!tx.payee.isNullOrBlank()) "$actionLabel · ${tx.payee}" else "$actionLabel · $routeText"
                accountLabel = routeText
                iconKey = "swap_horiz"
                colorHex = "#006970"
                primaryAmount = outPosting.money.abs()
                secondaryBase = if (outPosting.currency != baseCurrency && inPosting.currency == outPosting.currency) {
                    outPosting.baseMoney.abs()
                } else {
                    null
                }
                crossTarget = if (outPosting.currency != inPosting.currency) {
                    inPosting.money
                } else {
                    null
                }
                tone = if (isVoid) LedgerAmountTone.MUTED else LedgerAmountTone.NEUTRAL
                showPositiveSign = false
            }

            tx.displayType == TransactionDisplayType.REIMBURSE -> {
                val isAdvance = tx.refTransactionId == null
                val paymentPosting = balanceSheetPostings
                    .filter { accountsById[it.accountId]?.subType != AccountSubType.REIMBURSEMENT && it.amountMinor < 0L }
                    .minByOrNull { it.amountMinor }
                    ?: balanceSheetPostings.minByOrNull { it.amountMinor }
                val depositPosting = balanceSheetPostings
                    .filter { accountsById[it.accountId]?.subType != AccountSubType.REIMBURSEMENT && it.amountMinor > 0L }
                    .maxByOrNull { it.amountMinor }
                val focusPosting = if (isAdvance) {
                    paymentPosting ?: business.first()
                } else {
                    depositPosting ?: expensePostings.maxByOrNull { it.amountMinor } ?: business.first()
                }
                val account = accountsById[focusPosting.accountId]
                primaryAccount = account
                title = tx.payee?.takeIf { it.isNotBlank() }
                    ?: if (isAdvance) "待报销垫付" else "报销到账"
                accountLabel = account?.name ?: "待报销"
                iconKey = "receipt_long"
                colorHex = "#FB8C00"
                primaryAmount = if (!isAdvance && depositPosting == null && expensePostings.isNotEmpty()) {
                    Money(-focusPosting.amountMinor, focusPosting.currency)
                } else {
                    focusPosting.money
                }
                secondaryBase = if (focusPosting.currency != baseCurrency) focusPosting.baseMoney else null
                crossTarget = null
                tone = when {
                    isVoid -> LedgerAmountTone.MUTED
                    primaryAmount.isPositive -> LedgerAmountTone.INCOME
                    else -> LedgerAmountTone.EXPENSE
                }
                showPositiveSign = primaryAmount.isPositive
            }

            tx.displayType == TransactionDisplayType.DEBT -> {
                val cashPosting = balanceSheetPostings.firstOrNull { p ->
                    val st = accountsById[p.accountId]?.subType
                    st != AccountSubType.RECEIVABLE && st != AccountSubType.PAYABLE
                }
                val focusPosting = cashPosting
                    ?: expensePostings.maxByOrNull { it.amountMinor }
                    ?: balanceSheetPostings.firstOrNull()
                    ?: business.first()
                val account = accountsById[focusPosting.accountId]
                primaryAccount = account
                title = tx.payee?.takeIf { it.isNotBlank() } ?: "往来借贷"
                accountLabel = account?.name ?: "往来账户"
                iconKey = "card_giftcard"
                colorHex = "#00897B"
                val displayMoney = if (cashPosting == null && expensePostings.isNotEmpty()) {
                    Money(-focusPosting.amountMinor, focusPosting.currency)
                } else {
                    focusPosting.money
                }
                primaryAmount = displayMoney
                secondaryBase = if (focusPosting.currency != baseCurrency) focusPosting.baseMoney else null
                crossTarget = null
                tone = when {
                    isVoid -> LedgerAmountTone.MUTED
                    displayMoney.isPositive -> LedgerAmountTone.INCOME
                    displayMoney.isNegative -> LedgerAmountTone.EXPENSE
                    else -> LedgerAmountTone.NEUTRAL
                }
                showPositiveSign = displayMoney.isPositive
            }

            tx.displayType == TransactionDisplayType.REFUND -> {
                val expensePosting = expensePostings.firstOrNull()
                val refundToPosting = balanceSheetPostings.maxByOrNull { it.amountMinor } ?: business.first()
                val category = expensePosting?.let { accountsById[it.accountId] }
                val account = accountsById[refundToPosting.accountId]
                primaryAccount = category
                val categoryName = category?.let { formatCategoryShortName(it, accountsById) } ?: "退款"
                title = buildTitle("退款 · $categoryName", tx.payee)
                accountLabel = account?.name ?: "原路退回"
                iconKey = category?.icon ?: "receipt_long"
                colorHex = category?.color
                primaryAmount = refundToPosting.money
                secondaryBase = if (refundToPosting.currency != baseCurrency) refundToPosting.baseMoney else null
                crossTarget = null
                tone = if (isVoid) LedgerAmountTone.MUTED else LedgerAmountTone.INCOME
                showPositiveSign = true
            }

            else -> {
                // SPLIT / DEBT / LOAN_REPAY / INVEST
                val netBase = computeSplitDisplayMoney(business, accountsById, baseCurrency)
                primaryAccount = business.firstOrNull()?.let { accountsById[it.accountId] }
                title = tx.payee?.takeIf { it.isNotBlank() }
                    ?: tx.note?.takeIf { it.isNotBlank() }
                    ?: "多拆分录 (${business.size}行)"
                accountLabel = business
                    .mapNotNull { accountsById[it.accountId]?.name }
                    .distinct()
                    .take(2)
                    .joinToString(" · ")
                    .ifEmpty { "复式分录" }
                iconKey = "call_split"
                colorHex = "#4E5E7E"
                primaryAmount = netBase
                secondaryBase = null
                crossTarget = null
                tone = when {
                    isVoid -> LedgerAmountTone.MUTED
                    netBase.isPositive -> LedgerAmountTone.INCOME
                    netBase.isNegative -> LedgerAmountTone.EXPENSE
                    else -> LedgerAmountTone.NEUTRAL
                }
                showPositiveSign = netBase.isPositive
            }
        }

        val subtitleParts = buildList {
            add(accountLabel)
            tx.note?.trim()?.takeIf { it.isNotEmpty() && it != title }?.let { add(it) }
        }

        return LedgerTransactionItem(
            id = tx.id,
            occurredAt = tx.occurredAt,
            timeLabel = LedgerDateFormats.formatTime(tx.occurredAt, zoneId),
            dateTimeLabel = LedgerDateFormats.formatDateTime(tx.occurredAt, zoneId),
            displayType = tx.displayType,
            status = tx.status,
            title = title,
            subtitle = subtitleParts.joinToString(" · "),
            accountLabel = accountLabel,
            payee = tx.payee,
            note = tx.note,
            iconKey = iconKey,
            colorHex = colorHex,
            primaryAmount = primaryAmount,
            secondaryBaseAmount = secondaryBase,
            crossCurrencyTargetAmount = crossTarget,
            amountTone = tone,
            showPositiveSign = showPositiveSign,
            statusBadge = statusBadge,
            isReimburseBadge = isReimburseBadge,
            tags = record.tags,
            postings = postingDetails,
            isMultiCurrency = tx.isMultiCurrency,
            isOpeningBalance = isOpening,
            refTransactionId = tx.refTransactionId,
            reimburseState = effectiveReimburseState,
            refundSummary = refundSummary,
            reimbursementSummary = reimbursementSummary,
        )
    }

    private fun buildTitle(primary: String, payee: String?): String {
        val cleanPayee = payee?.trim().orEmpty()
        return if (cleanPayee.isNotEmpty() && cleanPayee != primary) {
            "$primary · $cleanPayee"
        } else {
            primary
        }
    }

    private fun formatCategoryShortName(
        account: Account,
        accountsById: Map<String, Account>,
    ): String {
        val parent = account.parentId?.let { accountsById[it] }
        return if (parent != null) "${parent.name}·${account.name}" else account.name
    }

    private fun formatAccountDisplayName(
        account: Account,
        accountsById: Map<String, Account>,
    ): String {
        val parent = account.parentId?.let { accountsById[it] }
        return if (parent != null) "${parent.name} / ${account.name}" else account.name
    }

    private fun computeSplitDisplayMoney(
        businessPostings: List<io.github.kvtttba.droplet.core.model.Posting>,
        accountsById: Map<String, Account>,
        baseCurrency: Currency,
    ): Money {
        val sameCurrency = businessPostings.map { it.currency }.distinct().singleOrNull()
        var incomeMinor = 0L
        var expenseMinor = 0L
        for (posting in businessPostings) {
            val type = accountsById[posting.accountId]?.type
            val value = if (sameCurrency != null) posting.amountMinor else posting.baseAmountMinor
            when (type) {
                AccountType.INCOME -> incomeMinor = Math.addExact(incomeMinor, -value)
                AccountType.EXPENSE -> expenseMinor = Math.addExact(expenseMinor, value)
                else -> Unit
            }
        }
        val targetCurrency = sameCurrency ?: baseCurrency
        val netIncomeStatement = Math.subtractExact(incomeMinor, expenseMinor)
        if (netIncomeStatement != 0L) {
            return Money(netIncomeStatement, targetCurrency)
        }
        val maxAbs = businessPostings.maxOfOrNull {
            val v = if (sameCurrency != null) it.amountMinor else it.baseAmountMinor
            kotlin.math.abs(v)
        } ?: 0L
        return Money(maxAbs, targetCurrency)
    }
}
