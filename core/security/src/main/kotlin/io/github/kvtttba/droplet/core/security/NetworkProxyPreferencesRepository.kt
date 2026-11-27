package io.github.kvtttba.droplet.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kvtttba.droplet.core.network.NetworkProxyConfig
import io.github.kvtttba.droplet.core.network.NetworkProxyConfigProvider
import io.github.kvtttba.droplet.core.network.NetworkProxyType
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Local DataStore persistence for optional HTTP/SOCKS proxy settings.
 * Only sanitized `proxyType`, `host`, and `port` fields are stored; credentials are never accepted
 * and proxy settings are never logged.
 */
interface NetworkProxyPreferencesRepository : NetworkProxyConfigProvider {
    val proxyConfig: Flow<NetworkProxyConfig>

    override suspend fun currentProxyConfig(): NetworkProxyConfig

    suspend fun saveProxyConfig(config: NetworkProxyConfig)

    suspend fun clearProxyConfig()
}

class DataStoreNetworkProxyPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : NetworkProxyPreferencesRepository {

    override val proxyConfig: Flow<NetworkProxyConfig> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs -> decodeSafely(prefs) }
        .distinctUntilChanged()

    override suspend fun currentProxyConfig(): NetworkProxyConfig = proxyConfig.first()

    override suspend fun saveProxyConfig(config: NetworkProxyConfig) {
        val normalized = config.validated()
        dataStore.edit { prefs ->
            prefs[Keys.PROXY_TYPE] = normalized.type.storageKey
            if (normalized.type == NetworkProxyType.NONE) {
                prefs.remove(Keys.PROXY_HOST)
                prefs.remove(Keys.PROXY_PORT)
            } else {
                prefs[Keys.PROXY_HOST] = normalized.host
                prefs[Keys.PROXY_PORT] = normalized.port
            }
        }
    }

    override suspend fun clearProxyConfig() {
        saveProxyConfig(NetworkProxyConfig.NONE)
    }

    private fun decodeSafely(prefs: Preferences): NetworkProxyConfig {
        val type = NetworkProxyType.fromStorageKey(prefs[Keys.PROXY_TYPE])
        if (type == NetworkProxyType.NONE) {
            return NetworkProxyConfig.NONE
        }
        val host = prefs[Keys.PROXY_HOST]?.trim().orEmpty()
        val port = prefs[Keys.PROXY_PORT] ?: 0
        val candidate = NetworkProxyConfig(type = type, host = host, port = port)
        return if (candidate.isEnabled) candidate else NetworkProxyConfig.NONE
    }

    internal object Keys {
        val PROXY_TYPE = stringPreferencesKey("network_proxy_type")
        val PROXY_HOST = stringPreferencesKey("network_proxy_host")
        val PROXY_PORT = intPreferencesKey("network_proxy_port")
    }
}
