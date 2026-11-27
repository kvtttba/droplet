package io.github.kvtttba.droplet.core.network

/**
 * Market quote & FX rate network contract constants shared across `:core:network`,
 * `:core:database`, and `:app`.
 */
object MarketNetworkContract {
    const val DEFAULT_REFRESH_THROTTLE_MINUTES: Long = 5L
    const val DEFAULT_REFRESH_THROTTLE_MS: Long = DEFAULT_REFRESH_THROTTLE_MINUTES * 60_000L

    const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 8_000L
    const val DEFAULT_READ_TIMEOUT_MS: Long = 10_000L
    const val DEFAULT_WRITE_TIMEOUT_MS: Long = 10_000L
    const val DEFAULT_CALL_TIMEOUT_MS: Long = 15_000L
    const val DEFAULT_MAX_CONCURRENCY: Int = 4

    const val DEFAULT_USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Droplet/0.1"

    const val MANUAL_OVERRIDE_PROVIDER_ID: String = "manual"
}
