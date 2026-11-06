package com.charles.livecaptionn.translation

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Calls the user's Cloudflare Worker. The OpenAI API key stays server-side
 * and is never embedded in the Android application.
 */
class OpenAiWorkerTranslationRepository : TranslationRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(
        text: String,
        sourceCode: String,
        targetCode: String,
        autoDetect: Boolean
    ): String {
        val clean = text.trim()
        if (clean.isEmpty() || targetCode.isBlank()) return clean
        return try {
            val payload = JSONObject()
                .put("text", clean)
                .put("source", if (autoDetect || sourceCode.isBlank()) "auto" else sourceCode)
                .put("target", targetCode)
            val request = Request.Builder()
                .url(WORKER_URL + "/translate")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w("OpenAITranslation", "Worker returned HTTP ${response.code}: ${raw.take(240)}")
                    return ""
                }
                JSONObject(raw).optString("translatedText").trim()
            }
        } catch (t: Throwable) {
            Log.w("OpenAITranslation", "Translation request failed", t)
            ""
        }
    }

    companion object {
        private const val WORKER_URL = "https://livecaptionn-translate.kenoxy1411.workers.dev"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
