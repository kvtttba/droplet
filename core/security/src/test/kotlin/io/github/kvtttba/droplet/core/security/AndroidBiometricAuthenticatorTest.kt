package io.github.kvtttba.droplet.core.security

import android.app.Application
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AndroidBiometricAuthenticatorTest {

    @Test
    fun buildPromptInfo_allowsBiometricAndDeviceCredentialWithoutNegativeButton() {
        val promptInfo = AndroidBiometricAuthenticator.buildPromptInfo(
            title = "解锁 Droplet 记账",
            subtitle = "验证指纹、面容或设备锁屏密码",
        )

        assertEquals("解锁 Droplet 记账", promptInfo.title)
        assertEquals("验证指纹、面容或设备锁屏密码", promptInfo.subtitle)
        assertEquals(AndroidBiometricAuthenticator.ALLOWED_AUTHENTICATORS, promptInfo.allowedAuthenticators)
        assertFalse(promptInfo.isConfirmationRequired)
    }

    @Test
    fun mapCapabilityStatus_handlesBiometricAndDeviceCredentialFallback() {
        assertEquals(
            BiometricCapability.AVAILABLE,
            AndroidBiometricAuthenticator.mapCapabilityStatus(BiometricManager.BIOMETRIC_SUCCESS),
        )
        assertEquals(
            BiometricCapability.NONE_ENROLLED,
            AndroidBiometricAuthenticator.mapCapabilityStatus(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
        assertEquals(
            BiometricCapability.AVAILABLE,
            AndroidBiometricAuthenticator.mapCapabilityStatus(
                primaryStatus = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                credentialFallbackStatus = BiometricManager.BIOMETRIC_SUCCESS,
            ),
        )
        assertEquals(
            BiometricCapability.NONE_ENROLLED,
            AndroidBiometricAuthenticator.mapCapabilityStatus(
                primaryStatus = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                credentialFallbackStatus = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            ),
        )
        assertEquals(
            BiometricCapability.UNAVAILABLE,
            AndroidBiometricAuthenticator.mapCapabilityStatus(
                primaryStatus = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                credentialFallbackStatus = null,
            ),
        )
    }

    @Test
    fun mapAuthError_separatesCancelFromTerminalFailure() {
        assertEquals(
            BiometricAuthResult.Cancelled,
            AndroidBiometricAuthenticator.mapAuthError(BiometricPrompt.ERROR_USER_CANCELED, "Cancelled"),
        )
        assertEquals(
            BiometricAuthResult.Cancelled,
            AndroidBiometricAuthenticator.mapAuthError(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "Cancel"),
        )
        assertEquals(
            BiometricAuthResult.Cancelled,
            AndroidBiometricAuthenticator.mapAuthError(BiometricPrompt.ERROR_CANCELED, ""),
        )

        val lockout = AndroidBiometricAuthenticator.mapAuthError(BiometricPrompt.ERROR_LOCKOUT, "锁定 30 秒")
        assertTrue(lockout is BiometricAuthResult.Failed)
        lockout as BiometricAuthResult.Failed
        assertEquals(BiometricPrompt.ERROR_LOCKOUT, lockout.errorCode)
        assertEquals("锁定 30 秒", lockout.message)

        val blankMessage = AndroidBiometricAuthenticator.mapAuthError(BiometricPrompt.ERROR_HW_UNAVAILABLE, "")
        assertTrue(blankMessage is BiometricAuthResult.Failed)
        assertTrue((blankMessage as BiometricAuthResult.Failed).message.contains("${BiometricPrompt.ERROR_HW_UNAVAILABLE}"))
    }
}
