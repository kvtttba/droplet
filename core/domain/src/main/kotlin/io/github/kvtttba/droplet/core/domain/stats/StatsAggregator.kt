package io.github.kvtttba.droplet.core.domain.stats

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Posting
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
import java.time.temporal.ChronoUnit

/**
 * Pure JVM zero-floating-point statistics aggregation engine producing:
 * 1. Income/Expense trend buckets (daily for months or <=45d ranges, monthly for years or >45d).
 * 2. Expense & Income primary-category rankings with subcategory breakdowns & drill-down records.
 * 3. Deterministic historical Net Worth / Total Assets / Total Liabilities timeline respecting
 *    [Account.includeInNetWorth], credit overpayments, and multi-currency conversion.
 * 4. Monthly Income/Expense calendar matrix with per-day transaction drill-down lists.
 *
 * Accounting invariants:
 * - Strictly excludes `VOID` and `DRAFT` transactions.
 * - Strictly excludes `REIMBURSE` advances (`refTransactionId == null`); reimbursed personal
 *   shortfalls posted to `EXPENSE` or over-reimbursed income count normally.
 * - Linked `REFUND` transactions automatically offset their original expense categories and inherit
 *   parent tags when un-tagged so tag-filtered net expense stays algebraically accurate.
 * - Historical income/expense totals in `BASE_CNY` mode strictly use transaction-time locked
 *   [Posting.baseAmountMinor] so historical reports never drift with later exchange-rate updates.
 */
object StatsAggregator {

    val DEFAULT_ZONE_ID: ZoneId = ZoneId.systemDefault()
    const val DAILY_BUCKET_MAX_DAYS: Int = 45

    private val HUNDRED = BigDecimal.valueOf(100L)

    fun resolveDateRange(
        query: StatsQuery,
        zoneId: ZoneId = DEFAULT_ZONE_ID,
    ): StatsDateRange {
        val (startDate, endDate) = when (query.periodType) {
            StatsPeriodType.MONTHLY -> query.targetMonth.atDay(1) to query.targetMonth.atEndOfMonth()
            StatsPeriodType.YEARLY -> LocalDate.of(query.targetYear, 1, 1) to LocalDate.of(query.targetYear, 12, 31)
            StatsPeriodType.CUSTOM_RANGE -> {
                if (query.customEndDate.isBefore(query.customStartDate)) {
                    query.customEndDate to query.customStartDate
                } else {
                    query.customStartDate to query.customEndDate
                }
            }
        }
        val startEpochMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endEpochMsExclusive = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayCount = (ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1).coerceAtLeast(1)
        return StatsDateRange(
            startDate = startDate,
            endDate = endDate,
            startEpochMs = startEpochMs,
            endEpochMsExclusive = endEpochMsExclusive,
            dayCount = dayCount,
        )
    }

    fun resolveGranularity(
        query: StatsQuery,
        dateRange: StatsDateRange,
    ): StatsTrendGranularity = when (query.periodType) {
        StatsPeriodType.MONTHLY -> StatsTrendGranularity.DAILY
        StatsPeriodType.YEARLY -> StatsTrendGranularity.MONTHLY
        StatsPeriodType.CUSTOM_RANGE -> if (dateRange.dayCount <= DAILY_BUCKET_MAX_DAYS) {
            StatsTrendGranularity.DAILY
        } else {
            StatsTrendGranularity.MONTHLY
        }
    }

