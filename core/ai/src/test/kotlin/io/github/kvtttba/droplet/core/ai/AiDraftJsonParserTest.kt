package io.github.kvtttba.droplet.core.ai

import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDraftJsonParserTest {

    private val parser = AiDraftJsonParser()
    private val shanghaiZone = ZoneId.of("Asia/Shanghai")
    private val fixedNowMs = LocalDate.of(2026, 11, 27)
        .atTime(12, 30)
        .toInstant(ZoneOffset.ofHours(8))
        .toEpochMilli()

    private val sampleContext = AiPromptContext(
        currentTimeEpochMs = fixedNowMs,
        zoneId = shanghaiZone,
        defaultCurrency = Currency.CNY,
        accounts = listOf(
            AiPromptAccountRef("acc_cmb", "招行信用卡", AccountType.LIABILITY, AccountSubType.CREDIT_CARD, Currency.CNY),
            AiPromptAccountRef("acc_wx", "微信零钱", AccountType.ASSET, AccountSubType.DEBIT_CARD, Currency.CNY),
            AiPromptAccountRef("acc_bank", "招商储蓄卡", AccountType.ASSET, AccountSubType.DEBIT_CARD, Currency.CNY),
            AiPromptAccountRef("acc_usd", "美股资金户", AccountType.ASSET, AccountSubType.INVEST_CASH, Currency.USD),
        ),
        categories = listOf(
            AiPromptCategoryRef("cat_breakfast", "餐饮/早餐", AccountType.EXPENSE, "餐饮", "早餐"),
            AiPromptCategoryRef("cat_lunch", "餐饮/午餐", AccountType.EXPENSE, "餐饮", "午餐"),
            AiPromptCategoryRef("cat_subway", "交通/地铁公交", AccountType.EXPENSE, "交通", "地铁公交"),
            AiPromptCategoryRef("cat_taxi", "交通/打车", AccountType.EXPENSE, "交通", "打车"),
            AiPromptCategoryRef("cat_salary", "工资薪金/基本工资", AccountType.INCOME, "工资薪金", "基本工资"),
        ),
        tags = listOf(
            AiPromptTagRef("tag_work", "工作日"),
            AiPromptTagRef("tag_trip", "出差"),
        ),
    )

    @Test
    fun parsesMarkdownFencedReasoningWrappedMultiItemBatchWithExactFixedPointMoney() {
        val messyOutput = """
            <think>先拆两笔交易，一笔早饭一笔打车</think>
            好的，以下是解析后的 JSON 结果：
            ```json
            {
              "items": [
                {
                  "display_type": "EXPENSE",
                  "occurred_at": "2026-11-27T08:15:00+08:00",
                  "amount": "8.50",
                  "currency": "CNY",
                  "category_path": "餐饮/早餐",
                  "account_name": "微信零钱",
                  "target_account_name": null,
                  "payee": "早餐铺",
                  "tags": ["工作日"],
                  "note": "豆浆油条",
                  "confidence": 0.96,
                  "uncertain_fields": []
                },
                {
                  "display_type": "EXPENSE",
                  "occurred_at": "2026-11-27 12:40",
                  "amount": 25.1234,
                  "currency": "CNY",
                  "category_path": "交通·打车",
                  "account_name": "招行信用卡",
                  "target_account_name": null,
                  "payee": "网约车",
                  "tags": ["工作日", "出差"],
                  "note": "午间打车",
                  "confidence": "91%",
                  "uncertain_fields": []
                }
              ]
            }
            ```
            请核对后入账。
        """.trimIndent()

        val batch = parser.parse(messyOutput, sampleContext)
        assertEquals(2, batch.items.size)
        assertEquals(0, batch.skippedItemCount)

        val first = batch.items[0]
        assertEquals(TransactionDisplayType.EXPENSE, first.displayType)
        assertEquals(Money(85_000L, Currency.CNY), first.amount)
        assertEquals("cat_breakfast", first.matchedCategoryId)
        assertEquals("acc_wx", first.matchedAccountId)
        assertEquals(listOf("tag_work"), first.matchedTagIds)
        assertEquals("0.96", first.confidenceDecimal)
        assertFalse(first.needsUserReview)

        val second = batch.items[1]
        assertEquals(Money(251_234L, Currency.CNY), second.amount) // exact 4-decimal fixed point
        assertEquals("cat_taxi", second.matchedCategoryId)
        assertEquals("acc_cmb", second.matchedAccountId)
        assertEquals(listOf("tag_work", "tag_trip"), second.matchedTagIds)
        assertEquals("0.91", second.confidenceDecimal)
        assertFalse(second.needsUserReview)
    }

    @Test
    fun handlesSingleObjectTransferCrossCurrencyFuzzyMatchAndUncertainFields() {
        val rawTransfer = """
            {
              "display_type": "TRANSFER",
              "occurred_at": "2026-11-27",
              "amount": "${'$'}1,250.50",
              "currency": "USD",
              "category_path": null,
              "account_name": "招商储蓄卡",
              "target_account_name": "美股资金户",
              "payee": null,
              "tags": ["未定义标签"],
              "note": "换汇入金",
              "confidence": 0.65,
              "uncertain_fields": []
            }
        """.trimIndent()

        val batch = parser.parse(rawTransfer, sampleContext)
        assertEquals(1, batch.items.size)
        val item = batch.items.single()
        assertEquals(TransactionDisplayType.TRANSFER, item.displayType)
        assertEquals(Currency.USD, item.currency)
        assertEquals(Money(12_505_000L, Currency.USD), item.amount)
        assertEquals("acc_bank", item.matchedAccountId)
        assertEquals("acc_usd", item.matchedTargetAccountId)
        assertNull(item.matchedCategoryId)
        assertFalse(AiUncertainFieldKeys.CATEGORY_PATH in item.uncertainFields) // category optional for TRANSFER
        assertTrue(AiUncertainFieldKeys.OCCURRED_AT in item.uncertainFields) // date-only timestamp
        assertTrue(AiUncertainFieldKeys.TAGS in item.uncertainFields) // unmatched tag
        assertTrue(item.isLowConfidence)
        assertTrue(item.needsUserReview)
    }

    @Test
    fun missingTimeUnsupportedCurrencyAndUnmatchedEntitiesFallbackSafelyAndFlagUncertain() {
        val raw = """
            [
              {
                "display_type": "EXPENSE",
                "occurred_at": null,
                "amount": "¥42.00",
                "currency": "EUR",
                "category_path": "交通/地铁",
                "account_name": "不存在的卡",
                "payee": "地铁站",
                "tags": [],
                "confidence": 0.80
              }
            ]
        """.trimIndent()

        val batch = parser.parse(raw, sampleContext)
        assertEquals(1, batch.items.size)
        val item = batch.items.single()
        assertEquals(fixedNowMs, item.occurredAtEpochMs)
        assertEquals(Currency.CNY, item.currency)
        assertEquals(Money(420_000L, Currency.CNY), item.amount)
        assertEquals("cat_subway", item.matchedCategoryId) // fuzzy match "交通/地铁" -> "交通/地铁公交"
        assertNull(item.matchedAccountId)
        assertTrue(AiUncertainFieldKeys.OCCURRED_AT in item.uncertainFields)
        assertTrue(AiUncertainFieldKeys.CURRENCY in item.uncertainFields)
        assertTrue(AiUncertainFieldKeys.CATEGORY_PATH in item.uncertainFields) // fuzzy match flagged for review
        assertTrue(AiUncertainFieldKeys.ACCOUNT_NAME in item.uncertainFields)
    }

    @Test
    fun skipsInvalidAmountsWhileKeepingValidItemsAndRejectsAllInvalidPayloads() {
        val mixed = """
            {
              "items": [
                {"display_type": "EXPENSE", "amount": "NaN", "currency": "CNY"},
                {"display_type": "EXPENSE", "amount": "-15.00", "currency": "CNY"},
                {"display_type": "EXPENSE", "amount": "0", "currency": "CNY"},
                {"display_type": "EXPENSE", "amount": "1e999999", "currency": "CNY"},
                {"display_type": "EXPENSE", "amount": "Infinity", "currency": "CNY"},
                {"display_type": "EXPENSE", "amount": "19.99", "currency": "CNY", "category_path": "餐饮/午餐", "account_name": "微信零钱", "occurred_at": "2026-11-27T12:00:00+08:00", "confidence": 0.95}
              ]
            }
        """.trimIndent()

        val batch = parser.parse(mixed, sampleContext)
        assertEquals(1, batch.items.size)
        assertEquals(5, batch.skippedItemCount)
        assertEquals(Money(199_900L, Currency.CNY), batch.items.single().amount)
        assertEquals("cat_lunch", batch.items.single().matchedCategoryId)
    }

    @Test(expected = AiDraftParseException::class)
    fun throwsParseExceptionWhenAllAmountsAreInvalid() {
        val invalidOnly = """
            {"items": [{"display_type": "EXPENSE", "amount": "-99.9", "currency": "CNY"}]}
        """.trimIndent()
        parser.parse(invalidOnly, sampleContext)
    }

    @Test(expected = AiDraftParseException::class)
    fun throwsParseExceptionOnNonJsonOutput() {
        parser.parse("抱歉，我无法识别这张图片中的金额。", sampleContext)
    }
}
