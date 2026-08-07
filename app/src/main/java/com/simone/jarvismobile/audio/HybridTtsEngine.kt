package com.simone.jarvismobile.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.simone.jarvismobile.core.speech.SpeechShaper
import com.simone.jarvismobile.core.speech.SpeechStyle
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.tts.NeuralTtsRepository
import com.simone.jarvismobile.tts.PcmPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val android: AndroidOfflineTtsEngine,
    private val neural: NeuralTtsRepository,
    private val player: PcmPlayer,
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

        val voice = neural.state.value.selectedVoice
        val speed = style.rate.coerceIn(MIN_SPEED, MAX_SPEED)
        val volume = settings.ttsVolume.first()
        val streaming = settings.ttsStreamingEnabled.first()

        // Streaming off means one synthesis for the whole reply: slower to the
        // first word, but a single uninterrupted take.
        val chunks = if (streaming) {
            SpeechShaper.shape(text, style).map { it.text }.filter { it.isNotBlank() }
        } else {
            listOf(SpeechShaper.plain(text)).filter { it.isNotBlank() }
        }
        if (chunks.isEmpty()) return

        requestAudioFocus()
        _state.value = TtsState.SPEAKING
        stopped = false
        player.start(engine.sampleRate)
        player.setVolume(volume)
        try {
            for (chunk in chunks) {
                if (stopped || !currentCoroutineContext().isActive) break
                val pcm = engine.synthesize(chunk, voice, speed) ?: continue
                if (!player.write(pcm)) break
            }
            if (!stopped) player.drain() else player.stop()
            _state.value = TtsState.IDLE
        } catch (e: Throwable) {
            player.stop()
            _state.value = TtsState.ERROR
            _lastDetail.value = "neural_speak_failed ${e.javaClass.simpleName}"
            Log.w(TAG, "neural_speak_failed ${e.javaClass.simpleName}")
        } finally {
            abandonAudioFocus()
        }
    }

    @Volatile private var stopped = false

    override fun stop() {
        stopped = true
        player.stop()
        android.stop()
        abandonAudioFocus()
        _state.value = TtsState.IDLE
    }

    override fun shutdown() {
        stop()
        android.shutdown()
        neural.unload()
    }

    // --- audio focus ------------------------------------------------------
    // The Android engine holds its own focus; the neural path has to hold one
    // too or music keeps playing straight through the reply.

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            stop()
        }
    }

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(true)
            .build()
        focusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { req -> runCatching { audioManager.abandonAudioFocusRequest(req) } }
        focusRequest = null
    }

    private companion object {
        const val TAG = "JarvisHybridTts"
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
    }
}
