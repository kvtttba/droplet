package io.github.kvtttba.droplet.core.domain.export

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure Kotlin lightweight OOXML (.xlsx) workbook generator using only [ZipOutputStream] and
 * well-formed XML (zero Apache POI overhead so the Android APK remains ~17 MB).
 *
 * Emits 3 worksheets:
 * 1. `流水明细` — flat transaction ledger with locked base-currency snapshots
 * 2. `分类汇总` — expense & income primary-category totals and shares
 * 3. `账户余额` — balance-sheet accounts with original & base-currency valuations
 */
internal object XlsxLedgerExporter {

    private sealed interface CellValue {
        data class Text(val value: String) : CellValue
        data class Number(val decimal: String) : CellValue
    }

    private val SHEET1_HEADERS = CsvLedgerExporter.HEADERS

    private val SHEET2_HEADERS = listOf(
        "收支方向",
        "一级分类",
        "汇总金额",
        "币种",
        "占比(%)",
        "交易笔数",
    )

    private val SHEET3_HEADERS = listOf(
        "账户名称",
        "科目类型",
        "账户子类",
        "币种",
        "原币种余额",
        "本位币估值",
        "本位币",
        "计入总资产",
        "状态",
    )

    fun export(
        transactionRows: List<ExportTransactionRow>,
        categoryRows: List<ExportCategorySummaryRow>,
        accountRows: List<ExportAccountBalanceRow>,
    ): ByteArray {
        val sheet1Data = transactionRows.map { row ->
            listOf(
                CellValue.Text(row.dateTimeText),
                CellValue.Text(row.displayTypeLabel),
                CellValue.Text(row.categoryPath),
                CellValue.Text(row.accountName),
                CellValue.Number(row.amountDecimal),
                CellValue.Text(row.currencyCode),
                CellValue.Number(row.baseAmountDecimal),
                CellValue.Text(row.baseCurrencyCode),
                CellValue.Number(row.exchangeRateDecimal),
                CellValue.Text(row.payee),
                CellValue.Text(row.tagsJoined),
                CellValue.Text(row.statusLabel),
                CellValue.Text(row.note),
                CellValue.Text(row.transactionId),
            )
        }

        val sheet2Data = categoryRows.map { row ->
            listOf(
                CellValue.Text(row.directionLabel),
                CellValue.Text(row.categoryName),
                CellValue.Number(row.amountDecimal),
                CellValue.Text(row.currencyCode),
                CellValue.Number(row.sharePctDecimal),
                CellValue.Number(row.transactionCount.toString()),
            )
        }

        val sheet3Data = accountRows.map { row ->
            listOf(
                CellValue.Text(row.accountName),
                CellValue.Text(row.typeLabel),
                CellValue.Text(row.subTypeLabel),
                CellValue.Text(row.currencyCode),
                CellValue.Number(row.originalBalanceDecimal),
                CellValue.Number(row.baseBalanceDecimal),
                CellValue.Text(row.baseCurrencyCode),
                CellValue.Text(row.includeInNetWorthLabel),
                CellValue.Text(row.statusLabel),
            )
        }

        val baos = ByteArrayOutputStream(8192)
        ZipOutputStream(baos).use { zip ->
            writeZipTextEntry(zip, "[Content_Types].xml", buildContentTypesXml())
            writeZipTextEntry(zip, "_rels/.rels", buildRootRelsXml())
            writeZipTextEntry(zip, "xl/workbook.xml", buildWorkbookXml())
            writeZipTextEntry(zip, "xl/_rels/workbook.xml.rels", buildWorkbookRelsXml())
            writeZipTextEntry(zip, "xl/styles.xml", buildStylesXml())
            writeZipTextEntry(
                zip,
                "xl/worksheets/sheet1.xml",
                buildWorksheetXml(SHEET1_HEADERS, sheet1Data),
            )
            writeZipTextEntry(
                zip,
                "xl/worksheets/sheet2.xml",
                buildWorksheetXml(SHEET2_HEADERS, sheet2Data),
            )
            writeZipTextEntry(
                zip,
                "xl/worksheets/sheet3.xml",
                buildWorksheetXml(SHEET3_HEADERS, sheet3Data),
            )
        }
        return baos.toByteArray()
    }

