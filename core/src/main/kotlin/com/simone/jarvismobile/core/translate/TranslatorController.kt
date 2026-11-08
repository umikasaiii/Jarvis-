package com.simone.jarvismobile.core.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The control logic of Live Translator (BETA 5), free of Android types so it is unit-tested with fakes:
 * listen → (detect) → translate → speak, in push-to-talk or continuous mode, plus text translation that
 * needs no microphone. The Android pieces (on-device STT, ML Kit, TTS) plug in through the ports in
 * `TranslatorPorts.kt`.
 *
 * Guarantees, all covered by tests:
 *  - **No stale results.** Every start / stop / pause / mode change bumps an epoch; a turn that was
 *    started under an older epoch can no longer publish a state, a segment or a notice, nor speak.
 *  - **Single flight.** Only one listening turn exists at a time (two quick push-to-talk presses never
 *    open two recognitions).
 *  - **No echo.** Listening is sequential with speaking (never both), a short settle pause follows each
 *    spoken translation, and a result that matches the just-spoken text, or repeats the previous one,
 *    is dropped ([EchoGuard]).
 *  - **No hot loop.** Transient recognizer failures back off and end the session after repeated failure.
 *
 * Honest limit: the recognizer hears ONE language at a time. Automatic modes listen in the active
 * language and switch sides via [LanguageStabilizer]; they do not hear two languages at once.
 */
