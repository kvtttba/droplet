package io.github.kvtttba.droplet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.security.WebDavConfigSaveInput
import io.github.kvtttba.droplet.core.security.WebDavSecretUpdateAction
import io.github.kvtttba.droplet.core.security.WebDavSyncPreferences
import io.github.kvtttba.droplet.core.security.WebDavSyncPreferencesRepository
import io.github.kvtttba.droplet.core.security.WebDavUrlValidator
import io.github.kvtttba.droplet.core.sync.BackupTrigger
import io.github.kvtttba.droplet.core.sync.LocalRollbackSnapshot
import io.github.kvtttba.droplet.core.sync.RemoteBackupSnapshot
import io.github.kvtttba.droplet.core.sync.RestoreOutcome
import io.github.kvtttba.droplet.core.sync.WebDavBackupOrchestrator
import io.github.kvtttba.droplet.core.sync.WebDavConnectionParams
import io.github.kvtttba.droplet.core.sync.WebDavConnectionTestResult
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebDavSyncUiState(
    val preferences: WebDavSyncPreferences = WebDavSyncPreferences(),
    val serverUrlInput: String = "",
    val usernameInput: String = "",
    val passwordInput: String = "",
    val passwordVisible: Boolean = false,
    val remoteDirectoryInput: String = WebDavUrlValidator.DEFAULT_REMOTE_DIRECTORY,
    val passphraseInput: String = "",
    val passphraseVisible: Boolean = false,
    val isServerDraftDirty: Boolean = false,
    val serverValidationError: String? = null,
    val passphraseValidationError: String? = null,
    val isSavingConfig: Boolean = false,
    val isSavingPassphrase: Boolean = false,
    val isTestingConnection: Boolean = false,
    val connectionTestResult: WebDavConnectionTestResult? = null,
    val isBackingUp: Boolean = false,
    val isLoadingRemoteSnapshots: Boolean = false,
    val remoteSnapshots: List<RemoteBackupSnapshot> = emptyList(),
    val remoteSnapshotsError: String? = null,
    val isCreatingLocalSnapshot: Boolean = false,
    val localRollbackSnapshots: List<LocalRollbackSnapshot> = emptyList(),
    val isRestoring: Boolean = false,
    val lastRestoreOutcome: RestoreOutcome? = null,
    val userMessage: String? = null,
) {
    override fun toString(): String =
        "WebDavSyncUiState(serverUrl=$serverUrlInput, username=$usernameInput, " +
            "remoteDirectory=$remoteDirectoryInput, hasPasswordInput=${passwordInput.isNotEmpty()}, " +
            "hasPassphraseInput=${passphraseInput.isNotEmpty()}, isServerDraftDirty=$isServerDraftDirty, " +
            "remoteCount=${remoteSnapshots.size}, localRollbackCount=${localRollbackSnapshots.size}, " +
            "isBackingUp=$isBackingUp, isRestoring=$isRestoring)"
}

private data class WebDavFormState(
    val serverUrlInput: String = "",
    val usernameInput: String = "",
    val passwordInput: String = "",
    val passwordVisible: Boolean = false,
    val remoteDirectoryInput: String = WebDavUrlValidator.DEFAULT_REMOTE_DIRECTORY,
    val passphraseInput: String = "",
    val passphraseVisible: Boolean = false,
    val isServerDraftDirty: Boolean = false,
    val serverValidationError: String? = null,
    val passphraseValidationError: String? = null,
    val isSavingConfig: Boolean = false,
    val isSavingPassphrase: Boolean = false,
    val isTestingConnection: Boolean = false,
    val connectionTestResult: WebDavConnectionTestResult? = null,
)

private data class WebDavOperationsState(
    val isBackingUp: Boolean = false,
    val isLoadingRemoteSnapshots: Boolean = false,
    val remoteSnapshots: List<RemoteBackupSnapshot> = emptyList(),
    val remoteSnapshotsError: String? = null,
    val isCreatingLocalSnapshot: Boolean = false,
    val localRollbackSnapshots: List<LocalRollbackSnapshot> = emptyList(),
    val isRestoring: Boolean = false,
    val lastRestoreOutcome: RestoreOutcome? = null,
    val userMessage: String? = null,
)