    private fun writeZipTextEntry(
        zip: ZipOutputStream,
        entryName: String,
        content: String,
    ) {
        val entry = ZipEntry(entryName)
        // Use deterministic timestamp for reproducible exports
        entry.time = 0L
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun buildContentTypesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
          <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        </Types>
    """.trimIndent()

    private fun buildRootRelsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private fun buildWorkbookXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets>
            <sheet name="流水明细" sheetId="1" r:id="rId1"/>
            <sheet name="分类汇总" sheetId="2" r:id="rId2"/>
            <sheet name="账户余额" sheetId="3" r:id="rId3"/>
          </sheets>
        </workbook>
    """.trimIndent()

    private fun buildWorkbookRelsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/>
          <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    private fun buildStylesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <fonts count="2">
            <font><sz val="11"/><name val="Calibri"/></font>
            <font><b/><sz val="11"/><color rgb="FF002022"/><name val="Calibri"/></font>
          </fonts>
          <fills count="3">
            <fill><patternFill patternType="none"/></fill>
            <fill><patternFill patternType="gray125"/></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FFCCEFEF"/><bgColor indexed="64"/></patternFill></fill>
          </fills>
          <borders count="1">
            <border><left/><right/><top/><bottom/><diagonal/></border>
          </borders>
          <cellStyleXfs count="1">
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
          </cellStyleXfs>
          <cellXfs count="3">
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
            <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
            <xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
          </cellXfs>
        </styleSheet>
    """.trimIndent()

    private fun buildWorksheetXml(
        headers: List<String>,
        rows: List<List<CellValue>>,
    ): String {
        val sb = StringBuilder(rows.size * 256 + 512)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\">")
        sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        sb.append("</sheetView></sheetViews>")
        sb.append("<sheetData>")

        // Header row (r = 1, style s = 1)
        sb.append("<row r=\"1\">")
        headers.forEachIndexed { colIdx, title ->
            val ref = "${columnLetters(colIdx)}1"
            sb.append("<c r=\"").append(ref).append("\" s=\"1\" t=\"inlineStr\"><is><t>")
            sb.append(escapeXml(title))
            sb.append("</t></is></c>")
        }
        sb.append("</row>")

        // Data rows
        rows.forEachIndexed { rowIdx, cells ->
            val excelRow = rowIdx + 2
            sb.append("<row r=\"").append(excelRow).append("\">")
            cells.forEachIndexed { colIdx, cell ->
                val ref = "${columnLetters(colIdx)}$excelRow"
                when (cell) {
                    is CellValue.Number -> {
                        val trimmed = cell.decimal.trim()
                        if (trimmed.isNotEmpty() && isValidXmlNumber(trimmed)) {
                            sb.append("<c r=\"").append(ref).append("\" s=\"2\" t=\"n\"><v>")
                            sb.append(trimmed)
                            sb.append("</v></c>")
                        } else {
                            appendInlineStrCell(sb, ref, trimmed)
                        }
                    }

                    is CellValue.Text -> appendInlineStrCell(sb, ref, cell.value)
                }
            }
            sb.append("</row>")
        }

        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun appendInlineStrCell(
        sb: StringBuilder,
        ref: String,
        value: String,
    ) {
        val escaped = escapeXml(value)
        val needsSpacePreserve = value.startsWith(' ') || value.endsWith(' ') || value.contains('\n')
        sb.append("<c r=\"").append(ref).append("\" s=\"0\" t=\"inlineStr\"><is><t")
        if (needsSpacePreserve) {
            sb.append(" xml:space=\"preserve\"")
        }
        sb.append(">").append(escaped).append("</t></is></c>")
    }

    internal fun columnLetters(zeroBasedIndex: Int): String {
        var n = zeroBasedIndex + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append(('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    internal fun escapeXml(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (ch in raw) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> {
                    val code = ch.code
                    val isValidXmlChar = code == 0x9 || code == 0xA || code == 0xD ||
                        (code in 0x20..0xD7FF) || (code in 0xE000..0xFFFD)
                    if (isValidXmlChar) {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun isValidXmlNumber(value: String): Boolean =
        Regex("^-?\\d+(\\.\\d+)?$").matches(value)
}
