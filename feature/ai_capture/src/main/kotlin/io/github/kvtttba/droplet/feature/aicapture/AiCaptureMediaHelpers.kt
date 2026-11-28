package io.github.kvtttba.droplet.feature.aicapture

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Platform helpers for FileProvider camera captures, system speech recognition, and
 * in-app AAC voice recording for OpenAI Whisper-compatible `/audio/transcriptions`.
 */
object AiCaptureMediaHelpers {

    private const val CAMERA_CACHE_SUBDIR = "camera_captures"
    private const val VOICE_CACHE_SUBDIR = "voice_captures"

    fun createCameraCaptureTarget(context: Context): Pair<Uri, File> {
        val dir = File(context.cacheDir, CAMERA_CACHE_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, "capture_${UUID.randomUUID().toString().replace("-", "")}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return uri to file
    }

    fun buildSystemSpeechRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出记账内容，例如：早饭8块微信，中午打车25刷信用卡")
        }

    fun isSystemSpeechRecognizerAvailable(context: Context): Boolean =
        buildSystemSpeechRecognizerIntent().resolveActivity(context.packageManager) != null

    fun createVoiceTempFile(context: Context): File {
        val dir = File(context.cacheDir, VOICE_CACHE_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "voice_${UUID.randomUUID().toString().replace("-", "")}.m4a")
    }
}

/**
 * Minimal lifecycle-safe wrapper around Android 12+ [MediaRecorder] outputting `.m4a` (AAC)
 * audio for custom AI transcription endpoints.
 */
class InAppVoiceAudioRecorder(
    private val context: Context,
) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    fun startRecording(): Result<File> = runCatching {
        stopAndDiscard()
        val outputFile = AiCaptureMediaHelpers.createVoiceTempFile(context)
        val mediaRecorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setAudioEncodingBitRate(64_000)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        recorder = mediaRecorder
        currentFile = outputFile
        outputFile
    }.onFailure {
        stopAndDiscard()
    }

    fun stopAndRetrieveFile(): Result<File> = runCatching {
        val activeRecorder = recorder ?: throw IOException("当前未在录音")
        val file = currentFile ?: throw IOException("未找到录音文件")
        try {
            activeRecorder.stop()
        } finally {
            activeRecorder.release()
            recorder = null
            currentFile = null
        }
        if (!file.exists() || file.length() <= 0L) {
            file.delete()
            throw IOException("录音时长过短，请长按或说话更清晰后重试")
        }
        file
    }

    fun stopAndDiscard() {
        recorder?.let { active ->
            try {
                active.stop()
            } catch (_: Exception) {
            }
            try {
                active.release()
            } catch (_: Exception) {
            }
        }
        recorder = null
        currentFile?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        currentFile = null
    }
}
