package io.github.kvtttba.droplet.feature.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.CreateInvestmentAssetRequest
import io.github.kvtttba.droplet.core.database.repository.CreateLoanPlanRequest
import io.github.kvtttba.droplet.core.database.repository.CreditCardRepository
import io.github.kvtttba.droplet.core.database.repository.InvestmentRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LoanRepository
import io.github.kvtttba.droplet.core.database.repository.MarketDataRepository
import io.github.kvtttba.droplet.core.database.repository.MarketSyncReport
import io.github.kvtttba.droplet.core.database.repository.MarketSyncStatus
import io.github.kvtttba.droplet.core.database.repository.OpenFixedDepositRequest
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.credit.CreditCardStatementSummary
import io.github.kvtttba.droplet.core.domain.investment.AssetGroupSummary
import io.github.kvtttba.droplet.core.domain.investment.AssetOverviewGroup
import io.github.kvtttba.droplet.core.domain.investment.CurrencyDistribution
import io.github.kvtttba.droplet.core.domain.investment.InvestmentPortfolioTotals
import io.github.kvtttba.droplet.core.domain.investment.PortfolioSummary
import io.github.kvtttba.droplet.core.domain.loan.LoanPlanSummary
import io.github.kvtttba.droplet.core.domain.loan.LoanScheduleCalculator
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DepositInterestMode
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.MarketQuote
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferencesRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AssetsNoticeTone {
    INFO,
    WARNING,
    ERROR,
}

data class AssetsRefreshNotice(
    val message: String,
    val tone: AssetsNoticeTone,
)

data class CreateInvestmentAssetFormInput(
    val assetClass: AssetClass,
    val name: String,
    val symbol: String,
    val currency: Currency,
    val existingHoldingAccountId: String? = null,
    val includeInNetWorth: Boolean = true,
    val quoteProviderHint: String? = null,
    val depositRatePercentText: String = "",
    val depositStartDateIso: String = "",
    val depositMaturityDateIso: String = "",
    val depositInterestMode: DepositInterestMode = DepositInterestMode.SIMPLE_AT_MATURITY,
    val depositPrincipalText: String = "",
    val depositCashAccountId: String? = null,
)

data class CreateLoanPlanFormInput(
    val name: String,
    val method: LoanMethod,
    val currency: Currency,
    val principalText: String,
    val totalPeriodsText: String,
    val annualRatePercentText: String = "",
    val fixedFeeUseRate: Boolean = false,
    val fixedFeePerPeriodText: String = "",
    val fixedFeeRatePercentText: String = "",
    val firstDueDateIso: String,
    val monthlyRepayDayText: String,
    val paymentAccountId: String?,
    val interestExpenseAccountId: String = DefaultSeedCatalog.AccountIds.EXP_FINANCE_INTEREST_FEE,
    val autoPost: Boolean = false,
    val remindDaysBeforeText: String = "3",
    val includeInNetWorth: Boolean = true,
    val note: String = "",
)

