package io.github.kvtttba.droplet.core.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.Charset
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NetworkProxyConfigAndClientFactoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun proxyConfig_noneHttpAndSocksBuildExpectedJavaProxyAndRedactToString() {
        assertFalse(NetworkProxyConfig.NONE.isEnabled)
        assertEquals(Proxy.NO_PROXY, NetworkProxyConfig.NONE.toJavaProxy())

        val httpConfig = NetworkProxyConfig(
            type = NetworkProxyType.HTTP,
            host = "  proxy.example.invalid  ",
            port = 8080,
        ).validated()
        assertTrue(httpConfig.isEnabled)
        assertEquals("proxy.example.invalid", httpConfig.host)
        val httpProxy = httpConfig.toJavaProxy()
        assertEquals(Proxy.Type.HTTP, httpProxy.type())
        val httpAddr = httpProxy.address() as InetSocketAddress
        assertEquals("proxy.example.invalid", httpAddr.hostString)
        assertEquals(8080, httpAddr.port)
        assertTrue(httpAddr.isUnresolved)

        // toString must never leak host or port into logs
        val redacted = httpConfig.toString()
        assertFalse(redacted.contains("proxy.example.invalid"))
        assertFalse(redacted.contains("8080"))

        val socksConfig = NetworkProxyConfig(
            type = NetworkProxyType.SOCKS,
            host = "127.0.0.1",
            port = 1080,
        ).validated()
        val socksProxy = socksConfig.toJavaProxy()
        assertEquals(Proxy.Type.SOCKS, socksProxy.type())
        val socksAddr = socksProxy.address() as InetSocketAddress
        assertEquals("127.0.0.1", socksAddr.hostString)
        assertEquals(1080, socksAddr.port)
    }

    @Test
    fun proxyConfig_validationRejectsInvalidHostPortSchemeAndCredentials() {
        val invalidConfigs = listOf(
            NetworkProxyConfig(NetworkProxyType.HTTP, "", 8080),
            NetworkProxyConfig(NetworkProxyType.HTTP, "   ", 8080),
            NetworkProxyConfig(NetworkProxyType.HTTP, "http://proxy.example.invalid", 8080),
            NetworkProxyConfig(NetworkProxyType.SOCKS, "user:pass@proxy.example.invalid", 1080),
            NetworkProxyConfig(NetworkProxyType.HTTP, "proxy.example.invalid/path", 8080),
            NetworkProxyConfig(NetworkProxyType.HTTP, "proxy.example.invalid", 0),
            NetworkProxyConfig(NetworkProxyType.HTTP, "proxy.example.invalid", -1),
            NetworkProxyConfig(NetworkProxyType.SOCKS, "proxy.example.invalid", 65536),
        )
        for (invalid in invalidConfigs) {
            try {
                invalid.validated()
                fail("Expected IllegalArgumentException for $invalid")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }

        // NONE normalizes cleanly by clearing stale host/port
        val cleared = NetworkProxyConfig(
            type = NetworkProxyType.NONE,
            host = "stale.example.invalid",
            port = 7890,
        ).validated()
        assertEquals(NetworkProxyConfig.NONE, cleared)
    }

    @Test
    fun httpClientFactory_cachesPerProxySwitchesDynamicallyAndDecodesCharsets() = runTest {
        var activeProxy = NetworkProxyConfig.NONE
        val factory = DropletHttpClientFactory(
            proxyConfigProvider = NetworkProxyConfigProvider { activeProxy },
            timeouts = HttpClientTimeouts(
                connectTimeoutMs = 1_000L,
                readTimeoutMs = 1_000L,
                writeTimeoutMs = 1_000L,
                callTimeoutMs = 2_000L,
            ),
        )

        val direct1 = factory.currentClient()
        val direct2 = factory.currentClient()
        assertSame(direct1, direct2)
        assertEquals(Proxy.NO_PROXY, direct1.proxy)

        activeProxy = NetworkProxyConfig(NetworkProxyType.SOCKS, "127.0.0.1", 1080)
        val proxied = factory.currentClient()
        assertNotSame(direct1, proxied)
        assertEquals(Proxy.Type.SOCKS, proxied.proxy?.type())

        // Reset to direct and test MockWebServer headers + GB18030 decoding
        activeProxy = NetworkProxyConfig.NONE
        val gbCharset = Charset.forName("GB18030")
        val gbText = """v_sh600519="1~贵州茅台~600519~1523.50~1500.00";"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(gbText.toByteArray(gbCharset))),
        )

        val response = factory.executeGet(
            url = server.url("/q=sh600519").toString(),
            headers = mapOf("Referer" to "https://finance.example.invalid"),
            fallbackCharset = gbCharset,
        )
        assertTrue(response.isSuccessful)
        assertEquals(gbText, response.body)

        val recorded = server.takeRequest()
        assertEquals(MarketNetworkContract.DEFAULT_USER_AGENT, recorded.getHeader("User-Agent"))
        assertEquals("https://finance.example.invalid", recorded.getHeader("Referer"))
    }

    @Test
    fun httpClientFactory_enforcesReadTimeout() = runTest {
        val factory = DropletHttpClientFactory(
            timeouts = HttpClientTimeouts(
                connectTimeoutMs = 300L,
                readTimeoutMs = 250L,
                writeTimeoutMs = 300L,
                callTimeoutMs = 400L,
            ),
        )
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        try {
            factory.executeGet(server.url("/slow").toString())
            fail("Expected IOException on socket timeout")
        } catch (_: IOException) {
            // expected timeout
        }
    }
}
