package io.github.kvtttba.droplet.core.ai

import io.github.kvtttba.droplet.core.security.AiEndpointValidator
import java.net.URI

/**
 * Normalizes user-supplied OpenAI-compatible BaseURLs into canonical root paths supporting:
 * - Bare origins (`https://api.example.invalid` -> `https://api.example.invalid/v1`);
 * - Standard `/v1` bases with or without trailing slashes;
 * - Custom provider prefixes (`/compatible-mode/v1`, `/api/paas/v4`, `/proxy/openai`);
 * - Accidental pasted full endpoint URLs (`/v1/chat/completions`, `/v1/models`, `/v1/audio/transcriptions`).
 */
object OpenAiEndpointUrlNormalizer {

    private val KNOWN_ENDPOINT_SUFFIXES = listOf(
        "/chat/completions",
        "/audio/transcriptions",
        "/models",
    )

    fun normalizeBaseUrl(rawBaseUrl: String): String {
        val validated = AiEndpointValidator.validateBaseUrl(rawBaseUrl).getOrThrow()
        val uri = URI(validated)
        val rawPath = uri.rawPath.orEmpty().trimEnd('/')

        val strippedPath = stripKnownEndpointSuffix(rawPath)
        val effectivePath = if (strippedPath.isEmpty()) "/v1" else strippedPath

        val authority = buildString {
            append(uri.host)
            if (uri.port != -1) {
                append(':').append(uri.port)
            }
        }
        return "${uri.scheme.lowercase()}://$authority$effectivePath"
    }

    fun modelsUrl(rawBaseUrl: String): String =
        "${normalizeBaseUrl(rawBaseUrl)}/models"

    fun chatCompletionsUrl(rawBaseUrl: String): String =
        "${normalizeBaseUrl(rawBaseUrl)}/chat/completions"

    fun audioTranscriptionsUrl(rawBaseUrl: String): String =
        "${normalizeBaseUrl(rawBaseUrl)}/audio/transcriptions"

    private fun stripKnownEndpointSuffix(path: String): String {
        val lower = path.lowercase()
        for (suffix in KNOWN_ENDPOINT_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return path.dropLast(suffix.length).trimEnd('/')
            }
        }
        return path
    }
}

/**
 * Redacts API keys and Bearer authorization headers before returning any diagnostic message to callers or UI.
 */
internal object AiErrorSanitizer {
    private val BEARER_TOKEN_REGEX = Regex("(?i)Bearer\\s+[A-Za-z0-9._\\-+/=]+")

    fun sanitize(rawMessage: String?, secretToRedact: String? = null): String {
        var cleaned = rawMessage?.trim().orEmpty()
        if (cleaned.isEmpty()) return ""
        val trimmedSecret = secretToRedact?.trim().orEmpty()
        if (trimmedSecret.length >= 4) {
            cleaned = cleaned.replace(trimmedSecret, "[REDACTED]")
        }
        cleaned = BEARER_TOKEN_REGEX.replace(cleaned, "Bearer [REDACTED]")
        return cleaned.lineSequence().firstOrNull().orEmpty().take(180)
    }

    fun sanitizeThrowable(error: Throwable, secretToRedact: String? = null): String {
        val name = error::class.simpleName ?: "NetworkError"
        val detail = sanitize(error.message, secretToRedact)
        return if (detail.isEmpty()) name else "$name: $detail"
    }
}
