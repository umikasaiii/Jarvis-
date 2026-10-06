package com.simone.jarvismobile.core.voice

import com.simone.jarvismobile.core.engine.SessionEpoch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live Voice Phase 0.7 — THE single live-capture owner: one microphone read loop,
 * one frame stream, one cancellation lifecycle. The platform microphone sits behind
 * [PcmSourceFactory]; this class is platform-free so its lifecycle is JVM-tested.
 *
 * - One run at a time (busy flag): a second concurrent run fails with ALREADY_OWNED
 *   instead of opening a second recorder.
 * - Generation fencing: each run takes a fresh [SessionEpoch] generation; a frame is
 *   emitted only while its generation is current, so a cancelled/superseded run can
 *   never leak a frame (no time-window heuristic).
 * - [frames]: SharedFlow, replay = 0, bounded buffer (64 frames), explicit overflow policy:
 *   the read loop only ever uses tryEmit and never suspends on a slow consumer; a refused
 *   emit (buffer full) drops the NEWEST frame and is counted in framesDropped. The buffer is
 *   shared, so one stuck subscriber starves all subscribers (acceptable for diagnostics/VAD).
 *   Never StateFlow; PCM is never persisted.
 * - [micLevel] derives from the SAME frames (no second read for RMS).
 * - Every opened source is released exactly once (finally).
 *
 * Not wired into production STT: SpeechRecognizer still owns the microphone for
 * conversation/wake word. Nothing may call [run] concurrently with it.
 */
class PcmCaptureEngine(
    private val factory: PcmSourceFactory,
    private val readDispatcher: CoroutineDispatcher,
    private val nanoTime: () -> Long = System::nanoTime,
    private val frameSamples: Int = DEFAULT_FRAME_SAMPLES,
) {
    init {
        require(frameSamples in 1..VoicePcmFrame.MAX_FRAME_SAMPLES) { "frameSamples out of bounds" }
    }

    private val epoch = SessionEpoch()
    private val busy = AtomicBoolean(false)

    private val _frames = MutableSharedFlow<VoicePcmFrame>(
        replay = 0,
        extraBufferCapacity = FRAME_BUFFER_CAPACITY,
        // SUSPEND + tryEmit: tryEmit returns false when a slow subscriber has filled the buffer,
        // which is exactly a measurable drop-latest (DROP_* policies make tryEmit always true).
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val frames: SharedFlow<VoicePcmFrame> = _frames.asSharedFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _lastDetail = MutableStateFlow("")
    val lastDetail: StateFlow<String> = _lastDetail.asStateFlow()

    private val _snapshot = MutableStateFlow(VoiceCaptureSnapshot())
    val snapshot: StateFlow<VoiceCaptureSnapshot> = _snapshot.asStateFlow()

    /** Requests cancellation of the run in progress (no-op when idle). Deterministic: invalidates the generation first. */
    fun cancel() {
        if (busy.get()) epoch.invalidate()
    }

    /** Captures until [maxDurationMs] elapses (null = until cancelled). Never throws except CancellationException of the caller. */
    suspend fun run(maxDurationMs: Long?, mode: PcmCaptureMode = PcmCaptureMode.STANDARD): VoiceCaptureOutcome {
        if (!busy.compareAndSet(false, true)) {
            return outcome(VoiceCaptureFailure.ALREADY_OWNED, "already_owned", 0)
        }
        val generation = epoch.invalidate()
        _snapshot.value = VoiceCaptureSnapshot(state = VoiceCaptureState.OPENING, generation = generation)
        try {
            return withContext(readDispatcher) { loop(generation, maxDurationMs, mode) }
        } finally {
            _micLevel.value = 0f
            _snapshot.value = _snapshot.value.copy(state = VoiceCaptureState.IDLE)
            busy.set(false)
        }
    }

    private suspend fun loop(generation: Long, maxDurationMs: Long?, mode: PcmCaptureMode): VoiceCaptureOutcome {
        val opened = (factory as? ModalPcmSourceFactory)?.open(mode) ?: factory.open()
        val source = when (opened) {
            is PcmSourceOpenResult.Failed -> return outcome(opened.failure, opened.detail, 0)
            is PcmSourceOpenResult.Opened -> opened.source
        }
        val released = AtomicBoolean(false)
        fun releaseOnce() { if (released.compareAndSet(false, true)) runCatching { source.release() } }
        var framesRead = 0L
        var dropped = 0L
        try {
            if (!source.start()) return outcome(VoiceCaptureFailure.START_FAILED, "start_failed ${source.description}", 0)
            _snapshot.value = _snapshot.value.copy(state = VoiceCaptureState.CAPTURING, sampleRateHz = source.sampleRateHz)

            val buffer = ShortArray(frameSamples)
            val deadline = maxDurationMs?.let { nanoTime() + it * 1_000_000L }
            var sequence = 0L
            var peak = 0f
            var failure: VoiceCaptureFailure? = null
            while (true) {
                if (!currentCoroutineContext().isActive || !epoch.isCurrent(generation)) { failure = VoiceCaptureFailure.CANCELLED; break }
                if (deadline != null && nanoTime() >= deadline) break
                val n = source.read(buffer, buffer.size)
                if (n < 0) { failure = VoiceCaptureFailure.READ_FAILED; break }
                if (n == 0) continue
                // Re-check AFTER the (blocking) read: a stale generation must not emit.
                if (!epoch.isCurrent(generation)) { failure = VoiceCaptureFailure.CANCELLED; break }
                val frame = VoicePcmFrame.copyOf(buffer, n, source.sampleRateHz, sequence) ?: continue
                sequence++
                framesRead++
                val level = frame.normalizedLevel()
                if (level > peak) peak = level
                _micLevel.value = level
                if (!_frames.tryEmit(frame)) dropped++
                _snapshot.value = VoiceCaptureSnapshot(
                    state = VoiceCaptureState.CAPTURING,
                    generation = generation,
                    framesRead = framesRead,
                    framesDropped = dropped,
                    lastFrameSamples = frame.sampleCount,
                    sampleRateHz = source.sampleRateHz,
                )
            }
            runCatching { source.stop() }
            val detail = when (failure) {
                null -> "ok ${source.description} frames=$framesRead peak=${"%.2f".format(peak)}"
                else -> "${failure.name.lowercase()} ${source.description} frames=$framesRead"
            }
            return outcome(failure, detail, framesRead)
        } finally {
            releaseOnce()
        }
    }

    private fun outcome(failure: VoiceCaptureFailure?, detail: String, framesRead: Long): VoiceCaptureOutcome {
        _lastDetail.value = detail
        return VoiceCaptureOutcome(failure, detail, framesRead)
    }

    companion object {
        /** 512 samples = 32 ms at 16 kHz (the frame size Silero VAD expects at 16 kHz). */
        const val DEFAULT_FRAME_SAMPLES = 512
        const val FRAME_BUFFER_CAPACITY = 64
    }
}
