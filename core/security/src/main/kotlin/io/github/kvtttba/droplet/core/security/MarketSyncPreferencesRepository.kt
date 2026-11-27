package io.github.kvtttba.droplet.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Local DataStore repository persisting background market refresh cadence and Wi-Fi constraint.
 */
interface MarketSyncPreferencesRepository {
    val preferences: Flow<MarketSyncPreferences>

    suspend fun currentPreferences(): MarketSyncPreferences

    suspend fun setRefreshInterval(interval: MarketSyncInterval)

    suspend fun setWifiOnly(wifiOnly: Boolean)

    suspend fun updatePreferences(
        refreshInterval: MarketSyncInterval,
        wifiOnly: Boolean,
    )
}

class DataStoreMarketSyncPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : MarketSyncPreferencesRepository {

    override val preferences: Flow<MarketSyncPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs ->
            MarketSyncPreferences(
                refreshInterval = MarketSyncInterval.fromStorageKey(prefs[Keys.REFRESH_INTERVAL]),
                wifiOnly = prefs[Keys.WIFI_ONLY] ?: false,
            )
        }
        .distinctUntilChanged()

    override suspend fun currentPreferences(): MarketSyncPreferences = preferences.first()

    override suspend fun setRefreshInterval(interval: MarketSyncInterval) {
        dataStore.edit { prefs ->
            prefs[Keys.REFRESH_INTERVAL] = interval.storageKey
        }
    }

    override suspend fun setWifiOnly(wifiOnly: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.WIFI_ONLY] = wifiOnly
        }
    }

    override suspend fun updatePreferences(
        refreshInterval: MarketSyncInterval,
        wifiOnly: Boolean,
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.REFRESH_INTERVAL] = refreshInterval.storageKey
            prefs[Keys.WIFI_ONLY] = wifiOnly
        }
    }

    internal object Keys {
        val REFRESH_INTERVAL = stringPreferencesKey("market_sync_refresh_interval")
        val WIFI_ONLY = booleanPreferencesKey("market_sync_wifi_only")
    }
}
