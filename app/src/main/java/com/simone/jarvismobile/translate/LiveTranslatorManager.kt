package com.simone.jarvismobile.translate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.simone.jarvismobile.audio.SpeechToTextEngine
import com.simone.jarvismobile.audio.SttResult
import com.simone.jarvismobile.core.translate.ConversationSegment
import com.simone.jarvismobile.core.translate.Detection
import com.simone.jarvismobile.core.translate.Heard
import com.simone.jarvismobile.core.translate.LanguageDetector
import com.simone.jarvismobile.core.translate.LiveTranslationState
import com.simone.jarvismobile.core.translate.ModelGate
import com.simone.jarvismobile.core.translate.Recognizer
import com.simone.jarvismobile.core.translate.SpeakOutcome
import com.simone.jarvismobile.core.translate.Speaker
import com.simone.jarvismobile.core.translate.TextOutcome
import com.simone.jarvismobile.core.translate.TranslationLanguage
import com.simone.jarvismobile.core.translate.TranslationMode
import com.simone.jarvismobile.core.translate.TranslationSession
import com.simone.jarvismobile.core.translate.TranslatorController
import com.simone.jarvismobile.core.translate.TranslatorNotice
import com.simone.jarvismobile.core.translate.TranslatorPrefs
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android face of Live Translator. All the control logic (single-flight push-to-talk, stop during any
 * stage, stale-result protection, echo avoidance, error handling) lives in the pure, unit-tested
 * [TranslatorController]; this class only plugs the real pieces into it: the on-device recognizer
 * ([SpeechToTextEngine]), ML Kit translation ([MlKitTranslationEngine]) and language-id
 * ([MlKitLanguageDetector]), the multilingual TTS ([TranslationAudioController]) and the downloadable
 * models ([TranslationModelManager]).
 *
 * Fully offline once the models are on the device; the assistant's own LLM and the Core PC are never involved.
 * Honest limit: the platform recognizer hears ONE language at a time, so the automatic modes listen in the
 * active language and switch sides when the other language is recognised — not simultaneous dual recognition.
 */
@Singleton
class LiveTranslatorManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stt: SpeechToTextEngine,
    private val engineRouter: TranslationEngineRouter,
    private val detector: MlKitLanguageDetector,
    private val audio: TranslationAudioController,
    private val models: TranslationModelManager,
    private val settings: SettingsRepository,
) {
    // Last-resort net: an uncaught exception in any translator coroutine must not crash the app.
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "live_translate_uncaught ${e.javaClass.simpleName}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)

    private val controller = TranslatorController(
        scope = scope,
        recognizer = object : Recognizer {
            override suspend fun listen(language: TranslationLanguage): Heard =
                when (val r = stt.transcribe(language.recognizerTag)) {
                    is SttResult.Text -> Heard.Text(r.text)
                    SttResult.NoSpeech -> Heard.Silence
                    is SttResult.Unavailable ->
                        if (r.reason.startsWith("language:")) Heard.LanguageUnavailable(language) else Heard.Unavailable(r.reason)
                    is SttResult.Failure -> Heard.Failure(r.code)
                }

            override fun cancel() = stt.cancel()
        },
        engine = engineRouter.engine(),
        speaker = object : Speaker {
            override suspend fun speak(text: String, language: TranslationLanguage): SpeakOutcome = audio.speak(text, language)
            override fun stop() = audio.stop()
        },
        detector = object : LanguageDetector {
            override suspend fun detect(text: String): Detection = detector.detect(text).let { Detection(it.language, it.confidence) }
        },
        models = object : ModelGate {
            override suspend fun missing(langs: Collection<TranslationLanguage>): List<TranslationLanguage> {
                models.refresh()
                return langs.filter { !models.isDownloaded(it) }
            }

            override suspend fun canDownloadNow(): Boolean {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
                val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
                val wifiOnly = runCatching { settings.translateWifiOnly.first() }.getOrDefault(true)
                return !wifiOnly || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }

            override suspend fun download(language: TranslationLanguage): Boolean =
                models.download(language, runCatching { settings.translateWifiOnly.first() }.getOrDefault(true))
        },
        prefs = object : TranslatorPrefs {
            override suspend fun ttsEnabled(): Boolean = runCatching { settings.translateTtsEnabled.first() }.getOrDefault(true)
        },
    )

    val state: StateFlow<LiveTranslationState> = controller.state
    val session: StateFlow<TranslationSession?> = controller.session
    val segments: StateFlow<List<ConversationSegment>> = controller.segments
    val listeningLanguage: StateFlow<TranslationLanguage?> = controller.listeningLanguage
    val notice: StateFlow<TranslatorNotice?> = controller.notice

    /** Live partial transcript from the recognizer, for the UI. */
    val partial: StateFlow<String> = stt.partial

    /** Why the last spoken translation was or wasn't heard (missing voice, etc.). */
    val audioDetail: StateFlow<String> = audio.lastDetail

    private val _openScreenRequest = MutableStateFlow(0)
    /** Bumped when a session is started by voice/chat, so the UI brings the translator screen forward. */
    val openScreenRequest: StateFlow<Int> = _openScreenRequest.asStateFlow()
    fun requestOpenScreen() { _openScreenRequest.value = _openScreenRequest.value + 1 }

    /** True while a session is active (Ready/Listening/…), for the UI and router. */
    val isRunning: Boolean get() = controller.isRunning

    fun start(session: TranslationSession) = controller.start(session)
    fun pushToTalk(source: TranslationLanguage) = controller.pushToTalk(source)
    fun swap() = controller.swap()
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun stop() = controller.stop()
    fun changeMode(mode: TranslationMode) = controller.changeMode(mode)
    fun clearTranscript() = controller.clearTranscript()
    fun clearNotice() = controller.clearNotice()

    /** Text translation: no microphone, no session, offline once the models are present. */
    suspend fun translateText(text: String, from: TranslationLanguage, to: TranslationLanguage): TextOutcome =
        controller.translateText(text, from, to)

    /** Reads a translated text aloud; the outcome says whether an offline voice exists. */
    suspend fun speakText(text: String, language: TranslationLanguage): SpeakOutcome = controller.speakText(text, language)

    /** Speaks a fixed phrase in [language] so the user can check the voice output on its own. */
    fun testVoice(language: TranslationLanguage) {
        scope.launch {
            runCatching {
                audio.speak(
                    when (language) {
                        TranslationLanguage.ITALIAN -> "Prova voce. Il traduttore è pronto."
                        TranslationLanguage.ENGLISH -> "Voice test. The translator is ready."
                        TranslationLanguage.SPANISH -> "Prueba de voz. El traductor está listo."
                        TranslationLanguage.FRENCH -> "Test de voix. Le traducteur est prêt."
                        TranslationLanguage.JAPANESE -> "音声テスト。翻訳の準備ができました。"
                    },
                    language,
                )
            }
        }
    }

    private companion object {
        const val TAG = "JarvisLiveTranslate"
    }
}
