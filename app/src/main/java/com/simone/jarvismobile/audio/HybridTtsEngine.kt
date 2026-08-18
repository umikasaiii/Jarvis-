package com.simone.jarvismobile.audio

import android.util.Log
import com.simone.jarvismobile.core.speech.SpeechShaper
import com.simone.jarvismobile.core.speech.SpeechStyle
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.tts.AudioFocusGate
import com.simone.jarvismobile.tts.NeuralTtsRepository
import com.simone.jarvismobile.tts.PcmPlayer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The engine the rest of the app talks to.
 *
 * It implements the existing [TextToSpeechEngine] contract and decides, per
 * utterance, whether to answer with the imported neural voice or with the
 * Android one. [SessionCoordinator] is unchanged and unaware: the reply text
 * arrives here exactly as before, so nothing about recognition, transcription,
 * memory or routing is touched by any of this.
 *
 * Long replies are synthesised sentence by sentence and pushed into an already
 * open [PcmPlayer], so the first sentence is audible while the second is still
 * being generated. The sentence split is [SpeechShaper]'s — the same one the
 * Android path uses for its pauses — so both voices phrase a reply the same way.
 */
@Singleton
class HybridTtsEngine @Inject constructor(
    private val android: AndroidOfflineTtsEngine,
    private val neural: NeuralTtsRepository,
    private val player: PcmPlayer,
    private val focus: AudioFocusGate,
    private val settings: SettingsRepository,
) : TextToSpeechEngine {

    private val _state = MutableStateFlow(TtsState.IDLE)
    override val state: StateFlow<TtsState> = _state.asStateFlow()

    // Voice enumeration stays the Android engine's job: the neural voice list is
    // a different thing entirely and is presented in its own Settings section.
    override val selectedVoiceName: StateFlow<String?> get() = android.selectedVoiceName
    override val availableVoices: StateFlow<List<TtsVoiceOption>> get() = android.availableVoices

    private val _lastDetail = MutableStateFlow("")
    override val lastDetail: StateFlow<String> = _lastDetail.asStateFlow()

    @Volatile private var style: SpeechStyle = SpeechStyle.NATURALE
    @Volatile private var neuralActive = false

    override suspend fun ensureReady(): Boolean {
        // Speech switched off is a valid, silent state — not a failure. Reporting
        // it as one would light up "tts_unavailable" in the chat every turn.
        if (!settings.ttsSpeechEnabled.first()) {
            _lastDetail.value = "risposta vocale disattivata"
            return true
        }
        if (neural.isConfigured()) {
            val engine = neural.ensureLoaded()
            if (engine != null) {
                neuralActive = true
                _lastDetail.value = "voce esterna: ${engine.label}"
                return true
            }
            // Configured but unloadable: fall through to Android rather than go
            // mute. A broken import must not cost the user their assistant.
            Log.w(TAG, "neural_unavailable_falling_back")
        }
        neuralActive = false
        val ok = android.ensureReady()
        _lastDetail.value = if (ok) android.lastDetail.value else "android: ${android.lastDetail.value}"
        return ok
    }

    override suspend fun refreshVoices(): List<TtsVoiceOption> = android.refreshVoices()

    override suspend fun configure(voiceName: String?, speechRate: Float, pitch: Float): Boolean =
        android.configure(voiceName, speechRate, pitch)

    override fun setStyle(style: SpeechStyle) {
        this.style = style.coerced()
        android.setStyle(style)
    }

    override suspend fun speak(text: String) {
        if (!settings.ttsSpeechEnabled.first()) return
        if (!neuralActive) {
            android.speak(text)
            return
        }
        val engine = neural.ensureLoaded() ?: run {
            neuralActive = false
            android.speak(text)
            return
        }

        val voice = neural.currentVoice()
        val speed = style.rate.coerceIn(MIN_SPEED, MAX_SPEED)
        val volume = settings.ttsVolume.first()
        val streaming = settings.ttsStreamingEnabled.first()

        // Both modes speak the same segments with the same silences between
        // them; they differ only in when the audio starts. Streaming plays each
        // sentence as it comes out of the model, so the first word is quick;
        // with streaming off everything is generated first and then played as
        // one take, which starts later but can never run dry mid-answer.
        val segments = SpeechShaper.shape(text, style).filter { it.text.isNotBlank() }
        if (segments.isEmpty()) return

        focus.acquire { stop() }
        _state.value = TtsState.SPEAKING
        stopped = false
        player.start(engine.sampleRate)
        player.setVolume(volume)
        try {
            if (streaming) {
                for (segment in segments) {
                    if (stopped || !currentCoroutineContext().isActive) break
                    val pcm = engine.synthesize(segment.text, voice, speed) ?: continue
                    if (!player.write(pcm)) break
                    if (!writePause(segment.pauseMs, engine.sampleRate)) break
                }
            } else {
                val take = ArrayList<FloatArray>(segments.size * 2)
                for (segment in segments) {
                    if (stopped || !currentCoroutineContext().isActive) break
                    engine.synthesize(segment.text, voice, speed)?.let { take += it }
                    silenceFor(segment.pauseMs, engine.sampleRate)?.let { take += it }
                }
                for (piece in take) {
                    if (stopped || !player.write(piece)) break
                }
            }
            if (!stopped) player.drain() else player.stop()
            _state.value = TtsState.IDLE
        } catch (e: Throwable) {
            player.stop()
            _state.value = TtsState.ERROR
            _lastDetail.value = "neural_speak_failed ${e.javaClass.simpleName}"
            Log.w(TAG, "neural_speak_failed ${e.javaClass.simpleName}")
        } finally {
            focus.release()
        }
    }

    /**
     * The silence that follows a sentence. Rhythm is what makes a reply sound
     * spoken rather than recited, and a neural engine has no notion of it: the
     * pause has to be pushed into the track as actual samples. This is what the
     * "Durata delle pause" and "Espressività" settings act on — [SpeechShaper]
     * turns them into per-segment milliseconds and they are honoured here.
     */
    private fun silenceFor(pauseMs: Long, sampleRate: Int): FloatArray? {
        if (pauseMs <= 0) return null
        val samples = (sampleRate * pauseMs.coerceAtMost(MAX_PAUSE_MS) / 1000L).toInt()
        return if (samples > 0) FloatArray(samples) else null
    }

    private suspend fun writePause(pauseMs: Long, sampleRate: Int): Boolean {
        val silence = silenceFor(pauseMs, sampleRate) ?: return true
        return player.write(silence)
    }

    @Volatile private var stopped = false

    override fun stop() {
        stopped = true
        // Interrupts an in-flight native synthesis call, not just the loop that
        // hands its output to the player — see NeuralTtsEngine.cancelSynthesis().
        neural.cancelActiveSynthesis()
        player.stop()
        android.stop()
        focus.release()
        _state.value = TtsState.IDLE
    }

    override fun shutdown() {
        stop()
        android.shutdown()
        neural.unload()
    }

    private companion object {
        const val TAG = "JarvisHybridTts"
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f

        /** A pause longer than this is a settings mistake, not a breath. */
        const val MAX_PAUSE_MS = 2_000L
    }
}
