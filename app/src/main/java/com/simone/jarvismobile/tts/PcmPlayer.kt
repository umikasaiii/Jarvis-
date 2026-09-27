package com.simone.jarvismobile.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Plays the float PCM a neural engine produces.
 *
 * Written in streaming mode and kept open across a whole reply: [start] once,
 * [write] each sentence as it comes out of the model, [drain] at the end. That
 * is what makes sentence-by-sentence synthesis audible as one continuous answer
 * instead of a series of clicks — the track never runs dry between sentences as
 * long as the next one arrives before the buffer empties.
 *
 * Output goes to USAGE_MEDIA so it follows AirPods over A2DP, matching the
 * Android engine's routing.
 *
 * Live Voice Phase 0.2 adds one purely observational capability: a bounded
 * watch, armed by [start] and self-cancelling, for the first time
 * [AudioTrack.getPlaybackHeadPosition] genuinely advances past this
 * session's own baseline — the trustworthy signal that real playback has
 * begun, as opposed to a [write] call merely having been accepted into the
 * OS buffer. This never drives playback itself (no write/stop/state
 * decision here depends on it) and never becomes a permanent
 * high-frequency loop: it gives up silently after [PLAYBACK_START_TIMEOUT_MS]
 * if no advance is ever observed.
 */
@Singleton
class PcmPlayer @Inject constructor() {

    private var track: AudioTrack? = null
    private var currentRate = 0
    @Volatile private var stopped = false

    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    /** Bumped on every [start] — the opaque token a poll iteration checks against, so a superseded session's poll can never emit for a newer one. */
    @Volatile private var generation = 0

    private val _playbackStartEvents = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /**
     * Emits the generation token [start] returned, the first time this
     * session's [AudioTrack] is observed to have genuinely advanced past
     * the head position it had when armed — never on a mere [write] call
     * succeeding, never a fabricated/estimated value. At most one emission
     * per generation.
     */
    val playbackStartEvents: SharedFlow<Int> = _playbackStartEvents.asSharedFlow()

    val isPlaying: Boolean get() = track != null

    /**
     * Opens a track at [sampleRate]. Reuses the existing one when it matches.
     *
     * Returns an opaque generation token for this session — pass it back to
     * [playbackStartEvents] filtering to correlate a later emission with
     * this exact call, never with a session that started before or after it.
     */
    fun start(sampleRate: Int): Int {
        val myGeneration = ++generation
        pollJob?.cancel()
        if (track != null && currentRate == sampleRate) {
            stopped = false
            runCatching { track?.play() }
            armPlaybackStartWatch(myGeneration)
            return myGeneration
        }
        release()
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(4096)
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                // Four times the minimum: enough slack for the next sentence to
                // finish generating while the current one is still playing.
                .setBufferSizeInBytes(minBuffer * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return myGeneration
        currentRate = sampleRate
        stopped = false
        track = created
        runCatching { created.play() }
        armPlaybackStartWatch(myGeneration)
        return myGeneration
    }

    /**
     * Bounded poll for the first positive head-position advance from this
     * session's own baseline (never just ">0": a reused track can already
     * carry a non-zero position from a prior session, so "initial value"
     * per the spec is measured fresh here, not assumed to be zero). Never
     * busy-spins — the same [POLL_MS] cadence [drain] already uses — and
     * always gives up after [PLAYBACK_START_TIMEOUT_MS] even if playback
     * genuinely never starts (a stalled HAL, a device without audio
     * output): the metric stays unavailable, speech is unaffected either
     * way.
     */
    private fun armPlaybackStartWatch(myGeneration: Int) {
        val t = track ?: return
        val baseline = runCatching { t.playbackHeadPosition }.getOrDefault(0)
        pollJob = pollScope.launch {
            val deadline = System.nanoTime() + PLAYBACK_START_TIMEOUT_MS * 1_000_000L
            while (isActive && generation == myGeneration && !stopped) {
                val head = runCatching { t.playbackHeadPosition }.getOrDefault(baseline)
                if (head > baseline) {
                    _playbackStartEvents.tryEmit(myGeneration)
                    return@launch
                }
                if (System.nanoTime() >= deadline) return@launch
                delay(POLL_MS)
            }
        }
    }

    /** Applies a 0..1 volume to this track only, never to the system stream. */
    fun setVolume(volume: Float) {
        runCatching { track?.setVolume(volume.coerceIn(0f, 1f)) }
    }

    /**
     * Writes one chunk, blocking until the buffer has room. Returns false if
     * playback was stopped underneath us, which is how a barge-in unwinds the
     * whole reply instead of only the sentence being spoken.
     */
    suspend fun write(samples: FloatArray): Boolean = withContext(Dispatchers.IO) {
        val t = track ?: return@withContext false
        var offset = 0
        while (offset < samples.size) {
            if (stopped) return@withContext false
            val n = runCatching {
                t.write(samples, offset, min(CHUNK, samples.size - offset), AudioTrack.WRITE_BLOCKING)
            }.getOrDefault(-1)
            if (n <= 0) {
                if (n < 0) Log.w(TAG, "audiotrack_write=$n")
                return@withContext false
            }
            offset += n
        }
        true
    }

    /** Lets the queued audio finish, then closes the track. */
    suspend fun drain() = withContext(Dispatchers.IO) {
        val t = track ?: return@withContext
        if (!stopped) {
            // MODE_STREAM has no "played to the end" callback that is reliable
            // across OEMs; polling the head position is.
            runCatching {
                var last = -1
                var stable = 0
                while (!stopped && stable < STABLE_POLLS) {
                    val head = t.playbackHeadPosition
                    if (head == last) stable++ else { stable = 0; last = head }
                    Thread.sleep(POLL_MS)
                }
            }
        }
        release()
    }

    fun stop() {
        stopped = true
        pollJob?.cancel()
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        release()
    }

    private fun release() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        currentRate = 0
    }

    private companion object {
        const val TAG = "JarvisPcm"
        const val CHUNK = 8192
        const val POLL_MS = 40L
        const val STABLE_POLLS = 3

        /**
         * Upper bound for the playback-start watch — far longer than any
         * realistic synthesis+buffering delay, but bounded on principle: this
         * must never become a permanent loop even if a device's audio HAL
         * never advances the head position for some reason.
         */
        const val PLAYBACK_START_TIMEOUT_MS = 5_000L
    }
}
