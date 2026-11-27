package io.github.kvtttba.droplet.core.network

import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.serialization.Serializable

/**
 * Supported optional client proxy modes for market/FX HTTP requests.
 */
@Serializable
enum class NetworkProxyType(
    val storageKey: String,
) {
    NONE("NONE"),
    HTTP("HTTP"),
    SOCKS("SOCKS"),
    ;

    companion object {
        val DEFAULT: NetworkProxyType = NONE

        fun fromStorageKey(key: String?): NetworkProxyType =
            entries.firstOrNull { it.storageKey.equals(key?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Optional HTTP/SOCKS proxy configuration for OkHttp clients.
 *
 * Security notes:
 * - Never embeds credentials or full URLs in [host].
 * - [toString] is intentionally redacted so host/port values are never leaked into logs.
 */
@Serializable
data class NetworkProxyConfig(
    val type: NetworkProxyType = NetworkProxyType.NONE,
    val host: String = "",
    val port: Int = 0,
) {
    val isEnabled: Boolean
        get() = type != NetworkProxyType.NONE && isValidHost(host) && port in MIN_PORT..MAX_PORT

    /**
     * Validates and returns a normalized copy.
     * When [type] is [NetworkProxyType.NONE], clears host and port to [NONE].
     */
    fun validated(): NetworkProxyConfig {
        if (type == NetworkProxyType.NONE) {
            return NONE
        }
        val trimmedHost = host.trim()
        require(trimmedHost.isNotEmpty()) {
            "Proxy host must not be blank when proxy type is $type"
        }
        require(!trimmedHost.contains("://")) {
            "Proxy host must be a bare hostname or IP address without URL scheme"
        }
        require(!trimmedHost.contains("@")) {
            "Proxy host must not contain embedded credentials"
        }
        require(trimmedHost.none { it.isWhitespace() || it == '/' || it == '?' || it == '#' }) {
            "Proxy host contains invalid characters"
        }
        require(port in MIN_PORT..MAX_PORT) {
            "Proxy port must be within $MIN_PORT..$MAX_PORT, got $port"
        }
        return copy(host = trimmedHost)
    }

    /**
     * Converts to [java.net.Proxy] using an unresolved socket address so DNS resolution is
     * deferred to connection time (and routed through SOCKS proxies without local DNS leak).
     */
    fun toJavaProxy(): Proxy {
        if (!isEnabled) {
            return Proxy.NO_PROXY
        }
        val normalized = validated()
        val javaType = when (normalized.type) {
            NetworkProxyType.NONE -> return Proxy.NO_PROXY
            NetworkProxyType.HTTP -> Proxy.Type.HTTP
            NetworkProxyType.SOCKS -> Proxy.Type.SOCKS
        }
        return Proxy(javaType, InetSocketAddress.createUnresolved(normalized.host, normalized.port))
    }

    override fun toString(): String = "NetworkProxyConfig(type=$type, enabled=$isEnabled)"

    companion object {
        const val MIN_PORT: Int = 1
        const val MAX_PORT: Int = 65_535

        val NONE: NetworkProxyConfig = NetworkProxyConfig()

        fun isValidHost(host: String): Boolean {
            val trimmed = host.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.contains("://") || trimmed.contains("@")) return false
            return trimmed.none { it.isWhitespace() || it == '/' || it == '?' || it == '#' }
        }
    }
}

/**
 * Pure JVM abstraction supplying the current optional proxy configuration.
 */
fun interface NetworkProxyConfigProvider {
    suspend fun currentProxyConfig(): NetworkProxyConfig

    companion object {
        val DIRECT: NetworkProxyConfigProvider = NetworkProxyConfigProvider { NetworkProxyConfig.NONE }
    }
}
