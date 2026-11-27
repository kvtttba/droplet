package io.github.kvtttba.droplet.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kvtttba.droplet.core.database.repository.MarketDataRepository
import io.github.kvtttba.droplet.core.database.repository.MarketSyncReport
import io.github.kvtttba.droplet.core.database.repository.MarketSyncStatus
import io.github.kvtttba.droplet.core.network.NetworkProxyConfig
import io.github.kvtttba.droplet.core.network.NetworkProxyType
import io.github.kvtttba.droplet.core.security.MarketSyncInterval
import io.github.kvtttba.droplet.core.security.MarketSyncPreferencesRepository
import io.github.kvtttba.droplet.core.security.NetworkProxyPreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MarketSyncSettingsUiState(
    val refreshInterval: MarketSyncInterval = MarketSyncInterval.DEFAULT,
    val wifiOnly: Boolean = false,
    val savedProxyConfig: NetworkProxyConfig = NetworkProxyConfig.NONE,
    val draftProxyType: NetworkProxyType = NetworkProxyType.NONE,
    val draftProxyHost: String = "",
    val draftProxyPortText: String = "",
    val proxyValidationError: String? = null,
    val isRefreshingNow: Boolean = false,
    val lastUpdatedAtEpochMs: Long? = null,
    val cachedRateCount: Int = 0,
    val cachedQuoteCount: Int = 0,
    val lastRefreshSummary: String? = null,
    val userMessage: String? = null,
)

private data class ProxyDraftState(
    val type: NetworkProxyType = NetworkProxyType.NONE,
    val host: String = "",
    val portText: String = "",
    val validationError: String? = null,
    val isDirty: Boolean = false,
) {
    companion object {
        fun fromConfig(config: NetworkProxyConfig): ProxyDraftState = ProxyDraftState(
            type = config.type,
            host = if (config.type == NetworkProxyType.NONE) "" else config.host,
            portText = if (config.type == NetworkProxyType.NONE || config.port <= 0) "" else config.port.toString(),
            validationError = null,
            isDirty = false,
        )
    }
}

private data class MarketSyncRuntimeState(
    val isRefreshingNow: Boolean = false,
    val lastRefreshSummary: String? = null,
    val userMessage: String? = null,
)

private data class CachedMarketMeta(
    val lastUpdatedAtEpochMs: Long? = null,
    val cachedRateCount: Int = 0,
    val cachedQuoteCount: Int = 0,
)