@HiltViewModel
class WebDavSyncViewModel @Inject constructor(
    private val preferencesRepository: WebDavSyncPreferencesRepository,
    private val orchestrator: WebDavBackupOrchestrator,
) : ViewModel() {

    private val formFlow = MutableStateFlow(WebDavFormState())
    private val opsFlow = MutableStateFlow(WebDavOperationsState())

    init {
        viewModelScope.launch {
            preferencesRepository.preferences.collect { saved ->
                formFlow.update { current ->
                    if (current.isServerDraftDirty) {
                        current
                    } else {
                        current.copy(
                            serverUrlInput = saved.serverUrl,
                            usernameInput = saved.username,
                            remoteDirectoryInput = saved.remoteDirectory,
                        )
                    }
                }
            }
        }
        refreshLocalRollbackSnapshots()
    }

    val uiState: StateFlow<WebDavSyncUiState> = combine(
        preferencesRepository.preferences,
        formFlow,
        opsFlow,
    ) { prefs, form, ops ->
        WebDavSyncUiState(
            preferences = prefs,
            serverUrlInput = form.serverUrlInput,
            usernameInput = form.usernameInput,
            passwordInput = form.passwordInput,
            passwordVisible = form.passwordVisible,
            remoteDirectoryInput = form.remoteDirectoryInput,
            passphraseInput = form.passphraseInput,
            passphraseVisible = form.passphraseVisible,
            isServerDraftDirty = form.isServerDraftDirty,
            serverValidationError = form.serverValidationError,
            passphraseValidationError = form.passphraseValidationError,
            isSavingConfig = form.isSavingConfig,
            isSavingPassphrase = form.isSavingPassphrase,
            isTestingConnection = form.isTestingConnection,
            connectionTestResult = form.connectionTestResult,
            isBackingUp = ops.isBackingUp,
            isLoadingRemoteSnapshots = ops.isLoadingRemoteSnapshots,
            remoteSnapshots = ops.remoteSnapshots,
            remoteSnapshotsError = ops.remoteSnapshotsError,
            isCreatingLocalSnapshot = ops.isCreatingLocalSnapshot,
            localRollbackSnapshots = ops.localRollbackSnapshots,
            isRestoring = ops.isRestoring,
            lastRestoreOutcome = ops.lastRestoreOutcome,
            userMessage = ops.userMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = WebDavSyncUiState(),
    )

    fun onServerUrlChange(value: String) {
        formFlow.update {
            it.copy(
                serverUrlInput = value,
                isServerDraftDirty = true,
                serverValidationError = null,
            )
        }
    }

    fun onUsernameChange(value: String) {
        formFlow.update {
            it.copy(
                usernameInput = value,
                isServerDraftDirty = true,
                serverValidationError = null,
            )
        }
    }

    fun onPasswordInputChange(value: String) {
        formFlow.update {
            it.copy(
                passwordInput = value,
                isServerDraftDirty = true,
                serverValidationError = null,
            )
        }
    }

    fun onTogglePasswordVisible() {
        formFlow.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun onRemoteDirectoryChange(value: String) {
        formFlow.update {
            it.copy(
                remoteDirectoryInput = value,
                isServerDraftDirty = true,
                serverValidationError = null,
            )
        }
    }

    fun saveServerConfig() {
        val form = formFlow.value
        if (form.isSavingConfig) return

        val urlResult = WebDavUrlValidator.validateServerUrl(form.serverUrlInput)
        if (urlResult.isFailure) {
            formFlow.update {
                it.copy(serverValidationError = urlResult.exceptionOrNull()?.message ?: "WebDAV 地址无效")
            }
            return
        }
        val trimmedUsername = form.usernameInput.trim()
        if (trimmedUsername.isEmpty()) {
            formFlow.update { it.copy(serverValidationError = "请输入 WebDAV 用户名") }
            return
        }
        val dirResult = WebDavUrlValidator.normalizeRemoteDirectory(form.remoteDirectoryInput)
        if (dirResult.isFailure) {
            formFlow.update {
                it.copy(serverValidationError = dirResult.exceptionOrNull()?.message ?: "远程目录路径无效")
            }
            return
        }

        val savedPrefs = uiState.value.preferences
        val hasEffectivePassword = form.passwordInput.isNotBlank() ||
            (savedPrefs.hasPassword && !savedPrefs.isPasswordCorrupted)
        if (!hasEffectivePassword) {
            formFlow.update {
                it.copy(serverValidationError = "请输入 WebDAV 密码或应用专用密码（将经 Keystore 本地加密保存）")
            }
            return
        }

        val passwordAction = if (form.passwordInput.isNotBlank()) {
            WebDavSecretUpdateAction.Replace(form.passwordInput)
        } else {
            WebDavSecretUpdateAction.KeepExisting
        }

        viewModelScope.launch {
            formFlow.update { it.copy(isSavingConfig = true, serverValidationError = null) }
            runCatching {
                preferencesRepository.saveConnectionConfig(
                    WebDavConfigSaveInput(
                        serverUrl = urlResult.getOrThrow(),
                        username = trimmedUsername,
                        remoteDirectory = dirResult.getOrThrow(),
                        passwordAction = passwordAction,
                    ),
                )
            }.onSuccess {
                val latest = preferencesRepository.currentPreferences()
                formFlow.update {
                    it.copy(
                        serverUrlInput = latest.serverUrl,
                        usernameInput = latest.username,
                        passwordInput = "",
                        passwordVisible = false,
                        remoteDirectoryInput = latest.remoteDirectory,
                        isServerDraftDirty = false,
                        serverValidationError = null,
                        isSavingConfig = false,
                    )
                }
                setUserMessage("已加密保存 WebDAV 服务器配置")
            }.onFailure { err ->
                formFlow.update {
                    it.copy(
                        isSavingConfig = false,
                        serverValidationError = err.message ?: "保存配置失败，请重试",
                    )
                }
            }
        }
    }

    fun clearSavedPassword() {
        viewModelScope.launch {
            preferencesRepository.clearPassword()
            formFlow.update {
                it.copy(
                    passwordInput = "",
                    passwordVisible = false,
                    serverValidationError = null,
                    connectionTestResult = null,
                )
            }
            setUserMessage("已清除本地加密保存的 WebDAV 密码")
        }
    }

    fun testConnection() {
        val form = formFlow.value
        if (form.isTestingConnection) return

        viewModelScope.launch {
            formFlow.update { it.copy(isTestingConnection = true, serverValidationError = null) }
            val urlResult = WebDavUrlValidator.validateServerUrl(form.serverUrlInput)
            if (urlResult.isFailure) {
                formFlow.update {
                    it.copy(
                        isTestingConnection = false,
                        serverValidationError = urlResult.exceptionOrNull()?.message ?: "WebDAV 地址无效",
                    )
                }
                return@launch
            }
            val dirResult = WebDavUrlValidator.normalizeRemoteDirectory(form.remoteDirectoryInput)
            if (dirResult.isFailure) {
                formFlow.update {
                    it.copy(
                        isTestingConnection = false,
                        serverValidationError = dirResult.exceptionOrNull()?.message ?: "远程目录路径无效",
                    )
                }
                return@launch
            }
            val username = form.usernameInput.trim()
            if (username.isEmpty()) {
                formFlow.update {
                    it.copy(
                        isTestingConnection = false,
                        serverValidationError = "请先输入 WebDAV 用户名",
                    )
                }
                return@launch
            }
            val password = form.passwordInput.takeIf { it.isNotBlank() }
                ?: preferencesRepository.decryptPassword()
            if (password.isNullOrBlank()) {
                formFlow.update {
                    it.copy(
                        isTestingConnection = false,
                        serverValidationError = "请先输入或保存 WebDAV 密码后再测试连接",
                    )
                }
                return@launch
            }

            val result = orchestrator.testConnection(
                WebDavConnectionParams(
                    serverUrl = urlResult.getOrThrow(),
                    username = username,
                    password = password,
                    remoteDirectory = dirResult.getOrThrow(),
                ),
            )
            formFlow.update {
                it.copy(
                    isTestingConnection = false,
                    connectionTestResult = result,
                )
            }
            setUserMessage(
                if (result.success) {
                    "WebDAV 连接测试通过（${result.latencyMs} ms）"
                } else {
                    "连接测试失败：${result.message}"
                },
            )
        }
    }

    fun onToggleEncryptionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setEncryptionEnabled(enabled)
            setUserMessage(
                if (enabled) {
                    "已开启 DRP1 端到端加密备份"
                } else {
                    "已关闭端到端加密，后续备份将以明文 ZIP 上传"
                },
            )
        }
    }

    fun onPassphraseInputChange(value: String) {
        formFlow.update {
            it.copy(
                passphraseInput = value,
                passphraseValidationError = null,
            )
        }
    }

    fun onTogglePassphraseVisible() {
        formFlow.update { it.copy(passphraseVisible = !it.passphraseVisible) }
    }

    fun saveBackupPassphrase() {
        val form = formFlow.value
        if (form.isSavingPassphrase) return
        val candidate = form.passphraseInput
        if (candidate.isBlank()) {
            formFlow.update { it.copy(passphraseValidationError = "请输入端到端备份加密口令") }
            return
        }
        if (candidate.length < MIN_PASSPHRASE_LENGTH) {
            formFlow.update {
                it.copy(passphraseValidationError = "为了保障账本安全，加密口令至少需要 $MIN_PASSPHRASE_LENGTH 个字符")
            }
            return
        }

        viewModelScope.launch {
            formFlow.update { it.copy(isSavingPassphrase = true, passphraseValidationError = null) }
            runCatching {
                preferencesRepository.saveBackupPassphrase(
                    WebDavSecretUpdateAction.Replace(candidate),
                )
            }.onSuccess {
                formFlow.update {
                    it.copy(
                        passphraseInput = "",
                        passphraseVisible = false,
                        passphraseValidationError = null,
                        isSavingPassphrase = false,
                    )
                }
                setUserMessage("已通过 Android Keystore 加密保存备份口令")
            }.onFailure { err ->
                formFlow.update {
                    it.copy(
                        isSavingPassphrase = false,
                        passphraseValidationError = err.message ?: "保存加密口令失败",
                    )
                }
            }
        }
    }

    fun clearBackupPassphrase() {
        viewModelScope.launch {
            preferencesRepository.clearBackupPassphrase()
            formFlow.update {
                it.copy(
                    passphraseInput = "",
                    passphraseVisible = false,
                    passphraseValidationError = null,
                )
            }
            setUserMessage("已清除本机保存的备份加密口令")
        }
    }

    fun onToggleAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoSyncEnabled(enabled)
            setUserMessage(
                if (enabled) "已开启变更后 30 秒防抖自动上传" else "已关闭变更后自动上传",
            )
        }
    }

    fun onToggleDailyBackup(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDailyBackupEnabled(enabled)
            setUserMessage(
                if (enabled) "已开启每日定时备份" else "已关闭每日定时备份",
            )
        }
    }

    fun onToggleWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setWifiOnly(enabled)
            setUserMessage(
                if (enabled) "自动与定时备份仅在 Wi-Fi 下执行" else "允许在移动网络与 Wi-Fi 下执行备份",
            )
        }
    }

    fun onToggleSyncAttachments(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setSyncAttachments(enabled)
            setUserMessage(
                if (enabled) "后续备份将同步包含票据原图附件" else "已改为仅备份账本数据库（不含票据原图）",
            )
        }
    }

    fun onSelectRetentionCount(count: Int) {
        viewModelScope.launch {
            preferencesRepository.setRetentionCount(count)
            setUserMessage("远端历史快照保留策略已更新为最近 $count 份")
        }
    }

    fun backupNow() {
        if (opsFlow.value.isBackingUp || opsFlow.value.isRestoring) return
        viewModelScope.launch {
            opsFlow.update { it.copy(isBackingUp = true) }
            val result = orchestrator.backupNow(BackupTrigger.MANUAL)
            opsFlow.update { it.copy(isBackingUp = false, userMessage = result.message) }
            if (result.success) {
                refreshRemoteSnapshotsInternal(silentOnError = true)
            }
        }
    }

    fun refreshRemoteSnapshots() {
        if (opsFlow.value.isLoadingRemoteSnapshots) return
        viewModelScope.launch {
            refreshRemoteSnapshotsInternal(silentOnError = false)
        }
    }

    fun restoreRemoteSnapshot(fileName: String, passphraseOverride: String? = null) {
        if (opsFlow.value.isRestoring || opsFlow.value.isBackingUp) return
        viewModelScope.launch {
            opsFlow.update { it.copy(isRestoring = true) }
            val outcome = orchestrator.restoreRemoteSnapshot(
                fileName = fileName,
                passphraseOverride = passphraseOverride?.takeIf { it.isNotBlank() },
            )
            val updatedRollbacks = orchestrator.listLocalRollbackSnapshots()
            opsFlow.update {
                it.copy(
                    isRestoring = false,
                    lastRestoreOutcome = outcome,
                    localRollbackSnapshots = updatedRollbacks,
                    userMessage = outcome.message,
                )
            }
        }
    }

    fun deleteRemoteSnapshot(fileName: String) {
        viewModelScope.launch {
            val result = orchestrator.deleteRemoteSnapshot(fileName)
            result.onSuccess {
                opsFlow.update { state ->
                    state.copy(
                        remoteSnapshots = state.remoteSnapshots.filterNot { it.fileName == fileName },
                        userMessage = "已删除远端快照 $fileName",
                    )
                }
            }.onFailure { err ->
                setUserMessage(err.message ?: "删除远端快照失败")
            }
        }
    }

    fun createLocalSafetySnapshot() {
        if (opsFlow.value.isCreatingLocalSnapshot || opsFlow.value.isRestoring) return
        viewModelScope.launch {
            opsFlow.update { it.copy(isCreatingLocalSnapshot = true) }
            val result = orchestrator.createLocalSafetySnapshot()
            val updatedRollbacks = orchestrator.listLocalRollbackSnapshots()
            result.onSuccess { snapshot ->
                opsFlow.update {
                    it.copy(
                        isCreatingLocalSnapshot = false,
                        localRollbackSnapshots = updatedRollbacks,
                        userMessage = "已创建本地安全快照：${snapshot.fileName}",
                    )
                }
            }.onFailure { err ->
                opsFlow.update {
                    it.copy(
                        isCreatingLocalSnapshot = false,
                        localRollbackSnapshots = updatedRollbacks,
                        userMessage = err.message ?: "创建本地安全快照失败",
                    )
                }
            }
        }
    }

    fun restoreLocalRollbackSnapshot(fileName: String) {
        if (opsFlow.value.isRestoring || opsFlow.value.isBackingUp) return
        viewModelScope.launch {
            opsFlow.update { it.copy(isRestoring = true) }
            val outcome = orchestrator.restoreLocalRollbackSnapshot(fileName)
            val updatedRollbacks = orchestrator.listLocalRollbackSnapshots()
            opsFlow.update {
                it.copy(
                    isRestoring = false,
                    lastRestoreOutcome = outcome,
                    localRollbackSnapshots = updatedRollbacks,
                    userMessage = outcome.message,
                )
            }
        }
    }

    fun deleteLocalRollbackSnapshot(fileName: String) {
        viewModelScope.launch {
            val deleted = orchestrator.deleteLocalRollbackSnapshot(fileName)
            val updatedRollbacks = orchestrator.listLocalRollbackSnapshots()
            opsFlow.update {
                it.copy(
                    localRollbackSnapshots = updatedRollbacks,
                    userMessage = if (deleted) "已删除本地快照 $fileName" else "未找到该本地快照",
                )
            }
        }
    }

    fun refreshLocalRollbackSnapshots() {
        viewModelScope.launch {
            val list = runCatching { orchestrator.listLocalRollbackSnapshots() }.getOrDefault(emptyList())
            opsFlow.update { it.copy(localRollbackSnapshots = list) }
        }
    }

    fun clearUserMessage() {
        opsFlow.update { it.copy(userMessage = null) }
    }

    private suspend fun refreshRemoteSnapshotsInternal(silentOnError: Boolean) {
        opsFlow.update { it.copy(isLoadingRemoteSnapshots = true, remoteSnapshotsError = null) }
        val result = orchestrator.listRemoteSnapshots()
        result.onSuccess { snapshots ->
            opsFlow.update {
                it.copy(
                    isLoadingRemoteSnapshots = false,
                    remoteSnapshots = snapshots,
                    remoteSnapshotsError = null,
                    userMessage = if (!silentOnError) "已加载 ${snapshots.size} 份远端历史快照" else it.userMessage,
                )
            }
        }.onFailure { err ->
            val msg = err.message ?: "读取远端快照列表失败"
            opsFlow.update {
                it.copy(
                    isLoadingRemoteSnapshots = false,
                    remoteSnapshotsError = msg,
                    userMessage = if (!silentOnError) msg else it.userMessage,
                )
            }
        }
    }

    private fun setUserMessage(message: String) {
        opsFlow.update { it.copy(userMessage = message) }
    }

    companion object {
        const val MIN_PASSPHRASE_LENGTH: Int = 6
    }
}
