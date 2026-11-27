package io.github.kvtttba.droplet.core.security

/**
 * Security & privacy contract anchor for M1.4 biometric lock and Keystore storage.
 */
object SecurityContract {
    const val KEYSTORE_PROVIDER: String = "AndroidKeyStore"
    const val BACKUP_MAGIC_HEADER: String = "DRP1"
}