    fun aggregate(
        query: StatsQuery,
        allTransactions: List<Transaction>,
        accountsById: Map<String, Account>,
        tagsById: Map<String, Tag> = emptyMap(),
        exchangeRateProvider: ExchangeRateProvider = ExchangeRateTable.empty(),
        zoneId: ZoneId = DEFAULT_ZONE_ID,
        latestInvestmentAdjustmentMinorByAccountId: Map<String, Long> = emptyMap(),
    ): StatsReportSnapshot {
        val dateRange = resolveDateRange(query, zoneId)
        val granularity = resolveGranularity(query, dateRange)
        val displayCurrency = query.currencyFilter.displayCurrency

        val postedTransactions = allTransactions.filter { it.status == TransactionStatus.POSTED }
        val postedById = postedTransactions.associateBy { it.id }

        val periodEligibleTxs = postedTransactions.filter { tx ->
            tx.occurredAt >= dateRange.startEpochMs &&
                tx.occurredAt < dateRange.endEpochMsExclusive &&
                !isReimburseAdvance(tx) &&
                matchesTagFilter(tx, postedById, query.selectedTagIds, query.tagFilterMode)
        }

        // 1. Trend buckets & headline totals
        val bucketSpecs = buildBucketSpecs(dateRange, granularity)
        val bucketAccumulators = bucketSpecs.associate { it.key to TrendAccumulator(it) }

        var totalIncomeMinor = 0L
        var totalExpenseMinor = 0L
        val countedPeriodTxIds = hashSetOf<String>()

        // 2. Category ranking accumulators
        val expenseRoots = linkedMapOf<String, RootCategoryAccumulator>()
        val incomeRoots = linkedMapOf<String, RootCategoryAccumulator>()

        for (tx in periodEligibleTxs) {
            val txDate = Instant.ofEpochMilli(tx.occurredAt).atZone(zoneId).toLocalDate()
            val bucketKey = bucketKeyFor(txDate, granularity)
            val bucketAcc = bucketAccumulators[bucketKey]
            var txHasRelevantFlow = false

            // Per-transaction aggregation by category for drill-down records
            val txExpenseByRoot = linkedMapOf<String, Long>()
            val txExpenseSubByRoot = linkedMapOf<String, String>()
            val txIncomeByRoot = linkedMapOf<String, Long>()
            val txIncomeSubByRoot = linkedMapOf<String, String>()

            for (posting in tx.postings) {
                if (posting.isSystemBalancing) continue
                val account = accountsById[posting.accountId] ?: continue
                if (account.type != AccountType.EXPENSE && account.type != AccountType.INCOME) continue

                val resolvedMinor = resolveFlowPostingMinor(
                    posting = posting,
                    currencyFilter = query.currencyFilter,
                    exchangeRateProvider = exchangeRateProvider,
                ) ?: continue

                txHasRelevantFlow = true
                val (rootAccount, leafAccount) = resolveRootAndLeafCategory(account, accountsById)

                when (account.type) {
                    AccountType.EXPENSE -> {
                        totalExpenseMinor = Math.addExact(totalExpenseMinor, resolvedMinor)
                        if (bucketAcc != null) {
                            bucketAcc.expenseMinor = Math.addExact(bucketAcc.expenseMinor, resolvedMinor)
                        }
                        val rootAcc = expenseRoots.getOrPut(rootAccount.id) {
                            RootCategoryAccumulator(rootAccount)
                        }
                        rootAcc.amountMinor = Math.addExact(rootAcc.amountMinor, resolvedMinor)
                        rootAcc.transactionIds += tx.id

                        val subAcc = rootAcc.subCategories.getOrPut(leafAccount.id) {
                            SubCategoryAccumulator(leafAccount)
                        }
                        subAcc.amountMinor = Math.addExact(subAcc.amountMinor, resolvedMinor)
                        subAcc.transactionIds += tx.id

                        txExpenseByRoot[rootAccount.id] = Math.addExact(
                            txExpenseByRoot[rootAccount.id] ?: 0L,
                            resolvedMinor,
                        )
                        txExpenseSubByRoot.putIfAbsent(rootAccount.id, leafAccount.id)
                    }

                    AccountType.INCOME -> {
                        // Income postings carry negative signed amounts in double-entry; negate to positive income.
                        val incomeDelta = Math.negateExact(resolvedMinor)
                        totalIncomeMinor = Math.addExact(totalIncomeMinor, incomeDelta)
                        if (bucketAcc != null) {
                            bucketAcc.incomeMinor = Math.addExact(bucketAcc.incomeMinor, incomeDelta)
                        }
                        val rootAcc = incomeRoots.getOrPut(rootAccount.id) {
                            RootCategoryAccumulator(rootAccount)
                        }
                        rootAcc.amountMinor = Math.addExact(rootAcc.amountMinor, incomeDelta)
                        rootAcc.transactionIds += tx.id

                        val subAcc = rootAcc.subCategories.getOrPut(leafAccount.id) {
                            SubCategoryAccumulator(leafAccount)
                        }
                        subAcc.amountMinor = Math.addExact(subAcc.amountMinor, incomeDelta)
                        subAcc.transactionIds += tx.id

                        txIncomeByRoot[rootAccount.id] = Math.addExact(
                            txIncomeByRoot[rootAccount.id] ?: 0L,
                            incomeDelta,
                        )
                        txIncomeSubByRoot.putIfAbsent(rootAccount.id, leafAccount.id)
                    }

                    else -> Unit
                }
            }

            if (txHasRelevantFlow) {
                countedPeriodTxIds += tx.id
                bucketAcc?.transactionIds?.add(tx.id)

                for ((rootId, deltaMinor) in txExpenseByRoot) {
                    val leafId = txExpenseSubByRoot[rootId] ?: rootId
                    expenseRoots[rootId]?.drilldowns?.add(
                        buildDrilldownRecord(
                            tx = tx,
                            date = txDate,
                            categoryAccount = accountsById[leafId] ?: accountsById[rootId],
                            accountsById = accountsById,
                            tagsById = tagsById,
                            signedFlowMinor = Math.negateExact(deltaMinor),
                            displayCurrency = displayCurrency,
                            isExpense = true,
                        ),
                    )
                }
                for ((rootId, deltaMinor) in txIncomeByRoot) {
                    val leafId = txIncomeSubByRoot[rootId] ?: rootId
                    incomeRoots[rootId]?.drilldowns?.add(
                        buildDrilldownRecord(
                            tx = tx,
                            date = txDate,
                            categoryAccount = accountsById[leafId] ?: accountsById[rootId],
                            accountsById = accountsById,
                            tagsById = tagsById,
                            signedFlowMinor = deltaMinor,
                            displayCurrency = displayCurrency,
                            isExpense = false,
                        ),
                    )
                }
            }
        }

        val trendBuckets = bucketSpecs.map { spec ->
            val acc = bucketAccumulators.getValue(spec.key)
            val incomeMoney = Money(acc.incomeMinor, displayCurrency)
            val expenseMoney = Money(acc.expenseMinor, displayCurrency)
            TrendBucket(
                key = spec.key,
                label = spec.label,
                startDate = spec.startDate,
                endDate = spec.endDate,
                income = incomeMoney,
                expense = expenseMoney,
                netBalance = incomeMoney - expenseMoney,
                transactionCount = acc.transactionIds.size,
            )
        }

        val expenseRankings = finalizeRankings(expenseRoots.values, displayCurrency)
        val incomeRankings = finalizeRankings(incomeRoots.values, displayCurrency)

        // 3. Deterministic historical Net Worth / Assets / Liabilities timeline
        val netWorthTrend = buildNetWorthTrend(
            bucketSpecs = bucketSpecs,
            postedTransactions = postedTransactions,
            accountsById = accountsById,
            currencyFilter = query.currencyFilter,
            exchangeRateProvider = exchangeRateProvider,
            zoneId = zoneId,
            latestInvestmentAdjustmentMinorByAccountId = latestInvestmentAdjustmentMinorByAccountId,
        )

        // 4. Calendar month matrix (supports independent calendarMonth with same tag & currency filters)
        val calendarMonth = buildCalendarMonth(
            month = query.calendarMonth,
            postedTransactions = postedTransactions,
            postedById = postedById,
            accountsById = accountsById,
            tagsById = tagsById,
            selectedTagIds = query.selectedTagIds,
            tagFilterMode = query.tagFilterMode,
            currencyFilter = query.currencyFilter,
            exchangeRateProvider = exchangeRateProvider,
            zoneId = zoneId,
        )

        val totalIncome = Money(totalIncomeMinor, displayCurrency)
        val totalExpense = Money(totalExpenseMinor, displayCurrency)
        val netBalance = totalIncome - totalExpense
        val dailyAvgMinor = if (totalExpenseMinor > 0L) {
            BigDecimal.valueOf(totalExpenseMinor)
                .divide(BigDecimal.valueOf(dateRange.dayCount.toLong()), 0, RoundingMode.HALF_UP)
                .toLong()
        } else {
            0L
        }

        return StatsReportSnapshot(
            query = query,
            dateRange = dateRange,
            displayCurrency = displayCurrency,
            granularity = granularity,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            netBalance = netBalance,
            dailyAverageExpense = Money(dailyAvgMinor, displayCurrency),
            transactionCount = countedPeriodTxIds.size,
            trendBuckets = trendBuckets,
            expenseRankings = expenseRankings,
            incomeRankings = incomeRankings,
            netWorthTrend = netWorthTrend,
            calendarMonth = calendarMonth,
        )
    }

