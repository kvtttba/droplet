package io.github.kvtttba.droplet.core.ai

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class ReceiptImageProcessorTest {

    private lateinit var tempRoot: File
    private lateinit var processor: AndroidReceiptImageProcessor

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        tempRoot = File(app.cacheDir, "receipt_processor_test_${System.nanoTime()}").apply { mkdirs() }
        processor = AndroidReceiptImageProcessor(
            context = app,
            storageRootDir = tempRoot,
        )
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun processBytes_downscalesLargeReceiptAndStripsInjectedExifMetadata() = runTest {
        val rawJpeg = createSyntheticJpegBytes(width = 2400, height = 1800)
        val exifPayload = "Exif\u0000\u0000MOCK_CAMERA_MODEL_PRIVATE_GPS_31.2304_121.4737"
            .toByteArray(StandardCharsets.UTF_8)
        val jpegWithExif = injectApp1Segment(rawJpeg, exifPayload)
        assertTrue(containsSubArray(jpegWithExif, "MOCK_CAMERA_MODEL".toByteArray(StandardCharsets.UTF_8)))
        assertTrue(hasJpegApp1Marker(jpegWithExif))

        val processed = processor.processBytes(jpegWithExif)

        assertTrue(processed.attachmentRef.localRelPath.startsWith("receipts/"))
        assertEquals(64, processed.attachmentRef.sha256.length)
        assertEquals(1, processed.attachmentRef.sliceCount)
        assertEquals(1, processed.visionPayloads.size)
        assertTrue(processed.attachmentRef.width <= AndroidReceiptImageProcessor.DEFAULT_MAX_NORMAL_EDGE_PX)
        assertTrue(processed.attachmentRef.height <= AndroidReceiptImageProcessor.DEFAULT_MAX_NORMAL_EDGE_PX)

        val storedFile = processor.resolveLocalFile(processed.attachmentRef.localRelPath)
        assertNotNull(storedFile)
        assertTrue(storedFile!!.exists())
        val storedBytes = storedFile.readBytes()
        assertFalse("EXIF APP1 marker must be stripped", hasJpegApp1Marker(storedBytes))
        assertFalse(
            "EXIF camera/GPS string must not survive compression",
            containsSubArray(storedBytes, "MOCK_CAMERA_MODEL".toByteArray(StandardCharsets.UTF_8)),
        )

        val decodedPayloadBytes = Base64.getDecoder().decode(processed.visionPayloads.first().base64Data)
        assertFalse(hasJpegApp1Marker(decodedPayloadBytes))
    }

    @Test
    fun processBytes_tallBillScreenshot_slicesIntoMultipleReadableChunksAndReloadsPayloads() = runTest {
        val tallJpeg = createSyntheticJpegBytes(width = 900, height = 4200)

        val processed = processor.processBytes(tallJpeg)

        assertTrue("Expected tall screenshot to produce >= 3 slices, got ${processed.attachmentRef.sliceCount}", processed.attachmentRef.sliceCount >= 3)
        assertEquals(processed.attachmentRef.sliceCount, processed.visionPayloads.size)
        assertTrue(processed.attachmentRef.width <= AndroidReceiptImageProcessor.DEFAULT_MAX_TALL_WIDTH_PX)

        val reloaded = processor.loadVisionPayloads(listOf(processed.attachmentRef))
        assertEquals(processed.visionPayloads.size, reloaded.size)
        assertTrue(reloaded.all { it.toDataUri().startsWith("data:image/jpeg;base64,") })
    }

    @Test
    fun resolveLocalFile_blocksPathTraversalAndDeleteRemovesOnlyStoredReceipt() = runTest {
        assertNull(processor.resolveLocalFile("../secrets.txt"))
        assertNull(processor.resolveLocalFile("/etc/passwd"))
        assertNull(processor.resolveLocalFile("other_dir/file.jpg"))

        val processed = processor.processBytes(createSyntheticJpegBytes(width = 400, height = 300))
        val stored = processor.resolveLocalFile(processed.attachmentRef.localRelPath)!!
        assertTrue(stored.exists())

        processor.deleteReceiptFiles(listOf(processed.attachmentRef))
        assertFalse(stored.exists())
    }

    @Test
    fun computeVerticalSliceRanges_coversEntireHeightWithOverlapWithinMaxSlices() {
        val ranges = AndroidReceiptImageProcessor.computeVerticalSliceRanges(
            totalHeight = 4500,
            sliceHeight = 1600,
            overlap = 140,
            maxSlices = 6,
        )
        assertEquals(0, ranges.first().first)
        val last = ranges.last()
        assertEquals(4500, last.first + last.second)
        assertTrue(ranges.size in 3..6)
        for (i in 1 until ranges.size) {
            val prevEnd = ranges[i - 1].first + ranges[i - 1].second
            assertTrue("Adjacent slices must overlap", ranges[i].first < prevEnd)
        }
    }

    private fun createSyntheticJpegBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun injectApp1Segment(jpegBytes: ByteArray, app1Payload: ByteArray): ByteArray {
        require(jpegBytes.size >= 2 && jpegBytes[0] == 0xFF.toByte() && jpegBytes[1] == 0xD8.toByte())
        val segmentLength = app1Payload.size + 2
        val out = ByteArrayOutputStream()
        out.write(0xFF)
        out.write(0xD8)
        out.write(0xFF)
        out.write(0xE1)
        out.write((segmentLength ushr 8) and 0xFF)
        out.write(segmentLength and 0xFF)
        out.write(app1Payload)
        out.write(jpegBytes, 2, jpegBytes.size - 2)
        return out.toByteArray()
    }

    private fun hasJpegApp1Marker(bytes: ByteArray): Boolean {
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xE1.toByte()) {
                return true
            }
        }
        return false
    }

    private fun containsSubArray(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
