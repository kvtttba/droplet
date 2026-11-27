package io.github.kvtttba.droplet.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockStateMachineTest {

    private class FakeClock(var nowMs: Long = 10_000L) : MonotonicClock {
        override fun elapsedRealtimeMs(): Long = nowMs
        fun advance(deltaMs: Long) {
            nowMs += deltaMs
        }
    }

    @Test
    fun coldStart_disabledLockStaysUnlocked() {
        val machine = AppLockStateMachine(FakeClock())
        machine.onColdStartLoaded(lockEnabled = false, relockTimeout = RelockTimeout.MINUTE_1)

        val state = machine.state.value
        assertEquals(LockStatus.UNLOCKED, state.status)
        assertFalse(state.isOverlayVisible)
        assertFalse(state.requiresColdStartEvaluation)
    }

    @Test
    fun coldStart_enabledLockStartsLockedAndUnlocksAfterAuth() {
        val machine = AppLockStateMachine(FakeClock())
        machine.onColdStartLoaded(lockEnabled = true, relockTimeout = RelockTimeout.MINUTE_1)

        assertEquals(LockStatus.LOCKED, machine.state.value.status)
        assertTrue(machine.state.value.isOverlayVisible)

        assertTrue(machine.onAuthenticationStarted())
        assertFalse(machine.onAuthenticationStarted())
        assertEquals(LockStatus.AUTHENTICATING, machine.state.value.status)
        assertTrue(machine.state.value.isAuthenticating)

        machine.onAuthenticationSoftFailure("指纹未匹配")
        assertEquals(LockStatus.AUTHENTICATING, machine.state.value.status)
        assertEquals("指纹未匹配", machine.state.value.lastErrorMessage)

        machine.onAuthenticationSucceeded()
        assertEquals(LockStatus.UNLOCKED, machine.state.value.status)
        assertFalse(machine.state.value.isOverlayVisible)
        assertNull(machine.state.value.lastErrorMessage)
    }

    @Test
    fun authenticationCancelledAndFailed_returnToLockedWithRetryMessage() {
        val machine = AppLockStateMachine(FakeClock())
        machine.onColdStartLoaded(lockEnabled = true, relockTimeout = RelockTimeout.MINUTE_1)

        assertTrue(machine.onAuthenticationStarted())
        machine.onAuthenticationResult(BiometricAuthResult.Cancelled)
        assertEquals(LockStatus.LOCKED, machine.state.value.status)
        assertEquals(AppLockStateMachine.DEFAULT_CANCEL_MESSAGE, machine.state.value.lastErrorMessage)

        assertTrue(machine.onAuthenticationStarted())
        assertNull(machine.state.value.lastErrorMessage)
        machine.onAuthenticationResult(BiometricAuthResult.Failed(errorCode = 7, message = "尝试次数过多"))
        assertEquals(LockStatus.LOCKED, machine.state.value.status)
        assertEquals("尝试次数过多", machine.state.value.lastErrorMessage)

        assertTrue(machine.onAuthenticationStarted())
        machine.onAuthenticationResult(BiometricAuthResult.Succeeded)
        assertEquals(LockStatus.UNLOCKED, machine.state.value.status)
    }

    @Test
    fun liveEnableLock_keepsCurrentSessionUnlockedUntilBackgroundTimeout() {
        val clock = FakeClock(1_000L)
        val machine = AppLockStateMachine(clock)
        machine.onColdStartLoaded(lockEnabled = false, relockTimeout = RelockTimeout.SECONDS_30)

        machine.onSettingsChanged(lockEnabled = true, relockTimeout = RelockTimeout.SECONDS_30)
        assertEquals(LockStatus.UNLOCKED, machine.state.value.status)
        assertTrue(machine.state.value.isLockEnabled)

        machine.onAppBackgrounded()
        clock.advance(30_000L)
        machine.onAppForegrounded()
        assertEquals(LockStatus.LOCKED, machine.state.value.status)

        machine.onSettingsChanged(lockEnabled = false, relockTimeout = RelockTimeout.SECONDS_30)
        assertEquals(LockStatus.UNLOCKED, machine.state.value.status)
        assertFalse(machine.state.value.isLockEnabled)
    }

    @Test
    fun relockTimeout_immediateLocksAsSoonAsBackgrounded() {
        val clock = FakeClock(5_000L)
        val machine = AppLockStateMachine(clock)
        machine.onColdStartLoaded(lockEnabled = true, relockTimeout = RelockTimeout.IMMEDIATE)
        machine.onAuthenticationSucceeded()
        assertEquals(LockStatus.UNLOCKED, machine.state.value.status)

        machine.onAppBackgrounded()
        assertEquals(LockStatus.LOCKED, machine.state.value.status)

        machine.onAppForegrounded()
        assertEquals(LockStatus.LOCKED, machine.state.value.status)
    }

    @Test
    fun relockTimeout_seconds30ExactBoundary() {
        val clock = FakeClock(100_000L)
        val machine = AppLockStateMachine(clock)
        machine.onColdStartLoaded(lockEnabled = true, relockTimeout = RelockTimeout.SECONDS_30)
        machine.onAuthenticationSucceeded()

        machine.onAppBackgrounded()
        clock.advance(29_999L)
        machine.onAppForegrounded()
        assertEquals(LockStatus.UNLOCKED, machine.state.value.status)

        machine.onAppBackgrounded()
        clock.advance(30_000L)
        machine.onAppForegrounded()
        assertEquals(LockStatus.LOCKED, machine.state.value.status)
    }

    @Test
    fun relockTimeout_minute1ExactBoundary() {
        val clock = FakeClock(200_000L)
        val machine = AppLockStateMachine(clock)
        machine.onColdStartLoaded(lockEnabled = true, relockTimeout = RelockTimeout.MINUTE_1)
        machine.onAuthenticationSucceeded()

        machine.onAppBackgrounded()
        clock.advance(59_999L)
        machine.onAppForegrounded()
        assertEquals(LockStatus.UNLOCKED, machine.state.value.status)

        machine.onAppBackgrounded()
        clock.advance(60_000L)
        machine.onAppForegrounded()
        assertEquals(LockStatus.LOCKED, machine.state.value.status)
    }

    @Test
    fun relockTimeout_minutes5ExactBoundary() {
        val clock = FakeClock(500_000L)
        val machine = AppLockStateMachine(clock)
        machine.onColdStartLoaded(lockEnabled = true, relockTimeout = RelockTimeout.MINUTES_5)
        machine.onAuthenticationSucceeded()

        machine.onAppBackgrounded()
        clock.advance(299_999L)
        machine.onAppForegrounded()
        assertEquals(LockStatus.UNLOCKED, machine.state.value.status)

        machine.onAppBackgrounded()
        clock.advance(300_000L)
        machine.onAppForegrounded()
        assertEquals(LockStatus.LOCKED, machine.state.value.status)
    }

    @Test
    fun relockTimeout_neverOnlyLocksOnColdStart() {
        val clock = FakeClock(1_000L)
        val machine = AppLockStateMachine(clock)
        machine.onColdStartLoaded(lockEnabled = true, relockTimeout = RelockTimeout.NEVER)
        assertEquals(LockStatus.LOCKED, machine.state.value.status)

        machine.onAuthenticationSucceeded()
        machine.onAppBackgrounded()
        clock.advance(36_000_000L) // 10 hours
        machine.onAppForegrounded()
        assertEquals(LockStatus.UNLOCKED, machine.state.value.status)
    }

    @Test
    fun negativeElapsedClockAnomaly_failsClosedAndLocks() {
        val clock = FakeClock(50_000L)
        val machine = AppLockStateMachine(clock)
        machine.onColdStartLoaded(lockEnabled = true, relockTimeout = RelockTimeout.MINUTES_5)
        machine.onAuthenticationSucceeded()

        machine.onAppBackgrounded()
        clock.nowMs = 10_000L
        machine.onAppForegrounded()

        assertEquals(LockStatus.LOCKED, machine.state.value.status)
    }

    @Test
    fun backgroundDuringSystemDeviceCredentialPrompt_doesNotBreakUnlockFlow() {
        val clock = FakeClock(10_000L)
        val machine = AppLockStateMachine(clock)
        machine.onColdStartLoaded(lockEnabled = true, relockTimeout = RelockTimeout.IMMEDIATE)

        assertTrue(machine.onAuthenticationStarted())
        // System ConfirmDeviceCredentialActivity stops our Activity while authenticating
        machine.onAppBackgrounded()
        assertEquals(LockStatus.AUTHENTICATING, machine.state.value.status)

        clock.advance(5_000L)
        machine.onAppForegrounded()
        assertEquals(LockStatus.AUTHENTICATING, machine.state.value.status)

        machine.onAuthenticationSucceeded()
        assertEquals(LockStatus.UNLOCKED, machine.state.value.status)
    }

    @Test
    fun lockNow_respectsLockEnabledState() {
        val machine = AppLockStateMachine(FakeClock())
        machine.onColdStartLoaded(lockEnabled = false, relockTimeout = RelockTimeout.MINUTE_1)
        machine.lockNow()
        assertEquals(LockStatus.UNLOCKED, machine.state.value.status)

        machine.onSettingsChanged(lockEnabled = true, relockTimeout = RelockTimeout.MINUTE_1)
        machine.lockNow()
        assertEquals(LockStatus.LOCKED, machine.state.value.status)
    }
}
