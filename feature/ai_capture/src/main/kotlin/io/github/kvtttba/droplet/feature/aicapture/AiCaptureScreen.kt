package io.github.kvtttba.droplet.feature.aicapture

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.ai.AiDraftAttachmentRef
import io.github.kvtttba.droplet.core.model.AiDraftInputType
import io.github.kvtttba.droplet.core.model.AiDraftStatus
import io.github.kvtttba.droplet.core.security.AiEndpointSlot
import io.github.kvtttba.droplet.core.security.VoiceEngineMode
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun AiCaptureRoute(
    onNavigateBack: () -> Unit,
    onOpenDraftReview: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    onShowMessage: (String) -> Unit = {},
    pendingSharePayload: PendingAiSharePayload? = null,
    onConsumePendingSharePayload: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AiCaptureViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val audioRecorder = remember(context) { InAppVoiceAudioRecorder(context.applicationContext) }
    var pendingCameraTarget by remember { mutableStateOf<Pair<android.net.Uri, File>?>(null) }

    DisposableEffect(audioRecorder) {
        onDispose {
            audioRecorder.stopAndDiscard()
        }
    }

    LaunchedEffect(uiState.isRecordingVoice) {
        if (uiState.isRecordingVoice) {
            var seconds = 0
            while (true) {
                delay(1_000L)
                seconds += 1
                viewModel.onVoiceRecordingStateChanged(recording = true, elapsedSeconds = seconds)
            }
        }
    }

    LaunchedEffect(pendingSharePayload) {
        if (pendingSharePayload != null) {
            viewModel.consumeSharedPayload(pendingSharePayload)
            onConsumePendingSharePayload()
        }
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            onShowMessage(msg)
            viewModel.clearUserMessage()
        }
    }

    LaunchedEffect(uiState.pendingNavigateToDraftId) {
        uiState.pendingNavigateToDraftId?.let { draftId ->
            viewModel.consumePendingNavigation()
            onOpenDraftReview(draftId)
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { saved ->
        val target = pendingCameraTarget
        pendingCameraTarget = null
        if (saved && target != null) {
            viewModel.addImagesFromUris(listOf(target.first))
        } else {
            target?.second?.delete()
        }
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addImagesFromUris(uris)
        }
    }

    val systemSpeechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val transcript = matches?.firstOrNull().orEmpty()
            viewModel.onSystemVoiceRecognized(transcript)
        } else {
            viewModel.showMessage("已取消系统语音输入，可重试或直接在下方编辑文本")
        }
    }

    val startInAppVoiceRecording: () -> Unit = {
        audioRecorder.startRecording().fold(
            onSuccess = {
                viewModel.onVoiceRecordingStateChanged(recording = true, elapsedSeconds = 0)
            },
            onFailure = { err ->
                viewModel.onVoiceRecordingStateChanged(recording = false, elapsedSeconds = 0)
                viewModel.showMessage(err.message ?: "启动录音失败，请检查麦克风权限")
            },
        )
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startInAppVoiceRecording()
        } else {
            viewModel.showMessage("未授予麦克风权限，可在下方手动输入语音内容或切换系统语音")
        }
    }

    AiCaptureScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onOpenAiSettings = onOpenAiSettings,
        onSelectTab = viewModel::selectTab,
        onTextInputChange = viewModel::onTextInputChange,
        onApplyTextExample = viewModel::applyTextExample,
        onSubmitTextOnline = { viewModel.submitTextCapture(saveOfflineOnly = false) },
        onSaveTextOffline = { viewModel.submitTextCapture(saveOfflineOnly = true) },
        onLaunchCamera = {
            try {
                val target = AiCaptureMediaHelpers.createCameraCaptureTarget(context)
                pendingCameraTarget = target
                takePictureLauncher.launch(target.first)
            } catch (e: Exception) {
                viewModel.showMessage(e.message ?: "无法调起相机，请改用相册选图")
            }
        },
        onLaunchGallery = {
            galleryPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onRemoveImage = viewModel::removeSelectedImage,
        onImageHintChange = viewModel::onImageHintChange,
        onSubmitImagesOnline = { viewModel.submitImageCapture(saveOfflineOnly = false) },
        onSaveImagesOffline = { viewModel.submitImageCapture(saveOfflineOnly = true) },
        onSelectVoiceEngineMode = viewModel::setVoiceEngineMode,
        onVoiceTranscriptChange = viewModel::onVoiceTranscriptChange,
        onTriggerSystemVoice = {
            if (AiCaptureMediaHelpers.isSystemSpeechRecognizerAvailable(context)) {
                systemSpeechLauncher.launch(AiCaptureMediaHelpers.buildSystemSpeechRecognizerIntent())
            } else {
                viewModel.showMessage("当前设备未安装系统语音识别服务，可直接在下方输入框使用键盘语音或文字输入")
            }
        },
        onToggleCustomVoiceRecording = {
            if (uiState.isRecordingVoice) {
                viewModel.onVoiceRecordingStateChanged(recording = false, elapsedSeconds = 0)
                audioRecorder.stopAndRetrieveFile().fold(
                    onSuccess = { recordedFile ->
                        viewModel.submitRecordedVoiceFile(recordedFile)
                    },
                    onFailure = { err ->
                        viewModel.showMessage(err.message ?: "保存录音失败，请重试")
                    },
                )
            } else {
                val hasMic = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (hasMic) {
                    startInAppVoiceRecording()
                } else {
                    recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        onSubmitVoiceTranscriptOnline = { viewModel.submitVoiceTranscript(saveOfflineOnly = false) },
        onSaveVoiceTranscriptOffline = { viewModel.submitVoiceTranscript(saveOfflineOnly = true) },
        onOpenDraftReview = onOpenDraftReview,
        onRetryDraft = viewModel::retryDraft,
        onConvertDraftToManualReview = viewModel::convertDraftToManualReview,
        onDeleteDraft = viewModel::deleteDraft,
        resolveThumbnailFile = { relPath -> File(context.filesDir, relPath) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiCaptureScreen(
    uiState: AiCaptureUiState,
    onNavigateBack: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    onSelectTab: (AiCaptureTab) -> Unit = {},
    onTextInputChange: (String) -> Unit = {},
    onApplyTextExample: (String) -> Unit = {},
    onSubmitTextOnline: () -> Unit = {},
    onSaveTextOffline: () -> Unit = {},
    onLaunchCamera: () -> Unit = {},
    onLaunchGallery: () -> Unit = {},
    onRemoveImage: (AiDraftAttachmentRef) -> Unit = {},
    onImageHintChange: (String) -> Unit = {},
    onSubmitImagesOnline: () -> Unit = {},
    onSaveImagesOffline: () -> Unit = {},
    onSelectVoiceEngineMode: (VoiceEngineMode) -> Unit = {},
    onVoiceTranscriptChange: (String) -> Unit = {},
    onTriggerSystemVoice: () -> Unit = {},
    onToggleCustomVoiceRecording: () -> Unit = {},
    onSubmitVoiceTranscriptOnline: () -> Unit = {},
    onSaveVoiceTranscriptOffline: () -> Unit = {},
    onOpenDraftReview: (String) -> Unit = {},
    onRetryDraft: (String) -> Unit = {},
    onConvertDraftToManualReview: (String) -> Unit = {},
    onDeleteDraft: (String) -> Unit = {},
    resolveThumbnailFile: ((String) -> File?)? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_ai_capture"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AI 智能记账",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("ai_capture_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenAiSettings,
                        modifier = Modifier.testTag("ai_capture_open_settings_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "AI 服务配置",
                            tint = MaterialTheme.colorScheme.primary,
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
                .testTag("ai_capture_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "ai_capture_hero") {
                AiCaptureStatusHeroCard(
                    uiState = uiState,
                    onOpenAiSettings = onOpenAiSettings,
                )
            }

            item(key = "ai_capture_tabs") {
                AiCaptureTabSelector(
                    activeTab = uiState.activeTab,
                    onSelectTab = onSelectTab,
                )
            }

            item(key = "ai_capture_active_panel") {
                when (uiState.activeTab) {
                    AiCaptureTab.TEXT -> TextCaptureCard(
                        uiState = uiState,
                        onTextInputChange = onTextInputChange,
                        onApplyTextExample = onApplyTextExample,
                        onSubmitTextOnline = onSubmitTextOnline,
                        onSaveTextOffline = onSaveTextOffline,
                    )

                    AiCaptureTab.IMAGE -> ImageCaptureCard(
                        uiState = uiState,
                        onLaunchCamera = onLaunchCamera,
                        onLaunchGallery = onLaunchGallery,
                        onRemoveImage = onRemoveImage,
                        onImageHintChange = onImageHintChange,
                        onSubmitImagesOnline = onSubmitImagesOnline,
                        onSaveImagesOffline = onSaveImagesOffline,
                        resolveThumbnailFile = resolveThumbnailFile,
                    )

                    AiCaptureTab.VOICE -> VoiceCaptureCard(
                        uiState = uiState,
                        onSelectVoiceEngineMode = onSelectVoiceEngineMode,
                        onVoiceTranscriptChange = onVoiceTranscriptChange,
                        onTriggerSystemVoice = onTriggerSystemVoice,
                        onToggleCustomVoiceRecording = onToggleCustomVoiceRecording,
                        onSubmitVoiceTranscriptOnline = onSubmitVoiceTranscriptOnline,
                        onSaveVoiceTranscriptOffline = onSaveVoiceTranscriptOffline,
                    )
                }
            }

            item(key = "ai_draft_inbox_header") {
                DropletSectionHeader(
                    title = "待处理草稿箱",
                    trailing = if (uiState.activeDraftCount > 0) {
                        "${uiState.activeDraftCount} 条待处理"
                    } else {
                        "已清空"
                    },
                    modifier = Modifier.testTag("ai_draft_inbox_header"),
                )
            }

            if (uiState.activeDrafts.isEmpty()) {
                item(key = "ai_draft_inbox_empty") {
                    DropletCard(
                        modifier = Modifier.testTag("ai_draft_inbox_empty"),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = DropletIcons.Inbox,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "暂无待处理草稿",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "离线暂存、解析失败或待确认的草稿都会保存在这里，不会丢失。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = uiState.activeDrafts,
                    key = { _, item -> "draft_${item.id}" },
                ) { index, draft ->
                    AiDraftInboxCard(
                        index = index,
                        draft = draft,
                        isRetrying = uiState.retryingDraftId == draft.id,
                        onOpenReview = { onOpenDraftReview(draft.id) },
                        onRetry = { onRetryDraft(draft.id) },
                        onManualEdit = { onConvertDraftToManualReview(draft.id) },
                        onDelete = { onDeleteDraft(draft.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AiCaptureStatusHeroCard(
    uiState: AiCaptureUiState,
    onOpenAiSettings: () -> Unit,
) {
    val activeSlot = when (uiState.activeTab) {
        AiCaptureTab.IMAGE -> AiEndpointSlot.VISION
        AiCaptureTab.TEXT -> AiEndpointSlot.TEXT
        AiCaptureTab.VOICE -> if (uiState.aiServiceConfig.voiceEngineMode == VoiceEngineMode.CUSTOM_AI) {
            AiEndpointSlot.VOICE
        } else {
            AiEndpointSlot.TEXT
        }
    }
    val resolvedMeta = uiState.aiServiceConfig.resolveSlot(activeSlot)
    val isReady = resolvedMeta.isConfigured
    val semantics = DropletTheme.semanticColors

    DropletHeroCard(
        modifier = Modifier.testTag("ai_capture_hero_card"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = DropletIcons.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = if (isReady) "AI 解析已就绪" else "离线草稿模式",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            DropletPillBadge(
                text = if (isReady) resolvedMeta.model else "未配置接口",
                containerColor = if (isReady) semantics.incomeContainer else semantics.warningContainer,
                contentColor = if (isReady) semantics.income else semantics.warning,
                modifier = Modifier
                    .semantics(mergeDescendants = true) {}
                    .testTag("ai_capture_model_badge"),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "隐私最小化保护：仅发送账户/分类/标签名称，不传余额与历史账单；图片自动压缩并剥离 EXIF，所有结果均先确认再复式入账。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
        )
        if (!isReady) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "可先离线暂存草稿，或立即配置 OpenAI Compatible 服务。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onOpenAiSettings,
                    modifier = Modifier.testTag("ai_capture_configure_now_button"),
                ) {
                    Text("去配置")
                }
            }
        }
    }
}

@Composable
private fun AiCaptureTabSelector(
    activeTab: AiCaptureTab,
    onSelectTab: (AiCaptureTab) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_capture_tab_bar"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AiCaptureTab.entries.forEach { tab ->
                val selected = tab == activeTab
                val icon = when (tab) {
                    AiCaptureTab.TEXT -> DropletIcons.AutoAwesome
                    AiCaptureTab.IMAGE -> DropletIcons.PhotoCamera
                    AiCaptureTab.VOICE -> DropletIcons.Mic
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectTab(tab) }
                        .testTag("ai_capture_tab_${tab.routeKey}"),
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextCaptureCard(
    uiState: AiCaptureUiState,
    onTextInputChange: (String) -> Unit,
    onApplyTextExample: (String) -> Unit,
    onSubmitTextOnline: () -> Unit,
    onSaveTextOffline: () -> Unit,
) {
    DropletCard(
        modifier = Modifier.testTag("ai_text_capture_card"),
    ) {
        Text(
            text = "一句话自然语言记账",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "支持一句包含多笔收支或转账，自动匹配你的分类、账户与标签。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.textInput,
            onValueChange = onTextInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_text_input"),
            minLines = 3,
            maxLines = 6,
            label = { Text("输入消费或收支描述") },
            placeholder = { Text("例如：早饭8块微信，中午打车25刷信用卡") },
            shape = RoundedCornerShape(16.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "快捷示例",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AiCaptureViewModel.TEXT_EXAMPLES.forEachIndexed { idx, example ->
                AssistChip(
                    onClick = { onApplyTextExample(example) },
                    label = {
                        Text(
                            text = example,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.testTag("ai_text_example_$idx"),
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onSaveTextOffline,
                enabled = !uiState.isSubmitting && uiState.textInput.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_text_save_offline_button"),
            ) {
                Text("离线暂存草稿")
            }
            Button(
                onClick = onSubmitTextOnline,
                enabled = !uiState.isSubmitting && uiState.textInput.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_text_parse_button"),
            ) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("解析中...")
                } else {
                    Text("AI 智能解析")
                }
            }
        }
    }
}

@Composable
private fun ImageCaptureCard(
    uiState: AiCaptureUiState,
    onLaunchCamera: () -> Unit,
    onLaunchGallery: () -> Unit,
    onRemoveImage: (AiDraftAttachmentRef) -> Unit,
    onImageHintChange: (String) -> Unit,
    onSubmitImagesOnline: () -> Unit,
    onSaveImagesOffline: () -> Unit,
    resolveThumbnailFile: ((String) -> File?)?,
) {
    DropletCard(
        modifier = Modifier.testTag("ai_image_capture_card"),
    ) {
        Text(
            text = "小票拍照与账单截图识别",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "支持单张购物小票汇总、支付截图、多图批量导入、账单长截图自动分段切片，以及微信/相册系统分享直达。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                onClick = onLaunchCamera,
                enabled = !uiState.isProcessingImages && !uiState.isSubmitting,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_image_camera_button"),
            ) {
                Icon(
                    imageVector = DropletIcons.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("拍照识别")
            }
            FilledTonalButton(
                onClick = onLaunchGallery,
                enabled = !uiState.isProcessingImages && !uiState.isSubmitting,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_image_gallery_button"),
            ) {
                Icon(
                    imageVector = DropletIcons.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("相册多选")
            }
        }

        if (uiState.isProcessingImages) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = "正在压缩图片并剥离 EXIF 元数据...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (uiState.selectedAttachments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "已选 ${uiState.selectedAttachments.size} 张图片（已自动剥离 EXIF）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("ai_selected_images_count"),
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.testTag("ai_selected_images_row"),
            ) {
                items(
                    items = uiState.selectedAttachments,
                    key = { it.localRelPath },
                ) { ref ->
                    SelectedReceiptThumbnailCard(
                        attachment = ref,
                        file = resolveThumbnailFile?.invoke(ref.localRelPath),
                        onRemove = { onRemoveImage(ref) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.imageHint,
            onValueChange = onImageHintChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_image_hint_input"),
            singleLine = true,
            label = { Text("补充说明（可选）") },
            placeholder = { Text("如：微信信用卡账单，归类到餐饮") },
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onSaveImagesOffline,
                enabled = uiState.selectedAttachments.isNotEmpty() && !uiState.isSubmitting && !uiState.isProcessingImages,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_image_save_offline_button"),
            ) {
                Text("离线暂存草稿")
            }
            Button(
                onClick = onSubmitImagesOnline,
                enabled = uiState.selectedAttachments.isNotEmpty() && !uiState.isSubmitting && !uiState.isProcessingImages,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_image_parse_button"),
            ) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("识别中...")
                } else {
                    Text("视觉 AI 识别")
                }
            }
        }
    }
}

@Composable
private fun SelectedReceiptThumbnailCard(
    attachment: AiDraftAttachmentRef,
    file: File?,
    onRemove: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = file?.absolutePath) {
        value = if (file != null && file.exists()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                }.getOrNull()
            }
        } else {
            null
        }
    }

    Surface(
        modifier = Modifier
            .width(104.dp)
            .height(126.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "已选票据图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = DropletIcons.ReceiptLong,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${attachment.width}×${attachment.height}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "移除图片",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
            ) {
                Text(
                    text = if (attachment.sliceCount > 1) {
                        "长图 · ${attachment.sliceCount}段"
                    } else {
                        "已去 EXIF"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceCaptureCard(
    uiState: AiCaptureUiState,
    onSelectVoiceEngineMode: (VoiceEngineMode) -> Unit,
    onVoiceTranscriptChange: (String) -> Unit,
    onTriggerSystemVoice: () -> Unit,
    onToggleCustomVoiceRecording: () -> Unit,
    onSubmitVoiceTranscriptOnline: () -> Unit,
    onSaveVoiceTranscriptOffline: () -> Unit,
) {
    val mode = uiState.aiServiceConfig.voiceEngineMode
    DropletCard(
        modifier = Modifier.testTag("ai_voice_capture_card"),
    ) {
        Text(
            text = "语音记账（双模式）",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "可自由切换 Android 系统语音（离线友好）或自定义 AI 语音转写（Whisper /audio/transcriptions），支持一句话拆多笔。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            VoiceEngineMode.entries.forEach { engineMode ->
                FilterChip(
                    selected = mode == engineMode,
                    onClick = { onSelectVoiceEngineMode(engineMode) },
                    label = { Text(engineMode.shortLabel) },
                    modifier = Modifier.testTag("ai_capture_voice_mode_${engineMode.name}"),
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (mode == VoiceEngineMode.ANDROID_SYSTEM) {
            FilledTonalButton(
                onClick = onTriggerSystemVoice,
                enabled = !uiState.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_voice_system_button"),
            ) {
                Icon(
                    imageVector = DropletIcons.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("调起系统语音识别")
            }
        } else {
            val isRecording = uiState.isRecordingVoice
            Button(
                onClick = onToggleCustomVoiceRecording,
                enabled = !uiState.isSubmitting,
                colors = if (isRecording) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_voice_record_button"),
            ) {
                Icon(
                    imageVector = DropletIcons.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isRecording) {
                        "停止录音并 AI 转写（${uiState.recordingElapsedSeconds}s）"
                    } else {
                        "开始应用内录音（AI 语音转写）"
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.voiceTranscript,
            onValueChange = onVoiceTranscriptChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_voice_transcript_input"),
            minLines = 2,
            maxLines = 5,
            label = { Text("语音转写内容（可微调）") },
            placeholder = { Text("语音转写结果将显示在这里，也可以手动补充或修正") },
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onSaveVoiceTranscriptOffline,
                enabled = !uiState.isSubmitting && uiState.voiceTranscript.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_voice_save_offline_button"),
            ) {
                Text("离线暂存草稿")
            }
            Button(
                onClick = onSubmitVoiceTranscriptOnline,
                enabled = !uiState.isSubmitting && uiState.voiceTranscript.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_voice_parse_button"),
            ) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("解析中...")
                } else {
                    Text("AI 解析转写")
                }
            }
        }
    }
}

@Composable
private fun AiDraftInboxCard(
    index: Int,
    draft: AiDraftSummaryItem,
    isRetrying: Boolean,
    onOpenReview: () -> Unit,
    onRetry: () -> Unit,
    onManualEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val semantics = DropletTheme.semanticColors
    val statusColors = when (draft.status) {
        AiDraftStatus.READY -> semantics.incomeContainer to semantics.income
        AiDraftStatus.PENDING_ONLINE -> semantics.warningContainer to semantics.warning
        AiDraftStatus.PARSING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        AiDraftStatus.FAILED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        AiDraftStatus.COMMITTED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    val typeIcon = when (draft.inputType) {
        AiDraftInputType.IMAGE -> DropletIcons.PhotoCamera
        AiDraftInputType.VOICE -> DropletIcons.Mic
        AiDraftInputType.TEXT -> DropletIcons.AutoAwesome
    }

    DropletCard(
        modifier = Modifier.testTag("ai_draft_card_$index"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = typeIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = AiReviewFieldLabels.inputTypeLabel(draft.inputType),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "· ${draft.createdAtLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropletPillBadge(
                text = AiReviewFieldLabels.draftStatusLabel(draft.status, draft.candidateCount),
                containerColor = statusColors.first,
                contentColor = statusColors.second,
                modifier = Modifier
                    .semantics(mergeDescendants = true) {}
                    .testTag("ai_draft_status_badge_$index"),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (!draft.rawTextPreview.isNullOrBlank()) {
            Text(
                text = draft.rawTextPreview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (draft.attachmentCount > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("含 ${draft.attachmentCount} 张去 EXIF 票据图")
                    if (draft.totalSliceCount > draft.attachmentCount) {
                        append("（长图切分为 ${draft.totalSliceCount} 段）")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (draft.status == AiDraftStatus.READY && draft.uncertainCandidateCount > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "其中 ${draft.uncertainCandidateCount} 笔含低置信度或待核对字段",
                style = MaterialTheme.typography.labelSmall,
                color = semantics.warning,
            )
        }
        if (!draft.errorMessage.isNullOrBlank() &&
            (draft.status == AiDraftStatus.FAILED || draft.status == AiDraftStatus.PENDING_ONLINE)
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (draft.status == AiDraftStatus.FAILED) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                } else {
                    semantics.warningContainer.copy(alpha = 0.7f)
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = if (draft.status == AiDraftStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            semantics.warning
                        },
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = draft.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (draft.status == AiDraftStatus.READY) {
                Button(
                    onClick = onOpenReview,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ai_draft_open_review_button_$index"),
                ) {
                    Text("核对并批量入账")
                }
            } else {
                FilledTonalButton(
                    onClick = onRetry,
                    enabled = !isRetrying && draft.status != AiDraftStatus.PARSING,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ai_draft_retry_button_$index"),
                ) {
                    if (isRetrying) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text("重新解析")
                }
                OutlinedButton(
                    onClick = onManualEdit,
                    enabled = !isRetrying,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ai_draft_manual_edit_button_$index"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("手动转草稿")
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("ai_draft_delete_button_$index"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "删除草稿",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
