package com.charles.livecaptionn.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.settings.SttBackend
import com.charles.livecaptionn.settings.TranslationBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "caption_settings")

class SettingsDataStore(private val context: Context) : SettingsRepository {

    init {
        // Migrate the established system-audio / local SenseVoice profile.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            context.dataStore.edit { p ->
                if (p[TRANSLATION_PROFILE_V2] != true) {
                    p[TRANSLATION_BACKEND] = TranslationBackend.OPENAI_API.name
                    p[SHOW_ORIGINAL] = false
                    p[TARGET] = "th"
                    if (p[SOURCE] !in setOf("ja", "zh")) p[SOURCE] = "ja"
                    p[AUDIO_SOURCE] = AudioSource.SYSTEM.name
                    p[STT_BACKEND] = SttBackend.LOCAL_SHERPA.name
                    p[TRANSLATION_PROFILE_V2] = true
                }
                if (p[TRANSLATION_PROFILE_V3] != true) {
                    p[TRANSLATION_BACKEND] = TranslationBackend.OPENAI_WITH_PLAMO_FALLBACK.name
                    p[TRANSLATION_PROFILE_V3] = true
                }
                if (p[TRANSLATION_PROFILE_V4] != true) {
                    p[TRANSLATION_BACKEND] = TranslationBackend.PLAMO_OFFLINE.name
                    p[TRANSLATION_PROFILE_V4] = true
                }
            }
        }
    }

    override val settingsFlow: Flow<CaptionSettings> = context.dataStore.data.map { p -> p.toCaptionSettings() }

    override suspend fun update(transform: (CaptionSettings) -> CaptionSettings) {
        context.dataStore.edit { p ->
            val curr = p.toCaptionSettings()
            val next = transform(curr)
            p[SOURCE] = next.sourceLanguageCode
            p[TARGET] = next.targetLanguageCode
            p[AUTO_DETECT] = next.autoDetectSource
            p[TEXT_SIZE] = next.textSizeSp
            p[OPACITY] = next.overlayOpacity.coerceIn(0.0f, 1.0f)
            p[SHOW_ORIGINAL] = next.showOriginal
            p[BASE_URL] = next.serverBaseUrl.trim()
            p[OVERLAY_X] = next.overlayX
            p[OVERLAY_Y] = next.overlayY
            p[OVERLAY_MIN] = next.overlayMinimized
            p[AUDIO_SOURCE] = next.audioSource.name
            p[STT_BACKEND] = next.sttBackend.name
            p[TRANSLATION_BACKEND] = next.translationBackend.name
            p[STT_URL] = next.sttBaseUrl.trim()
            p[OVERLAY_W] = next.overlayWidthDp
            p[OVERLAY_H] = next.overlayHeightDp
            p[OVERLAY_THEME] = next.overlayThemeId
            p[OVERLAY_FONT] = next.overlayFontId
            p[UI_LANG] = next.uiLanguageCode.orEmpty().ifBlank { "en" }
            p[ONBOARDING_COMPLETE] = next.onboardingComplete
            p[SAVE_HISTORY] = next.saveHistory
            p[BORDERLINE_WARNING_DISMISSED] = next.borderlineWarningDismissed
            if (next.simulatedCompatibilityTier != null) {
                p[SIMULATED_TIER] = next.simulatedCompatibilityTier
            } else {
                p.remove(SIMULATED_TIER)
            }
        }
    }

    private fun androidx.datastore.preferences.core.Preferences.toCaptionSettings(): CaptionSettings {
        val defaults = CaptionSettings()
        return CaptionSettings(
            sourceLanguageCode = this[SOURCE] ?: defaults.sourceLanguageCode,
            targetLanguageCode = this[TARGET] ?: defaults.targetLanguageCode,
            autoDetectSource = this[AUTO_DETECT] ?: false,
            textSizeSp = this[TEXT_SIZE] ?: 20f,
            overlayOpacity = this[OPACITY] ?: 0.65f,
            showOriginal = this[SHOW_ORIGINAL] ?: defaults.showOriginal,
            serverBaseUrl = this[BASE_URL] ?: CaptionSettings.DEFAULT_BASE_URL,
            overlayX = this[OVERLAY_X] ?: 60,
            overlayY = this[OVERLAY_Y] ?: 220,
            overlayMinimized = this[OVERLAY_MIN] ?: false,
            audioSource = AudioSource.fromName(this[AUDIO_SOURCE] ?: AudioSource.SYSTEM.name),
            sttBackend = SttBackend.fromName(this[STT_BACKEND] ?: SttBackend.LOCAL_SHERPA.name),
            translationBackend = TranslationBackend.fromName(this[TRANSLATION_BACKEND]),
            sttBaseUrl = this[STT_URL] ?: CaptionSettings.DEFAULT_STT_URL,
            overlayWidthDp = this[OVERLAY_W] ?: CaptionSettings.DEFAULT_OVERLAY_WIDTH_DP,
            overlayHeightDp = this[OVERLAY_H] ?: CaptionSettings.DEFAULT_OVERLAY_HEIGHT_DP,
            overlayThemeId = this[OVERLAY_THEME] ?: defaults.overlayThemeId,
            overlayFontId = this[OVERLAY_FONT] ?: defaults.overlayFontId,
            uiLanguageCode = this[UI_LANG] ?: defaults.uiLanguageCode,
            onboardingComplete = this[ONBOARDING_COMPLETE] ?: defaults.onboardingComplete,
            saveHistory = this[SAVE_HISTORY] ?: defaults.saveHistory,
            borderlineWarningDismissed = this[BORDERLINE_WARNING_DISMISSED] ?: defaults.borderlineWarningDismissed,
            simulatedCompatibilityTier = this[SIMULATED_TIER]
        )
    }

    private companion object {
        val SOURCE = stringPreferencesKey("source")
        val TARGET = stringPreferencesKey("target")
        val AUTO_DETECT = booleanPreferencesKey("auto_detect")
        val TEXT_SIZE = floatPreferencesKey("text_size")
        val OPACITY = floatPreferencesKey("opacity")
        val SHOW_ORIGINAL = booleanPreferencesKey("show_original")
        val BASE_URL = stringPreferencesKey("base_url")
        val OVERLAY_X = intPreferencesKey("overlay_x")
        val OVERLAY_Y = intPreferencesKey("overlay_y")
        val OVERLAY_MIN = booleanPreferencesKey("overlay_min")
        val AUDIO_SOURCE = stringPreferencesKey("audio_source")
        val STT_BACKEND = stringPreferencesKey("stt_backend")
        val TRANSLATION_BACKEND = stringPreferencesKey("translation_backend")
        val TRANSLATION_PROFILE_V2 = booleanPreferencesKey("translation_profile_sensevoice_openai_novel_v2")
        val TRANSLATION_PROFILE_V3 = booleanPreferencesKey("translation_profile_plamo_offline_fallback_v3")
        val TRANSLATION_PROFILE_V4 = booleanPreferencesKey("translation_profile_plamo_offline_only_v4")
        val STT_URL = stringPreferencesKey("stt_url")
        val OVERLAY_W = intPreferencesKey("overlay_w")
        val OVERLAY_H = intPreferencesKey("overlay_h")
        val OVERLAY_THEME = stringPreferencesKey("overlay_theme")
        val OVERLAY_FONT = stringPreferencesKey("overlay_font")
        val UI_LANG = stringPreferencesKey("ui_language")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
        val BORDERLINE_WARNING_DISMISSED = booleanPreferencesKey("borderline_warning_dismissed")
        val SIMULATED_TIER = stringPreferencesKey("simulated_tier")
    }
}
