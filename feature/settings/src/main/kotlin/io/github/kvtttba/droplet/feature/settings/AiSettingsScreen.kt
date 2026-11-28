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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.ai.AiConnectionTestResult
import io.github.kvtttba.droplet.core.security.AiEndpointSlot
import io.github.kvtttba.droplet.core.security.VoiceEngineMode
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons

@Composable
fun AiSettingsRoute(
    onNavigateBack: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AiSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    AiSettingsScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onBaseUrlChange = viewModel::onBaseUrlChange,
        onModelChange = viewModel::onModelChange,
        onApiKeyInputChange = viewModel::onApiKeyInputChange,
        onToggleApiKeyVisible = viewModel::onToggleApiKeyVisible,
        onToggleOverride = viewModel::onToggleOverride,
        onSaveSlot = viewModel::saveSlot,
        onClearSlotApiKey = viewModel::clearSlotApiKey,
        onTestConnection = viewModel::testConnection,
        onSelectVoiceEngineMode = viewModel::setVoiceEngineMode,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiSettingsScreen(
    uiState: AiSettingsUiState,
    onNavigateBack: () -> Unit = {},
    onBaseUrlChange: (AiEndpointSlot, String) -> Unit = { _, _ -> },
    onModelChange: (AiEndpointSlot, String) -> Unit = { _, _ -> },
    onApiKeyInputChange: (AiEndpointSlot, String) -> Unit = { _, _ -> },
    onToggleApiKeyVisible: (AiEndpointSlot) -> Unit = {},
    onToggleOverride: (AiEndpointSlot, Boolean) -> Unit = { _, _ -> },
    onSaveSlot: (AiEndpointSlot) -> Unit = {},
    onClearSlotApiKey: (AiEndpointSlot) -> Unit = {},
    onTestConnection: (AiEndpointSlot) -> Unit = {},
    onSelectVoiceEngineMode: (VoiceEngineMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_ai_settings"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AI 服务配置",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("ai_settings_back_button"),
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
                .testTag("ai_settings_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "ai_hero_privacy_card") {
                AiPrivacyHeroCard(uiState = uiState)
            }

            item(key = "ai_default_section_header") {
                DropletSectionHeader(title = "默认全局配置（OpenAI Compatible）")
            }

            item(key = "ai_default_config_card") {
                AiDefaultEndpointCard(
                    draft = uiState.defaultDraft,
                    onBaseUrlChange = { onBaseUrlChange(AiEndpointSlot.DEFAULT, it) },
                    onModelChange = { onModelChange(AiEndpointSlot.DEFAULT, it) },
                    onApiKeyInputChange = { onApiKeyInputChange(AiEndpointSlot.DEFAULT, it) },
                    onToggleApiKeyVisible = { onToggleApiKeyVisible(AiEndpointSlot.DEFAULT) },
                    onSave = { onSaveSlot(AiEndpointSlot.DEFAULT) },
                    onClearApiKey = { onClearSlotApiKey(AiEndpointSlot.DEFAULT) },
                    onTestConnection = { onTestConnection(AiEndpointSlot.DEFAULT) },
                )
            }

            item(key = "ai_voice_engine_header") {
                DropletSectionHeader(title = "语音记账转写引擎")
            }

            item(key = "ai_voice_engine_card") {
                VoiceEngineModeCard(
                    selectedMode = uiState.voiceEngineMode,
                    onSelectMode = onSelectVoiceEngineMode,
                )
            }

            item(key = "ai_override_section_header") {
                DropletSectionHeader(title = "能力槽位独立覆盖（可选）")
            }

            AiEndpointSlot.OVERRIDE_SLOTS.forEach { slot ->
                item(key = "ai_slot_card_${slot.storageKey}") {
                    AiOverrideSlotCard(
                        slot = slot,
                        draft = uiState.draftFor(slot),
                        onToggleOverride = { enabled -> onToggleOverride(slot, enabled) },
                        onBaseUrlChange = { onBaseUrlChange(slot, it) },
                        onModelChange = { onModelChange(slot, it) },
                        onApiKeyInputChange = { onApiKeyInputChange(slot, it) },
                        onToggleApiKeyVisible = { onToggleApiKeyVisible(slot) },
                        onSave = { onSaveSlot(slot) },
                        onClearApiKey = { onClearSlotApiKey(slot) },
                        onTestConnection = { onTestConnection(slot) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiPrivacyHeroCard(
    uiState: AiSettingsUiState,
) {
    val config = uiState.savedConfig
    DropletHeroCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.testTag("ai_privacy_notice_banner"),
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
                    imageVector = DropletIcons.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (config.hasAnyConfiguredEndpoint) {
                        "AI 记账服务已就绪"
                    } else {
                        "自定义 OpenAI Compatible 服务"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = config.formatSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier.testTag("ai_hero_summary"),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = DropletIcons.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(18.dp),
                )
                Text(
                    text = "隐私最小化承诺：仅向模型发送账户名称/类型/币种、二级分类路径与标签名，绝不上传账户余额、卡号或历史流水；API Key 经 Android Keystore 本地加密保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DropletPillBadge(
                text = "Keystore AES-256-GCM",
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            DropletPillBadge(
                text = "仅传名称 · 不传余额",
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            DropletPillBadge(
                text = "自动继承代理设置",
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AiDefaultEndpointCard(
    draft: AiSlotDraftUiState,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyInputChange: (String) -> Unit,
    onToggleApiKeyVisible: () -> Unit,
    onSave: () -> Unit,
    onClearApiKey: () -> Unit,
    onTestConnection: () -> Unit,
) {
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
                    imageVector = DropletIcons.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "默认全局端点",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val (badgeText, badgeContainer, badgeContent) = when {
                draft.isSavedKeyCorrupted -> Triple(
                    "密钥需重填",
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
                draft.isConfigured -> Triple(
                    "已加密配置",
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
                else -> Triple(
                    "未配置",
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropletPillBadge(
                text = badgeText,
                containerColor = badgeContainer,
                contentColor = badgeContent,
                modifier = Modifier
                    .semantics(mergeDescendants = true) {}
                    .testTag("ai_default_status_badge"),
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "适用于所有兼容 OpenAI 协议的服务商或自建网关；未单独覆盖的能力槽位均自动复用此配置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        AiEndpointFormFields(
            slotTagPrefix = "ai_default",
            draft = draft,
            baseUrlPlaceholder = "例如 https://api.example.invalid/v1",
            modelPlaceholder = "例如 gpt-4o-mini",
            isOverrideSlot = false,
            onBaseUrlChange = onBaseUrlChange,
            onModelChange = onModelChange,
            onApiKeyInputChange = onApiKeyInputChange,
            onToggleApiKeyVisible = onToggleApiKeyVisible,
            onClearApiKey = onClearApiKey,
        )

        Spacer(modifier = Modifier.height(14.dp))
        AiSlotActionRow(
            saveLabel = "保存配置",
            saveButtonTag = "ai_default_save_button",
            testButtonTag = "ai_default_test_button",
            isSaving = draft.isSaving,
            isTesting = draft.isTestingConnection,
            onSave = onSave,
            onTestConnection = onTestConnection,
        )

        draft.testResult?.let { result ->
            Spacer(modifier = Modifier.height(12.dp))
            AiConnectionResultBanner(
                result = result,
                modifier = Modifier.testTag("ai_default_test_result"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceEngineModeCard(
    selectedMode: VoiceEngineMode,
    onSelectMode: (VoiceEngineMode) -> Unit,
) {
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
                    imageVector = DropletIcons.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "语音转文字方式",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            DropletPillBadge(text = selectedMode.shortLabel)
        }
        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            VoiceEngineMode.entries.forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    onClick = { onSelectMode(mode) },
                    label = { Text(mode.label) },
                    modifier = Modifier.testTag("ai_voice_mode_${mode.storageKey}"),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = selectedMode.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AiOverrideSlotCard(
    slot: AiEndpointSlot,
    draft: AiSlotDraftUiState,
    onToggleOverride: (Boolean) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyInputChange: (String) -> Unit,
    onToggleApiKeyVisible: () -> Unit,
    onSave: () -> Unit,
    onClearApiKey: () -> Unit,
    onTestConnection: () -> Unit,
) {
    val slotIcon: ImageVector = when (slot) {
        AiEndpointSlot.VISION -> DropletIcons.PhotoCamera
        AiEndpointSlot.TEXT -> DropletIcons.EditNote
        AiEndpointSlot.VOICE -> DropletIcons.Mic
        AiEndpointSlot.DEFAULT -> DropletIcons.AutoAwesome
    }
    val modelPlaceholder = when (slot) {
        AiEndpointSlot.VISION -> "例如 gpt-4o / qwen-vl-max"
        AiEndpointSlot.TEXT -> "例如 deepseek-chat / gpt-4o-mini"
        AiEndpointSlot.VOICE -> "例如 whisper-1"
        AiEndpointSlot.DEFAULT -> "例如 gpt-4o-mini"
    }
    val prefix = "ai_slot_${slot.storageKey}"

    DropletCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    imageVector = slotIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = slot.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = slot.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = draft.overrideEnabled,
                onCheckedChange = onToggleOverride,
                modifier = Modifier.testTag("ai_slot_switch_${slot.storageKey}"),
            )
        }

        if (!draft.overrideEnabled) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_slot_fallback_${slot.storageKey}"),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Text(
                    text = "当前复用默认全局配置 · 开启开关可为该能力单独指定端点或模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        } else {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "BaseURL 或 API Key 留空时，将自动继承默认全局配置；也可独立指定专属网关与密钥。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            AiEndpointFormFields(
                slotTagPrefix = prefix,
                draft = draft,
                baseUrlPlaceholder = "留空则继承默认 BaseURL",
                modelPlaceholder = modelPlaceholder,
                isOverrideSlot = true,
                onBaseUrlChange = onBaseUrlChange,
                onModelChange = onModelChange,
                onApiKeyInputChange = onApiKeyInputChange,
                onToggleApiKeyVisible = onToggleApiKeyVisible,
                onClearApiKey = onClearApiKey,
            )
            Spacer(modifier = Modifier.height(14.dp))
            AiSlotActionRow(
                saveLabel = "保存覆盖",
                saveButtonTag = "ai_slot_save_${slot.storageKey}",
                testButtonTag = "ai_slot_test_${slot.storageKey}",
                isSaving = draft.isSaving,
                isTesting = draft.isTestingConnection,
                onSave = onSave,
                onTestConnection = onTestConnection,
            )
            draft.testResult?.let { result ->
                Spacer(modifier = Modifier.height(12.dp))
                AiConnectionResultBanner(
                    result = result,
                    modifier = Modifier.testTag("ai_slot_test_result_${slot.storageKey}"),
                )
            }
        }
    }
}

@Composable
private fun AiEndpointFormFields(
    slotTagPrefix: String,
    draft: AiSlotDraftUiState,
    baseUrlPlaceholder: String,
    modelPlaceholder: String,
    isOverrideSlot: Boolean,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyInputChange: (String) -> Unit,
    onToggleApiKeyVisible: () -> Unit,
    onClearApiKey: () -> Unit,
) {
    OutlinedTextField(
        value = draft.baseUrl,
        onValueChange = onBaseUrlChange,
        label = { Text(if (isOverrideSlot) "独立 BaseURL（可选）" else "API BaseURL") },
        placeholder = { Text(baseUrlPlaceholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${slotTagPrefix}_base_url_input"),
    )
    Spacer(modifier = Modifier.height(10.dp))

    val keyPlaceholder = when {
        draft.isSavedKeyCorrupted -> "本地密钥已失效，请重新输入 API Key"
        draft.hasSavedApiKey -> "已加密保存（${draft.savedApiKeyMasked}），留空保持不变"
        isOverrideSlot -> "留空则继承默认 API Key"
        else -> "输入 API Key（将经 Keystore 本地加密）"
    }
    OutlinedTextField(
        value = draft.apiKeyInput,
        onValueChange = onApiKeyInputChange,
        label = { Text(if (isOverrideSlot) "独立 API Key（可选）" else "API Key") },
        placeholder = { Text(keyPlaceholder) },
        singleLine = true,
        visualTransformation = if (draft.apiKeyVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(
                onClick = onToggleApiKeyVisible,
                modifier = Modifier.testTag("${slotTagPrefix}_api_key_visibility"),
            ) {
                Icon(
                    imageVector = if (draft.apiKeyVisible) {
                        DropletIcons.VisibilityOff
                    } else {
                        DropletIcons.Visibility
                    },
                    contentDescription = if (draft.apiKeyVisible) "隐藏 API Key" else "显示 API Key",
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${slotTagPrefix}_api_key_input"),
    )

    if (draft.hasSavedApiKey && !draft.isSavedKeyCorrupted) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = DropletIcons.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "已 Keystore 加密：${draft.savedApiKeyMasked}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("${slotTagPrefix}_saved_key_badge"),
                )
            }
            TextButton(
                onClick = onClearApiKey,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.testTag("${slotTagPrefix}_clear_key_button"),
            ) {
                Text(
                    text = "清除密钥",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))
    OutlinedTextField(
        value = draft.model,
        onValueChange = onModelChange,
        label = { Text(if (isOverrideSlot) "独立模型名称（Model）" else "默认模型名称（Model）") },
        placeholder = { Text(modelPlaceholder) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${slotTagPrefix}_model_input"),
    )

    if (!draft.validationError.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = draft.validationError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("${slotTagPrefix}_error"),
        )
    }
}

@Composable
private fun AiSlotActionRow(
    saveLabel: String,
    saveButtonTag: String,
    testButtonTag: String,
    isSaving: Boolean,
    isTesting: Boolean,
    onSave: () -> Unit,
    onTestConnection: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onSave,
            enabled = !isSaving && !isTesting,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .weight(1f)
                .testTag(saveButtonTag),
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(text = saveLabel)
        }
        FilledTonalButton(
            onClick = onTestConnection,
            enabled = !isSaving && !isTesting,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .weight(1f)
                .testTag(testButtonTag),
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "测试中…")
            } else {
                Icon(
                    imageVector = DropletIcons.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "连通性测试")
            }
        }
    }
}

@Composable
private fun AiConnectionResultBanner(
    result: AiConnectionTestResult,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (result.success) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (result.success) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (result.success) "连通性测试通过" else "连通性测试未通过",
                    style = MaterialTheme.typography.labelLarge,
                )
                val metaText = buildString {
                    if (result.httpStatusCode != null) {
                        append("HTTP ${result.httpStatusCode}")
                    }
                    if (result.latencyMs > 0L) {
                        if (isNotEmpty()) append(" · ")
                        append("${result.latencyMs} ms")
                    }
                }
                if (metaText.isNotEmpty()) {
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text = result.message,
                style = MaterialTheme.typography.bodySmall,
            )
            if (result.probeMethod.isNotBlank()) {
                Text(
                    text = "探测路径：${result.probeMethod}",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.8f),
                )
            }
        }
    }
}
