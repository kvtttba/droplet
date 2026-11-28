package io.github.kvtttba.droplet.core.ai

import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fault-tolerant, strictly zero-floating-point structured JSON parser for AI bookkeeping responses.
 *
 * Capabilities:
 * - Strips `<think>...</think>` reasoning blocks, Markdown code fences, and surrounding prose;
 * - Accepts `{"items":[...]}`, common alias keys, bare JSON arrays, and single-item objects;
 * - Parses amounts strictly via `JsonPrimitive.content -> BigDecimal -> Money (scale = 4)`,
 *   rejecting `NaN`, `Infinity`, non-positive, or overflowing numeric values;
 * - Normalizes `display_type`, `currency`, and diverse ISO-8601 / local timestamp formats;
 * - Matches categories, accounts, target accounts, and tags against [AiPromptContext], automatically
 *   recording missing/unmatched/low-confidence fields in `uncertainFields`.
 */
class AiDraftJsonParser internal constructor(
    private val json: Json,
) {
    constructor() : this(DEFAULT_JSON)

    fun parse(
        rawModelOutput: String,
        context: AiPromptContext,
    ): AiParsedDraftBatch {
        val extracted = extractJsonElement(rawModelOutput)
            ?: throw AiDraftParseException("AI 返回内容中未找到可解析的 JSON 数据")

        val batchWarnings = mutableListOf<String>()
        val rawItems: List<JsonObject> = when (extracted) {
            is JsonObject -> {
                val itemsArray = extracted["items"] as? JsonArray
                    ?: run {
                        val aliasKey = ITEM_ARRAY_ALIASES.firstOrNull { extracted[it] is JsonArray }
                        if (aliasKey != null) {
                            batchWarnings += "模型使用了非标准数组键 \"$aliasKey\"，已自动兼容解析"
                            extracted[aliasKey] as JsonArray
                        } else {
                            null
                        }
                    }
                if (itemsArray != null) {
                    itemsArray.mapNotNull { it as? JsonObject }
                } else if (looksLikeSingleTransactionObject(extracted)) {
                    batchWarnings += "模型直接返回了单笔交易对象，已自动包装为 items 数组"
                    listOf(extracted)
                } else {
                    throw AiDraftParseException("AI JSON 缺少有效的 items 数组")
                }
            }
            is JsonArray -> {
                batchWarnings += "模型直接返回了 JSON 数组，已自动兼容解析"
                extracted.mapNotNull { it as? JsonObject }
            }
            else -> throw AiDraftParseException("AI 返回的 JSON 顶层结构不受支持")
        }

        if (rawItems.isEmpty()) {
            throw AiDraftParseException("AI 未识别出任何交易记录")
        }

        val candidates = mutableListOf<AiParsedTransactionCandidate>()
        var skippedCount = 0

        rawItems.forEachIndexed { index, itemObj ->
            val parsed = parseSingleItem(
                itemObj = itemObj,
                context = context,
                itemIndex = index,
                batchWarnings = batchWarnings,
            )
            if (parsed != null) {
                candidates += parsed
            } else {
                skippedCount++
            }
        }

        if (candidates.isEmpty()) {
            val detail = batchWarnings.firstOrNull()?.let { "（$it）" }.orEmpty()
            throw AiDraftParseException("AI 返回的交易金额或字段无效，未生成可用草稿$detail")
        }

        return AiParsedDraftBatch(
            schemaVersion = AiCaptureContract.STRUCTURED_SCHEMA_VERSION,
            items = candidates,
            skippedItemCount = skippedCount,
            warnings = batchWarnings,
        )
    }

    private fun parseSingleItem(
        itemObj: JsonObject,
        context: AiPromptContext,
        itemIndex: Int,
        batchWarnings: MutableList<String>,
    ): AiParsedTransactionCandidate? {
        val uncertain = linkedSetOf<String>()
        val itemWarnings = mutableListOf<String>()

        // Collect model-reported uncertain fields first
        (itemObj["uncertain_fields"] as? JsonArray)?.forEach { elem ->
            val fieldName = elem.stringContentOrNull()?.trim()?.lowercase()
            if (!fieldName.isNullOrEmpty()) {
                uncertain += canonicalizeUncertainFieldName(fieldName)
            }
        }

        // 1. Display type
        val rawDisplayType = itemObj["display_type"].stringContentOrNull()
        val displayType = parseDisplayType(rawDisplayType) ?: run {
            uncertain += AiUncertainFieldKeys.DISPLAY_TYPE
            itemWarnings += "交易类型未识别，已默认归为支出"
            TransactionDisplayType.EXPENSE
        }

        // 2. Currency
        val rawCurrency = itemObj["currency"].stringContentOrNull()
        val currency = parseCurrency(rawCurrency) ?: run {
            uncertain += AiUncertainFieldKeys.CURRENCY
            if (!rawCurrency.isNullOrBlank()) {
                itemWarnings += "暂不支持币种 \"$rawCurrency\"，已回退为 ${context.defaultCurrency.code}"
            }
            context.defaultCurrency
        }

        // 3. Zero-float Amount (strict positive validation)
        val rawAmount = itemObj["amount"].rawScalarContentOrNull()
        val amountMoney = parsePositiveMoneyOrNull(rawAmount, currency)
        if (amountMoney == null) {
            batchWarnings += "第 ${itemIndex + 1} 笔金额非法或缺失（raw=${rawAmount ?: "null"}），已拦截跳过"
            return null
        }

        // 4. Timestamp
        val rawOccurredAt = itemObj["occurred_at"].stringContentOrNull()
        val parsedTime = parseOccurredAt(rawOccurredAt, context.zoneId)
        val occurredAtEpochMs = if (parsedTime != null) {
            if (parsedTime.isDateOnly) {
                uncertain += AiUncertainFieldKeys.OCCURRED_AT
            }
            parsedTime.epochMs
        } else {
            uncertain += AiUncertainFieldKeys.OCCURRED_AT
            if (!rawOccurredAt.isNullOrBlank()) {
                itemWarnings += "时间格式 \"$rawOccurredAt\" 无法解析，已使用当前时间"
            }
            context.currentTimeEpochMs
        }

        // 5. Category matching (required for expense/income/refund/reimburse)
        val rawCategoryPath = itemObj["category_path"].stringContentOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val expectedCategoryType = expectedCategoryTypeFor(displayType)
        val matchedCategory = if (rawCategoryPath != null && expectedCategoryType != null) {
            matchCategory(rawCategoryPath, expectedCategoryType, context.categories)
        } else {
            null
        }
        if (expectedCategoryType != null) {
            if (matchedCategory == null) {
                uncertain += AiUncertainFieldKeys.CATEGORY_PATH
            } else if (matchedCategory.isFuzzy) {
                uncertain += AiUncertainFieldKeys.CATEGORY_PATH
                itemWarnings += "分类 \"$rawCategoryPath\" 经模糊匹配到 \"${matchedCategory.value.path}\"，请核对"
            }
        }

        // 6. Source / primary account matching
        val rawAccountName = itemObj["account_name"].stringContentOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val matchedAccount = rawAccountName?.let { matchAccount(it, currency, context.accounts) }
        if (matchedAccount == null) {
            uncertain += AiUncertainFieldKeys.ACCOUNT_NAME
        } else if (matchedAccount.isFuzzy) {
            uncertain += AiUncertainFieldKeys.ACCOUNT_NAME
            itemWarnings += "账户 \"$rawAccountName\" 经模糊匹配到 \"${matchedAccount.value.name}\"，请核对"
        }

        // 7. Target account matching (required for TRANSFER)
        val rawTargetAccountName = itemObj["target_account_name"].stringContentOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val matchedTargetAccount = rawTargetAccountName?.let {
            matchAccount(
                rawName = it,
                preferredCurrency = currency,
                accounts = context.accounts,
                excludeAccountId = matchedAccount?.value?.id,
            )
        }
        if (displayType == TransactionDisplayType.TRANSFER) {
            if (matchedTargetAccount == null) {
                uncertain += AiUncertainFieldKeys.TARGET_ACCOUNT_NAME
            } else if (matchedTargetAccount.isFuzzy) {
                uncertain += AiUncertainFieldKeys.TARGET_ACCOUNT_NAME
            }
        } else if (rawTargetAccountName != null && matchedTargetAccount == null) {
            uncertain += AiUncertainFieldKeys.TARGET_ACCOUNT_NAME
        }

        // 8. Tags matching
        val rawTags = extractStringList(itemObj["tags"])
        val matchedTags = mutableListOf<AiPromptTagRef>()
        val unmatchedTagNames = mutableListOf<String>()
        for (rawTag in rawTags) {
            val found = matchTag(rawTag, context.tags)
            if (found != null) {
                if (matchedTags.none { it.id == found.id }) {
                    matchedTags += found
                }
            } else {
                unmatchedTagNames += rawTag
            }
        }
        if (unmatchedTagNames.isNotEmpty()) {
            uncertain += AiUncertainFieldKeys.TAGS
            itemWarnings += "未匹配到本地标签：${unmatchedTagNames.joinToString("、")}"
        }

        // 9. Payee & Note
        val payee = itemObj["payee"].stringContentOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val note = itemObj["note"].stringContentOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        // 10. Zero-float Confidence
        val rawConfidence = itemObj["confidence"].rawScalarContentOrNull()
        val confidenceDecimal = parseConfidenceDecimal(rawConfidence) ?: run {
            uncertain += AiUncertainFieldKeys.CONFIDENCE
            AiParsedTransactionCandidate.DEFAULT_CONFIDENCE_DECIMAL
        }

        return AiParsedTransactionCandidate(
            displayType = displayType,
            occurredAtEpochMs = occurredAtEpochMs,
            occurredAtRaw = rawOccurredAt,
            amount = amountMoney,
            currency = currency,
            rawCategoryPath = rawCategoryPath,
            matchedCategoryId = matchedCategory?.value?.id,
            matchedCategoryPath = matchedCategory?.value?.path,
            rawAccountName = rawAccountName,
            matchedAccountId = matchedAccount?.value?.id,
            matchedAccountName = matchedAccount?.value?.name,
            rawTargetAccountName = rawTargetAccountName,
            matchedTargetAccountId = matchedTargetAccount?.value?.id,
            matchedTargetAccountName = matchedTargetAccount?.value?.name,
            payee = payee,
            rawTags = rawTags,
            matchedTagIds = matchedTags.map { it.id },
            matchedTagNames = matchedTags.map { it.name },
            note = note,
            confidenceDecimal = confidenceDecimal,
            uncertainFields = uncertain,
            warnings = itemWarnings,
        )
    }

    private fun extractJsonElement(raw: String): JsonElement? {
        val cleaned = stripReasoningTags(raw.removePrefix("\uFEFF")).trim()
        if (cleaned.isEmpty()) return null

        // Strategy 1: Direct parse
        parseJsonOrNull(cleaned)?.let { return it }

        // Strategy 2: Markdown fenced code blocks (prefer blocks containing "items")
        val fencedCandidates = CODE_FENCE_REGEX.findAll(cleaned)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        var firstValidFenced: JsonElement? = null
        for (block in fencedCandidates) {
            val parsed = parseJsonOrNull(block) ?: extractBalancedJsonSubstring(block)?.let(::parseJsonOrNull)
            if (parsed != null) {
                if (parsed is JsonObject && parsed.containsKey("items")) {
                    return parsed
                }
                if (firstValidFenced == null) {
                    firstValidFenced = parsed
                }
            }
        }
        if (firstValidFenced != null) return firstValidFenced

        // Strategy 3: Balanced top-level JSON object or array embedded in prose
        val embedded = extractBalancedJsonSubstring(cleaned) ?: return null
        return parseJsonOrNull(embedded)
    }

    private fun stripReasoningTags(input: String): String =
        THINK_BLOCK_REGEX.replace(input, "").trim()

    private fun parseJsonOrNull(candidate: String): JsonElement? = try {
        val parsed = json.parseToJsonElement(candidate)
        if (parsed is JsonObject || parsed is JsonArray) parsed else null
    } catch (_: Exception) {
        null
    }

    /**
     * Finds the first balanced `{...}` or `[...]` JSON payload inside arbitrary surrounding text,
     * accurately ignoring braces/brackets inside quoted strings.
     */
    private fun extractBalancedJsonSubstring(text: String): String? {
        var searchFrom = 0
        while (searchFrom < text.length) {
            val startIdx = text.indexOfAny(charArrayOf('{', '['), startIndex = searchFrom)
            if (startIdx < 0) return null
            val openChar = text[startIdx]
            val closeChar = if (openChar == '{') '}' else ']'
            var depth = 0
            var inString = false
            var escaped = false
            for (i in startIdx until text.length) {
                val ch = text[i]
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        inString = false
                    }
                } else {
                    when (ch) {
                        '"' -> inString = true
                        openChar -> depth++
                        closeChar -> {
                            depth--
                            if (depth == 0) {
                                val slice = text.substring(startIdx, i + 1)
                                if (parseJsonOrNull(slice) != null) {
                                    return slice
                                }
                                break
                            }
                        }
                    }
                }
            }
            searchFrom = startIdx + 1
        }
        return null
    }

    private fun looksLikeSingleTransactionObject(obj: JsonObject): Boolean =
        obj.containsKey("amount") || obj.containsKey("display_type") || obj.containsKey("category_path")

    private fun parseDisplayType(raw: String?): TransactionDisplayType? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val upper = trimmed.uppercase().replace('-', '_').replace(' ', '_')
        TransactionDisplayType.entries.firstOrNull { it.name == upper }?.let { return it }
        return when (trimmed) {
            "支出", "消费", "付款" -> TransactionDisplayType.EXPENSE
            "收入", "工资", "奖金", "收款" -> TransactionDisplayType.INCOME
            "转账", "还款", "信用卡还款", "换汇" -> TransactionDisplayType.TRANSFER
            "退款", "退货" -> TransactionDisplayType.REFUND
            "报销", "待报销", "垫付" -> TransactionDisplayType.REIMBURSE
            "借贷", "借出", "借入" -> TransactionDisplayType.DEBT
            "分期", "分期还款", "贷款还款" -> TransactionDisplayType.LOAN_REPAY
            "投资", "买入", "卖出" -> TransactionDisplayType.INVEST
            else -> null
        }
    }

    private fun parseCurrency(raw: String?): Currency? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        Currency.fromCodeOrNull(trimmed)?.let { return it }
        return when (trimmed.uppercase()) {
            "RMB", "¥", "￥", "CN¥", "人民币", "元" -> Currency.CNY
            "$", "US$", "USD$", "美元", "美金" -> Currency.USD
            "HK$", "HKD$", "港币", "港元" -> Currency.HKD
            else -> null
        }
    }

    internal fun parsePositiveMoneyOrNull(raw: String?, currency: Currency): Money? {
        if (raw == null) return null
        var cleaned = raw.trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.equals("null", ignoreCase = true) ||
            cleaned.equals("nan", ignoreCase = true) ||
            cleaned.contains("infinity", ignoreCase = true)
        ) {
            return null
        }
        // Strip common leading/trailing currency tokens & thousand separators
        cleaned = cleaned
            .replace(",", "")
            .replace("_", "")
            .replace(" ", "")
            .removePrefix("HK$")
            .removePrefix("US$")
            .removePrefix("CNY")
            .removePrefix("RMB")
            .removePrefix("USD")
            .removePrefix("HKD")
            .removePrefix("¥")
            .removePrefix("￥")
            .removePrefix("$")
            .removeSuffix("元")
            .removeSuffix("块")
            .removeSuffix("人民币")
            .removeSuffix("美元")
            .removeSuffix("港币")
            .trim()

        if (cleaned.isEmpty() || cleaned.length > MAX_DECIMAL_TOKEN_LENGTH) return null
        if (!STRICT_DECIMAL_REGEX.matches(cleaned)) return null

        val decimal = try {
            BigDecimal(cleaned)
        } catch (_: NumberFormatException) {
            return null
        }
        // Guard against extreme exponents (e.g. 1e999999) before scaling
        val magnitudeExponent = decimal.precision() - decimal.scale()
        if (magnitudeExponent > MAX_INTEGER_DIGITS) return null
        if (decimal.compareTo(BigDecimal.ZERO) <= 0) return null
        if (decimal.compareTo(MAX_SUPPORTED_AMOUNT) > 0) return null

        return try {
            val money = Money.fromBigDecimal(decimal, currency, RoundingMode.HALF_UP)
            money.takeIf { it.amountMinor > 0L }
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun parseConfidenceDecimal(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.trim().removeSuffix("%").trim()
        if (cleaned.isEmpty() || !STRICT_DECIMAL_REGEX.matches(cleaned)) return null
        val parsed = try {
            BigDecimal(cleaned)
        } catch (_: NumberFormatException) {
            return null
        }
        val normalized = if (raw.trim().endsWith("%") || parsed.compareTo(BigDecimal.ONE) > 0 && parsed.compareTo(HUNDRED) <= 0) {
            parsed.divide(HUNDRED, 4, RoundingMode.HALF_UP)
        } else {
            parsed
        }
        val clamped = when {
            normalized.compareTo(BigDecimal.ZERO) < 0 -> BigDecimal.ZERO
            normalized.compareTo(BigDecimal.ONE) > 0 -> BigDecimal.ONE
            else -> normalized
        }
        return clamped.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    private data class ParsedTimestamp(
        val epochMs: Long,
        val isDateOnly: Boolean,
    )

    private fun parseOccurredAt(raw: String?, zoneId: ZoneId): ParsedTimestamp? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        try {
            return ParsedTimestamp(OffsetDateTime.parse(trimmed).toInstant().toEpochMilli(), isDateOnly = false)
        } catch (_: DateTimeParseException) {
        }
        try {
            return ParsedTimestamp(ZonedDateTime.parse(trimmed).toInstant().toEpochMilli(), isDateOnly = false)
        } catch (_: DateTimeParseException) {
        }
        try {
            return ParsedTimestamp(Instant.parse(trimmed).toEpochMilli(), isDateOnly = false)
        } catch (_: DateTimeParseException) {
        }
        for (formatter in LOCAL_DATE_TIME_FORMATTERS) {
            try {
                val ldt = LocalDateTime.parse(trimmed, formatter)
                return ParsedTimestamp(ldt.atZone(zoneId).toInstant().toEpochMilli(), isDateOnly = false)
            } catch (_: DateTimeParseException) {
            }
        }
        for (formatter in LOCAL_DATE_FORMATTERS) {
            try {
                val date = LocalDate.parse(trimmed, formatter)
                val noon = LocalDateTime.of(date, LocalTime.NOON)
                return ParsedTimestamp(noon.atZone(zoneId).toInstant().toEpochMilli(), isDateOnly = true)
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    private data class CatalogMatch<T>(
        val value: T,
        val isFuzzy: Boolean,
    )

    private fun expectedCategoryTypeFor(displayType: TransactionDisplayType): AccountType? = when (displayType) {
        TransactionDisplayType.EXPENSE,
        TransactionDisplayType.REFUND,
        TransactionDisplayType.REIMBURSE,
        -> AccountType.EXPENSE

        TransactionDisplayType.INCOME -> AccountType.INCOME

        TransactionDisplayType.TRANSFER,
        TransactionDisplayType.LOAN_REPAY,
        TransactionDisplayType.DEBT,
        TransactionDisplayType.INVEST,
        TransactionDisplayType.SPLIT,
        -> null
    }

    private fun matchCategory(
        rawPath: String,
        expectedType: AccountType,
        categories: List<AiPromptCategoryRef>,
    ): CatalogMatch<AiPromptCategoryRef>? {
        val pool = categories.filter { it.type == expectedType }
        if (pool.isEmpty()) return null

        val normInput = normalizePath(rawPath)
        // 1. Exact full path match ("餐饮/午餐", "餐饮-午餐", "餐饮·午餐")
        pool.firstOrNull { normalizePath(it.path) == normInput }?.let {
            return CatalogMatch(it, isFuzzy = false)
        }

        // 2. Exact leaf match when unambiguous (e.g. "午餐" -> "餐饮/午餐")
        val inputLeaf = normInput.substringAfterLast('/')
        val exactLeafMatches = pool.filter { normalizeName(it.leafName) == inputLeaf }
        if (exactLeafMatches.size == 1) {
            return CatalogMatch(exactLeafMatches.first(), isFuzzy = false)
        }

        // 3. Parent + partial leaf match (e.g. "交通/地铁" -> "交通/地铁公交")
        if ('/' in normInput) {
            val inputParent = normInput.substringBefore('/')
            val parentMatches = pool.filter {
                it.parentName != null && normalizeName(it.parentName) == inputParent
            }
            val leafFuzzy = parentMatches.filter {
                val leaf = normalizeName(it.leafName)
                leaf.contains(inputLeaf) || inputLeaf.contains(leaf)
            }
            if (leafFuzzy.size == 1) {
                return CatalogMatch(leafFuzzy.first(), isFuzzy = true)
            }
        }

        // 4. General unique substring match on path or leaf
        val fuzzyMatches = pool.filter {
            val normPath = normalizePath(it.path)
            val normLeaf = normalizeName(it.leafName)
            normPath.contains(normInput) || normInput.contains(normPath) ||
                (inputLeaf.length >= 2 && (normLeaf.contains(inputLeaf) || inputLeaf.contains(normLeaf)))
        }
        if (fuzzyMatches.size == 1) {
            return CatalogMatch(fuzzyMatches.first(), isFuzzy = true)
        }
        return null
    }

    private fun matchAccount(
        rawName: String,
        preferredCurrency: Currency,
        accounts: List<AiPromptAccountRef>,
        excludeAccountId: String? = null,
    ): CatalogMatch<AiPromptAccountRef>? {
        if (accounts.isEmpty()) return null
        val candidates = if (excludeAccountId != null) {
            accounts.filterNot { it.id == excludeAccountId }.ifEmpty { accounts }
        } else {
            accounts
        }
        val normInput = normalizeName(rawName)
        if (normInput.isEmpty()) return null

        // 1. Exact normalized name match (prefer same currency)
        val exact = candidates.filter { normalizeName(it.name) == normInput }
        if (exact.isNotEmpty()) {
            val picked = exact.firstOrNull { it.currency == preferredCurrency } ?: exact.first()
            return CatalogMatch(picked, isFuzzy = false)
        }

        // 2. Contains / substring fuzzy match (e.g. "招行" -> "招行信用卡", "微信" -> "微信零钱")
        val containsMatches = candidates.filter {
            val normAcc = normalizeName(it.name)
            normAcc.contains(normInput) || normInput.contains(normAcc)
        }
        if (containsMatches.size == 1) {
            return CatalogMatch(containsMatches.first(), isFuzzy = true)
        }
        val sameCurrencyContains = containsMatches.filter { it.currency == preferredCurrency }
        if (sameCurrencyContains.size == 1) {
            return CatalogMatch(sameCurrencyContains.first(), isFuzzy = true)
        }
        return null
    }

    private fun matchTag(
        rawTag: String,
        tags: List<AiPromptTagRef>,
    ): AiPromptTagRef? {
        val norm = normalizeName(rawTag)
        if (norm.isEmpty()) return null
        return tags.firstOrNull { normalizeName(it.name) == norm }
    }

    private fun extractStringList(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { it.stringContentOrNull()?.trim()?.takeIf(String::isNotEmpty) }
        is JsonPrimitive -> element.stringContentOrNull()
            ?.split(',', '，', '、')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        else -> emptyList()
    }

    private fun canonicalizeUncertainFieldName(raw: String): String = when (raw) {
        "category", "category_path", "分类" -> AiUncertainFieldKeys.CATEGORY_PATH
        "account", "account_name", "账户" -> AiUncertainFieldKeys.ACCOUNT_NAME
        "target_account", "target_account_name", "目标账户" -> AiUncertainFieldKeys.TARGET_ACCOUNT_NAME
        "time", "date", "occurred_at", "时间" -> AiUncertainFieldKeys.OCCURRED_AT
        "type", "display_type", "类型" -> AiUncertainFieldKeys.DISPLAY_TYPE
        "amount", "金额" -> AiUncertainFieldKeys.AMOUNT
        "currency", "币种" -> AiUncertainFieldKeys.CURRENCY
        "tags", "标签" -> AiUncertainFieldKeys.TAGS
        "payee", "商户" -> AiUncertainFieldKeys.PAYEE
        "note", "备注" -> AiUncertainFieldKeys.NOTE
        else -> raw
    }

    private fun normalizePath(raw: String): String = raw
        .trim()
        .lowercase()
        .replace('·', '/')
        .replace('>', '/')
        .replace('-', '/')
        .replace('\\', '/')
        .replace('：', '/')
        .replace(':', '/')
        .replace(Regex("\\s*/\\s*"), "/")
        .replace(Regex("/+"), "/")
        .trim('/')

    private fun normalizeName(raw: String): String = raw
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), "")

    private fun JsonElement?.stringContentOrNull(): String? {
        if (this == null || this is JsonNull) return null
        val primitive = this as? JsonPrimitive ?: return null
        val content = primitive.content
        return if (!primitive.isString && content.equals("null", ignoreCase = true)) null else content
    }

    private fun JsonElement?.rawScalarContentOrNull(): String? {
        if (this == null || this is JsonNull) return null
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.content
    }

    companion object {
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private val THINK_BLOCK_REGEX = Regex("(?is)<think>.*?</think>")
        private val CODE_FENCE_REGEX = Regex("(?s)```(?:json|JSON)?\\s*(.*?)\\s*```")
        private val STRICT_DECIMAL_REGEX = Regex("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d{1,3})?$")

        private val ITEM_ARRAY_ALIASES = listOf("transactions", "records", "drafts", "entries")
        private val HUNDRED = BigDecimal("100")
        private val MAX_SUPPORTED_AMOUNT = BigDecimal("900000000000000") // safely within Long.MAX_VALUE / 10_000
        private const val MAX_DECIMAL_TOKEN_LENGTH = 32
        private const val MAX_INTEGER_DIGITS = 15

        private val LOCAL_DATE_TIME_FORMATTERS = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"),
        )

        private val LOCAL_DATE_FORMATTERS = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy年M月d日"),
        )
    }
}
