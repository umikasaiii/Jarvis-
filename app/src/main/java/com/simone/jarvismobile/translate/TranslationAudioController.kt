package com.simone.jarvismobile.translate

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.simone.jarvismobile.core.translate.TranslationLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
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

    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var focusRequest: AudioFocusRequest? = null

    /** Creates the engine once; safe to call repeatedly. */
    suspend fun ensureReady(): Boolean {
        if (_ready.value && tts != null) return true
        val engine = createEngine() ?: return false
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
        return true
    }

    private fun finish(utteranceId: String) {
        pending.remove(utteranceId)?.complete(Unit)
        if (pending.isEmpty()) _speaking.value = false
    }

    private suspend fun createEngine(): TextToSpeech? = suspendCancellableCoroutine { cont ->
        var ref: TextToSpeech? = null
        ref = TextToSpeech(context) { status ->
            if (cont.isActive) cont.resume(if (status == TextToSpeech.SUCCESS) ref else null)
        }
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
        if (!ensureReady()) return
        val engine = tts ?: return
        runCatching { engine.language = locale(language) }

        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        requestFocus()
        _speaking.value = true
        val ok = engine.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
        if (!ok) {
            finish(id)
            abandonFocus()
            return
        }
        try {
            done.await()
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

    private fun locale(language: TranslationLanguage): Locale = when (language) {
        TranslationLanguage.ITALIAN -> Locale.ITALIAN
        TranslationLanguage.ENGLISH -> Locale.ENGLISH
        TranslationLanguage.SPANISH -> Locale("es", "ES")
        TranslationLanguage.FRENCH -> Locale.FRENCH
        TranslationLanguage.JAPANESE -> Locale.JAPANESE
    }
}
