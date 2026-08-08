package com.simone.jarvismobile.translate

import com.simone.jarvismobile.core.translate.TranslationAudioOutput
import com.simone.jarvismobile.core.translate.TranslationLanguage
import com.simone.jarvismobile.core.translate.TranslationMode
import com.simone.jarvismobile.core.translate.TranslationSession
import com.simone.jarvismobile.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Live Translator's persistence and model surface, kept out of the manager so
 * the pipeline stays about audio and translation only. Only *preferences* are
 * stored — the language pair, mode, whether to speak, Wi-Fi-only downloads and the
 * audio sink — never a transcript or any translated content (docs/PRIVACY.md).
 */
@Singleton
class LiveTranslatorRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val models: TranslationModelManager,
) {
    val languageA: Flow<TranslationLanguage> = settings.translateLanguageA
    val languageB: Flow<TranslationLanguage> = settings.translateLanguageB
    val mode: Flow<TranslationMode> = settings.translateMode
    val ttsEnabled: Flow<Boolean> = settings.translateTtsEnabled
    val wifiOnly: Flow<Boolean> = settings.translateWifiOnly
    val audioOutput: Flow<TranslationAudioOutput> = settings.translateAudioOutput
    val modelStatuses = models.statuses

    suspend fun setPair(a: TranslationLanguage, b: TranslationLanguage) = settings.setTranslatePair(a, b)
    suspend fun setMode(mode: TranslationMode) = settings.setTranslateMode(mode)
    suspend fun setTtsEnabled(value: Boolean) = settings.setTranslateTtsEnabled(value)
    suspend fun setWifiOnly(value: Boolean) = settings.setTranslateWifiOnly(value)
    suspend fun setAudioOutput(value: TranslationAudioOutput) = settings.setTranslateAudioOutput(value)

    suspend fun refreshModels() = models.refresh()
    suspend fun download(lang: TranslationLanguage) = models.download(lang, settings.translateWifiOnly.first())
    suspend fun delete(lang: TranslationLanguage) = models.delete(lang)

    /** Builds a session from the saved preferences, overriding the pair/mode when given. */
    suspend fun buildSession(
        a: TranslationLanguage? = null,
        b: TranslationLanguage? = null,
        mode: TranslationMode? = null,
    ): TranslationSession {
        val langA = a ?: settings.translateLanguageA.first()
        val langB = b ?: settings.translateLanguageB.first()
        return TranslationSession(
            sessionId = UUID.randomUUID().toString(),
            languageA = langA,
            languageB = langB,
            mode = mode ?: settings.translateMode.first(),
            startedAt = System.currentTimeMillis(),
            audioOutput = settings.translateAudioOutput.first(),
            ttsEnabled = settings.translateTtsEnabled.first(),
            autoDetectEnabled = (mode ?: settings.translateMode.first()).autoDetects,
        )
    }
}
