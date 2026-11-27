package io.github.kvtttba.droplet.feature.settings

import androidx.fragment.app.FragmentActivity
import io.github.kvtttba.droplet.core.security.AppLockStateMachine
import io.github.kvtttba.droplet.core.security.AppThemePreference
import io.github.kvtttba.droplet.core.security.BiometricAuthResult
import io.github.kvtttba.droplet.core.security.BiometricAuthenticator
import io.github.kvtttba.droplet.core.security.BiometricCapability
import io.github.kvtttba.droplet.core.security.LockStatus
import io.github.kvtttba.droplet.core.security.MonotonicClock
import io.github.kvtttba.droplet.core.security.PrivacyPreferences
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.core.security.RelockTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacySecurityViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakePreferencesRepository : PrivacyPreferencesRepository {
        val flow = MutableStateFlow(PrivacyPreferences())
        override val preferences: Flow<PrivacyPreferences> = flow

        override suspend fun setBiometricLockEnabled(enabled: Boolean) {
            flow.update { it.copy(biometricLockEnabled = enabled) }
        }

        override suspend fun setRelockTimeout(timeout: RelockTimeout) {
            flow.update { it.copy(relockTimeout = timeout) }
        }

        override suspend fun setFlagSecureEnabled(enabled: Boolean) {
            flow.update { it.copy(flagSecureEnabled = enabled) }
        }

        override suspend fun setAmountsHidden(hidden: Boolean) {
            flow.update { it.copy(amountsHidden = hidden) }
        }

        override suspend fun toggleAmountsHidden(): Boolean {
            var next = false
            flow.update {
                next = !it.amountsHidden
                it.copy(amountsHidden = next)
            }
            return next
        }

        override suspend fun setThemeMode(themeMode: AppThemePreference) {
            flow.update { it.copy(themeMode = themeMode) }
        }

        override suspend fun setDynamicColor(enabled: Boolean) {
            flow.update { it.copy(dynamicColor = enabled) }
        }
    }

    private class FakeBiometricAuthenticator(
        var capability: BiometricCapability = BiometricCapability.AVAILABLE,
    ) : BiometricAuthenticator {
        override fun checkCapability(): BiometricCapability = capability

        override fun authenticate(
            activity: FragmentActivity,
            title: String,
            subtitle: String,
            onSoftFailure: (String) -> Unit,
            onResult: (BiometricAuthResult) -> Unit,
        ) {
            onResult(BiometricAuthResult.Succeeded)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun enableBiometricLock_requiresCapabilityAndVerification() = runTest(testDispatcher) {
        val repo = FakePreferencesRepository()
        val authenticator = FakeBiometricAuthenticator(BiometricCapability.NONE_ENROLLED)
        val lockMachine = AppLockStateMachine(MonotonicClock { 0L })
        val viewModel = PrivacySecurityViewModel(repo, authenticator, lockMachine)

        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        // Cannot enable when NONE_ENROLLED
        var verifyCalled = false
        viewModel.onBiometricLockToggleRequested(true) { verifyCalled = true }
        advanceUntilIdle()
        assertFalse(verifyCalled)
        assertFalse(viewModel.uiState.value.biometricLockEnabled)
        assertNotNull(viewModel.uiState.value.userMessage)
        viewModel.clearUserMessage()

        // After enrolling in system settings, cancelled verification keeps switch OFF
        authenticator.capability = BiometricCapability.AVAILABLE
        viewModel.refreshCapability()
        viewModel.onBiometricLockToggleRequested(true) { cb -> cb(false) }
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.biometricLockEnabled)

        // Successful verification enables lock
        viewModel.onBiometricLockToggleRequested(true) { cb -> cb(true) }
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.biometricLockEnabled)

        // Disabling lock works directly
        viewModel.onBiometricLockToggleRequested(false) { error("Should not verify when turning off") }
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.biometricLockEnabled)

        collector.cancel()
    }

    @Test
    fun togglesAndRelockTimeout_updateRepositoryAndLockNow() = runTest(testDispatcher) {
        val repo = FakePreferencesRepository()
        val authenticator = FakeBiometricAuthenticator(BiometricCapability.AVAILABLE)
        val lockMachine = AppLockStateMachine(MonotonicClock { 1_000L })
        lockMachine.onColdStartLoaded(lockEnabled = true, relockTimeout = RelockTimeout.MINUTE_1)
        lockMachine.onAuthenticationSucceeded()

        val viewModel = PrivacySecurityViewModel(repo, authenticator, lockMachine)
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setRelockTimeout(RelockTimeout.SECONDS_30)
        viewModel.setFlagSecureEnabled(true)
        viewModel.setAmountsHidden(true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(RelockTimeout.SECONDS_30, state.relockTimeout)
        assertTrue(state.flagSecureEnabled)
        assertTrue(state.amountsHidden)

        viewModel.lockNow()
        assertEquals(LockStatus.LOCKED, lockMachine.state.value.status)

        collector.cancel()
    }
}
