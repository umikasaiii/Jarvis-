package com.simone.jarvismobile.core.voice

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Live Voice Phase 0.7 — the ONE canonical, bounded, immutable PCM16 mono frame
 * of the live-capture foundation (consumed next by VAD, later by AEC/streaming STT).
 *
 * Immutability: the backing array is copied on construction ([copyOf]) and never
 * exposed; consumers read through [sampleAt]/[copyOfSamples]. A frame can therefore
 * be fanned out to several consumers by reference without any of them being able to
 * mutate what another sees.
 *
 * No wall-clock timestamp, no text, no device identifier. [sequence] is monotonically
 * increasing (starting at 0) within ONE capture generation and carries no meaning
 * across generations. PCM is ephemeral: it is never persisted, logged or uploaded.
 */
class VoicePcmFrame private constructor(
    private val samples: ShortArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val sequence: Long,
) {
    val sampleCount: Int get() = samples.size

    fun sampleAt(index: Int): Short = samples[index]

    fun copyOfSamples(): ShortArray = samples.copyOf()

    /** Same normalization the legacy mic-level meter used: RMS vs 16-bit full scale, x4 gentle gain, capped at 1. */
    fun normalizedLevel(): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) {
            val d = s.toDouble()
            sum += d * d
        }
        val rms = sqrt(sum / samples.size)
        return min(1f, (rms / 32768.0 * 4.0).toFloat())
    }

    override fun toString(): String =
        "VoicePcmFrame(seq=$sequence, samples=${samples.size}, rate=$sampleRateHz)" // never prints sample content

    companion object {
        const val BASELINE_SAMPLE_RATE_HZ = 16_000
        const val BASELINE_CHANNEL_COUNT = 1
        const val MAX_FRAME_SAMPLES = 4_096

        /** Copies [count] samples out of [source] (caller keeps ownership of [source]). Returns null when the arguments violate the contract. */
        fun copyOf(
            source: ShortArray,
            count: Int,
            sampleRateHz: Int,
            sequence: Long,
            channelCount: Int = BASELINE_CHANNEL_COUNT,
        ): VoicePcmFrame? {
            if (count <= 0 || count > MAX_FRAME_SAMPLES || count > source.size) return null
            if (sampleRateHz <= 0 || channelCount <= 0 || sequence < 0) return null
            return VoicePcmFrame(source.copyOf(count), sampleRateHz, channelCount, sequence)
        }
    }
}
