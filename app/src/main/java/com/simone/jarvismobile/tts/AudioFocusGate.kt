package com.simone.jarvismobile.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transient audio focus around anything JARVIS plays.
 *
 * Both the reply path and the Settings test tone need it: without focus a test
 * tone mixes with whatever music is playing, which makes a perfectly good voice
 * sound broken and sends the user chasing the wrong bug. Shared rather than
 * duplicated so the two can never drift into requesting different things.
 */
@Singleton
class AudioFocusGate @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var request: AudioFocusRequest? = null

    /** Called when focus is lost for good — the caller should stop playing. */
    @Volatile private var onLoss: (() -> Unit)? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            onLoss?.invoke()
        }
    }

    fun acquire(onFocusLost: () -> Unit) {
        onLoss = onFocusLost
        if (request != null) return
        val built = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(listener)
            .setWillPauseWhenDucked(true)
            .build()
        request = built
        runCatching { audioManager.requestAudioFocus(built) }
    }

    fun release() {
        request?.let { req -> runCatching { audioManager.abandonAudioFocusRequest(req) } }
        request = null
        onLoss = null
    }
}
