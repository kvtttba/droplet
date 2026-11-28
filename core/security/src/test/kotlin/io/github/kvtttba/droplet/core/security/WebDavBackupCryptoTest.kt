package io.github.kvtttba.droplet.core.security

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebDavBackupCryptoTest {

    // Fast test-only iteration count above MIN_PBKDF2_ITERATIONS for quick unit feedback, plus default check below.
    private val fastCrypto = WebDavBackupCrypto(pbkdf2Iterations = 2_000)

    @Test
    fun encryptAndDecryptRoundTripPreservesPayloadAndUsesRandomSaltAndNonce() {
        val samplePassphrase = "demo-backup-phrase-2026"
        val payload = "Droplet double-entry ledger snapshot payload ¥12,345.67".toByteArray(StandardCharsets.UTF_8)

        val encrypted1 = fastCrypto.encrypt(payload, samplePassphrase)
        val encrypted2 = fastCrypto.encrypt(payload, samplePassphrase)

        assertTrue(fastCrypto.isDrp1Encrypted(encrypted1))
        assertTrue(fastCrypto.isDrp1Encrypted(encrypted2))
        assertEquals(
            WebDavBackupCrypto.HEADER_BYTES + payload.size + WebDavBackupCrypto.GCM_TAG_BYTES,
            encrypted1.size,
        )
        assertFalse(
            "Same plaintext + passphrase must produce distinct ciphertexts due to random salt & nonce",
            encrypted1.contentEquals(encrypted2),
        )

        val decrypted1 = fastCrypto.decrypt(encrypted1, samplePassphrase)
        val decrypted2 = fastCrypto.decrypt(encrypted2, samplePassphrase)
        assertArrayEquals(payload, decrypted1)
        assertArrayEquals(payload, decrypted2)

        // Empty plaintext is also validly authenticated
        val emptyEncrypted = fastCrypto.encrypt(ByteArray(0), samplePassphrase)
        assertArrayEquals(ByteArray(0), fastCrypto.decrypt(emptyEncrypted, samplePassphrase))
    }

    @Test
    fun defaultHighIterationCryptoRoundTripsWithUnicodePassphrase() {
        val defaultCrypto = WebDavBackupCrypto()
        val unicodePass = "水滴账本口令-2026-测试"
        val sampleData = ByteArray(1024) { idx -> (idx % 251).toByte() }

        val encrypted = defaultCrypto.encrypt(sampleData, unicodePass)
        assertTrue(defaultCrypto.isDrp1Encrypted(encrypted))
        val decrypted = defaultCrypto.decrypt(encrypted, unicodePass)
        assertArrayEquals(sampleData, decrypted)
    }

    @Test
    fun wrongPassphraseAndAnySingleBitTamperAreRejectedWithoutLeakingPassphrase() {
        val correctPass = "demo-correct-phrase-1001"
        val wrongPass = "demo-wrong-phrase-9999"
        val plaintext = "sensitive ledger sqlite bytes".toByteArray(StandardCharsets.UTF_8)
        val encrypted = fastCrypto.encrypt(plaintext, correctPass)

        // 1. Wrong passphrase
        try {
            fastCrypto.decrypt(encrypted, wrongPass)
            fail("Expected BackupCryptoException for wrong passphrase")
        } catch (e: BackupCryptoException) {
            assertEquals(BackupCryptoErrorReason.AUTHENTICATION_FAILED, e.reason)
            assertFalse(e.message.orEmpty().contains(correctPass))
            assertFalse(e.message.orEmpty().contains(wrongPass))
        }

        // 2. Flip 1 bit in header salt (bound by AAD)
        val tamperedSalt = encrypted.copyOf()
        tamperedSalt[6] = (tamperedSalt[6].toInt() xor 0x01).toByte()
        assertTamperRejected(tamperedSalt, correctPass)

        // 3. Flip 1 bit in header nonce (bound by AAD & GCM IV)
        val tamperedNonce = encrypted.copyOf()
        tamperedNonce[22] = (tamperedNonce[22].toInt() xor 0x80).toByte()
        assertTamperRejected(tamperedNonce, correctPass)

        // 4. Flip 1 bit in ciphertext body
        val tamperedCipher = encrypted.copyOf()
        tamperedCipher[WebDavBackupCrypto.HEADER_BYTES + 2] =
            (tamperedCipher[WebDavBackupCrypto.HEADER_BYTES + 2].toInt() xor 0x04).toByte()
        assertTamperRejected(tamperedCipher, correctPass)

        // 5. Flip 1 bit in GCM tag
        val tamperedTag = encrypted.copyOf()
        tamperedTag[tamperedTag.lastIndex] = (tamperedTag[tamperedTag.lastIndex].toInt() xor 0x01).toByte()
        assertTamperRejected(tamperedTag, correctPass)
    }

    @Test
    fun invalidMagicHeaderTruncatedPayloadAndBlankPassphraseAreRejected() {
        val samplePass = "demo-valid-phrase-0001"
        assertFalse(fastCrypto.isDrp1Encrypted(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertFalse(fastCrypto.isDrp1Encrypted(byteArrayOf(0x44, 0x52)))

        try {
            fastCrypto.encrypt("abc".toByteArray(), "   ")
            fail("Expected blank passphrase rejection on encrypt")
        } catch (e: BackupCryptoException) {
            assertEquals(BackupCryptoErrorReason.BLANK_PASSPHRASE, e.reason)
        }

        try {
            fastCrypto.decrypt("NOT_DRP1_PAYLOAD_DATA_LONG_ENOUGH_FOR_HEADER_AND_TAG_CHECK".toByteArray(), samplePass)
            fail("Expected invalid header rejection")
        } catch (e: BackupCryptoException) {
            assertEquals(BackupCryptoErrorReason.INVALID_HEADER, e.reason)
        }

        val truncated = ByteArray(WebDavBackupCrypto.HEADER_BYTES + 4)
        System.arraycopy(WebDavBackupCrypto.MAGIC_BYTES, 0, truncated, 0, 4)
        try {
            fastCrypto.decrypt(truncated, samplePass)
            fail("Expected truncated payload rejection")
        } catch (e: BackupCryptoException) {
            assertEquals(BackupCryptoErrorReason.TRUNCATED_PAYLOAD, e.reason)
        }
    }

    private fun assertTamperRejected(tampered: ByteArray, passphrase: String) {
        try {
            fastCrypto.decrypt(tampered, passphrase)
            fail("Expected tampered DRP1 payload to fail authentication")
        } catch (e: BackupCryptoException) {
            assertEquals(BackupCryptoErrorReason.AUTHENTICATION_FAILED, e.reason)
        }
    }
}
