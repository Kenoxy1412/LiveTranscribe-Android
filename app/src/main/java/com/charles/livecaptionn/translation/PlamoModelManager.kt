package com.charles.livecaptionn.translation

import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class PlamoDownloadState(
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = MODEL_SIZE_ESTIMATE,
    val error: String? = null
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    companion object {
        const val MODEL_SIZE_ESTIMATE = 570_000_000L
    }
}

/**
 * Resumable, app-private downloader for the official PLaMo 0.8B Q4_K_M GGUF.
 * The model is never bundled into the APK and never uploaded from the device.
 */
class PlamoModelManager(private val modelFile: File) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private val partFile = File(modelFile.parentFile, modelFile.name + ".part")
    private val mutableState = MutableStateFlow(snapshot())
    val state: StateFlow<PlamoDownloadState> = mutableState.asStateFlow()

    suspend fun download() = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            mutableState.value = snapshot()
            return@withContext
        }
        modelFile.parentFile?.mkdirs()
        val stat = StatFs(modelFile.parentFile?.absolutePath ?: return@withContext)
        val required = PlamoDownloadState.MODEL_SIZE_ESTIMATE + 150_000_000L
        if (stat.availableBytes < required) {
            mutableState.value = snapshot().copy(error = "พื้นที่ว่างไม่เพียงพอ ต้องมีพื้นที่อย่างน้อยประมาณ 720 MB")
            return@withContext
        }

        var offset = if (partFile.exists()) partFile.length() else 0L
        mutableState.value = snapshot().copy(downloading = true, error = null, downloadedBytes = offset)
        try {
            val request = Request.Builder()
                .url(MODEL_URL)
                .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || response.body == null) {
                    throw IllegalStateException("ดาวน์โหลดโมเดลไม่สำเร็จ (HTTP ${response.code})")
                }
                val append = offset > 0L && response.code == 206
                if (offset > 0L && !append) {
                    partFile.delete()
                    offset = 0L
                }
                val expectedHash = response.header("x-linked-etag")?.trim('"')
                    ?: response.header("etag")?.trim('"')
                val body = response.body!!
                val responseLength = body.contentLength()
                val total = if (append && responseLength > 0L) offset + responseLength
                    else if (responseLength > 0L) responseLength
                    else PlamoDownloadState.MODEL_SIZE_ESTIMATE
                var downloaded = offset
                body.byteStream().use { input ->
                    FileOutputStream(partFile, append).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            mutableState.value = snapshot().copy(
                                downloading = true,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                error = null
                            )
                        }
                        output.fd.sync()
                    }
                }
            }

            val size = partFile.length()
            if (size < MIN_MODEL_BYTES || size > MAX_MODEL_BYTES) {
                throw IllegalStateException("ขนาดไฟล์โมเดลไม่ถูกต้อง (${size / 1_000_000} MB)")
            }
            if (!expectedHash.isNullOrBlank() && expectedHash.matches(Regex("[0-9a-fA-F]{64}"))) {
                val actualHash = sha256(partFile)
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    partFile.delete()
                    throw IllegalStateException("ตรวจสอบ SHA-256 ของโมเดลไม่ผ่าน กรุณาดาวน์โหลดใหม่")
                }
            }
            if (modelFile.exists()) modelFile.delete()
            if (!partFile.renameTo(modelFile)) {
                throw IllegalStateException("ไม่สามารถบันทึกไฟล์โมเดลได้")
            }
            mutableState.value = snapshot().copy(installed = true, downloading = false)
        } catch (t: Throwable) {
            Log.e(TAG, "PLaMo model download failed", t)
            mutableState.value = snapshot().copy(
                downloading = false,
                downloadedBytes = if (partFile.exists()) partFile.length() else 0L,
                error = t.message ?: "ดาวน์โหลดโมเดลไม่สำเร็จ"
            )
            throw t
        }
    }

    fun refresh() {
        mutableState.value = snapshot()
    }

    fun delete() {
        partFile.delete()
        modelFile.delete()
        mutableState.value = snapshot()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isInstalled(): Boolean =
        modelFile.isFile && modelFile.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES

    private fun snapshot(): PlamoDownloadState {
        val installed = isInstalled()
        return PlamoDownloadState(
            installed = installed,
            downloadedBytes = if (installed) modelFile.length()
                else if (partFile.exists()) partFile.length() else 0L,
            totalBytes = PlamoDownloadState.MODEL_SIZE_ESTIMATE
        )
    }

    companion object {
        private const val TAG = "PlamoModelManager"
        private const val MODEL_URL =
            "https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/resolve/main/plamo-3-translate-0.8b-Q4_K_M.gguf"
        private const val MIN_MODEL_BYTES = 500_000_000L
        private const val MAX_MODEL_BYTES = 650_000_000L
    }
}
