package com.charles.livecaptionn.settings

/**
 * Translation route selected for captions.
 *
 * OPENAI_WITH_PLAMO_FALLBACK keeps the Worker as the primary translator and
 * uses the on-device PLaMo model only when the Worker fails.
 */
enum class TranslationBackend {
    ML_KIT,
    LIBRE_TRANSLATE,
    OPENAI_API,
    OPENAI_WITH_PLAMO_FALLBACK;

    companion object {
        fun fromName(name: String?): TranslationBackend =
            entries.firstOrNull { it.name == name } ?: OPENAI_WITH_PLAMO_FALLBACK
    }
}
