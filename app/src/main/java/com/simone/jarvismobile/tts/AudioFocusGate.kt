package com.simone.jarvismobile.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live Voice Phase 0.4 — bounded, closed-world observation of a real
 * `AudioManager` focus event. [GRANTED]/[DENIED]/[DELAYED] come only from
 * `requestAudioFocus()`'s own return value (previously discarded — see
 * [AudioFocusGate.acquire]); [GAIN]/[LOST_PERMANENT]/[LOST_TRANSIENT]/
 * [LOST_TRANSIENT_CAN_DUCK] come only from a real `OnAudioFocusChangeListener`
 * callback. Never inferred from the mere fact that a request was made, or
 * that focus was requested at all.
 */
enum class AudioFocusObservation { GRANTED, DENIED, DELAYED, GAIN, LOST_PERMANENT, LOST_TRANSIENT, LOST_TRANSIENT_CAN_DUCK, UNKNOWN }

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

    // Live Voice Phase 0.4 — observation only, added alongside the existing
    // onLoss callback below without changing its condition/behavior at all:
    // the pause-on-loss decision stays exactly GAIN-independent/
    // CAN_DUCK-independent as it already was, this just also reports every
    // real callback so a voice turn's diagnostics can tell the difference
    // between "never lost focus" and "lost it, then regained it" honestly.
    private val _focusEvents = MutableSharedFlow<AudioFocusObservation>(extraBufferCapacity = 8)
    val focusEvents: SharedFlow<AudioFocusObservation> = _focusEvents.asSharedFlow()

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        _focusEvents.tryEmit(
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> AudioFocusObservation.GAIN
                AudioManager.AUDIOFOCUS_LOSS -> AudioFocusObservation.LOST_PERMANENT
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusObservation.LOST_TRANSIENT
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusObservation.LOST_TRANSIENT_CAN_DUCK
                else -> AudioFocusObservation.UNKNOWN
            },
        )
        // Existing, unchanged behavior: only a real loss (permanent or
        // transient) stops playback — never touched by this phase.
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
        // Live Voice Phase 0.4 — the grant result itself was previously
        // discarded entirely (JARVIS never knew whether the initial request
        // actually succeeded). Still never changes behavior: nothing acts
        // differently on DENIED/DELAYED today, this only makes the real
        // outcome observable.
        val result = runCatching { audioManager.requestAudioFocus(built) }.getOrNull()
        _focusEvents.tryEmit(
            when (result) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> AudioFocusObservation.GRANTED
                AudioManager.AUDIOFOCUS_REQUEST_FAILED -> AudioFocusObservation.DENIED
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> AudioFocusObservation.DELAYED
                else -> AudioFocusObservation.UNKNOWN
            },
        )
    }

    fun release() {
        request?.let { req -> runCatching { audioManager.abandonAudioFocusRequest(req) } }
        request = null
        onLoss = null
    }
}
