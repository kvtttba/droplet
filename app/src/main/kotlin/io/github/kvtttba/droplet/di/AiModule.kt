package io.github.kvtttba.droplet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.kvtttba.droplet.core.ai.AiDraftJsonParser
import io.github.kvtttba.droplet.core.ai.AiPromptBuilder
import io.github.kvtttba.droplet.core.ai.OkHttpOpenAiCompatibleClient
import io.github.kvtttba.droplet.core.ai.OpenAiCompatibleClient
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.security.AiConfigPreferencesRepository
import javax.inject.Singleton

/**
 * Hilt entry point exposing AI configuration, prompt builder, fault-tolerant JSON parser,
 * and OpenAI-compatible HTTP client singletons, ensuring compile-time graph verification.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AiEntryPoint {
    fun aiConfigPreferencesRepository(): AiConfigPreferencesRepository
    fun aiPromptBuilder(): AiPromptBuilder
    fun aiDraftJsonParser(): AiDraftJsonParser
    fun openAiCompatibleClient(): OpenAiCompatibleClient
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
}