    private fun buildNetWorthTrend(
        bucketSpecs: List<BucketSpec>,
        postedTransactions: List<Transaction>,
        accountsById: Map<String, Account>,
        currencyFilter: StatsCurrencyFilter,
        exchangeRateProvider: ExchangeRateProvider,
        zoneId: ZoneId,
        latestInvestmentAdjustmentMinorByAccountId: Map<String, Long>,
    ): List<NetWorthPoint> {
        if (bucketSpecs.isEmpty()) return emptyList()
        val displayCurrency = currencyFilter.displayCurrency

        val chronologicalTxs = postedTransactions.sortedWith(
            compareBy<Transaction>({ it.occurredAt }, { it.createdAt }, { it.id }),
        )

        // Per account: original balances per currency + historical locked base fallback
        val balancesByAccount = hashMapOf<String, HashMap<Currency, Long>>()
        val lockedBaseByAccount = hashMapOf<String, Long>()
        var txIndex = 0
        val points = ArrayList<NetWorthPoint>(bucketSpecs.size)

        bucketSpecs.forEachIndexed { idx, spec ->
            val nodeEndExclusive = spec.endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            while (txIndex < chronologicalTxs.size && chronologicalTxs[txIndex].occurredAt < nodeEndExclusive) {
                val tx = chronologicalTxs[txIndex]
                for (posting in tx.postings) {
                    if (posting.isSystemBalancing) continue
                    val account = accountsById[posting.accountId] ?: continue
                    if (account.type != AccountType.ASSET && account.type != AccountType.LIABILITY) continue
                    if (!account.includeInNetWorth) continue

                    val perCurrency = balancesByAccount.getOrPut(account.id) { hashMapOf() }
                    perCurrency[posting.currency] = Math.addExact(
                        perCurrency[posting.currency] ?: 0L,
                        posting.amountMinor,
                    )
                    if (posting.baseCurrency == displayCurrency) {
                        lockedBaseByAccount[account.id] = Math.addExact(
                            lockedBaseByAccount[account.id] ?: 0L,
                            posting.baseAmountMinor,
                        )
                    }
                }
                txIndex++
            }

            var assetsMinor = 0L
            var liabilitiesMinor = 0L
            val isLatestPoint = idx == bucketSpecs.lastIndex

            // Union accounts with postings and accounts carrying live investment valuation adjustments
            val candidateAccountIds = if (isLatestPoint && latestInvestmentAdjustmentMinorByAccountId.isNotEmpty()) {
                balancesByAccount.keys + latestInvestmentAdjustmentMinorByAccountId.keys
            } else {
                balancesByAccount.keys
            }

            for (accountId in candidateAccountIds) {
                val account = accountsById[accountId] ?: continue
                if (account.type != AccountType.ASSET && account.type != AccountType.LIABILITY) continue
                if (!account.includeInNetWorth) continue

                val perCurrency = balancesByAccount[accountId].orEmpty()
                var accountValMinor = if (currencyFilter.isBaseCurrencyMode) {
                    var sum = 0L
                    var conversionFailed = false
                    for ((ccy, origMinor) in perCurrency) {
                        if (origMinor == 0L) continue
                        val converted = if (ccy == displayCurrency) {
                            origMinor
                        } else {
                            exchangeRateProvider.findRate(ccy, displayCurrency)
                                ?.convert(Money(origMinor, ccy))
                                ?.amountMinor
                        }
                        if (converted != null) {
                            sum = Math.addExact(sum, converted)
                        } else {
                            conversionFailed = true
                            break
                        }
                    }
                    if (conversionFailed) {
                        lockedBaseByAccount[accountId] ?: 0L
                    } else {
                        sum
                    }
                } else {
                    perCurrency[displayCurrency] ?: 0L
                }

                if (isLatestPoint) {
                    val adjustment = latestInvestmentAdjustmentMinorByAccountId[accountId]
                    if (adjustment != null && (currencyFilter.isBaseCurrencyMode || account.currency == displayCurrency)) {
                        accountValMinor = Math.addExact(accountValMinor, adjustment)
                    }
                }

                if (accountValMinor >= 0L) {
                    assetsMinor = Math.addExact(assetsMinor, accountValMinor)
                } else {
                    liabilitiesMinor = Math.addExact(liabilitiesMinor, Math.negateExact(accountValMinor))
                }
            }

            val totalAssets = Money(assetsMinor, displayCurrency)
            val totalLiabilities = Money(liabilitiesMinor, displayCurrency)
            points += NetWorthPoint(
                key = spec.key,
                label = spec.label,
                asOfDate = spec.endDate,
                totalAssets = totalAssets,
                totalLiabilities = totalLiabilities,
                netWorth = totalAssets - totalLiabilities,
            )
        }

        return points
    }

