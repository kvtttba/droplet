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
}
