package io.github.kvtttba.droplet.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.component.LocalAmountsHidden
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onToggleAmountsHidden: () -> Unit,
    onOpenAccountManagement: () -> Unit = {},
    onOpenCategoryTagManagement: () -> Unit = {},
    onOpenPrivacySecurity: () -> Unit = {},
    onOpenMarketSyncSettings: () -> Unit = {},
    biometricLockEnabled: Boolean = false,
    flagSecureEnabled: Boolean = false,
    marketSyncSummary: String = "后台自动刷新 · 可选 HTTP/SOCKS 代理",
    versionLabel: String = "v0.1.0-m2.3",
    modifier: Modifier = Modifier,
) {
    val amountsHidden = LocalAmountsHidden.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_settings"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "我的",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                DropletPillBadge(text = versionLabel)
            }
        }

        item {
            DropletHeroCard(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
                            imageVector = DropletIcons.WaterDrop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Column {
                        Text(
                            text = "Droplet 记账",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "无账号 · 本地离线优先 · 严格复式记账",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }

        item {
            DropletSectionHeader(title = "账本基础管理")
        }

        item {
            DropletCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                SettingsItemRow(
                    icon = DropletIcons.AccountBalanceWallet,
                    title = "账户管理",
                    subtitle = "日常账户、信用参数、期初余额建账与归档",
                    onClick = onOpenAccountManagement,
                    modifier = Modifier.testTag("settings_entry_accounts"),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 48.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                SettingsItemRow(
                    icon = DropletIcons.Category,
                    title = "二级分类与标签管理",
                    subtitle = "自定义收支大类/小类图标与多标签配色",
                    onClick = onOpenCategoryTagManagement,
                    modifier = Modifier.testTag("settings_entry_categories_tags"),
                )
            }
        }

        item {
            DropletSectionHeader(title = "外观与隐私")
        }

        item {
            DropletCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = DropletIcons.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "主题模式",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            label = { Text(mode.label) },
                            modifier = Modifier.testTag("settings_theme_${mode.name}"),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                SettingsSwitchRow(
                    title = "Material You 动态取色",
                    subtitle = "跟随 Android 12+ 系统壁纸色板",
                    checked = dynamicColor,
                    onCheckedChange = onDynamicColorChange,
                    switchTestTag = "settings_switch_dynamic_color",
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                SettingsSwitchRow(
                    title = "隐藏全部金额",
                    subtitle = "首页、资产与统计页统一显示为 ****",
                    checked = amountsHidden,
                    onCheckedChange = { onToggleAmountsHidden() },
                    switchTestTag = "settings_switch_amounts_hidden",
                )
            }
        }

        item {
            DropletSectionHeader(title = "隐私安全 · 智能记账与同步")
        }

        item(key = "settings_security_card") {
            val privacyStatusSummary = buildString {
                append(if (biometricLockEnabled) "应用锁已开启" else "应用锁未开启")
                append(" · ")
                append(if (flagSecureEnabled) "防截屏已开启" else "防截屏未开启")
            }
            DropletCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                SettingsItemRow(
                    icon = DropletIcons.Fingerprint,
                    title = "生物识别锁与防截屏",
                    subtitle = privacyStatusSummary,
                    onClick = onOpenPrivacySecurity,
                    modifier = Modifier.testTag("settings_entry_privacy_security"),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 48.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                SettingsItemRow(
                    icon = DropletIcons.CandlestickChart,
                    title = "行情同步与网络代理",
                    subtitle = marketSyncSummary,
                    onClick = onOpenMarketSyncSettings,
                    modifier = Modifier.testTag("settings_entry_market_sync"),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 48.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                SettingsItemRow(
                    icon = DropletIcons.AutoAwesome,
                    title = "AI 服务配置",
                    subtitle = "自定义 OpenAI Compatible BaseURL / 模型 · M4",
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 48.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                SettingsItemRow(
                    icon = DropletIcons.Repeat,
                    title = "预算、周期记账与快捷模板",
                    subtitle = "分类预算预警 · 自动入账规则 · M5.1",
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 48.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                SettingsItemRow(
                    icon = DropletIcons.CloudSync,
                    title = "WebDAV 加密备份与恢复",
                    subtitle = "AES-256-GCM 端到端快照 · 本地回滚保护 · M5.3",
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 48.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                SettingsItemRow(
                    icon = DropletIcons.FileDownload,
                    title = "数据导出",
                    subtitle = "Excel (.xlsx) / CSV / Beancount 纯文本 · M5.2",
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTestTag: String? = null,
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
            modifier = if (switchTestTag != null) Modifier.testTag(switchTestTag) else Modifier,
        )
    }
}

@Composable
private fun SettingsItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
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
        if (onClick != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
