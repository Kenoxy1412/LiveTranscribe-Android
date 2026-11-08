package com.charles.livecaptionn.speech

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Offline multilingual Whisper (sherpa-onnx) for Japanese/Chinese system audio.
 * The model is downloaded once to app-private storage; inference is on-device.
 */
class WhisperOfflineSttClient(private val context: Context) {
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(15, TimeUnit.MINUTES)
        .build()

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var recognizerLanguage: String? = null

    private val modelDirectory = File(context.filesDir, MODEL_DIRECTORY)

    fun isModelInstalled(): Boolean =
        File(modelDirectory, ENCODER_FILE).length() > MIN_ENCODER_BYTES &&
            File(modelDirectory, DECODER_FILE).length() > MIN_DECODER_BYTES &&
            File(modelDirectory, TOKENS_FILE).length() > MIN_TOKENS_BYTES

    suspend fun prepare(languageCode: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val language = whisperLanguage(languageCode)
            if (recognizer != null && recognizerLanguage == language) return@withLock

            recognizer?.release()
            recognizer = null
            ensureModelsInstalled()

            val modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDirectory, ENCODER_FILE).absolutePath,
                    decoder = File(modelDirectory, DECODER_FILE).absolutePath,
                    language = language,
                    task = "transcribe"
                ),
                tokens = File(modelDirectory, TOKENS_FILE).absolutePath,
                numThreads = 3,
                provider = "cpu",
                modelType = "whisper"
            )
            recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search"
                )
            )
            recognizerLanguage = language
            Log.i(TAG, "Offline Whisper ready for language=$language")
        }
    }

    /** Accepts signed 16-bit little-endian mono PCM at 16 kHz. */
    fun transcribe(pcm16: ByteArray): String {
        val active = recognizer ?: error("Offline Whisper model is not initialized")
        val count = pcm16.size / 2
        if (count == 0) return ""
        val samples = FloatArray(count)
        var offset = 0
        for (i in 0 until count) {
            val low = pcm16[offset].toInt() and 0xff
            val high = pcm16[offset + 1].toInt()
            samples[i] = (((high shl 8) or low).toShort()).toFloat() / 32768.0f
            offset += 2
        }

        synchronized(active) {
            val stream = active.createStream()
            return try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                active.decode(stream)
                active.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
    }

    fun release() {
        synchronized(this) {
            try { recognizer?.release() } catch (_: Throwable) {}
            recognizer = null
            recognizerLanguage = null
        }
    }

    private suspend fun ensureModelsInstalled() {
        modelDirectory.mkdirs()
        if (isModelInstalled()) return
        downloadFile(ENCODER_URL, File(modelDirectory, ENCODER_FILE), MIN_ENCODER_BYTES)
        downloadFile(DECODER_URL, File(modelDirectory, DECODER_FILE), MIN_DECODER_BYTES)
        downloadFile(TOKENS_URL, File(modelDirectory, TOKENS_FILE), MIN_TOKENS_BYTES)
        check(isModelInstalled()) { "Whisper model files are incomplete" }
    }

    private fun downloadFile(url: String, destination: File, minimumBytes: Long) {
        if (destination.isFile && destination.length() > minimumBytes) return
        val temporary = File(destination.parentFile, destination.name + ".download")
        temporary.delete()
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Whisper model download failed (HTTP ${response.code})")
            val body = response.body ?: error("Whisper model download returned an empty response")
            body.byteStream().use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
        }
        if (temporary.length() <= minimumBytes) {
            temporary.delete()
            error("Whisper model file is incomplete: ${destination.name}")
        }
        destination.delete()
        check(temporary.renameTo(destination)) { "Could not install ${destination.name}" }
    }

    private fun whisperLanguage(code: String): String = when {
        code.startsWith("ja", true) -> "ja"
        code.startsWith("zh", true) -> "zh"
        code.startsWith("ko", true) -> "ko"
        code.startsWith("en", true) -> "en"
        else -> error("Offline Whisper does not support the selected language: $code")
    }

    companion object {
        private const val TAG = "WhisperOfflineSTT"
        private const val SAMPLE_RATE = 16_000
        private const val MODEL_DIRECTORY = "sherpa-onnx-whisper-base"
        private const val ENCODER_FILE = "base-encoder.int8.onnx"
        private const val DECODER_FILE = "base-decoder.int8.onnx"
        private const val TOKENS_FILE = "base-tokens.txt"
        private const val MIN_ENCODER_BYTES = 20_000_000L
        private const val MIN_DECODER_BYTES = 100_000_000L
        private const val MIN_TOKENS_BYTES = 100_000L
        private const val MODEL_ROOT =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/"
        private const val ENCODER_URL = MODEL_ROOT + ENCODER_FILE
        private const val DECODER_URL = MODEL_ROOT + DECODER_FILE
        private const val TOKENS_URL = MODEL_ROOT + TOKENS_FILE
    }
}
