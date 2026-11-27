package io.github.kvtttba.droplet.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Monotonic elapsed-time source (backed by [android.os.SystemClock.elapsedRealtime] in production)
 * so relock timeout evaluation is immune to wall-clock adjustments.
 */
fun interface MonotonicClock {
    fun elapsedRealtimeMs(): Long
}

enum class LockStatus {
    UNLOCKED,
    LOCKED,
    AUTHENTICATING,
}

data class AppLockState(
    val status: LockStatus = LockStatus.UNLOCKED,
    val isLockEnabled: Boolean = false,
    val relockTimeout: RelockTimeout = RelockTimeout.DEFAULT,
    val lastBackgroundedAtMs: Long? = null,
    val lastErrorMessage: String? = null,
    val requiresColdStartEvaluation: Boolean = true,
) {
    val isOverlayVisible: Boolean
        get() = isLockEnabled && status != LockStatus.UNLOCKED

    val isAuthenticating: Boolean
        get() = status == LockStatus.AUTHENTICATING
}

/**
 * Pure deterministic state machine controlling cold-start locking, background timeout re-locking,
 * authentication-in-flight protection (e.g. system PIN/password fallback Activity), and retries.
 */
class AppLockStateMachine(
    private val clock: MonotonicClock,
) {
    private val _state = MutableStateFlow(AppLockState())
    val state: StateFlow<AppLockState> = _state.asStateFlow()

    /**
     * Hydrates the initial persisted privacy configuration on process cold start.
     * Subsequent calls are treated as regular live setting updates.
     */
    fun onColdStartLoaded(
        lockEnabled: Boolean,
        relockTimeout: RelockTimeout,
    ) {
        _state.update { current ->
            if (!current.requiresColdStartEvaluation) {
                applyLiveSettingsChange(current, lockEnabled, relockTimeout)
            } else {
                current.copy(
                    status = if (lockEnabled) LockStatus.LOCKED else LockStatus.UNLOCKED,
                    isLockEnabled = lockEnabled,
                    relockTimeout = relockTimeout,
                    lastBackgroundedAtMs = null,
                    lastErrorMessage = null,
                    requiresColdStartEvaluation = false,
                )
            }
        }
    }

    /**
     * Applies live changes to lock preferences made while the app is running.
     * Turning lock ON inside settings keeps the current session unlocked until background timeout
     * or next cold start; turning lock OFF immediately unlocks.
     */
    fun onSettingsChanged(
        lockEnabled: Boolean,
        relockTimeout: RelockTimeout,
    ) {
        _state.update { current ->
            if (current.requiresColdStartEvaluation) {
                current.copy(
                    status = if (lockEnabled) LockStatus.LOCKED else LockStatus.UNLOCKED,
                    isLockEnabled = lockEnabled,
                    relockTimeout = relockTimeout,
                    lastBackgroundedAtMs = null,
                    lastErrorMessage = null,
                    requiresColdStartEvaluation = false,
                )
            } else {
                applyLiveSettingsChange(current, lockEnabled, relockTimeout)
            }
        }
    }

    /**
     * Records app moving to background.
     * - If lock is disabled: no-op.
     * - If currently [LockStatus.AUTHENTICATING] (e.g. system Device Credential Activity launched):
     *   preserves authentication state without clobbering the callback flow.
     * - If [RelockTimeout.IMMEDIATE]: locks immediately on backgrounding.
     * - If [RelockTimeout.NEVER]: does not record a relock deadline.
     */
    fun onAppBackgrounded() {
        _state.update { current ->
            if (!current.isLockEnabled) {
                return@update current.copy(lastBackgroundedAtMs = null)
            }
            if (current.status != LockStatus.UNLOCKED) {
                return@update current
            }
            val now = clock.elapsedRealtimeMs()
            when (current.relockTimeout) {
                RelockTimeout.IMMEDIATE -> current.copy(
                    status = LockStatus.LOCKED,
                    lastBackgroundedAtMs = now,
                    lastErrorMessage = null,
                )
                RelockTimeout.NEVER -> current.copy(
                    lastBackgroundedAtMs = null,
                )
                RelockTimeout.SECONDS_30,
                RelockTimeout.MINUTE_1,
                RelockTimeout.MINUTES_5,
                -> current.copy(
                    lastBackgroundedAtMs = now,
                )
            }
        }
    }

    /**
     * Evaluates whether the elapsed background duration met or exceeded the configured timeout.
     */
    fun onAppForegrounded() {
        _state.update { current ->
            if (!current.isLockEnabled) {
                return@update current.copy(
                    status = LockStatus.UNLOCKED,
                    lastBackgroundedAtMs = null,
                    lastErrorMessage = null,
                )
            }
            if (current.status != LockStatus.UNLOCKED) {
                return@update current.copy(lastBackgroundedAtMs = null)
            }
            val backgroundedAt = current.lastBackgroundedAtMs ?: return@update current
            val timeoutMs = current.relockTimeout.durationMs
            if (timeoutMs == null) {
                return@update current.copy(lastBackgroundedAtMs = null)
            }
            val elapsed = clock.elapsedRealtimeMs() - backgroundedAt
            val shouldLock = elapsed < 0L || elapsed >= timeoutMs
            current.copy(
                status = if (shouldLock) LockStatus.LOCKED else LockStatus.UNLOCKED,
                lastBackgroundedAtMs = null,
                lastErrorMessage = if (shouldLock) null else current.lastErrorMessage,
            )
        }
    }

    /**
     * Immediately locks the app if biometric lock is enabled.
     */
    fun lockNow() {
        _state.update { current ->
            if (!current.isLockEnabled) {
                current
            } else {
                current.copy(
                    status = LockStatus.LOCKED,
                    lastBackgroundedAtMs = null,
                    lastErrorMessage = null,
                )
            }
        }
    }

    /**
     * Marks authentication prompt as in-flight. Returns `true` only if the machine transitioned
     * from [LockStatus.LOCKED] to [LockStatus.AUTHENTICATING].
     */
    fun onAuthenticationStarted(): Boolean {
        var started = false
        _state.update { current ->
            if (current.isLockEnabled && current.status == LockStatus.LOCKED) {
                started = true
                current.copy(
                    status = LockStatus.AUTHENTICATING,
                    lastErrorMessage = null,
                )
            } else {
                started = false
                current
            }
        }
        return started
    }

    /**
     * Handles a non-terminal authentication mismatch (e.g. unrecognized fingerprint while prompt
     * remains open).
     */
    fun onAuthenticationSoftFailure(message: String) {
        _state.update { current ->
            if (current.isLockEnabled && current.status == LockStatus.AUTHENTICATING) {
                current.copy(lastErrorMessage = message.ifBlank { DEFAULT_RETRY_MESSAGE })
            } else {
                current
            }
        }
    }

    fun onAuthenticationSucceeded() {
        _state.update { current ->
            current.copy(
                status = LockStatus.UNLOCKED,
                lastBackgroundedAtMs = null,
                lastErrorMessage = null,
            )
        }
    }

    fun onAuthenticationCancelled(message: String? = null) {
        _state.update { current ->
            if (!current.isLockEnabled) {
                current.copy(
                    status = LockStatus.UNLOCKED,
                    lastErrorMessage = null,
                )
            } else {
                current.copy(
                    status = LockStatus.LOCKED,
                    lastErrorMessage = message?.takeIf { it.isNotBlank() } ?: DEFAULT_CANCEL_MESSAGE,
                )
            }
        }
    }

    fun onAuthenticationFailed(message: String) {
        _state.update { current ->
            if (!current.isLockEnabled) {
                current.copy(
                    status = LockStatus.UNLOCKED,
                    lastErrorMessage = null,
                )
            } else {
                current.copy(
                    status = LockStatus.LOCKED,
                    lastErrorMessage = message.ifBlank { DEFAULT_RETRY_MESSAGE },
                )
            }
        }
    }

    fun onAuthenticationResult(result: BiometricAuthResult) {
        when (result) {
            BiometricAuthResult.Succeeded -> onAuthenticationSucceeded()
            BiometricAuthResult.Cancelled -> onAuthenticationCancelled()
            is BiometricAuthResult.Failed -> onAuthenticationFailed(result.message)
        }
    }

    private fun applyLiveSettingsChange(
        current: AppLockState,
        lockEnabled: Boolean,
        relockTimeout: RelockTimeout,
    ): AppLockState = if (!lockEnabled) {
        current.copy(
            status = LockStatus.UNLOCKED,
            isLockEnabled = false,
            relockTimeout = relockTimeout,
            lastBackgroundedAtMs = null,
            lastErrorMessage = null,
        )
    } else {
        current.copy(
            isLockEnabled = true,
            relockTimeout = relockTimeout,
            lastBackgroundedAtMs = if (relockTimeout == RelockTimeout.NEVER) {
                null
            } else {
                current.lastBackgroundedAtMs
            },
        )
    }

    companion object {
        const val DEFAULT_CANCEL_MESSAGE: String = "已取消验证，请点击下方按钮重试"
        const val DEFAULT_RETRY_MESSAGE: String = "身份验证未通过，请重试"
    }
}
