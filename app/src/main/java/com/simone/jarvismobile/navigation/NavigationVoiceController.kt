package com.simone.jarvismobile.navigation

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks navigation instructions with the on-device TTS (spec §11) — never a
 * cloud voice. It owns its own Italian engine so guidance can play without the
 * assistant's LLM loaded, and de-duplicates so the same phrase isn't repeated.
 * Muting is honoured immediately.
 */
@Singleton
class NavigationVoiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var lastSpoken: String? = null

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    fun setMuted(value: Boolean) {
        _muted.value = value
        if (value) runCatching { tts?.stop() }
    }

    private fun ensureEngine() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching {
                    tts?.language = Locale.ITALIAN
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                }
                ready = true
            } else {
                Log.w(TAG, "nav_tts_init_failed status=$status")
            }
        }
    }

    /** Speaks [text] unless muted or identical to the previous utterance. */
    fun speak(text: String) {
        if (_muted.value) return
        val t = text.trim()
        if (t.isEmpty() || t == lastSpoken) return
        lastSpoken = t
        scope.launch {
            withContext(Dispatchers.Main) {
                ensureEngine()
                if (ready) {
                    tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "nav-${t.hashCode()}")
                }
            }
        }
    }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        ready = false
    }

    private companion object { const val TAG = "JarvisNavVoice" }
}