class TranslatorController(
    private val scope: CoroutineScope,
    private val recognizer: Recognizer,
    private val engine: TranslationEngine,
    private val speaker: Speaker,
    private val detector: LanguageDetector,
    private val models: ModelGate,
    private val prefs: TranslatorPrefs,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val settleDelayMs: Long = SETTLE_MS,
    private val retryBaseMs: Long = RETRY_BASE_MS,
) {
    private val _state = MutableStateFlow<LiveTranslationState>(LiveTranslationState.Idle)
    val state: StateFlow<LiveTranslationState> = _state.asStateFlow()

    private val _session = MutableStateFlow<TranslationSession?>(null)
    val session: StateFlow<TranslationSession?> = _session.asStateFlow()

    private val _segments = MutableStateFlow<List<ConversationSegment>>(emptyList())
    val segments: StateFlow<List<ConversationSegment>> = _segments.asStateFlow()

    private val _listening = MutableStateFlow<TranslationLanguage?>(null)
    val listeningLanguage: StateFlow<TranslationLanguage?> = _listening.asStateFlow()

    private val _notice = MutableStateFlow<TranslatorNotice?>(null)
    val notice: StateFlow<TranslatorNotice?> = _notice.asStateFlow()

    private val epoch = AtomicLong(0)
    private val turnBusy = AtomicBoolean(false)
    private val segmentIds = AtomicLong(0)
    private val stabilizer = LanguageStabilizer(minConfidence = 0.5f, switchThreshold = 2)
    private val echo = EchoGuard()
    private var startJob: Job? = null
    private var loopJob: Job? = null
    private var turnJob: Job? = null

    val isRunning: Boolean get() = _state.value.isActive

    fun clearNotice() { _notice.value = null }
    fun clearTranscript() { _segments.value = emptyList() }

    // ------------------------------------------------------------------ session control

    fun start(session: TranslationSession) {
        val my: Long
        synchronized(this) {
            val st = _state.value
            if (st.isActive || st is LiveTranslationState.CheckingModels || st is LiveTranslationState.DownloadingModels) return
            my = epoch.incrementAndGet()
            _session.value = session
            _segments.value = emptyList()
            _notice.value = null
            stabilizer.reset(); echo.reset()
            _state.value = LiveTranslationState.CheckingModels
        }
        startJob = scope.launch {
            val needed = TranslationModels.required(session.languageA, session.languageB)
            var missing = models.missing(needed)
            for (lang in missing) {
                if (!models.canDownloadNow()) break // offline (or Wi-Fi-only without Wi-Fi): never try
                if (!setIf(my) { _state.value = LiveTranslationState.DownloadingModels(lang) }) return@launch
                models.download(lang)
            }
            if (missing.isNotEmpty()) missing = models.missing(needed)
            if (missing.isNotEmpty()) {
                setIf(my) {
                    _notice.value = TranslatorNotice.ModelMissing(missing)
                    _state.value = LiveTranslationState.Error("model_missing:${missing.first().code}")
                }
                return@launch
            }
            setIf(my) {
                _state.value = LiveTranslationState.Ready
                _listening.value = session.languageA
                if (session.mode != TranslationMode.PUSH_TO_TALK) startLoopLocked(session, my)
            }
        }
    }

    /** Ends the session now. Anything still in flight is cancelled and can no longer publish. */
    fun stop() = synchronized(this) {
        epoch.incrementAndGet()
        cancelWorkLocked()
        _listening.value = null
        _session.value = null
        _state.value = LiveTranslationState.Idle
    }

    fun pause() = synchronized(this) {
        if (_session.value == null || _state.value is LiveTranslationState.Paused) return@synchronized
        epoch.incrementAndGet()
        cancelWorkLocked()
        _state.value = LiveTranslationState.Paused
    }

    fun resume() = synchronized(this) {
        val session = _session.value ?: return@synchronized
        if (_state.value != LiveTranslationState.Paused) return@synchronized
        _state.value = LiveTranslationState.Ready
        if (session.mode != TranslationMode.PUSH_TO_TALK) startLoopLocked(session, epoch.get())
    }

    /** Swaps the two languages for the NEXT turns; a turn already in flight keeps the pair it started with. */
    fun swap() = synchronized(this) {
        val s = _session.value ?: return@synchronized
        _session.value = s.swapped()
        stabilizer.reset()
        _listening.value = _session.value?.languageA
    }

    /** Changes the mode of a running session: the current turn is dropped and the new mode takes over. */
    fun changeMode(mode: TranslationMode) = synchronized(this) {
        val s = _session.value ?: return@synchronized
        val st = _state.value
        if (st is LiveTranslationState.CheckingModels || st is LiveTranslationState.DownloadingModels || st is LiveTranslationState.Error) return@synchronized
        if (s.mode == mode) return@synchronized
        epoch.incrementAndGet()
        cancelWorkLocked()
        echo.reset()
        val next = s.copy(mode = mode)
        _session.value = next
        if (st is LiveTranslationState.Paused) return@synchronized
        _state.value = LiveTranslationState.Ready
        if (mode != TranslationMode.PUSH_TO_TALK) startLoopLocked(next, epoch.get())
    }

    // ------------------------------------------------------------------ push to talk

    /**
     * One explicit turn: listen in [source], translate to the other side. A second press of the SAME button
     * while listening ends that listening; any other press while busy is ignored with a [TranslatorNotice.Busy].
     */
    fun pushToTalk(source: TranslationLanguage) {
        val my: Long
        val session: TranslationSession
        synchronized(this) {
            session = _session.value ?: return
            if (session.mode != TranslationMode.PUSH_TO_TALK) return
            val st = _state.value
            if (st is LiveTranslationState.Listening && turnBusy.get() && _listening.value == source) {
                // Same button again: stop listening, discard the turn.
                epoch.incrementAndGet()
                turnJob?.cancel(); recognizer.cancel()
                turnBusy.set(false)
                _state.value = LiveTranslationState.Ready
                return
            }
            if (st != LiveTranslationState.Ready) {
                if (st is LiveTranslationState.Listening || st is LiveTranslationState.Translating ||
                    st is LiveTranslationState.Speaking || st is LiveTranslationState.Transcribing
                ) _notice.value = TranslatorNotice.Busy
                return
            }
            if (!turnBusy.compareAndSet(false, true)) { _notice.value = TranslatorNotice.Busy; return }
            my = epoch.get()
            _notice.value = null
            turnJob = scope.launch {
                try {
                    val end = runTurn(session, my, source)
                    if (end is TurnEnd.Fatal) setIf(my) { _state.value = LiveTranslationState.Error(end.reason) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    setIf(my) { _notice.value = TranslatorNotice.TranslateFailed("errore") }
                } finally {
                    if (epoch.get() == my) turnBusy.set(false)
                    setIf(my) { if (_state.value !is LiveTranslationState.Error && _state.value !is LiveTranslationState.Paused) _state.value = LiveTranslationState.Ready }
                }
            }
        }
    }

    // ------------------------------------------------------------------ text (no microphone)

    /** Translates typed text. Needs only the offline models; never starts a session, the mic or a download. */
    suspend fun translateText(text: String, from: TranslationLanguage, to: TranslationLanguage): TextOutcome {
        val t = text.trim()
        if (t.isEmpty()) return TextOutcome.Empty
        if (t.length > MAX_TEXT_CHARS) return TextOutcome.TooLong
        if (from == to) return TextOutcome.Ok(t, 0)
        val missing = models.missing(TranslationModels.required(from, to))
        if (missing.isNotEmpty()) return TextOutcome.ModelMissing(missing)
        return when (val r = engine.translate(t, from.code, to.code)) {
            is TranslationResult.Ok -> TextOutcome.Ok(r.text, r.latencyMs)
            is TranslationResult.Failure -> TextOutcome.Failed(r.code)
        }
    }

    /** Reads a translated text aloud (text-card button). */
    suspend fun speakText(text: String, language: TranslationLanguage): SpeakOutcome = speaker.speak(text, language)

    // ------------------------------------------------------------------ the turn

    private sealed interface TurnEnd {
        data object Aborted : TurnEnd
        data object Silent : TurnEnd
        data object Dropped : TurnEnd
        data class Done(val spoke: Boolean) : TurnEnd
        data object Failed : TurnEnd
        data object Transient : TurnEnd
        data class Fatal(val reason: String) : TurnEnd
    }

    private fun startLoopLocked(session: TranslationSession, my: Long) {
        loopJob?.cancel()
        loopJob = scope.launch {
            var failures = 0
            while (isActive && epoch.get() == my) {
                val end = try {
                    runTurn(session, my, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    TurnEnd.Transient
                }
                when (end) {
                    TurnEnd.Aborted -> return@launch
                    is TurnEnd.Fatal -> { setIf(my) { _state.value = LiveTranslationState.Error(end.reason) }; return@launch }
                    TurnEnd.Transient -> {
                        failures++
                        if (failures >= MAX_FAILURES) {
                            setIf(my) { _state.value = LiveTranslationState.Error("stt_failed") }
                            return@launch
                        }
                        delay(retryBaseMs * failures)
                    }
                    is TurnEnd.Done -> { failures = 0; if (end.spoke) delay(settleDelayMs) }
                    else -> failures = 0
                }
                setIf(my) { if (_state.value.isActive && _state.value !is LiveTranslationState.Paused) _state.value = LiveTranslationState.Ready }
            }
        }
    }

    /**
     * [fixedSource] set = push-to-talk (the button chose the speaker); null = automatic (detect + stabilise).
     * The pair is captured from [session] at the start: a swap in the middle affects only the next turn.
     */
    private suspend fun runTurn(session: TranslationSession, my: Long, fixedSource: TranslationLanguage?): TurnEnd {
        val listenLang = fixedSource ?: stabilizer.current ?: session.languageA
        if (!setIf(my) { _listening.value = listenLang; _state.value = LiveTranslationState.Listening }) return TurnEnd.Aborted
        val heard = recognizer.listen(listenLang)
        if (epoch.get() != my) return TurnEnd.Aborted
        when (heard) {
            is Heard.Silence -> return TurnEnd.Silent
            is Heard.Failure -> return TurnEnd.Transient
            is Heard.LanguageUnavailable -> {
                setIf(my) { _notice.value = TranslatorNotice.RecognizerLanguageMissing(heard.language) }
                return TurnEnd.Fatal("stt_language:${heard.language.code}")
            }
            is Heard.Unavailable -> {
                setIf(my) { _notice.value = TranslatorNotice.RecognizerUnavailable }
                return TurnEnd.Fatal("stt_unavailable")
            }
            is Heard.Text -> Unit
        }
        val text = (heard as Heard.Text).text.trim()
        if (text.isEmpty() || echo.shouldDrop(text, clock())) return TurnEnd.Dropped

        if (!setIf(my) { _state.value = LiveTranslationState.Translating }) return TurnEnd.Aborted
        val source: TranslationLanguage
        val target: TranslationLanguage
        var confidence = 1f
        if (fixedSource != null) {
            source = fixedSource
            target = if (fixedSource == session.languageA) session.languageB else session.languageA
        } else {
            val d = detector.detect(text)
            if (epoch.get() != my) return TurnEnd.Aborted
            confidence = d.confidence
            val stable = stabilizer.offer(d.language, d.confidence) ?: session.languageA
            val routed = LivePairRouter.route(stable, session.languageA, session.languageB) ?: return TurnEnd.Dropped
            source = routed.first; target = routed.second
        }

        val result = engine.translate(text, source.code, target.code)
        if (epoch.get() != my) return TurnEnd.Aborted
        val ok = when (result) {
            is TranslationResult.Ok -> result
            is TranslationResult.Failure -> {
                setIf(my) { _notice.value = TranslatorNotice.TranslateFailed(result.code) }
                return TurnEnd.Failed
            }
        }

        val segment = ConversationSegment(
            id = segmentIds.incrementAndGet(),
            timestamp = clock(),
            detectedLanguage = source,
            sourceText = text,
            translatedText = ok.text,
            targetLanguage = target,
            confidence = confidence,
            translationLatencyMs = ok.latencyMs,
        )
        if (!setIf(my) { _segments.value = (_segments.value + segment).takeLast(MAX_SEGMENTS) }) return TurnEnd.Aborted

        val speak = (session.mode == TranslationMode.PUSH_TO_TALK || session.mode.speaks) && session.ttsEnabled && prefs.ttsEnabled()
        if (!speak) return TurnEnd.Done(false)
        if (!setIf(my) { _state.value = LiveTranslationState.Speaking }) return TurnEnd.Aborted
        echo.recordSpoken(ok.text, clock())
        val out = speaker.speak(ok.text, target)
        if (epoch.get() != my) return TurnEnd.Aborted
        echo.recordSpoken(ok.text, clock()) // restart the echo window from the END of the speech
        when (out) {
            SpeakOutcome.NO_VOICE -> setIf(my) { _notice.value = TranslatorNotice.NoVoice(target) }
            SpeakOutcome.FAILED -> setIf(my) { _notice.value = TranslatorNotice.SpeakFailed }
            SpeakOutcome.SPOKEN -> Unit
        }
        return TurnEnd.Done(out == SpeakOutcome.SPOKEN)
    }

    // ------------------------------------------------------------------ helpers

    /** Runs [block] only if [my] is still the current epoch, atomically with stop/pause. */
    private inline fun setIf(my: Long, block: () -> Unit): Boolean = synchronized(this) {
        if (epoch.get() != my) false else { block(); true }
    }

    private fun cancelWorkLocked() {
        startJob?.cancel(); loopJob?.cancel(); turnJob?.cancel()
        startJob = null; loopJob = null; turnJob = null
        turnBusy.set(false)
        recognizer.cancel()
        speaker.stop()
    }

    companion object {
        const val MAX_SEGMENTS = 200
        const val MAX_TEXT_CHARS = 4_000
        const val MAX_FAILURES = 5
        const val SETTLE_MS = 350L
        const val RETRY_BASE_MS = 500L
    }
}
