package io.github.kvtttba.droplet.feature.settings

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.security.BiometricCapability
import io.github.kvtttba.droplet.core.security.RelockTimeout
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons

@Composable
fun PrivacySecurityRoute(
    onNavigateBack: () -> Unit,
    onVerifyIdentityForEnable: ((Boolean) -> Unit) -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PrivacySecurityViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshCapability()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    PrivacySecurityScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onToggleBiometricLock = { enabled ->
            viewModel.onBiometricLockToggleRequested(
                enabled = enabled,
                requestIdentityVerification = onVerifyIdentityForEnable,
            )
        },
        onSelectRelockTimeout = viewModel::setRelockTimeout,
        onToggleFlagSecure = viewModel::setFlagSecureEnabled,
        onToggleAmountsHidden = viewModel::setAmountsHidden,
        onLockNow = viewModel::lockNow,
        onOpenSystemSecuritySettings = {
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
                .onFailure { onShowMessage("无法打开系统安全设置，请手动前往系统设置配置锁屏") }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PrivacySecurityScreen(
    uiState: PrivacySecurityUiState,
    onNavigateBack: () -> Unit = {},
    onToggleBiometricLock: (Boolean) -> Unit = {},
    onSelectRelockTimeout: (RelockTimeout) -> Unit = {},
    onToggleFlagSecure: (Boolean) -> Unit = {},
    onToggleAmountsHidden: (Boolean) -> Unit = {},
    onLockNow: () -> Unit = {},
    onOpenSystemSecuritySettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val activeProtectionCount = listOf(
        uiState.biometricLockEnabled,
        uiState.flagSecureEnabled,
        uiState.amountsHidden,
    ).count { it }

    val heroTitle = when {
        uiState.biometricLockEnabled && uiState.flagSecureEnabled -> "隐私防护已全面开启"
        activeProtectionCount > 0 -> "隐私防护已部分开启"
        else -> "标准本地保护"
    }

    val heroSubtitle = buildString {
        append(if (uiState.biometricLockEnabled) "应用锁已开启（${uiState.relockTimeout.label}重锁）" else "应用锁未开启")
        append(" · ")
        append(if (uiState.flagSecureEnabled) "防截屏遮罩生效中" else "防截屏未开启")
    }

    val heroContainer = if (uiState.biometricLockEnabled || uiState.flagSecureEnabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val heroContentColor = if (uiState.biometricLockEnabled || uiState.flagSecureEnabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_privacy_security"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "隐私与安全",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("privacy_back_button"),
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
                .testTag("privacy_security_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "hero_banner") {
                DropletHeroCard(
                    containerColor = heroContainer,
                    contentColor = heroContentColor,
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
                                imageVector = DropletIcons.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = heroTitle,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = heroSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = heroContentColor.copy(alpha = 0.82f),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DropletPillBadge(
                            text = if (uiState.biometricLockEnabled) "应用锁 · 已开启" else "应用锁 · 未开启",
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                        DropletPillBadge(
                            text = if (uiState.flagSecureEnabled) "FLAG_SECURE · 已开启" else "FLAG_SECURE · 关闭",
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                        DropletPillBadge(
                            text = if (uiState.amountsHidden) "金额隐身 · 开启" else "金额显示 · 正常",
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            item(key = "section_app_lock_header") {
                DropletSectionHeader(title = "应用访问锁")
            }

            item(key = "app_lock_card") {
                DropletCard {
                    BiometricCapabilityBanner(
                        capability = uiState.biometricCapability,
                        onOpenSystemSecuritySettings = onOpenSystemSecuritySettings,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    PrivacySwitchRow(
                        title = "生物识别 / 设备锁屏保护",
                        subtitle = "冷启动与切后台超时后自动锁定，支持指纹、面容与 PIN / 图案 / 密码兜底",
                        checked = uiState.biometricLockEnabled,
                        enabled = uiState.biometricCapability == BiometricCapability.AVAILABLE ||
                            uiState.biometricLockEnabled,
                        onCheckedChange = onToggleBiometricLock,
                        switchTestTag = "privacy_switch_biometric_lock",
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("privacy_relock_timeout_section"),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = DropletIcons.Timer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = "切后台自动重锁",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            DropletPillBadge(text = uiState.relockTimeout.label)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RelockTimeout.entries.forEach { timeout ->
                                FilterChip(
                                    selected = uiState.relockTimeout == timeout,
                                    onClick = { onSelectRelockTimeout(timeout) },
                                    label = { Text(timeout.label) },
                                    modifier = Modifier.testTag("relock_timeout_${timeout.storageKey}"),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = uiState.relockTimeout.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (uiState.biometricLockEnabled) {
                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = onLockNow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("privacy_lock_now_button"),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(
                                imageVector = DropletIcons.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "立即锁定应用")
                        }
                    }
                }
            }

            item(key = "section_screen_privacy_header") {
                DropletSectionHeader(title = "屏幕防窥与金额隐身")
            }

            item(key = "screen_privacy_card") {
                DropletCard {
                    PrivacySwitchRow(
                        title = "防截屏与多任务遮罩",
                        subtitle = "启用系统 FLAG_SECURE，禁止截屏录屏并在多任务预览中自动隐藏账本画面",
                        checked = uiState.flagSecureEnabled,
                        enabled = true,
                        onCheckedChange = onToggleFlagSecure,
                        switchTestTag = "privacy_switch_flag_secure",
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    PrivacySwitchRow(
                        title = "默认隐藏全部金额",
                        subtitle = "首页、资产与统计页金额统一显示为 ****，重启后自动保留当前隐身状态",
                        checked = uiState.amountsHidden,
                        enabled = true,
                        onCheckedChange = onToggleAmountsHidden,
                        switchTestTag = "privacy_switch_amounts_hidden",
                    )
                }
            }

            item(key = "section_keystore_header") {
                DropletSectionHeader(title = "本地凭据与密钥保护")
            }

            item(key = "keystore_info_card") {
                DropletCard {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = DropletIcons.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Android Keystore AES-256-GCM 凭据加密",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "AI API Key 与 WebDAV 密码等敏感凭据均通过本机 Android Keystore 生成的 256 位随机密钥加密保存，严禁明文入库或写入日志。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BiometricCapabilityBanner(
    capability: BiometricCapability,
    onOpenSystemSecuritySettings: () -> Unit,
) {
    val (badgeText, badgeContainer, badgeContent, description) = when (capability) {
        BiometricCapability.AVAILABLE -> Quadruple(
            "设备就绪",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "当前设备已配置生物识别或系统锁屏密码，可直接开启应用锁保护。",
        )
        BiometricCapability.NONE_ENROLLED -> Quadruple(
            "未配置锁屏",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "未检测到已录入的指纹、面容或设备锁屏密码，请先前往系统设置启用锁屏验证。",
        )
        BiometricCapability.UNAVAILABLE -> Quadruple(
            "硬件不可用",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "当前设备环境暂不支持生物识别或设备锁屏验证，仍可使用防截屏遮罩与金额隐身。",
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("privacy_capability_banner"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = DropletIcons.Fingerprint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "本机鉴权能力检测",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropletPillBadge(
                    text = badgeText,
                    containerColor = badgeContainer,
                    contentColor = badgeContent,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (capability == BiometricCapability.NONE_ENROLLED) {
                Spacer(modifier = Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = onOpenSystemSecuritySettings,
                    modifier = Modifier.testTag("privacy_open_system_settings"),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(text = "前往系统安全设置")
                }
            }
        }
    }
}

@Composable
private fun PrivacySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTestTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                },
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(switchTestTag),
        )
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
