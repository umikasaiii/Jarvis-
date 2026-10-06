package com.simone.jarvismobile.core.voice

/**
 * LV-R1 — platform-free contract for acoustic echo cancellation (WebRTC AEC3 behind JNI in `app`).
 * No Android / native types here. Everything is PCM that is ephemeral: never persisted or logged.
 */
enum class EchoCancellerState { UNAVAILABLE, INITIALIZING, READY, ERROR, CLOSED }

enum class EchoCancellerFailure {
    NATIVE_LIBRARY_MISSING,
    INIT_FAILED,
    UNSUPPORTED_NEAR_RATE,
    UNSUPPORTED_FAR_RATE,
    INVALID_BLOCK,
    NATIVE_PROCESS_FAILED,
    CLOSED,
    STALE_GENERATION,
}

/** Typed outcome of one block operation. */
sealed interface EchoBlockResult {
    class Ok(val samples: ShortArray) : EchoBlockResult
    object Accepted : EchoBlockResult
    class Failed(val failure: EchoCancellerFailure) : EchoBlockResult
}

/**
 * Exact 10 ms blocks only. [nearRateHz]=16000 -> 160 samples; far-end [farRateHz] must be a rate
 * where rate/100 is an integer (16000, 24000, 32000, 48000 ...). 22050 Hz is NOT supported.
 */
interface EchoCanceller {
    val state: EchoCancellerState
    val nearBlockSamples: Int
    val farBlockSamples: Int

    /** Far-end/render block (float, [-1,1], exactly [farBlockSamples]). */
    fun feedFarEnd(block: FloatArray): EchoBlockResult

    /** Near-end/capture block (PCM16, exactly [nearBlockSamples]); returns the echo-reduced block. */
    fun processNearEnd(block: ShortArray): EchoBlockResult

    fun close()
}

/** Rate policy: which far-end TTS rates may drive automatic acoustic barge-in. */
object EchoRatePolicy {
    const val NEAR_RATE_HZ = 16_000

    /** 10 ms block size for [rateHz], or null when it cannot form an exact integer block. */
    fun blockSamples(rateHz: Int): Int? =
        if (rateHz in 8_000..48_000 && rateHz % 100 == 0) rateHz / 100 else null

    /** True when the far-end rate is natively representable (no fractional 10 ms block). */
    fun farRateSupported(rateHz: Int): Boolean = rateHz in SUPPORTED_FAR_RATES

    val SUPPORTED_FAR_RATES: Set<Int> = setOf(16_000, 24_000, 32_000, 48_000)
}
