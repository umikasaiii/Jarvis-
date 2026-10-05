package com.simone.jarvismobile.core.voice

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Live Voice Phase 0.9 — far-end PCM reference foundation (input for a FUTURE echo canceller).
 *
 * Deliberately a different type from the near-end [VoicePcmFrame] (PCM16 microphone frames):
 * far-end frames are mono FLOAT playback samples at the REAL sample rate of the playback path.
 * Nothing here is AEC: no subtraction, no delay estimation, no resampling.
 *
 * A frame describes ONLY samples that `AudioTrack.write` actually accepted. It is ephemeral:
 * never persisted, logged or uploaded; carries no text, utterance, device id or wall-clock identity.
 */
class FarEndPcmFrame private constructor(
    private val samples: FloatArray,
    /** Playback generation (deterministic identity, NOT a timestamp). */
    val generation: Int,
    /** 0,1,2,… within one generation. */
    val sequence: Long,
    /** Index (within the generation's accepted-sample timeline) of this frame's first sample. */
    val sampleOffset: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
) {
    val sampleCount: Int get() = samples.size

    fun sampleAt(index: Int): Float = samples[index]

    fun copyOfSamples(): FloatArray = samples.copyOf()

    /** Reference point: the captured samples are BEFORE the AudioTrack output volume is applied. */
    val gainStage: FarEndGainStage get() = FarEndGainStage.PRE_OUTPUT_GAIN

    override fun toString(): String =
        "FarEndPcmFrame(gen=$generation, seq=$sequence, offset=$sampleOffset, samples=${samples.size}, rate=$sampleRateHz)" // never prints samples

    companion object {
        const val MAX_FRAME_SAMPLES = 8_192

        /** Copies exactly [count] samples from [source] starting at [start]. Null when the arguments violate the contract. */
        fun copyOf(
            source: FloatArray,
            start: Int,
            count: Int,
            generation: Int,
            sequence: Long,
            sampleOffset: Long,
            sampleRateHz: Int,
            channelCount: Int = 1,
        ): FarEndPcmFrame? {
            if (count <= 0 || count > MAX_FRAME_SAMPLES || start < 0 || start > source.size - count) return null
            if (sequence < 0 || sampleOffset < 0 || sampleRateHz <= 0 || channelCount <= 0) return null
            return FarEndPcmFrame(source.copyOfRange(start, start + count), generation, sequence, sampleOffset, sampleRateHz, channelCount)
        }
    }
}

/** Where in the output chain the reference was taken. AudioTrack volume is applied AFTER write(), so the reference is pre-gain. */
enum class FarEndGainStage { PRE_OUTPUT_GAIN }

/** Closed-world capability: only a path that really writes PCM it owns is AVAILABLE_PCM. */
enum class FarEndReferenceCapability {
    /** JARVIS owns the PCM being written to AudioTrack right now. */
    AVAILABLE_PCM,

    /** android.speech.tts.TextToSpeech: playback PCM is not exposed to JARVIS. Never faked. */
    UNAVAILABLE_PLATFORM_TTS,

    /** No playback in progress. */
    IDLE,

    ERROR,
}

/** Bounded technical snapshot. No samples, no text. */
data class FarEndReferenceSnapshot(
    val capability: FarEndReferenceCapability = FarEndReferenceCapability.IDLE,
    val generation: Int = -1,
    val framesAccepted: Long = 0,
    val framesDropped: Long = 0,
    val samplesAccepted: Long = 0,
    val sampleRateHz: Int = 0,
    val lastFrameSamples: Int = 0,
    /** Scalar AudioTrack volume (0..1) applied AFTER the referenced PCM; informational for a future AEC. */
    val outputGain: Float = 1f,
)

/**
 * The single recorder owned by the one PCM player. Thread-safe; never blocks playback:
 * emission is `tryEmit` into a bounded, replay=0 flow and a slow consumer only increments
 * [FarEndReferenceSnapshot.framesDropped]. The sample timeline (sequence/offset) advances
 * with ACCEPTED playback samples regardless of whether a consumer kept up.
 */
