package com.simone.jarvismobile.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
 */
@Singleton
class PcmPlayer @Inject constructor() {

    private var track: AudioTrack? = null
    private var currentRate = 0
    @Volatile private var stopped = false

    val isPlaying: Boolean get() = track != null

    /** Opens a track at [sampleRate]. Reuses the existing one when it matches. */
    fun start(sampleRate: Int) {
        if (track != null && currentRate == sampleRate) {
            stopped = false
            runCatching { track?.play() }
            return
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
        }.getOrNull() ?: return
        currentRate = sampleRate
        stopped = false
        track = created
        runCatching { created.play() }
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
    }
}
