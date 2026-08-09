package com.simone.jarvismobile.translate

import android.util.Log
import com.simone.jarvismobile.audio.SpeechToTextEngine
import com.simone.jarvismobile.audio.SttResult
import com.simone.jarvismobile.core.translate.ConversationSegment
import com.simone.jarvismobile.core.translate.LanguageStabilizer
import com.simone.jarvismobile.core.translate.LivePairRouter
import com.simone.jarvismobile.core.translate.LiveTranslationState
import com.simone.jarvismobile.core.translate.TranslationLanguage
import com.simone.jarvismobile.core.translate.TranslationMode
import com.simone.jarvismobile.core.translate.TranslationResult
import com.simone.jarvismobile.core.translate.TranslationSegmenter
import com.simone.jarvismobile.core.translate.TranslationSession
import com.simone.jarvismobile.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Live Translator pipeline: **listen → segment → detect → translate → speak**,
 * looping for as long as the session is active. It reuses the existing offline STT
 * ([SpeechToTextEngine]) and a dedicated multilingual TTS
 * ([TranslationAudioController]); translation is ML Kit on-device
 * ([MlKitTranslationEngine]). The assistant's own LLM is never involved — this is
 * a pure translation path, so it stays fast and works in airplane mode once the
 * language models are present.
 *
 * The loop is deliberately sequential (listen, then speak, then listen again): the
 * microphone is never open while a translation is being read aloud, which is what
 * keeps JARVIS from hearing its own output and translating it back (echo).
 *
 * Honest limits: the platform recognizer transcribes one language at a time, so
 * AUTO detection listens in the *currently active* language and flips sides via
 * the [LanguageStabilizer] once the other speaker has said enough — it is not
 * simultaneous dual-language capture, which the on-device recognizer cannot do.
 */
