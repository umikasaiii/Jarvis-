package com.simone.jarvismobile.audio

import com.simone.jarvismobile.core.voice.VoiceTurnDiagnostics
import com.simone.jarvismobile.core.voice.VoiceTurnFailureStage
import com.simone.jarvismobile.core.voice.VoiceTurnOutcome
import com.simone.jarvismobile.core.voice.VoiceTurnTimestamps
import com.simone.jarvismobile.core.voice.appendBounded
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded, privacy-safe voice-turn timing diagnostics — Live Voice Phase
 * 0.1 (docs/JARVIS_MASTER_ARCHITECTURE.md). Derived observability only:
 * [SessionCoordinator] remains the sole voice-session owner and calls the
 * mark*/finish* methods below at existing call sites; this class owns no
 * session state of its own beyond the current in-flight turn's raw
 * timestamps, and nothing it records ever feeds back into routing,
 * permissions, semantic interpretation, tool execution, retries, or
 * conversation state.
 *
 * Every method is a plain, non-suspending field write wrapped in
 * [runCatching] — a bug here must never break a real conversation, and
 * none of these calls can themselves throw [kotlinx.coroutines.CancellationException]
 * (they never call a suspend function), so there is nothing to
 * accidentally swallow that the existing cancellation contract cares about.
 *
 * All elapsed values are measured from [System.nanoTime] — a monotonic
 * source immune to wall-clock adjustments mid-turn — centralised here so
 * [SessionCoordinator] itself never calls a raw clock API directly; only
 * [MutableTurn.startedAtEpochMs] is wall-clock, kept solely for the
 * human-readable timestamp on a finished record.
 */
@Singleton
class VoiceTurnDiagnosticsRecorder @Inject constructor() {

    private class MutableTurn(val turnId: String, val startedAtEpochMs: Long, val followUpIndex: Int) {
        private val startedAtNanos = System.nanoTime()

        @Volatile var sttStartedAtMs: Long? = null
        @Volatile var sttFinalAtMs: Long? = null
        @Volatile var answerStartedAtMs: Long? = null
        @Volatile var answerReadyAtMs: Long? = null
        @Volatile var ttsRequestedAtMs: Long? = null
        @Volatile var ttsFinishedAtMs: Long? = null
        @Volatile var bargeInRequestedAtMs: Long? = null
        @Volatile var cancellationRequested: Boolean = false

        fun elapsedMs(): Long = (System.nanoTime() - startedAtNanos) / 1_000_000L

        fun toTimestamps(): VoiceTurnTimestamps = VoiceTurnTimestamps(
            sttStartedAtMs = sttStartedAtMs,
            sttFinalAtMs = sttFinalAtMs,
            answerStartedAtMs = answerStartedAtMs,
            answerReadyAtMs = answerReadyAtMs,
            ttsRequestedAtMs = ttsRequestedAtMs,
            ttsFinishedAtMs = ttsFinishedAtMs,
            bargeInRequestedAtMs = bargeInRequestedAtMs,
        )
    }

    @Volatile private var current: MutableTurn? = null

    private val _history = MutableStateFlow<List<VoiceTurnDiagnostics>>(emptyList())
    /** Latest [MAX_HISTORY] finished voice turns, oldest first. */
    val history: StateFlow<List<VoiceTurnDiagnostics>> = _history.asStateFlow()

    /** Starts a new in-flight turn record. [followUpIndex] is the same 0-based counter [SessionCoordinator.runTurn] already tracks. */
    fun beginTurn(followUpIndex: Int) {
        runCatching {
            current = MutableTurn(
                turnId = UUID.randomUUID().toString(),
                startedAtEpochMs = System.currentTimeMillis(),
                followUpIndex = followUpIndex,
            )
        }
    }

    fun markSttStarted() = mark { it.sttStartedAtMs = it.elapsedMs() }
    fun markSttFinal() = mark { it.sttFinalAtMs = it.elapsedMs() }
    fun markAnswerStarted() = mark { it.answerStartedAtMs = it.elapsedMs() }
    fun markAnswerReady() = mark { it.answerReadyAtMs = it.elapsedMs() }
    fun markTtsRequested() = mark { it.ttsRequestedAtMs = it.elapsedMs() }
    fun markTtsFinished() = mark { it.ttsFinishedAtMs = it.elapsedMs() }

    /**
     * Called from [SessionCoordinator.interruptAndListen] — reachable only
     * while this same in-flight turn's own `speakOut()` is still suspended
     * waiting for TTS, since that branch is gated on TTS actually speaking;
     * see that function's own doc comment for why the ordering this relies
     * on is structural, not incidental.
     */
    fun markBargeInRequested() = mark { if (it.bargeInRequestedAtMs == null) it.bargeInRequestedAtMs = it.elapsedMs() }

    /** Called from [SessionCoordinator.cancel] — marks the in-flight turn, if any, as having been asked to stop. */
    fun markCancellationRequested() = mark { it.cancellationRequested = true }

    /** Finishes the in-flight turn (if any) with an explicit outcome/stage. A no-op if no turn is in flight. */
    fun finish(outcome: VoiceTurnOutcome, failureStage: VoiceTurnFailureStage) {
        runCatching {
            val turn = current ?: return
            current = null
            val record = VoiceTurnDiagnostics.compute(
                turnId = turn.turnId,
                startedAtEpochMs = turn.startedAtEpochMs,
                followUpIndex = turn.followUpIndex,
                cancellationRequested = turn.cancellationRequested,
                outcome = outcome,
                failureStage = failureStage,
                timestamps = turn.toTimestamps(),
                finishedAtMs = turn.elapsedMs(),
            )
            _history.value = _history.value.appendBounded(record, MAX_HISTORY)
        }
    }

    /** Finishes the in-flight turn (if any) as CANCELLED, inferring the interrupted stage from which timestamps are still missing. */
    fun finishCancelled() = finishInterrupted(VoiceTurnOutcome.CANCELLED)

    /** Finishes the in-flight turn (if any) as CRASH, inferring the interrupted stage the same way as [finishCancelled]. */
    fun finishCrashed() = finishInterrupted(VoiceTurnOutcome.CRASH)

    private fun finishInterrupted(outcome: VoiceTurnOutcome) {
        val stage = current?.toTimestamps()
            ?.let { VoiceTurnDiagnostics.inferInterruptedStage(it) }
            ?: VoiceTurnFailureStage.UNKNOWN
        finish(outcome, stage)
    }

    private inline fun mark(block: (MutableTurn) -> Unit) {
        runCatching { current?.let(block) }
    }

    private companion object {
        const val MAX_HISTORY = 20
    }
}
