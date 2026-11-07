package com.charles.livecaptionn.translation

/**
 * Thin JNI facade for the bundled llama.cpp runtime.
 *
 * The native library is optional at install time; callers must check
 * [isAvailable] and ensure the model file has been downloaded before loading.
 */
internal object PlamoNative {
    val isAvailable: Boolean = runCatching {
        System.loadLibrary("plamo_jni")
    }.isSuccess

    external fun nativeLoadModel(modelPath: String, threads: Int): Boolean
    external fun nativeTranslate(prompt: String, maxTokens: Int): String
    external fun nativeRelease()
}
