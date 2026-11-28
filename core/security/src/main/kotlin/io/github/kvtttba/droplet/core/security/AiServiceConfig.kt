package io.github.kvtttba.droplet.core.security

import java.net.URI

/**
 * Capability slot for OpenAI-compatible AI endpoint configuration.
 * [DEFAULT] is the global fallback; [VISION], [TEXT], and [VOICE] can optionally override it.
 */
enum class AiEndpointSlot(
    val storageKey: String,
    val label: String,
    val description: String,
    val aadContext: String,
) {
    DEFAULT(
        storageKey = "DEFAULT",
        label = "默认全局配置",
        description = "未单独覆盖的 AI 能力将统一使用此端点与模型",
        aadContext = "ai_api_key:DEFAULT",
    ),
    VISION(
        storageKey = "VISION",
        label = "视觉模型（图片）",
        description = "用于消费小票、支付截图与账单长图识别",
        aadContext = "ai_api_key:VISION",
    ),
    TEXT(
        storageKey = "TEXT",
        label = "文本模型（一句话 / 拆单）",
        description = "用于自然语言一句话记账与语音转写后的多笔拆单",
        aadContext = "ai_api_key:TEXT",
    ),
    VOICE(
        storageKey = "VOICE",
        label = "语音转写模型（音频接口）",
        description = "用于 OpenAI Whisper 兼容 /audio/transcriptions 音频转写",
        aadContext = "ai_api_key:VOICE",
    ),
    ;

    val isOverrideSlot: Boolean
        get() = this != DEFAULT

    companion object {
        val OVERRIDE_SLOTS: List<AiEndpointSlot> = listOf(VISION, TEXT, VOICE)

        fun fromStorageKey(raw: String?): AiEndpointSlot? {
            if (raw.isNullOrBlank()) return null
            val normalized = raw.trim().uppercase()
            return entries.firstOrNull { it.storageKey == normalized }
        }
    }
}

/**
 * Voice-to-text transcription strategy for voice bookkeeping.
 */
enum class VoiceEngineMode(
    val storageKey: String,
    val label: String,
    val shortLabel: String,
    val description: String,
) {
    ANDROID_SYSTEM(
        storageKey = "ANDROID_SYSTEM",
        label = "Android 系统语音输入",
        shortLabel = "系统语音",
        description = "使用设备自带系统语音输入转文字后再交给文本模型拆单，离线可用且零额外模型体积",
    ),
    CUSTOM_AI(
        storageKey = "CUSTOM_AI",
        label = "自定义 AI 语音接口",
        shortLabel = "AI 语音",
        description = "通过已配置的 OpenAI Compatible 语音端点（/audio/transcriptions）转写音频后再拆单",
    ),
    ;

    companion object {
        val DEFAULT: VoiceEngineMode = ANDROID_SYSTEM

        fun fromStorageKey(raw: String?): VoiceEngineMode {
            if (raw.isNullOrBlank()) return DEFAULT
            val normalized = raw.trim().uppercase()
            return entries.firstOrNull { it.storageKey == normalized } ?: DEFAULT
        }
    }
}

/**
 * Safe masking helper for displaying non-sensitive credential presence previews in UI.
 * Never returns the full credential.
 */
object AiSecretMasker {
    private const val MIN_LENGTH_FOR_SUFFIX_MASK = 8
    private const val VISIBLE_SUFFIX_LENGTH = 4

    fun mask(rawSecret: String): String {
        val trimmed = rawSecret.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.length >= MIN_LENGTH_FOR_SUFFIX_MASK) {
            "••••${trimmed.takeLast(VISIBLE_SUFFIX_LENGTH)}"
        } else {
            "••••••••"
        }
    }
}

/**
 * Validation utilities for OpenAI-compatible endpoint base URLs.
 * Rejects non-HTTP(S) schemes and embedded credentials to protect public-repo & device hygiene.
 */
object AiEndpointValidator {
    fun validateBaseUrl(raw: String): Result<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入 API BaseURL"))
        }
        if (trimmed.any { it.isWhitespace() }) {
            return Result.failure(IllegalArgumentException("BaseURL 不能包含空白字符"))
        }
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("BaseURL 格式无效，请输入有效的 http(s) 地址"))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.failure(IllegalArgumentException("BaseURL 必须以 http:// 或 https:// 开头"))
        }
        if (!uri.userInfo.isNullOrEmpty() || trimmed.substringAfter("://").substringBefore("/").contains("@")) {
            return Result.failure(IllegalArgumentException("为保障安全，BaseURL 禁止内嵌账号密码"))
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("BaseURL 缺少有效主机名"))
        }
        if (!uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) {
            return Result.failure(IllegalArgumentException("BaseURL 无需包含查询参数或锚点"))
        }
        return Result.success(trimmed.trimEnd('/'))
    }
}

/**
 * Public, redacted metadata view of a single AI endpoint slot.
 * Plaintext API keys are never stored inside this state object.
 */
