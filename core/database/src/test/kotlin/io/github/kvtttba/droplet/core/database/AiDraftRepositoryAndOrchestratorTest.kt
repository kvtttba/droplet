package io.github.kvtttba.droplet.core.database

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.ai.AiDraftAttachmentRef
import io.github.kvtttba.droplet.core.ai.AiDraftBatchCodec
import io.github.kvtttba.droplet.core.ai.AiImagePayload
import io.github.kvtttba.droplet.core.ai.AiParsedDraftBatch
import io.github.kvtttba.droplet.core.ai.AiParsedTransactionCandidate
import io.github.kvtttba.droplet.core.ai.OkHttpOpenAiCompatibleClient
import io.github.kvtttba.droplet.core.ai.ProcessedReceiptImage
import io.github.kvtttba.droplet.core.ai.ReceiptImageProcessor
import io.github.kvtttba.droplet.core.database.dao.TransactionFilter
import io.github.kvtttba.droplet.core.database.entity.toDomain
import io.github.kvtttba.droplet.core.database.repository.AiCaptureOrchestrator
import io.github.kvtttba.droplet.core.database.repository.AiCaptureOutcome
import io.github.kvtttba.droplet.core.database.repository.LocalAiDraftRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLedgerRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.domain.ledger.LedgerValidator
import io.github.kvtttba.droplet.core.model.AiDraftInputType
import io.github.kvtttba.droplet.core.model.AiDraftStatus
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.model.TransactionSource
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.security.AiConfigPreferencesRepository
import io.github.kvtttba.droplet.core.security.AiEndpointSaveInput
import io.github.kvtttba.droplet.core.security.AiEndpointSlot
import io.github.kvtttba.droplet.core.security.AiResolvedEndpoint
import io.github.kvtttba.droplet.core.security.AiServiceConfig
import io.github.kvtttba.droplet.core.security.VoiceEngineMode
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AiDraftRepositoryAndOrchestratorTest {

    private lateinit var database: DropletDatabase
    private lateinit var writer: LedgerTransactionWriter
    private lateinit var seeder: DefaultLedgerSeeder
    private lateinit var ledgerEngine: LedgerEngine
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var draftRepository: LocalAiDraftRepository
    private lateinit var mockWebServer: MockWebServer
    private lateinit var fakeAiConfigRepo: InMemoryAiConfigRepo
    private lateinit var fakeImageProcessor: FakeReceiptImageProcessor
    private lateinit var orchestrator: AiCaptureOrchestrator

    private val fixedNowMs = 1_795_840_200_000L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = DropletDatabase.createInMemory(context, allowMainThreadQueries = true)
        writer = LedgerTransactionWriter(database)
        seeder = DefaultLedgerSeeder(database)
        ledgerEngine = LedgerEngine(baseCurrency = Currency.CNY)
        ledgerRepository = LocalLedgerRepository(database, writer, seeder, ledgerEngine)
        fakeImageProcessor = FakeReceiptImageProcessor()
        draftRepository = LocalAiDraftRepository(
            database = database,
            transactionWriter = writer,
            seeder = seeder,
            ledgerEngine = ledgerEngine,
            receiptImageProcessor = fakeImageProcessor,
        )

        mockWebServer = MockWebServer()
        mockWebServer.start()

        fakeAiConfigRepo = InMemoryAiConfigRepo()
        val openAiClient = OkHttpOpenAiCompatibleClient(
            httpClientFactory = DropletHttpClientFactory(),
        )
        orchestrator = AiCaptureOrchestrator(
            aiDraftRepository = draftRepository,
            ledgerRepository = ledgerRepository,
            aiConfigPreferencesRepository = fakeAiConfigRepo,
            openAiCompatibleClient = openAiClient,
            receiptImageProcessor = fakeImageProcessor,
            clock = { fixedNowMs },
            zoneIdProvider = { ZoneId.of("Asia/Shanghai") },
        )
        ledgerRepository.ensureSeeded(fixedNowMs)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        database.close()
    }

    @Test
    fun commitDraftBatch_commitsMultiItemDraftWithReceiptsAtomicallyAndPreventsDuplicateCommit() = runTest {
        val receiptRef = AiDraftAttachmentRef(
            localRelPath = "receipts/sample_01.jpg",
            sha256 = "a".repeat(64),
            sizeBytes = 12_345L,
            width = 1200,
            height = 1600,
            sliceCount = 1,
        )
        val candidates = listOf(
            AiParsedTransactionCandidate(
                displayType = TransactionDisplayType.EXPENSE,
                occurredAtEpochMs = fixedNowMs,
                amount = Money.fromDecimalString("18.50", Currency.CNY),
                currency = Currency.CNY,
                matchedCategoryId = DefaultSeedCatalog.AccountIds.EXP_FOOD_BREAKFAST,
                matchedAccountId = DefaultSeedCatalog.AccountIds.WECHAT,
                payee = "早餐铺",
                matchedTagIds = listOf(DefaultSeedCatalog.TagIds.WORKDAY),
            ),
            AiParsedTransactionCandidate(
                displayType = TransactionDisplayType.EXPENSE,
                occurredAtEpochMs = fixedNowMs + 60_000L,
                amount = Money.fromDecimalString("10.00", Currency.USD),
                currency = Currency.USD,
                matchedCategoryId = DefaultSeedCatalog.AccountIds.EXP_EDUCATION_SUBSCRIPTION,
                matchedAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
                payee = "Tool Plan",
            ),
        )
        val draft = draftRepository.createDraft(
            inputType = AiDraftInputType.IMAGE,
            rawText = "双笔小票",
            attachments = listOf(receiptRef),
            status = AiDraftStatus.READY,
            parsedBatch = AiParsedDraftBatch(items = candidates),
            nowEpochMs = fixedNowMs,
        )

        assertEquals(1, draftRepository.observeActiveDraftCount().first())

        val commitResult = draftRepository.commitDraftBatch(
            draftId = draft.id,
            selectedCandidates = candidates,
            attachReceiptsToTransactions = true,
            nowEpochMs = fixedNowMs + 1000L,
        ).getOrThrow()

        assertEquals(2, commitResult.committedTransactionIds.size)
        assertEquals(2, commitResult.attachedReceiptCount)
        assertEquals(0, draftRepository.observeActiveDraftCount().first())

        val updatedDraft = draftRepository.getDraft(draft.id)!!
        assertEquals(AiDraftStatus.COMMITTED, updatedDraft.status)
        assertEquals(2, database.attachmentDao().countAll())

        val allAccounts = database.accountDao().getAll(includeArchived = true, includeSystem = true)
            .associate { it.id to it.toDomain() }
        commitResult.committedTransactionIds.forEach { txId ->
            val tx = ledgerRepository.getTransactionById(txId)
            assertNotNull(tx)
            assertEquals(TransactionSource.AI_IMAGE, tx!!.source)
            LedgerValidator.requireValid(tx, allAccounts)
        }

        // Duplicate commit must fail cleanly
        val duplicate = draftRepository.commitDraftBatch(
            draftId = draft.id,
            selectedCandidates = candidates,
        )
        assertTrue(duplicate.isFailure)
        assertTrue(duplicate.exceptionOrNull()!!.message!!.contains("已完成入账"))
    }

    @Test
    fun commitDraftBatch_rollsBackEntireBatchWhenAnyCandidateIsInvalid() = runTest {
        val validItem = AiParsedTransactionCandidate(
            displayType = TransactionDisplayType.EXPENSE,
            occurredAtEpochMs = fixedNowMs,
            amount = Money.fromDecimalString("25.00", Currency.CNY),
            currency = Currency.CNY,
            matchedCategoryId = DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TAXI,
            matchedAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
            payee = "打车",
        )
        val invalidItem = AiParsedTransactionCandidate(
            displayType = TransactionDisplayType.EXPENSE,
            occurredAtEpochMs = fixedNowMs,
            amount = Money.fromDecimalString("50.00", Currency.CNY),
            currency = Currency.CNY,
            matchedCategoryId = null, // missing required expense category
            matchedAccountId = DefaultSeedCatalog.AccountIds.WECHAT,
        )
        val draft = draftRepository.createDraft(
            inputType = AiDraftInputType.TEXT,
            rawText = "打车25，另一笔待补分类",
            status = AiDraftStatus.READY,
            parsedBatch = AiParsedDraftBatch(items = listOf(validItem, invalidItem)),
            nowEpochMs = fixedNowMs,
        )

        val result = draftRepository.commitDraftBatch(
            draftId = draft.id,
            selectedCandidates = listOf(validItem, invalidItem),
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("第 2 笔"))
        assertEquals(0, ledgerRepository.observeFilteredTransactions(TransactionFilter()).first().size)
        assertEquals(0, database.attachmentDao().countAll())
        assertEquals(AiDraftStatus.READY, draftRepository.getDraft(draft.id)!!.status)
    }

    @Test
    fun orchestrator_onlineTextParseOfflineFallbackRetryAndCustomVoicePipeline() = runTest {
        // 1. Without configured endpoint, text capture falls back cleanly to PENDING_ONLINE
        val unconfiguredOutcome = orchestrator.submitTextCapture(
            rawText = "早饭8块微信，中午打车25刷信用卡",
            saveOfflineOnly = false,
        )
        assertTrue(unconfiguredOutcome is AiCaptureOutcome.NeedsConfiguration)
        assertEquals(AiDraftStatus.PENDING_ONLINE, unconfiguredOutcome.draft.status)

        // 2. Configure mock OpenAI endpoint and simulate HTTP 500 -> FAILED (preserving raw text)
        fakeAiConfigRepo.setResolvedEndpoint(
            AiResolvedEndpoint(
                requestedSlot = AiEndpointSlot.TEXT,
                effectiveSlot = AiEndpointSlot.DEFAULT,
                baseUrl = mockWebServer.url("/v1").toString(),
                model = "mock-text-model",
                apiKey = "demo-placeholder-cred-0001",
            ),
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(500).setBody("""{"error":{"message":"upstream busy"}}"""),
        )
        val failedRetry = orchestrator.retryDraft(unconfiguredOutcome.draft.id)
        assertTrue(failedRetry is AiCaptureOutcome.FailedStoredInDrafts)
        assertEquals(AiDraftStatus.FAILED, failedRetry.draft.status)

        // 3. Retry succeeds with 2 items -> verifies privacy-minimized prompt and READY draft
        val aiJson = """
            {
              "choices": [
                {
                  "message": {
                    "content": "{\"items\":[{\"display_type\":\"EXPENSE\",\"occurred_at\":\"2026-11-28T08:10:00+08:00\",\"amount\":\"8.00\",\"currency\":\"CNY\",\"category_path\":\"餐饮/早餐\",\"account_name\":\"微信零钱\",\"payee\":\"早餐店\",\"tags\":[\"工作日\"],\"confidence\":0.94,\"uncertain_fields\":[]},{\"display_type\":\"EXPENSE\",\"occurred_at\":\"2026-11-28T12:20:00+08:00\",\"amount\":\"25.00\",\"currency\":\"CNY\",\"category_path\":\"交通/打车\",\"account_name\":\"信用卡\",\"payee\":\"网约车\",\"tags\":[],\"confidence\":0.88,\"uncertain_fields\":[]}]}"
                  }
                }
              ]
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(aiJson))

        val recovered = orchestrator.retryDraft(unconfiguredOutcome.draft.id)
        assertTrue(recovered is AiCaptureOutcome.ParsedReady)
        val ready = recovered as AiCaptureOutcome.ParsedReady
        assertEquals(2, ready.batch.items.size)
        assertEquals(AiDraftStatus.READY, ready.draft.status)

        // Verify mock request body obeyed privacy minimization (no balances/history)
        mockWebServer.takeRequest() // 500 request
        val successReqBody = mockWebServer.takeRequest().body.readUtf8()
        assertTrue(successReqBody.contains("微信零钱"))
        assertTrue(successReqBody.contains("餐饮/早餐"))
        assertFalse(successReqBody.contains("balance"))

        // 4. Custom AI voice transcription -> text parse pipeline
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"text":"晚饭42支付宝"}"""),
        )
        val singleAiJson = """
            {
              "choices": [
                {
                  "message": {
                    "content": "{\"items\":[{\"display_type\":\"EXPENSE\",\"occurred_at\":\"2026-11-28T19:00:00+08:00\",\"amount\":\"42.00\",\"currency\":\"CNY\",\"category_path\":\"餐饮/晚餐\",\"account_name\":\"支付宝\",\"payee\":\"面馆\",\"confidence\":0.91,\"uncertain_fields\":[]}]}"
                  }
                }
              ]
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(singleAiJson))

        val voiceOutcome = orchestrator.submitCustomVoiceAudio(
            audioBytes = byteArrayOf(1, 2, 3, 4),
            fileName = "voice_demo.m4a",
            mimeType = "audio/mp4",
        ).getOrThrow()
        assertTrue(voiceOutcome is AiCaptureOutcome.ParsedReady)
        assertEquals(AiDraftInputType.VOICE, voiceOutcome.draft.inputType)
        assertEquals("晚饭42支付宝", voiceOutcome.draft.rawText)
    }

    @Test
    fun orchestrator_convertOfflineDraftToManualReview_extractsAmountsAndMarksUncertainFields() = runTest {
        val offline = orchestrator.submitTextCapture(
            rawText = "测试早饭 18.50 微信",
            saveOfflineOnly = true,
        )
        assertTrue(offline is AiCaptureOutcome.SavedOffline)

        val converted = orchestrator.convertDraftToManualReview(offline.draft.id).getOrThrow()
        assertEquals(AiDraftStatus.READY, converted.status)
        val batch = AiDraftBatchCodec.decodeBatchOrNull(converted.parsedItemsJson)
        assertNotNull(batch)
        assertEquals(1, batch!!.items.size)
        assertEquals(Money.fromDecimalString("18.50", Currency.CNY), batch.items.first().amount)
        assertTrue(batch.items.first().needsUserReview)
    }

    private class InMemoryAiConfigRepo : AiConfigPreferencesRepository {
        private val configFlow = MutableStateFlow(AiServiceConfig())
        private var resolved: AiResolvedEndpoint? = null

        fun setResolvedEndpoint(endpoint: AiResolvedEndpoint?) {
            resolved = endpoint
        }

        override val config: Flow<AiServiceConfig> = configFlow
        override suspend fun currentConfig(): AiServiceConfig = configFlow.value
        override suspend fun saveEndpoint(input: AiEndpointSaveInput) = Unit
        override suspend fun setOverrideEnabled(slot: AiEndpointSlot, enabled: Boolean) = Unit
        override suspend fun clearEndpointApiKey(slot: AiEndpointSlot) = Unit
        override suspend fun setVoiceEngineMode(mode: VoiceEngineMode) {
            configFlow.value = configFlow.value.copy(voiceEngineMode = mode)
        }
        override suspend fun resolveEndpointForRequest(slot: AiEndpointSlot): AiResolvedEndpoint? =
            resolved?.let {
                AiResolvedEndpoint(
                    requestedSlot = slot,
                    effectiveSlot = it.effectiveSlot,
                    baseUrl = it.baseUrl,
                    model = it.model,
                    apiKey = it.apiKey,
                )
            }
        override suspend fun decryptSlotApiKey(slot: AiEndpointSlot): String? = resolved?.apiKey
        override suspend fun clearAll() {
            resolved = null
        }
    }

    private class FakeReceiptImageProcessor : ReceiptImageProcessor {
        override suspend fun processUri(uri: Uri): ProcessedReceiptImage =
            processBytes(byteArrayOf(1, 2, 3))

        override suspend fun processUris(uris: List<Uri>): List<ProcessedReceiptImage> =
            uris.map { processUri(it) }

        override suspend fun processBytes(rawBytes: ByteArray): ProcessedReceiptImage {
            val ref = AiDraftAttachmentRef(
                localRelPath = "receipts/fake_${System.nanoTime()}.jpg",
                sha256 = "b".repeat(64),
                sizeBytes = rawBytes.size.toLong(),
                width = 800,
                height = 600,
                sliceCount = 1,
            )
            return ProcessedReceiptImage(
                attachmentRef = ref,
                visionPayloads = listOf(AiImagePayload(base64Data = "AQID", mimeType = "image/jpeg")),
            )
        }

        override suspend fun loadVisionPayloads(attachments: List<AiDraftAttachmentRef>): List<AiImagePayload> =
            attachments.map { AiImagePayload(base64Data = "AQID", mimeType = "image/jpeg") }

        override fun resolveLocalFile(localRelPath: String): File? = null

        override suspend fun deleteReceiptFiles(attachments: List<AiDraftAttachmentRef>) = Unit
    }
}
