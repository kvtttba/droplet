package io.github.kvtttba.droplet.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.kvtttba.droplet.BuildConfig
import io.github.kvtttba.droplet.core.database.DropletDatabase
import io.github.kvtttba.droplet.core.database.snapshot.DatabaseSnapshotExporter
import io.github.kvtttba.droplet.core.network.DropletHttpClientFactory
import io.github.kvtttba.droplet.core.network.NetworkClock
import io.github.kvtttba.droplet.core.security.WebDavBackupCrypto
import io.github.kvtttba.droplet.core.security.WebDavSyncPreferencesRepository
import io.github.kvtttba.droplet.core.sync.BackupArchiveCodec
import io.github.kvtttba.droplet.core.sync.DefaultWebDavBackupOrchestrator
import io.github.kvtttba.droplet.core.sync.OkHttpWebDavClient
import io.github.kvtttba.droplet.core.sync.SafeDatabaseRestoreManager
import io.github.kvtttba.droplet.core.sync.WebDavBackupOrchestrator
import io.github.kvtttba.droplet.core.sync.WebDavClient
import io.github.kvtttba.droplet.worker.WebDavSyncScheduler
import javax.inject.Singleton

/**
 * Hilt entry point used by [io.github.kvtttba.droplet.worker.WebDavBackupWorker] and
 * compile-time binding verification for M5.3 WebDAV encrypted backup & rollback restore.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WebDavSyncEntryPoint {
    fun webDavSyncPreferencesRepository(): WebDavSyncPreferencesRepository
    fun webDavClient(): WebDavClient
    fun webDavBackupOrchestrator(): WebDavBackupOrchestrator
    fun webDavSyncScheduler(): WebDavSyncScheduler
}

@Module
@InstallIn(SingletonComponent::class)
object WebDavSyncModule {

    @Provides
    @Singleton
    fun provideDatabaseSnapshotExporter(): DatabaseSnapshotExporter = DatabaseSnapshotExporter()

    @Provides
    @Singleton
    fun provideBackupArchiveCodec(
        snapshotExporter: DatabaseSnapshotExporter,
        backupCrypto: WebDavBackupCrypto,
    ): BackupArchiveCodec = BackupArchiveCodec(
        snapshotExporter = snapshotExporter,
        crypto = backupCrypto,
    )

    @Provides
    @Singleton
    fun provideWebDavClient(
        httpClientFactory: DropletHttpClientFactory,
    ): WebDavClient = OkHttpWebDavClient(
        httpClientFactory = httpClientFactory,
    )

    @Provides
    @Singleton
    fun provideSafeDatabaseRestoreManager(
        @ApplicationContext context: Context,
        database: DropletDatabase,
        archiveCodec: BackupArchiveCodec,
        clock: NetworkClock,
    ): SafeDatabaseRestoreManager = SafeDatabaseRestoreManager(
        database = database,
        filesRootDir = context.filesDir,
        archiveCodec = archiveCodec,
        appVersion = BuildConfig.VERSION_NAME,
        clock = { clock.nowEpochMs() },
    )

    @Provides
    @Singleton
    fun provideWebDavBackupOrchestrator(
        @ApplicationContext context: Context,
        database: DropletDatabase,
        preferencesRepository: WebDavSyncPreferencesRepository,
        webDavClient: WebDavClient,
        archiveCodec: BackupArchiveCodec,
        restoreManager: SafeDatabaseRestoreManager,
        clock: NetworkClock,
    ): WebDavBackupOrchestrator = DefaultWebDavBackupOrchestrator(
        database = database,
        filesRootDir = context.filesDir,
        preferencesRepository = preferencesRepository,
        webDavClient = webDavClient,
        archiveCodec = archiveCodec,
        restoreManager = restoreManager,
        appVersion = BuildConfig.VERSION_NAME,
        clock = { clock.nowEpochMs() },
    )

    @Provides
    @Singleton
    fun provideWebDavSyncScheduler(
        workManager: WorkManager,
    ): WebDavSyncScheduler = WebDavSyncScheduler(workManager)
}
