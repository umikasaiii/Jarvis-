package com.simone.jarvismobile.audio

import kotlinx.coroutines.flow.StateFlow

/** Result of a fixed Phase-1 capture window. */
enum class CaptureResult { COMPLETED, FAILED, PERMISSION_DENIED }

/**
 * Records a short, fixed audio window (Phase-1). The captured PCM is used only to
 * compute a live level and is **discarded immediately** — nothing is transcribed
 * or persisted yet (docs/ANDROID_BUILD_AUDIT.md §3). STT arrives in Phase 2.
 */
interface AudioCapture {
    /** Normalized microphone level (0f..1f), updated while capturing. */
    val micLevel: StateFlow<Float>

    /** Captures for [durationMs] then stops. Returns the outcome; never throws. */
    suspend fun capture(durationMs: Long): CaptureResult

    /** Requests cancellation of an in-progress capture. */
    fun cancel()
}