@Singleton
class LiveTranslatorManager @Inject constructor(
    private val stt: SpeechToTextEngine,
    private val engineRouter: TranslationEngineRouter,
    private val detector: MlKitLanguageDetector,
    private val audio: TranslationAudioController,
    private val models: TranslationModelManager,
    private val settings: SettingsRepository,
) {
    // A last-resort net: an uncaught exception in any launched coroutine here
    // would otherwise reach the thread's default handler and CRASH the app (the
    // device symptom: "as soon as it hears a word the app closes"). Instead we
    // surface it as an Error state and keep the app alive.
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "live_translate_uncaught ${e.javaClass.simpleName}: ${e.message}")
        _state.value = LiveTranslationState.Error("crash:${e.javaClass.simpleName}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)

    private val _state = MutableStateFlow<LiveTranslationState>(LiveTranslationState.Idle)
    val state: StateFlow<LiveTranslationState> = _state.asStateFlow()

    private val _session = MutableStateFlow<TranslationSession?>(null)
    val session: StateFlow<TranslationSession?> = _session.asStateFlow()

    private val _segments = MutableStateFlow<List<ConversationSegment>>(emptyList())
    val segments: StateFlow<List<ConversationSegment>> = _segments.asStateFlow()

    /** Live partial transcript from the recognizer, for the UI. */
    val partial: StateFlow<String> = stt.partial

    private val _listeningLanguage = MutableStateFlow<TranslationLanguage?>(null)
    /** Which language the recognizer is currently transcribing (UI hint). */
    val listeningLanguage: StateFlow<TranslationLanguage?> = _listeningLanguage.asStateFlow()

    private val _openScreenRequest = MutableStateFlow(0)
    /**
     * Bumped when a session is started by voice/chat command, so the UI can bring
     * the Live Translator screen to the front (the user asked for it out loud, so
     * they expect to see it — not just have it running in the background).
     */
    val openScreenRequest: StateFlow<Int> = _openScreenRequest.asStateFlow()

    /** Asks the UI to open the translator screen. */
    fun requestOpenScreen() { _openScreenRequest.value = _openScreenRequest.value + 1 }

    /** Why the last spoken translation was or wasn't heard (missing voice, etc.). */
    val audioDetail: StateFlow<String> = audio.lastDetail

    private val stabilizer = LanguageStabilizer(minConfidence = 0.5f, switchThreshold = 2)
    private val segmentIds = AtomicLong(0)
    private val controlMutex = Mutex()

    private var loop: Job? = null

    /** True while a session is active (Ready/Listening/…), for the UI and router. */
    val isRunning: Boolean get() = _state.value.isActive

    /**
     * Starts a session for the given pair. Ensures both language models are
     * present (downloading them when allowed), then runs the continuous loop for
     * auto/listen modes. For [TranslationMode.PUSH_TO_TALK] it prepares and waits
     * for explicit [pushToTalk] turns.
     */
    fun start(session: TranslationSession) {
        scope.launch {
            controlMutex.withLock {
                if (_state.value.isActive) return@withLock
                _session.value = session
                _segments.value = emptyList()
                stabilizer.reset()
                _state.value = LiveTranslationState.CheckingModels

                if (!ensureModels(session)) return@withLock

                audio.ensureReady()
                _state.value = LiveTranslationState.Ready
                _listeningLanguage.value = session.languageA
                // Every mode runs the continuous listen loop except push-to-talk,
                // which stays Ready and waits for an explicit pushToTalk() turn.
                if (session.mode != TranslationMode.PUSH_TO_TALK) startLoop(session)
            }
        }
    }

    /** Ensures both models are downloaded; downloads if the policy permits. */
    private suspend fun ensureModels(session: TranslationSession): Boolean {
        models.refresh()
        val needed = listOf(session.languageA, session.languageB)
        if (models.allDownloaded(needed)) return true
        val wifiOnly = runCatching { settings.translateWifiOnly.first() }.getOrDefault(true)
        for (lang in needed) {
            if (!models.isDownloaded(lang)) {
                _state.value = LiveTranslationState.DownloadingModels(lang)
                val ok = models.download(lang, wifiOnly)
                if (!ok) {
                    _state.value = LiveTranslationState.Error("model_download_failed:${lang.code}")
                    return false
                }
            }
        }
        return true
    }

    private fun startLoop(session: TranslationSession) {
        loop?.cancel()
        loop = scope.launch {
            val segmenter = TranslationSegmenter()
            while (isActive) {
                try {
                    val listenLang = stabilizer.current ?: session.languageA
                    _listeningLanguage.value = listenLang
                    _state.value = LiveTranslationState.Listening
                    val result = stt.transcribe(listenLang.recognizerTag)
                    if (!isActive) break
                    when (result) {
                        is SttResult.Text -> handleFinal(session, segmenter, result.text)
                        SttResult.NoSpeech -> { /* nothing said; loop and keep listening */ }
                        is SttResult.Unavailable -> {
                            _state.value = LiveTranslationState.Error("stt_unavailable:${result.reason}")
                            break
                        }
                        is SttResult.Failure -> {
                            // Transient recognizer errors shouldn't kill the session.
                            Log.w(TAG, "stt_failure ${result.code}")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // One bad segment (a translate/TTS hiccup) must not crash the
                    // app or kill the loop: log, keep listening.
                    Log.w(TAG, "live_translate_iteration_failed ${e.javaClass.simpleName}: ${e.message}")
                }
                if (_state.value.isActive && _state.value !is LiveTranslationState.Error) {
                    _state.value = LiveTranslationState.Ready
                }
            }
        }
    }

    /** A completed recognizer result: split into segments and translate each. */
    private suspend fun handleFinal(
        session: TranslationSession,
        segmenter: TranslationSegmenter,
        text: String,
    ) {
        val now = System.currentTimeMillis()
        val pieces = segmenter.offer(text, isFinal = true, nowMs = now)
        for (piece in pieces) {
            translateAndMaybeSpeak(session, piece)
        }
    }

    private suspend fun translateAndMaybeSpeak(session: TranslationSession, sourceText: String) {
        _state.value = LiveTranslationState.Translating
        // Detect the language and stabilise; a low-confidence or foreign guess
        // holds the current side rather than mistranslating.
        val detected = detector.detect(sourceText)
        val stable = stabilizer.offer(detected.language, detected.confidence)
            ?: session.languageA
        val (source, target) = LivePairRouter.route(stable, session.languageA, session.languageB)
            ?: return

        val engine = engineRouter.engine()
        val result = engine.translate(sourceText, source.code, target.code)
        val translated = when (result) {
            is TranslationResult.Ok -> result.text
            is TranslationResult.Failure -> {
                Log.w(TAG, "translate_failed ${result.code}")
                return
            }
        }

        val segment = ConversationSegment(
            id = segmentIds.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            detectedLanguage = source,
            sourceText = sourceText,
            translatedText = translated,
            targetLanguage = target,
            confidence = detected.confidence,
            translationLatencyMs = (result as? TranslationResult.Ok)?.latencyMs ?: 0L,
        )
        _segments.value = (_segments.value + segment).takeLast(MAX_SEGMENTS)

        val speak = session.mode.speaks && session.ttsEnabled &&
            runCatching { settings.translateTtsEnabled.first() }.getOrDefault(true)
        if (speak) {
            _state.value = LiveTranslationState.Speaking
            audio.speak(translated, target)
        }
    }

    /**
     * One explicit push-to-talk turn: listen in [source] and translate to the
     * other side of the pair. Used by [TranslationMode.PUSH_TO_TALK] where the
     * speaker's language is chosen by a button, not auto-detected.
     */
    fun pushToTalk(source: TranslationLanguage) {
        val session = _session.value ?: return
        scope.launch {
          try {
            if (_state.value == LiveTranslationState.Speaking) return@launch
            val target = if (source == session.languageA) session.languageB else session.languageA
            _listeningLanguage.value = source
            _state.value = LiveTranslationState.Listening
            when (val result = stt.transcribe(source.recognizerTag)) {
                is SttResult.Text -> {
                    val engine = engineRouter.engine()
                    when (val tr = engine.translate(result.text, source.code, target.code)) {
                        is TranslationResult.Ok -> {
                            val segment = ConversationSegment(
                                id = segmentIds.incrementAndGet(),
                                timestamp = System.currentTimeMillis(),
                                detectedLanguage = source,
                                sourceText = result.text,
                                translatedText = tr.text,
                                targetLanguage = target,
                                translationLatencyMs = tr.latencyMs,
                            )
                            _segments.value = (_segments.value + segment).takeLast(MAX_SEGMENTS)
                            val speak = session.ttsEnabled &&
                                runCatching { settings.translateTtsEnabled.first() }.getOrDefault(true)
                            if (speak) {
                                _state.value = LiveTranslationState.Speaking
                                audio.speak(tr.text, target)
                            }
                        }
                        is TranslationResult.Failure -> Log.w(TAG, "ptt_translate_failed ${tr.code}")
                    }
                }
                else -> Unit
            }
            _state.value = LiveTranslationState.Ready
          } catch (e: CancellationException) {
              throw e
          } catch (e: Throwable) {
              Log.w(TAG, "ptt_failed ${e.javaClass.simpleName}: ${e.message}")
              _state.value = LiveTranslationState.Ready
          }
        }
    }

    /** Swaps the two languages of the running session. */
    fun swap() {
        val session = _session.value ?: return
        _session.value = session.swapped()
        stabilizer.reset()
        _listeningLanguage.value = _session.value?.languageA
    }

    fun pause() {
        stt.cancel()
        audio.stop()
        loop?.cancel()
        loop = null
        _state.value = LiveTranslationState.Paused
    }

    fun resume() {
        val session = _session.value ?: return
        if (_state.value != LiveTranslationState.Paused) return
        if (session.mode != TranslationMode.PUSH_TO_TALK) startLoop(session)
        _state.value = LiveTranslationState.Ready
    }

    fun stop() {
        scope.launch {
            _state.value = LiveTranslationState.Stopping
            stt.cancel()
            audio.stop()
            loop?.cancel()
            loop = null
            _listeningLanguage.value = null
            _session.value = null
            _state.value = LiveTranslationState.Idle
        }
    }

    fun clearTranscript() {
        _segments.value = emptyList()
    }

    private companion object {
        const val TAG = "JarvisLiveTranslate"
        const val MAX_SEGMENTS = 200
    }
}
