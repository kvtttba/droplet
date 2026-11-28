package io.github.kvtttba.droplet.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of normalizing, EXIF-stripping, and storing a receipt/bill screenshot in app-private storage.
 */
data class ProcessedReceiptImage(
    val attachmentRef: AiDraftAttachmentRef,
    val visionPayloads: List<AiImagePayload>,
)

/**
 * Processes receipt photos, payment screenshots, batch images, and tall bill screenshots:
 * - Reads orientation from EXIF (in memory only) and normalizes upright pixels;
 * - Safely downscales high-resolution photos to avoid OOM while preserving readable text;
 * - Re-encodes JPEG pixels from raw bitmap so all EXIF privacy metadata (GPS, device, timestamps)
 *   is completely stripped before saving locally or sending to Vision LLMs;
 * - Slices tall bill screenshots into overlapping vertical chunks so Vision models preserve accuracy.
 */
interface ReceiptImageProcessor {
    suspend fun processUri(uri: Uri): ProcessedReceiptImage

    suspend fun processUris(uris: List<Uri>): List<ProcessedReceiptImage>

    suspend fun processBytes(rawBytes: ByteArray): ProcessedReceiptImage

    suspend fun loadVisionPayloads(attachments: List<AiDraftAttachmentRef>): List<AiImagePayload>

    fun resolveLocalFile(localRelPath: String): File?

    suspend fun deleteReceiptFiles(attachments: List<AiDraftAttachmentRef>)
}

