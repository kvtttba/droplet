package io.github.kvtttba.droplet.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import io.github.kvtttba.droplet.core.ai.AndroidReceiptImageProcessor
import io.github.kvtttba.droplet.feature.aicapture.PendingAiSharePayload
import java.util.concurrent.atomic.AtomicLong

/**
 * Parses incoming Android system share intents (`ACTION_SEND` for image or plain-text MIME types,
 * and `ACTION_SEND_MULTIPLE` for multiple images) into a normalized [PendingAiSharePayload].
 */
object ShareIntentParser {
    private val sequence = AtomicLong(1L)

    fun parse(intent: Intent?): PendingAiSharePayload? {
        if (intent == null) return null
        val action = intent.action ?: return null
        val mimeType = intent.type?.trim()?.lowercase().orEmpty()

        return when (action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

                if (mimeType.startsWith("image/")) {
                    val uris = extractSingleOrClipUris(intent)
                    if (uris.isEmpty()) return null
                    PendingAiSharePayload(
                        id = sequence.getAndIncrement(),
                        sharedText = sharedText,
                        imageUriStrings = uris.take(AndroidReceiptImageProcessor.MAX_BATCH_IMAGE_COUNT)
                            .map { it.toString() },
                    )
                } else if (mimeType == "text/plain" || mimeType.startsWith("text/")) {
                    if (sharedText == null) return null
                    PendingAiSharePayload(
                        id = sequence.getAndIncrement(),
                        sharedText = sharedText,
                        imageUriStrings = emptyList(),
                    )
                } else {
                    null
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                if (!mimeType.startsWith("image/")) return null
                val uris = extractMultipleOrClipUris(intent)
                if (uris.isEmpty()) return null
                val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                PendingAiSharePayload(
                    id = sequence.getAndIncrement(),
                    sharedText = sharedText,
                    imageUriStrings = uris.take(AndroidReceiptImageProcessor.MAX_BATCH_IMAGE_COUNT)
                        .map { it.toString() },
                )
            }

            else -> null
        }
    }

    private fun extractSingleOrClipUris(intent: Intent): List<Uri> {
        val result = linkedSetOf<Uri>()
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
            result += it
        }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { result += it }
            }
        }
        return result.toList()
    }

    private fun extractMultipleOrClipUris(intent: Intent): List<Uri> {
        val result = linkedSetOf<Uri>()
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.forEach { uri -> result += uri }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { result += it }
            }
        }
        return result.toList()
    }
}
