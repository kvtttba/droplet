package io.github.kvtttba.droplet.feature.assets

import io.github.kvtttba.droplet.core.domain.PrivacyAmountFormatter
import io.github.kvtttba.droplet.core.domain.investment.BankWealthValuationMode
import io.github.kvtttba.droplet.core.domain.investment.FixedDepositCalculator
import io.github.kvtttba.droplet.core.domain.investment.FixedDepositStatus
import io.github.kvtttba.droplet.core.domain.investment.PositionValuationSource
import io.github.kvtttba.droplet.core.model.AssetClass
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.DepositInterestMode
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.LoanMethod
import io.github.kvtttba.droplet.core.model.LoanPlan
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.UnitPrice
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object AssetsFormatters {

    private val HUNDRED = BigDecimal("100")
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val fullDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val isoDateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun assetClassLabel(assetClass: AssetClass): String = when (assetClass) {
        AssetClass.CN_STOCK -> "A股"
        AssetClass.CN_FUND -> "公募基金"
        AssetClass.ETF -> "ETF"
        AssetClass.HK_STOCK -> "港股"
        AssetClass.US_STOCK -> "美股"
        AssetClass.GOLD -> "黄金"
        AssetClass.CRYPTO -> "加密"
        AssetClass.FIXED_DEPOSIT -> "定期存款"
        AssetClass.BANK_WEALTH -> "银行理财"
    }

    fun assetClassDefaultCurrency(assetClass: AssetClass): Currency = when (assetClass) {
        AssetClass.HK_STOCK -> Currency.HKD
        AssetClass.US_STOCK, AssetClass.CRYPTO -> Currency.USD
        else -> Currency.CNY
    }

    fun assetClassSymbolHint(assetClass: AssetClass): String = when (assetClass) {
        AssetClass.CN_STOCK -> "例如 600519 或 000001"
        AssetClass.CN_FUND -> "例如 110011"
        AssetClass.ETF -> "例如 510300"
        AssetClass.HK_STOCK -> "例如 00700"
        AssetClass.US_STOCK -> "例如 AAPL / NVDA"
        AssetClass.GOLD -> "国内 AU9999 / 国际 XAU"
        AssetClass.CRYPTO -> "例如 BTC / ETH / USDT"
        AssetClass.FIXED_DEPOSIT -> "例如 CMB-1Y（可留空自动生成）"
        AssetClass.BANK_WEALTH -> "公开代码或留空使用手动估值"
    }

    fun assetClassColorHex(assetClass: AssetClass): String = when (assetClass) {
        AssetClass.CN_STOCK -> "#D84315"
        AssetClass.CN_FUND -> "#00897B"
        AssetClass.ETF -> "#006970"
        AssetClass.HK_STOCK -> "#1565C0"
        AssetClass.US_STOCK -> "#3949AB"
        AssetClass.GOLD -> "#B36B00"
        AssetClass.CRYPTO -> "#6A1B9A"
        AssetClass.FIXED_DEPOSIT -> "#2E7D32"
        AssetClass.BANK_WEALTH -> "#00695C"
    }

    fun depositInterestModeLabel(mode: DepositInterestMode): String = when (mode) {
        DepositInterestMode.SIMPLE_AT_MATURITY -> "到期一次性还本付息"
        DepositInterestMode.COMPOUND_ANNUAL -> "按年复利"
        DepositInterestMode.MONTHLY_PAYOUT -> "按月付息"
    }

    fun fixedDepositStatusLabel(status: FixedDepositStatus): String = when (status) {
        FixedDepositStatus.NOT_STARTED -> "未起息"
        FixedDepositStatus.ACCRUING -> "计息中"
        FixedDepositStatus.MATURED -> "已到期"
    }

    fun valuationSourceBadge(
        source: PositionValuationSource,
        bankWealthMode: BankWealthValuationMode? = null,
    ): String = when {
        bankWealthMode == BankWealthValuationMode.MANUAL_NAV_OVERRIDE -> "手动净值"
        bankWealthMode == BankWealthValuationMode.PUBLIC_NAV -> "公开净值"
        source == PositionValuationSource.MARKET_QUOTE -> "实时行情"
        source == PositionValuationSource.MANUAL_OVERRIDE -> "手动覆写"
        source == PositionValuationSource.FIXED_DEPOSIT_ACCRUAL -> "本地计息"
        source == PositionValuationSource.COST_BASIS_FALLBACK -> "成本估值"
        else -> "已清仓"
    }

    fun formatPercentSigned(pctDecimal: String?, fallback: String = "--"): String {
        if (pctDecimal.isNullOrBlank()) return fallback
        val bd = runCatching { BigDecimal(pctDecimal) }.getOrNull() ?: return fallback
        val scaled = bd.setScale(2, RoundingMode.HALF_UP)
        val sign = when {
            scaled.compareTo(BigDecimal.ZERO) > 0 -> "+"
            else -> ""
        }
        return "$sign${scaled.toPlainString()}%"
    }

    fun formatSharePercent(pctDecimal: String): String {
        val bd = runCatching { BigDecimal(pctDecimal) }.getOrNull() ?: BigDecimal.ZERO
        val scaled = bd.setScale(1, RoundingMode.HALF_UP)
        val plain = if (scaled.stripTrailingZeros().scale() <= 0) {
            scaled.setScale(0, RoundingMode.HALF_UP).toPlainString()
        } else {
            scaled.toPlainString()
        }
        return "$plain%"
    }

    fun formatAnnualRatePercent(rateDecimal: String?): String {
        if (rateDecimal.isNullOrBlank()) return "--"
        val bd = runCatching { BigDecimal(rateDecimal) }.getOrNull() ?: return "--"
        val pct = bd.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP)
        return "${pct.toPlainString()}%"
    }

    /**
     * Formats unit price / NAV cleanly using BigDecimal only.
     * Shows at least 2 decimals and preserves up to 8 decimals for funds/crypto.
     */
    fun formatUnitPrice(
        price: UnitPrice?,
        currency: Currency,
        hidden: Boolean = false,
    ): String {
        if (hidden) return "${currency.symbol} ${PrivacyAmountFormatter.MASKED_PLACEHOLDER}"
        if (price == null) return "--"
        val bd = price.toBigDecimal().stripTrailingZeros()
        val displayScale = bd.scale().coerceIn(2, UnitPrice.MAX_SCALE)
        val pattern = buildString {
            append("#,##0.")
            repeat(displayScale) { append('0') }
        }
        val df = DecimalFormat(pattern, DecimalFormatSymbols(Locale.US))
        return "${currency.symbol}${df.format(bd.setScale(displayScale, RoundingMode.HALF_UP))}"
    }

    /**
     * Formats holding share quantity cleanly, preserving up to 8 decimal places (e.g. BTC/ETH)
     * without scientific notation or unnecessary trailing zeros.
     */
    fun formatQuantity(
        quantity: Quantity,
        assetClass: AssetClass,
        hidden: Boolean = false,
    ): String {
        if (hidden) return PrivacyAmountFormatter.MASKED_PLACEHOLDER
        val bd = quantity.toBigDecimal().stripTrailingZeros()
        if (bd.compareTo(BigDecimal.ZERO) == 0) return "0"
        val raw = if (bd.scale() <= 0) {
            bd.setScale(0, RoundingMode.HALF_UP).toPlainString()
        } else {
            bd.toPlainString()
        }
        return if (assetClass == AssetClass.CRYPTO) {
            raw
        } else {
            raw
        }
    }

    fun formatQuantityWithUnit(
        quantity: Quantity,
        assetClass: AssetClass,
        symbol: String,
        hidden: Boolean = false,
    ): String {
        val qtyText = formatQuantity(quantity, assetClass, hidden)
        if (hidden) return qtyText
        val unit = when (assetClass) {
            AssetClass.CRYPTO -> symbol.uppercase(Locale.US)
            AssetClass.CN_STOCK, AssetClass.HK_STOCK, AssetClass.US_STOCK, AssetClass.ETF -> "股"
            AssetClass.GOLD -> if (symbol.equals("XAU", ignoreCase = true)) "盎司" else "克"
            AssetClass.FIXED_DEPOSIT -> "元"
            AssetClass.CN_FUND, AssetClass.BANK_WEALTH -> "份"
        }
        return "$qtyText $unit"
    }

    fun formatShortTimestamp(
        epochMs: Long?,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (epochMs == null || epochMs <= 0L) return "尚未更新"
        return Instant.ofEpochMilli(epochMs).atZone(zoneId).format(dateTimeFormatter)
    }

    fun formatFullTimestamp(
        epochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = Instant.ofEpochMilli(epochMs).atZone(zoneId).format(fullDateTimeFormatter)

    fun formatIsoDate(
        epochMs: Long,
        zoneId: ZoneId = FixedDepositCalculator.DEFAULT_ZONE_ID,
    ): String = Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDate().format(isoDateFormatter)

    fun parseIsoDateToEpochMs(
        isoDate: String,
        zoneId: ZoneId = FixedDepositCalculator.DEFAULT_ZONE_ID,
    ): Long {
        val localDate = LocalDate.parse(isoDate.trim(), isoDateFormatter)
        return localDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    /**
     * Converts a user-entered annual percentage string (e.g. `"2.45"` for 2.45%)
     * into a canonical 8-decimal ratio string (`"0.0245"`).
     */
    fun parseAnnualRatePercentToCanonicalDecimal(percentText: String): String {
        val cleaned = percentText.trim().removeSuffix("%").trim()
        require(cleaned.isNotEmpty()) { "请输入年利率" }
        val pctBd = BigDecimal(cleaned)
        require(pctBd.compareTo(BigDecimal.ZERO) >= 0) { "年利率不能为负数" }
        require(pctBd.compareTo(BigDecimal("1000")) <= 0) { "年利率数值过大，请检查输入" }
        val ratio = pctBd.divide(HUNDRED, ExchangeRateSnapshot.SCALE, RoundingMode.HALF_UP)
        return UnitPrice.of(ratio).canonical
    }

    fun computeBarWeight(sharePctDecimal: String): Float {
        val bd = runCatching { BigDecimal(sharePctDecimal) }.getOrNull() ?: return 0f
        if (bd.compareTo(BigDecimal.ZERO) <= 0) return 0f
        return bd.toFloat().coerceAtLeast(0.01f)
    }

    fun loanMethodLabel(method: LoanMethod): String = when (method) {
        LoanMethod.EQUAL_INSTALLMENT -> "等额本息"
        LoanMethod.EQUAL_PRINCIPAL -> "等额本金"
        LoanMethod.FIXED_FEE -> "固定手续费"
    }

    fun loanMethodDescription(method: LoanMethod): String = when (method) {
        LoanMethod.EQUAL_INSTALLMENT -> "每期总还款额固定，适合房贷/车贷/消费贷"
        LoanMethod.EQUAL_PRINCIPAL -> "每期本金固定、利息逐期递减，总利息更省"
        LoanMethod.FIXED_FEE -> "本金按期均摊，每期收取固定金额或固定费率手续费"
    }

    fun formatLoanRateOrFeeSummary(
        plan: LoanPlan,
        hidden: Boolean = false,
    ): String = when (plan.method) {
        LoanMethod.EQUAL_INSTALLMENT,
        LoanMethod.EQUAL_PRINCIPAL,
        -> {
            val rateText = plan.annualRateDecimal
                ?.let { runCatching { BigDecimal(it) }.getOrNull() }
                ?.takeIf { it.compareTo(BigDecimal.ZERO) > 0 }
                ?.let { formatAnnualRatePercent(plan.annualRateDecimal) }
            if (rateText != null) "年利率 $rateText" else "免息 0.00%"
        }
        LoanMethod.FIXED_FEE -> when {
            plan.fixedFeePerPeriodMinor != null && plan.fixedFeePerPeriodMinor!! > 0L -> {
                val formatted = PrivacyAmountFormatter.format(
                    money = Money(plan.fixedFeePerPeriodMinor!!, plan.currency),
                    hidden = hidden,
                )
                "每期手续费 $formatted"
            }
            !plan.fixedFeeRateDecimal.isNullOrBlank() &&
                runCatching { BigDecimal(plan.fixedFeeRateDecimal!!) }.getOrNull()
                    ?.compareTo(BigDecimal.ZERO) == 1 ->
                "每期费率 ${formatAnnualRatePercent(plan.fixedFeeRateDecimal)}"
            else -> "免手续费分期"
        }
    }

    fun formatLocalDateIso(date: LocalDate): String = date.format(isoDateFormatter)

    fun formatLocalDateMonthDay(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("MM-dd"))

    /**
     * Converts an optional percentage string (e.g. `"4.8"`, `"0.6"`, `"0"`) into a canonical
     * 8-decimal ratio string (`"0.048"`, `"0.006"`, `"0"`). Returns null when blank.
     */
    fun parseOptionalRatePercentToCanonicalDecimal(
        percentText: String,
        label: String,
    ): String? {
        val cleaned = percentText.trim().removeSuffix("%").trim()
        if (cleaned.isEmpty()) return null
        val pctBd = runCatching { BigDecimal(cleaned) }.getOrElse {
            throw IllegalArgumentException("${label}格式无效")
        }
        require(pctBd.compareTo(BigDecimal.ZERO) >= 0) { "${label}不能为负数" }
        require(pctBd.compareTo(BigDecimal("1000")) <= 0) { "${label}数值过大，请检查输入" }
        val ratio = pctBd.divide(HUNDRED, ExchangeRateSnapshot.SCALE, RoundingMode.HALF_UP)
        return UnitPrice.of(ratio).canonical
    }
}
