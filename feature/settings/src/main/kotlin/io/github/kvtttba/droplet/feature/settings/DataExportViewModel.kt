package io.github.kvtttba.droplet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.ExportedFileResult
import io.github.kvtttba.droplet.core.database.repository.LedgerExportRepository
import io.github.kvtttba.droplet.core.domain.export.ExportFormat
import io.github.kvtttba.droplet.core.domain.export.ExportScope
import io.github.kvtttba.droplet.core.domain.export.LedgerExportArtifact
import io.github.kvtttba.droplet.core.domain.export.LedgerExportPreview
import io.github.kvtttba.droplet.core.domain.export.LedgerExportRequest
import io.github.kvtttba.droplet.core.domain.stats.StatsTagFilterMode
import io.github.kvtttba.droplet.core.model.Tag
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DataExportUiState(
    val format: ExportFormat = ExportFormat.XLSX,
    val scope: ExportScope = ExportScope.ALL_TIME,
    val customStartDateText: String = YearMonth.now().atDay(1).toString(),
    val customEndDateText: String = LocalDate.now().toString(),
    val customDateError: String? = null,
    val selectedTagIds: Set<String> = emptySet(),
    val tagFilterMode: StatsTagFilterMode = StatsTagFilterMode.ANY,
    val includeVoided: Boolean = false,
    val availableTags: List<Tag> = emptyList(),
    val preview: LedgerExportPreview? = null,
    val isLoadingPreview: Boolean = true,
    val isExporting: Boolean = false,
    val lastExportedFile: ExportedFileResult? = null,
    val lastExportStatusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val parsedCustomStartDate: LocalDate?
        get() = runCatching { LocalDate.parse(customStartDateText.trim()) }.getOrNull()

    val parsedCustomEndDate: LocalDate?
        get() = runCatching { LocalDate.parse(customEndDateText.trim()) }.getOrNull()

    val canExport: Boolean
        get() = !isExporting && (scope != ExportScope.CUSTOM_RANGE || customDateError == null)
}

private data class DataExportParams(
    val format: ExportFormat = ExportFormat.XLSX,
    val scope: ExportScope = ExportScope.ALL_TIME,
    val customStartDateText: String = YearMonth.now().atDay(1).toString(),
    val customEndDateText: String = LocalDate.now().toString(),
    val selectedTagIds: Set<String> = emptySet(),
    val tagFilterMode: StatsTagFilterMode = StatsTagFilterMode.ANY,
    val includeVoided: Boolean = false,
)

