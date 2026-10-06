package com.simone.jarvismobile.core.voice

/** Closed-world capture failures. Exception text is never policy; Android strings never enter :core. */
enum class VoiceCaptureFailure {
    PERMISSION_DENIED,
    INITIALIZATION_FAILED,
    START_FAILED,
    READ_FAILED,
    CANCELLED,
    ALREADY_OWNED,
}

enum class VoiceCaptureState { IDLE, OPENING, CAPTURING }

/** Result of one capture run. [failure] == null means it ran to completion (deadline reached). */
data class VoiceCaptureOutcome(
    val failure: VoiceCaptureFailure?,
    /** Redacted, human-debug-only technical detail. Never PCM, never policy. */
    val detail: String,
    val framesRead: Long,
) {
    val completed: Boolean get() = failure == null
}

/** Bounded technical observability. No PCM content, no device identifiers. */
data class VoiceCaptureSnapshot(
    val state: VoiceCaptureState = VoiceCaptureState.IDLE,
    val generation: Long = 0L,
    val framesRead: Long = 0L,
    val framesDropped: Long = 0L,
    val lastFrameSamples: Int = 0,
    val sampleRateHz: Int = 0,
)

/** Platform microphone behind the canonical capture engine (AudioRecord on Android, a fake in tests). */
interface PcmSource {
    val sampleRateHz: Int

    /** Short, redacted description of how the source was opened (debug only). */
    val description: String

    /** Starts recording. False when it could not start (never throws). */
    fun start(): Boolean

    /** Blocking read of up to [count] samples into [dest]. >0 samples read; <=0 means no data / error (<0). */
    fun read(dest: ShortArray, count: Int): Int

    fun stop()

    /** Releases the underlying recorder. The engine guarantees exactly one call per opened source. */
    fun release()
}

sealed interface PcmSourceOpenResult {
    class Opened(val source: PcmSource) : PcmSourceOpenResult
    class Failed(val failure: VoiceCaptureFailure, val detail: String) : PcmSourceOpenResult
}

fun interface PcmSourceFactory {
    /** Checks permission and opens (but does not start) a source. Never throws. */
    fun open(): PcmSourceOpenResult
}

/**
 * LV-R1 — which platform source profile the (single, canonical) capture owner opens.
 * STANDARD = legacy MagicOS-safe order (VOICE_COMMUNICATION -> MIC -> DEFAULT), unchanged.
 * ECHO_CONTROLLED_RAW = the app's own AEC3 runs, so the platform communication source (which on many
 * OEMs silently stacks its own AEC/NS) is NOT used: MIC -> DEFAULT only.
 */
enum class PcmCaptureMode { STANDARD, ECHO_CONTROLLED_RAW }

/** A [PcmSourceFactory] that can open a source for an explicit [PcmCaptureMode]. */
interface ModalPcmSourceFactory : PcmSourceFactory {
    fun open(mode: PcmCaptureMode): PcmSourceOpenResult
    override fun open(): PcmSourceOpenResult = open(PcmCaptureMode.STANDARD)
}
