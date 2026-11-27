package io.github.kvtttba.droplet.core.security

import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class KeystoreSecretCipherTest {

    private fun newInMemoryAes256Key(): SecretKey = KeyGenerator.getInstance("AES").apply {
        init(256)
    }.generateKey()

    @Test
    fun encryptAndDecrypt_roundTripsStandardUnicodeEmptyAndLongValues() {
        val key = newInMemoryAes256Key()
        val cipher = KeystoreSecretCipher { key }

        val samples = listOf(
            "",
            "mock-endpoint-credential-value",
            "中文密码-€¥$-🔐-123456",
            "x".repeat(4096),
        )

        samples.forEach { plaintext ->
            val encrypted = cipher.encrypt(plaintext)
            assertNotEquals(plaintext, encrypted)
            assertEquals(plaintext, cipher.decrypt(encrypted))
        }
    }

    @Test
    fun encrypt_usesUniqueRandomIvPerInvocation() {
        val key = newInMemoryAes256Key()
        val cipher = KeystoreSecretCipher { key }

        val first = cipher.encrypt("same-placeholder-value")
        val second = cipher.encrypt("same-placeholder-value")

        assertNotEquals(first, second)
        assertEquals("same-placeholder-value", cipher.decrypt(first))
        assertEquals("same-placeholder-value", cipher.decrypt(second))
    }

    @Test
    fun associatedData_bindsCiphertextToContext() {
        val key = newInMemoryAes256Key()
        val cipher = KeystoreSecretCipher { key }

        val encrypted = cipher.encrypt(
            plaintext = "mock-slot-credential",
            associatedData = "ai_slot:vision",
        )

        assertEquals(
            "mock-slot-credential",
            cipher.decrypt(encrypted, associatedData = "ai_slot:vision"),
        )

        assertThrowsCipherException {
            cipher.decrypt(encrypted, associatedData = "ai_slot:text")
        }
        assertThrowsCipherException {
            cipher.decrypt(encrypted, associatedData = "")
        }
    }

    @Test
    fun decrypt_detectsCiphertextIvAndTagTampering() {
        val key = newInMemoryAes256Key()
        val cipher = KeystoreSecretCipher { key }

        val encrypted = cipher.encrypt("sensitive-local-placeholder")
        val raw = Base64.getDecoder().decode(encrypted)

        // Flip a byte in the GCM tag / ciphertext tail
        val tamperedTail = raw.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 0x01).toByte()
        }
        assertThrowsCipherException {
            cipher.decrypt(Base64.getEncoder().encodeToString(tamperedTail))
        }

        // Flip a byte in the IV region (offset 2..13)
        val tamperedIv = raw.copyOf().apply {
            this[4] = (this[4].toInt() xor 0xFF).toByte()
        }
        assertThrowsCipherException {
            cipher.decrypt(Base64.getEncoder().encodeToString(tamperedIv))
        }

        // Corrupt version header
        val badVersion = raw.copyOf().apply {
            this[0] = 2
        }
        assertThrowsCipherException {
            cipher.decrypt(Base64.getEncoder().encodeToString(badVersion))
        }
    }

    @Test
    fun decrypt_rejectsMalformedAndWrongKeyPayloadsWithoutLeakingSecret() {
        val sampleInput = "sample-test-message-value"
        val keyA = newInMemoryAes256Key()
        val keyB = newInMemoryAes256Key()
        val cipherA = KeystoreSecretCipher { keyA }
        val cipherB = KeystoreSecretCipher { keyB }

        val encrypted = cipherA.encrypt(sampleInput)
        val wrongKeyError = assertThrowsCipherException {
            cipherB.decrypt(encrypted)
        }
        assertFalse(wrongKeyError.message.orEmpty().contains(sampleInput))

        assertThrowsCipherException { cipherA.decrypt("") }
        assertThrowsCipherException { cipherA.decrypt("not@valid#base64!") }
        assertThrowsCipherException {
            cipherA.decrypt(Base64.getEncoder().encodeToString(byteArrayOf(1, 12, 0, 1)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun keystoreProvider_rejectsBlankAlias() {
        AndroidKeystoreSecretKeyProvider(keyAlias = "   ")
    }

    private fun assertThrowsCipherException(block: () -> Unit): SecretCipherException {
        try {
            block()
            fail("Expected SecretCipherException to be thrown")
            throw AssertionError("Unreachable")
        } catch (e: SecretCipherException) {
            return e
        }
    }
}
