package io.github.kvtttba.droplet.core.network

/**
 * Injectable wall-clock abstraction for deterministic timestamping and throttling tests.
 */
fun interface NetworkClock {
    fun nowEpochMs(): Long

    companion object {
        val SYSTEM: NetworkClock = NetworkClock { System.currentTimeMillis() }
    }
}

sealed interface ThrottleDecision {
    data object Allow : ThrottleDecision

    data class Throttled(
        val lastSuccessAtMs: Long,
        val remainingMs: Long,
    ) : ThrottleDecision
}

/**
 * Guards against redundant network refreshes when opening the Assets screen or triggering
 * background syncs within the configured throttle window (default 5 minutes).
 */
class RefreshThrottlePolicy(
    val windowMs: Long = MarketNetworkContract.DEFAULT_REFRESH_THROTTLE_MS,
) {
    init {
        require(windowMs >= 0L) { "Throttle windowMs must be non-negative, got $windowMs" }
    }

    fun evaluate(
        lastSuccessAtMs: Long?,
        nowEpochMs: Long,
        forceRefresh: Boolean = false,
    ): ThrottleDecision {
        if (forceRefresh || lastSuccessAtMs == null || windowMs == 0L) {
            return ThrottleDecision.Allow
        }
        val elapsedMs = nowEpochMs - lastSuccessAtMs
        if (elapsedMs < 0L || elapsedMs >= windowMs) {
            return ThrottleDecision.Allow
        }
        return ThrottleDecision.Throttled(
            lastSuccessAtMs = lastSuccessAtMs,
            remainingMs = windowMs - elapsedMs,
        )
    }
}