@HiltViewModel
class MarketSyncSettingsViewModel @Inject constructor(
    private val syncPreferencesRepository: MarketSyncPreferencesRepository,
    private val proxyPreferencesRepository: NetworkProxyPreferencesRepository,
    private val marketDataRepository: MarketDataRepository,
) : ViewModel() {

    private val proxyDraftFlow = MutableStateFlow(ProxyDraftState())
    private val runtimeFlow = MutableStateFlow(MarketSyncRuntimeState())

    init {
        viewModelScope.launch {
            proxyPreferencesRepository.proxyConfig.collect { saved ->
                proxyDraftFlow.update { current ->
                    if (current.isDirty) current else ProxyDraftState.fromConfig(saved)
                }
            }
        }
    }

    private val cachedMarketMetaFlow = combine(
        marketDataRepository.observeExchangeRates(),
        marketDataRepository.observeMarketQuotes(),
    ) { rates, quotes ->
        val latestRateAt = rates.maxOfOrNull { it.fetchedAt }
        val latestQuoteAt = quotes.maxOfOrNull { it.fetchedAt }
        CachedMarketMeta(
            lastUpdatedAtEpochMs = listOfNotNull(latestRateAt, latestQuoteAt).maxOrNull(),
            cachedRateCount = rates.size,
            cachedQuoteCount = quotes.size,
        )
    }

    val uiState: StateFlow<MarketSyncSettingsUiState> = combine(
        syncPreferencesRepository.preferences,
        proxyPreferencesRepository.proxyConfig,
        proxyDraftFlow,
        cachedMarketMetaFlow,
        runtimeFlow,
    ) { syncPrefs, savedProxy, draft, cacheMeta, runtime ->
        MarketSyncSettingsUiState(
            refreshInterval = syncPrefs.refreshInterval,
            wifiOnly = syncPrefs.wifiOnly,
            savedProxyConfig = savedProxy,
            draftProxyType = draft.type,
            draftProxyHost = draft.host,
            draftProxyPortText = draft.portText,
            proxyValidationError = draft.validationError,
            isRefreshingNow = runtime.isRefreshingNow,
            lastUpdatedAtEpochMs = cacheMeta.lastUpdatedAtEpochMs,
            cachedRateCount = cacheMeta.cachedRateCount,
            cachedQuoteCount = cacheMeta.cachedQuoteCount,
            lastRefreshSummary = runtime.lastRefreshSummary,
            userMessage = runtime.userMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MarketSyncSettingsUiState(),
    )

    fun setRefreshInterval(interval: MarketSyncInterval) {
        viewModelScope.launch {
            syncPreferencesRepository.setRefreshInterval(interval)
            runtimeFlow.update {
                it.copy(
                    userMessage = if (interval.isPeriodicEnabled) {
                        "已切换后台自动刷新：${interval.label}"
                    } else {
                        "已关闭后台定时刷新，改为仅手动刷新"
                    },
                )
            }
        }
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            syncPreferencesRepository.setWifiOnly(wifiOnly)
            runtimeFlow.update {
                it.copy(
                    userMessage = if (wifiOnly) {
                        "已开启仅 Wi-Fi 后台同步"
                    } else {
                        "已允许移动网络与 Wi-Fi 后台同步"
                    },
                )
            }
        }
    }

    fun onDraftProxyTypeChange(type: NetworkProxyType) {
        proxyDraftFlow.update {
            it.copy(
                type = type,
                validationError = null,
                isDirty = true,
            )
        }
    }

    fun onDraftProxyHostChange(host: String) {
        proxyDraftFlow.update {
            it.copy(
                host = host,
                validationError = null,
                isDirty = true,
            )
        }
    }

    fun onDraftProxyPortChange(portText: String) {
        val digitsOnly = portText.filter { ch -> ch.isDigit() }.take(5)
        proxyDraftFlow.update {
            it.copy(
                portText = digitsOnly,
                validationError = null,
                isDirty = true,
            )
        }
    }

    fun saveProxyConfig() {
        val draft = proxyDraftFlow.value
        if (draft.type == NetworkProxyType.NONE) {
            clearProxyConfig()
            return
        }

        val host = draft.host.trim()
        if (host.isEmpty()) {
            proxyDraftFlow.update { it.copy(validationError = "请输入代理主机名或 IP 地址") }
            return
        }
        if (host.contains("://")) {
            proxyDraftFlow.update { it.copy(validationError = "请仅填写裸主机名或 IP，不要包含 http:// 或 socks:// 前缀") }
            return
        }
        if (host.contains("@")) {
            proxyDraftFlow.update { it.copy(validationError = "为保障安全，代理主机不支持内嵌账号密码") }
            return
        }
        if (!NetworkProxyConfig.isValidHost(host)) {
            proxyDraftFlow.update { it.copy(validationError = "代理主机格式无效，请检查是否包含空格或非法路径字符") }
            return
        }

        val port = draft.portText.toIntOrNull()
        if (port == null || port !in NetworkProxyConfig.MIN_PORT..NetworkProxyConfig.MAX_PORT) {
            proxyDraftFlow.update {
                it.copy(validationError = "代理端口需在 ${NetworkProxyConfig.MIN_PORT}–${NetworkProxyConfig.MAX_PORT} 之间")
            }
            return
        }

        val config = NetworkProxyConfig(
            type = draft.type,
            host = host,
            port = port,
        )
        viewModelScope.launch {
            runCatching {
                proxyPreferencesRepository.saveProxyConfig(config)
            }.onSuccess {
                proxyDraftFlow.value = ProxyDraftState.fromConfig(config.validated())
                runtimeFlow.update {
                    it.copy(userMessage = "已保存 ${config.type.storageKey} 代理配置")
                }
            }.onFailure { error ->
                proxyDraftFlow.update {
                    it.copy(validationError = error.message ?: "代理配置校验失败")
                }
            }
        }
    }

    fun clearProxyConfig() {
        viewModelScope.launch {
            proxyPreferencesRepository.clearProxyConfig()
            proxyDraftFlow.value = ProxyDraftState.fromConfig(NetworkProxyConfig.NONE)
            runtimeFlow.update { it.copy(userMessage = "已恢复默认直连模式") }
        }
    }

    fun refreshNow() {
        if (runtimeFlow.value.isRefreshingNow) return
        viewModelScope.launch {
            runtimeFlow.update { it.copy(isRefreshingNow = true) }
            runCatching {
                marketDataRepository.refreshAll(
                    forceRefresh = true,
                    overwriteManualOverrides = false,
                )
            }.onSuccess { report ->
                val summary = formatRefreshReport(report)
                runtimeFlow.update {
                    it.copy(
                        isRefreshingNow = false,
                        lastRefreshSummary = summary,
                        userMessage = summary,
                    )
                }
            }.onFailure {
                val fallbackMsg = "行情刷新异常，已完整保留上次缓存数据"
                runtimeFlow.update {
                    it.copy(
                        isRefreshingNow = false,
                        lastRefreshSummary = fallbackMsg,
                        userMessage = fallbackMsg,
                    )
                }
            }
        }
    }

    fun clearUserMessage() {
        runtimeFlow.update { it.copy(userMessage = null) }
    }

    companion object {
        fun formatRefreshReport(report: MarketSyncReport): String {
            val fxPart = when (report.fxSummary.status) {
                MarketSyncStatus.UPDATED -> "汇率已更新"
                MarketSyncStatus.THROTTLED -> "汇率已最新"
                MarketSyncStatus.FAILED_CACHE_RETAINED -> "汇率拉取失败(保留缓存)"
                else -> "汇率无变更"
            }
            val quoteSummary = report.quoteSummary
            val quotePart = when (quoteSummary.status) {
                MarketSyncStatus.UPDATED -> "更新 ${quoteSummary.updatedAssetIds.size} 项行情"
                MarketSyncStatus.PARTIAL_SUCCESS ->
                    "行情更新 ${quoteSummary.updatedAssetIds.size} 项，${quoteSummary.failures.size} 项失败已保留缓存"
                MarketSyncStatus.FAILED_CACHE_RETAINED ->
                    "行情拉取失败（${quoteSummary.failures.size} 项），已保留缓存净值"
                MarketSyncStatus.NO_TARGETS -> "暂无投资标的"
                MarketSyncStatus.SKIPPED_ONLY -> "全部为本地计息或手动估值标的"
                MarketSyncStatus.THROTTLED -> "行情命中 5 分钟节流"
            }
            return "$fxPart · $quotePart"
        }
    }
}