class FarEndReferenceRecorder(extraBuffer: Int = DEFAULT_EXTRA_BUFFER) {
    private val lock = Any()
    private val flow = MutableSharedFlow<FarEndPcmFrame>(
        replay = 0,
        extraBufferCapacity = extraBuffer,
        onBufferOverflow = BufferOverflow.SUSPEND, // tryEmit() returns false instead of suspending → counted as a drop
    )

    val frames: SharedFlow<FarEndPcmFrame> = flow.asSharedFlow()

    private var active = false
    private var generation = -1
    private var rate = 0
    private var nextSequence = 0L
    private var nextOffset = 0L
    private var accepted = 0L
    private var dropped = 0L
    private var samplesTotal = 0L
    private var lastSamples = 0
    private var gain = 1f

    /** New playback generation: resets sequence, offset and counters. */
    fun begin(generation: Int, sampleRateHz: Int) = synchronized(lock) {
        this.generation = generation
        rate = sampleRateHz
        active = sampleRateHz > 0
        nextSequence = 0; nextOffset = 0
        accepted = 0; dropped = 0; samplesTotal = 0; lastSamples = 0
    }

    /** Playback stopped/finished: no further reference is accepted until the next [begin]. */
    fun end() = synchronized(lock) { active = false }

    fun setOutputGain(volume: Float) = synchronized(lock) { gain = volume.coerceIn(0f, 1f) }

    /**
     * Records [count] samples that AudioTrack genuinely accepted, read from [source] at [start].
     * Returns false (nothing recorded) for a stale generation, an inactive recorder or an invalid region.
     */
    fun onAcceptedWrite(generation: Int, source: FloatArray, start: Int, count: Int): Boolean = synchronized(lock) {
        if (!active || generation != this.generation) return false
        val frame = FarEndPcmFrame.copyOf(source, start, count, generation, nextSequence, nextOffset, rate)
        if (frame == null) {
            dropped++
            return false
        }
        nextSequence++
        nextOffset += count
        accepted++
        samplesTotal += count
        lastSamples = count
        if (!flow.tryEmit(frame) && flow.subscriptionCount.value > 0) dropped++
        true
    }

    fun capability(): FarEndReferenceCapability = synchronized(lock) {
        if (active) FarEndReferenceCapability.AVAILABLE_PCM else FarEndReferenceCapability.IDLE
    }

    fun snapshot(): FarEndReferenceSnapshot = synchronized(lock) {
        FarEndReferenceSnapshot(
            capability = if (active) FarEndReferenceCapability.AVAILABLE_PCM else FarEndReferenceCapability.IDLE,
            generation = generation,
            framesAccepted = accepted,
            framesDropped = dropped,
            samplesAccepted = samplesTotal,
            sampleRateHz = rate,
            lastFrameSamples = lastSamples,
            outputGain = gain,
        )
    }

    companion object {
        const val DEFAULT_EXTRA_BUFFER = 64
    }
}

/**
 * The exact chunked write loop of the PCM player, extracted so the accepted-sample semantics are
 * testable on the JVM. [sink] is the real `AudioTrack.write` (returns samples accepted, or <=0).
 * Behaviour is identical to the previous inline loop; the only addition is the reference callback
 * with exactly the n samples each successful write accepted.
 */
object PlaybackWriteLoop {
    fun write(
        samples: FloatArray,
        chunk: Int,
        generation: Int,
        isStopped: () -> Boolean,
        sink: (FloatArray, Int, Int) -> Int,
        recorder: FarEndReferenceRecorder,
        onWriteError: (Int) -> Unit = {},
    ): Boolean {
        var offset = 0
        while (offset < samples.size) {
            if (isStopped()) return false
            val n = try {
                sink(samples, offset, minOf(chunk, samples.size - offset))
            } catch (e: Exception) {
                -1
            }
            if (n <= 0) {
                if (n < 0) onWriteError(n)
                return false
            }
            recorder.onAcceptedWrite(generation, samples, offset, n)
            offset += n
        }
        return true
    }
}

/** Smallest contract a FUTURE duplex/AEC engine needs. Introduces no new microphone or playback owner. */
interface DuplexFrameConsumer {
    fun onNearEnd(frame: VoicePcmFrame)
    fun onFarEnd(frame: FarEndPcmFrame)
}
