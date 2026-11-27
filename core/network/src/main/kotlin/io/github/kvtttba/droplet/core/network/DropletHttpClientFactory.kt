package io.github.kvtttba.droplet.core.network

import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class HttpClientTimeouts(
    val connectTimeoutMs: Long = MarketNetworkContract.DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Long = MarketNetworkContract.DEFAULT_READ_TIMEOUT_MS,
    val writeTimeoutMs: Long = MarketNetworkContract.DEFAULT_WRITE_TIMEOUT_MS,
    val callTimeoutMs: Long = MarketNetworkContract.DEFAULT_CALL_TIMEOUT_MS,
) {
    init {
        require(connectTimeoutMs > 0L) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs > 0L) { "readTimeoutMs must be positive" }
        require(writeTimeoutMs > 0L) { "writeTimeoutMs must be positive" }
        require(callTimeoutMs > 0L) { "callTimeoutMs must be positive" }
    }
}

data class HttpTextResponse(
    val statusCode: Int,
    val body: String,
    val contentType: String?,
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
}

/**
 * Pure JVM OkHttpClient factory sharing a single underlying connection pool and dispatcher while
 * supporting dynamic optional HTTP/SOCKS proxy switching, uniform timeouts, and a lightweight
 * browser-compatible default User-Agent.
 */
class DropletHttpClientFactory(
    private val proxyConfigProvider: NetworkProxyConfigProvider = NetworkProxyConfigProvider.DIRECT,
    private val timeouts: HttpClientTimeouts = HttpClientTimeouts(),
    private val userAgent: String = MarketNetworkContract.DEFAULT_USER_AGENT,
    baseClient: OkHttpClient = OkHttpClient(),
) {
    private val sharedBaseClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(timeouts.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeouts.writeTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeouts.callTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor(DefaultHeadersInterceptor(userAgent))
        .build()

    private val clientCache = ConcurrentHashMap<NetworkProxyConfig, OkHttpClient>()

    fun create(proxyConfig: NetworkProxyConfig = NetworkProxyConfig.NONE): OkHttpClient {
        val key = if (proxyConfig.isEnabled) proxyConfig.validated() else NetworkProxyConfig.NONE
        return clientCache.computeIfAbsent(key) { normalized ->
            sharedBaseClient.newBuilder()
                .proxy(normalized.toJavaProxy())
                .build()
        }
    }

    suspend fun currentClient(): OkHttpClient {
        val rawConfig = proxyConfigProvider.currentProxyConfig()
        val effective = if (rawConfig.isEnabled) rawConfig else NetworkProxyConfig.NONE
        return create(effective)
    }

    suspend fun executeGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        fallbackCharset: Charset = Charsets.UTF_8,
        proxyConfigOverride: NetworkProxyConfig? = null,
    ): HttpTextResponse {
        val client = if (proxyConfigOverride != null) {
            create(proxyConfigOverride)
        } else {
            currentClient()
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        for ((name, value) in headers) {
            requestBuilder.header(name, value)
        }
        val call = client.newCall(requestBuilder.build())
        return call.awaitTextResponse(fallbackCharset)
    }

    private class DefaultHeadersInterceptor(
        private val defaultUserAgent: String,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val builder = original.newBuilder()
            if (original.header("User-Agent") == null) {
                builder.header("User-Agent", defaultUserAgent)
            }
            if (original.header("Accept") == null) {
                builder.header("Accept", "*/*")
            }
            return chain.proceed(builder.build())
        }
    }
}

private suspend fun Call.awaitTextResponse(
    fallbackCharset: Charset,
): HttpTextResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancel()
    }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val body = res.body
                    val bytes = try {
                        body?.bytes() ?: ByteArray(0)
                    } catch (e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                        return
                    }
                    val charset = body?.contentType()?.charset(fallbackCharset) ?: fallbackCharset
                    val text = String(bytes, charset)
                    if (continuation.isActive) {
                        continuation.resume(
                            HttpTextResponse(
                                statusCode = res.code,
                                body = text,
                                contentType = body?.contentType()?.toString(),
                            ),
                        )
                    }
                }
            }
        },
    )
}
