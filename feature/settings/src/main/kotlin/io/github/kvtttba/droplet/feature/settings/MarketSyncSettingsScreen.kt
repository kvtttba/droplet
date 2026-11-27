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
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.network.NetworkProxyType
import io.github.kvtttba.droplet.core.security.MarketSyncInterval
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MarketSyncSettingsRoute(
    onNavigateBack: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MarketSyncSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    MarketSyncSettingsScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onSelectInterval = viewModel::setRefreshInterval,
        onToggleWifiOnly = viewModel::setWifiOnly,
        onRefreshNow = viewModel::refreshNow,
        onProxyTypeChange = viewModel::onDraftProxyTypeChange,
        onProxyHostChange = viewModel::onDraftProxyHostChange,
        onProxyPortChange = viewModel::onDraftProxyPortChange,
        onSaveProxy = viewModel::saveProxyConfig,
        onClearProxy = viewModel::clearProxyConfig,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MarketSyncSettingsScreen(
    uiState: MarketSyncSettingsUiState,
    onNavigateBack: () -> Unit = {},
    onSelectInterval: (MarketSyncInterval) -> Unit = {},
    onToggleWifiOnly: (Boolean) -> Unit = {},
    onRefreshNow: () -> Unit = {},
    onProxyTypeChange: (NetworkProxyType) -> Unit = {},
    onProxyHostChange: (String) -> Unit = {},
    onProxyPortChange: (String) -> Unit = {},
    onSaveProxy: () -> Unit = {},
    onClearProxy: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val proxyStatusLabel = when {
        !uiState.savedProxyConfig.isEnabled -> "直连（无代理）"
        uiState.savedProxyConfig.type == NetworkProxyType.HTTP ->
            "HTTP 代理 · ${uiState.savedProxyConfig.host}:${uiState.savedProxyConfig.port}"
        else ->
            "SOCKS 代理 · ${uiState.savedProxyConfig.host}:${uiState.savedProxyConfig.port}"
    }
    val lastUpdatedLabel = formatSyncTimestamp(uiState.lastUpdatedAtEpochMs)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_market_sync_settings"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "行情同步与网络代理",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("market_sync_back_button"),
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
                .testTag("market_sync_settings_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "hero_sync_status") {
                DropletHeroCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
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
                                text = if (uiState.refreshInterval.isPeriodicEnabled) {
                                    "后台自动刷新 · ${uiState.refreshInterval.label}"
                                } else {
                                    "仅手动刷新行情与汇率"
                                },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = buildString {
                                    append(if (uiState.wifiOnly) "仅 Wi-Fi 同步" else "任意网络同步")
                                    append(" · ")
                                    append(proxyStatusLabel)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DropletPillBadge(
                            text = "上次更新：$lastUpdatedLabel",
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                        DropletPillBadge(
                            text = "汇率缓存 ${uiState.cachedRateCount} 组",
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                        DropletPillBadge(
                            text = "行情缓存 ${uiState.cachedQuoteCount} 项",
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            item(key = "section_schedule_header") {
                DropletSectionHeader(title = "后台定时刷新（WorkManager）")
            }

            item(key = "sync_schedule_card") {
                DropletCard {
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
                                text = "自动刷新周期",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DropletPillBadge(text = uiState.refreshInterval.label)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MarketSyncInterval.entries.forEach { interval ->
                            FilterChip(
                                selected = uiState.refreshInterval == interval,
                                onClick = { onSelectInterval(interval) },
                                label = { Text(interval.label) },
                                modifier = Modifier.testTag("market_sync_interval_${interval.storageKey}"),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = uiState.refreshInterval.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "仅在 Wi-Fi 下后台更新",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (uiState.refreshInterval.isPeriodicEnabled) {
                                    "开启后仅在非计费 Wi-Fi 网络下执行后台定时刷新，不影响手动刷新"
                                } else {
                                    "开启自动刷新周期后该约束将立即生效"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.wifiOnly,
                            onCheckedChange = onToggleWifiOnly,
                            modifier = Modifier.testTag("market_sync_switch_wifi_only"),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = onRefreshNow,
                        enabled = !uiState.isRefreshingNow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("market_sync_refresh_now_button"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (uiState.isRefreshingNow) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (uiState.isRefreshingNow) "正在同步汇率与行情…" else "立即强制刷新汇率与行情",
                        )
                    }
                    if (!uiState.lastRefreshSummary.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("market_sync_last_summary"),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Text(
                                text = uiState.lastRefreshSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            item(key = "section_proxy_header") {
                DropletSectionHeader(title = "网络代理设置（可选）")
            }

            item(key = "network_proxy_card") {
                DropletCard {
                    Text(
                        text = "默认优先使用国内可直连的公开行情源；若拉取海外美股、Yahoo 或加密货币接口受限，可配置本机或局域网 HTTP / SOCKS 代理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NetworkProxyType.entries.forEach { type ->
                            val label = when (type) {
                                NetworkProxyType.NONE -> "直连"
                                NetworkProxyType.HTTP -> "HTTP 代理"
                                NetworkProxyType.SOCKS -> "SOCKS 代理"
                            }
                            FilterChip(
                                selected = uiState.draftProxyType == type,
                                onClick = { onProxyTypeChange(type) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("market_proxy_type_${type.storageKey}"),
                            )
                        }
                    }

                    if (uiState.draftProxyType != NetworkProxyType.NONE) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = uiState.draftProxyHost,
                            onValueChange = onProxyHostChange,
                            label = { Text("代理主机（域名或 IP）") },
                            placeholder = { Text("例如 127.0.0.1") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("market_proxy_host_input"),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = uiState.draftProxyPortText,
                            onValueChange = onProxyPortChange,
                            label = { Text("代理端口 (1–65535)") },
                            placeholder = {
                                Text(
                                    if (uiState.draftProxyType == NetworkProxyType.SOCKS) "例如 1080" else "例如 7890",
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("market_proxy_port_input"),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "仅持久化裸主机名与端口，严禁内嵌凭据且不会写入日志。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (!uiState.proxyValidationError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.proxyValidationError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("market_proxy_error"),
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onSaveProxy,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("market_proxy_save_button"),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                text = if (uiState.draftProxyType == NetworkProxyType.NONE) {
                                    "保存直连设置"
                                } else {
                                    "保存代理配置"
                                },
                            )
                        }
                        if (uiState.savedProxyConfig.isEnabled) {
                            OutlinedButton(
                                onClick = onClearProxy,
                                modifier = Modifier.testTag("market_proxy_clear_button"),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text("恢复直连")
                            }
                        }
                    }
                }
            }

            item(key = "provider_policy_card") {
                DropletCard {
                    Text(
                        text = "多源降级与缓存保护说明",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• 进入资产页默认执行 5 分钟节流同步，后台定时任务同步遵守节流策略。\n" +
                            "• 任一标的拉取失败将完整保留上次缓存净值与更新时间，不影响其他标的。\n" +
                            "• 已手动覆写单价或手工更新净值的银行理财不会被自动行情覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val syncTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatSyncTimestamp(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0L) return "尚未同步"
    return Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(syncTimeFormatter)
}
