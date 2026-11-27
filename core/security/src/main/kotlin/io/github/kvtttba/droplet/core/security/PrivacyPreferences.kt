package io.github.kvtttba.droplet.core.security

/**
 * Background inactivity window after which the app automatically re-locks when biometric lock
 * is enabled.
 */
enum class RelockTimeout(
    val storageKey: String,
    val label: String,
    val description: String,
    val durationMs: Long?,
) {
    IMMEDIATE(
        storageKey = "IMMEDIATE",
        label = "立即",
        description = "切至后台后立即重新锁定",
        durationMs = 0L,
    ),
    SECONDS_30(
        storageKey = "SECONDS_30",
        label = "30 秒",
        description = "离开超过 30 秒后重新锁定",
        durationMs = 30_000L,
    ),
    MINUTE_1(
        storageKey = "MINUTE_1",
        label = "1 分钟",
        description = "离开超过 1 分钟后重新锁定",
        durationMs = 60_000L,
    ),
    MINUTES_5(
        storageKey = "MINUTES_5",
        label = "5 分钟",
        description = "离开超过 5 分钟后重新锁定",
        durationMs = 300_000L,
    ),
    NEVER(
        storageKey = "NEVER",
        label = "关闭",
        description = "仅冷启动时锁定，切后台不自动重锁",
        durationMs = null,
    ),
    ;

    companion object {
        val DEFAULT: RelockTimeout = MINUTE_1

        fun fromStorageKey(key: String?): RelockTimeout =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/**
 * Persisted appearance theme mode independent of Compose/UI modules.
 */
enum class AppThemePreference(
    val storageKey: String,
) {
    SYSTEM("SYSTEM"),
    LIGHT("LIGHT"),
    DARK("DARK"),
    ;

    companion object {
        val DEFAULT: AppThemePreference = SYSTEM

        fun fromStorageKey(key: String?): AppThemePreference =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/**
 * Persisted local security, privacy, and appearance preferences.
 */
data class PrivacyPreferences(
    val biometricLockEnabled: Boolean = false,
    val relockTimeout: RelockTimeout = RelockTimeout.DEFAULT,
    val flagSecureEnabled: Boolean = false,
    val amountsHidden: Boolean = false,
    val themeMode: AppThemePreference = AppThemePreference.DEFAULT,
    val dynamicColor: Boolean = true,
)
