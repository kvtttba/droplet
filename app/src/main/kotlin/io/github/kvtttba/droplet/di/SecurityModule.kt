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
import io.github.kvtttba.droplet.core.security.AndroidBiometricAuthenticator
import io.github.kvtttba.droplet.core.security.AndroidKeystoreSecretKeyProvider
import io.github.kvtttba.droplet.core.security.AppLockStateMachine
import io.github.kvtttba.droplet.core.security.BiometricAuthenticator
import io.github.kvtttba.droplet.core.security.DataStorePrivacyPreferencesRepository
import io.github.kvtttba.droplet.core.security.KeystoreSecretCipher
import io.github.kvtttba.droplet.core.security.MonotonicClock
import io.github.kvtttba.droplet.core.security.PrivacyPreferencesRepository
import io.github.kvtttba.droplet.core.security.SecretCipher
import io.github.kvtttba.droplet.core.security.SecretKeyProvider
import io.github.kvtttba.droplet.core.security.SecurityContract
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

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
}