    private fun buildCalendarMonth(
        month: YearMonth,
        postedTransactions: List<Transaction>,
        postedById: Map<String, Transaction>,
        accountsById: Map<String, Account>,
        tagsById: Map<String, Tag>,
        selectedTagIds: Set<String>,
        tagFilterMode: StatsTagFilterMode,
        currencyFilter: StatsCurrencyFilter,
        exchangeRateProvider: ExchangeRateProvider,
        zoneId: ZoneId,
    ): CalendarMonthSummary {
        val displayCurrency = currencyFilter.displayCurrency
        val firstDay = month.atDay(1)
        val daysInMonth = month.lengthOfMonth()
        val startMs = firstDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMsExclusive = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val dayAccumulators = (1..daysInMonth).associateWith { day ->
            DayAccumulator(month.atDay(day))
        }

        val monthTxs = postedTransactions.filter { tx ->
            tx.occurredAt >= startMs &&
                tx.occurredAt < endMsExclusive &&
                !isReimburseAdvance(tx) &&
                matchesTagFilter(tx, postedById, selectedTagIds, tagFilterMode)
        }

        var monthIncomeMinor = 0L
        var monthExpenseMinor = 0L

        for (tx in monthTxs) {
            val date = Instant.ofEpochMilli(tx.occurredAt).atZone(zoneId).toLocalDate()
            val dayAcc = dayAccumulators[date.dayOfMonth] ?: continue

            var txIncomeMinor = 0L
            var txExpenseMinor = 0L
            var primaryCategory: Account? = null
            var hasFlow = false

            for (posting in tx.postings) {
                if (posting.isSystemBalancing) continue
                val account = accountsById[posting.accountId] ?: continue
                if (account.type != AccountType.EXPENSE && account.type != AccountType.INCOME) continue

                val resolvedMinor = resolveFlowPostingMinor(
                    posting = posting,
                    currencyFilter = currencyFilter,
                    exchangeRateProvider = exchangeRateProvider,
                ) ?: continue

                hasFlow = true
                if (primaryCategory == null) {
                    primaryCategory = account
                }
                when (account.type) {
                    AccountType.EXPENSE -> {
                        txExpenseMinor = Math.addExact(txExpenseMinor, resolvedMinor)
                    }
                    AccountType.INCOME -> {
                        txIncomeMinor = Math.addExact(txIncomeMinor, Math.negateExact(resolvedMinor))
                    }
                    else -> Unit
                }
            }

            if (!hasFlow) continue

            dayAcc.incomeMinor = Math.addExact(dayAcc.incomeMinor, txIncomeMinor)
            dayAcc.expenseMinor = Math.addExact(dayAcc.expenseMinor, txExpenseMinor)
            monthIncomeMinor = Math.addExact(monthIncomeMinor, txIncomeMinor)
            monthExpenseMinor = Math.addExact(monthExpenseMinor, txExpenseMinor)
            dayAcc.txIds += tx.id

            val signedFlowMinor = Math.subtractExact(txIncomeMinor, txExpenseMinor)
            val isExpense = txExpenseMinor != 0L && txIncomeMinor == 0L
            dayAcc.transactions += buildDrilldownRecord(
                tx = tx,
                date = date,
                categoryAccount = primaryCategory,
                accountsById = accountsById,
                tagsById = tagsById,
                signedFlowMinor = signedFlowMinor,
                displayCurrency = displayCurrency,
                isExpense = isExpense,
            )
        }

        val cells = (1..daysInMonth).map { day ->
            val acc = dayAccumulators.getValue(day)
            val income = Money(acc.incomeMinor, displayCurrency)
            val expense = Money(acc.expenseMinor, displayCurrency)
            CalendarDayCell(
                date = acc.date,
                dayOfMonth = day,
                income = income,
                expense = expense,
                netFlow = income - expense,
                transactionCount = acc.txIds.size,
                transactions = acc.transactions.sortedWith(
                    compareByDescending<StatsDrilldownTransaction> { it.occurredAt }
                        .thenByDescending { it.transactionId },
                ),
            )
        }

        val totalIncome = Money(monthIncomeMinor, displayCurrency)
        val totalExpense = Money(monthExpenseMinor, displayCurrency)
        return CalendarMonthSummary(
            month = month,
            currency = displayCurrency,
            firstDayOfWeekIso = firstDay.dayOfWeek.value,
            daysInMonth = daysInMonth,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            netFlow = totalIncome - totalExpense,
            activeDayCount = cells.count { it.hasActivity },
            days = cells,
        )
    }

