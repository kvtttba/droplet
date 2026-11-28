package io.github.kvtttba.droplet.share

import android.app.Application
import android.content.Intent
import android.net.Uri
import io.github.kvtttba.droplet.feature.aicapture.AiCaptureTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShareIntentParserTest {

    @Test
    fun parse_singleSharedImageWithTextHint_returnsImagePayload() {
        val imageUri = Uri.parse("content://demo.provider/images/receipt_1.jpg")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            putExtra(Intent.EXTRA_TEXT, "微信支付截图")
        }

        val payload = ShareIntentParser.parse(intent)
        assertNotNull(payload)
        assertEquals(AiCaptureTab.IMAGE, payload!!.preferredTab)
        assertEquals(listOf(imageUri.toString()), payload.imageUriStrings)
        assertEquals("微信支付截图", payload.sharedText)
    }

    @Test
    fun parse_multipleSharedImages_deduplicatesAndCapsAtBatchLimit() {
        val uris = ArrayList<Uri>().apply {
            repeat(12) { idx ->
                add(Uri.parse("content://demo.provider/images/bill_$idx.png"))
            }
            add(Uri.parse("content://demo.provider/images/bill_0.png")) // duplicate
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }

        val payload = ShareIntentParser.parse(intent)
        assertNotNull(payload)
        assertEquals(9, payload!!.imageUriStrings.size)
        assertTrue(payload.hasImages)
    }

    @Test
    fun parse_sharedPlainText_returnsTextPayload() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "  早饭10元微信，地铁4元支付宝  ")
        }

        val payload = ShareIntentParser.parse(intent)
        assertNotNull(payload)
        assertEquals(AiCaptureTab.TEXT, payload!!.preferredTab)
        assertEquals("早饭10元微信，地铁4元支付宝", payload.sharedText)
        assertTrue(payload.imageUriStrings.isEmpty())
    }

    @Test
    fun parse_unsupportedActionsEmptyTextOrNonImageMime_returnsNull() {
        assertNull(ShareIntentParser.parse(null))
        assertNull(ShareIntentParser.parse(Intent(Intent.ACTION_MAIN)))
        assertNull(
            ShareIntentParser.parse(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "   ")
                },
            ),
        )
        assertNull(
            ShareIntentParser.parse(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, Uri.parse("content://demo/a.pdf"))
                },
            ),
        )
    }
}
