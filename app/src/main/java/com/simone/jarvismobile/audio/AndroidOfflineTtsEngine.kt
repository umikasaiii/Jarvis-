package com.simone.jarvismobile.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Android offline TTS. Selects an Italian voice that is NOT network-required,
 * routes to the voice-communication stream (so it follows AirPods), and exposes
 * a suspend [speak] that completes when the utterance ends.
 *
 * Not compiled in the scaffolding container (no Android SDK); validated on-device.
 */
@Singleton
class AndroidOfflineTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextToSpeechEngine {

    private val _state = MutableStateFlow(TtsState.IDLE)
    override val state = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun ensureReady(): Boolean {
        if (tts != null) return true
        val ready = suspendCancellableCoroutine { cont ->
            val engine = TextToSpeech(context) { status ->
                cont.resume(status == TextToSpeech.SUCCESS)
            }
            tts = engine
        }
        if (!ready) {
            _state.value = TtsState.ERROR
            return false
        }
        configureEngine()
        return selectOfflineItalianVoice()
    }

    private fun configureEngine() {
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        // Slightly slower than default for a calm delivery (docs §16).
        engine.setSpeechRate(0.95f)
        engine.setPitch(0.98f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { _state.value = TtsState.SPEAKING }
            override fun onDone(utteranceId: String) {
                _state.value = TtsState.IDLE
                pending.remove(utteranceId)?.complete(Unit)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = onError(utteranceId, -1)
            override fun onError(utteranceId: String, errorCode: Int) {
                _state.value = TtsState.ERROR
                pending.remove(utteranceId)?.complete(Unit)
            }
        })
    }

    /**
     * Picks an Italian voice, preferring a male, non-network voice. Returns false
     * when only network voices exist (we refuse to silently use the cloud).
     */
    private fun selectOfflineItalianVoice(): Boolean {
        val engine = tts ?: return false
        val italian = engine.voices?.filter {
            it.locale.language == Locale.ITALIAN.language &&
                !it.isNetworkConnectionRequired &&
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
        }.orEmpty()
        if (italian.isEmpty()) {
            _state.value = TtsState.ERROR
            return false
        }
        val chosen = italian.firstOrNull { it.isMale() } ?: italian.first()
        engine.voice = chosen
        return true
    }

    override suspend fun speak(text: String) {
        val engine = tts ?: return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            _state.value = TtsState.ERROR
            pending.remove(id)
            return
        }
        done.await()
    }

    override fun stop() {
        tts?.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
        _state.value = TtsState.IDLE
    }

    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun Voice.isMale(): Boolean {
        val n = name.lowercase()
        return n.contains("male") && !n.contains("female") || n.contains("-m-") || n.endsWith("-m")
    }
}
