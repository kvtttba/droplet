package io.github.kvtttba.droplet.feature.aicapture

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.kvtttba.droplet.core.ai.AiConnectionTestResult
import io.github.kvtttba.droplet.core.ai.AiDraftAttachmentRef
import io.github.kvtttba.droplet.core.ai.AiImagePayload
import io.github.kvtttba.droplet.core.ai.AiParseResult
import io.github.kvtttba.droplet.core.ai.AiParsedDraftBatch
import io.github.kvtttba.droplet.core.ai.AiParsedTransactionCandidate
import io.github.kvtttba.droplet.core.ai.AiPromptContext
import io.github.kvtttba.droplet.core.ai.AiTranscriptionResult
import io.github.kvtttba.droplet.core.ai.AiUncertainFieldKeys
import io.github.kvtttba.droplet.core.ai.OpenAiCompatibleClient
import io.github.kvtttba.droplet.core.ai.ProcessedReceiptImage
import io.github.kvtttba.droplet.core.ai.ReceiptImageProcessor
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.dao.TransactionFilter
import io.github.kvtttba.droplet.core.database.repository.AiCaptureOrchestrator
import io.github.kvtttba.droplet.core.database.repository.LocalAiDraftRepository
import io.github.kvtttba.droplet.core.database.repository.LocalLedgerRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.database.seed.DefaultSeedCatalog
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.model.AiDraftInputType
import io.github.kvtttba.droplet.core.model.AiDraftStatus
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.model.TransactionDisplayType
import io.github.kvtttba.droplet.core.security.AiConfigPreferencesRepository
import io.github.kvtttba.droplet.core.security.AiEndpointSaveInput
import io.github.kvtttba.droplet.core.security.AiEndpointSlot
import io.github.kvtttba.droplet.core.security.AiResolvedEndpoint
import io.github.kvtttba.droplet.core.security.AiServiceConfig
import io.github.kvtttba.droplet.core.security.VoiceEngineMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AiCaptureViewModelsTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var database: DropletDatabase
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var draftRepository: LocalAiDraftRepository
    private lateinit var configRepo: FakeAiConfigRepo
    private lateinit var fakeClient: FakeOpenAiClient
    private lateinit var fakeProcessor: FakeImageProcessor
    private lateinit var orchestrator: AiCaptureOrchestrator

    private val nowMs = 1_795_840_200_000L

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(testDispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        database = DropletDatabase.createInMemory(app, allowMainThreadQueries = true)
        val writer = LedgerTransactionWriter(database)
        val seeder = DefaultLedgerSeeder(database)
        val engine = LedgerEngine(baseCurrency = Currency.CNY)
        ledgerRepository = LocalLedgerRepository(database, writer, seeder, engine)
        fakeProcessor = FakeImageProcessor()
        draftRepository = LocalAiDraftRepository(database, writer, seeder, engine, fakeProcessor)
        configRepo = FakeAiConfigRepo()
        fakeClient = FakeOpenAiClient(nowMs)
        orchestrator = AiCaptureOrchestrator(
            aiDraftRepository = draftRepository,
            ledgerRepository = ledgerRepository,
            aiConfigPreferencesRepository = configRepo,
            openAiCompatibleClient = fakeClient,
            receiptImageProcessor = fakeProcessor,
            clock = { nowMs },
        )
        ledgerRepository.ensureSeeded(nowMs)
        Unit
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun captureViewModel_offlineSaveSharePayloadAndOnlineParseFlow() = runBlocking {
        val vm = AiCaptureViewModel(
            orchestrator = orchestrator,
            aiDraftRepository = draftRepository,
            aiConfigPreferencesRepository = configRepo,
            receiptImageProcessor = fakeProcessor,
            savedStateHandle = SavedStateHandle(mapOf(AiCaptureViewModel.NAV_ARG_TAB to "text")),
        )

        // 1. Offline save text draft
        vm.onTextInputChange("早饭12块微信")
        vm.submitTextCapture(saveOfflineOnly = true)
        val afterOffline = awaitState(vm.uiState) { it.activeDrafts.size == 1 }
        assertEquals(AiDraftStatus.PENDING_ONLINE, afterOffline.activeDrafts.first().status)

        // 2. Consume inbound shared text payload
        vm.consumeSharedPayload(
            PendingAiSharePayload(id = 10L, sharedText = "咖啡28支付宝"),
        )
        assertEquals(AiCaptureTab.TEXT, vm.uiState.value.activeTab)
        assertEquals("咖啡28支付宝", vm.uiState.value.textInput)

        // 3. Configure AI endpoint and parse online -> triggers navigation to draft review
        configRepo.resolved = AiResolvedEndpoint(
            requestedSlot = AiEndpointSlot.TEXT,
            effectiveSlot = AiEndpointSlot.DEFAULT,
            baseUrl = "https://mock-ai.example.invalid/v1",
            model = "demo-model",
            apiKey = "demo-placeholder-0001",
        )
        vm.submitTextCapture(saveOfflineOnly = false)
        val afterOnline = awaitState(vm.uiState) {
            it.pendingNavigateToDraftId != null && it.activeDrafts.size == 2
        }
        assertNotNull(afterOnline.pendingNavigateToDraftId)

        // 4. Consume shared image URIs -> switches to IMAGE tab and populates EXIF-stripped refs
        vm.consumeSharedPayload(
            PendingAiSharePayload(
                id = 11L,
                imageUriStrings = listOf("content://demo/receipt1.jpg", "content://demo/receipt2.jpg"),
            ),
        )
        val afterShareImages = awaitState(vm.uiState) {
            it.activeTab == AiCaptureTab.IMAGE && it.selectedAttachments.size == 2
        }
        assertEquals(2, afterShareImages.selectedAttachments.size)

        // 5. Switch voice engine mode persists cleanly
        vm.setVoiceEngineMode(VoiceEngineMode.CUSTOM_AI)
        val afterVoiceMode = awaitState(vm.uiState) {
            it.aiServiceConfig.voiceEngineMode == VoiceEngineMode.CUSTOM_AI
        }
        assertEquals(VoiceEngineMode.CUSTOM_AI, afterVoiceMode.aiServiceConfig.voiceEngineMode)
    }

    @Test
    fun draftReviewViewModel_editsUncertainFieldsAndCommitsSelectedBatchAtomically() = runBlocking {
        val draft = draftRepository.createDraft(
            inputType = AiDraftInputType.TEXT,
            rawText = "早饭8块，打车25",
            status = AiDraftStatus.READY,
            parsedBatch = AiParsedDraftBatch(
                items = listOf(
                    AiParsedTransactionCandidate(
                        displayType = TransactionDisplayType.EXPENSE,
                        occurredAtEpochMs = nowMs,
                        amount = Money.fromDecimalString("8.00", Currency.CNY),
                        currency = Currency.CNY,
                        matchedCategoryId = DefaultSeedCatalog.AccountIds.EXP_FOOD_BREAKFAST,
                        matchedAccountId = DefaultSeedCatalog.AccountIds.WECHAT,
                        payee = "早餐铺",
                        confidenceDecimal = "0.95",
                        uncertainFields = emptySet(),
                    ),
                    AiParsedTransactionCandidate(
                        displayType = TransactionDisplayType.EXPENSE,
                        occurredAtEpochMs = nowMs,
                        amount = Money.fromDecimalString("25.00", Currency.CNY),
                        currency = Currency.CNY,
                        matchedCategoryId = null, // needs user review!
                        matchedAccountId = DefaultSeedCatalog.AccountIds.CREDIT_CARD,
                        payee = "网约车",
                        confidenceDecimal = "0.58",
                        uncertainFields = setOf(AiUncertainFieldKeys.CATEGORY_PATH),
                    ),
                ),
            ),
            nowEpochMs = nowMs,
        )

        val vm = AiDraftReviewViewModel(
            aiDraftRepository = draftRepository,
            ledgerRepository = ledgerRepository,
            savedStateHandle = SavedStateHandle(mapOf(AiDraftReviewViewModel.NAV_ARG_DRAFT_ID to draft.id)),
        )

        val hydrated = awaitState(vm.uiState) { !it.isLoading && it.items.size == 2 }
        val secondItem = hydrated.items[1]
        assertTrue(secondItem.isLowConfidence)
        assertTrue(secondItem.expanded)
        assertNotNull(secondItem.validationError)

        // Attempting commit before fixing item 2 blocks cleanly and keeps DB untouched
        vm.commitSelectedBatch()
        val blocked = awaitState(vm.uiState) { it.errorMessage != null }
        assertNotNull(blocked.errorMessage)
        assertEquals(0, ledgerRepository.observeFilteredTransactions(TransactionFilter()).first().size)

        // Fix item 2's category & amount -> clears uncertain category field and validation error
        vm.updateItemCategory(secondItem.localId, DefaultSeedCatalog.AccountIds.EXP_TRANSPORT_TAXI)
        vm.updateItemAmount(secondItem.localId, "26.50")
        vm.toggleItemTag(secondItem.localId, DefaultSeedCatalog.TagIds.WORKDAY)

        val updatedSecond = vm.uiState.value.items[1]
        assertFalse(AiUncertainFieldKeys.CATEGORY_PATH in updatedSecond.uncertainFields)
        assertEquals(null, updatedSecond.validationError)

        // Commit batch atomically
        vm.commitSelectedBatch()
        val committed = awaitState(vm.uiState) { it.committedSummaryMessage != null }
        assertTrue(committed.committedSummaryMessage!!.contains("2 笔"))
        assertEquals(2, ledgerRepository.observeFilteredTransactions(TransactionFilter()).first().size)
        assertEquals(AiDraftStatus.COMMITTED, draftRepository.getDraft(draft.id)!!.status)
    }

    private suspend fun <T> awaitState(
        flow: Flow<T>,
        predicate: (T) -> Boolean,
    ): T = withTimeout(5_000L) {
        flow.first(predicate)
    }

    private class FakeAiConfigRepo : AiConfigPreferencesRepository {
        private val flow = MutableStateFlow(AiServiceConfig())
        var resolved: AiResolvedEndpoint? = null

        override val config: Flow<AiServiceConfig> = flow
        override suspend fun currentConfig(): AiServiceConfig = flow.value
        override suspend fun saveEndpoint(input: AiEndpointSaveInput) = Unit
        override suspend fun setOverrideEnabled(slot: AiEndpointSlot, enabled: Boolean) = Unit
        override suspend fun clearEndpointApiKey(slot: AiEndpointSlot) = Unit
        override suspend fun setVoiceEngineMode(mode: VoiceEngineMode) {
            flow.value = flow.value.copy(voiceEngineMode = mode)
        }
        override suspend fun resolveEndpointForRequest(slot: AiEndpointSlot): AiResolvedEndpoint? = resolved
        override suspend fun decryptSlotApiKey(slot: AiEndpointSlot): String? = resolved?.apiKey
        override suspend fun clearAll() {
            resolved = null
        }
    }

    private class FakeOpenAiClient(private val nowMs: Long) : OpenAiCompatibleClient {
        override suspend fun testConnection(endpoint: AiResolvedEndpoint): AiConnectionTestResult =
            AiConnectionTestResult(true, endpoint.effectiveSlot, endpoint.baseUrl, endpoint.model, 200, "GET", 10L, "OK")

        override suspend fun parseText(
            text: String,
            promptContext: AiPromptContext,
            endpoint: AiResolvedEndpoint,
        ): AiParseResult = AiParseResult(
            batch = AiParsedDraftBatch(
                items = listOf(
                    AiParsedTransactionCandidate(
                        displayType = TransactionDisplayType.EXPENSE,
                        occurredAtEpochMs = nowMs,
                        amount = Money.fromDecimalString("28.00", Currency.CNY),
                        currency = Currency.CNY,
                        matchedCategoryId = DefaultSeedCatalog.AccountIds.EXP_FOOD_SNACKS,
                        matchedAccountId = DefaultSeedCatalog.AccountIds.ALIPAY,
                        payee = "咖啡馆",
                        note = text,
                        confidenceDecimal = "0.93",
                    ),
                ),
            ),
            model = endpoint.model,
            latencyMs = 50L,
        )

        override suspend fun parseImages(
            images: List<AiImagePayload>,
            promptContext: AiPromptContext,
            endpoint: AiResolvedEndpoint,
            userHint: String?,
        ): AiParseResult = parseText(userHint ?: "图片识别", promptContext, endpoint)

        override suspend fun transcribeAudio(
            audioBytes: ByteArray,
            fileName: String,
            mimeType: String,
            endpoint: AiResolvedEndpoint,
            language: String?,
        ): AiTranscriptionResult = AiTranscriptionResult("咖啡28支付宝", endpoint.model, 40L)
    }

    private class FakeImageProcessor : ReceiptImageProcessor {
        override suspend fun processUri(uri: Uri): ProcessedReceiptImage = processBytes(byteArrayOf(1, 2, 3))
        override suspend fun processUris(uris: List<Uri>): List<ProcessedReceiptImage> =
            uris.mapIndexed { idx, _ ->
                ProcessedReceiptImage(
                    attachmentRef = AiDraftAttachmentRef(
                        localRelPath = "receipts/shared_$idx.jpg",
                        sha256 = "c".repeat(64),
                        sizeBytes = 1024L,
                        width = 800,
                        height = 1200,
                        sliceCount = 1,
                    ),
                    visionPayloads = listOf(AiImagePayload("AQID")),
                )
            }
        override suspend fun processBytes(rawBytes: ByteArray): ProcessedReceiptImage =
            processUris(listOf(Uri.EMPTY)).first()
        override suspend fun loadVisionPayloads(attachments: List<AiDraftAttachmentRef>): List<AiImagePayload> =
            attachments.map { AiImagePayload("AQID") }
        override fun resolveLocalFile(localRelPath: String): File? = null
        override suspend fun deleteReceiptFiles(attachments: List<AiDraftAttachmentRef>) = Unit
    }
}