    private fun finalizeRankings(
        accumulators: Collection<RootCategoryAccumulator>,
        currency: Currency,
    ): List<CategoryRankingItem> {
        val positiveRoots = accumulators
            .filter { it.amountMinor > 0L }
            .sortedWith(
                compareByDescending<RootCategoryAccumulator> { it.amountMinor }
                    .thenBy { it.rootAccount.sortOrder }
                    .thenBy { it.rootAccount.id },
            )
        if (positiveRoots.isEmpty()) return emptyList()

        var grandTotalMinor = 0L
        for (root in positiveRoots) {
            grandTotalMinor = Math.addExact(grandTotalMinor, root.amountMinor)
        }
        val grandTotalBd = BigDecimal.valueOf(grandTotalMinor)

        return positiveRoots.map { rootAcc ->
            val rootBd = BigDecimal.valueOf(rootAcc.amountMinor)
            val sharePct = rootBd.multiply(HUNDRED)
                .divide(grandTotalBd, 1, RoundingMode.HALF_UP)
                .toPlainString()
            val shareFraction = rootBd
                .divide(grandTotalBd, 4, RoundingMode.HALF_UP)
                .toFloat()
                .coerceIn(0f, 1f)

            val subs = rootAcc.subCategories.values
                .filter { it.amountMinor > 0L }
                .sortedWith(
                    compareByDescending<SubCategoryAccumulator> { it.amountMinor }
                        .thenBy { it.account.sortOrder }
                        .thenBy { it.account.id },
                )
                .map { subAcc ->
                    val subBd = BigDecimal.valueOf(subAcc.amountMinor)
                    val ofParent = subBd.multiply(HUNDRED)
                        .divide(rootBd, 1, RoundingMode.HALF_UP)
                        .toPlainString()
                    val ofTotal = subBd.multiply(HUNDRED)
                        .divide(grandTotalBd, 1, RoundingMode.HALF_UP)
                        .toPlainString()
                    SubCategoryBreakdownItem(
                        categoryId = subAcc.account.id,
                        name = subAcc.account.name,
                        iconKey = subAcc.account.icon,
                        colorHex = subAcc.account.color ?: rootAcc.rootAccount.color,
                        amount = Money(subAcc.amountMinor, currency),
                        shareOfParentPctDecimal = ofParent,
                        shareOfTotalPctDecimal = ofTotal,
                        transactionCount = subAcc.transactionIds.size,
                        transactionIds = subAcc.transactionIds.toList(),
                    )
                }

            val drilldowns = rootAcc.drilldowns.sortedWith(
                compareByDescending<StatsDrilldownTransaction> { it.occurredAt }
                    .thenByDescending { it.transactionId },
            )

            CategoryRankingItem(
                categoryId = rootAcc.rootAccount.id,
                name = rootAcc.rootAccount.name,
                iconKey = rootAcc.rootAccount.icon,
                colorHex = rootAcc.rootAccount.color,
                amount = Money(rootAcc.amountMinor, currency),
                sharePctDecimal = sharePct,
                shareFraction = shareFraction,
                transactionCount = rootAcc.transactionIds.size,
                subCategories = subs,
                transactions = drilldowns,
            )
        }
    }