data class AiEndpointConfig(
    val slot: AiEndpointSlot,
    val overrideEnabled: Boolean = slot == AiEndpointSlot.DEFAULT,
    val baseUrl: String = "",
    val model: String = "",
    val hasApiKey: Boolean = false,
    val apiKeyMasked: String = "",
    val isKeyCorrupted: Boolean = false,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && hasApiKey && !isKeyCorrupted

    override fun toString(): String =
        "AiEndpointConfig(slot=$slot, overrideEnabled=$overrideEnabled, baseUrl=$baseUrl, " +
            "model=$model, hasApiKey=$hasApiKey, isKeyCorrupted=$isKeyCorrupted, apiKey=[REDACTED])"

    companion object {
        fun empty(slot: AiEndpointSlot): AiEndpointConfig = AiEndpointConfig(
            slot = slot,
            overrideEnabled = slot == AiEndpointSlot.DEFAULT,
        )
    }
}

/**
 * Effective endpoint metadata after applying slot override or falling back to [AiEndpointSlot.DEFAULT].
 * Plaintext API keys are never exposed here.
 */
data class AiEffectiveEndpointMetadata(
    val requestedSlot: AiEndpointSlot,
    val effectiveSlot: AiEndpointSlot,
    val baseUrl: String,
    val model: String,
    val hasApiKey: Boolean,
    val apiKeySourceSlot: AiEndpointSlot?,
    val apiKeyMasked: String,
    val isUsingDefaultFallback: Boolean,
    val isKeyCorrupted: Boolean = false,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && hasApiKey && !isKeyCorrupted

    override fun toString(): String =
        "AiEffectiveEndpointMetadata(requestedSlot=$requestedSlot, effectiveSlot=$effectiveSlot, " +
            "baseUrl=$baseUrl, model=$model, hasApiKey=$hasApiKey, apiKeySourceSlot=$apiKeySourceSlot, " +
            "isUsingDefaultFallback=$isUsingDefaultFallback, apiKey=[REDACTED])"
}

/**
 * Aggregated AI service configuration persisted locally (with API keys encrypted via Android Keystore).
 * [toString] is strictly redacted.
 */
data class AiServiceConfig(
    val defaultEndpoint: AiEndpointConfig = AiEndpointConfig.empty(AiEndpointSlot.DEFAULT),
    val visionEndpoint: AiEndpointConfig = AiEndpointConfig.empty(AiEndpointSlot.VISION),
    val textEndpoint: AiEndpointConfig = AiEndpointConfig.empty(AiEndpointSlot.TEXT),
    val voiceEndpoint: AiEndpointConfig = AiEndpointConfig.empty(AiEndpointSlot.VOICE),
    val voiceEngineMode: VoiceEngineMode = VoiceEngineMode.DEFAULT,
) {
    fun endpointFor(slot: AiEndpointSlot): AiEndpointConfig = when (slot) {
        AiEndpointSlot.DEFAULT -> defaultEndpoint
        AiEndpointSlot.VISION -> visionEndpoint
        AiEndpointSlot.TEXT -> textEndpoint
        AiEndpointSlot.VOICE -> voiceEndpoint
    }

    /**
     * Resolves the effective endpoint metadata for [slot].
     * - When an override slot is disabled, falls back entirely to [defaultEndpoint].
     * - When an override slot is enabled, non-blank override fields take precedence and blank
     *   `baseUrl` / `model` / missing `apiKey` automatically inherit from [defaultEndpoint].
     */
    fun resolveSlot(slot: AiEndpointSlot): AiEffectiveEndpointMetadata {
        if (slot == AiEndpointSlot.DEFAULT) {
            return AiEffectiveEndpointMetadata(
                requestedSlot = AiEndpointSlot.DEFAULT,
                effectiveSlot = AiEndpointSlot.DEFAULT,
                baseUrl = defaultEndpoint.baseUrl,
                model = defaultEndpoint.model,
                hasApiKey = defaultEndpoint.hasApiKey && !defaultEndpoint.isKeyCorrupted,
                apiKeySourceSlot = if (defaultEndpoint.hasApiKey && !defaultEndpoint.isKeyCorrupted) {
                    AiEndpointSlot.DEFAULT
                } else {
                    null
                },
                apiKeyMasked = defaultEndpoint.apiKeyMasked,
                isUsingDefaultFallback = false,
                isKeyCorrupted = defaultEndpoint.isKeyCorrupted,
            )
        }
        val override = endpointFor(slot)
        if (!override.overrideEnabled) {
            return AiEffectiveEndpointMetadata(
                requestedSlot = slot,
                effectiveSlot = AiEndpointSlot.DEFAULT,
                baseUrl = defaultEndpoint.baseUrl,
                model = defaultEndpoint.model,
                hasApiKey = defaultEndpoint.hasApiKey && !defaultEndpoint.isKeyCorrupted,
                apiKeySourceSlot = if (defaultEndpoint.hasApiKey && !defaultEndpoint.isKeyCorrupted) {
                    AiEndpointSlot.DEFAULT
                } else {
                    null
                },
                apiKeyMasked = defaultEndpoint.apiKeyMasked,
                isUsingDefaultFallback = true,
                isKeyCorrupted = defaultEndpoint.isKeyCorrupted,
            )
        }

        val effectiveBaseUrl = override.baseUrl.ifBlank { defaultEndpoint.baseUrl }
        val effectiveModel = override.model.ifBlank { defaultEndpoint.model }
        val useOverrideKey = override.hasApiKey && !override.isKeyCorrupted
        val useDefaultKey = !useOverrideKey && !override.isKeyCorrupted &&
            defaultEndpoint.hasApiKey && !defaultEndpoint.isKeyCorrupted
        val apiKeySource = when {
            useOverrideKey -> slot
            useDefaultKey -> AiEndpointSlot.DEFAULT
            else -> null
        }
        val effectiveMasked = when (apiKeySource) {
            slot -> override.apiKeyMasked
            AiEndpointSlot.DEFAULT -> defaultEndpoint.apiKeyMasked
            else -> ""
        }
        val isCorrupted = override.isKeyCorrupted || (!useOverrideKey && defaultEndpoint.isKeyCorrupted)

        return AiEffectiveEndpointMetadata(
            requestedSlot = slot,
            effectiveSlot = slot,
            baseUrl = effectiveBaseUrl,
            model = effectiveModel,
            hasApiKey = apiKeySource != null,
            apiKeySourceSlot = apiKeySource,
            apiKeyMasked = effectiveMasked,
            isUsingDefaultFallback = false,
            isKeyCorrupted = isCorrupted,
        )
    }

    val enabledOverrideCount: Int
        get() = AiEndpointSlot.OVERRIDE_SLOTS.count { endpointFor(it).overrideEnabled }

    val hasAnyConfiguredEndpoint: Boolean
        get() = defaultEndpoint.isConfigured ||
            AiEndpointSlot.OVERRIDE_SLOTS.any { resolveSlot(it).isConfigured }

    fun formatSummary(): String {
        if (!hasAnyConfiguredEndpoint) {
            return "未配置 · 点击设置 OpenAI Compatible 接口"
        }
        val parts = mutableListOf<String>()
        if (defaultEndpoint.isConfigured) {
            parts += "已配置 ${defaultEndpoint.model}"
        } else {
            parts += "已配置独立槽位"
        }
        if (enabledOverrideCount > 0) {
            parts += "$enabledOverrideCount 项覆盖"
        }
        parts += voiceEngineMode.shortLabel
        return parts.joinToString(" · ")
    }

    override fun toString(): String =
        "AiServiceConfig(default=$defaultEndpoint, vision=$visionEndpoint, text=$textEndpoint, " +
            "voice=$voiceEndpoint, voiceEngineMode=$voiceEngineMode)"
}

