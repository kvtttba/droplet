package io.github.kvtttba.droplet.di

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.kvtttba.droplet.core.network.NetworkProxyConfigProvider
import io.github.kvtttba.droplet.core.security.AiConfigPreferencesRepository
import io.github.kvtttba.droplet.core.security.AndroidBiometricAuthenticator
import io.github.kvtttba.droplet.core.security.AndroidKeystoreSecretKeyProvider
import io.github.kvtttba.droplet.core.security.AppLockStateMachine
import io.github.kvtttba.droplet.core.security.BiometricAuthenticator
import io.github.kvtttba.droplet.core.security.BudgetAndRecurringPreferencesRepository
import io.github.kvtttba.droplet.core.security.CreditCardReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.DataStoreAiConfigPreferencesRepository
import io.github.kvtttba.droplet.core.security.DataStoreBudgetAndRecurringPreferencesRepository
import io.github.kvtttba.droplet.core.security.DataStoreCreditCardReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.DataStoreDebtReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.DataStoreLoanReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.DataStoreMarketSyncPreferencesRepository
import io.github.kvtttba.droplet.core.security.DataStoreNetworkProxyPreferencesRepository
import io.github.kvtttba.droplet.core.security.DataStorePrivacyPreferencesRepository
import io.github.kvtttba.droplet.core.security.DataStoreWebDavSyncPreferencesRepository
import io.github.kvtttba.droplet.core.security.DebtReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.KeystoreSecretCipher
import io.github.kvtttba.droplet.core.security.LoanReminderPreferencesRepository
import io.github.kvtttba.droplet.core.security.MarketSyncPreferencesRepository
import io.github.kvtttba.droplet.core.security.MonotonicClock
import io.github.kvtttba.droplet.core.security.NetworkProxyPreferencesRepository
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.core.security.SecretCipher
import io.github.kvtttba.droplet.core.security.SecretKeyProvider
import io.github.kvtttba.droplet.core.security.SecurityContract
import io.github.kvtttba.droplet.core.security.WebDavBackupCrypto
import io.github.kvtttba.droplet.core.security.WebDavSyncPreferencesRepository
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NetworkProxyDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MarketSyncDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CreditCardReminderDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LoanReminderDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DebtReminderDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AiConfigDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BudgetRecurringDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WebDavSyncDataStore

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun providePrivacyPreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = applicationScope,
        produceFile = {
            context.preferencesDataStoreFile(SecurityContract.PRIVACY_PREFERENCES_FILE)
        },
    )

    @Provides
    @Singleton
    fun providePrivacyPreferencesRepository(
        dataStore: DataStore<Preferences>,
    ): PrivacyPreferencesRepository = DataStorePrivacyPreferencesRepository(dataStore)

    @Provides
    @Singleton
    @NetworkProxyDataStore
    fun provideNetworkProxyPreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = applicationScope,
        produceFile = {
            context.preferencesDataStoreFile(SecurityContract.NETWORK_PROXY_PREFERENCES_FILE)
        },
    )

    @Provides
    @Singleton
    fun provideNetworkProxyPreferencesRepository(
        @NetworkProxyDataStore dataStore: DataStore<Preferences>,
    ): NetworkProxyPreferencesRepository = DataStoreNetworkProxyPreferencesRepository(dataStore)

    @Provides
    @Singleton
    fun provideNetworkProxyConfigProvider(
        repository: NetworkProxyPreferencesRepository,
    ): NetworkProxyConfigProvider = repository

    @Provides
    @Singleton
    @MarketSyncDataStore
    fun provideMarketSyncPreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = applicationScope,
        produceFile = {
            context.preferencesDataStoreFile(SecurityContract.MARKET_SYNC_PREFERENCES_FILE)
        },
    )

    @Provides
    @Singleton
    fun provideMarketSyncPreferencesRepository(
        @MarketSyncDataStore dataStore: DataStore<Preferences>,
    ): MarketSyncPreferencesRepository = DataStoreMarketSyncPreferencesRepository(dataStore)

    @Provides
    @Singleton
    @CreditCardReminderDataStore
    fun provideCreditCardReminderPreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = applicationScope,
        produceFile = {
            context.preferencesDataStoreFile(SecurityContract.CREDIT_CARD_REMINDER_PREFERENCES_FILE)
        },
    )

    @Provides
    @Singleton
    fun provideCreditCardReminderPreferencesRepository(
        @CreditCardReminderDataStore dataStore: DataStore<Preferences>,
    ): CreditCardReminderPreferencesRepository =
        DataStoreCreditCardReminderPreferencesRepository(dataStore)

    @Provides
    @Singleton
    @LoanReminderDataStore
    fun provideLoanReminderPreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = applicationScope,
        produceFile = {
            context.preferencesDataStoreFile(SecurityContract.LOAN_REMINDER_PREFERENCES_FILE)
        },
    )

    @Provides
    @Singleton
    fun provideLoanReminderPreferencesRepository(
        @LoanReminderDataStore dataStore: DataStore<Preferences>,
    ): LoanReminderPreferencesRepository =
        DataStoreLoanReminderPreferencesRepository(dataStore)

    @Provides
    @Singleton
    @DebtReminderDataStore
    fun provideDebtReminderPreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = applicationScope,
        produceFile = {
            context.preferencesDataStoreFile(SecurityContract.DEBT_REMINDER_PREFERENCES_FILE)
        },
    )

    @Provides
    @Singleton
    fun provideDebtReminderPreferencesRepository(
        @DebtReminderDataStore dataStore: DataStore<Preferences>,
    ): DebtReminderPreferencesRepository =
        DataStoreDebtReminderPreferencesRepository(dataStore)

    @Provides
    @Singleton
    fun provideMonotonicClock(): MonotonicClock = MonotonicClock {
        SystemClock.elapsedRealtime()
    }

    @Provides
    @Singleton
    fun provideAppLockStateMachine(
        clock: MonotonicClock,
    ): AppLockStateMachine = AppLockStateMachine(clock)

    @Provides
    @Singleton
    fun provideBiometricAuthenticator(
        @ApplicationContext context: Context,
    ): BiometricAuthenticator = AndroidBiometricAuthenticator(context)

    @Provides
    @Singleton
    fun provideSecretKeyProvider(): SecretKeyProvider = AndroidKeystoreSecretKeyProvider()

    @Provides
    @Singleton
    fun provideSecretCipher(
        keyProvider: SecretKeyProvider,
    ): SecretCipher = KeystoreSecretCipher(keyProvider)

    @Provides
    @Singleton
    @AiConfigDataStore
    fun provideAiConfigPreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = applicationScope,
        produceFile = {
            context.preferencesDataStoreFile(SecurityContract.AI_CONFIG_PREFERENCES_FILE)
        },
    )

    @Provides
    @Singleton
    fun provideAiConfigPreferencesRepository(
        @AiConfigDataStore dataStore: DataStore<Preferences>,
        secretCipher: SecretCipher,
    ): AiConfigPreferencesRepository = DataStoreAiConfigPreferencesRepository(
        dataStore = dataStore,
        secretCipher = secretCipher,
    )

    @Provides
    @Singleton
    @BudgetRecurringDataStore
    fun provideBudgetAndRecurringPreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = applicationScope,
        produceFile = {
            context.preferencesDataStoreFile(SecurityContract.BUDGET_RECURRING_PREFERENCES_FILE)
        },
    )

    @Provides
    @Singleton
    fun provideBudgetAndRecurringPreferencesRepository(
        @BudgetRecurringDataStore dataStore: DataStore<Preferences>,
    ): BudgetAndRecurringPreferencesRepository =
        DataStoreBudgetAndRecurringPreferencesRepository(dataStore)

    @Provides
    @Singleton
    @WebDavSyncDataStore
    fun provideWebDavSyncPreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = applicationScope,
        produceFile = {
            context.preferencesDataStoreFile(SecurityContract.WEBDAV_SYNC_PREFERENCES_FILE)
        },
    )

    @Provides
    @Singleton
    fun provideWebDavSyncPreferencesRepository(
        @WebDavSyncDataStore dataStore: DataStore<Preferences>,
        secretCipher: SecretCipher,
    ): WebDavSyncPreferencesRepository = DataStoreWebDavSyncPreferencesRepository(
        dataStore = dataStore,
        secretCipher = secretCipher,
    )

    @Provides
    @Singleton
    fun provideWebDavBackupCrypto(): WebDavBackupCrypto = WebDavBackupCrypto()
}
