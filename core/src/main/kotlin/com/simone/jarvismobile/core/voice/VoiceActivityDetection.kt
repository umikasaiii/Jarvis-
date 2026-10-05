package com.simone.jarvismobile.core.voice

/**
 * Live Voice Phase 0.7 — the minimal contract a real VAD (Silero, next phase) consumes.
 * Closed-world output; a probability, if a future engine has one, is mapped to this enum
 * by [fromProbability] with an EXPLICIT caller-provided threshold (no hidden default).
 *
 * SILERO VAD RUNTIME = NOT IMPLEMENTED / NOT QUALIFIED. Nothing implements
 * [VoiceActivityDetector] in production yet and nothing is wired to it.
 */
enum class VadObservation {
    SPEECH,
    NON_SPEECH,

    /** No usable evidence (engine not ready, invalid value, gap). Neither speech nor silence. */
    UNKNOWN,
    ;

    companion object {
        /** [probability] outside 0..1 or NaN/Infinite => [UNKNOWN] (never clamped into a false decision). */
        fun fromProbability(probability: Float, speechThreshold: Float): VadObservation {
            if (probability.isNaN() || probability.isInfinite() || probability < 0f || probability > 1f) return UNKNOWN
            if (speechThreshold.isNaN() || speechThreshold < 0f || speechThreshold > 1f) return UNKNOWN
            return if (probability >= speechThreshold) SPEECH else NON_SPEECH
        }
    }
}

interface VoiceActivityDetector {
    suspend fun process(frame: VoicePcmFrame): VadObservation
    fun reset()
}