data class AssetsUiState(
    val displayCurrency: Currency = Currency.CNY,
    val summary: PortfolioSummary = emptyPortfolioSummary(Currency.CNY),
    val creditCardSummariesById: Map<String, CreditCardStatementSummary> = emptyMap(),
    val loanSummaries: List<LoanPlanSummary> = emptyList(),
    val quotesByAssetId: Map<String, MarketQuote> = emptyMap(),
    val availableCashAccounts: List<Account> = emptyList(),
    val availableHoldingAccounts: List<Account> = emptyList(),
    val availableLoanPaymentAccounts: List<Account> = emptyList(),
    val availableExpenseCategories: List<Account> = emptyList(),
    val lastUpdatedAtEpochMs: Long? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val refreshNotice: AssetsRefreshNotice? = null,
    val excludedExpanded: Boolean = false,
    val isCreatingAsset: Boolean = false,
    val isCreatingLoan: Boolean = false,
    val userMessage: String? = null,
) {
    companion object {
        fun emptyPortfolioSummary(baseCurrency: Currency): PortfolioSummary = PortfolioSummary(
            baseCurrency = baseCurrency,
            netWorthBase = Money.zero(baseCurrency),
            totalAssetsBase = Money.zero(baseCurrency),
            totalLiabilitiesBase = Money.zero(baseCurrency),
            excludedNetWorthBase = Money.zero(baseCurrency),
            currencyDistributions = Currency.entries.map { ccy ->
                CurrencyDistribution(
                    currency = ccy,
                    assetsOriginal = Money.zero(ccy),
                    liabilitiesOriginal = Money.zero(ccy),
                    netOriginal = Money.zero(ccy),
                    assetsBase = Money.zero(baseCurrency),
                    liabilitiesBase = Money.zero(baseCurrency),
                    netBase = Money.zero(baseCurrency),
                    assetSharePctDecimal = "0",
                )
            },
            groupSummaries = AssetOverviewGroup.entries.map { grp ->
                AssetGroupSummary(
                    group = grp,
                    assetsBase = Money.zero(baseCurrency),
                    liabilitiesBase = Money.zero(baseCurrency),
                    netBase = Money.zero(baseCurrency),
                    accountCount = 0,
                )
            },
            investmentTotals = InvestmentPortfolioTotals(
                baseCurrency = baseCurrency,
                positionCount = 0,
                activePositionCount = 0,
                marketValueBase = Money.zero(baseCurrency),
                costBasisBase = Money.zero(baseCurrency),
                unrealizedPnLBase = Money.zero(baseCurrency),
                unrealizedReturnPctDecimal = null,
                realizedPnLBase = Money.zero(baseCurrency),
                totalDividendsBase = Money.zero(baseCurrency),
                totalFeesBase = Money.zero(baseCurrency),
                totalReturnBase = Money.zero(baseCurrency),
                dailyChangeBase = null,
                dailyChangePctDecimal = null,
            ),
            accountValuations = emptyList(),
            excludedAccountValuations = emptyList(),
            positions = emptyList(),
            includedPositions = emptyList(),
        )
    }
}

private data class AssetsRuntimeState(
    val isRefreshing: Boolean = false,
    val refreshNotice: AssetsRefreshNotice? = null,
    val excludedExpanded: Boolean = false,
    val isCreatingAsset: Boolean = false,
    val isCreatingLoan: Boolean = false,
    val userMessage: String? = null,
)

private data class MarketMetadataSnapshot(
    val quotesByAssetId: Map<String, MarketQuote> = emptyMap(),
    val lastUpdatedAtEpochMs: Long? = null,
)

