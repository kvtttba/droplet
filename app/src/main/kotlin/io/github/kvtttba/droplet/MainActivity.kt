package io.github.kvtttba.droplet

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.kvtttba.droplet.core.network.NetworkProxyConfig
import io.github.kvtttba.droplet.core.network.NetworkProxyType
import io.github.kvtttba.droplet.core.security.BiometricAuthResult
import io.github.kvtttba.droplet.core.security.BiometricAuthenticator
import io.github.kvtttba.droplet.core.security.LockStatus
import io.github.kvtttba.droplet.core.security.MarketSyncPreferences
import io.github.kvtttba.droplet.core.security.MarketSyncPreferencesRepository
import io.github.kvtttba.droplet.core.security.NetworkProxyPreferencesRepository
import io.github.kvtttba.droplet.security.AppLockCoordinator
import io.github.kvtttba.droplet.ui.DropletApp
import io.github.kvtttba.droplet.ui.toAppThemePreference
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var appLockCoordinator: AppLockCoordinator

    @Inject
    lateinit var biometricAuthenticator: BiometricAuthenticator

    @Inject
    lateinit var marketSyncPreferencesRepository: MarketSyncPreferencesRepository

    @Inject
    lateinit var networkProxyPreferencesRepository: NetworkProxyPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appLockCoordinator.start()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appLockCoordinator.preferences
                    .map { it.flagSecureEnabled }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        applyFlagSecure(enabled)
                    }
            }
        }

        setContent {
            val preferences by appLockCoordinator.preferences.collectAsStateWithLifecycle()
            val isHydrated by appLockCoordinator.isHydrated.collectAsStateWithLifecycle()
            val lockState by appLockCoordinator.lockState.collectAsStateWithLifecycle()
            val marketSyncPrefs by marketSyncPreferencesRepository.preferences
                .collectAsStateWithLifecycle(initialValue = MarketSyncPreferences())
            val proxyConfig by networkProxyPreferencesRepository.proxyConfig
                .collectAsStateWithLifecycle(initialValue = NetworkProxyConfig.NONE)
            val marketSyncSummary = buildString {
                append(marketSyncPrefs.refreshInterval.label)
                if (marketSyncPrefs.wifiOnly && marketSyncPrefs.refreshInterval.isPeriodicEnabled) {
                    append(" · 仅 Wi-Fi")
                }
                append(" · ")
                append(
                    when {
                        !proxyConfig.isEnabled -> "直连"
                        proxyConfig.type == NetworkProxyType.HTTP -> "HTTP 代理"
                        else -> "SOCKS 代理"
                    },
                )
            }

            LaunchedEffect(isHydrated, lockState.isOverlayVisible, lockState.status, lockState.lastErrorMessage) {
                if (
                    isHydrated &&
                    lockState.isOverlayVisible &&
                    lockState.status == LockStatus.LOCKED &&
                    lockState.lastErrorMessage == null
                ) {
                    triggerUnlockPrompt()
                }
            }

            DropletApp(
                preferences = preferences,
                isPreferencesHydrated = isHydrated,
                lockState = lockState,
                marketSyncSummary = marketSyncSummary,
                onThemeModeChange = { mode ->
                    appLockCoordinator.setThemeMode(mode.toAppThemePreference())
                },
                onDynamicColorChange = appLockCoordinator::setDynamicColor,
                onToggleAmountsHidden = appLockCoordinator::toggleAmountsHidden,
                onRequestUnlock = ::triggerUnlockPrompt,
                onVerifyIdentityForEnable = ::verifyIdentityForLockEnable,
                onLockOverlayBackPressed = { moveTaskToBack(true) },
            )
        }
    }

    private fun applyFlagSecure(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun triggerUnlockPrompt() {
        if (!appLockCoordinator.onAuthenticationStarted()) return
        biometricAuthenticator.authenticate(
            activity = this,
            title = "解锁 Droplet 记账",
            subtitle = "验证指纹、面容或设备锁屏密码以继续",
            onSoftFailure = appLockCoordinator::onAuthenticationSoftFailure,
            onResult = appLockCoordinator::onAuthenticationResult,
        )
    }

    private fun verifyIdentityForLockEnable(onComplete: (Boolean) -> Unit) {
        biometricAuthenticator.authenticate(
            activity = this,
            title = "开启应用隐私锁",
            subtitle = "请先验证本机指纹、面容或锁屏密码以确认可正常解锁",
            onResult = { result ->
                onComplete(result is BiometricAuthResult.Succeeded)
            },
        )
    }
}
