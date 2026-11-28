package io.github.kvtttba.droplet.core.domain.export

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * RFC-4180 compliant CSV exporter emitting UTF-8 with BOM so Microsoft Excel and WPS
 * open Chinese characters cleanly without mojibake.
 */
internal object CsvLedgerExporter {

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    val HEADERS: List<String> = listOf(
        "日期时间",
        "交易类型",
        "分类",
        "账户",
        "金额",
        "币种",
        "锁定本位币金额",
        "本位币",
        "汇率快照",
        "商户",
        "标签",
        "状态",
        "备注",
        "交易ID",
    )

    fun export(rows: List<ExportTransactionRow>): ByteArray {
        val sb = StringBuilder(rows.size * 128 + 256)
        appendCsvRow(sb, HEADERS)
        for (row in rows) {
            appendCsvRow(
                sb,
                listOf(
                    row.dateTimeText,
                    row.displayTypeLabel,
                    row.categoryPath,
                    row.accountName,
                    row.amountDecimal,
                    row.currencyCode,
                    row.baseAmountDecimal,
                    row.baseCurrencyCode,
                    row.exchangeRateDecimal,
                    row.payee,
                    row.tagsJoined,
                    row.statusLabel,
                    row.note,
                    row.transactionId,
                ),
            )
        }
        val out = ByteArrayOutputStream(sb.length * 2 + 3)
        out.write(UTF8_BOM)
        out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        return out.toByteArray()
    }

    private fun appendCsvRow(
        sb: StringBuilder,
        fields: List<String>,
    ) {
        fields.forEachIndexed { idx, value ->
            if (idx > 0) sb.append(',')
            sb.append(escapeCsvField(value))
        }
        sb.append("\r\n")
    }

    internal fun escapeCsvField(raw: String): String {
        val needsQuoting = raw.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        if (!needsQuoting) return raw
        val escaped = raw.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