class AndroidReceiptImageProcessor(
    private val context: Context,
    private val storageRootDir: File = context.filesDir,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxNormalEdgePx: Int = DEFAULT_MAX_NORMAL_EDGE_PX,
    private val maxTallWidthPx: Int = DEFAULT_MAX_TALL_WIDTH_PX,
    private val maxTallHeightPx: Int = DEFAULT_MAX_TALL_HEIGHT_PX,
    private val tallAspectRatioThreshold: Float = DEFAULT_TALL_ASPECT_RATIO_THRESHOLD,
    private val tallSliceHeightPx: Int = DEFAULT_TALL_SLICE_HEIGHT_PX,
    private val tallSliceOverlapPx: Int = DEFAULT_TALL_SLICE_OVERLAP_PX,
    private val maxSlicesPerImage: Int = DEFAULT_MAX_SLICES_PER_IMAGE,
    private val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
) : ReceiptImageProcessor {

    private val receiptsDir: File
        get() = File(storageRootDir, RECEIPTS_DIR_NAME)

    override suspend fun processUri(uri: Uri): ProcessedReceiptImage = withContext(ioDispatcher) {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            readBoundedBytes(stream, MAX_INPUT_IMAGE_BYTES)
        } ?: throw IOException("无法读取所选图片，请检查图片是否存在或重新选择")
        processBytesInternal(rawBytes)
    }

    override suspend fun processUris(uris: List<Uri>): List<ProcessedReceiptImage> = withContext(ioDispatcher) {
        require(uris.isNotEmpty()) { "请至少选择 1 张图片" }
        require(uris.size <= MAX_BATCH_IMAGE_COUNT) {
            "单次最多选择 $MAX_BATCH_IMAGE_COUNT 张图片"
        }
        uris.map { uri -> processUri(uri) }
    }

    override suspend fun processBytes(rawBytes: ByteArray): ProcessedReceiptImage = withContext(ioDispatcher) {
        processBytesInternal(rawBytes)
    }

    override suspend fun loadVisionPayloads(
        attachments: List<AiDraftAttachmentRef>,
    ): List<AiImagePayload> = withContext(ioDispatcher) {
        val payloads = mutableListOf<AiImagePayload>()
        for (attachment in attachments) {
            val file = resolveLocalFile(attachment.localRelPath)
                ?: throw IOException("本地票据图片路径无效：${attachment.localRelPath}")
            if (!file.exists() || !file.isFile) {
                throw IOException("本地票据图片已缺失，请重新选图识别")
            }
            val fileBytes = file.readBytes()
            val decoded = BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size)
                ?: throw IOException("本地票据图片无法解码，请重新选图")
            try {
                payloads += buildVisionPayloadsFromBitmap(decoded, preEncodedWholeBytes = fileBytes)
            } finally {
                decoded.recycle()
            }
        }
        if (payloads.isEmpty()) {
            throw IOException("未找到可用的图片数据")
        }
        payloads
    }

    override fun resolveLocalFile(localRelPath: String): File? {
        val normalized = localRelPath.trim().replace('\\', '/')
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains("..")) {
            return null
        }
        val expectedPrefix = "$RECEIPTS_DIR_NAME/"
        if (!normalized.startsWith(expectedPrefix)) {
            return null
        }
        val baseCanonical = receiptsDir.canonicalFile
        val target = File(storageRootDir, normalized).canonicalFile
        return if (target.path.startsWith(baseCanonical.path + File.separator)) target else null
    }

    override suspend fun deleteReceiptFiles(attachments: List<AiDraftAttachmentRef>) {
        withContext(ioDispatcher) {
            attachments.forEach { ref ->
                resolveLocalFile(ref.localRelPath)?.let { file ->
                    if (file.exists() && file.isFile) {
                        file.delete()
                    }
                }
            }
        }
    }

    private fun processBytesInternal(rawBytes: ByteArray): ProcessedReceiptImage {
        if (rawBytes.isEmpty()) {
            throw IOException("图片数据为空")
        }
        if (rawBytes.size > MAX_INPUT_IMAGE_BYTES) {
            throw IOException("单张图片体积过大（超过 25 MB），请先裁剪后重试")
        }

        val orientation = readExifOrientation(rawBytes)

        // Pass 1: inspect raw bounds without allocating full pixel buffer
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
        val rawWidth = boundsOpts.outWidth
        val rawHeight = boundsOpts.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) {
            throw IOException("无法识别图片格式，请选择有效的 JPG/PNG/WebP 图片")
        }

        val rotates90 = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        val orientedWidth = if (rotates90) rawHeight else rawWidth
        val orientedHeight = if (rotates90) rawWidth else rawHeight
        val isTallScreenshot = isTallScreenshot(orientedWidth, orientedHeight)

        val targetBounds = computeTargetDimensions(
            width = orientedWidth,
            height = orientedHeight,
            isTall = isTallScreenshot,
        )
        val sampleSize = computeInSampleSize(
            srcWidth = orientedWidth,
            srcHeight = orientedHeight,
            reqWidth = targetBounds.first,
            reqHeight = targetBounds.second,
        )

        // Pass 2: decode subsampled bitmap
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            ?: throw IOException("图片解码失败，请重新选择图片")

        val orientedBitmap = applyExifOrientation(decoded, orientation)
        if (orientedBitmap !== decoded) {
            decoded.recycle()
        }

        val finalBitmap = scaleBitmapIfNeeded(
            bitmap = orientedBitmap,
            targetWidth = targetBounds.first,
            targetHeight = targetBounds.second,
        )
        if (finalBitmap !== orientedBitmap) {
            orientedBitmap.recycle()
        }

        try {
            // Re-compressing raw pixel buffer via Bitmap.compress strips 100% of EXIF/GPS/camera tags.
            val cleanJpegBytes = encodeCleanJpeg(finalBitmap, jpegQuality)
            val visionPayloads = buildVisionPayloadsFromBitmap(
                bitmap = finalBitmap,
                preEncodedWholeBytes = cleanJpegBytes,
            )

            val targetDir = receiptsDir
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IOException("无法创建本地票据目录")
            }
            val fileName = "receipt_${UUID.randomUUID().toString().replace("-", "")}.jpg"
            val outputFile = File(targetDir, fileName)
            outputFile.writeBytes(cleanJpegBytes)

            val sha256 = sha256Hex(cleanJpegBytes)
            val relPath = "$RECEIPTS_DIR_NAME/$fileName"
            val attachmentRef = AiDraftAttachmentRef(
                localRelPath = relPath,
                sha256 = sha256,
                mimeType = JPEG_MIME_TYPE,
                sizeBytes = cleanJpegBytes.size.toLong(),
                width = finalBitmap.width,
                height = finalBitmap.height,
                sliceCount = visionPayloads.size,
            )
            return ProcessedReceiptImage(
                attachmentRef = attachmentRef,
                visionPayloads = visionPayloads,
            )
        } finally {
            finalBitmap.recycle()
        }
    }

    private fun buildVisionPayloadsFromBitmap(
        bitmap: Bitmap,
        preEncodedWholeBytes: ByteArray?,
    ): List<AiImagePayload> {
        val isTall = isTallScreenshot(bitmap.width, bitmap.height) && bitmap.height > tallSliceHeightPx
        if (!isTall) {
            val jpegBytes = preEncodedWholeBytes ?: encodeCleanJpeg(bitmap, jpegQuality)
            return listOf(
                AiImagePayload(
                    base64Data = Base64.getEncoder().encodeToString(jpegBytes),
                    mimeType = JPEG_MIME_TYPE,
                ),
            )
        }

        val ranges = computeVerticalSliceRanges(
            totalHeight = bitmap.height,
            sliceHeight = tallSliceHeightPx,
            overlap = tallSliceOverlapPx,
            maxSlices = maxSlicesPerImage,
        )
        return ranges.map { (top, height) ->
            val slice = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
            try {
                val sliceBytes = encodeCleanJpeg(slice, jpegQuality)
                AiImagePayload(
                    base64Data = Base64.getEncoder().encodeToString(sliceBytes),
                    mimeType = JPEG_MIME_TYPE,
                )
            } finally {
                slice.recycle()
            }
        }
    }

    private fun isTallScreenshot(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return height.toFloat() / width.toFloat() >= tallAspectRatioThreshold
    }

    private fun computeTargetDimensions(
        width: Int,
        height: Int,
        isTall: Boolean,
    ): Pair<Int, Int> {
        if (isTall) {
            val widthScale = if (width > maxTallWidthPx) maxTallWidthPx.toFloat() / width else 1f
            val heightScale = if (height > maxTallHeightPx) maxTallHeightPx.toFloat() / height else 1f
            val scale = min(widthScale, heightScale)
            return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
        }
        val longest = max(width, height)
        if (longest <= maxNormalEdgePx) {
            return width to height
        }
        val scale = maxNormalEdgePx.toFloat() / longest.toFloat()
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    private fun computeInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        var inSampleSize = 1
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }

    private fun readExifOrientation(rawBytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(rawBytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleBitmapIfNeeded(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap {
        if (bitmap.width <= targetWidth && bitmap.height <= targetHeight) {
            return bitmap
        }
        val scale = min(
            targetWidth.toFloat() / bitmap.width.toFloat(),
            targetHeight.toFloat() / bitmap.height.toFloat(),
        ).coerceAtMost(1f)
        val outW = max(1, (bitmap.width * scale).roundToInt())
        val outH = max(1, (bitmap.height * scale).roundToInt())
        if (outW == bitmap.width && outH == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, outW, outH, true)
    }

    private fun encodeCleanJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 95), out)
        if (!ok) {
            throw IOException("图片压缩编码失败")
        }
        return out.toByteArray()
    }

    private fun readBoundedBytes(stream: java.io.InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw IOException("单张图片体积超过 25 MB 限制")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) {
                append(HEX_CHARS[(b.toInt() ushr 4) and 0x0F])
                append(HEX_CHARS[b.toInt() and 0x0F])
            }
        }
    }

    companion object {
        const val RECEIPTS_DIR_NAME: String = "receipts"
        const val JPEG_MIME_TYPE: String = "image/jpeg"
        const val MAX_BATCH_IMAGE_COUNT: Int = 9
        const val MAX_INPUT_IMAGE_BYTES: Int = 25 * 1024 * 1024
        const val DEFAULT_MAX_NORMAL_EDGE_PX: Int = 1600
        const val DEFAULT_MAX_TALL_WIDTH_PX: Int = 1280
        const val DEFAULT_MAX_TALL_HEIGHT_PX: Int = 7200
        const val DEFAULT_TALL_ASPECT_RATIO_THRESHOLD: Float = 2.4f
        const val DEFAULT_TALL_SLICE_HEIGHT_PX: Int = 1600
        const val DEFAULT_TALL_SLICE_OVERLAP_PX: Int = 140
        const val DEFAULT_MAX_SLICES_PER_IMAGE: Int = 6
        const val DEFAULT_JPEG_QUALITY: Int = 85

        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        /**
         * Computes deterministic `(top, height)` vertical slice windows with overlap for tall bill screenshots.
         */
        internal fun computeVerticalSliceRanges(
            totalHeight: Int,
            sliceHeight: Int,
            overlap: Int,
            maxSlices: Int,
        ): List<Pair<Int, Int>> {
            require(totalHeight > 0) { "totalHeight must be positive" }
            require(sliceHeight > 0) { "sliceHeight must be positive" }
            require(maxSlices > 0) { "maxSlices must be positive" }
            if (totalHeight <= sliceHeight || maxSlices == 1) {
                return listOf(0 to totalHeight)
            }
            val safeOverlap = overlap.coerceIn(0, sliceHeight / 3)
            val nominalStep = max(1, sliceHeight - safeOverlap)
            val estimatedSlices = ceil((totalHeight - safeOverlap).toDouble() / nominalStep.toDouble())
                .toInt()
                .coerceIn(2, maxSlices)

            val effectiveSliceHeight = if (estimatedSlices == maxSlices) {
                val needed = ceil(
                    (totalHeight + (maxSlices - 1) * safeOverlap).toDouble() / maxSlices.toDouble(),
                ).toInt()
                max(sliceHeight, needed).coerceAtMost(totalHeight)
            } else {
                sliceHeight
            }
            val step = max(1, effectiveSliceHeight - safeOverlap)
            val ranges = mutableListOf<Pair<Int, Int>>()
            var top = 0
            while (top < totalHeight && ranges.size < maxSlices) {
                val remaining = totalHeight - top
                val height = min(effectiveSliceHeight, remaining)
                ranges += top to height
                if (top + height >= totalHeight) break
                top += step
            }
            return ranges
        }
    }
}