    private fun buildDrilldownRecord(
        tx: Transaction,
        date: LocalDate,
        categoryAccount: Account?,
        accountsById: Map<String, Account>,
        tagsById: Map<String, Tag>,
        signedFlowMinor: Long,
        displayCurrency: Currency,
        isExpense: Boolean,
    ): StatsDrilldownTransaction {
        val categoryLabel = formatCategoryPath(categoryAccount, accountsById)
        val paymentAccount = tx.postings
            .asSequence()
            .filterNot { it.isSystemBalancing }
            .mapNotNull { accountsById[it.accountId] }
            .firstOrNull { it.type == AccountType.ASSET || it.type == AccountType.LIABILITY }
        val accountName = paymentAccount?.name ?: "复式账户"
        val payeeClean = tx.payee?.trim()?.takeIf { it.isNotEmpty() }
        val title = when {
            tx.displayType == TransactionDisplayType.REFUND ->
                if (payeeClean != null) "退款 · $payeeClean" else "退款 · $categoryLabel"
            payeeClean != null -> "$categoryLabel · $payeeClean"
            else -> categoryLabel
        }
        val parentAccount = categoryAccount?.parentId?.let { accountsById[it] }
        val tagNames = tx.tagIds.mapNotNull { tagsById[it]?.name }

        val signedMoney = Money(signedFlowMinor, displayCurrency)
        return StatsDrilldownTransaction(
            transactionId = tx.id,
            occurredAt = tx.occurredAt,
            date = date,
            displayType = tx.displayType,
            title = title,
            categoryId = categoryAccount?.id,
            categoryName = categoryLabel,
            accountName = accountName,
            payee = payeeClean,
            note = tx.note?.trim()?.takeIf { it.isNotEmpty() },
            signedFlowAmount = signedMoney,
            displayAmount = signedMoney.abs(),
            isExpense = isExpense,
            isRefund = tx.displayType == TransactionDisplayType.REFUND,
            tagNames = tagNames,
            iconKey = categoryAccount?.icon ?: parentAccount?.icon,
            colorHex = categoryAccount?.color ?: parentAccount?.color,
        )
    }

