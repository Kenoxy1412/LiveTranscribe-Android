package com.charles.livecaptionn.translation

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * On-device PLaMo 3 Translate repository.
 *
 * The GGUF is downloaded separately and kept in app-private storage; it is
 * deliberately not packaged in the APK. Inference is serialized because one
 * llama.cpp context is shared by the caption pipeline.
 */
class PlamoTranslationRepository(
    private val modelFile: File,
    private val threads: Int = 4
) : TranslationRepository {

    private val mutex = Mutex()
    @Volatile private var loaded = false

    override suspend fun prewarm(sourceCode: String, targetCode: String) {
        mutex.withLock { ensureLoaded() }
    }

    override suspend fun translate(
        text: String,
        sourceCode: String,
        targetCode: String,
        autoDetect: Boolean
    ): String {
        val clean = text.trim()
        if (clean.isEmpty()) return ""
        if (targetCode.isBlank() || !targetCode.equals("th", ignoreCase = true)) return ""

        return mutex.withLock {
            if (!ensureLoaded()) return@withLock ""
            try {
                val sourceAttribute = if (!autoDetect && sourceCode.isNotBlank()) {
                    "<|plamo:key|>lang<|plamo:val|>${sourceCode.lowercase()}"
                } else {
                    ""
                }
                val prompt = buildString {
                    append("<|plamo:tag|>dataset<|plamo:msg|>translation")
                    append("<|plamo:tag|>input")
                    append(sourceAttribute)
                    append("<|plamo:msg|>")
                    append(clean)
                    append("<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>th<|plamo:msg|>")
                }
                val maxTokens = (clean.length * 2 + 64).coerceIn(96, 512)
                PlamoNative.nativeTranslate(prompt, maxTokens).trim()
            } catch (t: Throwable) {
                Log.e(TAG, "Offline PLaMo translation failed", t)
                loaded = false
                runCatching { PlamoNative.nativeRelease() }
                ""
            }
        }
    }

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (!PlamoNative.isAvailable || !modelFile.isFile || modelFile.length() < MIN_MODEL_BYTES) {
            return false
        }
        return try {
            loaded = PlamoNative.nativeLoadModel(modelFile.absolutePath, threads)
            loaded
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to load PLaMo model", t)
            loaded = false
            false
        }
    }

    override fun close() {
        runCatching { PlamoNative.nativeRelease() }
        loaded = false
    }

    companion object {
        private const val TAG = "PlamoTranslation"
        // The official Q4_K_M distribution is approximately 570 MB.
        private const val MIN_MODEL_BYTES = 500_000_000L
    }
}
