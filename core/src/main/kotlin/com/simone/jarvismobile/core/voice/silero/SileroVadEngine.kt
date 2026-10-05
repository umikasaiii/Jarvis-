package com.simone.jarvismobile.core.voice.silero

import com.simone.jarvismobile.core.voice.VadObservation
import com.simone.jarvismobile.core.voice.VoiceActivityDetector
import com.simone.jarvismobile.core.voice.VoicePcmFrame

/** Result of one model inference. [Ok.nextState] must be a fresh array of [SileroModelManifest.STATE_FLOATS]. */
sealed interface SileroInferenceResult {
    class Ok(val probability: Float, val nextState: FloatArray) : SileroInferenceResult
    class Failed(val failure: SileroFailure) : SileroInferenceResult
}

/**
 * Native-runtime seam (ORT on Android, a fake in JVM tests). Implementations must not retain [input] / [state]
 * after returning. [input] is the 576-sample window (64 context + 512 frame); [state] is [2, 1, 128] flattened.
 */
interface SileroInferenceBackend {
    fun run(input: FloatArray, state: FloatArray, sampleRateHz: Long): SileroInferenceResult
    fun close()
}

/** Bounded aggregate diagnostics of one engine session (no per-frame list, no PCM). */
data class SileroEngineStats(
    val framesOffered: Long = 0,
    val framesInferred: Long = 0,
    val framesUnknown: Long = 0,
    val framesRejectedStale: Long = 0,
    val continuityResets: Long = 0,
    val maxProbability: Float? = null,
    val meanProbability: Float? = null,
    val totalInferenceNanos: Long = 0,
    val maxInferenceNanos: Long = 0,
    val lastFailure: SileroFailure? = null,
) {
    val averageInferenceMs: Double? get() = if (framesInferred > 0) totalInferenceNanos / framesInferred / 1_000_000.0 else null
    val maxInferenceMs: Double get() = maxInferenceNanos / 1_000_000.0
}

/**
 * Real Silero VAD v5 streaming engine (Live Voice Phase 0.8). Feeds the official streaming contract:
 * model input = 64 samples of CONTEXT (tail of the previous frame, zeros at session start) + the 512-sample frame
 * = 576 floats, with the recurrent state [2,1,128] carried across frames. It only maps frame -> [VadObservation];
 * the speech-boundary hysteresis stays in VadTurnPolicy and nothing here touches conversation, STT, TTS or capture.
 *
 * Continuity: callers MUST [reset] at the start of every capture generation (VoicePcmFrame.sequence restarts per
 * generation). A duplicate/decreasing sequence is rejected (UNKNOWN, nothing advances); a forward gap breaks
 * continuity, so context + state are zeroed before the frame is processed — missing PCM is never invented.
 * A failed or invalid inference advances neither state nor context. The single monotonic clock lives here.
 */
class SileroVadEngine(
    private val backend: SileroInferenceBackend,
    val config: SileroVadConfig = SileroVadConfig(),
    private val nanoClock: () -> Long = System::nanoTime,
) : VoiceActivityDetector {

    private val lock = Any()
    private val input = FloatArray(SileroModelManifest.EFFECTIVE_INPUT_SAMPLES)
    private var context = FloatArray(SileroModelManifest.CONTEXT_SAMPLES)
    private var state = FloatArray(SileroModelManifest.STATE_FLOATS)
    private var lastSequence: Long? = null
    private var closed = false
    private var stats = SileroEngineStats()
    private var probabilitySum = 0.0

    fun stats(): SileroEngineStats = synchronized(lock) { stats }

    override suspend fun process(frame: VoicePcmFrame): VadObservation = synchronized(lock) { processLocked(frame) }

    override fun reset() = synchronized(lock) {
        resetContinuity()
        lastSequence = null
        stats = SileroEngineStats()
        probabilitySum = 0.0
    }

    /** Closes the native backend exactly once. Further frames yield UNKNOWN / MODEL_CLOSED. */
    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        backend.close()
    }

    private fun resetContinuity() {
        context = FloatArray(SileroModelManifest.CONTEXT_SAMPLES)
        state = FloatArray(SileroModelManifest.STATE_FLOATS)
    }

    private fun unknown(failure: SileroFailure?): VadObservation {
        stats = stats.copy(framesUnknown = stats.framesUnknown + 1, lastFailure = failure ?: stats.lastFailure)
        return VadObservation.UNKNOWN
    }

    private fun processLocked(frame: VoicePcmFrame): VadObservation {
        stats = stats.copy(framesOffered = stats.framesOffered + 1)
        if (closed) return unknown(SileroFailure.MODEL_CLOSED)
        if (frame.sampleRateHz != SileroModelManifest.SAMPLE_RATE_HZ ||
            frame.channelCount != 1 ||
            frame.sampleCount != SileroModelManifest.FRAME_SAMPLES
        ) return unknown(SileroFailure.UNSUPPORTED_INPUT)

        val last = lastSequence
        if (last != null) {
            if (frame.sequence <= last) {
                stats = stats.copy(framesRejectedStale = stats.framesRejectedStale + 1)
                return unknown(null)
            }
            if (frame.sequence > last + 1) {
                resetContinuity()
                stats = stats.copy(continuityResets = stats.continuityResets + 1)
            }
        }
        lastSequence = frame.sequence

        // 64 context + 512 normalized frame = 576. Normalization happens exactly here.
        System.arraycopy(context, 0, input, 0, SileroModelManifest.CONTEXT_SAMPLES)
        for (i in 0 until SileroModelManifest.FRAME_SAMPLES) {
            input[SileroModelManifest.CONTEXT_SAMPLES + i] = SileroPcm.toFloat(frame.sampleAt(i))
        }

        val started = nanoClock()
        val result = try {
            backend.run(input, state, SileroModelManifest.SAMPLE_RATE_HZ.toLong())
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            return unknown(SileroFailure.INFERENCE_FAILED)
        }
        val elapsed = (nanoClock() - started).coerceAtLeast(0)

        if (result is SileroInferenceResult.Failed) return unknown(result.failure)
        result as SileroInferenceResult.Ok
        val p = result.probability
        if (p.isNaN() || p.isInfinite() || p < 0f || p > 1f || result.nextState.size != SileroModelManifest.STATE_FLOATS) {
            return unknown(SileroFailure.INVALID_OUTPUT)
        }

        state = result.nextState.copyOf()
        // Next context = tail of the CURRENT 512-sample frame (not of the 576 combined buffer's head).
        System.arraycopy(
            input, SileroModelManifest.EFFECTIVE_INPUT_SAMPLES - SileroModelManifest.CONTEXT_SAMPLES,
            context, 0, SileroModelManifest.CONTEXT_SAMPLES,
        )
        val n = stats.framesInferred + 1
        probabilitySum += p
        stats = stats.copy(
            framesInferred = n,
            maxProbability = maxOf(stats.maxProbability ?: p, p),
            meanProbability = (probabilitySum / n).toFloat(),
            totalInferenceNanos = stats.totalInferenceNanos + elapsed,
            maxInferenceNanos = maxOf(stats.maxInferenceNanos, elapsed),
        )
        return VadObservation.fromProbability(p, config.speechThreshold)
    }
}

/** The single PCM16 -> float conversion (official Silero scaling: x / 32768). */
object SileroPcm {
    fun toFloat(sample: Short): Float = sample.toInt() / 32768f
}
