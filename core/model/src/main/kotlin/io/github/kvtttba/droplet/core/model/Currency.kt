package io.github.kvtttba.droplet.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Currency(
    val code: String,
    val symbol: String,
    val displayName: String,
) {
    CNY(code = "CNY", symbol = "¥", displayName = "人民币"),
    USD(code = "USD", symbol = "$", displayName = "美元"),
    HKD(code = "HKD", symbol = "HK$", displayName = "港币"),
    ;

    companion object {
        val DEFAULT: Currency = CNY

        fun fromCodeOrNull(code: String): Currency? {
            val normalized = code.trim().uppercase()
            return entries.firstOrNull { it.code == normalized }
        }

        fun fromCode(code: String): Currency =
            fromCodeOrNull(code)
                ?: throw IllegalArgumentException("Unsupported currency code: '$code'")
    }
}
