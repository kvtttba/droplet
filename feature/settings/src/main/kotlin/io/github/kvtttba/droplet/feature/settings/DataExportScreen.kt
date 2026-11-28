package io.github.kvtttba.droplet.feature.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kvtttba.droplet.core.domain.export.ExportFormat
import io.github.kvtttba.droplet.core.domain.export.ExportScope
import io.github.kvtttba.droplet.core.domain.stats.StatsTagFilterMode
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.Money
import io.github.kvtttba.droplet.core.ui.component.DropletCard
import io.github.kvtttba.droplet.core.ui.component.DropletHeroCard
import io.github.kvtttba.droplet.core.ui.component.DropletPillBadge
import io.github.kvtttba.droplet.core.ui.component.DropletSectionHeader
import io.github.kvtttba.droplet.core.ui.component.PrivacyAmountText
import io.github.kvtttba.droplet.core.ui.icon.DropletIcons
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.tabularNums
import java.io.File

@Composable
fun DataExportRoute(
    onNavigateBack: () -> Unit,
    onShowMessage: (String) -> Unit,
    viewModel: DataExportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.generateExportArtifact(
            onArtifactReady = { artifact ->
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(artifact.bytes)
                        out.flush()
                    } ?: error("无法打开所选文件的写入流")
                }.onSuccess {
                    viewModel.recordSafExportSuccess(
                        fileName = artifact.fileName,
                        transactionCount = artifact.transactionCount,
                        sizeBytes = artifact.sizeBytes,
                    )
                    onShowMessage("已保存 ${artifact.fileName}")
                }.onFailure { err ->
                    val msg = err.message ?: "写入系统文件失败"
                    viewModel.recordExportError(msg)
                    onShowMessage(msg)
                }
            },
            onError = onShowMessage,
        )
    }

    DataExportScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onSelectFormat = viewModel::selectFormat,
        onSelectScope = viewModel::selectScope,
        onUpdateCustomStartDate = viewModel::updateCustomStartDate,
        onUpdateCustomEndDate = viewModel::updateCustomEndDate,
        onToggleTag = viewModel::toggleTag,
        onSelectTagFilterMode = viewModel::setTagFilterMode,
        onClearTags = viewModel::clearTags,
        onSetIncludeVoided = viewModel::setIncludeVoided,
        onGenerateLocalFile = {
            val exportDir = File(context.filesDir, "exports")
            viewModel.exportToLocalDirectory(
                outputDirectory = exportDir,
                onCompleted = { res ->
                    onShowMessage("已导出 ${res.fileName}（${res.transactionCount} 笔）")
                },
                onError = onShowMessage,
            )
        },
        onSaveViaSaf = {
            val suggested = uiState.preview?.suggestedFileName
                ?: "droplet-ledger.${uiState.format.extension}"
            safLauncher.launch(suggested)
        },
        onGenerateAndShare = {
            val cacheExportDir = File(context.cacheDir, "exports")
            viewModel.exportToLocalDirectory(
                outputDirectory = cacheExportDir,
                onCompleted = { res ->
                    shareExportedFile(
                        context = context,
                        file = res.file,
                        mimeType = res.mimeType,
                        title = "分享 ${res.fileName}",
                        onError = onShowMessage,
                    )
                },
                onError = onShowMessage,
            )
        },
        onShareLastExportedFile = {
            val last = uiState.lastExportedFile ?: return@DataExportScreen
            shareExportedFile(
                context = context,
                file = last.file,
                mimeType = last.mimeType,
                title = "分享 ${last.fileName}",
                onError = onShowMessage,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DataExportScreen(
    uiState: DataExportUiState,
    onNavigateBack: () -> Unit = {},
    onSelectFormat: (ExportFormat) -> Unit = {},
    onSelectScope: (ExportScope) -> Unit = {},
    onUpdateCustomStartDate: (String) -> Unit = {},
    onUpdateCustomEndDate: (String) -> Unit = {},
    onToggleTag: (String) -> Unit = {},
    onSelectTagFilterMode: (StatsTagFilterMode) -> Unit = {},
    onClearTags: () -> Unit = {},
    onSetIncludeVoided: (Boolean) -> Unit = {},
    onGenerateLocalFile: () -> Unit = {},
    onSaveViaSaf: () -> Unit = {},
    onGenerateAndShare: () -> Unit = {},
    onShareLastExportedFile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val semantic = DropletTheme.semanticColors

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_data_export"),
        topBar = {
            TopAppBar(
                title = { Text("数据导出") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("data_export_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("data_export_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Live Export Preview Hero Card
            item(key = "export_preview") {
                val preview = uiState.preview
                DropletHeroCard(modifier = Modifier.testTag("export_preview_card")) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = DropletIcons.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "本地离线导出预览",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        DropletPillBadge(
                            text = uiState.format.shortLabel,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = "预计导出交易",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                            )
                            Text(
                                text = "${preview?.transactionCount ?: 0} 笔",
                                style = MaterialTheme.typography.headlineSmall.tabularNums(),
                                modifier = Modifier.testTag("export_preview_tx_count"),
                            )
                        }
                        Column {
                            Text(
                                text = "复式分录",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                            )
                            Text(
                                text = "${preview?.postingCount ?: 0} 条",
                                style = MaterialTheme.typography.headlineSmall.tabularNums(),
                                modifier = Modifier.testTag("export_preview_posting_count"),
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "区间净支出",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                            )
                            PrivacyAmountText(
                                money = preview?.totalExpenseBase ?: Money.zero(Currency.CNY),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "文件：${preview?.suggestedFileName ?: "droplet-ledger.${uiState.format.extension}"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Format selection cards
            item(key = "export_format_header") {
                DropletSectionHeader(
                    title = "选择导出格式",
                    trailing = "零云端上传 · 纯本地生成",
                )
            }

            item(key = "export_format_cards") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExportFormat.entries.forEach { format ->
                        val selected = uiState.format == format
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { onSelectFormat(format) }
                                .testTag("export_format_${format.name}"),
                            shape = RoundedCornerShape(18.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            },
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = null,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = format.label,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = format.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Scope & Filter section
            item(key = "export_scope_header") {
                DropletSectionHeader(title = "导出范围与筛选")
            }

            item(key = "export_scope_card") {
                DropletCard {
                    Text(
                        text = "时间范围",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ExportScope.entries.forEach { scope ->
                            FilterChip(
                                selected = uiState.scope == scope,
                                onClick = { onSelectScope(scope) },
                                label = { Text(scope.label) },
                                modifier = Modifier.testTag("export_scope_${scope.name}"),
                            )
                        }
                    }

                    if (uiState.scope == ExportScope.CUSTOM_RANGE) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = uiState.customStartDateText,
                                onValueChange = onUpdateCustomStartDate,
                                label = { Text("开始日期") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("export_custom_start_input"),
                            )
                            OutlinedTextField(
                                value = uiState.customEndDateText,
                                onValueChange = onUpdateCustomEndDate,
                                label = { Text("结束日期") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("export_custom_end_input"),
                            )
                        }
                        if (uiState.customDateError != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiState.customDateError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    if (uiState.availableTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "按标签筛选（可选）",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (uiState.selectedTagIds.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StatsTagFilterMode.entries.forEach { mode ->
                                        FilterChip(
                                            selected = uiState.tagFilterMode == mode,
                                            onClick = { onSelectTagFilterMode(mode) },
                                            label = { Text(mode.label) },
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            uiState.availableTags.forEach { tag ->
                                FilterChip(
                                    selected = tag.id in uiState.selectedTagIds,
                                    onClick = { onToggleTag(tag.id) },
                                    label = { Text("#${tag.name}") },
                                    modifier = Modifier.testTag("export_tag_${tag.name}"),
                                )
                            }
                            if (uiState.selectedTagIds.isNotEmpty()) {
                                FilterChip(
                                    selected = false,
                                    onClick = onClearTags,
                                    label = { Text("清空标签") },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "包含已作废流水",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "默认仅导出已入账（POSTED）流水，开启后保留作废审计记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.includeVoided,
                            onCheckedChange = onSetIncludeVoided,
                            modifier = Modifier.testTag("export_switch_include_voided"),
                        )
                    }
                }
            }

            // Error banner if present
            if (uiState.errorMessage != null) {
                item(key = "export_error") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            text = uiState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }

            // Export Actions
            item(key = "export_actions") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onGenerateLocalFile,
                        enabled = uiState.canExport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("export_generate_local_button"),
                    ) {
                        if (uiState.isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(
                                imageVector = DropletIcons.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("立即生成 ${uiState.format.shortLabel} 文件")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onSaveViaSaf,
                            enabled = uiState.canExport,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_save_saf_button"),
                        ) {
                            Text("另存为系统文件")
                        }
                        OutlinedButton(
                            onClick = onGenerateAndShare,
                            enabled = uiState.canExport,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_share_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("生成并分享")
                        }
                    }
                }
            }

            // Latest Export Result Card
            val lastFile = uiState.lastExportedFile
            val statusMessage = uiState.lastExportStatusMessage
            if (lastFile != null || statusMessage != null) {
                item(key = "export_result") {
                    DropletCard(
                        modifier = Modifier.testTag("export_result_card"),
                        containerColor = semantic.incomeContainer.copy(alpha = 0.55f),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(semantic.income),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "导出已完成",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = statusMessage ?: lastFile?.fileName.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("export_result_status"),
                                )
                            }
                        }

                        if (lastFile != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "文件名：${lastFile.fileName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("export_result_filename"),
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "大小：${DataExportViewModel.formatBytes(lastFile.sizeBytes)} · 共 ${lastFile.transactionCount} 笔交易 / ${lastFile.postingCount} 条分录",
                                style = MaterialTheme.typography.labelSmall.tabularNums(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "本机路径：${lastFile.file.absolutePath}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onShareLastExportedFile,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("通过系统分享发送此文件")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shareExportedFile(
    context: Context,
    file: File,
    mimeType: String,
    title: String,
    onError: (String) -> Unit,
) {
    runCatching {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, title)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.onFailure { err ->
        onError(err.message ?: "无法调起系统分享面板")
    }
}
