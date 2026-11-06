package com.charles.livecaptionn.speech

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
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
 * Offline SenseVoice recognizer for PCM captured from Android AudioPlaybackCapture.
 * The model is downloaded once into app-private storage; inference never calls a server.
 */
class SherpaSenseVoiceSttClient(private val context: Context) {
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(15, TimeUnit.MINUTES)
        .build()

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var recognizerLanguage: String? = null

    val modelDirectory: File
        get() = File(context.filesDir, MODEL_DIRECTORY)

    fun isModelInstalled(): Boolean =
        File(modelDirectory, MODEL_FILE).length() > MIN_MODEL_BYTES &&
            File(modelDirectory, TOKENS_FILE).length() > 10_000L

    suspend fun ensureModelInstalled() = withContext(Dispatchers.IO) {
        mutex.withLock {
            modelDirectory.mkdirs()
            val model = File(modelDirectory, MODEL_FILE)
            val tokens = File(modelDirectory, TOKENS_FILE)
            if (model.length() <= MIN_MODEL_BYTES) {
                downloadFile(MODEL_URL, model, MIN_MODEL_BYTES)
            }
            if (tokens.length() <= 10_000L) {
                downloadFile(TOKENS_URL, tokens, 10_000L)
            }
        }
    }

    suspend fun prepare(languageCode: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val language = senseVoiceLanguage(languageCode)
            if (recognizer != null && recognizerLanguage == language) return@withLock
            recognizer?.release()
            recognizer = null
            ensureFilesExist()
            val modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(modelDirectory, MODEL_FILE).absolutePath,
                    language = language,
                    useInverseTextNormalization = true
                ),
                tokens = File(modelDirectory, TOKENS_FILE).absolutePath,
                numThreads = 2,
                provider = "cpu"
            )
            recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search"
                )
            )
            recognizerLanguage = language
            Log.i(TAG, "SenseVoice recognizer ready for language=$language")
        }
    }

    /** Accepts signed 16-bit little-endian mono PCM at 16 kHz. */
    fun transcribe(pcm16: ByteArray): String {
        val active = recognizer ?: error("sherpa-onnx model is not initialized")
        val sampleCount = pcm16.size / 2
        if (sampleCount == 0) return ""
        val samples = FloatArray(sampleCount)
        var source = 0
        for (i in 0 until sampleCount) {
            val low = pcm16[source].toInt() and 0xff
            val high = pcm16[source + 1].toInt()
            val value = ((high shl 8) or low).toShort()
            samples[i] = value / 32768.0f
            source += 2
        }
        val stream = active.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            active.decode(stream)
            active.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() {
        try { recognizer?.release() } catch (_: Throwable) {}
        recognizer = null
        recognizerLanguage = null
    }

    private suspend fun ensureFilesExist() {
        if (!isModelInstalled()) {
            modelDirectory.mkdirs()
            val model = File(modelDirectory, MODEL_FILE)
            val tokens = File(modelDirectory, TOKENS_FILE)
            if (model.length() <= MIN_MODEL_BYTES) downloadFile(MODEL_URL, model, MIN_MODEL_BYTES)
            if (tokens.length() <= 10_000L) downloadFile(TOKENS_URL, tokens, 10_000L)
        }
    }

    private fun downloadFile(url: String, destination: File, minimumBytes: Long) {
        val temporary = File(destination.parentFile, destination.name + ".download")
        temporary.delete()
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Model download failed (HTTP ${response.code})")
            val body = response.body ?: error("Model download returned an empty response")
            body.byteStream().use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
        }
        if (temporary.length() <= minimumBytes) {
            temporary.delete()
            error("Downloaded sherpa model file is incomplete")
        }
        if (destination.exists()) destination.delete()
        check(temporary.renameTo(destination)) { "Could not install downloaded sherpa model file" }
    }

    private fun senseVoiceLanguage(code: String): String = when {
        code.startsWith("ja", true) -> "ja"
        code.startsWith("zh", true) -> "zh"
        code.startsWith("ko", true) -> "ko"
        code.startsWith("en", true) -> "en"
        code.equals("yue", true) -> "yue"
        else -> "auto"
    }

    companion object {
        private const val TAG = "SherpaSenseVoice"
        private const val SAMPLE_RATE = 16_000
        private const val MODEL_DIRECTORY = "sherpa-onnx-sense-voice"
        private const val MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val MIN_MODEL_BYTES = 100_000_000L
        private const val MODEL_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx"
        private const val TOKENS_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt"
    }
}
