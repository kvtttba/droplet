package io.github.kvtttba.droplet.core.security

/**
 * Security & privacy contract constants shared across local storage, biometric lock,
 * Keystore credential encryption, and future WebDAV encrypted snapshots.
 */
object SecurityContract {
    const val KEYSTORE_PROVIDER: String = "AndroidKeyStore"
    const val BACKUP_MAGIC_HEADER: String = "DRP1"
    const val PRIVACY_PREFERENCES_FILE: String = "droplet_privacy_preferences.preferences_pb"
    const val NETWORK_PROXY_PREFERENCES_FILE: String = "droplet_network_proxy_preferences.preferences_pb"
    const val DEFAULT_SECRET_KEY_ALIAS: String = "droplet_local_secret_aes256_gcm_v1"
}
