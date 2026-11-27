package io.github.kvtttba.droplet.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class BiometricCapability {
    /** Biometric hardware and/or device PIN/pattern/password is enrolled and ready. */
    AVAILABLE,

    /** Device supports authentication, but no biometric or screen lock credential is configured. */
    NONE_ENROLLED,

    /** Biometric and device credential authentication is unavailable on this device. */
    UNAVAILABLE,
}

sealed interface BiometricAuthResult {
    data object Succeeded : BiometricAuthResult
    data object Cancelled : BiometricAuthResult
    data class Failed(
        val errorCode: Int,
        val message: String,
    ) : BiometricAuthResult
}

/**
 * Abstraction over Android 12+ [BiometricPrompt] with automatic device credential fallback
 * (PIN / Pattern / Password).
 */
interface BiometricAuthenticator {
    fun checkCapability(): BiometricCapability

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSoftFailure: (String) -> Unit = {},
        onResult: (BiometricAuthResult) -> Unit,
    )
}

class AndroidBiometricAuthenticator(
    private val context: Context,
) : BiometricAuthenticator {

    override fun checkCapability(): BiometricCapability {
        val manager = BiometricManager.from(context)
        val primaryStatus = manager.canAuthenticate(ALLOWED_AUTHENTICATORS)
        val fallbackStatus = if (primaryStatus == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
            manager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            null
        }
        return mapCapabilityStatus(primaryStatus, fallbackStatus)
    }

    override fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSoftFailure: (String) -> Unit,
        onResult: (BiometricAuthResult) -> Unit,
    ) {
        val promptInfo = buildPromptInfo(title = title, subtitle = subtitle)
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(BiometricAuthResult.Succeeded)
            }

            override fun onAuthenticationFailed() {
                onSoftFailure("指纹或面容未匹配，请重试或改用锁屏密码")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(mapAuthError(errorCode, errString.toString()))
            }
        }
        runCatching {
            val prompt = BiometricPrompt(activity, executor, callback)
            prompt.authenticate(promptInfo)
        }.onFailure { throwable ->
            onResult(
                BiometricAuthResult.Failed(
                    errorCode = BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
                    message = throwable.message?.takeIf { it.isNotBlank() }
                        ?: "无法唤起系统身份验证，请检查锁屏设置",
                ),
            )
        }
    }

    companion object {
        const val ALLOWED_AUTHENTICATORS: Int =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        /**
         * Builds Android 12+ [BiometricPrompt.PromptInfo] with device credential fallback.
         * Note: `setNegativeButtonText` must NOT be called when `DEVICE_CREDENTIAL` is allowed.
         */
        internal fun buildPromptInfo(
            title: String,
            subtitle: String,
        ): BiometricPrompt.PromptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()

        internal fun mapCapabilityStatus(
            primaryStatus: Int,
            credentialFallbackStatus: Int? = null,
        ): BiometricCapability {
            val effective = when {
                primaryStatus == BiometricManager.BIOMETRIC_SUCCESS -> primaryStatus
                credentialFallbackStatus != null -> credentialFallbackStatus
                else -> primaryStatus
            }
            return when (effective) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricCapability.AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricCapability.NONE_ENROLLED
                else -> BiometricCapability.UNAVAILABLE
            }
        }

        internal fun mapAuthError(
            errorCode: Int,
            rawMessage: String,
        ): BiometricAuthResult = when (errorCode) {
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_CANCELED,
            -> BiometricAuthResult.Cancelled
            else -> BiometricAuthResult.Failed(
                errorCode = errorCode,
                message = rawMessage.ifBlank { "身份验证失败（错误码 $errorCode）" },
            )
        }
    }
}
