package io.github.kvtttba.droplet.core.security

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class BackupCryptoErrorReason {
    BLANK_PASSPHRASE,
    INVALID_HEADER,
    TRUNCATED_PAYLOAD,
    AUTHENTICATION_FAILED,
    CRYPTO_ERROR,
}

/**
 * Sanitized exception raised during DRP1 end-to-end backup encryption or decryption.
 * Messages never contain plaintext passphrases, derived key bytes, or decrypted payloads.
 */
class BackupCryptoException(
    val reason: BackupCryptoErrorReason,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * End-to-end backup archive encryption engine implementing Droplet's `DRP1` binary format:
 *
 * `MAGIC("DRP1", 4B) || SALT(16B) || NONCE(12B) || AES-256-GCM(ciphertext + 16B tag)`
 *
 * - Key derivation: `PBKDF2WithHmacSHA256` with high iteration count (default 120,000) producing a 256-bit key.
 * - Authenticated encryption uses AES-256-GCM with no padding, a 128-bit authentication tag,
 *   and AAD bound to the full 32-byte header (`DRP1 + salt + nonce`), so any 1-bit tamper in
 *   header, ciphertext, or tag is rejected deterministically.
 * - Sensitive intermediate key material and passphrase character buffers are zeroed immediately in `finally`.
 */
class WebDavBackupCrypto(
    private val pbkdf2Iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
    private val secureRandom: SecureRandom = SecureRandom(),
) {

    init {
        require(pbkdf2Iterations >= MIN_PBKDF2_ITERATIONS) {
            "PBKDF2 iteration count must be at least $MIN_PBKDF2_ITERATIONS."
        }
    }

    /**
     * Returns true when [bytes] starts with the 4-byte ASCII `DRP1` magic header.
     */
    fun isDrp1Encrypted(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC_BYTES.size) return false
        for (i in MAGIC_BYTES.indices) {
            if (bytes[i] != MAGIC_BYTES[i]) return false
        }
        return true
    }

    /**
     * Encrypts [plaintext] into the authenticated `DRP1` binary container using [passphrase].
     */
    fun encrypt(
        plaintext: ByteArray,
        passphrase: String,
    ): ByteArray {
        if (passphrase.isBlank()) {
            throw BackupCryptoException(
                reason = BackupCryptoErrorReason.BLANK_PASSPHRASE,
                message = "备份加密口令不能为空",
            )
        }

        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val header = ByteArray(HEADER_BYTES)
        System.arraycopy(MAGIC_BYTES, 0, header, 0, MAGIC_BYTES.size)
        System.arraycopy(salt, 0, header, MAGIC_BYTES.size, SALT_BYTES)
        System.arraycopy(nonce, 0, header, MAGIC_BYTES.size + SALT_BYTES, NONCE_BYTES)

        val passphraseChars = passphrase.toCharArray()
        var derivedKeyBytes: ByteArray? = null
        return try {
            derivedKeyBytes = deriveAes256KeyBytes(passphraseChars, salt)
            val keySpec = SecretKeySpec(derivedKeyBytes, ALGORITHM_AES)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(header)
            val cipherTextWithTag = cipher.doFinal(plaintext)

            ByteArray(HEADER_BYTES + cipherTextWithTag.size).apply {
                System.arraycopy(header, 0, this, 0, HEADER_BYTES)
                System.arraycopy(cipherTextWithTag, 0, this, HEADER_BYTES, cipherTextWithTag.size)
            }
        } catch (e: BackupCryptoException) {
            throw e
        } catch (t: Throwable) {
            throw BackupCryptoException(
                reason = BackupCryptoErrorReason.CRYPTO_ERROR,
                message = "备份数据加密失败，请重试",
                cause = t,
            )
        } finally {
            passphraseChars.fill('\u0000')
            derivedKeyBytes?.fill(0)
        }
    }

    /**
     * Authenticates and decrypts a `DRP1` binary container using [passphrase].
     * Any wrong passphrase, truncated container, invalid header, or 1-bit tamper throws [BackupCryptoException].
     */
    fun decrypt(
        payload: ByteArray,
        passphrase: String,
    ): ByteArray {
        if (passphrase.isBlank()) {
            throw BackupCryptoException(
                reason = BackupCryptoErrorReason.BLANK_PASSPHRASE,
                message = "请输入备份解密口令",
            )
        }
        if (payload.size < MAGIC_BYTES.size || !isDrp1Encrypted(payload)) {
            throw BackupCryptoException(
                reason = BackupCryptoErrorReason.INVALID_HEADER,
                message = "备份文件头无效，不是合法的 DRP1 加密快照",
            )
        }
        if (payload.size < HEADER_BYTES + GCM_TAG_BYTES) {
            throw BackupCryptoException(
                reason = BackupCryptoErrorReason.TRUNCATED_PAYLOAD,
                message = "加密备份文件不完整或已截断",
            )
        }

        val header = payload.copyOfRange(0, HEADER_BYTES)
        val salt = payload.copyOfRange(MAGIC_BYTES.size, MAGIC_BYTES.size + SALT_BYTES)
        val nonce = payload.copyOfRange(MAGIC_BYTES.size + SALT_BYTES, HEADER_BYTES)
        val cipherLength = payload.size - HEADER_BYTES

        val passphraseChars = passphrase.toCharArray()
        var derivedKeyBytes: ByteArray? = null
        return try {
            derivedKeyBytes = deriveAes256KeyBytes(passphraseChars, salt)
            val keySpec = SecretKeySpec(derivedKeyBytes, ALGORITHM_AES)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(header)
            cipher.doFinal(payload, HEADER_BYTES, cipherLength)
        } catch (e: AEADBadTagException) {
            throw BackupCryptoException(
                reason = BackupCryptoErrorReason.AUTHENTICATION_FAILED,
                message = "解密校验失败：备份口令不正确或快照数据已被篡改",
                cause = e,
            )
        } catch (e: BackupCryptoException) {
            throw e
        } catch (t: Throwable) {
            throw BackupCryptoException(
                reason = BackupCryptoErrorReason.CRYPTO_ERROR,
                message = "解密备份文件失败，请确认口令及文件完整性",
                cause = t,
            )
        } finally {
            passphraseChars.fill('\u0000')
            derivedKeyBytes?.fill(0)
        }
    }

    private fun deriveAes256KeyBytes(
        passphraseChars: CharArray,
        salt: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(passphraseChars, salt, pbkdf2Iterations, KEY_SIZE_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val PBKDF2_ALGORITHM: String = "PBKDF2WithHmacSHA256"
        const val ALGORITHM_AES: String = "AES"
        const val AES_GCM_TRANSFORMATION: String = "AES/GCM/NoPadding"

        const val DEFAULT_PBKDF2_ITERATIONS: Int = 120_000
        const val MIN_PBKDF2_ITERATIONS: Int = 1_000

        const val KEY_SIZE_BITS: Int = 256
        const val SALT_BYTES: Int = 16
        const val NONCE_BYTES: Int = 12
        const val GCM_TAG_BITS: Int = 128
        const val GCM_TAG_BYTES: Int = GCM_TAG_BITS / 8

        val MAGIC_BYTES: ByteArray = SecurityContract.BACKUP_MAGIC_HEADER.toByteArray(StandardCharsets.US_ASCII)
        const val HEADER_BYTES: Int = 4 + SALT_BYTES + NONCE_BYTES
    }
}
