package io.github.kvtttba.droplet.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.kvtttba.droplet.core.ai.AiDraftJsonParser
import io.github.kvtttba.droplet.core.ai.AiPromptBuilder
import io.github.kvtttba.droplet.core.ai.AndroidReceiptImageProcessor
import io.github.kvtttba.droplet.core.ai.OkHttpOpenAiCompatibleClient
import io.github.kvtttba.droplet.core.ai.OpenAiCompatibleClient
import io.github.kvtttba.droplet.core.ai.ReceiptImageProcessor
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.LedgerTransactionWriter
import io.github.kvtttba.droplet.core.database.repository.AiCaptureOrchestrator
import io.github.kvtttba.droplet.core.database.repository.AiDraftRepository
import io.github.kvtttba.droplet.core.database.repository.LedgerRepository
import io.github.kvtttba.droplet.core.database.repository.LocalAiDraftRepository
import io.github.kvtttba.droplet.core.database.seed.DefaultLedgerSeeder
import io.github.kvtttba.droplet.core.domain.ledger.LedgerEngine
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.security.AiConfigPreferencesRepository
import javax.inject.Singleton

/**
 * Hilt entry point exposing AI configuration, prompt builder, fault-tolerant JSON parser,
 * OpenAI-compatible HTTP client, EXIF-stripping receipt processor, offline draft repository,
 * and capture orchestrator singletons for compile-time graph verification.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AiEntryPoint {
    fun aiConfigPreferencesRepository(): AiConfigPreferencesRepository
    fun aiPromptBuilder(): AiPromptBuilder
    fun aiDraftJsonParser(): AiDraftJsonParser
    fun openAiCompatibleClient(): OpenAiCompatibleClient
    fun receiptImageProcessor(): ReceiptImageProcessor
    fun aiDraftRepository(): AiDraftRepository
    fun aiCaptureOrchestrator(): AiCaptureOrchestrator
}

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideAiPromptBuilder(): AiPromptBuilder = AiPromptBuilder()

    @Provides
    @Singleton
    fun provideAiDraftJsonParser(): AiDraftJsonParser = AiDraftJsonParser()

    @Provides
    @Singleton
    fun provideOpenAiCompatibleClient(
        httpClientFactory: DropletHttpClientFactory,
        promptBuilder: AiPromptBuilder,
        draftJsonParser: AiDraftJsonParser,
    ): OpenAiCompatibleClient = OkHttpOpenAiCompatibleClient(
        httpClientFactory = httpClientFactory,
        promptBuilder = promptBuilder,
        draftJsonParser = draftJsonParser,
    )

    @Provides
    @Singleton
    fun provideReceiptImageProcessor(
        @ApplicationContext context: Context,
    ): ReceiptImageProcessor = AndroidReceiptImageProcessor(context = context)

    @Provides
    @Singleton
    fun provideAiDraftRepository(
        database: DropletDatabase,
        transactionWriter: LedgerTransactionWriter,
        seeder: DefaultLedgerSeeder,
        ledgerEngine: LedgerEngine,
        receiptImageProcessor: ReceiptImageProcessor,
    ): AiDraftRepository = LocalAiDraftRepository(
        database = database,
        transactionWriter = transactionWriter,
        seeder = seeder,
        ledgerEngine = ledgerEngine,
        receiptImageProcessor = receiptImageProcessor,
    )

    @Provides
    @Singleton
    fun provideAiCaptureOrchestrator(
        aiDraftRepository: AiDraftRepository,
        ledgerRepository: LedgerRepository,
        aiConfigPreferencesRepository: AiConfigPreferencesRepository,
        openAiCompatibleClient: OpenAiCompatibleClient,
        receiptImageProcessor: ReceiptImageProcessor,
    ): AiCaptureOrchestrator = AiCaptureOrchestrator(
        aiDraftRepository = aiDraftRepository,
        ledgerRepository = ledgerRepository,
        aiConfigPreferencesRepository = aiConfigPreferencesRepository,
        openAiCompatibleClient = openAiCompatibleClient,
        receiptImageProcessor = receiptImageProcessor,
    )
}
