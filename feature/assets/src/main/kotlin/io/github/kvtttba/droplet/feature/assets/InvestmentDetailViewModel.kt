package io.github.kvtttba.droplet.feature.assets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.FixedDepositInterestPayoutRequest
import io.github.kvtttba.droplet.core.database.repository.FixedDepositSettlementRequest
import io.github.kvtttba.droplet.core.database.repository.InvestmentBuyRequest
import io.github.kvtttba.droplet.core.database.repository.InvestmentDividendRequest
import io.github.kvtttba.droplet.core.database.repository.InvestmentRepository
import io.github.kvtttba.droplet.core.database.repository.InvestmentSellRequest
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.MarketDataRepository
import io.github.kvtttba.droplet.core.database.repository.MarketSyncStatus
import io.github.kvtttba.droplet.core.domain.investment.OversellPositionException
import io.github.kvtttba.droplet.core.domain.investment.PortfolioPositionEngine
import io.github.kvtttba.droplet.core.domain.investment.PortfolioPositionValuation
import io.github.kvtttba.droplet.core.domain.investment.SellCostBasisPreview
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.Transaction
import io.github.kvtttba.droplet.core.model.UnitPrice
import io.github.kvtttba.droplet.core.network.NetworkClock
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BankWealthManualInputMode(val label: String) {
    UNIT_PRICE("最新净值"),
    PROFIT_AMOUNT("最新收益金额"),
    TOTAL_VALUE("最新总市值"),
}

enum class InvestmentHistoryKind(val label: String) {
    BUY("买入"),
    DCA("定投"),
    SELL("卖出"),
    DIVIDEND("现金分红"),
    DEPOSIT_OPEN("定期存入"),
    DEPOSIT_SETTLE("定存结息/支取"),
    DEPOSIT_PAYOUT("按期付息"),
}

data class InvestmentTransactionItem(
    val id: String,
    val occurredAt: Long,
    val dateTimeLabel: String,
    val kind: InvestmentHistoryKind,
    val quantity: Quantity?,
    val unitPrice: UnitPrice?,
    val amount: Money,
    val fee: Money?,
    val cashAccountName: String?,
    val note: String?,
)

data class SellPreviewComputation(
    val preview: SellCostBasisPreview? = null,
    val oversellError: String? = null,
    val inputError: String? = null,
) {
    val canSubmit: Boolean
        get() = preview != null && oversellError == null && inputError == null
}

data class InvestmentDetailUiState(
    val assetId: String = "",
    val position: PortfolioPositionValuation? = null,
    val transactions: List<InvestmentTransactionItem> = emptyList(),
    val matchingCashAccounts: List<Account> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshingQuote: Boolean = false,
    val isSubmitting: Boolean = false,
    val userMessage: String? = null,
)

private data class DetailRuntimeState(
    val isRefreshingQuote: Boolean = false,
    val isSubmitting: Boolean = false,
    val userMessage: String? = null,
)

