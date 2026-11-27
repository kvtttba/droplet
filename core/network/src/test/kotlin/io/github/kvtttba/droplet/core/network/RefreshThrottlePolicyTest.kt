package io.github.kvtttba.droplet.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshThrottlePolicyTest {

    @Test
    fun evaluate_respectsDefaultFiveMinuteWindowAndForceRefresh() {
        val policy = RefreshThrottlePolicy()
        assertEquals(300_000L, policy.windowMs)

        // First fetch (no previous timestamp) is always allowed
        assertEquals(ThrottleDecision.Allow, policy.evaluate(lastSuccessAtMs = null, nowEpochMs = 1_000L))

        // Inside 5-minute window -> throttled with exact remaining time
        val inside0 = policy.evaluate(lastSuccessAtMs = 100_000L, nowEpochMs = 100_000L)
        assertTrue(inside0 is ThrottleDecision.Throttled)
        assertEquals(300_000L, (inside0 as ThrottleDecision.Throttled).remainingMs)

        val insideBoundary = policy.evaluate(lastSuccessAtMs = 100_000L, nowEpochMs = 399_999L)
        assertTrue(insideBoundary is ThrottleDecision.Throttled)
        assertEquals(1L, (insideBoundary as ThrottleDecision.Throttled).remainingMs)

        // Force refresh bypasses throttle even 1ms after last success
        assertEquals(
            ThrottleDecision.Allow,
            policy.evaluate(lastSuccessAtMs = 100_000L, nowEpochMs = 100_001L, forceRefresh = true),
        )

        // At and past the 5-minute boundary -> allowed
        assertEquals(
            ThrottleDecision.Allow,
            policy.evaluate(lastSuccessAtMs = 100_000L, nowEpochMs = 400_000L),
        )
        assertEquals(
            ThrottleDecision.Allow,
            policy.evaluate(lastSuccessAtMs = 100_000L, nowEpochMs = 500_000L),
        )

        // Wall-clock moving backwards -> safely allow refresh instead of blocking indefinitely
        assertEquals(
            ThrottleDecision.Allow,
            policy.evaluate(lastSuccessAtMs = 500_000L, nowEpochMs = 400_000L),
        )
    }

    @Test
    fun zeroWindow_alwaysAllows() {
        val policy = RefreshThrottlePolicy(windowMs = 0L)
        assertEquals(ThrottleDecision.Allow, policy.evaluate(lastSuccessAtMs = 100_000L, nowEpochMs = 100_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeWindow_rejected() {
        RefreshThrottlePolicy(windowMs = -1L)
    }
}
