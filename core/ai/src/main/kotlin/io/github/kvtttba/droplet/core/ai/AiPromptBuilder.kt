package io.github.kvtttba.droplet.core.ai

import io.github.kvtttba.droplet.core.model.AccountType
import java.time.Instant
import java.time.format.DateTimeFormatter

data class AiPromptPayload(
    val systemPrompt: String,
    val userPrompt: String,
)

/**
 * Privacy-minimized prompt builder per `docs/ARCHITECTURE.md §5.2` & `§5.3`.
 *
 * Strictly injects ONLY:
 * 1. Current ISO-8601 timestamp, timezone, and default base currency;
 * 2. Active operational account names + types + currencies (ZERO balances, limits, or card numbers);
 * 3. Active two-level category paths and active tag names;
 * 4. The current user text or image task instruction.
 */
class AiPromptBuilder {

    fun buildTextPrompt(
        userText: String,
        context: AiPromptContext,
    ): AiPromptPayload {
        val cleanedInput = userText.trim()
        require(cleanedInput.isNotEmpty()) { "User text for AI parsing must not be blank" }
        val systemPrompt = buildSystemPrompt(context = context, isVisionTask = false)
        val userPrompt = buildString {
            appendLine("请将以下自然语言记账内容解析为严格符合约定 Schema 的 JSON：")
            appendLine()
            append(cleanedInput)
        }
        return AiPromptPayload(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
        )
    }

    fun buildImagePrompt(
        imageCount: Int,
        context: AiPromptContext,
        userHint: String? = null,
    ): AiPromptPayload {
        require(imageCount > 0) { "imageCount must be positive, got $imageCount" }
        val systemPrompt = buildSystemPrompt(context = context, isVisionTask = true)
        val userPrompt = buildString {
            if (imageCount == 1) {
                appendLine("请识别这张消费小票、支付截图或账单图片中的交易，并输出严格 JSON：")
            } else {
                appendLine("请依次识别这 $imageCount 张图片中的所有消费小票、支付截图或账单记录，合并输出到同一个 JSON 的 items 数组中：")
            }
            appendLine("- 若单张图片为单笔购物小票或发票，请汇总为一笔总金额交易（不要拆分为逐行商品明细）。")
            appendLine("- 若图片为账单列表长截图或多笔明细，请逐笔拆分并按时间顺序输出到 items 数组。")
            val trimmedHint = userHint?.trim().orEmpty()
            if (trimmedHint.isNotEmpty()) {
                appendLine("- 用户补充说明：$trimmedHint")
            }
        }.trimEnd()
        return AiPromptPayload(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
        )
    }

    private fun buildSystemPrompt(
        context: AiPromptContext,
        isVisionTask: Boolean,
    ): String {
        val nowIso = Instant.ofEpochMilli(context.currentTimeEpochMs)
            .atZone(context.zoneId)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        val accountsBlock = if (context.accounts.isEmpty()) {
            "（暂无账户，请留空 account_name 并在 uncertain_fields 标记）"
        } else {
            context.accounts.joinToString("\n") { acc ->
                "- ${acc.name} | ${acc.type.name}/${acc.subType.name} | ${acc.currency.code}"
            }
        }

        val expenseCategories = context.categories
            .filter { it.type == AccountType.EXPENSE }
            .map { it.path }
        val incomeCategories = context.categories
            .filter { it.type == AccountType.INCOME }
            .map { it.path }
        val tagsList = context.tags.map { it.name }

        return buildString {
            appendLine("你是 Droplet 个人复式记账应用的结构化解析引擎。请只输出纯 JSON 对象，不要输出任何 Markdown 代码块、解释文字或额外前后缀。")
            appendLine()
            appendLine("【当前上下文】")
            appendLine("- 当前时间：$nowIso")
            appendLine("- 时区：${context.zoneId.id}")
            appendLine("- 默认本位币：${context.defaultCurrency.code}（仅支持 CNY / USD / HKD）")
            appendLine()
            appendLine("【可用账户列表（名称 | 类型 | 币种）】")
            appendLine(accountsBlock)
            appendLine()
            appendLine("【可用支出二级分类】")
            appendLine(if (expenseCategories.isEmpty()) "（无）" else expenseCategories.joinToString("、"))
            appendLine()
            appendLine("【可用收入二级分类】")
            appendLine(if (incomeCategories.isEmpty()) "（无）" else incomeCategories.joinToString("、"))
            appendLine()
            appendLine("【可用标签】")
            appendLine(if (tagsList.isEmpty()) "（无）" else tagsList.joinToString("、"))
            appendLine()
            appendLine("【输出 JSON Schema（严格遵守）】")
            appendLine(SCHEMA_TEMPLATE)
            appendLine()
            appendLine("【解析规则】")
            appendLine("1. 顶层必须为 {\"items\": [...]}，支持单笔与多笔交易。")
            appendLine("2. display_type 仅限：EXPENSE（支出）、INCOME（收入）、TRANSFER（转账/还款）、REFUND（退款）、REIMBURSE（待报销垫付）、DEBT（往来借贷）、LOAN_REPAY（分期还款）。")
            appendLine("3. amount 必须为正数十进制字符串（例如 \"38.50\"），不含货币符号或千分位逗号。")
            appendLine("4. currency 仅限 CNY / USD / HKD；未明确提及币种时默认使用 ${context.defaultCurrency.code}。")
            appendLine("5. occurred_at 使用 ISO-8601 带时区偏移格式（如 $nowIso）；若原文未明确时间，请结合当前时间推断并将 \"occurred_at\" 加入 uncertain_fields。")
            appendLine("6. category_path、account_name、target_account_name、tags 必须优先从上方可用列表中精确选择；无法确定时设为 null（或空数组）并将对应字段名加入 uncertain_fields。")
            appendLine("7. TRANSFER 类型请同时填写 account_name（转出户）与 target_account_name（转入户），category_path 可为 null。")
            appendLine("8. confidence 为 0.00 到 1.00 的置信度数值；任意存在推测或模糊的字段都必须列出在 uncertain_fields 中。")
            if (isVisionTask) {
                appendLine("9. 若图片中存在实付金额与原价/优惠，请以实际支付金额为准，优惠说明可写入 note。")
            }
        }.trimEnd()
    }

    companion object {
        private val SCHEMA_TEMPLATE = """
            {
              "items": [
                {
                  "display_type": "EXPENSE",
                  "occurred_at": "2026-11-27T12:30:00+08:00",
                  "amount": "38.50",
                  "currency": "CNY",
                  "category_path": "餐饮/午餐",
                  "account_name": "招行信用卡",
                  "target_account_name": null,
                  "payee": "麦当劳",
                  "tags": ["工作日"],
                  "note": "午饭",
                  "confidence": 0.92,
                  "uncertain_fields": []
                }
              ]
            }
        """.trimIndent()
    }
}
