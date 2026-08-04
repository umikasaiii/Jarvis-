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

    private val _selectedVoiceName = MutableStateFlow<String?>(null)
    override val selectedVoiceName = _selectedVoiceName.asStateFlow()

    private val _lastDetail = MutableStateFlow("")
    override val lastDetail = _lastDetail.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun ensureReady(): Boolean {
        if (ready) return true
        val engine = tts ?: run {
            val created = suspendCancellableCoroutine { cont ->
                var ref: TextToSpeech? = null
                ref = TextToSpeech(context) { status ->
                    cont.resume(if (status == TextToSpeech.SUCCESS) ref else null)
                }
            }
            if (created == null) {
                _state.value = TtsState.ERROR
                _lastDetail.value = "init_failed (no usable TTS engine)"
                return false
            }
            tts = created
            configureEngine(created)
            created
        }
        ready = setupLanguageAndVoice(engine)
        if (!ready) _state.value = TtsState.ERROR
        return ready
    }

    private fun configureEngine(engine: TextToSpeech) {
        // Play through the media output so the reply is audible on the phone
        // loudspeaker (and on AirPods via A2DP when connected). Communication
        // usage could route to the quiet earpiece with no comm device active.
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
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
     * Chooses a language + voice, tolerant of devices without a dedicated offline
     * Italian voice. Order of preference:
     *   1. Italian, if its data is installed (offline).
     *   2. the device default locale, if installed offline.
     * Then an offline (non-network) voice for that language, preferring a male
     * one; else any offline voice. We never silently use a network-only voice —
     * but we do fall back to an installed language rather than failing outright.
     * Returns false only when no offline voice/language is available at all.
     */
    private fun setupLanguageAndVoice(engine: TextToSpeech): Boolean {
        val itAvail = isInstalledOffline(engine, Locale.ITALIAN)
        val defLoc = Locale.getDefault()
        val defAvail = isInstalledOffline(engine, defLoc)
        val chosenLocale = when {
            itAvail -> Locale.ITALIAN
            defAvail -> defLoc
            else -> null
        }
        if (chosenLocale != null) {
            runCatching { engine.language = chosenLocale }
        }

        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        val offline = voices.filter { !it.isNetworkConnectionRequired }
        val forLocale = offline.filter { chosenLocale != null && it.locale.language == chosenLocale.language }
        val chosen = forLocale.firstOrNull { it.isMale() }
            ?: forLocale.firstOrNull()
            ?: offline.firstOrNull { it.isMale() }
            ?: offline.firstOrNull()

        val base = "engine=${runCatching { engine.defaultEngine }.getOrNull()} " +
            "it=$itAvail def=${defLoc.language}:$defAvail voices=${voices.size} offline=${offline.size}"

        if (chosen != null) {
            runCatching { engine.voice = chosen }
            _selectedVoiceName.value = chosen.name
            _lastDetail.value = "ok voice=${chosen.name} $base"
            return true
        }
        // No enumerable offline voice, but the engine may still synthesize offline
        // for an installed language (some engines expose few Voice objects).
        if (chosenLocale != null) {
            _selectedVoiceName.value = "engine:${chosenLocale.language}"
            _lastDetail.value = "engine_lang=${chosenLocale.language} $base"
            return true
        }
        _selectedVoiceName.value = null
        _lastDetail.value = "no_offline_voice $base"
        return false
    }

    private fun isInstalledOffline(engine: TextToSpeech, locale: Locale): Boolean {
        val r = runCatching { engine.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        return r == TextToSpeech.LANG_AVAILABLE ||
            r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
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
        ready = false
    }

    private fun Voice.isMale(): Boolean {
        val n = name.lowercase()
        return n.contains("male") && !n.contains("female") || n.contains("-m-") || n.endsWith("-m")
    }
}
