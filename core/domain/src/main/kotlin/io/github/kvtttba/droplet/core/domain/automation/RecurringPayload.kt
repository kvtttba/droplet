package io.github.kvtttba.droplet.core.domain.automation

import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Structured, strictly fixed-point transaction blueprint persisted inside both
 * `recurring_rules.template_payload_json` and `templates.template_payload_json`.
 *
 * Uses `Long` minor units (`scale = 4`) exclusively — never floating point.
 */
@Serializable
data class RecurringPayload(
    val displayType: TransactionDisplayType,
    val amountMinor: Long,
    val currency: Currency = Currency.CNY,
    val categoryAccountId: String? = null,
    val accountId: String,
    val targetAccountId: String? = null,
    val targetAmountMinor: Long? = null,
    val targetCurrency: Currency? = null,
    val payee: String? = null,
    val tagIds: List<String> = emptyList(),
    val note: String? = null,
    val isReimbursable: Boolean = false,
    val reimbursementAccountId: String? = null,
) {
    init {
        require(displayType in SUPPORTED_DISPLAY_TYPES) {
            "模板/周期仅支持支出、收入或转账类型，当前为 $displayType"
        }
        require(amountMinor > 0L) {
            "金额必须大于 0"
        }
        require(accountId.isNotBlank()) {
            "账户不能为空"
        }
        when (displayType) {
            TransactionDisplayType.EXPENSE -> {
                if (!isReimbursable) {
                    require(!categoryAccountId.isNullOrBlank()) {
                        "支出分类不能为空"
                    }
                }
            }
            TransactionDisplayType.INCOME -> {
                require(!isReimbursable) { "收入不支持标记为待报销" }
                require(!categoryAccountId.isNullOrBlank()) {
                    "收入分类不能为空"
                }
            }
            TransactionDisplayType.TRANSFER -> {
                require(!isReimbursable) { "转账不支持标记为待报销" }
                require(!targetAccountId.isNullOrBlank()) {
                    "转入账户不能为空"
                }
                require(targetAccountId != accountId) {
                    "转出账户与转入账户不能相同"
                }
                if (targetAmountMinor != null) {
                    require(targetAmountMinor > 0L) { "到账金额必须大于 0" }
                }
            }
            else -> Unit
        }
    }

    val amount: Money
        get() = Money(amountMinor, currency)

    val targetAmount: Money?
        get() = if (targetAmountMinor != null && targetCurrency != null) {
            Money(targetAmountMinor, targetCurrency)
        } else {
            null
        }

    companion object {
        val SUPPORTED_DISPLAY_TYPES: Set<TransactionDisplayType> = setOf(
            TransactionDisplayType.EXPENSE,
            TransactionDisplayType.INCOME,
            TransactionDisplayType.TRANSFER,
        )
    }
}

/**
 * Encapsulates JSON encoding/decoding of [RecurringPayload] without exposing
 * `kotlinx.serialization.json.Json` across module boundaries.
 */
object RecurringPayloadCodec {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(payload: RecurringPayload): String = json.encodeToString(payload)

    fun decode(rawJson: String): RecurringPayload = json.decodeFromString(rawJson)

    fun decodeOrNull(rawJson: String): RecurringPayload? = runCatching {
        decode(rawJson)
    }.getOrNull()
}
