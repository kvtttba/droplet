package io.github.kvtttba.droplet.core.domain.investment

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateProvider
import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.Money

enum class AssetOverviewGroup {
    DAILY_CASH,
    CREDIT,
    INVESTMENT,
    LOAN,
    DEBT_AND_REIMBURSEMENT,
}

data class AccountValuation(
    val account: Account,
    val originalBalance: Money,
    val baseBalance: Money,
    val exchangeRate: ExchangeRateSnapshot,
    val group: AssetOverviewGroup,
    val includedInNetWorth: Boolean,
    val linkedPositionCount: Int = 0,
)

data class CurrencyDistribution(
    val currency: Currency,
    val assetsOriginal: Money,
    val liabilitiesOriginal: Money,
    val netOriginal: Money,
    val assetsBase: Money,
    val liabilitiesBase: Money,
    val netBase: Money,
    val assetSharePctDecimal: String,
)

data class AssetGroupSummary(
    val group: AssetOverviewGroup,
    val assetsBase: Money,
    val liabilitiesBase: Money,
    val netBase: Money,
    val accountCount: Int,
)

data class InvestmentPortfolioTotals(
    val baseCurrency: Currency,
    val positionCount: Int,
    val activePositionCount: Int,
    val marketValueBase: Money,
    val costBasisBase: Money,
    val unrealizedPnLBase: Money,
    val unrealizedReturnPctDecimal: String?,
    val realizedPnLBase: Money,
    val totalDividendsBase: Money,
    val totalFeesBase: Money,
    val totalReturnBase: Money,
    val dailyChangeBase: Money?,
    val dailyChangePctDecimal: String?,
)

/**
 * Complete cross-account and multi-currency net-worth summary ready for the M2.3 Assets overview.
 *
 * Guaranteed algebraic identities (across all `includeInNetWorth == true` accounts & holdings):
 * - `netWorthBase == totalAssetsBase - totalLiabilitiesBase`
 * - `currencyDistributions.sumOf { it.assetsBase } == totalAssetsBase`
 * - `currencyDistributions.sumOf { it.liabilitiesBase } == totalLiabilitiesBase`
 * - `currencyDistributions.sumOf { it.netBase } == netWorthBase`
 */
data class PortfolioSummary(
    val baseCurrency: Currency,
    val netWorthBase: Money,
    val totalAssetsBase: Money,
    val totalLiabilitiesBase: Money,
    val excludedNetWorthBase: Money,
    val currencyDistributions: List<CurrencyDistribution>,
    val groupSummaries: List<AssetGroupSummary>,
    val investmentTotals: InvestmentPortfolioTotals,
    val accountValuations: List<AccountValuation>,
    val excludedAccountValuations: List<AccountValuation>,
    val positions: List<PortfolioPositionValuation>,
    val includedPositions: List<PortfolioPositionValuation>,
)

/**
 * Pure Kotlin/JVM aggregator combining all balance-sheet accounts (cash, debit, stored value,
 * credit, investment cash, investment holdings, loans, receivables, payables, and reimbursements)
 * with live/accrued [PortfolioPositionValuation]s.
 *
 * Key invariants:
 * - Strictly respects [Account.includeInNetWorth] on both cash/liability accounts and holding accounts.
 * - Replaces `INVEST_HOLDING` book balances with the mark-to-market / accrued valuation of linked
 *   [PortfolioPositionValuation]s so investment assets are never double-counted.
 * - Handles liabilities (`amountMinor < 0` contributes to `totalLiabilitiesBase`; credit-card
 *   overpayments `amountMinor > 0` contribute to `totalAssetsBase`) and asset overdrafts cleanly.
 * - Preserves exact reconciliation between headline totals and per-currency CNY/USD/HKD totals.
 */
object PortfolioSummaryAggregator {

