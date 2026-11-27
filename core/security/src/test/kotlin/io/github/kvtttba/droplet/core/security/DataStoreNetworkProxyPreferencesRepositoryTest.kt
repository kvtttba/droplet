package io.github.kvtttba.droplet.core.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import io.github.kvtttba.droplet.core.network.NetworkProxyConfig
import io.github.kvtttba.droplet.core.network.NetworkProxyType
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreNetworkProxyPreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun defaultsToNoneAndPersistsHttpAndSocksAcrossReload() = runTest {
        val storeFile = File(tempFolder.newFolder(), "proxy.preferences_pb")

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store1 = PreferenceDataStoreFactory.create(scope = scope1) { storeFile }
            val repo1 = DataStoreNetworkProxyPreferencesRepository(store1)

            assertEquals(NetworkProxyConfig.NONE, repo1.currentProxyConfig())
            assertFalse(repo1.proxyConfig.first().isEnabled)

            val httpProxy = NetworkProxyConfig(
                type = NetworkProxyType.HTTP,
                host = "  proxy.example.invalid  ",
                port = 8080,
            )
            repo1.saveProxyConfig(httpProxy)

            val savedHttp = repo1.currentProxyConfig()
            assertTrue(savedHttp.isEnabled)
            assertEquals(NetworkProxyType.HTTP, savedHttp.type)
            assertEquals("proxy.example.invalid", savedHttp.host)
            assertEquals(8080, savedHttp.port)
        } finally {
            scope1.coroutineContext[Job]?.cancelAndJoin()
        }

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store2 = PreferenceDataStoreFactory.create(scope = scope2) { storeFile }
            val repo2 = DataStoreNetworkProxyPreferencesRepository(store2)

            val reloaded = repo2.currentProxyConfig()
            assertEquals(NetworkProxyType.HTTP, reloaded.type)
            assertEquals("proxy.example.invalid", reloaded.host)
            assertEquals(8080, reloaded.port)

            repo2.saveProxyConfig(
                NetworkProxyConfig(
                    type = NetworkProxyType.SOCKS,
                    host = "127.0.0.1",
                    port = 1080,
                ),
            )
            val socks = repo2.currentProxyConfig()
            assertEquals(NetworkProxyType.SOCKS, socks.type)
            assertEquals("127.0.0.1", socks.host)
            assertEquals(1080, socks.port)

            repo2.clearProxyConfig()
            assertEquals(NetworkProxyConfig.NONE, repo2.currentProxyConfig())
        } finally {
            scope2.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun corruptedOrUnknownStoredValues_fallbackSafelyToNone() = runTest {
        val storeFile = File(tempFolder.newFolder(), "corrupted_proxy.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
            store.edit { prefs ->
                prefs[DataStoreNetworkProxyPreferencesRepository.Keys.PROXY_TYPE] = "UNKNOWN_PROTO"
                prefs[DataStoreNetworkProxyPreferencesRepository.Keys.PROXY_HOST] = "proxy.example.invalid"
                prefs[DataStoreNetworkProxyPreferencesRepository.Keys.PROXY_PORT] = 8080
            }
            val repo = DataStoreNetworkProxyPreferencesRepository(store)
            assertEquals(NetworkProxyConfig.NONE, repo.currentProxyConfig())

            // Valid type with invalid port also falls back safely to NONE
            store.edit { prefs ->
                prefs[DataStoreNetworkProxyPreferencesRepository.Keys.PROXY_TYPE] = "HTTP"
                prefs[DataStoreNetworkProxyPreferencesRepository.Keys.PROXY_HOST] = "proxy.example.invalid"
                prefs[DataStoreNetworkProxyPreferencesRepository.Keys.PROXY_PORT] = 99999
            }
            assertEquals(NetworkProxyConfig.NONE, repo.currentProxyConfig())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun saveProxyConfig_rejectsInvalidOrCredentialedHost() = runTest {
        val storeFile = File(tempFolder.newFolder(), "invalid_proxy.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
            val repo = DataStoreNetworkProxyPreferencesRepository(store)

            try {
                repo.saveProxyConfig(
                    NetworkProxyConfig(
                        type = NetworkProxyType.HTTP,
                        host = "user:pass@proxy.example.invalid",
                        port = 8080,
                    ),
                )
                fail("Expected IllegalArgumentException for credentialed host")
            } catch (_: IllegalArgumentException) {
                // expected
            }
            assertEquals(NetworkProxyConfig.NONE, repo.currentProxyConfig())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
