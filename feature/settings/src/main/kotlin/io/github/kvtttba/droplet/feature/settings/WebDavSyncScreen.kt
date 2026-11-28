package io.github.kvtttba.droplet.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.security.WebDavUrlValidator
import io.github.kvtttba.droplet.core.sync.DefaultWebDavBackupOrchestrator
import io.github.kvtttba.droplet.core.sync.LocalRollbackSnapshot
import io.github.kvtttba.droplet.core.sync.RemoteBackupSnapshot
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun WebDavSyncRoute(
    onNavigateBack: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: WebDavSyncViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    WebDavSyncScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onServerUrlChange = viewModel::onServerUrlChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordInputChange = viewModel::onPasswordInputChange,
        onTogglePasswordVisible = viewModel::onTogglePasswordVisible,
        onRemoteDirectoryChange = viewModel::onRemoteDirectoryChange,
        onSaveServerConfig = viewModel::saveServerConfig,
        onClearSavedPassword = viewModel::clearSavedPassword,
        onTestConnection = viewModel::testConnection,
        onToggleEncryptionEnabled = viewModel::onToggleEncryptionEnabled,
        onPassphraseInputChange = viewModel::onPassphraseInputChange,
        onTogglePassphraseVisible = viewModel::onTogglePassphraseVisible,
        onSavePassphrase = viewModel::saveBackupPassphrase,
        onClearSavedPassphrase = viewModel::clearBackupPassphrase,
        onToggleAutoSync = viewModel::onToggleAutoSync,
        onToggleDailyBackup = viewModel::onToggleDailyBackup,
        onToggleWifiOnly = viewModel::onToggleWifiOnly,
        onToggleSyncAttachments = viewModel::onToggleSyncAttachments,
        onSelectRetentionCount = viewModel::onSelectRetentionCount,
        onBackupNow = viewModel::backupNow,
        onRefreshRemoteSnapshots = viewModel::refreshRemoteSnapshots,
        onRestoreRemoteSnapshot = viewModel::restoreRemoteSnapshot,
        onDeleteRemoteSnapshot = viewModel::deleteRemoteSnapshot,
        onCreateLocalSafetySnapshot = viewModel::createLocalSafetySnapshot,
        onRestoreLocalRollbackSnapshot = viewModel::restoreLocalRollbackSnapshot,
        onDeleteLocalRollbackSnapshot = viewModel::deleteLocalRollbackSnapshot,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WebDavSyncScreen(
    uiState: WebDavSyncUiState,
    onNavigateBack: () -> Unit = {},
    onServerUrlChange: (String) -> Unit = {},
    onUsernameChange: (String) -> Unit = {},
    onPasswordInputChange: (String) -> Unit = {},
    onTogglePasswordVisible: () -> Unit = {},
    onRemoteDirectoryChange: (String) -> Unit = {},
    onSaveServerConfig: () -> Unit = {},
    onClearSavedPassword: () -> Unit = {},
    onTestConnection: () -> Unit = {},
    onToggleEncryptionEnabled: (Boolean) -> Unit = {},
    onPassphraseInputChange: (String) -> Unit = {},
    onTogglePassphraseVisible: () -> Unit = {},
    onSavePassphrase: () -> Unit = {},
    onClearSavedPassphrase: () -> Unit = {},
    onToggleAutoSync: (Boolean) -> Unit = {},
    onToggleDailyBackup: (Boolean) -> Unit = {},
    onToggleWifiOnly: (Boolean) -> Unit = {},
    onToggleSyncAttachments: (Boolean) -> Unit = {},
    onSelectRetentionCount: (Int) -> Unit = {},
    onBackupNow: () -> Unit = {},
    onRefreshRemoteSnapshots: () -> Unit = {},
    onRestoreRemoteSnapshot: (fileName: String, passphraseOverride: String?) -> Unit = { _, _ -> },
    onDeleteRemoteSnapshot: (String) -> Unit = {},
    onCreateLocalSafetySnapshot: () -> Unit = {},
    onRestoreLocalRollbackSnapshot: (String) -> Unit = {},
    onDeleteLocalRollbackSnapshot: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var pendingRestoreRemote by remember { mutableStateOf<RemoteBackupSnapshot?>(null) }
    var restorePassphraseOverride by remember { mutableStateOf("") }
    var pendingDeleteRemote by remember { mutableStateOf<RemoteBackupSnapshot?>(null) }
    var pendingRestoreLocal by remember { mutableStateOf<LocalRollbackSnapshot?>(null) }
    var pendingDeleteLocal by remember { mutableStateOf<LocalRollbackSnapshot?>(null) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_webdav_sync"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "WebDAV 加密备份与恢复",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("webdav_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("webdav_sync_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "webdav_hero_card") {
                WebDavHeroStatusCard(
                    uiState = uiState,
                    onBackupNow = onBackupNow,
                    onCreateLocalSafetySnapshot = onCreateLocalSafetySnapshot,
                )
            }

            item(key = "webdav_server_section") {
                DropletSectionHeader(title = "WebDAV 服务器配置")
            }

            item(key = "webdav_server_card") {
                WebDavServerConfigCard(
                    uiState = uiState,
                    onServerUrlChange = onServerUrlChange,
                    onUsernameChange = onUsernameChange,
                    onPasswordInputChange = onPasswordInputChange,
                    onTogglePasswordVisible = onTogglePasswordVisible,
                    onRemoteDirectoryChange = onRemoteDirectoryChange,
                    onSaveServerConfig = onSaveServerConfig,
                    onClearSavedPassword = onClearSavedPassword,
                    onTestConnection = onTestConnection,
                )
            }

            item(key = "webdav_encryption_section") {
                DropletSectionHeader(title = "端到端加密（DRP1）")
            }

            item(key = "webdav_encryption_card") {
                WebDavEncryptionCard(
                    uiState = uiState,
                    onToggleEncryptionEnabled = onToggleEncryptionEnabled,
                    onPassphraseInputChange = onPassphraseInputChange,
                    onTogglePassphraseVisible = onTogglePassphraseVisible,
                    onSavePassphrase = onSavePassphrase,
                    onClearSavedPassphrase = onClearSavedPassphrase,
                )
            }

            item(key = "webdav_policy_section") {
                DropletSectionHeader(title = "自动同步与快照保留策略")
            }

            item(key = "webdav_policy_card") {
                WebDavSyncPolicyCard(
                    uiState = uiState,
                    onToggleAutoSync = onToggleAutoSync,
                    onToggleDailyBackup = onToggleDailyBackup,
                    onToggleWifiOnly = onToggleWifiOnly,
                    onToggleSyncAttachments = onToggleSyncAttachments,
                    onSelectRetentionCount = onSelectRetentionCount,
                )
            }

            item(key = "webdav_remote_section") {
                DropletSectionHeader(title = "远端历史快照")
            }

            item(key = "webdav_remote_snapshots_card") {
                WebDavRemoteSnapshotsCard(
                    uiState = uiState,
                    onRefreshRemoteSnapshots = onRefreshRemoteSnapshots,
                    onRequestRestore = { snapshot ->
                        restorePassphraseOverride = ""
                        pendingRestoreRemote = snapshot
                    },
                    onRequestDelete = { snapshot ->
                        pendingDeleteRemote = snapshot
                    },
                )
            }

            item(key = "webdav_local_section") {
                DropletSectionHeader(title = "本机应急回滚快照")
            }

            item(key = "webdav_local_rollbacks_card") {
                LocalRollbackSnapshotsCard(
                    uiState = uiState,
                    onRequestRestore = { snapshot -> pendingRestoreLocal = snapshot },
                    onRequestDelete = { snapshot -> pendingDeleteLocal = snapshot },
                )
            }
        }
    }

    pendingRestoreRemote?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRestoreRemote = null },
            title = { Text("确认安全恢复远端快照") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "即将从远端快照 ${target.fileName} 完整替换本机账本。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "安全回滚保护：恢复前会自动在本机生成一份应急回滚快照，若解密、校验或替换任一环节异常将自动回滚，保证现有数据零损坏。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (target.isEncrypted) {
                        OutlinedTextField(
                            value = restorePassphraseOverride,
                            onValueChange = { restorePassphraseOverride = it },
                            label = { Text("解密口令（留空则复用本机已保存口令）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("webdav_restore_passphrase_override"),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fileName = target.fileName
                        val overridePass = restorePassphraseOverride.trim().ifEmpty { null }
                        pendingRestoreRemote = null
                        onRestoreRemoteSnapshot(fileName, overridePass)
                    },
                    modifier = Modifier.testTag("webdav_confirm_restore_remote"),
                ) {
                    Text("确认安全恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreRemote = null }) {
                    Text("取消")
                }
            },
        )
    }

    pendingDeleteRemote?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRemote = null },
            title = { Text("删除远端快照") },
            text = {
                Text("确定从 WebDAV 服务器删除快照 ${target.fileName} 吗？该操作不可撤销，不会影响本机当前账本。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fileName = target.fileName
                        pendingDeleteRemote = null
                        onDeleteRemoteSnapshot(fileName)
                    },
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRemote = null }) {
                    Text("取消")
                }
            },
        )
    }

    pendingRestoreLocal?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRestoreLocal = null },
            title = { Text("从本地应急快照回滚") },
            text = {
                Text(
                    "确定从本地快照 ${target.fileName} 恢复账本吗？恢复前系统会再次保存一份当前应急备份以便随时反悔。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fileName = target.fileName
                        pendingRestoreLocal = null
                        onRestoreLocalRollbackSnapshot(fileName)
                    },
                    modifier = Modifier.testTag("webdav_confirm_restore_local"),
                ) {
                    Text("立即回滚恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreLocal = null }) {
                    Text("取消")
                }
            },
        )
    }

    pendingDeleteLocal?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteLocal = null },
            title = { Text("删除本地快照") },
            text = {
                Text("确定删除本地回滚快照 ${target.fileName} 吗？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fileName = target.fileName
                        pendingDeleteLocal = null
                        onDeleteLocalRollbackSnapshot(fileName)
                    },
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteLocal = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebDavHeroStatusCard(
    uiState: WebDavSyncUiState,
    onBackupNow: () -> Unit,
    onCreateLocalSafetySnapshot: () -> Unit,
) {
    val prefs = uiState.preferences
    DropletHeroCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.testTag("webdav_hero_status_card"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DropletIcons.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (prefs.isReadyForBackup) {
                        "WebDAV 加密同步已就绪"
                    } else {
                        "端到端加密快照备份"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = prefs.formatSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier.testTag("webdav_hero_summary"),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DropletPillBadge(
                text = if (prefs.encryptionEnabled) "DRP1 · AES-256-GCM" else "明文 ZIP 模式",
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            DropletPillBadge(
                text = "WAL 一致性快照",
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            DropletPillBadge(
                text = "恢复前自动回滚保护",
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }

        val lastBackupLine = buildLastBackupLine(prefs)
        if (lastBackupLine != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = lastBackupLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("webdav_last_backup_status"),
                    )
                    if (!prefs.lastBackupMessage.isNullOrBlank()) {
                        Text(
                            text = prefs.lastBackupMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!prefs.lastBackupFileName.isNullOrBlank()) {
                        Text(
                            text = "最新远端文件：${prefs.lastBackupFileName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onBackupNow,
                enabled = prefs.isReadyForBackup && !uiState.isBackingUp && !uiState.isRestoring,
                modifier = Modifier
                    .weight(1f)
                    .testTag("webdav_backup_now_button"),
            ) {
                if (uiState.isBackingUp) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("备份上传中…")
                } else {
                    Text("立即备份至 WebDAV")
                }
            }
            FilledTonalButton(
                onClick = onCreateLocalSafetySnapshot,
                enabled = !uiState.isCreatingLocalSnapshot && !uiState.isRestoring,
                modifier = Modifier.testTag("webdav_create_local_snapshot_button"),
            ) {
                if (uiState.isCreatingLocalSnapshot) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("打包中…")
                } else {
                    Text("创建本地快照")
                }
            }
        }
    }
}

@Composable
private fun WebDavServerConfigCard(
    uiState: WebDavSyncUiState,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordInputChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onRemoteDirectoryChange: (String) -> Unit,
    onSaveServerConfig: () -> Unit,
    onClearSavedPassword: () -> Unit,
    onTestConnection: () -> Unit,
) {
    val prefs = uiState.preferences
    DropletCard {
        OutlinedTextField(
            value = uiState.serverUrlInput,
            onValueChange = onServerUrlChange,
            label = { Text("WebDAV 服务器地址") },
            placeholder = { Text("https://dav.jianguoyun.com/dav/") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("webdav_server_url_input"),
        )
        if (uiState.serverUrlInput.trim().startsWith("http://", ignoreCase = true)) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "提示：检测到非 HTTPS 明文连接，仅建议在受信任局域网使用，公网请优先使用 https://",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.usernameInput,
            onValueChange = onUsernameChange,
            label = { Text("用户名 / 账号") },
            placeholder = { Text("输入 WebDAV 登录用户名") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("webdav_username_input"),
        )

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.passwordInput,
            onValueChange = onPasswordInputChange,
            label = { Text("密码 / 应用专用密码") },
            placeholder = {
                Text(
                    if (prefs.hasPassword) {
                        "已通过 Keystore 加密保存（${prefs.passwordMasked}），留空保持不变"
                    } else {
                        "输入 WebDAV 密码（经 Android Keystore 本地加密）"
                    },
                )
            },
            singleLine = true,
            visualTransformation = if (uiState.passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onTogglePasswordVisible) {
                    Icon(
                        imageVector = if (uiState.passwordVisible) {
                            DropletIcons.VisibilityOff
                        } else {
                            DropletIcons.Visibility
                        },
                        contentDescription = if (uiState.passwordVisible) "隐藏密码" else "显示密码",
                    )
                }
            },
            supportingText = {
                when {
                    prefs.isPasswordCorrupted -> Text(
                        text = "本机密钥变更导致已存密码无法解密，请重新输入并保存",
                        color = MaterialTheme.colorScheme.error,
                    )
                    prefs.hasPassword -> Text(
                        text = "Keystore 已加密存储：${prefs.passwordMasked}",
                        modifier = Modifier.testTag("webdav_password_saved_badge"),
                    )
                    else -> Text("密码仅使用 Android Keystore 加密存本机，绝不输出到日志")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("webdav_password_input"),
        )

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.remoteDirectoryInput,
            onValueChange = onRemoteDirectoryChange,
            label = { Text("远程备份目录") },
            placeholder = { Text(WebDavUrlValidator.DEFAULT_REMOTE_DIRECTORY) },
            singleLine = true,
            supportingText = { Text("首次备份时将自动按级创建目录，默认 droplet-backups") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("webdav_remote_dir_input"),
        )

        if (uiState.serverValidationError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiState.serverValidationError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("webdav_server_validation_error"),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onSaveServerConfig,
                enabled = !uiState.isSavingConfig,
                modifier = Modifier
                    .weight(1f)
                    .testTag("webdav_save_config_button"),
            ) {
                Text(if (uiState.isSavingConfig) "保存中…" else "保存配置")
            }
            FilledTonalButton(
                onClick = onTestConnection,
                enabled = !uiState.isTestingConnection,
                modifier = Modifier
                    .weight(1f)
                    .testTag("webdav_test_connection_button"),
            ) {
                if (uiState.isTestingConnection) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("测试中…")
                } else {
                    Text("测试连接")
                }
            }
        }

        if (prefs.hasPassword || prefs.isPasswordCorrupted) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onClearSavedPassword,
                    modifier = Modifier.testTag("webdav_clear_password_button"),
                ) {
                    Text("清除已存密码")
                }
            }
        }

        uiState.connectionTestResult?.let { testResult ->
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("webdav_connection_test_result"),
                shape = RoundedCornerShape(12.dp),
                color = if (testResult.success) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = if (testResult.success) {
                            "连接成功 · ${testResult.latencyMs} ms"
                        } else {
                            "连接失败${testResult.httpStatusCode?.let { " · HTTP $it" }.orEmpty()}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (testResult.success) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    )
                    Text(
                        text = testResult.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testResult.success) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WebDavEncryptionCard(
    uiState: WebDavSyncUiState,
    onToggleEncryptionEnabled: (Boolean) -> Unit,
    onPassphraseInputChange: (String) -> Unit,
    onTogglePassphraseVisible: () -> Unit,
    onSavePassphrase: () -> Unit,
    onClearSavedPassphrase: () -> Unit,
) {
    val prefs = uiState.preferences
    DropletCard {
        WebDavSwitchRow(
            title = "启用端到端加密（强烈建议）",
            subtitle = "备份包在上传前经 PBKDF2-HMAC-SHA256 + AES-256-GCM 本地加密封装为 .drp",
            checked = prefs.encryptionEnabled,
            onCheckedChange = onToggleEncryptionEnabled,
            switchTestTag = "webdav_switch_encryption",
        )

        if (prefs.encryptionEnabled) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )
            OutlinedTextField(
                value = uiState.passphraseInput,
                onValueChange = onPassphraseInputChange,
                label = { Text("独立备份加密口令（至少 6 位）") },
                placeholder = {
                    Text(
                        if (prefs.hasPassphrase) {
                            "已加密保存（${prefs.passphraseMasked}），输入新口令可更换"
                        } else {
                            "输入独立于 WebDAV 密码的加密口令"
                        },
                    )
                },
                singleLine = true,
                visualTransformation = if (uiState.passphraseVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = onTogglePassphraseVisible) {
                        Icon(
                            imageVector = if (uiState.passphraseVisible) {
                                DropletIcons.VisibilityOff
                            } else {
                                DropletIcons.Visibility
                            },
                            contentDescription = if (uiState.passphraseVisible) "隐藏口令" else "显示口令",
                        )
                    }
                },
                supportingText = {
                    when {
                        prefs.isPassphraseCorrupted -> Text(
                            text = "本机密钥变更导致已存口令无法解密，请重新设置",
                            color = MaterialTheme.colorScheme.error,
                        )
                        prefs.hasPassphrase -> Text(
                            text = "Keystore 已加密存储：${prefs.passphraseMasked}",
                            modifier = Modifier.testTag("webdav_passphrase_saved_badge"),
                        )
                        else -> Text("换机恢复 .drp 快照时需要输入该口令")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("webdav_passphrase_input"),
            )

            if (uiState.passphraseValidationError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = uiState.passphraseValidationError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onSavePassphrase,
                    enabled = !uiState.isSavingPassphrase,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("webdav_save_passphrase_button"),
                ) {
                    Text(if (uiState.isSavingPassphrase) "保存中…" else "保存加密口令")
                }
                if (prefs.hasPassphrase || prefs.isPassphraseCorrupted) {
                    TextButton(
                        onClick = onClearSavedPassphrase,
                        modifier = Modifier.testTag("webdav_clear_passphrase_button"),
                    ) {
                        Text("清除口令")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = DropletIcons.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp),
                    )
                    Text(
                        text = "请妥善保管加密口令：Droplet 无云端账号体系，口令仅存于本机 Keystore，遗忘后任何人都无法解密已上传的 .drp 备份包。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebDavSyncPolicyCard(
    uiState: WebDavSyncUiState,
    onToggleAutoSync: (Boolean) -> Unit,
    onToggleDailyBackup: (Boolean) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onToggleSyncAttachments: (Boolean) -> Unit,
    onSelectRetentionCount: (Int) -> Unit,
) {
    val prefs = uiState.preferences
    DropletCard {
        WebDavSwitchRow(
            title = "记账变更后自动上传（30 秒防抖）",
            subtitle = "连续记账自动合并为单次快照上传，减少网盘请求与耗电",
            checked = prefs.autoSyncEnabled,
            onCheckedChange = onToggleAutoSync,
            switchTestTag = "webdav_switch_auto_sync",
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        WebDavSwitchRow(
            title = "每日定时备份",
            subtitle = "由 WorkManager 每 24 小时在联网时执行一次完整加密快照",
            checked = prefs.dailyBackupEnabled,
            onCheckedChange = onToggleDailyBackup,
            switchTestTag = "webdav_switch_daily_backup",
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        WebDavSwitchRow(
            title = "仅在 Wi-Fi 下自动同步",
            subtitle = "自动防抖上传与每日定时备份仅在不计费网络下触发",
            checked = prefs.wifiOnly,
            onCheckedChange = onToggleWifiOnly,
            switchTestTag = "webdav_switch_wifi_only",
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        WebDavSwitchRow(
            title = "同步票据原图附件",
            subtitle = "默认关闭以节省网盘空间；开启后一并打包本机票据图片并校验哈希",
            checked = prefs.syncAttachments,
            onCheckedChange = onToggleSyncAttachments,
            switchTestTag = "webdav_switch_sync_attachments",
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "远端历史快照保留份数",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "每次上传成功后自动清理超额最旧快照，当前保留最近 ${prefs.retentionCount} 份",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WebDavUrlValidator.RETENTION_PRESETS.forEach { count ->
                FilterChip(
                    selected = prefs.retentionCount == count,
                    onClick = { onSelectRetentionCount(count) },
                    label = { Text("$count 份") },
                    modifier = Modifier.testTag("webdav_retention_$count"),
                )
            }
        }
    }
}

@Composable
private fun WebDavRemoteSnapshotsCard(
    uiState: WebDavSyncUiState,
    onRefreshRemoteSnapshots: () -> Unit,
    onRequestRestore: (RemoteBackupSnapshot) -> Unit,
    onRequestDelete: (RemoteBackupSnapshot) -> Unit,
) {
    DropletCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WebDAV 远端快照（${uiState.remoteSnapshots.size}）",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "按时间倒序展示，点击「安全恢复」前自动生成本地应急回滚点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onRefreshRemoteSnapshots,
                enabled = uiState.preferences.isServerConfigured && !uiState.isLoadingRemoteSnapshots,
                modifier = Modifier.testTag("webdav_refresh_snapshots_button"),
            ) {
                if (uiState.isLoadingRemoteSnapshots) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("刷新列表")
                }
            }
        }

        if (uiState.remoteSnapshotsError != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = uiState.remoteSnapshotsError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (uiState.remoteSnapshots.isEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (uiState.preferences.isServerConfigured) {
                    "暂无已加载的远端快照，点击「刷新列表」拉取或立即执行首次备份。"
                } else {
                    "保存 WebDAV 服务器配置后即可拉取并管理远端历史快照。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(modifier = Modifier.height(10.dp))
            uiState.remoteSnapshots.forEachIndexed { index, snapshot ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("webdav_remote_snapshot_$index"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = snapshot.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        DropletPillBadge(
                            text = if (snapshot.isEncrypted) "DRP1 加密" else "明文 ZIP",
                            containerColor = if (snapshot.isEncrypted) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (snapshot.isEncrypted) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Text(
                        text = "${formatTimestamp(snapshot.lastModifiedEpochMs)} · ${
                            DefaultWebDavBackupOrchestrator.formatReadableSize(snapshot.sizeBytes)
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { onRequestRestore(snapshot) },
                            enabled = !uiState.isRestoring && !uiState.isBackingUp,
                            modifier = Modifier.testTag("webdav_restore_remote_button_$index"),
                        ) {
                            Text("安全恢复")
                        }
                        OutlinedButton(
                            onClick = { onRequestDelete(snapshot) },
                            enabled = !uiState.isRestoring && !uiState.isBackingUp,
                            modifier = Modifier.testTag("webdav_delete_remote_button_$index"),
                        ) {
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalRollbackSnapshotsCard(
    uiState: WebDavSyncUiState,
    onRequestRestore: (LocalRollbackSnapshot) -> Unit,
    onRequestDelete: (LocalRollbackSnapshot) -> Unit,
) {
    DropletCard {
        Text(
            text = "本机应急回滚点（${uiState.localRollbackSnapshots.size}）",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "每次执行恢复前会自动留存，也可手动创建本机一致性快照，最多保留最近 5 份。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (uiState.localRollbackSnapshots.isEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "当前暂无本机应急快照，可点击顶部「创建本地快照」立即离线留存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("webdav_local_rollbacks_empty"),
            )
        } else {
            Spacer(modifier = Modifier.height(10.dp))
            uiState.localRollbackSnapshots.forEachIndexed { index, snapshot ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("webdav_local_rollback_$index"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = snapshot.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("webdav_local_rollback_name_$index"),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        DropletPillBadge(
                            text = if (snapshot.isPreRestoreAuto) "恢复前自动留存" else "手动本地快照",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Text(
                        text = "${formatTimestamp(snapshot.createdAtEpochMs)} · ${
                            DefaultWebDavBackupOrchestrator.formatReadableSize(snapshot.sizeBytes)
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { onRequestRestore(snapshot) },
                            enabled = !uiState.isRestoring && !uiState.isBackingUp,
                            modifier = Modifier.testTag("webdav_restore_local_button_$index"),
                        ) {
                            Text("回滚恢复")
                        }
                        OutlinedButton(
                            onClick = { onRequestDelete(snapshot) },
                            enabled = !uiState.isRestoring && !uiState.isBackingUp,
                            modifier = Modifier.testTag("webdav_delete_local_button_$index"),
                        ) {
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebDavSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTestTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(switchTestTag),
        )
    }
}

private val SNAPSHOT_DISPLAY_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

private fun formatTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return "未知时间"
    return SNAPSHOT_DISPLAY_TIME_FORMATTER.format(
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()),
    )
}

private fun buildLastBackupLine(prefs: io.github.kvtttba.droplet.core.security.WebDavSyncPreferences): String? {
    val ts = prefs.lastBackupAt ?: return null
    val status = when (prefs.lastBackupSuccess) {
        true -> "上次备份成功"
        false -> "上次备份失败"
        null -> "上次备份"
    }
    return "$status · ${formatTimestamp(ts)}"
}
