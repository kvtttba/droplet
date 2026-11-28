package io.github.kvtttba.droplet.core.domain.export

import io.github.kvtttba.droplet.core.domain.stats.StatsTagFilterMode
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Transaction
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

enum class ExportFormat(
    val label: String,
    val shortLabel: String,
    val description: String,
    val extension: String,
    val mimeType: String,
) {
    XLSX(
        label = "Excel 工作簿 (.xlsx)",
        shortLabel = "Excel",
        description = "包含「流水明细」「分类汇总」「账户余额」3 个工作表",
        extension = "xlsx",
        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ),
    CSV(
        label = "CSV 表格 (.csv)",
        shortLabel = "CSV",
        description = "UTF-8 BOM 编码，Excel 直接打开中文不乱码",
        extension = "csv",
        mimeType = "text/csv",
    ),
    BEANCOUNT(
        label = "Beancount 纯文本 (.beancount)",
        shortLabel = "Beancount",
        description = "完整复式科目声明、标签与借贷恒平分录，兼容纯文本记账生态",
        extension = "beancount",
        mimeType = "text/plain",
    ),
}

enum class ExportScope(val label: String) {
    ALL_TIME("全量账本"),
    CURRENT_MONTH("当月"),
    CURRENT_YEAR("当年"),
    CUSTOM_RANGE("自定义区间"),
}

data class LedgerExportRequest(
    val format: ExportFormat = ExportFormat.XLSX,
    val scope: ExportScope = ExportScope.ALL_TIME,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val selectedTagIds: Set<String> = emptySet(),
    val tagFilterMode: StatsTagFilterMode = StatsTagFilterMode.ANY,
    val includeVoided: Boolean = false,
    val baseCurrency: Currency = Currency.CNY,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val generatedAtEpochMs: Long = System.currentTimeMillis(),
)

data class LedgerExportPreview(
    val transactionCount: Int,
    val postingCount: Int,
    val accountCount: Int,
    val totalIncomeBase: Money,
    val totalExpenseBase: Money,
    val suggestedFileName: String,
)

data class LedgerExportArtifact(
    val format: ExportFormat,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
    val transactionCount: Int,
    val postingCount: Int,
    val generatedAtEpochMs: Long,
) {
    val sizeBytes: Long
        get() = bytes.size.toLong()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LedgerExportArtifact) return false
        return format == other.format &&
            fileName == other.fileName &&
            mimeType == other.mimeType &&
            bytes.contentEquals(other.bytes) &&
            transactionCount == other.transactionCount &&
            postingCount == other.postingCount &&
            generatedAtEpochMs == other.generatedAtEpochMs
    }

    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + transactionCount
        result = 31 * result + postingCount
        result = 31 * result + generatedAtEpochMs.hashCode()
        return result
    }
}

internal data class ExportTransactionRow(
    val dateTimeText: String,
    val displayTypeLabel: String,
    val categoryPath: String,
    val accountName: String,
    val amountDecimal: String,
    val currencyCode: String,
    val baseAmountDecimal: String,
    val baseCurrencyCode: String,
    val exchangeRateDecimal: String,
    val payee: String,
    val tagsJoined: String,
    val statusLabel: String,
    val note: String,
    val transactionId: String,
    val originalTransaction: Transaction,
)

internal data class ExportCategorySummaryRow(
    val directionLabel: String,
    val categoryName: String,
    val amountDecimal: String,
    val currencyCode: String,
    val sharePctDecimal: String,
    val transactionCount: Int,
)

internal data class ExportAccountBalanceRow(
    val accountName: String,
    val typeLabel: String,
    val subTypeLabel: String,
    val currencyCode: String,
    val originalBalanceDecimal: String,
    val baseBalanceDecimal: String,
    val baseCurrencyCode: String,
    val includeInNetWorthLabel: String,
    val statusLabel: String,
)

internal object ExportDecimalFormatter {
    /**
     * Zero-floating-point formatting for fixed-point [Money]:
     * displays 2 decimal places when the 3rd & 4th digits are zero, and preserves all 4 digits
     * whenever sub-cent precision exists.
     */
    fun formatMoney(money: Money): String {
        val bd = money.toBigDecimal()
        val stripped = bd.stripTrailingZeros()
        val scale = stripped.scale().coerceIn(2, Money.SCALE)
        return bd.setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }
}
