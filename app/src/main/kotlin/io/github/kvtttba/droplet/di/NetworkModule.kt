package io.github.kvtttba.droplet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.repository.LocalMarketDataRepository
import io.github.kvtttba.droplet.core.database.repository.MarketDataRepository
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.network.MarketNetworkContract
import io.github.kvtttba.droplet.core.network.MarketProviderEndpoints
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.network.NetworkProxyConfigProvider
import io.github.kvtttba.droplet.core.network.RefreshThrottlePolicy
import io.github.kvtttba.droplet.core.network.fx.ExchangeRateProviderChain
import io.github.kvtttba.droplet.core.network.quote.MarketQuoteProviderChain
import io.github.kvtttba.droplet.core.security.NetworkProxyPreferencesRepository
import javax.inject.Singleton

/**
 * Hilt entry point exposing market/FX sync & proxy singletons for WorkManager workers (M2.3)
 * and guaranteeing compile-time Dagger graph validation for [NetworkModule].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MarketDataEntryPoint {
    fun marketDataRepository(): MarketDataRepository
    fun networkProxyPreferencesRepository(): NetworkProxyPreferencesRepository
    fun dropletHttpClientFactory(): DropletHttpClientFactory
    fun exchangeRateProviderChain(): ExchangeRateProviderChain
    fun marketQuoteProviderChain(): MarketQuoteProviderChain
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideNetworkClock(): NetworkClock = NetworkClock.SYSTEM

    @Provides
    @Singleton
    fun provideMarketProviderEndpoints(): MarketProviderEndpoints = MarketProviderEndpoints.DEFAULT

    @Provides
    @Singleton
    fun provideRefreshThrottlePolicy(): RefreshThrottlePolicy = RefreshThrottlePolicy(
        windowMs = MarketNetworkContract.DEFAULT_REFRESH_THROTTLE_MS,
    )

    @Provides
    @Singleton
    fun provideDropletHttpClientFactory(
        proxyConfigProvider: NetworkProxyConfigProvider,
    ): DropletHttpClientFactory = DropletHttpClientFactory(
        proxyConfigProvider = proxyConfigProvider,
    )

    @Provides
    @Singleton
    fun provideExchangeRateProviderChain(
        httpClientFactory: DropletHttpClientFactory,
        endpoints: MarketProviderEndpoints,
        clock: NetworkClock,
    ): ExchangeRateProviderChain = ExchangeRateProviderChain.default(
        httpClientFactory = httpClientFactory,
        endpoints = endpoints,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideMarketQuoteProviderChain(
        httpClientFactory: DropletHttpClientFactory,
        endpoints: MarketProviderEndpoints,
        clock: NetworkClock,
    ): MarketQuoteProviderChain = MarketQuoteProviderChain.default(
        httpClientFactory = httpClientFactory,
        endpoints = endpoints,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideMarketDataRepository(
        database: DropletDatabase,
        exchangeRateProviderChain: ExchangeRateProviderChain,
        marketQuoteProviderChain: MarketQuoteProviderChain,
        throttlePolicy: RefreshThrottlePolicy,
        clock: NetworkClock,
    ): MarketDataRepository = LocalMarketDataRepository(
        database = database,
        exchangeRateProviderChain = exchangeRateProviderChain,
        marketQuoteProviderChain = marketQuoteProviderChain,
        throttlePolicy = throttlePolicy,
        clock = clock,
    )
}
