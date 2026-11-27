package io.github.kvtttba.droplet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.security.AppLockStateMachine
import io.github.kvtttba.droplet.core.security.BiometricAuthenticator
import io.github.kvtttba.droplet.core.security.BiometricCapability
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.core.security.RelockTimeout
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PrivacySecurityUiState(
    val biometricLockEnabled: Boolean = false,
    val relockTimeout: RelockTimeout = RelockTimeout.DEFAULT,
    val flagSecureEnabled: Boolean = false,
    val amountsHidden: Boolean = false,
    val biometricCapability: BiometricCapability = BiometricCapability.AVAILABLE,
    val userMessage: String? = null,
)

@HiltViewModel
class PrivacySecurityViewModel @Inject constructor(
    private val preferencesRepository: PrivacyPreferencesRepository,
    private val biometricAuthenticator: BiometricAuthenticator,
    private val appLockStateMachine: AppLockStateMachine,
) : ViewModel() {

    private val capabilityFlow = MutableStateFlow(biometricAuthenticator.checkCapability())
    private val userMessageFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PrivacySecurityUiState> = combine(
        preferencesRepository.preferences,
        capabilityFlow,
        userMessageFlow,
    ) { prefs, capability, message ->
        PrivacySecurityUiState(
            biometricLockEnabled = prefs.biometricLockEnabled,
            relockTimeout = prefs.relockTimeout,
            flagSecureEnabled = prefs.flagSecureEnabled,
            amountsHidden = prefs.amountsHidden,
            biometricCapability = capability,
            userMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PrivacySecurityUiState(
            biometricCapability = capabilityFlow.value,
        ),
    )

    fun refreshCapability() {
        capabilityFlow.value = biometricAuthenticator.checkCapability()
    }

    fun onBiometricLockToggleRequested(
        enabled: Boolean,
        requestIdentityVerification: ((Boolean) -> Unit) -> Unit,
    ) {
        if (!enabled) {
            viewModelScope.launch {
                preferencesRepository.setBiometricLockEnabled(false)
                userMessageFlow.value = "已关闭生物识别 / 锁屏保护"
            }
            return
        }

        val currentCapability = biometricAuthenticator.checkCapability()
        capabilityFlow.value = currentCapability
        if (currentCapability != BiometricCapability.AVAILABLE) {
            userMessageFlow.value = when (currentCapability) {
                BiometricCapability.NONE_ENROLLED ->
                    "请先在系统安全设置中录入指纹、面容或锁屏密码"
                BiometricCapability.UNAVAILABLE ->
                    "当前设备不支持生物识别或设备锁屏鉴权"
                BiometricCapability.AVAILABLE -> null
            }
            return
        }

        requestIdentityVerification { verified ->
            if (verified) {
                viewModelScope.launch {
                    preferencesRepository.setBiometricLockEnabled(true)
                    userMessageFlow.value = "已开启应用隐私锁"
                }
            } else {
                userMessageFlow.value = "未完成身份验证，应用隐私锁保持关闭"
            }
        }
    }

    fun setRelockTimeout(timeout: RelockTimeout) {
        viewModelScope.launch {
            preferencesRepository.setRelockTimeout(timeout)
        }
    }

    fun setFlagSecureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setFlagSecureEnabled(enabled)
        }
    }

    fun setAmountsHidden(hidden: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAmountsHidden(hidden)
        }
    }

    fun lockNow() {
        appLockStateMachine.lockNow()
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }
}
