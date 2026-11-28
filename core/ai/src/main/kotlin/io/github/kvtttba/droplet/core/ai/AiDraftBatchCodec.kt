package io.github.kvtttba.droplet.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local EXIF-stripped receipt image metadata serialized inside `ai_drafts.attachment_paths_json`.
 */
@Serializable
data class AiDraftAttachmentRef(
    val localRelPath: String,
    val sha256: String,
    val mimeType: String = "image/jpeg",
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val sliceCount: Int = 1,
) {
    init {
        require(localRelPath.isNotBlank()) { "localRelPath must not be blank" }
        require(sha256.isNotBlank()) { "sha256 must not be blank" }
        require(mimeType.isNotBlank()) { "mimeType must not be blank" }
        require(sizeBytes >= 0L) { "sizeBytes must be non-negative, got $sizeBytes" }
        require(sliceCount > 0) { "sliceCount must be positive, got $sliceCount" }
    }
}

/**
 * Encapsulates Kotlinx Serialization encoding/decoding of [AiParsedDraftBatch] and
 * [AiDraftAttachmentRef] lists without leaking `kotlinx.serialization.json.Json` across module boundaries.
 */
object AiDraftBatchCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encodeBatch(batch: AiParsedDraftBatch): String = json.encodeToString(batch)

    fun decodeBatchOrNull(rawJson: String?): AiParsedDraftBatch? {
        val trimmed = rawJson?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            json.decodeFromString<AiParsedDraftBatch>(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    fun encodeAttachments(attachments: List<AiDraftAttachmentRef>): String =
        json.encodeToString(attachments)

    fun decodeAttachments(rawJson: String?): List<AiDraftAttachmentRef> {
        val trimmed = rawJson?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            json.decodeFromString<List<AiDraftAttachmentRef>>(trimmed)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
