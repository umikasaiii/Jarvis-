package com.simone.jarvismobile.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Android offline TTS. Selects an Italian voice that is NOT network-required,
 * routes to the media output (so it follows AirPods), and exposes
 * a suspend [speak] that completes when the utterance ends.
 *
 * Not compiled in the scaffolding container (no Android SDK); validated on-device.
 */
@Singleton
class AndroidOfflineTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : TextToSpeechEngine {

    private val _state = MutableStateFlow(TtsState.IDLE)
    override val state = _state.asStateFlow()

    private val _selectedVoiceName = MutableStateFlow<String?>(null)
    override val selectedVoiceName = _selectedVoiceName.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<TtsVoiceOption>>(emptyList())
    override val availableVoices = _availableVoices.asStateFlow()

    private val _lastDetail = MutableStateFlow("")
    override val lastDetail = _lastDetail.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val initMutex = Mutex()

    // --- Audio focus (Phase 4) ------------------------------------------------
    // While JARVIS speaks we hold TRANSIENT audio focus so music/podcasts pause
    // (or duck) and then resume when we finish — proper audio manners. Focus is
    // scoped to playback ONLY, never to listening: requesting focus around the
    // recognizer is what previously broke mic capture on MagicOS.
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // If something with higher priority takes over (e.g. a phone call), stop
        // talking rather than speak over it.
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
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

    override suspend fun ensureReady(): Boolean = initMutex.withLock {
        if (ready) return@withLock true
        val engine = tts ?: createEngine() ?: return@withLock false
        val requested = settings.ttsVoiceName.first()
        val rate = settings.ttsSpeechRate.first()
        val pitch = settings.ttsPitch.first()
        ready = setupLanguageAndVoice(engine, requested, rate, pitch)
        if (!ready) _state.value = TtsState.ERROR
        ready
    }

    private suspend fun createEngine(): TextToSpeech? {
        val created = suspendCancellableCoroutine { cont ->
            var ref: TextToSpeech? = null
            ref = TextToSpeech(context) { status ->
                if (cont.isActive) cont.resume(if (status == TextToSpeech.SUCCESS) ref else null)
            }
            cont.invokeOnCancellation { ref?.shutdown() }
        }
        if (created == null) {
            _state.value = TtsState.ERROR
            _lastDetail.value = "init_failed (no usable TTS engine)"
            return null
        }
        tts = created
        configureEngine(created)
        return created
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
            override fun onStop(utteranceId: String, interrupted: Boolean) {
                _state.value = TtsState.IDLE
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
    private fun setupLanguageAndVoice(
        engine: TextToSpeech,
        requestedVoiceName: String,
        speechRate: Float,
        pitch: Float,
    ): Boolean {
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
        _availableVoices.value = offline
            .sortedWith(compareByDescending<Voice> { it.locale.language == Locale.ITALIAN.language }.thenBy { it.name })
            .map { voice ->
                val localeLabel = voice.locale.getDisplayName(Locale.ITALIAN)
                TtsVoiceOption(voice.name, "$localeLabel · ${voice.name}", voice.locale.toLanguageTag())
            }
        val forLocale = offline.filter { chosenLocale != null && it.locale.language == chosenLocale.language }
        val chosen = offline.firstOrNull { it.name == requestedVoiceName }
            ?: forLocale.firstOrNull { it.isMale() }
            ?: forLocale.firstOrNull()
            ?: offline.firstOrNull { it.isMale() }
            ?: offline.firstOrNull()

        val base = "engine=${runCatching { engine.defaultEngine }.getOrNull()} " +
            "it=$itAvail def=${defLoc.language}:$defAvail voices=${voices.size} offline=${offline.size}"

        if (chosen != null) {
            runCatching { engine.voice = chosen }
            engine.setSpeechRate(speechRate.coerceIn(SettingsRepository.MIN_TTS_RATE, SettingsRepository.MAX_TTS_RATE))
            engine.setPitch(pitch.coerceIn(SettingsRepository.MIN_TTS_PITCH, SettingsRepository.MAX_TTS_PITCH))
            _selectedVoiceName.value = chosen.name
            _lastDetail.value = "ok voice=${chosen.name} $base"
            return true
        }
        // No enumerable offline voice, but the engine may still synthesize offline
        // for an installed language (some engines expose few Voice objects).
        if (chosenLocale != null) {
            engine.setSpeechRate(speechRate.coerceIn(SettingsRepository.MIN_TTS_RATE, SettingsRepository.MAX_TTS_RATE))
            engine.setPitch(pitch.coerceIn(SettingsRepository.MIN_TTS_PITCH, SettingsRepository.MAX_TTS_PITCH))
            _selectedVoiceName.value = "engine:${chosenLocale.language}"
            _lastDetail.value = "engine_lang=${chosenLocale.language} $base"
            return true
        }
        _selectedVoiceName.value = null
        _lastDetail.value = "no_offline_voice $base"
        return false
    }

    override suspend fun refreshVoices(): List<TtsVoiceOption> = initMutex.withLock {
        val engine = tts ?: createEngine() ?: return@withLock emptyList()
        ready = setupLanguageAndVoice(
            engine,
            settings.ttsVoiceName.first(),
            settings.ttsSpeechRate.first(),
            settings.ttsPitch.first(),
        )
        availableVoices.value
    }

    override suspend fun configure(voiceName: String?, speechRate: Float, pitch: Float): Boolean {
        if (!ensureReady()) return false
        return initMutex.withLock {
            val engine = tts ?: return@withLock false
            ready = setupLanguageAndVoice(engine, voiceName.orEmpty(), speechRate, pitch)
            ready
        }
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
        requestAudioFocus()
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            _state.value = TtsState.ERROR
            pending.remove(id)
            abandonAudioFocus()
            return
        }
        try {
            done.await()
        } finally {
            if (!done.isCompleted) {
                engine.stop()
                pending.remove(id)?.complete(Unit)
                _state.value = TtsState.IDLE
            }
            abandonAudioFocus()
        }
    }

    override fun stop() {
        tts?.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
        abandonAudioFocus()
        _state.value = TtsState.IDLE
    }

    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
        _availableVoices.value = emptyList()
        _selectedVoiceName.value = null
    }

    private fun Voice.isMale(): Boolean {
        val n = name.lowercase()
        return n.contains("male") && !n.contains("female") || n.contains("-m-") || n.endsWith("-m")
    }
}
