package io.github.kvtttba.droplet.security

import io.github.kvtttba.droplet.core.security.AppLockState
import io.github.kvtttba.droplet.core.security.AppLockStateMachine
import io.github.kvtttba.droplet.core.security.AppThemePreference
import io.github.kvtttba.droplet.core.security.BiometricAuthResult
import io.github.kvtttba.droplet.core.security.PrivacyPreferences
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.di.ApplicationScope
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-scoped coordinator bridging persisted [PrivacyPreferencesRepository] with
 * [AppLockStateMachine] and exposing reactive state to [io.github.kvtttba.droplet.MainActivity].
 */
@Singleton
class AppLockCoordinator @Inject constructor(
    private val preferencesRepository: PrivacyPreferencesRepository,
    private val appLockStateMachine: AppLockStateMachine,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    private val _preferences = MutableStateFlow(PrivacyPreferences())
    val preferences: StateFlow<PrivacyPreferences> = _preferences.asStateFlow()

    private val _isHydrated = MutableStateFlow(false)
    val isHydrated: StateFlow<Boolean> = _isHydrated.asStateFlow()

    val lockState: StateFlow<AppLockState> = appLockStateMachine.state

    fun start() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch {
            preferencesRepository.preferences.collect { prefs ->
                _preferences.value = prefs
                if (!_isHydrated.value) {
                    appLockStateMachine.onColdStartLoaded(
                        lockEnabled = prefs.biometricLockEnabled,
                        relockTimeout = prefs.relockTimeout,
                    )
                    _isHydrated.value = true
                } else {
                    appLockStateMachine.onSettingsChanged(
                        lockEnabled = prefs.biometricLockEnabled,
                        relockTimeout = prefs.relockTimeout,
                    )
                }
            }
        }
    }

    fun onAppBackgrounded() {
        appLockStateMachine.onAppBackgrounded()
    }

    fun onAppForegrounded() {
        appLockStateMachine.onAppForegrounded()
    }

    fun onAuthenticationStarted(): Boolean = appLockStateMachine.onAuthenticationStarted()

    fun onAuthenticationSoftFailure(message: String) {
        appLockStateMachine.onAuthenticationSoftFailure(message)
    }

    fun onAuthenticationResult(result: BiometricAuthResult) {
        appLockStateMachine.onAuthenticationResult(result)
    }

    fun toggleAmountsHidden() {
        applicationScope.launch {
            preferencesRepository.toggleAmountsHidden()
        }
    }

    fun setThemeMode(themeMode: AppThemePreference) {
        applicationScope.launch {
            preferencesRepository.setThemeMode(themeMode)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        applicationScope.launch {
            preferencesRepository.setDynamicColor(enabled)
        }
    }
}