@HiltViewModel
class InvestmentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val investmentRepository: InvestmentRepository,
    private val marketDataRepository: MarketDataRepository,
    private val ledgerRepository: LedgerRepository,
    private val clock: NetworkClock,
) : ViewModel() {

    val assetId: String = checkNotNull(savedStateHandle.get<String>(NAV_ARG_ASSET_ID)) {
        "Missing navigation argument '$NAV_ARG_ASSET_ID'"
    }

    private val runtimeFlow = MutableStateFlow(DetailRuntimeState())

    val uiState: StateFlow<InvestmentDetailUiState> = combine(
        investmentRepository.observePosition(assetId = assetId, baseCurrency = Currency.DEFAULT),
        investmentRepository.observeAssetTransactions(assetId = assetId),
        ledgerRepository.observeAllAccounts(includeArchived = true, includeSystem = true),
        runtimeFlow,
    ) { position, transactions, allAccounts, runtime ->
        val accountsById = allAccounts.associateBy { it.id }
        val matchingCashAccounts = if (position != null) {
            allAccounts.filter { acct ->
                !acct.isArchived &&
                    !acct.isSystem &&
                    acct.currency == position.asset.currency &&
                    acct.subType in AssetsViewModel.FUNDING_CASH_SUB_TYPES
            }
        } else {
            emptyList()
        }
        val mappedHistory = if (position != null) {
            transactions
                .sortedWith(
                    compareByDescending<Transaction> { it.occurredAt }
                        .thenByDescending { it.createdAt },
                )
                .map { tx -> mapTransactionItem(tx, position, accountsById) }
        } else {
            emptyList()
        }
        InvestmentDetailUiState(
            assetId = assetId,
            position = position,
            transactions = mappedHistory,
            matchingCashAccounts = matchingCashAccounts,
            isLoading = false,
            isRefreshingQuote = runtime.isRefreshingQuote,
            isSubmitting = runtime.isSubmitting,
            userMessage = runtime.userMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = InvestmentDetailUiState(assetId = assetId),
    )

    fun clearUserMessage() {
        runtimeFlow.update { it.copy(userMessage = null) }
    }

    fun refreshQuote() {
        if (runtimeFlow.value.isRefreshingQuote) return
        viewModelScope.launch {
            runtimeFlow.update { it.copy(isRefreshingQuote = true) }
            runCatching {
                marketDataRepository.refreshMarketQuotes(
                    assetIds = setOf(assetId),
                    forceRefresh = true,
                    overwriteManualOverrides = false,
                )
            }.onSuccess { summary ->
                val msg = when (summary.status) {
                    MarketSyncStatus.UPDATED -> "已更新最新行情"
                    MarketSyncStatus.SKIPPED_ONLY -> {
                        if (assetId in summary.preservedManualOverrideAssetIds) {
                            "当前使用手动覆写价，清除覆写后可恢复自动行情"
                        } else {
                            "该标的为本地计息或无公开行情源"
                        }
                    }
                    MarketSyncStatus.FAILED_CACHE_RETAINED -> "行情拉取失败，已保留上次缓存净值"
                    else -> "行情状态已同步"
                }
                runtimeFlow.update { it.copy(isRefreshingQuote = false, userMessage = msg) }
            }.onFailure {
                runtimeFlow.update {
                    it.copy(isRefreshingQuote = false, userMessage = "行情拉取失败，已保留上次缓存净值")
                }
            }
        }
    }

    /**
     * Pure deterministic real-time sell cost-basis preview & oversell guard (zero floating-point).
     */
    fun computeSellPreview(
        quantityText: String,
        unitPriceText: String,
        grossProceedsText: String = "",
    ): SellPreviewComputation {
        val position = uiState.value.position
            ?: return SellPreviewComputation(inputError = "正在加载持仓信息")
        return computeSellPreviewForPosition(
            position = position,
            quantityText = quantityText,
            unitPriceText = unitPriceText,
            grossProceedsText = grossProceedsText,
        )
    }

    fun recordBuy(
        isDca: Boolean,
        cashAccountId: String?,
        quantityText: String,
        unitPriceText: String,
        totalAmountText: String,
        feeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        val position = uiState.value.position
        if (position == null) {
            onComplete("标的信息尚未就绪")
            return
        }
        runMutation(onComplete) {
            val resolvedCashId = cashAccountId
                ?: throw IllegalArgumentException("请选择付款资金账户")
            val qty = parsePositiveQuantity(quantityText, "买入份额")
            val price = parsePositiveUnitPrice(unitPriceText, "成交单价")
            val totalMoney = parseOptionalPositiveMoney(totalAmountText, position.asset.currency, "成交金额")
            val feeMoney = parseOptionalPositiveMoney(feeText, position.asset.currency, "手续费")
            val request = InvestmentBuyRequest(
                assetId = assetId,
                cashAccountId = resolvedCashId,
                quantity = qty,
                unitPrice = price,
                totalAmount = totalMoney,
                fee = feeMoney,
                isDca = isDca,
                occurredAt = clock.nowEpochMs(),
                note = note.trim().takeIf { it.isNotEmpty() },
            )
            if (isDca) {
                investmentRepository.recordDcaBuy(request)
                "定投记录已入账"
            } else {
                investmentRepository.recordBuy(request)
                "买入记录已入账"
            }
        }
    }

    fun recordSell(
        cashAccountId: String?,
        quantityText: String,
        unitPriceText: String,
        grossProceedsText: String,
        feeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        val position = uiState.value.position
        if (position == null) {
            onComplete("标的信息尚未就绪")
            return
        }
        runMutation(onComplete) {
            val resolvedCashId = cashAccountId
                ?: throw IllegalArgumentException("请选择收款资金账户")
            val qty = parsePositiveQuantity(quantityText, "卖出份额")
            val price = parsePositiveUnitPrice(unitPriceText, "卖出单价")
            val grossProceeds = parseOptionalPositiveMoney(grossProceedsText, position.asset.currency, "卖出总额")
            val feeMoney = parseOptionalPositiveMoney(feeText, position.asset.currency, "手续费")
            try {
                investmentRepository.recordSell(
                    InvestmentSellRequest(
                        assetId = assetId,
                        cashAccountId = resolvedCashId,
                        quantity = qty,
                        unitPrice = price,
                        grossProceeds = grossProceeds,
                        fee = feeMoney,
                        occurredAt = clock.nowEpochMs(),
                        note = note.trim().takeIf { it.isNotEmpty() },
                    ),
                )
            } catch (e: OversellPositionException) {
                throw IllegalArgumentException(
                    "超卖拦截：当前可用份额 ${e.availableQuantity.canonical}，不可卖出 ${e.attemptedSellQuantity.canonical}",
                )
            }
            "卖出记录已入账并结转加权成本"
        }
    }

    fun recordDividend(
        cashAccountId: String?,
        amountText: String,
        taxOrFeeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        val position = uiState.value.position
        if (position == null) {
            onComplete("标的信息尚未就绪")
            return
        }
        runMutation(onComplete) {
            val resolvedCashId = cashAccountId
                ?: throw IllegalArgumentException("请选择收款资金账户")
            val amount = parseRequiredPositiveMoney(amountText, position.asset.currency, "分红金额")
            val taxOrFee = parseOptionalPositiveMoney(taxOrFeeText, position.asset.currency, "税费")
            investmentRepository.recordDividend(
                InvestmentDividendRequest(
                    assetId = assetId,
                    cashAccountId = resolvedCashId,
                    amount = amount,
                    taxOrFee = taxOrFee,
                    occurredAt = clock.nowEpochMs(),
                    note = note.trim().takeIf { it.isNotEmpty() },
                ),
            )
            "现金分红已入账"
        }
    }

    fun recordFixedDepositSettlement(
        cashAccountId: String?,
        isFullSettlement: Boolean,
        withdrawnPrincipalText: String,
        actualInterestText: String,
        penaltyFeeText: String,
        archiveOnFullSettlement: Boolean,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        val position = uiState.value.position
        if (position == null) {
            onComplete("标的信息尚未就绪")
            return
        }
        runMutation(onComplete) {
            val resolvedCashId = cashAccountId
                ?: throw IllegalArgumentException("请选择回款资金账户")
            val withdrawnPrincipal = if (isFullSettlement) {
                null
            } else {
                parseRequiredPositiveMoney(withdrawnPrincipalText, position.asset.currency, "支取本金")
            }
            val actualInterest = if (actualInterestText.trim().isEmpty()) {
                null
            } else {
                parseNonNegativeMoney(actualInterestText, position.asset.currency, "实结利息")
            }
            val penaltyFee = parseOptionalPositiveMoney(penaltyFeeText, position.asset.currency, "扣费/违约金")
            try {
                investmentRepository.recordFixedDepositSettlement(
                    FixedDepositSettlementRequest(
                        assetId = assetId,
                        cashAccountId = resolvedCashId,
                        withdrawnPrincipal = withdrawnPrincipal,
                        actualInterestAmount = actualInterest,
                        penaltyOrTaxFee = penaltyFee,
                        occurredAt = clock.nowEpochMs(),
                        note = note.trim().takeIf { it.isNotEmpty() },
                        archiveHoldingAccountOnFullSettlement = archiveOnFullSettlement,
                    ),
                )
            } catch (e: OversellPositionException) {
                throw IllegalArgumentException(
                    "支取本金超限：当前剩余本金 ${e.availableQuantity.canonical}",
                )
            }
            if (isFullSettlement) "定期存款到期结息已入账" else "定期存款提前支取已入账"
        }
    }

    fun recordFixedDepositInterestPayout(
        cashAccountId: String?,
        interestAmountText: String,
        taxOrFeeText: String,
        note: String,
        onComplete: (String?) -> Unit,
    ) {
        val position = uiState.value.position
        if (position == null) {
            onComplete("标的信息尚未就绪")
            return
        }
        runMutation(onComplete) {
            val resolvedCashId = cashAccountId
                ?: throw IllegalArgumentException("请选择收息资金账户")
            val interestAmount = parseRequiredPositiveMoney(interestAmountText, position.asset.currency, "付息金额")
            val taxOrFee = parseOptionalPositiveMoney(taxOrFeeText, position.asset.currency, "税费")
            investmentRepository.recordFixedDepositInterestPayout(
                FixedDepositInterestPayoutRequest(
                    assetId = assetId,
                    cashAccountId = resolvedCashId,
                    interestAmount = interestAmount,
                    taxOrFee = taxOrFee,
                    occurredAt = clock.nowEpochMs(),
                    note = note.trim().takeIf { it.isNotEmpty() },
                ),
            )
            "定期按期付息已入账"
        }
    }

    fun manualOverrideQuote(
        priceText: String,
        prevCloseText: String,
        onComplete: (String?) -> Unit,
    ) {
        val position = uiState.value.position
        if (position == null) {
            onComplete("标的信息尚未就绪")
            return
        }
        runMutation(onComplete) {
            val price = parsePositiveUnitPrice(priceText, "手动覆写单价")
            val prevClose = if (prevCloseText.trim().isEmpty()) {
                null
            } else {
                parsePositiveUnitPrice(prevCloseText, "昨收价").canonical
            }
            marketDataRepository.manualOverrideQuote(
                assetId = assetId,
                priceDecimal = price.canonical,
                currency = position.asset.currency,
                prevCloseDecimal = prevClose,
                nowEpochMs = clock.nowEpochMs(),
            )
            "已保存手动覆写单价 ${AssetsFormatters.formatUnitPrice(price, position.asset.currency)}"
        }
    }

    fun clearManualOverride() {
        runMutation({}) {
            marketDataRepository.clearManualOverride(
                assetId = assetId,
                refreshImmediately = true,
            )
            "已清除手动覆写价"
        }
    }

    fun updateBankWealthValuation(
        mode: BankWealthManualInputMode,
        valueText: String,
        onComplete: (String?) -> Unit,
    ) {
        val position = uiState.value.position
        if (position == null) {
            onComplete("标的信息尚未就绪")
            return
        }
        runMutation(onComplete) {
            val trimmed = valueText.trim()
            require(trimmed.isNotEmpty()) { "请输入更新数值" }
            val now = clock.nowEpochMs()
            when (mode) {
                BankWealthManualInputMode.UNIT_PRICE -> {
                    val nav = parsePositiveUnitPrice(trimmed, "最新单位净值")
                    investmentRepository.updateBankWealthByUnitPrice(
                        assetId = assetId,
                        manualUnitPrice = nav,
                        updatedAtEpochMs = now,
                    )
                    "已更新银行理财净值"
                }
                BankWealthManualInputMode.PROFIT_AMOUNT -> {
                    val profit = runCatching {
                        Money.fromDecimalString(trimmed, position.asset.currency)
                    }.getOrElse {
                        throw IllegalArgumentException("收益金额格式无效（支持正数或负收益）")
                    }
                    investmentRepository.updateBankWealthByProfitAmount(
                        assetId = assetId,
                        latestProfitAmount = profit,
                        updatedAtEpochMs = now,
                    )
                    "已按最新收益金额反推净值并更新"
                }
                BankWealthManualInputMode.TOTAL_VALUE -> {
                    val total = parseRequiredPositiveMoney(trimmed, position.asset.currency, "最新总市值")
                    investmentRepository.updateBankWealthByTotalValue(
                        assetId = assetId,
                        currentTotalValue = total,
                        updatedAtEpochMs = now,
                    )
                    "已按最新总市值反推净值并更新"
                }
            }
        }
    }

    private fun runMutation(
        onComplete: (String?) -> Unit,
        block: suspend () -> String,
    ) {
        if (runtimeFlow.value.isSubmitting) return
        viewModelScope.launch {
            runtimeFlow.update { it.copy(isSubmitting = true) }
            val result = runCatching { block() }
            runtimeFlow.update {
                it.copy(
                    isSubmitting = false,
                    userMessage = result.fold(
                        onSuccess = { msg -> msg },
                        onFailure = { err -> err.message ?: "操作失败" },
                    ),
                )
            }
            onComplete(result.exceptionOrNull()?.message)
        }
    }

    private fun mapTransactionItem(
        tx: Transaction,
        position: PortfolioPositionValuation,
        accountsById: Map<String, Account>,
    ): InvestmentTransactionItem {
        val asset = position.asset
        val business = tx.businessPostings
        val holdingPosting = business.firstOrNull {
            it.investmentAssetId == asset.id && it.accountId == asset.accountId && it.quantity != null
        }
        val cashPosting = business.firstOrNull {
            it.accountId != asset.accountId &&
                it.investmentAssetId == null &&
                accountsById[it.accountId]?.type == AccountType.ASSET
        }
        val feeMinor = business
            .filter {
                it.accountId != asset.accountId &&
                    it.investmentAssetId == null &&
                    accountsById[it.accountId]?.type == AccountType.EXPENSE &&
                    it.currency == asset.currency &&
                    it.amountMinor > 0L
            }
            .fold(0L) { acc, p -> Math.addExact(acc, p.amountMinor) }

        val kind = when {
            holdingPosting != null && holdingPosting.quantity!!.isPositive -> {
                when {
                    asset.assetClass == AssetClass.FIXED_DEPOSIT -> InvestmentHistoryKind.DEPOSIT_OPEN
                    tx.note?.contains("定投") == true -> InvestmentHistoryKind.DCA
                    else -> InvestmentHistoryKind.BUY
                }
            }
            holdingPosting != null && holdingPosting.quantity!!.isNegative -> {
                if (asset.assetClass == AssetClass.FIXED_DEPOSIT) {
                    InvestmentHistoryKind.DEPOSIT_SETTLE
                } else {
                    InvestmentHistoryKind.SELL
                }
            }
            asset.assetClass == AssetClass.FIXED_DEPOSIT -> InvestmentHistoryKind.DEPOSIT_PAYOUT
            else -> InvestmentHistoryKind.DIVIDEND
        }

        val displayAmount = when {
            cashPosting != null && cashPosting.currency == asset.currency ->
                Money(cashPosting.amountMinor, asset.currency).abs()
            holdingPosting != null -> Money(holdingPosting.amountMinor, asset.currency).abs()
            else -> {
                val incomeMinor = business
                    .filter { it.investmentAssetId == asset.id && it.amountMinor < 0L }
                    .fold(0L) { acc, p -> Math.addExact(acc, Math.negateExact(p.amountMinor)) }
                Money(incomeMinor, asset.currency)
            }
        }

        return InvestmentTransactionItem(
            id = tx.id,
            occurredAt = tx.occurredAt,
            dateTimeLabel = AssetsFormatters.formatFullTimestamp(tx.occurredAt),
            kind = kind,
            quantity = holdingPosting?.quantity?.abs(),
            unitPrice = holdingPosting?.unitPrice,
            amount = displayAmount,
            fee = if (feeMinor > 0L) Money(feeMinor, asset.currency) else null,
            cashAccountName = cashPosting?.let { accountsById[it.accountId]?.name },
            note = tx.note,
        )
    }

    companion object {
        const val NAV_ARG_ASSET_ID: String = "assetId"

        fun computeSellPreviewForPosition(
            position: PortfolioPositionValuation,
            quantityText: String,
            unitPriceText: String,
            grossProceedsText: String = "",
        ): SellPreviewComputation {
            val trimmedQty = quantityText.trim()
            val trimmedPrice = unitPriceText.trim()
            if (trimmedQty.isEmpty() || trimmedPrice.isEmpty()) {
                return SellPreviewComputation()
            }
            val qty = runCatching { Quantity.of(trimmedQty) }.getOrNull()
                ?: return SellPreviewComputation(inputError = "卖出份额格式无效")
            if (!qty.isPositive) {
                return SellPreviewComputation(inputError = "卖出份额必须大于 0")
            }
            val price = runCatching { UnitPrice.of(trimmedPrice) }.getOrNull()
                ?: return SellPreviewComputation(inputError = "卖出单价格式无效")
            if (!price.isPositive) {
                return SellPreviewComputation(inputError = "卖出单价必须大于 0")
            }
            val grossProceeds = if (grossProceedsText.trim().isNotEmpty()) {
                val parsed = runCatching {
                    Money.fromDecimalString(grossProceedsText.trim(), position.asset.currency)
                }.getOrNull() ?: return SellPreviewComputation(inputError = "卖出总额格式无效")
                if (!parsed.isPositive) {
                    return SellPreviewComputation(inputError = "卖出总额必须大于 0")
                }
                parsed
            } else {
                null
            }

            return try {
                val preview = PortfolioPositionEngine.previewSellFromState(
                    state = position.ledgerState,
                    sellQuantity = qty,
                    unitPrice = price,
                    grossProceeds = grossProceeds,
                )
                SellPreviewComputation(preview = preview)
            } catch (e: OversellPositionException) {
                SellPreviewComputation(
                    oversellError = "超卖拦截：当前可用份额 ${e.availableQuantity.canonical}，不可卖出 ${e.attemptedSellQuantity.canonical}",
                )
            } catch (e: IllegalArgumentException) {
                SellPreviewComputation(inputError = e.message ?: "无法计算卖出结转预览")
            }
        }

        private fun parsePositiveQuantity(raw: String, fieldLabel: String): Quantity {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "请输入$fieldLabel" }
            val qty = runCatching { Quantity.of(trimmed) }.getOrElse {
                throw IllegalArgumentException("${fieldLabel}格式无效（最多支持 8 位小数）")
            }
            require(qty.isPositive) { "${fieldLabel}必须大于 0" }
            return qty
        }

        private fun parsePositiveUnitPrice(raw: String, fieldLabel: String): UnitPrice {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "请输入$fieldLabel" }
            val price = runCatching { UnitPrice.of(trimmed) }.getOrElse {
                throw IllegalArgumentException("${fieldLabel}格式无效")
            }
            require(price.isPositive) { "${fieldLabel}必须大于 0" }
            return price
        }

        private fun parseRequiredPositiveMoney(raw: String, currency: Currency, fieldLabel: String): Money {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "请输入$fieldLabel" }
            val money = runCatching { Money.fromDecimalString(trimmed, currency) }.getOrElse {
                throw IllegalArgumentException("${fieldLabel}格式无效")
            }
            require(money.isPositive) { "${fieldLabel}必须大于 0" }
            return money
        }

        private fun parseOptionalPositiveMoney(raw: String, currency: Currency, fieldLabel: String): Money? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val bd = runCatching { BigDecimal(trimmed) }.getOrElse {
                throw IllegalArgumentException("${fieldLabel}格式无效")
            }
            if (bd.compareTo(BigDecimal.ZERO) == 0) return null
            val money = Money.fromBigDecimal(bd, currency)
            require(money.isPositive) { "${fieldLabel}不能为负数" }
            return money
        }

        private fun parseNonNegativeMoney(raw: String, currency: Currency, fieldLabel: String): Money {
            val trimmed = raw.trim()
            val money = runCatching { Money.fromDecimalString(trimmed, currency) }.getOrElse {
                throw IllegalArgumentException("${fieldLabel}格式无效")
            }
            require(!money.isNegative) { "${fieldLabel}不能为负数" }
            return money
        }
    }
}
