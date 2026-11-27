package io.github.kvtttba.droplet.core.model

import kotlinx.serialization.Serializable

/**
 * Local receipt or invoice image/document attachment linked to a [Transaction].
 */
@Serializable
data class Attachment(
    val id: String,
    val transactionId: String,
    val localRelPath: String,
    val sha256: String,
    val mimeType: String,
    val sizeBytes: Long,
    val syncToWebdav: Boolean = false,
    val createdAt: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "Attachment id must not be blank" }
        require(transactionId.isNotBlank()) { "Attachment transactionId must not be blank" }
        require(localRelPath.isNotBlank()) { "Attachment localRelPath must not be blank" }
        require(sha256.isNotBlank()) { "Attachment sha256 must not be blank" }
        require(mimeType.isNotBlank()) { "Attachment mimeType must not be blank" }
        require(sizeBytes >= 0L) { "Attachment sizeBytes must be non-negative, got $sizeBytes" }
    }
}