    fun aggregate(
        accounts: Iterable<Account>,
        accountBalancesById: Map<String, Money>,
        positions: Iterable<PortfolioPositionValuation>,
        baseCurrency: Currency = Currency.DEFAULT,
        exchangeRateProvider: ExchangeRateProvider = ExchangeRateTable.empty(),
    ): PortfolioSummary {
        val balanceSheetAccounts = accounts.filter {
            it.type == AccountType.ASSET || it.type == AccountType.LIABILITY
        }
        val accountsById = balanceSheetAccounts.associateBy { it.id }
        val allPositions = positions.toList()
        val positionsByAccountId = allPositions.groupBy { it.asset.accountId }

        val includedAccountValuations = mutableListOf<AccountValuation>()
        val excludedAccountValuations = mutableListOf<AccountValuation>()

        var totalAssetsBaseMinor = 0L
        var totalLiabilitiesBaseMinor = 0L
        var excludedNetWorthBaseMinor = 0L

        val currencyAccumulators = Currency.entries.associateWith { CurrencyAccumulator(it) }
        val groupAccumulators = AssetOverviewGroup.entries.associateWith { GroupAccumulator(it) }

        val handledAccountIds = hashSetOf<String>()

        for (account in balanceSheetAccounts) {
            handledAccountIds += account.id
            val linkedPositions = positionsByAccountId[account.id].orEmpty()
            val group = mapAccountToGroup(account.subType)
            val rate = exchangeRateProvider.requireRate(account.currency, baseCurrency)

            val (originalValue, baseValue) = if (
                account.subType == AccountSubType.INVEST_HOLDING && linkedPositions.isNotEmpty()
            ) {
                var origSum = 0L
                var baseSum = 0L
                for (pos in linkedPositions) {
                    require(pos.asset.currency == account.currency) {
                        "Position '${pos.asset.id}' currency (${pos.asset.currency}) differs from " +
                            "holding account '${account.id}' currency (${account.currency})"
                    }
                    require(pos.baseCurrency == baseCurrency) {
                        "Position '${pos.asset.id}' baseCurrency (${pos.baseCurrency}) differs from " +
                            "summary baseCurrency ($baseCurrency)"
                    }
                    origSum = Math.addExact(origSum, pos.marketValueOriginal.amountMinor)
                    baseSum = Math.addExact(baseSum, pos.marketValueBase.amountMinor)
                }
                Money(origSum, account.currency) to Money(baseSum, baseCurrency)
            } else {
                val rawBalance = accountBalancesById[account.id] ?: Money.zero(account.currency)
                require(rawBalance.currency == account.currency) {
                    "Balance currency (${rawBalance.currency}) for account '${account.id}' differs from account currency (${account.currency})"
                }
                rawBalance to rate.convert(rawBalance)
            }

            val valuation = AccountValuation(
                account = account,
                originalBalance = originalValue,
                baseBalance = baseValue,
                exchangeRate = rate,
                group = group,
                includedInNetWorth = account.includeInNetWorth,
                linkedPositionCount = linkedPositions.size,
            )

            if (account.includeInNetWorth) {
                includedAccountValuations += valuation
                val origMinor = originalValue.amountMinor
                val baseMinor = baseValue.amountMinor
                val currencyBucket = currencyAccumulators.getValue(account.currency)
                val groupBucket = groupAccumulators.getValue(group)
                groupBucket.accountCount++

                if (baseMinor >= 0L) {
                    totalAssetsBaseMinor = Math.addExact(totalAssetsBaseMinor, baseMinor)
                    currencyBucket.assetsOriginalMinor = Math.addExact(currencyBucket.assetsOriginalMinor, origMinor)
                    currencyBucket.assetsBaseMinor = Math.addExact(currencyBucket.assetsBaseMinor, baseMinor)
                    groupBucket.assetsBaseMinor = Math.addExact(groupBucket.assetsBaseMinor, baseMinor)
                } else {
                    val liabilityOrig = Math.negateExact(origMinor)
                    val liabilityBase = Math.negateExact(baseMinor)
                    totalLiabilitiesBaseMinor = Math.addExact(totalLiabilitiesBaseMinor, liabilityBase)
                    currencyBucket.liabilitiesOriginalMinor =
                        Math.addExact(currencyBucket.liabilitiesOriginalMinor, liabilityOrig)
                    currencyBucket.liabilitiesBaseMinor =
                        Math.addExact(currencyBucket.liabilitiesBaseMinor, liabilityBase)
                    groupBucket.liabilitiesBaseMinor =
                        Math.addExact(groupBucket.liabilitiesBaseMinor, liabilityBase)
                }
            } else {
                excludedAccountValuations += valuation
                excludedNetWorthBaseMinor = Math.addExact(excludedNetWorthBaseMinor, baseValue.amountMinor)
            }
        }

        // Also include any standalone positions whose holding account was not supplied in `accounts`
        val orphanPositions = allPositions.filter { it.asset.accountId !in handledAccountIds }
        for (pos in orphanPositions) {
            require(pos.baseCurrency == baseCurrency) {
                "Position '${pos.asset.id}' baseCurrency (${pos.baseCurrency}) differs from summary baseCurrency ($baseCurrency)"
            }
            val origMinor = pos.marketValueOriginal.amountMinor
            val baseMinor = pos.marketValueBase.amountMinor
            val currencyBucket = currencyAccumulators.getValue(pos.asset.currency)
            val groupBucket = groupAccumulators.getValue(AssetOverviewGroup.INVESTMENT)
            if (baseMinor >= 0L) {
                totalAssetsBaseMinor = Math.addExact(totalAssetsBaseMinor, baseMinor)
                currencyBucket.assetsOriginalMinor = Math.addExact(currencyBucket.assetsOriginalMinor, origMinor)
                currencyBucket.assetsBaseMinor = Math.addExact(currencyBucket.assetsBaseMinor, baseMinor)
                groupBucket.assetsBaseMinor = Math.addExact(groupBucket.assetsBaseMinor, baseMinor)
            } else {
                val liabilityOrig = Math.negateExact(origMinor)
                val liabilityBase = Math.negateExact(baseMinor)
                totalLiabilitiesBaseMinor = Math.addExact(totalLiabilitiesBaseMinor, liabilityBase)
                currencyBucket.liabilitiesOriginalMinor =
                    Math.addExact(currencyBucket.liabilitiesOriginalMinor, liabilityOrig)
                currencyBucket.liabilitiesBaseMinor =
                    Math.addExact(currencyBucket.liabilitiesBaseMinor, liabilityBase)
                groupBucket.liabilitiesBaseMinor =
                    Math.addExact(groupBucket.liabilitiesBaseMinor, liabilityBase)
            }
        }

        val totalAssetsBase = Money(totalAssetsBaseMinor, baseCurrency)
        val totalLiabilitiesBase = Money(totalLiabilitiesBaseMinor, baseCurrency)
        val netWorthBase = Money(
            Math.subtractExact(totalAssetsBaseMinor, totalLiabilitiesBaseMinor),
            baseCurrency,
        )

        val currencyDistributions = Currency.entries.map { ccy ->
            val acc = currencyAccumulators.getValue(ccy)
            val assetsBase = Money(acc.assetsBaseMinor, baseCurrency)
            val liabilitiesBase = Money(acc.liabilitiesBaseMinor, baseCurrency)
            val netBase = Money(Math.subtractExact(acc.assetsBaseMinor, acc.liabilitiesBaseMinor), baseCurrency)
            val sharePct = if (totalAssetsBaseMinor > 0L) {
                InvestmentDecimalMath.percentageOrNull(
                    numerator = assetsBase.toBigDecimal(),
                    denominator = totalAssetsBase.toBigDecimal(),
                ) ?: "0"
            } else {
                "0"
            }
            CurrencyDistribution(
                currency = ccy,
                assetsOriginal = Money(acc.assetsOriginalMinor, ccy),
                liabilitiesOriginal = Money(acc.liabilitiesOriginalMinor, ccy),
                netOriginal = Money(Math.subtractExact(acc.assetsOriginalMinor, acc.liabilitiesOriginalMinor), ccy),
                assetsBase = assetsBase,
                liabilitiesBase = liabilitiesBase,
                netBase = netBase,
                assetSharePctDecimal = sharePct,
            )
        }

        val groupSummaries = AssetOverviewGroup.entries.map { grp ->
            val acc = groupAccumulators.getValue(grp)
            AssetGroupSummary(
                group = grp,
                assetsBase = Money(acc.assetsBaseMinor, baseCurrency),
                liabilitiesBase = Money(acc.liabilitiesBaseMinor, baseCurrency),
                netBase = Money(Math.subtractExact(acc.assetsBaseMinor, acc.liabilitiesBaseMinor), baseCurrency),
                accountCount = acc.accountCount,
            )
        }

        val includedPositions = allPositions.filter { pos ->
            accountsById[pos.asset.accountId]?.includeInNetWorth ?: true
        }
        val investmentTotals = aggregateInvestmentTotals(
            positions = includedPositions,
            baseCurrency = baseCurrency,
        )

        return PortfolioSummary(
            baseCurrency = baseCurrency,
            netWorthBase = netWorthBase,
            totalAssetsBase = totalAssetsBase,
            totalLiabilitiesBase = totalLiabilitiesBase,
            excludedNetWorthBase = Money(excludedNetWorthBaseMinor, baseCurrency),
            currencyDistributions = currencyDistributions,
            groupSummaries = groupSummaries,
            investmentTotals = investmentTotals,
            accountValuations = includedAccountValuations,
            excludedAccountValuations = excludedAccountValuations,
            positions = allPositions,
            includedPositions = includedPositions,
        )
    }

