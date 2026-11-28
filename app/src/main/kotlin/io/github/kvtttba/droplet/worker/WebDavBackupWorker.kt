package io.github.kvtttba.droplet.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import io.github.kvtttba.droplet.core.security.WebDavSyncPreferencesRepository
import io.github.kvtttba.droplet.core.sync.BackupTrigger
import io.github.kvtttba.droplet.core.sync.WebDavBackupOrchestrator
import io.github.kvtttba.droplet.di.WebDavSyncEntryPoint
import kotlinx.coroutines.CancellationException

internal data class WebDavBackupWorkerDependencies(
    val preferencesRepository: WebDavSyncPreferencesRepository,
    val orchestrator: WebDavBackupOrchestrator,
)

/**
 * WorkManager worker executing either:
 * - debounced auto-backup (30s after ledger writes), or
 * - daily periodic encrypted WebDAV backup.
 *
 * Resolves dependencies via Hilt [EntryPointAccessors] against [WebDavSyncEntryPoint].
 */
class WebDavBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val deps = dependenciesResolver(applicationContext)
        val trigger = parseTrigger(inputData.getString(KEY_TRIGGER))

        return try {
            val prefs = deps.preferencesRepository.currentPreferences()
            val isEnabledForTrigger = when (trigger) {
                BackupTrigger.DEBOUNCED_AUTO -> prefs.autoSyncEnabled
                BackupTrigger.DAILY_PERIODIC -> prefs.dailyBackupEnabled
                BackupTrigger.MANUAL -> true
            }
            if (!isEnabledForTrigger || !prefs.isReadyForBackup) {
                return Result.success(
                    workDataOf(
                        KEY_STATUS to STATUS_SKIPPED,
                        KEY_TRIGGER to trigger.name,
                    ),
                )
            }

            val execution = deps.orchestrator.backupNow(trigger)
            if (execution.success) {
                Result.success(
                    workDataOf(
                        KEY_STATUS to STATUS_UPLOADED,
                        KEY_TRIGGER to trigger.name,
                        KEY_FILE_NAME to execution.fileName.orEmpty(),
                        KEY_PRUNED_COUNT to execution.prunedFileNames.size,
                    ),
                )
            } else if (execution.isTransientFailure && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        KEY_STATUS to STATUS_FAILED,
                        KEY_TRIGGER to trigger.name,
                        KEY_MESSAGE to execution.message,
                    ),
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        KEY_STATUS to STATUS_FAILED,
                        KEY_TRIGGER to trigger.name,
                    ),
                )
            }
        }
    }

    private fun parseTrigger(raw: String?): BackupTrigger =
        BackupTrigger.entries.firstOrNull { it.name == raw } ?: BackupTrigger.DEBOUNCED_AUTO

    companion object {
        const val MAX_RETRY_ATTEMPTS: Int = 3
        const val KEY_TRIGGER: String = "webdav_backup_trigger"
        const val KEY_STATUS: String = "webdav_backup_status"
        const val KEY_FILE_NAME: String = "webdav_backup_file_name"
        const val KEY_PRUNED_COUNT: String = "webdav_backup_pruned_count"
        const val KEY_MESSAGE: String = "webdav_backup_message"

        const val STATUS_SKIPPED: String = "SKIPPED"
        const val STATUS_UPLOADED: String = "UPLOADED"
        const val STATUS_FAILED: String = "FAILED"

        internal var dependenciesResolver: (Context) -> WebDavBackupWorkerDependencies =
            ::defaultDependenciesResolver

        private fun defaultDependenciesResolver(context: Context): WebDavBackupWorkerDependencies {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WebDavSyncEntryPoint::class.java,
            )
            return WebDavBackupWorkerDependencies(
                preferencesRepository = entryPoint.webDavSyncPreferencesRepository(),
                orchestrator = entryPoint.webDavBackupOrchestrator(),
            )
        }

        internal fun resetDependenciesResolver() {
            dependenciesResolver = ::defaultDependenciesResolver
        }
    }
}
