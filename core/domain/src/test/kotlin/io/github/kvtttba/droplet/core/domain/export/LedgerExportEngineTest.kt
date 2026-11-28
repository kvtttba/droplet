package io.github.kvtttba.droplet.core.domain.export

import io.github.kvtttba.droplet.core.domain.ledger.ExchangeRateTable
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.TransactionMeta
import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.ExchangeRateSnapshot
import io.github.kvtttba.droplet.core.model.ExchangeRateSource
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.Quantity
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.Tag
import io.github.kvtttba.droplet.core.model.TransactionStatus
import io.github.kvtttba.droplet.core.model.UnitPrice
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerExportEngineTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val genMs = LocalDate.of(2026, 11, 28).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private val debit = Account("acct:debit", "储蓄卡", AccountType.ASSET, AccountSubType.DEBIT_CARD, currency = Currency.CNY)
    private val usdCash = Account("acct:usd", "美元户", AccountType.ASSET, AccountSubType.INVEST_CASH, currency = Currency.USD)
    private val btcHolding = Account("acct:btc", "BTC持仓", AccountType.ASSET, AccountSubType.INVEST_HOLDING, currency = Currency.USD)
    private val expFood = Account("cat:food", "餐饮", AccountType.EXPENSE, AccountSubType.CATEGORY)
    private val expLunch = Account("cat:food:lunch", "午餐", AccountType.EXPENSE, AccountSubType.CATEGORY, parentId = "cat:food")
    private val incSalary = Account("cat:salary", "工资", AccountType.INCOME, AccountSubType.CATEGORY)
    private val tradingCny = Account(SystemAccounts.tradingAccountId(Currency.CNY), "Trading CNY", AccountType.EQUITY, AccountSubType.TRADING, currency = Currency.CNY, isSystem = true)
    private val tradingUsd = Account(SystemAccounts.tradingAccountId(Currency.USD), "Trading USD", AccountType.EQUITY, AccountSubType.TRADING, currency = Currency.USD, isSystem = true)

    private val accountsById = listOf(
        debit, usdCash, btcHolding, expFood, expLunch, incSalary, tradingCny, tradingUsd,
    ).associateBy { it.id }

    private val tagWork = Tag("tag:work", "工作日")
    private val tagsById = mapOf(tagWork.id to tagWork)

    private val rates = ExchangeRateTable.of(
        ExchangeRateSnapshot(Currency.USD, Currency.CNY, "7.25", ExchangeRateSource.USER_OVERRIDE, genMs),
    )

    private val engine = LedgerEngine(baseCurrency = Currency.CNY, rateProvider = rates)
    private val exporter = LedgerExportEngine()

    private fun sampleTransactions() = listOf(
        engine.buildIncome(
            meta = TransactionMeta(id = "tx-1", occurredAt = ms(2026, 11, 1), payee = "科技公司"),
            incomeCategoryAccountId = incSalary.id,
            depositAccountId = debit.id,
            amount = Money.fromDecimalString("15000.00"),
        ),
        engine.buildExpense(
            meta = TransactionMeta(
                id = "tx-2",
                occurredAt = ms(2026, 11, 10),
                payee = "A&B \"特色\" 餐馆, 旗舰店",
                note = "午餐<套餐>\n含饮料",
                tagIds = listOf(tagWork.id),
            ),
            expenseCategoryAccountId = expLunch.id,
            paymentAccountId = debit.id,
            amount = Money.fromDecimalString("38.50"),
        ),
        engine.buildCrossCurrencyTransfer(
            meta = TransactionMeta(id = "tx-3", occurredAt = ms(2026, 11, 12), payee = "购汇"),
            fromAccountId = debit.id,
            toAccountId = usdCash.id,
            paidAmount = Money.fromDecimalString("7250.00", Currency.CNY),
            receivedAmount = Money.fromDecimalString("1000.00", Currency.USD),
        ),
        engine.buildInvestmentBuy(
            meta = TransactionMeta(id = "tx-4", occurredAt = ms(2026, 11, 15), payee = "BTC 定投"),
            holdingAccountId = btcHolding.id,
            cashAccountId = usdCash.id,
            investmentAssetId = "asset:btc",
            quantity = Quantity.of("0.01234567"),
            unitPrice = UnitPrice.of("65000.00"),
            currency = Currency.USD,
            totalAmount = Money.fromDecimalString("802.47", Currency.USD),
        ),
        engine.buildExpense(
            meta = TransactionMeta(id = "tx-void", occurredAt = ms(2026, 11, 18), payee = "已作废单"),
            expenseCategoryAccountId = expLunch.id,
            paymentAccountId = debit.id,
            amount = Money.fromDecimalString("99.00"),
        ).copy(status = TransactionStatus.VOID),
    )

    private fun ms(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atTime(9, 30).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun csvExport_emitsUtf8Bom_rfc4180Escaping_andRespectsIncludeVoided() {
        val txs = sampleTransactions()
        val artifact = exporter.export(
            request = LedgerExportRequest(
                format = ExportFormat.CSV,
                scope = ExportScope.ALL_TIME,
                includeVoided = false,
                zoneId = zone,
                generatedAtEpochMs = genMs,
            ),
            allTransactions = txs,
            accountsById = accountsById,
            tagsById = tagsById,
            exchangeRateProvider = rates,
        )

        assertEquals(4, artifact.transactionCount)
        assertTrue(artifact.fileName.endsWith(".csv"))
        // Verify UTF-8 BOM (EF BB BF)
        assertEquals(0xEF.toByte(), artifact.bytes[0])
        assertEquals(0xBB.toByte(), artifact.bytes[1])
        assertEquals(0xBF.toByte(), artifact.bytes[2])

        val text = String(artifact.bytes, 3, artifact.bytes.size - 3, StandardCharsets.UTF_8)
        assertTrue(text.startsWith("日期时间,交易类型,分类,账户,金额,币种,锁定本位币金额,本位币,汇率快照,商户,标签,状态,备注,交易ID\r\n"))
        // Verify RFC-4180 double-quote and newline escaping
        assertTrue(text.contains("\"A&B \"\"特色\"\" 餐馆, 旗舰店\""))
        assertTrue(text.contains("\"午餐<套餐>\n含饮料\""))
        assertTrue(text.contains("餐饮/午餐"))
        assertTrue(text.contains("-38.50"))
        assertFalse(text.contains("tx-void"))
    }

    @Test
    fun xlsxExport_generatesValidMultiSheetOoxmlZipWithoutPoi() {
        val txs = sampleTransactions()
        val artifact = exporter.export(
            request = LedgerExportRequest(
                format = ExportFormat.XLSX,
                scope = ExportScope.ALL_TIME,
                includeVoided = false,
                zoneId = zone,
                generatedAtEpochMs = genMs,
            ),
            allTransactions = txs,
            accountsById = accountsById,
            tagsById = tagsById,
            exchangeRateProvider = rates,
        )

        assertTrue(artifact.fileName.endsWith(".xlsx"))
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(artifact.bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entries[entry.name] = zis.readBytes().toString(StandardCharsets.UTF_8)
                zis.closeEntry()
            }
        }

        val expectedParts = setOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/styles.xml",
            "xl/worksheets/sheet1.xml",
            "xl/worksheets/sheet2.xml",
            "xl/worksheets/sheet3.xml",
        )
        assertEquals(expectedParts, entries.keys)

        // Parse every XML part with strict DOM parser to prove XML well-formedness (even with & < > " in data)
        val docFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for ((name, xml) in entries) {
            val doc = docFactory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
            assertTrue("Expected root element in $name", doc.documentElement != null)
        }

        val workbookXml = entries.getValue("xl/workbook.xml")
        assertTrue(workbookXml.contains("name=\"流水明细\""))
        assertTrue(workbookXml.contains("name=\"分类汇总\""))
        assertTrue(workbookXml.contains("name=\"账户余额\""))

        val sheet1 = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet1.contains("A&amp;B &quot;特色&quot; 餐馆, 旗舰店"))
        assertTrue(sheet1.contains("午餐&lt;套餐&gt;"))
        assertTrue(sheet1.contains("<v>-38.50</v>"))

        val sheet2 = entries.getValue("xl/worksheets/sheet2.xml")
        assertTrue(sheet2.contains("餐饮"))
        assertTrue(sheet2.contains("38.50"))
        assertTrue(sheet2.contains("工资"))
        assertTrue(sheet2.contains("15000.00"))

        val sheet3 = entries.getValue("xl/worksheets/sheet3.xml")
        assertTrue(sheet3.contains("储蓄卡"))
        assertTrue(sheet3.contains("美元户"))
        assertTrue(sheet3.contains("BTC持仓"))
    }

    @Test
    fun beancountExport_declaresAllRootsAndProducesBalancedPostingsPerCurrency() {
        val txs = sampleTransactions()
        val artifact = exporter.export(
            request = LedgerExportRequest(
                format = ExportFormat.BEANCOUNT,
                scope = ExportScope.ALL_TIME,
                includeVoided = true,
                zoneId = zone,
                generatedAtEpochMs = genMs,
            ),
            allTransactions = txs,
            accountsById = accountsById,
            tagsById = tagsById,
            exchangeRateProvider = rates,
        )

        assertEquals(5, artifact.transactionCount)
        assertTrue(artifact.fileName.endsWith(".beancount"))

        val text = artifact.bytes.toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("option \"operating_currency\" \"CNY\""))
        assertTrue(text.contains("open Assets:"))
        assertTrue(text.contains("open Equity:Trading:CNY"))
        assertTrue(text.contains("open Equity:Trading:USD"))
        assertTrue(text.contains("open Income:"))
        assertTrue(text.contains("open Expenses:"))
        assertTrue(text.contains("#工作日"))
        assertTrue(text.contains("#void"))
        assertTrue(text.contains("qty=0.01234567"))
        assertTrue(text.contains("price=65000"))

        // Parse every exported transaction block and verify per-currency postings sum strictly to 0!
        val postingRegex = Regex("^\\s+([A-Z][^\\s]+)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(CNY|USD|HKD)(?:\\s*;.*)?$")
        val blocks = text.split(Regex("(?=\\n\\d{4}-\\d{2}-\\d{2} [*!])")).drop(1)
        assertEquals(5, blocks.size)
        for (block in blocks) {
            val sumsByCcy = hashMapOf<String, BigDecimal>()
            for (line in block.lines()) {
                val match = postingRegex.matchEntire(line) ?: continue
                val amount = BigDecimal(match.groupValues[2])
                val ccy = match.groupValues[3]
                sumsByCcy[ccy] = (sumsByCcy[ccy] ?: BigDecimal.ZERO).add(amount)
            }
            assertTrue("Transaction block must contain postings: $block", sumsByCcy.isNotEmpty())
            for ((ccy, sum) in sumsByCcy) {
                assertEquals(
                    "Beancount transaction must balance strictly to 0 for $ccy in block:\n$block",
                    0,
                    sum.compareTo(BigDecimal.ZERO),
                )
            }
        }
    }
}
