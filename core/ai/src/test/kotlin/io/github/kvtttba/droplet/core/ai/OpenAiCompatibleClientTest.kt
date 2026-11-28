package io.github.kvtttba.droplet.core.ai

import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.security.AiEndpointSlot
import io.github.kvtttba.droplet.core.security.AiResolvedEndpoint
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpOpenAiCompatibleClient
    private val samplePlaceholderPass = "demo-local-mock-pass-4321"

    private val fixedNowMs = LocalDate.of(2026, 11, 27)
        .atTime(12, 30)
        .toInstant(ZoneOffset.ofHours(8))
        .toEpochMilli()

    private val promptContext = AiPromptContext(
        currentTimeEpochMs = fixedNowMs,
        defaultCurrency = Currency.CNY,
        accounts = listOf(
            AiPromptAccountRef("acc_wx", "微信零钱", AccountType.ASSET, AccountSubType.DEBIT_CARD, Currency.CNY),
        ),
        categories = listOf(
            AiPromptCategoryRef("cat_lunch", "餐饮/午餐", AccountType.EXPENSE, "餐饮", "午餐"),
        ),
        tags = listOf(AiPromptTagRef("tag_work", "工作日")),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpOpenAiCompatibleClient(
            httpClientFactory = DropletHttpClientFactory(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun testEndpoint(
        pathSuffix: String = "/v1",
        slot: AiEndpointSlot = AiEndpointSlot.DEFAULT,
        model: String = "demo-model-mini",
    ): AiResolvedEndpoint = AiResolvedEndpoint(
        requestedSlot = slot,
        effectiveSlot = slot,
        baseUrl = server.url(pathSuffix).toString().trimEnd('/'),
        model = model,
        apiKey = samplePlaceholderPass,
    )

    @Test
    fun urlNormalizerHandlesStandardBareCustomAndFullEndpointUrls() {
        assertEquals(
            "https://mock-ai.example.invalid/v1",
            OpenAiEndpointUrlNormalizer.normalizeBaseUrl("https://mock-ai.example.invalid"),
        )
        assertEquals(
            "https://mock-ai.example.invalid/v1",
            OpenAiEndpointUrlNormalizer.normalizeBaseUrl("https://mock-ai.example.invalid/"),
        )
        assertEquals(
            "https://mock-ai.example.invalid/v1",
            OpenAiEndpointUrlNormalizer.normalizeBaseUrl("https://mock-ai.example.invalid/v1/"),
        )
        assertEquals(
            "https://mock-ai.example.invalid/compatible-mode/v1",
            OpenAiEndpointUrlNormalizer.normalizeBaseUrl("https://mock-ai.example.invalid/compatible-mode/v1/"),
        )
        assertEquals(
            "https://mock-ai.example.invalid/api/paas/v4",
            OpenAiEndpointUrlNormalizer.normalizeBaseUrl("https://mock-ai.example.invalid/api/paas/v4"),
        )
        assertEquals(
            "https://mock-ai.example.invalid/v1",
            OpenAiEndpointUrlNormalizer.normalizeBaseUrl("https://mock-ai.example.invalid/v1/chat/completions"),
        )
    }

    @Test
    fun testConnection_succeedsViaModelsProbeAndChecksAuthorizationHeader() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[{"id":"demo-model-mini"}]}"""),
        )

        val result = client.testConnection(testEndpoint(pathSuffix = ""))
        val recorded = server.takeRequest()

        assertEquals("GET", recorded.method)
        assertEquals("/v1/models", recorded.path)
        assertEquals("Bearer $samplePlaceholderPass", recorded.getHeader("Authorization"))
        assertTrue(result.success)
        assertEquals(200, result.httpStatusCode)
        assertEquals("GET /models", result.probeMethod)
        assertTrue(result.message.contains("demo-model-mini"))
    }

    @Test
    fun testConnection_fallsBackToChatCompletionsWhenModelsReturns404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"not found"}}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"pong"}}]}"""),
        )

        val result = client.testConnection(testEndpoint(pathSuffix = "/custom/v1"))
        val req1 = server.takeRequest()
        val req2 = server.takeRequest()

        assertEquals("/custom/v1/models", req1.path)
        assertEquals("/custom/v1/chat/completions", req2.path)
        assertTrue(req2.body.readUtf8().contains("\"max_tokens\":1"))
        assertTrue(result.success)
        assertEquals("POST /chat/completions", result.probeMethod)
    }

    @Test
    fun testConnection_authErrorAndServerEchoNeverLeakApiKey() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid header Bearer $samplePlaceholderPass"}}"""),
        )

        val authResult = client.testConnection(testEndpoint())
        assertFalse(authResult.success)
        assertEquals(401, authResult.httpStatusCode)
        assertFalse(authResult.message.contains(samplePlaceholderPass))

        // 500 error echoing raw credential in body must be scrubbed
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"message":"Upstream failed for $samplePlaceholderPass"}}"""),
        )
        val serverResult = client.testConnection(testEndpoint())
        assertFalse(serverResult.success)
        assertEquals(500, serverResult.httpStatusCode)
        assertFalse(serverResult.message.contains(samplePlaceholderPass))
        assertTrue(serverResult.message.contains("[REDACTED]"))
    }

    @Test
    fun parseText_callsChatCompletionsAndRetriesWithoutResponseFormatOn400() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"Unsupported parameter: response_format"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "{\"items\":[{\"display_type\":\"EXPENSE\",\"occurred_at\":\"2026-11-27T12:15:00+08:00\",\"amount\":\"36.50\",\"currency\":\"CNY\",\"category_path\":\"餐饮/午餐\",\"account_name\":\"微信零钱\",\"payee\":\"简餐馆\",\"tags\":[\"工作日\"],\"confidence\":0.95,\"uncertain_fields\":[]}]}"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val result = client.parseText(
            text = "午饭简餐馆36.5微信",
            promptContext = promptContext,
            endpoint = testEndpoint(slot = AiEndpointSlot.TEXT),
        )

        val firstReq = server.takeRequest()
        val secondReq = server.takeRequest()
        assertTrue(firstReq.body.readUtf8().contains("response_format"))
        assertFalse(secondReq.body.readUtf8().contains("response_format"))

        assertEquals(1, result.batch.items.size)
        val item = result.batch.items.single()
        assertEquals(Money(365_000L, Currency.CNY), item.amount)
        assertEquals("cat_lunch", item.matchedCategoryId)
        assertEquals("acc_wx", item.matchedAccountId)
    }

    @Test
    fun parseImages_sendsMultiImageVisionDataUrisAndParsesBatch() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "{\"items\":[{\"display_type\":\"EXPENSE\",\"occurred_at\":\"2026-11-27T12:20:00+08:00\",\"amount\":\"58.00\",\"currency\":\"CNY\",\"category_path\":\"餐饮/午餐\",\"account_name\":\"微信零钱\",\"payee\":\"小票商户A\",\"confidence\":0.92,\"uncertain_fields\":[]},{\"display_type\":\"EXPENSE\",\"occurred_at\":\"2026-11-27T12:25:00+08:00\",\"amount\":\"12.00\",\"currency\":\"CNY\",\"category_path\":\"餐饮/午餐\",\"account_name\":\"微信零钱\",\"payee\":\"小票商户B\",\"confidence\":0.88,\"uncertain_fields\":[]}]}"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val images = listOf(
            AiImagePayload(base64Data = "AAAA", mimeType = "image/jpeg"),
            AiImagePayload(base64Data = "BBBB", mimeType = "image/png"),
        )
        val result = client.parseImages(
            images = images,
            promptContext = promptContext,
            endpoint = testEndpoint(slot = AiEndpointSlot.VISION, model = "demo-vision-vl"),
        )

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertEquals("/v1/chat/completions", recorded.path)
        assertTrue(body.contains("data:image/jpeg;base64,AAAA"))
        assertTrue(body.contains("data:image/png;base64,BBBB"))
        assertEquals(2, result.batch.items.size)
        assertEquals(Money(580_000L, Currency.CNY), result.batch.items[0].amount)
        assertEquals(Money(120_000L, Currency.CNY), result.batch.items[1].amount)
    }

    @Test
    fun transcribeAudio_postsMultipartToWhisperCompatibleEndpoint() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"text":"早饭八块微信，中午打车二十五"}"""),
        )

        val result = client.transcribeAudio(
            audioBytes = byteArrayOf(1, 2, 3, 4, 5),
            fileName = "voice_note.m4a",
            mimeType = "audio/mp4",
            endpoint = testEndpoint(slot = AiEndpointSlot.VOICE, model = "whisper-1"),
            language = "zh",
        )

        val recorded = server.takeRequest()
        val bodyText = recorded.body.readUtf8()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/audio/transcriptions", recorded.path)
        assertTrue(recorded.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
        assertTrue(bodyText.contains("name=\"file\"; filename=\"voice_note.m4a\""))
        assertTrue(bodyText.contains("whisper-1"))
        assertEquals("早饭八块微信，中午打车二十五", result.text)
    }
}
