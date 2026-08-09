package com.simone.jarvismobile.translate

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.simone.jarvismobile.core.translate.TranslationLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Speaks the translated text in the *target* language. This is separate from the
 * assistant's own [com.simone.jarvismobile.audio.TextToSpeechEngine], which is
 * tuned for Italian and the neural voices — translation output has to switch
 * language per utterance (English, Spanish, French, Japanese…), which those must
 * not do.
 *
 * [speaking] is exposed so the pipeline can gate the microphone while a
 * translation is being read aloud: opening the recognizer over our own speaker
 * would feed the just-spoken translation back in as a new segment (echo). The
 * manager pauses listening for the duration of [speak].
 */
@Singleton
class TranslationAudioController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _speaking = MutableStateFlow(false)
    /** True while a translation is being read aloud (used for echo avoidance). */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _lastDetail = MutableStateFlow("")
    /** Why the last utterance was (or wasn't) heard — for diagnostics/UI. */
    val lastDetail: StateFlow<String> = _lastDetail.asStateFlow()

    private var tts: TextToSpeech? = null
    private var engineName: String = ""
    @Volatile private var voiceInstallRequested = false
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Creates the engine once; safe to call repeatedly. TextToSpeech must be
     * created and driven from a thread with a Looper — the main thread — or on
     * many devices its callbacks never fire and nothing is heard. The translation
     * loop runs on Dispatchers.Default, so every engine touch hops to Main here.
     */
    suspend fun ensureReady(): Boolean = withContext(Dispatchers.Main) {
        if (_ready.value && tts != null) return@withContext true
        // Prefer Google's TTS: it reliably synthesises it/en/es/fr/ja offline. On
        // HONOR/MagicOS the stock engine can claim a language is available yet
        // produce no audio, which is the usual reason a translation is shown but
        // never heard. Fall back to the system default only if Google is absent.
        val engine = createEngine(GOOGLE_TTS) ?: createEngine(null) ?: return@withContext false
        tts = engine
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { _speaking.value = true }
            override fun onDone(utteranceId: String) { finish(utteranceId) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId)
        })
        _ready.value = true
        true
    }

    private fun finish(utteranceId: String) {
        pending.remove(utteranceId)?.complete(Unit)
        if (pending.isEmpty()) _speaking.value = false
    }

    /**
     * Creates an engine, optionally pinned to a specific package (e.g. Google
     * TTS). Returns null if that engine can't init, so the caller can fall back.
     */
    private suspend fun createEngine(pkg: String?): TextToSpeech? = suspendCancellableCoroutine { cont ->
        var ref: TextToSpeech? = null
        val cb = TextToSpeech.OnInitListener { status ->
            if (cont.isActive) cont.resume(if (status == TextToSpeech.SUCCESS) ref else null)
        }
        ref = if (pkg != null) {
            runCatching { TextToSpeech(context, cb, pkg) }.getOrNull()
        } else {
            TextToSpeech(context, cb)
        }
        if (ref == null && cont.isActive) cont.resume(null)
        engineName = pkg ?: "default"
        cont.invokeOnCancellation { ref?.shutdown() }
    }

    /** True if this device can synthesise [language] offline. */
    fun supports(language: TranslationLanguage): Boolean {
        val engine = tts ?: return false
        val r = runCatching { engine.isLanguageAvailable(locale(language)) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        return r == TextToSpeech.LANG_AVAILABLE ||
            r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }

    /**
     * Reads [text] aloud in [language] and suspends until it finishes (or is
     * stopped). Sets the engine language just before speaking, so consecutive
     * turns can alternate languages on a single engine instance.
     */
    suspend fun speak(text: String, language: TranslationLanguage) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!ensureReady()) {
            _lastDetail.value = "tts non inizializzato"
            return
        }
        val engine = tts ?: return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        requestFocus()
        _speaking.value = true
        // All engine calls on Main (see ensureReady): setLanguage returns a code
        // — a missing/unsupported voice is the usual reason a translation is shown
        // but never heard — and speak is queued from the same thread.
        val ok = withContext(Dispatchers.Main) {
            ensureAudibleVolume()
            var langResult = runCatching { engine.setLanguage(locale(language)) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                // The voice pack for this language isn't on the device. Prompt the
                // user to install it (once) — silence otherwise looks like a bug.
                requestVoiceInstall()
                _lastDetail.value = "voce ${language.code} non installata — installa i dati vocali"
                Log.w(TAG, "tts_language_unavailable ${language.code} code=$langResult eng=$engineName")
            } else {
                val vol = mediaVolumePercent()
                _lastDetail.value = "ok ${language.code} · voce=$engineName · vol=$vol%"
            }
            // Force full utterance volume on the media stream: some ROMs otherwise
            // route the audio to a muted/near-silent stream and nothing is heard.
            val params = android.os.Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            engine.speak(trimmed, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS
        }
        if (!ok) {
            Log.w(TAG, "tts_speak_rejected ${language.code}")
            finish(id)
            abandonFocus()
            return
        }
        try {
            // Never let a silent/stuck utterance hang the whole translation loop.
            kotlinx.coroutines.withTimeoutOrNull(SPEAK_TIMEOUT_MS) { done.await() }
        } finally {
            if (!done.isCompleted) {
                engine.stop()
                finish(id)
            }
            abandonFocus()
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
        _speaking.value = false
        abandonFocus()
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        _ready.value = false
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(true)
            .build()
        focusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        focusRequest?.let { req -> runCatching { audioManager.abandonAudioFocusRequest(req) } }
        focusRequest = null
    }

    /** Media-stream volume as a percentage, for diagnostics. */
    private fun mediaVolumePercent(): Int = runCatching {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) 0 else (cur * 100 / max)
    }.getOrDefault(0)

    /**
     * If the media stream is silent the translation is spoken but inaudible, which
     * reads as "no audio". Raise it to a sensible level (never touching it when the
     * user has already set some volume).
     */
    private fun ensureAudibleVolume() {
        runCatching {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 && max > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.6f).toInt().coerceAtLeast(1), 0)
            }
        }
    }

    /**
     * Sends the user to the system "install voice data" flow, once per session, so
     * a missing target-language voice can be downloaded. Uses NEW_TASK because we
     * may be called from a non-Activity context.
     */
    private fun requestVoiceInstall() {
        if (voiceInstallRequested) return
        voiceInstallRequested = true
        runCatching {
            val intent = android.content.Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { Log.w(TAG, "tts_install_prompt_failed ${it.javaClass.simpleName}") }
    }

    private fun locale(language: TranslationLanguage): Locale = when (language) {
        TranslationLanguage.ITALIAN -> Locale.ITALIAN
        TranslationLanguage.ENGLISH -> Locale.ENGLISH
        TranslationLanguage.SPANISH -> Locale("es", "ES")
        TranslationLanguage.FRENCH -> Locale.FRENCH
        TranslationLanguage.JAPANESE -> Locale.JAPANESE
    }

    private companion object {
        const val TAG = "JarvisTranslateTts"
        const val SPEAK_TIMEOUT_MS = 20_000L
        /** Google's TTS engine package — the reliable multilingual offline engine. */
        const val GOOGLE_TTS = "com.google.android.tts"
    }
}