    fun mapAccountToGroup(subType: AccountSubType): AssetOverviewGroup = when (subType) {
        AccountSubType.CASH,
        AccountSubType.DEBIT_CARD,
        AccountSubType.STORED_VALUE,
        -> AssetOverviewGroup.DAILY_CASH

        AccountSubType.CREDIT_CARD -> AssetOverviewGroup.CREDIT

        AccountSubType.INVEST_CASH,
        AccountSubType.INVEST_HOLDING,
        -> AssetOverviewGroup.INVESTMENT

        AccountSubType.LOAN -> AssetOverviewGroup.LOAN

        AccountSubType.RECEIVABLE,
        AccountSubType.PAYABLE,
        AccountSubType.REIMBURSEMENT,
        -> AssetOverviewGroup.DEBT_AND_REIMBURSEMENT

        AccountSubType.CATEGORY,
        AccountSubType.TRADING,
        AccountSubType.OPENING,
        -> error("Non-balance-sheet subType $subType cannot map to an asset overview group")
    }

    private fun aggregateInvestmentTotals(
        positions: List<PortfolioPositionValuation>,
        baseCurrency: Currency,
    ): InvestmentPortfolioTotals {
        var marketValueMinor = 0L
        var costBasisMinor = 0L
        var unrealizedPnLMinor = 0L
        var realizedPnLMinor = 0L
        var totalDividendsMinor = 0L
        var totalFeesMinor = 0L
        var totalReturnMinor = 0L

        var hasAnyDailyChange = false
        var dailyChangeMinor = 0L
        var dailyBaseMarketValueMinor = 0L
        var activeCount = 0

        for (pos in positions) {
            if (pos.hasHolding) activeCount++
            marketValueMinor = Math.addExact(marketValueMinor, pos.marketValueBase.amountMinor)
            costBasisMinor = Math.addExact(costBasisMinor, pos.costBasisBase.amountMinor)
            unrealizedPnLMinor = Math.addExact(unrealizedPnLMinor, pos.unrealizedPnLBase.amountMinor)
            realizedPnLMinor = Math.addExact(realizedPnLMinor, pos.realizedPnLBase.amountMinor)
            totalDividendsMinor = Math.addExact(totalDividendsMinor, pos.totalDividendsBase.amountMinor)
            totalFeesMinor = Math.addExact(totalFeesMinor, pos.totalFeesBase.amountMinor)
            totalReturnMinor = Math.addExact(totalReturnMinor, pos.totalReturnBase.amountMinor)

            val dailyChange = pos.dailyChangeBase
            if (dailyChange != null) {
                hasAnyDailyChange = true
                dailyChangeMinor = Math.addExact(dailyChangeMinor, dailyChange.amountMinor)
                if (pos.hasHolding) {
                    dailyBaseMarketValueMinor = Math.addExact(dailyBaseMarketValueMinor, pos.marketValueBase.amountMinor)
                }
            }
        }

        val costBasisMoney = Money(costBasisMinor, baseCurrency)
        val unrealizedMoney = Money(unrealizedPnLMinor, baseCurrency)
        val unrealizedPct = if (costBasisMinor > 0L) {
            InvestmentDecimalMath.percentageOrNull(
                numerator = unrealizedMoney.toBigDecimal(),
                denominator = costBasisMoney.toBigDecimal(),
            )
        } else {
            null
        }

        val dailyChangeMoney = if (hasAnyDailyChange) Money(dailyChangeMinor, baseCurrency) else null
        val dailyChangePct = if (hasAnyDailyChange) {
            val prevDayTotalMinor = Math.subtractExact(dailyBaseMarketValueMinor, dailyChangeMinor)
            if (prevDayTotalMinor > 0L) {
                InvestmentDecimalMath.percentageOrNull(
                    numerator = dailyChangeMoney!!.toBigDecimal(),
                    denominator = Money(prevDayTotalMinor, baseCurrency).toBigDecimal(),
                )
            } else {
                null
            }
        } else {
            null
        }

        return InvestmentPortfolioTotals(
            baseCurrency = baseCurrency,
            positionCount = positions.size,
            activePositionCount = activeCount,
            marketValueBase = Money(marketValueMinor, baseCurrency),
            costBasisBase = costBasisMoney,
            unrealizedPnLBase = unrealizedMoney,
            unrealizedReturnPctDecimal = unrealizedPct,
            realizedPnLBase = Money(realizedPnLMinor, baseCurrency),
            totalDividendsBase = Money(totalDividendsMinor, baseCurrency),
            totalFeesBase = Money(totalFeesMinor, baseCurrency),
            totalReturnBase = Money(totalReturnMinor, baseCurrency),
            dailyChangeBase = dailyChangeMoney,
            dailyChangePctDecimal = dailyChangePct,
        )
    }

    private class CurrencyAccumulator(val currency: Currency) {
        var assetsOriginalMinor: Long = 0L
        var liabilitiesOriginalMinor: Long = 0L
        var assetsBaseMinor: Long = 0L
        var liabilitiesBaseMinor: Long = 0L
    }

    private class GroupAccumulator(val group: AssetOverviewGroup) {
        var assetsBaseMinor: Long = 0L
        var liabilitiesBaseMinor: Long = 0L
        var accountCount: Int = 0
    }
}
