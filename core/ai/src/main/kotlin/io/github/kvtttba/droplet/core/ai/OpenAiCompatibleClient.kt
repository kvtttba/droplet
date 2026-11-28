package io.github.kvtttba.droplet.core.ai

import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.security.AiResolvedEndpoint
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * OpenAI-compatible AI service client supporting connection probes, text parsing,
 * multi-image vision parsing, and Whisper-compatible audio transcription.
 */
interface OpenAiCompatibleClient {
    suspend fun testConnection(endpoint: AiResolvedEndpoint): AiConnectionTestResult

    suspend fun parseText(
        text: String,
        promptContext: AiPromptContext,
        endpoint: AiResolvedEndpoint,
    ): AiParseResult

    suspend fun parseImages(
        images: List<AiImagePayload>,
        promptContext: AiPromptContext,
        endpoint: AiResolvedEndpoint,
        userHint: String? = null,
    ): AiParseResult

    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        fileName: String,
        mimeType: String,
        endpoint: AiResolvedEndpoint,
        language: String? = "zh",
    ): AiTranscriptionResult
}

/**
 * Default OkHttp implementation reusing [DropletHttpClientFactory] so all AI traffic automatically
 * respects Droplet's optional HTTP/SOCKS proxy configuration while never logging API keys.
 */