private data class DataExportRuntimeState(
    val isExporting: Boolean = false,
    val lastExportedFile: ExportedFileResult? = null,
    val lastExportStatusMessage: String? = null,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DataExportViewModel @Inject constructor(
    private val exportRepository: LedgerExportRepository,
) : ViewModel() {

    private val paramsFlow = MutableStateFlow(DataExportParams())
    private val runtimeFlow = MutableStateFlow(DataExportRuntimeState())

    val uiState: StateFlow<DataExportUiState> = paramsFlow
        .flatMapLatest { params ->
            val dateError = validateCustomDates(params)
            val request = buildRequest(params)
            combine(
                exportRepository.observeExportPreview(request),
                exportRepository.observeAvailableTags(),
                runtimeFlow,
            ) { preview, tags, runtime ->
                DataExportUiState(
                    format = params.format,
                    scope = params.scope,
                    customStartDateText = params.customStartDateText,
                    customEndDateText = params.customEndDateText,
                    customDateError = dateError,
                    selectedTagIds = params.selectedTagIds,
                    tagFilterMode = params.tagFilterMode,
                    includeVoided = params.includeVoided,
                    availableTags = tags,
                    preview = preview,
                    isLoadingPreview = false,
                    isExporting = runtime.isExporting,
                    lastExportedFile = runtime.lastExportedFile,
                    lastExportStatusMessage = runtime.lastExportStatusMessage,
                    errorMessage = runtime.errorMessage,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = DataExportUiState(),
        )

    fun selectFormat(format: ExportFormat) {
        paramsFlow.update { it.copy(format = format) }
        runtimeFlow.update { it.copy(errorMessage = null) }
    }

    fun selectScope(scope: ExportScope) {
        paramsFlow.update { it.copy(scope = scope) }
        runtimeFlow.update { it.copy(errorMessage = null) }
    }

    fun updateCustomStartDate(text: String) {
        paramsFlow.update { it.copy(customStartDateText = text) }
    }

    fun updateCustomEndDate(text: String) {
        paramsFlow.update { it.copy(customEndDateText = text) }
    }

    fun toggleTag(tagId: String) {
        paramsFlow.update { current ->
            val next = if (tagId in current.selectedTagIds) {
                current.selectedTagIds - tagId
            } else {
                current.selectedTagIds + tagId
            }
            current.copy(selectedTagIds = next)
        }
    }

    fun setTagFilterMode(mode: StatsTagFilterMode) {
        paramsFlow.update { it.copy(tagFilterMode = mode) }
    }

    fun clearTags() {
        paramsFlow.update { it.copy(selectedTagIds = emptySet()) }
    }

    fun setIncludeVoided(include: Boolean) {
        paramsFlow.update { it.copy(includeVoided = include) }
    }

    fun currentRequestOrNull(): LedgerExportRequest? {
        val params = paramsFlow.value
        val err = validateCustomDates(params)
        if (err != null) {
            runtimeFlow.update { it.copy(errorMessage = err) }
            return null
        }
        return buildRequest(params)
    }

    fun exportToLocalDirectory(
        outputDirectory: File,
        onCompleted: (ExportedFileResult) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        val request = currentRequestOrNull() ?: return
        viewModelScope.launch {
            runtimeFlow.update { it.copy(isExporting = true, errorMessage = null) }
            runCatching {
                exportRepository.exportToLocalFile(request, outputDirectory)
            }.onSuccess { result ->
                val msg = "已导出 ${result.fileName}（${result.transactionCount} 笔，${formatBytes(result.sizeBytes)}）"
                runtimeFlow.update {
                    it.copy(
                        isExporting = false,
                        lastExportedFile = result,
                        lastExportStatusMessage = msg,
                        errorMessage = null,
                    )
                }
                onCompleted(result)
            }.onFailure { err ->
                val msg = err.message ?: "导出失败，请稍后重试"
                runtimeFlow.update { it.copy(isExporting = false, errorMessage = msg) }
                onError(msg)
            }
        }
    }

    fun generateExportArtifact(
        onArtifactReady: (LedgerExportArtifact) -> Unit,
        onError: (String) -> Unit = {},
    ) {
        val request = currentRequestOrNull() ?: return
        viewModelScope.launch {
            runtimeFlow.update { it.copy(isExporting = true, errorMessage = null) }
            runCatching {
                exportRepository.exportBytes(request)
            }.onSuccess { artifact ->
                runtimeFlow.update { it.copy(isExporting = false, errorMessage = null) }
                onArtifactReady(artifact)
            }.onFailure { err ->
                val msg = err.message ?: "生成导出内容失败"
                runtimeFlow.update { it.copy(isExporting = false, errorMessage = msg) }
                onError(msg)
            }
        }
    }

    fun recordSafExportSuccess(
        fileName: String,
        transactionCount: Int,
        sizeBytes: Long,
    ) {
        val msg = "已保存至系统文件：$fileName（$transactionCount 笔，${formatBytes(sizeBytes)}）"
        runtimeFlow.update {
            it.copy(
                isExporting = false,
                lastExportStatusMessage = msg,
                errorMessage = null,
            )
        }
    }

    fun recordExportError(message: String) {
        runtimeFlow.update { it.copy(isExporting = false, errorMessage = message) }
    }

    private fun validateCustomDates(params: DataExportParams): String? {
        if (params.scope != ExportScope.CUSTOM_RANGE) return null
        val start = runCatching { LocalDate.parse(params.customStartDateText.trim()) }.getOrNull()
        val end = runCatching { LocalDate.parse(params.customEndDateText.trim()) }.getOrNull()
        return when {
            start == null || end == null -> "请按 YYYY-MM-DD 格式填写起止日期"
            end.isBefore(start) -> "结束日期不能早于开始日期"
            else -> null
        }
    }

    private fun buildRequest(params: DataExportParams): LedgerExportRequest {
        val startDate = if (params.scope == ExportScope.CUSTOM_RANGE) {
            runCatching { LocalDate.parse(params.customStartDateText.trim()) }.getOrNull()
        } else {
            null
        }
        val endDate = if (params.scope == ExportScope.CUSTOM_RANGE) {
            runCatching { LocalDate.parse(params.customEndDateText.trim()) }.getOrNull()
        } else {
            null
        }
        return LedgerExportRequest(
            format = params.format,
            scope = params.scope,
            startDate = startDate,
            endDate = endDate,
            selectedTagIds = params.selectedTagIds,
            tagFilterMode = params.tagFilterMode,
            includeVoided = params.includeVoided,
        )
    }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "${(bytes + 512L) / 1024L} KB"
            else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