private data class AssetsCoreData(
    val displayCurrency: Currency,
    val summary: PortfolioSummary,
    val creditCardSummaries: Map<String, CreditCardStatementSummary>,
    val loanSummaries: List<LoanPlanSummary>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AssetsViewModel @Inject constructor(
    private val investmentRepository: InvestmentRepository,
    private val creditCardRepository: CreditCardRepository,
    private val creditCardReminderPreferencesRepository: CreditCardReminderPreferencesRepository,
    private val loanRepository: LoanRepository,
    private val marketDataRepository: MarketDataRepository,
    private val ledgerRepository: LedgerRepository,
    private val clock: NetworkClock,
) : ViewModel() {

    private val displayCurrencyFlow = MutableStateFlow(Currency.DEFAULT)
    private val runtimeFlow = MutableStateFlow(AssetsRuntimeState())

    private val portfolioSummaryFlow = displayCurrencyFlow.flatMapLatest { baseCurrency ->
        investmentRepository.observePortfolioSummary(baseCurrency = baseCurrency)
    }

    private val creditCardSummariesFlow = creditCardReminderPreferencesRepository.preferences
        .map { it.defaultRemindDaysBefore }
        .distinctUntilChanged()
        .flatMapLatest { defaultRemindDays ->
            creditCardRepository.observeCreditCardSummaries(
                includeArchived = false,
                defaultRemindDaysBefore = defaultRemindDays,
            )
        }
        .map { summaries -> summaries.associateBy { it.account.id } }

    private val loanSummariesFlow = loanRepository.observeLoanSummaries(includeArchived = false)

    private val marketMetadataFlow = combine(
        marketDataRepository.observeMarketQuotes(),
        marketDataRepository.observeExchangeRates(),
    ) { quotes, rates ->
        val latestQuoteAt = quotes.maxOfOrNull { it.fetchedAt }
        val latestRateAt = rates.maxOfOrNull { it.fetchedAt }
        MarketMetadataSnapshot(
            quotesByAssetId = quotes.associateBy { it.assetId },
            lastUpdatedAtEpochMs = listOfNotNull(latestQuoteAt, latestRateAt).maxOrNull(),
        )
    }

    private val coreDataFlow = combine(
        displayCurrencyFlow,
        portfolioSummaryFlow,
        creditCardSummariesFlow,
        loanSummariesFlow,
    ) { displayCurrency, summary, creditCardSummaries, loanSummaries ->
        AssetsCoreData(
            displayCurrency = displayCurrency,
            summary = summary,
            creditCardSummaries = creditCardSummaries,
            loanSummaries = loanSummaries,
        )
    }

    private val accountsCatalogFlow = combine(
        ledgerRepository.observeOperationalAccounts(includeArchived = false),
        ledgerRepository.observeCategories(AccountType.EXPENSE, includeArchived = false),
    ) { operationalAccounts, expenseCategories ->
        operationalAccounts to expenseCategories
    }

    val uiState: StateFlow<AssetsUiState> = combine(
        coreDataFlow,
        marketMetadataFlow,
        accountsCatalogFlow,
        runtimeFlow,
    ) { coreData, marketMeta, (operationalAccounts, expenseCategories), runtime ->
        val cashAccounts = operationalAccounts.filter {
            it.subType in FUNDING_CASH_SUB_TYPES
        }
        val holdingAccounts = operationalAccounts.filter {
            it.subType == AccountSubType.INVEST_HOLDING
        }
        val loanPaymentAccounts = operationalAccounts.filter {
            it.subType in FUNDING_CASH_SUB_TYPES || it.subType == AccountSubType.CREDIT_CARD
        }
        val leafExpenseCategories = expenseCategories.filter { it.parentId != null }
            .ifEmpty { expenseCategories }
        AssetsUiState(
            displayCurrency = coreData.displayCurrency,
            summary = coreData.summary,
            creditCardSummariesById = coreData.creditCardSummaries,
            loanSummaries = coreData.loanSummaries,
            quotesByAssetId = marketMeta.quotesByAssetId,
            availableCashAccounts = cashAccounts,
            availableHoldingAccounts = holdingAccounts,
            availableLoanPaymentAccounts = loanPaymentAccounts,
            availableExpenseCategories = leafExpenseCategories,
            lastUpdatedAtEpochMs = marketMeta.lastUpdatedAtEpochMs,
            isLoading = false,
            isRefreshing = runtime.isRefreshing,
            refreshNotice = runtime.refreshNotice,
            excludedExpanded = runtime.excludedExpanded,
            isCreatingAsset = runtime.isCreatingAsset,
            isCreatingLoan = runtime.isCreatingLoan,
            userMessage = runtime.userMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AssetsUiState(),
    )

    fun selectDisplayCurrency(currency: Currency) {
        displayCurrencyFlow.value = currency
    }

    fun toggleExcludedExpanded() {
        runtimeFlow.update { it.copy(excludedExpanded = !it.excludedExpanded) }
    }

    fun dismissRefreshNotice() {
        runtimeFlow.update { it.copy(refreshNotice = null) }
    }

    fun clearUserMessage() {
        runtimeFlow.update { it.copy(userMessage = null) }
    }

    /**
     * Triggered when entering the Assets tab: runs a 5-minute throttled refresh of FX & market quotes.
     * Silently skips UI disruption when throttled or when no public-quoted assets exist.
     */
    fun onScreenEntered() {
        performRefresh(forceRefresh = false, showUserToast = false)
    }

    /**
     * Triggered by explicit pull-to-refresh or top refresh button: bypasses 5-minute throttle.
     */
    fun refreshNow() {
        performRefresh(forceRefresh = true, showUserToast = true)
    }

    private fun performRefresh(
        forceRefresh: Boolean,
        showUserToast: Boolean,
    ) {
        if (runtimeFlow.value.isRefreshing) return
        viewModelScope.launch {
            runtimeFlow.update { it.copy(isRefreshing = true) }
            runCatching {
                marketDataRepository.refreshAll(
                    forceRefresh = forceRefresh,
                    overwriteManualOverrides = false,
                )
            }.onSuccess { report ->
                val notice = buildRefreshNotice(report, isManual = showUserToast)
                runtimeFlow.update {
                    it.copy(
                        isRefreshing = false,
                        refreshNotice = notice,
                        userMessage = if (showUserToast) notice?.message ?: "行情与汇率已同步" else it.userMessage,
                    )
                }
            }.onFailure {
                val failNotice = AssetsRefreshNotice(
                    message = "行情同步失败，已保留上次缓存估值",
                    tone = AssetsNoticeTone.WARNING,
                )
                runtimeFlow.update {
                    it.copy(
                        isRefreshing = false,
                        refreshNotice = failNotice,
                        userMessage = if (showUserToast) failNotice.message else it.userMessage,
                    )
                }
            }
        }
    }

    fun createInvestmentAsset(
        input: CreateInvestmentAssetFormInput,
        onResult: (Result<String>) -> Unit,
    ) {
        if (runtimeFlow.value.isCreatingAsset) return
        viewModelScope.launch {
            runtimeFlow.update { it.copy(isCreatingAsset = true) }
            val result = runCatching {
                val trimmedName = input.name.trim()
                require(trimmedName.isNotEmpty()) { "请输入标的名称" }

                val rawSymbol = input.symbol.trim()
                val resolvedSymbol = when {
                    rawSymbol.isNotEmpty() -> rawSymbol
                    input.assetClass == AssetClass.FIXED_DEPOSIT ->
                        "FD-${UUID.randomUUID().toString().take(6).uppercase()}"
                    input.assetClass == AssetClass.BANK_WEALTH ->
                        "BW-${UUID.randomUUID().toString().take(6).uppercase()}"
                    else -> throw IllegalArgumentException("请输入标的代码（如 600519 / AAPL / BTC）")
                }

                val now = clock.nowEpochMs()
                if (input.assetClass == AssetClass.FIXED_DEPOSIT) {
                    val canonicalRate = AssetsFormatters.parseAnnualRatePercentToCanonicalDecimal(
                        input.depositRatePercentText,
                    )
                    val startAt = runCatching {
                        AssetsFormatters.parseIsoDateToEpochMs(input.depositStartDateIso)
                    }.getOrElse {
                        throw IllegalArgumentException("起息日格式无效，请使用 YYYY-MM-DD")
                    }
                    val maturityAt = runCatching {
                        AssetsFormatters.parseIsoDateToEpochMs(input.depositMaturityDateIso)
                    }.getOrElse {
                        throw IllegalArgumentException("到期日格式无效，请使用 YYYY-MM-DD")
                    }
                    require(maturityAt >= startAt) { "到期日不能早于起息日" }

                    val assetRequest = CreateInvestmentAssetRequest(
                        assetClass = AssetClass.FIXED_DEPOSIT,
                        symbol = resolvedSymbol,
                        name = trimmedName,
                        currency = input.currency,
                        existingHoldingAccountId = input.existingHoldingAccountId,
                        includeInNetWorth = input.includeInNetWorth,
                        depositRateAnnualDecimal = canonicalRate,
                        depositStartAt = startAt,
                        depositMaturityAt = maturityAt,
                        depositInterestMode = input.depositInterestMode,
                        nowEpochMs = now,
                    )

                    val principalText = input.depositPrincipalText.trim()
                    if (principalText.isNotEmpty()) {
                        val principal = runCatching {
                            Money.fromDecimalString(principalText, input.currency)
                        }.getOrElse {
                            throw IllegalArgumentException("存入本金格式无效")
                        }
                        require(principal.isPositive) { "存入本金必须大于 0" }
                        val cashAccountId = input.depositCashAccountId
                            ?: throw IllegalArgumentException("请选择存入定期的扣款资金账户")
                        investmentRepository.openFixedDeposit(
                            OpenFixedDepositRequest(
                                assetRequest = assetRequest,
                                cashAccountId = cashAccountId,
                                principal = principal,
                                occurredAt = startAt,
                            ),
                        ).asset.id
                    } else {
                        investmentRepository.createInvestmentAsset(assetRequest).id
                    }
                } else {
                    investmentRepository.createInvestmentAsset(
                        CreateInvestmentAssetRequest(
                            assetClass = input.assetClass,
                            symbol = resolvedSymbol,
                            name = trimmedName,
                            currency = input.currency,
                            existingHoldingAccountId = input.existingHoldingAccountId,
                            includeInNetWorth = input.includeInNetWorth,
                            quoteProviderHint = input.quoteProviderHint?.trim()?.takeIf { it.isNotEmpty() },
                            nowEpochMs = now,
                        ),
                    ).id
                }
            }

            runtimeFlow.update {
                it.copy(
                    isCreatingAsset = false,
                    userMessage = result.fold(
                        onSuccess = { "已创建「${input.name.trim()}」" },
                        onFailure = { error -> error.message ?: "创建标的失败" },
                    ),
                )
            }
            onResult(result)
        }
    }

    fun createLoanPlan(
        input: CreateLoanPlanFormInput,
        onResult: (Result<String>) -> Unit,
    ) {
        if (runtimeFlow.value.isCreatingLoan) return
        viewModelScope.launch {
            runtimeFlow.update { it.copy(isCreatingLoan = true) }
            val result = runCatching {
                val trimmedName = input.name.trim()
                require(trimmedName.isNotEmpty()) { "请输入分期贷款名称" }

                val principalText = input.principalText.trim()
                require(principalText.isNotEmpty()) { "请输入贷款总本金" }
                val principal = runCatching {
                    Money.fromDecimalString(principalText, input.currency)
                }.getOrElse {
                    throw IllegalArgumentException("贷款总本金格式无效")
                }
                require(principal.isPositive) { "贷款总本金必须大于 0" }

                val totalPeriods = input.totalPeriodsText.trim().toIntOrNull()
                    ?: throw IllegalArgumentException("总期数需为正整数")
                require(totalPeriods in 1..600) { "总期数需在 1–600 期之间" }

                val firstDueAt = runCatching {
                    AssetsFormatters.parseIsoDateToEpochMs(
                        input.firstDueDateIso,
                        zoneId = LoanScheduleCalculator.DEFAULT_ZONE_ID,
                    )
                }.getOrElse {
                    throw IllegalArgumentException("首期还款日格式无效，请使用 YYYY-MM-DD")
                }

                val monthlyRepayDay = input.monthlyRepayDayText.trim().toIntOrNull()
                    ?: throw IllegalArgumentException("每月扣款日需为 1–31 的整数")
                require(monthlyRepayDay in 1..31) { "每月扣款日需在 1–31 之间" }

                val paymentAccountId = input.paymentAccountId
                    ?: throw IllegalArgumentException("请选择关联付款账户")

                val remindDays = input.remindDaysBeforeText.trim().let { raw ->
                    if (raw.isEmpty()) {
                        null
                    } else {
                        val parsed = raw.toIntOrNull()
                            ?: throw IllegalArgumentException("提前提醒天数需为 0–31 的整数")
                        require(parsed in 0..31) { "提前提醒天数需在 0–31 之间" }
                        parsed
                    }
                }

                val annualRateDecimal: String?
                val fixedFeePerPeriodMinor: Long?
                val fixedFeeRateDecimal: String?

                if (input.method == LoanMethod.FIXED_FEE) {
                    annualRateDecimal = null
                    if (input.fixedFeeUseRate) {
                        fixedFeePerPeriodMinor = null
                        fixedFeeRateDecimal = AssetsFormatters.parseOptionalRatePercentToCanonicalDecimal(
                            input.fixedFeeRatePercentText,
                            "每期手续费率",
                        )
                    } else {
                        fixedFeeRateDecimal = null
                        val trimmedFee = input.fixedFeePerPeriodText.trim()
                        fixedFeePerPeriodMinor = if (trimmedFee.isEmpty()) {
                            0L
                        } else {
                            val feeMoney = runCatching {
                                Money.fromDecimalString(trimmedFee, input.currency)
                            }.getOrElse {
                                throw IllegalArgumentException("每期手续费格式无效")
                            }
                            require(!feeMoney.isNegative) { "每期手续费不能为负数" }
                            feeMoney.amountMinor
                        }
                    }
                } else {
                    annualRateDecimal = AssetsFormatters.parseOptionalRatePercentToCanonicalDecimal(
                        input.annualRatePercentText,
                        "年利率",
                    )
                    fixedFeePerPeriodMinor = null
                    fixedFeeRateDecimal = null
                }

                val created = loanRepository.createLoanPlan(
                    CreateLoanPlanRequest(
                        name = trimmedName,
                        method = input.method,
                        currency = input.currency,
                        totalPrincipalMinor = principal.amountMinor,
                        totalPeriods = totalPeriods,
                        annualRateDecimal = annualRateDecimal,
                        fixedFeePerPeriodMinor = fixedFeePerPeriodMinor,
                        fixedFeeRateDecimal = fixedFeeRateDecimal,
                        firstDueAt = firstDueAt,
                        monthlyRepayDay = monthlyRepayDay,
                        includeInNetWorth = input.includeInNetWorth,
                        paymentAccountId = paymentAccountId,
                        interestExpenseAccountId = input.interestExpenseAccountId,
                        autoPost = input.autoPost,
                        remindDaysBefore = remindDays,
                        note = input.note.trim().takeIf { it.isNotEmpty() },
                        nowEpochMs = clock.nowEpochMs(),
                    ),
                )
                created.plan.id
            }

            runtimeFlow.update {
                it.copy(
                    isCreatingLoan = false,
                    userMessage = result.fold(
                        onSuccess = { "已创建分期贷款「${input.name.trim()}」并生成还款计划表" },
                        onFailure = { error -> error.message ?: "创建分期贷款失败" },
                    ),
                )
            }
            onResult(result)
        }
    }

    private fun buildRefreshNotice(
        report: MarketSyncReport,
        isManual: Boolean,
    ): AssetsRefreshNotice? {
        val fxStatus = report.fxSummary.status
        val quoteSummary = report.quoteSummary
        val quoteStatus = quoteSummary.status

        return when {
            quoteStatus == MarketSyncStatus.PARTIAL_SUCCESS -> AssetsRefreshNotice(
                message = "部分行情拉取失败（${quoteSummary.failures.size} 项），已保留上次缓存净值",
                tone = AssetsNoticeTone.WARNING,
            )
            quoteStatus == MarketSyncStatus.FAILED_CACHE_RETAINED -> AssetsRefreshNotice(
                message = "行情拉取失败，已保留上次缓存净值（可在详情页手动覆写）",
                tone = AssetsNoticeTone.WARNING,
            )
            fxStatus == MarketSyncStatus.FAILED_CACHE_RETAINED && isManual -> AssetsRefreshNotice(
                message = "汇率刷新失败，已保留上次缓存汇率",
                tone = AssetsNoticeTone.WARNING,
            )
            fxStatus == MarketSyncStatus.FAILED_CACHE_RETAINED && !isManual && quoteStatus != MarketSyncStatus.UPDATED ->
                AssetsRefreshNotice(
                    message = "网络不可用，当前展示上次缓存汇率与净值",
                    tone = AssetsNoticeTone.WARNING,
                )
            isManual && (fxStatus == MarketSyncStatus.UPDATED || quoteStatus == MarketSyncStatus.UPDATED) -> {
                val updatedQuotes = quoteSummary.updatedAssetIds.size
                val msg = if (updatedQuotes > 0) {
                    "已刷新汇率与 $updatedQuotes 项持仓行情"
                } else {
                    "汇率已更新至最新"
                }
                AssetsRefreshNotice(message = msg, tone = AssetsNoticeTone.INFO)
            }
            isManual && quoteStatus == MarketSyncStatus.NO_TARGETS -> AssetsRefreshNotice(
                message = if (fxStatus == MarketSyncStatus.UPDATED) "汇率已刷新，当前暂无持仓标的" else "汇率已是最新",
                tone = AssetsNoticeTone.INFO,
            )
            isManual && quoteStatus == MarketSyncStatus.SKIPPED_ONLY -> AssetsRefreshNotice(
                message = "当前持仓均为本地计息或手动估值标的",
                tone = AssetsNoticeTone.INFO,
            )
            else -> null
        }
    }

    companion object {
        val FUNDING_CASH_SUB_TYPES: Set<AccountSubType> = setOf(
            AccountSubType.CASH,
            AccountSubType.DEBIT_CARD,
            AccountSubType.STORED_VALUE,
            AccountSubType.INVEST_CASH,
        )
    }
}
