package io.github.kvtttba.droplet.core.ai

import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.Tag
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptBuilderTest {

    private val builder = AiPromptBuilder()
    private val fixedEpochMs = LocalDate.of(2026, 11, 27)
        .atTime(12, 30)
        .toInstant(ZoneOffset.ofHours(8))
        .toEpochMilli()

    @Test
    fun fromCatalogAndBuildTextPrompt_enforcesPrivacyMinimizationInvariant() {
        val accounts = listOf(
            Account(
                id = "acc_card",
                name = "招行信用卡",
                type = AccountType.LIABILITY,
                subType = AccountSubType.CREDIT_CARD,
                currency = Currency.CNY,
                creditLimitMinor = 888_880_000L, // ¥88,888.00 — MUST NOT appear in prompt
                billingDay = 15,
                dueDay = 3,
                remindDaysBefore = 3,
            ),
            Account(
                id = "acc_usd",
                name = "美股资金户",
                type = AccountType.ASSET,
                subType = AccountSubType.INVEST_CASH,
                currency = Currency.USD,
            ),
            Account(
                id = "acc_archived",
                name = "已归档私密金库",
                type = AccountType.ASSET,
                subType = AccountSubType.DEBIT_CARD,
                currency = Currency.CNY,
                isArchived = true,
            ),
            Account(
                id = SystemAccounts.tradingAccountId(Currency.CNY),
                name = "Equity:Trading:CNY",
                type = AccountType.EQUITY,
                subType = AccountSubType.TRADING,
                currency = Currency.CNY,
                isSystem = true,
            ),
            Account(
                id = "cat_food",
                name = "餐饮",
                type = AccountType.EXPENSE,
                subType = AccountSubType.CATEGORY,
                currency = Currency.CNY,
            ),
            Account(
                id = "cat_food_lunch",
                name = "午餐",
                type = AccountType.EXPENSE,
                subType = AccountSubType.CATEGORY,
                parentId = "cat_food",
                currency = Currency.CNY,
            ),
            Account(
                id = "cat_salary",
                name = "工资",
                type = AccountType.INCOME,
                subType = AccountSubType.CATEGORY,
                currency = Currency.CNY,
            ),
            Account(
                id = "cat_archived",
                name = "已归档支出分类",
                type = AccountType.EXPENSE,
                subType = AccountSubType.CATEGORY,
                currency = Currency.CNY,
                isArchived = true,
            ),
        )
        val tags = listOf(
            Tag(id = "tag_work", name = "工作日"),
            Tag(id = "tag_archived", name = "已归档标签", isArchived = true),
        )

        val context = AiPromptContext.fromCatalog(
            accounts = accounts,
            tags = tags,
            currentTimeEpochMs = fixedEpochMs,
            zoneId = ZoneId.of("Asia/Shanghai"),
            defaultCurrency = Currency.CNY,
        )

        val payload = builder.buildTextPrompt("午饭麦当劳38.5刷招行信用卡", context)
        val system = payload.systemPrompt

        // Included minimal metadata
        assertTrue(system.contains("2026-11-27T12:30:00+08:00"))
        assertTrue(system.contains("Asia/Shanghai"))
        assertTrue(system.contains("招行信用卡 | LIABILITY/CREDIT_CARD | CNY"))
        assertTrue(system.contains("美股资金户 | ASSET/INVEST_CASH | USD"))
        assertTrue(system.contains("餐饮/午餐"))
        assertTrue(system.contains("工资"))
        assertTrue(system.contains("工作日"))
        assertTrue(system.contains("\"items\""))

        // Privacy redlines: zero balances/limits, zero archived/system entries
        assertFalse(system.contains("88888"))
        assertFalse(system.contains("888,880"))
        assertFalse(system.contains("888880000"))
        assertFalse(system.contains("已归档私密金库"))
        assertFalse(system.contains("Equity:Trading:CNY"))
        assertFalse(system.contains("已归档支出分类"))
        assertFalse(system.contains("已归档标签"))

        assertTrue(payload.userPrompt.contains("午饭麦当劳38.5刷招行信用卡"))
    }

    @Test
    fun buildImagePrompt_handlesSingleAndBatchInstructions() {
        val context = AiPromptContext(
            currentTimeEpochMs = fixedEpochMs,
            defaultCurrency = Currency.CNY,
        )

        val single = builder.buildImagePrompt(imageCount = 1, context = context)
        assertTrue(single.userPrompt.contains("这张"))
        assertTrue(single.userPrompt.contains("不要拆分为逐行商品明细"))
        assertTrue(single.systemPrompt.contains("实际支付金额为准"))

        val batch = builder.buildImagePrompt(
            imageCount = 3,
            context = context,
            userHint = "全是出差报销小票",
        )
        assertTrue(batch.userPrompt.contains("3 张图片"))
        assertTrue(batch.userPrompt.contains("全是出差报销小票"))
    }
}
