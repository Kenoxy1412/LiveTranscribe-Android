package com.charles.livecaptionn.translation

import com.charles.livecaptionn.data.SettingsRepository
import com.charles.livecaptionn.settings.TranslationBackend
import kotlinx.coroutines.flow.first

/**
 * Dispatches caption translation to the selected backend.
 * The hybrid route always tries the Worker first and falls back to local PLaMo.
 */
class RoutingTranslationRepository(
    private val settingsRepository: SettingsRepository,
    private val mlKit: TranslationRepository,
    private val libre: TranslationRepository,
    private val openAi: TranslationRepository,
    private val plamo: TranslationRepository
) : TranslationRepository {

    override suspend fun translate(
        text: String,
        sourceCode: String,
        targetCode: String,
        autoDetect: Boolean
    ): String {
        return when (settingsRepository.settingsFlow.first().translationBackend) {
            TranslationBackend.ML_KIT ->
                mlKit.translate(text, sourceCode, targetCode, autoDetect)
            TranslationBackend.LIBRE_TRANSLATE ->
                libre.translate(text, sourceCode, targetCode, autoDetect)
            TranslationBackend.OPENAI_API ->
                openAi.translate(text, sourceCode, targetCode, autoDetect)
            TranslationBackend.PLAMO_OFFLINE -> plamo.translate(text, sourceCode, targetCode, autoDetect)
            TranslationBackend.OPENAI_WITH_PLAMO_FALLBACK -> {
                val online = runCatching {
                    openAi.translate(text, sourceCode, targetCode, autoDetect)
                }.getOrDefault("")
                if (online.isNotBlank()) online
                else plamo.translate(text, sourceCode, targetCode, autoDetect)
            }
        }
    }

    override suspend fun prewarm(sourceCode: String, targetCode: String) {
        when (settingsRepository.settingsFlow.first().translationBackend) {
            TranslationBackend.OPENAI_WITH_PLAMO_FALLBACK, TranslationBackend.PLAMO_OFFLINE ->
                plamo.prewarm(sourceCode, targetCode)
            TranslationBackend.ML_KIT -> mlKit.prewarm(sourceCode, targetCode)
            TranslationBackend.LIBRE_TRANSLATE -> libre.prewarm(sourceCode, targetCode)
            TranslationBackend.OPENAI_API -> openAi.prewarm(sourceCode, targetCode)
        }
    }

    override fun close() {
        mlKit.close()
        libre.close()
        openAi.close()
        plamo.close()
    }
}
