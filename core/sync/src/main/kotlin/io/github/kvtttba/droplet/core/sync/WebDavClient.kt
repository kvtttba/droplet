package io.github.kvtttba.droplet.core.sync

import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.security.WebDavUrlValidator
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Sanitized error message scrubber ensuring passwords, passphrases, and Basic Auth headers
 * are never echoed in exceptions, UI messages, or logs.
 */
object WebDavErrorSanitizer {
    private val basicAuthPattern = Regex("(?i)Basic\\s+[A-Za-z0-9+/=_-]{6,}")
    private val urlCredsPattern = Regex("(?i)(https?://)[^/\\s:@]+:[^/\\s:@]+@")

    fun sanitize(rawMessage: String?, vararg secretsToRedact: String?): String {
        var result = rawMessage.orEmpty()
        for (secret in secretsToRedact) {
            if (!secret.isNullOrBlank()) {
                result = result.replace(secret, "[REDACTED]")
                val b64 = Base64.getEncoder().encodeToString(secret.toByteArray(StandardCharsets.UTF_8))
                if (b64.length >= 6) {
                    result = result.replace(b64, "[REDACTED]")
                }
            }
        }
        result = basicAuthPattern.replace(result, "Basic [REDACTED]")
        result = urlCredsPattern.replace(result, "$1[REDACTED]@")
        return result.trim()
    }

    fun sanitizeThrowable(throwable: Throwable, vararg secretsToRedact: String?): String {
        val raw = throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName
        return sanitize(raw, *secretsToRedact).ifEmpty { "网络请求异常" }
    }
}

class WebDavException(
    message: String,
    val httpStatusCode: Int? = null,
    val isTransientNetworkError: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Parameters required to talk to a WebDAV endpoint. [toString] is strictly redacted.
 */
class WebDavConnectionParams(
    val serverUrl: String,
    val username: String,
    val password: String,
    val remoteDirectory: String = WebDavUrlValidator.DEFAULT_REMOTE_DIRECTORY,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebDavConnectionParams) return false
        return serverUrl == other.serverUrl &&
            username == other.username &&
            password == other.password &&
            remoteDirectory == other.remoteDirectory
    }

    override fun hashCode(): Int {
        var result = serverUrl.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + password.hashCode()
        result = 31 * result + remoteDirectory.hashCode()
        return result
    }

    override fun toString(): String =
        "WebDavConnectionParams(serverUrl=$serverUrl, username=$username, " +
            "remoteDirectory=$remoteDirectory, password=[REDACTED])"
}

data class WebDavConnectionTestResult(
    val success: Boolean,
    val normalizedEndpointUrl: String,
    val httpStatusCode: Int? = null,
    val latencyMs: Long = 0L,
    val message: String,
)

data class RemoteBackupSnapshot(
    val fileName: String,
    val remotePath: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val isEncrypted: Boolean,
)

/**
 * WebDAV storage client for Droplet snapshot backups.
 */
interface WebDavClient {
    suspend fun testConnection(params: WebDavConnectionParams): WebDavConnectionTestResult

    suspend fun ensureRemoteDirectory(params: WebDavConnectionParams)

    suspend fun listSnapshots(params: WebDavConnectionParams): List<RemoteBackupSnapshot>

    suspend fun uploadSnapshot(
        params: WebDavConnectionParams,
        fileName: String,
        content: ByteArray,
    )

    suspend fun downloadSnapshot(
        params: WebDavConnectionParams,
        fileName: String,
    ): ByteArray

    suspend fun deleteSnapshot(
        params: WebDavConnectionParams,
        fileName: String,
    )

    suspend fun pruneOldSnapshots(
        params: WebDavConnectionParams,
        retentionCount: Int,
    ): List<String>
}

/**
 * Standard OkHttp WebDAV client reusing [DropletHttpClientFactory] so all sync traffic respects
 * user-configured HTTP/SOCKS proxy settings without ever logging credentials.
 */