    private fun formatCategoryPath(
        category: Account?,
        accountsById: Map<String, Account>,
    ): String {
        if (category == null) return "未分类"
        val parent = category.parentId?.let { accountsById[it] }
        return if (parent != null) "${parent.name}·${category.name}" else category.name
    }

    private fun resolveRootAndLeafCategory(
        category: Account,
        accountsById: Map<String, Account>,
    ): Pair<Account, Account> {
        var current = category
        val visited = hashSetOf(current.id)
        while (current.parentId != null) {
            val parent = accountsById[current.parentId] ?: break
            if (!visited.add(parent.id)) break
            current = parent
        }
        return current to category
    }

    private fun resolveFlowPostingMinor(
        posting: Posting,
        currencyFilter: StatsCurrencyFilter,
        exchangeRateProvider: ExchangeRateProvider,
    ): Long? {
        if (!currencyFilter.isBaseCurrencyMode) {
            return if (posting.currency == currencyFilter.displayCurrency) {
                posting.amountMinor
            } else {
                null
            }
        }
        val targetBase = currencyFilter.displayCurrency
        if (posting.baseCurrency == targetBase) {
            // Strictly use transaction-time locked base_amount_minor (immune to FX drift)
            return posting.baseAmountMinor
        }
        if (posting.currency == targetBase) {
            return posting.amountMinor
        }
        return exchangeRateProvider.findRate(posting.baseCurrency, targetBase)
            ?.convert(Money(posting.baseAmountMinor, posting.baseCurrency))
            ?.amountMinor
    }

    private fun isReimburseAdvance(tx: Transaction): Boolean =
        tx.displayType == TransactionDisplayType.REIMBURSE && tx.refTransactionId == null