/**
 * In-memory resolved endpoint containing the decrypted API key strictly for executing an active
 * network request. Never persisted or logged.
 */
class AiResolvedEndpoint(
    val requestedSlot: AiEndpointSlot,
    val effectiveSlot: AiEndpointSlot,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    init {
        require(baseUrl.isNotBlank()) { "Resolved baseUrl must not be blank" }
        require(model.isNotBlank()) { "Resolved model must not be blank" }
        require(apiKey.isNotBlank()) { "Resolved apiKey must not be blank" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AiResolvedEndpoint) return false
        return requestedSlot == other.requestedSlot &&
            effectiveSlot == other.effectiveSlot &&
            baseUrl == other.baseUrl &&
            model == other.model &&
            apiKey == other.apiKey
    }

    override fun hashCode(): Int {
        var result = requestedSlot.hashCode()
        result = 31 * result + effectiveSlot.hashCode()
        result = 31 * result + baseUrl.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + apiKey.hashCode()
        return result
    }

    override fun toString(): String =
        "AiResolvedEndpoint(requestedSlot=$requestedSlot, effectiveSlot=$effectiveSlot, " +
            "baseUrl=$baseUrl, model=$model, apiKey=[REDACTED])"
}

/**
 * Explicit update action for a slot's encrypted API key.
 */
sealed interface AiApiKeyUpdateAction {
    data object KeepExisting : AiApiKeyUpdateAction

    data object Clear : AiApiKeyUpdateAction

    class Replace(val plaintextApiKey: String) : AiApiKeyUpdateAction {
        override fun equals(other: Any?): Boolean =
            other is Replace && plaintextApiKey == other.plaintextApiKey

        override fun hashCode(): Int = plaintextApiKey.hashCode()

        override fun toString(): String = "Replace(plaintextApiKey=[REDACTED])"
    }
}

/**
 * Input payload for persisting a single AI endpoint slot.
 */
data class AiEndpointSaveInput(
    val slot: AiEndpointSlot,
    val overrideEnabled: Boolean = true,
    val baseUrl: String,
    val model: String,
    val apiKeyAction: AiApiKeyUpdateAction = AiApiKeyUpdateAction.KeepExisting,
) {
    override fun toString(): String =
        "AiEndpointSaveInput(slot=$slot, overrideEnabled=$overrideEnabled, " +
            "baseUrl=$baseUrl, model=$model, apiKeyAction=$apiKeyAction)"
}