class OkHttpOpenAiCompatibleClient(
    private val httpClientFactory: DropletHttpClientFactory,
    private val promptBuilder: AiPromptBuilder = AiPromptBuilder(),
    private val draftJsonParser: AiDraftJsonParser = AiDraftJsonParser(),
    private val requestTimeoutMs: Long = DEFAULT_AI_CALL_TIMEOUT_MS,
    private val testTimeoutMs: Long = DEFAULT_TEST_CALL_TIMEOUT_MS,
    private val nanoTimeProvider: () -> Long = System::nanoTime,
) : OpenAiCompatibleClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun testConnection(endpoint: AiResolvedEndpoint): AiConnectionTestResult {
        val normalizedBase = try {
            OpenAiEndpointUrlNormalizer.normalizeBaseUrl(endpoint.baseUrl)
        } catch (e: IllegalArgumentException) {
            return AiConnectionTestResult(
                success = false,
                endpointSlot = endpoint.effectiveSlot,
                resolvedBaseUrl = endpoint.baseUrl,
                model = endpoint.model,
                message = AiErrorSanitizer.sanitize(e.message, endpoint.apiKey).ifEmpty { "BaseURL 格式无效" },
            )
        }

        val startedNanos = nanoTimeProvider()
        val client = buildClientWithTimeout(testTimeoutMs)
        val modelsUrl = "$normalizedBase/models"

        val modelsResp = try {
            executeRequest(
                client = client,
                request = Request.Builder()
                    .url(modelsUrl)
                    .get()
                    .header("Authorization", "Bearer ${endpoint.apiKey}")
                    .header("Accept", "application/json")
                    .build(),
            )
        } catch (e: IOException) {
            return AiConnectionTestResult(
                success = false,
                endpointSlot = endpoint.effectiveSlot,
                resolvedBaseUrl = normalizedBase,
                model = endpoint.model,
                probeMethod = "GET /models",
                latencyMs = elapsedMsSince(startedNanos),
                message = "网络连接失败：${AiErrorSanitizer.sanitizeThrowable(e, endpoint.apiKey)}",
            )
        }

        if (modelsResp.isSuccessful) {
            val modelListed = isModelPresentInModelsPayload(modelsResp.body, endpoint.model)
            val msg = if (modelListed) {
                "连接成功，已验证模型 ${endpoint.model}"
            } else {
                "连接成功（接口鉴权通过）"
            }
            return AiConnectionTestResult(
                success = true,
                endpointSlot = endpoint.effectiveSlot,
                resolvedBaseUrl = normalizedBase,
                model = endpoint.model,
                httpStatusCode = modelsResp.statusCode,
                probeMethod = "GET /models",
                latencyMs = elapsedMsSince(startedNanos),
                message = msg,
            )
        }

        if (modelsResp.statusCode == 401 || modelsResp.statusCode == 403) {
            return AiConnectionTestResult(
                success = false,
                endpointSlot = endpoint.effectiveSlot,
                resolvedBaseUrl = normalizedBase,
                model = endpoint.model,
                httpStatusCode = modelsResp.statusCode,
                probeMethod = "GET /models",
                latencyMs = elapsedMsSince(startedNanos),
                message = "鉴权失败（HTTP ${modelsResp.statusCode}），请检查 API Key 权限",
            )
        }

        // Some OpenAI-compatible gateways do not expose /models; fall back to a minimal /chat/completions probe.
        if (modelsResp.statusCode in FALLBACK_PROBE_STATUS_CODES) {
            val chatUrl = "$normalizedBase/chat/completions"
            val pingPayload = buildJsonObject {
                put("model", endpoint.model)
                put("max_tokens", 1)
                put("temperature", 0)
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", "ping")
                            },
                        )
                    },
                )
            }.toString()

            val chatResp = try {
                executeRequest(
                    client = client,
                    request = Request.Builder()
                        .url(chatUrl)
                        .post(pingPayload.toRequestBody(JSON_MEDIA_TYPE))
                        .header("Authorization", "Bearer ${endpoint.apiKey}")
                        .header("Accept", "application/json")
                        .build(),
                )
            } catch (e: IOException) {
                return AiConnectionTestResult(
                    success = false,
                    endpointSlot = endpoint.effectiveSlot,
                    resolvedBaseUrl = normalizedBase,
                    model = endpoint.model,
                    probeMethod = "POST /chat/completions",
                    latencyMs = elapsedMsSince(startedNanos),
                    message = "网络连接失败：${AiErrorSanitizer.sanitizeThrowable(e, endpoint.apiKey)}",
                )
            }

            if (chatResp.isSuccessful) {
                return AiConnectionTestResult(
                    success = true,
                    endpointSlot = endpoint.effectiveSlot,
                    resolvedBaseUrl = normalizedBase,
                    model = endpoint.model,
                    httpStatusCode = chatResp.statusCode,
                    probeMethod = "POST /chat/completions",
                    latencyMs = elapsedMsSince(startedNanos),
                    message = "连接成功（对话接口探测通过）",
                )
            }

            val errDetail = extractSanitizedApiError(chatResp.body, endpoint.apiKey)
            return AiConnectionTestResult(
                success = false,
                endpointSlot = endpoint.effectiveSlot,
                resolvedBaseUrl = normalizedBase,
                model = endpoint.model,
                httpStatusCode = chatResp.statusCode,
                probeMethod = "POST /chat/completions",
                latencyMs = elapsedMsSince(startedNanos),
                message = buildHttpFailureMessage(chatResp.statusCode, errDetail),
            )
        }

        val errDetail = extractSanitizedApiError(modelsResp.body, endpoint.apiKey)
        return AiConnectionTestResult(
            success = false,
            endpointSlot = endpoint.effectiveSlot,
            resolvedBaseUrl = normalizedBase,
            model = endpoint.model,
            httpStatusCode = modelsResp.statusCode,
            probeMethod = "GET /models",
            latencyMs = elapsedMsSince(startedNanos),
            message = buildHttpFailureMessage(modelsResp.statusCode, errDetail),
        )
    }

    override suspend fun parseText(
        text: String,
        promptContext: AiPromptContext,
        endpoint: AiResolvedEndpoint,
    ): AiParseResult {
        val promptPayload = promptBuilder.buildTextPrompt(userText = text, context = promptContext)
        val messages = buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", promptPayload.systemPrompt)
                },
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", promptPayload.userPrompt)
                },
            )
        }
        return executeChatCompletionAndParse(
            messages = messages,
            promptContext = promptContext,
            endpoint = endpoint,
        )
    }

    override suspend fun parseImages(
        images: List<AiImagePayload>,
        promptContext: AiPromptContext,
        endpoint: AiResolvedEndpoint,
        userHint: String?,
    ): AiParseResult {
        require(images.isNotEmpty()) { "At least one image is required for parseImages" }
        val promptPayload = promptBuilder.buildImagePrompt(
            imageCount = images.size,
            context = promptContext,
            userHint = userHint,
        )
        val userContentParts = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", promptPayload.userPrompt)
                },
            )
            images.forEach { image ->
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        put(
                            "image_url",
                            buildJsonObject {
                                put("url", image.toDataUri())
                            },
                        )
                    },
                )
            }
        }
        val messages = buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", promptPayload.systemPrompt)
                },
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", userContentParts)
                },
            )
        }
        return executeChatCompletionAndParse(
            messages = messages,
            promptContext = promptContext,
            endpoint = endpoint,
        )
    }

    override suspend fun transcribeAudio(
        audioBytes: ByteArray,
        fileName: String,
        mimeType: String,
        endpoint: AiResolvedEndpoint,
        language: String?,
    ): AiTranscriptionResult {
        require(audioBytes.isNotEmpty()) { "Audio payload must not be empty" }
        val safeFileName = fileName.trim().ifEmpty { "recording.m4a" }
        val url = OpenAiEndpointUrlNormalizer.audioTranscriptionsUrl(endpoint.baseUrl)
        val startedNanos = nanoTimeProvider()
        val client = buildClientWithTimeout(requestTimeoutMs)

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                safeFileName,
                audioBytes.toRequestBody(mimeType.toMediaTypeOrNull()),
            )
            .addFormDataPart("model", endpoint.model)
            .addFormDataPart("response_format", "json")

        val trimmedLang = language?.trim().orEmpty()
        if (trimmedLang.isNotEmpty()) {
            multipartBuilder.addFormDataPart("language", trimmedLang)
        }

        val response = try {
            executeRequest(
                client = client,
                request = Request.Builder()
                    .url(url)
                    .post(multipartBuilder.build())
                    .header("Authorization", "Bearer ${endpoint.apiKey}")
                    .header("Accept", "application/json")
                    .build(),
            )
        } catch (e: IOException) {
            throw AiClientException(
                message = "语音转写网络请求失败：${AiErrorSanitizer.sanitizeThrowable(e, endpoint.apiKey)}",
                cause = e,
            )
        }

        if (!response.isSuccessful) {
            val detail = extractSanitizedApiError(response.body, endpoint.apiKey)
            throw AiClientException(
                httpStatusCode = response.statusCode,
                message = buildHttpFailureMessage(response.statusCode, detail),
            )
        }

        val transcriptText = extractTranscriptionText(response.body)
        if (transcriptText.isBlank()) {
            throw AiClientException(
                httpStatusCode = response.statusCode,
                message = "语音转写接口返回了空文本",
            )
        }

        return AiTranscriptionResult(
            text = transcriptText,
            model = endpoint.model,
            latencyMs = elapsedMsSince(startedNanos),
        )
    }

    private suspend fun executeChatCompletionAndParse(
        messages: JsonArray,
        promptContext: AiPromptContext,
        endpoint: AiResolvedEndpoint,
    ): AiParseResult {
        val url = OpenAiEndpointUrlNormalizer.chatCompletionsUrl(endpoint.baseUrl)
        val startedNanos = nanoTimeProvider()
        val client = buildClientWithTimeout(requestTimeoutMs)

        var response = sendChatRequest(
            client = client,
            url = url,
            endpoint = endpoint,
            messages = messages,
            includeJsonResponseFormat = true,
        )

        // Retry once without response_format if the provider rejects JSON mode with HTTP 400
        if (response.statusCode == 400 && response.body.contains("response_format", ignoreCase = true)) {
            response = sendChatRequest(
                client = client,
                url = url,
                endpoint = endpoint,
                messages = messages,
                includeJsonResponseFormat = false,
            )
        }

        if (!response.isSuccessful) {
            val detail = extractSanitizedApiError(response.body, endpoint.apiKey)
            throw AiClientException(
                httpStatusCode = response.statusCode,
                message = buildHttpFailureMessage(response.statusCode, detail),
            )
        }

        val assistantContent = extractAssistantContent(response.body)
            ?: throw AiClientException(
                httpStatusCode = response.statusCode,
                message = "AI 接口返回格式异常，缺少 choices[0].message.content",
            )

        val parsedBatch = draftJsonParser.parse(assistantContent, promptContext)
        return AiParseResult(
            batch = parsedBatch,
            model = endpoint.model,
            latencyMs = elapsedMsSince(startedNanos),
        )
    }

    private suspend fun sendChatRequest(
        client: OkHttpClient,
        url: String,
        endpoint: AiResolvedEndpoint,
        messages: JsonArray,
        includeJsonResponseFormat: Boolean,
    ): RawHttpResponse {
        val payload = buildJsonObject {
            put("model", endpoint.model)
            put("temperature", 0.1)
            if (includeJsonResponseFormat) {
                put(
                    "response_format",
                    buildJsonObject {
                        put("type", "json_object")
                    },
                )
            }
            put("messages", messages)
        }.toString()

        return try {
            executeRequest(
                client = client,
                request = Request.Builder()
                    .url(url)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Authorization", "Bearer ${endpoint.apiKey}")
                    .header("Accept", "application/json")
                    .build(),
            )
        } catch (e: IOException) {
            throw AiClientException(
                message = "AI 接口网络请求失败：${AiErrorSanitizer.sanitizeThrowable(e, endpoint.apiKey)}",
                cause = e,
            )
        }
    }

    private suspend fun buildClientWithTimeout(timeoutMs: Long): OkHttpClient {
        val base = httpClientFactory.currentClient()
        return base.newBuilder()
            .connectTimeout(minOf(timeoutMs, DEFAULT_CONNECT_TIMEOUT_MS), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun extractAssistantContent(body: String): String? {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            return null
        } ?: return null

        val choices = root["choices"] as? JsonArray ?: return null
        val firstChoice = choices.firstOrNull() as? JsonObject ?: return null
        val message = firstChoice["message"] as? JsonObject ?: return null
        val contentElement = message["content"] ?: return null
        return when (contentElement) {
            is JsonPrimitive -> contentElement.content
            is JsonArray -> contentElement
                .mapNotNull { part ->
                    val partObj = part as? JsonObject ?: return@mapNotNull null
                    (partObj["text"] as? JsonPrimitive)?.content
                }
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun extractTranscriptionText(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        val parsed = try {
            json.parseToJsonElement(trimmed) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val fromJson = (parsed?.get("text") as? JsonPrimitive)?.content?.trim()
        return fromJson ?: trimmed
    }

    private fun isModelPresentInModelsPayload(body: String, targetModel: String): Boolean {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            return false
        } ?: return false
        val data = root["data"] as? JsonArray ?: return false
        return data.any { elem ->
            val obj = elem as? JsonObject ?: return@any false
            val id = (obj["id"] as? JsonPrimitive)?.content
            id != null && id.equals(targetModel.trim(), ignoreCase = true)
        }
    }

    private fun extractSanitizedApiError(body: String, secretToRedact: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        val fromJson = try {
            val root = json.parseToJsonElement(trimmed) as? JsonObject
            val errObj = root?.get("error") as? JsonObject
            (errObj?.get("message") as? JsonPrimitive)?.content
                ?: (root?.get("message") as? JsonPrimitive)?.content
        } catch (_: Exception) {
            null
        }
        return AiErrorSanitizer.sanitize(fromJson ?: trimmed, secretToRedact)
    }

    private fun buildHttpFailureMessage(statusCode: Int, sanitizedDetail: String): String {
        val prefix = when (statusCode) {
            401, 403 -> "鉴权失败（HTTP $statusCode）"
            404 -> "接口或模型不存在（HTTP 404）"
            429 -> "请求频率受限或额度不足（HTTP 429）"
            in 500..599 -> "服务端异常（HTTP $statusCode）"
            else -> "接口返回错误（HTTP $statusCode）"
        }
        return if (sanitizedDetail.isBlank()) prefix else "$prefix：$sanitizedDetail"
    }

    private fun elapsedMsSince(startedNanos: Long): Long =
        ((nanoTimeProvider() - startedNanos).coerceAtLeast(0L)) / 1_000_000L

    private data class RawHttpResponse(
        val statusCode: Int,
        val body: String,
    ) {
        val isSuccessful: Boolean get() = statusCode in 200..299
    }

    private suspend fun executeRequest(
        client: OkHttpClient,
        request: Request,
    ): RawHttpResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        val bodyText = try {
                            res.body?.string().orEmpty()
                        } catch (e: IOException) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(e)
                            }
                            return
                        }
                        if (continuation.isActive) {
                            continuation.resume(
                                RawHttpResponse(
                                    statusCode = res.code,
                                    body = bodyText,
                                ),
                            )
                        }
                    }
                }
            },
        )
    }

    companion object {
        const val DEFAULT_AI_CALL_TIMEOUT_MS: Long = 60_000L
        const val DEFAULT_TEST_CALL_TIMEOUT_MS: Long = 15_000L
        private const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 15_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val FALLBACK_PROBE_STATUS_CODES = setOf(404, 405, 501)
    }
}