    private fun matchesTagFilter(
        tx: Transaction,
        postedById: Map<String, Transaction>,
        selectedTagIds: Set<String>,
        mode: StatsTagFilterMode,
    ): Boolean {
        if (selectedTagIds.isEmpty()) return true
        val effectiveTags = if (
            tx.displayType == TransactionDisplayType.REFUND &&
            tx.refTransactionId != null
        ) {
            val parentTags = postedById[tx.refTransactionId]?.tagIds.orEmpty()
            (tx.tagIds + parentTags).toSet()
        } else {
            tx.tagIds.toSet()
        }
        return when (mode) {
            StatsTagFilterMode.ANY -> selectedTagIds.any { it in effectiveTags }
            StatsTagFilterMode.ALL -> selectedTagIds.all { it in effectiveTags }
        }
    }

    private fun buildBucketSpecs(
        dateRange: StatsDateRange,
        granularity: StatsTrendGranularity,
    ): List<BucketSpec> = when (granularity) {
        StatsTrendGranularity.DAILY -> {
            val multiMonth = YearMonth.from(dateRange.startDate) != YearMonth.from(dateRange.endDate)
            val result = mutableListOf<BucketSpec>()
            var cursor = dateRange.startDate
            while (!cursor.isAfter(dateRange.endDate)) {
                val label = if (multiMonth) {
                    "${cursor.monthValue}/${cursor.dayOfMonth}"
                } else {
                    "${cursor.dayOfMonth}日"
                }
                result += BucketSpec(
                    key = cursor.toString(),
                    label = label,
                    startDate = cursor,
                    endDate = cursor,
                )
                cursor = cursor.plusDays(1)
            }
            result
        }

        StatsTrendGranularity.MONTHLY -> {
            val startYm = YearMonth.from(dateRange.startDate)
            val endYm = YearMonth.from(dateRange.endDate)
            val multiYear = startYm.year != endYm.year
            val result = mutableListOf<BucketSpec>()
            var cursor = startYm
            while (!cursor.isAfter(endYm)) {
                val bucketStart = if (cursor == startYm) dateRange.startDate else cursor.atDay(1)
                val bucketEnd = if (cursor == endYm) dateRange.endDate else cursor.atEndOfMonth()
                val label = if (multiYear) {
                    "${cursor.year % 100}/${cursor.monthValue}月"
                } else {
                    "${cursor.monthValue}月"
                }
                result += BucketSpec(
                    key = "%04d-%02d".format(cursor.year, cursor.monthValue),
                    label = label,
                    startDate = bucketStart,
                    endDate = bucketEnd,
                )
                cursor = cursor.plusMonths(1)
            }
            result
        }
    }

    private fun bucketKeyFor(
        date: LocalDate,
        granularity: StatsTrendGranularity,
    ): String = when (granularity) {
        StatsTrendGranularity.DAILY -> date.toString()
        StatsTrendGranularity.MONTHLY -> "%04d-%02d".format(date.year, date.monthValue)
    }

    private data class BucketSpec(
        val key: String,
        val label: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
    )

    private class TrendAccumulator(val spec: BucketSpec) {
        var incomeMinor: Long = 0L
        var expenseMinor: Long = 0L
        val transactionIds: LinkedHashSet<String> = linkedSetOf()
    }

    private class SubCategoryAccumulator(val account: Account) {
        var amountMinor: Long = 0L
        val transactionIds: LinkedHashSet<String> = linkedSetOf()
    }

    private class RootCategoryAccumulator(val rootAccount: Account) {
        var amountMinor: Long = 0L
        val transactionIds: LinkedHashSet<String> = linkedSetOf()
        val subCategories: LinkedHashMap<String, SubCategoryAccumulator> = linkedMapOf()
        val drilldowns: MutableList<StatsDrilldownTransaction> = mutableListOf()
    }

    private class DayAccumulator(val date: LocalDate) {
        var incomeMinor: Long = 0L
        var expenseMinor: Long = 0L
        val txIds: LinkedHashSet<String> = linkedSetOf()
        val transactions: MutableList<StatsDrilldownTransaction> = mutableListOf()
    }
}