class OkHttpWebDavClient(
    private val httpClientFactory: DropletHttpClientFactory,
    private val probeTimeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS,
    private val transferTimeoutMs: Long = DEFAULT_TRANSFER_TIMEOUT_MS,
    private val nanoTimeProvider: () -> Long = System::nanoTime,
) : WebDavClient {

    override suspend fun testConnection(params: WebDavConnectionParams): WebDavConnectionTestResult {
        val validatedUrl = WebDavUrlValidator.validateServerUrl(params.serverUrl)
        if (validatedUrl.isFailure) {
            return WebDavConnectionTestResult(
                success = false,
                normalizedEndpointUrl = params.serverUrl,
                message = validatedUrl.exceptionOrNull()?.message ?: "WebDAV 服务器地址无效",
            )
        }
        val normalizedBase = validatedUrl.getOrThrow()
        val normalizedDirResult = WebDavUrlValidator.normalizeRemoteDirectory(params.remoteDirectory)
        if (normalizedDirResult.isFailure) {
            return WebDavConnectionTestResult(
                success = false,
                normalizedEndpointUrl = normalizedBase,
                message = normalizedDirResult.exceptionOrNull()?.message ?: "远程目录路径无效",
            )
        }
        if (params.username.isBlank() || params.password.isBlank()) {
            return WebDavConnectionTestResult(
                success = false,
                normalizedEndpointUrl = normalizedBase,
                message = "请先填写用户名与密码后再测试连接",
            )
        }

        val authHeader = buildBasicAuth(params.username, params.password)
        val startedNanos = nanoTimeProvider()
        val client = buildClientWithTimeout(probeTimeoutMs)
        val rootUrl = "$normalizedBase/"

        val propfindResult = try {
            executeCall(
                client = client,
                request = Request.Builder()
                    .url(rootUrl)
                    .method("PROPFIND", PROPFIND_DEPTH0_XML.toRequestBody(XML_MEDIA_TYPE))
                    .header("Authorization", authHeader)
                    .header("Depth", "0")
                    .build(),
                authSecret = params.password,
                authHeader = authHeader,
            )
        } catch (e: IOException) {
            return WebDavConnectionTestResult(
                success = false,
                normalizedEndpointUrl = normalizedBase,
                latencyMs = elapsedMsSince(startedNanos),
                message = "网络连接失败：${WebDavErrorSanitizer.sanitizeThrowable(e, params.password, authHeader)}",
            )
        }

        if (propfindResult.statusCode == 207 || propfindResult.statusCode in 200..299) {
            return WebDavConnectionTestResult(
                success = true,
                normalizedEndpointUrl = normalizedBase,
                httpStatusCode = propfindResult.statusCode,
                latencyMs = elapsedMsSince(startedNanos),
                message = "连接成功，WebDAV 服务与账号鉴权均已通过",
            )
        }

        if (propfindResult.statusCode == 401 || propfindResult.statusCode == 403) {
            return WebDavConnectionTestResult(
                success = false,
                normalizedEndpointUrl = normalizedBase,
                httpStatusCode = propfindResult.statusCode,
                latencyMs = elapsedMsSince(startedNanos),
                message = "认证失败（HTTP ${propfindResult.statusCode}），请检查用户名或应用专用密码",
            )
        }

        if (propfindResult.statusCode == 405 || propfindResult.statusCode == 501) {
            val optionsResult = try {
                executeCall(
                    client = client,
                    request = Request.Builder()
                        .url(rootUrl)
                        .method("OPTIONS", null)
                        .header("Authorization", authHeader)
                        .build(),
                    authSecret = params.password,
                    authHeader = authHeader,
                )
            } catch (e: IOException) {
                return WebDavConnectionTestResult(
                    success = false,
                    normalizedEndpointUrl = normalizedBase,
                    latencyMs = elapsedMsSince(startedNanos),
                    message = "网络连接失败：${WebDavErrorSanitizer.sanitizeThrowable(e, params.password, authHeader)}",
                )
            }
            if (optionsResult.statusCode in 200..299) {
                return WebDavConnectionTestResult(
                    success = true,
                    normalizedEndpointUrl = normalizedBase,
                    httpStatusCode = optionsResult.statusCode,
                    latencyMs = elapsedMsSince(startedNanos),
                    message = "连接成功（OPTIONS 探测通过）",
                )
            }
            return WebDavConnectionTestResult(
                success = false,
                normalizedEndpointUrl = normalizedBase,
                httpStatusCode = optionsResult.statusCode,
                latencyMs = elapsedMsSince(startedNanos),
                message = buildHttpFailureMessage("连接测试", optionsResult.statusCode),
            )
        }

        return WebDavConnectionTestResult(
            success = false,
            normalizedEndpointUrl = normalizedBase,
            httpStatusCode = propfindResult.statusCode,
            latencyMs = elapsedMsSince(startedNanos),
            message = buildHttpFailureMessage("连接测试", propfindResult.statusCode),
        )
    }

    override suspend fun ensureRemoteDirectory(params: WebDavConnectionParams) {
        val baseUrl = WebDavUrlValidator.validateServerUrl(params.serverUrl).getOrThrow()
        val remoteDir = WebDavUrlValidator.normalizeRemoteDirectory(params.remoteDirectory).getOrThrow()
        val authHeader = buildBasicAuth(params.username, params.password)
        val client = buildClientWithTimeout(probeTimeoutMs)

        val segments = remoteDir.split('/').filter { it.isNotEmpty() }
        var currentUrl = baseUrl
        for (segment in segments) {
            currentUrl = "$currentUrl/${encodePathSegment(segment)}"
            val response = try {
                executeCall(
                    client = client,
                    request = Request.Builder()
                        .url("$currentUrl/")
                        .method("MKCOL", ByteArray(0).toRequestBody(null))
                        .header("Authorization", authHeader)
                        .build(),
                    authSecret = params.password,
                    authHeader = authHeader,
                )
            } catch (e: IOException) {
                throw WebDavException(
                    message = "创建远程目录失败：${WebDavErrorSanitizer.sanitizeThrowable(e, params.password, authHeader)}",
                    isTransientNetworkError = true,
                    cause = e,
                )
            }

            if (response.statusCode !in MKCOL_OK_STATUS_CODES) {
                throw WebDavException(
                    message = buildHttpFailureMessage("创建远程目录", response.statusCode),
                    httpStatusCode = response.statusCode,
                    isTransientNetworkError = response.statusCode >= 500,
                )
            }
        }
    }

    override suspend fun listSnapshots(params: WebDavConnectionParams): List<RemoteBackupSnapshot> {
        val dirUrl = buildRemoteDirectoryUrl(params)
        val authHeader = buildBasicAuth(params.username, params.password)
        val client = buildClientWithTimeout(probeTimeoutMs)

        val response = try {
            executeCall(
                client = client,
                request = Request.Builder()
                    .url("$dirUrl/")
                    .method("PROPFIND", PROPFIND_DEPTH1_XML.toRequestBody(XML_MEDIA_TYPE))
                    .header("Authorization", authHeader)
                    .header("Depth", "1")
                    .build(),
                authSecret = params.password,
                authHeader = authHeader,
            )
        } catch (e: IOException) {
            throw WebDavException(
                message = "读取远端快照列表失败：${WebDavErrorSanitizer.sanitizeThrowable(e, params.password, authHeader)}",
                isTransientNetworkError = true,
                cause = e,
            )
        }

        if (response.statusCode == 404) {
            return emptyList()
        }
        if (response.statusCode != 207 && response.statusCode !in 200..299) {
            throw WebDavException(
                message = buildHttpFailureMessage("读取远端快照列表", response.statusCode),
                httpStatusCode = response.statusCode,
                isTransientNetworkError = response.statusCode >= 500,
            )
        }

        val xmlText = String(response.bodyBytes, StandardCharsets.UTF_8)
        return parsePropfindXml(xmlText)
    }

    override suspend fun uploadSnapshot(
        params: WebDavConnectionParams,
        fileName: String,
        content: ByteArray,
    ) {
        val safeName = validateSnapshotFileName(fileName)
        val fileUrl = "${buildRemoteDirectoryUrl(params)}/${encodePathSegment(safeName)}"
        val authHeader = buildBasicAuth(params.username, params.password)
        val client = buildClientWithTimeout(transferTimeoutMs)

        val response = try {
            executeCall(
                client = client,
                request = Request.Builder()
                    .url(fileUrl)
                    .put(content.toRequestBody(OCTET_STREAM_MEDIA_TYPE))
                    .header("Authorization", authHeader)
                    .build(),
                authSecret = params.password,
                authHeader = authHeader,
            )
        } catch (e: IOException) {
            throw WebDavException(
                message = "上传快照失败：${WebDavErrorSanitizer.sanitizeThrowable(e, params.password, authHeader)}",
                isTransientNetworkError = true,
                cause = e,
            )
        }

        if (response.statusCode !in UPLOAD_OK_STATUS_CODES) {
            throw WebDavException(
                message = buildHttpFailureMessage("上传快照", response.statusCode),
                httpStatusCode = response.statusCode,
                isTransientNetworkError = response.statusCode >= 500,
            )
        }
    }

    override suspend fun downloadSnapshot(
        params: WebDavConnectionParams,
        fileName: String,
    ): ByteArray {
        val safeName = validateSnapshotFileName(fileName)
        val fileUrl = "${buildRemoteDirectoryUrl(params)}/${encodePathSegment(safeName)}"
        val authHeader = buildBasicAuth(params.username, params.password)
        val client = buildClientWithTimeout(transferTimeoutMs)

        val response = try {
            executeCall(
                client = client,
                request = Request.Builder()
                    .url(fileUrl)
                    .get()
                    .header("Authorization", authHeader)
                    .build(),
                authSecret = params.password,
                authHeader = authHeader,
            )
        } catch (e: IOException) {
            throw WebDavException(
                message = "下载快照失败：${WebDavErrorSanitizer.sanitizeThrowable(e, params.password, authHeader)}",
                isTransientNetworkError = true,
                cause = e,
            )
        }

        if (response.statusCode !in 200..299) {
            throw WebDavException(
                message = buildHttpFailureMessage("下载快照", response.statusCode),
                httpStatusCode = response.statusCode,
                isTransientNetworkError = response.statusCode >= 500,
            )
        }
        return response.bodyBytes
    }

    override suspend fun deleteSnapshot(
        params: WebDavConnectionParams,
        fileName: String,
    ) {
        val safeName = validateSnapshotFileName(fileName)
        val fileUrl = "${buildRemoteDirectoryUrl(params)}/${encodePathSegment(safeName)}"
        val authHeader = buildBasicAuth(params.username, params.password)
        val client = buildClientWithTimeout(probeTimeoutMs)

        val response = try {
            executeCall(
                client = client,
                request = Request.Builder()
                    .url(fileUrl)
                    .delete()
                    .header("Authorization", authHeader)
                    .build(),
                authSecret = params.password,
                authHeader = authHeader,
            )
        } catch (e: IOException) {
            throw WebDavException(
                message = "删除远端快照失败：${WebDavErrorSanitizer.sanitizeThrowable(e, params.password, authHeader)}",
                isTransientNetworkError = true,
                cause = e,
            )
        }

        if (response.statusCode !in DELETE_OK_STATUS_CODES) {
            throw WebDavException(
                message = buildHttpFailureMessage("删除远端快照", response.statusCode),
                httpStatusCode = response.statusCode,
                isTransientNetworkError = response.statusCode >= 500,
            )
        }
    }

    override suspend fun pruneOldSnapshots(
        params: WebDavConnectionParams,
        retentionCount: Int,
    ): List<String> {
        val safeRetention = WebDavUrlValidator.sanitizeRetentionCount(retentionCount)
        val all = listSnapshots(params)
        if (all.size <= safeRetention) {
            return emptyList()
        }
        val toDelete = all.drop(safeRetention)
        val deleted = mutableListOf<String>()
        for (snapshot in toDelete) {
            deleteSnapshot(params, snapshot.fileName)
            deleted += snapshot.fileName
        }
        return deleted
    }

    private fun buildRemoteDirectoryUrl(params: WebDavConnectionParams): String {
        val baseUrl = WebDavUrlValidator.validateServerUrl(params.serverUrl).getOrThrow()
        val remoteDir = WebDavUrlValidator.normalizeRemoteDirectory(params.remoteDirectory).getOrThrow()
        val encodedSegments = remoteDir.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodePathSegment(it) }
        return "$baseUrl/$encodedSegments"
    }

    private fun validateSnapshotFileName(fileName: String): String {
        val trimmed = fileName.trim()
        require(
            trimmed.isNotEmpty() &&
                !trimmed.contains('/') &&
                !trimmed.contains('\\') &&
                !trimmed.contains(".."),
        ) {
            "快照文件名非法"
        }
        return trimmed
    }

    private fun buildBasicAuth(username: String, password: String): String =
        Credentials.basic(username, password, StandardCharsets.UTF_8)

    private suspend fun buildClientWithTimeout(timeoutMs: Long): OkHttpClient =
        httpClientFactory.currentClient().newBuilder()
            .connectTimeout(timeoutMs.coerceAtMost(DEFAULT_PROBE_TIMEOUT_MS), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

    private fun elapsedMsSince(startedNanos: Long): Long =
        ((nanoTimeProvider() - startedNanos).coerceAtLeast(0L)) / 1_000_000L

    private fun buildHttpFailureMessage(action: String, statusCode: Int): String = when (statusCode) {
        401 -> "$action 失败（HTTP 401）：用户名或密码不正确"
        403 -> "$action 失败（HTTP 403）：无访问权限，请检查 WebDAV 目录授权"
        404 -> "$action 失败（HTTP 404）：目标路径或文件不存在"
        409 -> "$action 失败（HTTP 409）：父目录不存在或路径冲突"
        507 -> "$action 失败（HTTP 507）：WebDAV 网盘存储空间不足"
        else -> "$action 失败（HTTP $statusCode）"
    }

    private data class RawHttpResponse(
        val statusCode: Int,
        val bodyBytes: ByteArray,
    )

    private suspend fun executeCall(
        client: OkHttpClient,
        request: Request,
        authSecret: String,
        authHeader: String,
    ): RawHttpResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation {
            call.cancel()
        }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        val sanitized = IOException(
                            WebDavErrorSanitizer.sanitizeThrowable(e, authSecret, authHeader),
                        )
                        continuation.resumeWithException(sanitized)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        val bytes = try {
                            val body = res.body
                            val length = body?.contentLength() ?: -1L
                            if (length > MAX_DOWNLOAD_BYTES) {
                                throw IOException("响应体体积超过安全上限（256 MB）")
                            }
                            body?.bytes() ?: ByteArray(0)
                        } catch (e: IOException) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException(
                                        WebDavErrorSanitizer.sanitizeThrowable(e, authSecret, authHeader),
                                    ),
                                )
                            }
                            return
                        }
                        if (bytes.size.toLong() > MAX_DOWNLOAD_BYTES) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(IOException("响应体体积超过安全上限（256 MB）"))
                            }
                            return
                        }
                        if (continuation.isActive) {
                            continuation.resume(
                                RawHttpResponse(
                                    statusCode = res.code,
                                    bodyBytes = bytes,
                                ),
                            )
                        }
                    }
                }
            },
        )
    }

    internal fun parsePropfindXml(xmlText: String): List<RemoteBackupSnapshot> {
        if (xmlText.isBlank()) return emptyList()
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xmlText))

        val snapshots = mutableListOf<RemoteBackupSnapshot>()
        var inResponse = false
        var currentHref = ""
        var currentSize = 0L
        var currentLastModifiedRaw = ""
        var currentIsCollection = false
        var currentTag: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val localName = parser.name?.substringAfter(':')?.lowercase(Locale.US)
                    if (localName == "response") {
                        inResponse = true
                        currentHref = ""
                        currentSize = 0L
                        currentLastModifiedRaw = ""
                        currentIsCollection = false
                        currentTag = null
                    } else if (inResponse) {
                        if (localName == "collection") {
                            currentIsCollection = true
                        }
                        currentTag = localName
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inResponse && currentTag != null) {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            when (currentTag) {
                                "href" -> currentHref += text
                                "getcontentlength" -> currentSize = text.toLongOrNull() ?: currentSize
                                "getlastmodified" -> currentLastModifiedRaw = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val localName = parser.name?.substringAfter(':')?.lowercase(Locale.US)
                    if (localName == "response" && inResponse) {
                        buildSnapshotFromResponse(
                            href = currentHref,
                            sizeBytes = currentSize,
                            lastModifiedRaw = currentLastModifiedRaw,
                            isCollection = currentIsCollection,
                        )?.let { snapshots += it }
                        inResponse = false
                        currentTag = null
                    } else if (localName == currentTag) {
                        currentTag = null
                    }
                }
            }
            eventType = parser.next()
        }

        return snapshots
            .distinctBy { it.fileName }
            .sortedWith(
                compareByDescending<RemoteBackupSnapshot> { it.lastModifiedEpochMs }
                    .thenByDescending { it.fileName },
            )
    }

    private fun buildSnapshotFromResponse(
        href: String,
        sizeBytes: Long,
        lastModifiedRaw: String,
        isCollection: Boolean,
    ): RemoteBackupSnapshot? {
        val trimmedHref = href.trim()
        if (trimmedHref.isEmpty() || isCollection || trimmedHref.endsWith("/")) {
            return null
        }
        val pathOnly = trimmedHref
            .substringAfter("://", missingDelimiterValue = trimmedHref)
            .let { if (trimmedHref.contains("://")) "/" + it.substringAfter("/", "") else it }
            .substringBefore('?')
        val rawSegment = pathOnly.trimEnd('/').substringAfterLast('/')
        if (rawSegment.isEmpty()) return null
        val decodedName = try {
            URLDecoder.decode(rawSegment, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            rawSegment
        }
        if (!isRecognizedBackupFileName(decodedName)) {
            return null
        }
        val parsedTs = parseLastModifiedEpochMs(lastModifiedRaw)
            ?: parseTimestampFromFileName(decodedName)
            ?: 0L
        return RemoteBackupSnapshot(
            fileName = decodedName,
            remotePath = pathOnly,
            sizeBytes = sizeBytes.coerceAtLeast(0L),
            lastModifiedEpochMs = parsedTs,
            isEncrypted = decodedName.endsWith(".${BackupArchiveCodec.ENCRYPTED_EXTENSION}", ignoreCase = true),
        )
    }

    private fun isRecognizedBackupFileName(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.US)
        if (!lower.startsWith("${BackupArchiveCodec.BACKUP_FILE_PREFIX}-")) return false
        return lower.endsWith(".${BackupArchiveCodec.ENCRYPTED_EXTENSION}") ||
            lower.endsWith(".${BackupArchiveCodec.PLAIN_EXTENSION}")
    }

    private fun parseLastModifiedEpochMs(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        runCatching {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }
        runCatching {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.ISO_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }
        return null
    }

    private fun parseTimestampFromFileName(fileName: String): Long? {
        val stem = fileName
            .removePrefix("${BackupArchiveCodec.BACKUP_FILE_PREFIX}-")
            .substringBeforeLast('.')
        return runCatching {
            LocalDateTime.parse(stem, FILE_TS_PARSER).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private fun encodePathSegment(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val DEFAULT_PROBE_TIMEOUT_MS: Long = 15_000L
        const val DEFAULT_TRANSFER_TIMEOUT_MS: Long = 60_000L
        private const val MAX_DOWNLOAD_BYTES: Long = 256L * 1024L * 1024L

        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()

        private val MKCOL_OK_STATUS_CODES = setOf(200, 201, 204, 301, 302, 405)
        private val UPLOAD_OK_STATUS_CODES = setOf(200, 201, 204)
        private val DELETE_OK_STATUS_CODES = setOf(200, 202, 204, 404)

        private val FILE_TS_PARSER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US)

        private const val PROPFIND_DEPTH0_XML: String =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""

        private const val PROPFIND_DEPTH1_XML: String =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
    }
}
