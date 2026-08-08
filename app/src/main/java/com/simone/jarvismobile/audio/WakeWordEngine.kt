package com.simone.jarvismobile.audio

import android.content.Context
import com.simone.jarvismobile.core.speech.WakeWord
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens for a spoken wake word, entirely on-device. Swappable behind this
 * interface (docs/ARCHITECTURE.md §4) so the current recognizer-backed engine
 * can be replaced by a dedicated ONNX keyword spotter later without touching the
 * controller, the UI, or the settings.
 *
 * This is only ever driven while the app is in the foreground (see
 * [com.simone.jarvismobile.audio.WakeWordController]); it is never a background
 * microphone.
 */
interface WakeWordEngine {
    /** Whether wake-word listening can run on this device. */
    fun isAvailable(): Boolean

    /**
     * Listens once and returns true if [word] was heard. One recognition window;
     * the controller calls it in a loop. Never throws.
     */
    suspend fun awaitWakeWord(word: String, languageTag: String = "it-IT"): Boolean

    /** Aborts an in-progress listen (e.g. because a real session is starting). */
    fun cancel()
}

/**
 * Wake-word detection on top of the Android on-device recognizer.
 *
 * It uses its **own** [AndroidOnDeviceSpeechEngine] instance, separate from the
 * one the conversation uses, so cancelling a wake listen can never abort a real
 * session (they don't share a recognizer). The recognizer returns quickly on
 * end-of-speech or its own silence timeout, so looping it is cheap enough for a
 * foreground-only listener. Detection is the pure [WakeWord.matches] in `:core`.
 */
@Singleton
class RecognizerWakeWordEngine @Inject constructor(
    @ApplicationContext context: Context,
) : WakeWordEngine {

    private val stt = AndroidOnDeviceSpeechEngine(context)

    @Volatile private var listening = false

    override fun isAvailable(): Boolean = runCatching { stt.isAvailable() }.getOrDefault(false)

    override suspend fun awaitWakeWord(word: String, languageTag: String): Boolean {
        if (word.isBlank()) return false
        listening = true
        return try {
            when (val result = stt.transcribe(languageTag)) {
                is SttResult.Text -> WakeWord.matches(result.text, word)
                else -> false
            }
        } catch (_: Throwable) {
            false
        } finally {
            listening = false
        }
    }

    // Only touches its own recognizer, and only while it is actually listening.
    override fun cancel() {
        if (listening) runCatching { stt.cancel() }
    }
}
