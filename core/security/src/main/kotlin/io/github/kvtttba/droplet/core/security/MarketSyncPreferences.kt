package io.github.kvtttba.droplet.core.security

/**
 * Background automatic refresh cadence for market quotes and FX rates via WorkManager.
 */
enum class MarketSyncInterval(
    val storageKey: String,
    val label: String,
    val description: String,
    val repeatIntervalHours: Long?,
) {
    MANUAL_ONLY(
        storageKey = "MANUAL_ONLY",
        label = "仅手动",
        description = "关闭后台定时任务，仅在进入资产页或手动点击时刷新",
        repeatIntervalHours = null,
    ),
    HOURS_6(
        storageKey = "HOURS_6",
        label = "每 6 小时",
        description = "后台每 6 小时检查并同步最新汇率与持仓行情",
        repeatIntervalHours = 6L,
    ),
    HOURS_12(
        storageKey = "HOURS_12",
        label = "每 12 小时",
        description = "默认推荐，每日两次在后台同步汇率与行情",
        repeatIntervalHours = 12L,
    ),
    DAILY(
        storageKey = "DAILY",
        label = "每日一次",
        description = "后台每 24 小时同步一次汇率与持仓净值，更省流量",
        repeatIntervalHours = 24L,
    ),
    ;

    val isPeriodicEnabled: Boolean
        get() = repeatIntervalHours != null

    companion object {
        val DEFAULT: MarketSyncInterval = HOURS_12

        fun fromStorageKey(key: String?): MarketSyncInterval =
            entries.firstOrNull { it.storageKey.equals(key?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Persisted preferences governing background market quote & FX synchronization.
 */
data class MarketSyncPreferences(
    val refreshInterval: MarketSyncInterval = MarketSyncInterval.DEFAULT,
    val wifiOnly: Boolean = false,
)
