package com.simone.jarvismobile.audio

import com.simone.jarvismobile.core.voice.ObservedAudioFocusState
import com.simone.jarvismobile.core.voice.ObservedAudioRoute
import com.simone.jarvismobile.core.voice.VoiceTurnAudioEvidence
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
 * 0.1, extended in Phase 0.2 with real TTS playback-start observability
 * (docs/JARVIS_MASTER_ARCHITECTURE.md). Derived observability only:
 * [SessionCoordinator] remains the sole voice-session owner and calls the
 * mark-and-finish methods below at existing call sites; this class owns no
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
        @Volatile var ttsPlaybackStartAtMs: Long? = null
        @Volatile var speechStartedAtMs: Long? = null
        @Volatile var speechEndedAtMs: Long? = null
        @Volatile var cancellationRequested: Boolean = false

        // Live Voice Phase 0.4 — raw audio route/focus accumulation. `null`
        // for the two "*route"/"*FocusState" pairs below means "never
        // observed yet this turn" (the first real observation captures
        // initial*; every value — including the first — becomes final*/the
        // new focus state immediately, so a turn with exactly one
        // observation correctly reports initial == final, not a fabricated
        // change). lastKnown* are purely for de-duplication (never
        // exposed): a duplicate platform callback reporting the same value
        // again must never bump a change count.
        @Volatile var initialInputRoute: ObservedAudioRoute? = null
        @Volatile var initialOutputRoute: ObservedAudioRoute? = null
        @Volatile var finalInputRoute: ObservedAudioRoute = ObservedAudioRoute.NOT_AVAILABLE
        @Volatile var finalOutputRoute: ObservedAudioRoute = ObservedAudioRoute.NOT_AVAILABLE
        @Volatile var communicationRouteAppliedAtStart: Boolean? = null
        @Volatile var routeChangeCount: Int = 0
        @Volatile var audioFocusAtStart: ObservedAudioFocusState? = null
        @Volatile var audioFocusLostDuringTurn: Boolean = false
        @Volatile var audioFocusRegainedDuringTurn: Boolean = false
        @Volatile var focusChangeCount: Int = 0
        private var lastKnownInputRoute: ObservedAudioRoute? = null
        private var lastKnownOutputRoute: ObservedAudioRoute? = null
        private var lastKnownFocusState: ObservedAudioFocusState? = null

        /** Records one real route observation. Returns true only if this was a genuine (de-duplicated) change, never the turn's first observation. */
        fun recordRouteObservation(input: ObservedAudioRoute, output: ObservedAudioRoute, communicationDeviceActive: Boolean?): Boolean {
            val isFirst = initialInputRoute == null
            if (isFirst) {
                initialInputRoute = input
                initialOutputRoute = output
                communicationRouteAppliedAtStart = communicationDeviceActive
            }
            val changed = !isFirst &&
                ((lastKnownInputRoute != null && lastKnownInputRoute != input) || (lastKnownOutputRoute != null && lastKnownOutputRoute != output))
            if (changed) routeChangeCount = (routeChangeCount + 1).coerceAtMost(MAX_BOUNDED_COUNT)
            lastKnownInputRoute = input
            lastKnownOutputRoute = output
            finalInputRoute = input
            finalOutputRoute = output
            return changed
        }

        /** Records one real focus observation. Returns true only if this was a genuine (de-duplicated) change, never the turn's first observation. */
        fun recordFocusObservation(state: ObservedAudioFocusState): Boolean {
            val isFirst = audioFocusAtStart == null
            if (isFirst) audioFocusAtStart = state
            val changed = !isFirst && lastKnownFocusState != null && lastKnownFocusState != state
            if (changed) {
                focusChangeCount = (focusChangeCount + 1).coerceAtMost(MAX_BOUNDED_COUNT)
                when (state) {
                    ObservedAudioFocusState.LOST_PERMANENT,
                    ObservedAudioFocusState.LOST_TRANSIENT,
                    ObservedAudioFocusState.LOST_TRANSIENT_CAN_DUCK -> audioFocusLostDuringTurn = true
                    ObservedAudioFocusState.GAIN -> if (audioFocusLostDuringTurn) audioFocusRegainedDuringTurn = true
                    else -> {}
                }
            }
            lastKnownFocusState = state
            return changed
        }

        fun toAudioEvidence(): VoiceTurnAudioEvidence = VoiceTurnAudioEvidence(
            initialInputRoute = initialInputRoute ?: ObservedAudioRoute.NOT_AVAILABLE,
            initialOutputRoute = initialOutputRoute ?: ObservedAudioRoute.NOT_AVAILABLE,
            finalInputRoute = finalInputRoute,
            finalOutputRoute = finalOutputRoute,
            communicationRouteAppliedAtStart = communicationRouteAppliedAtStart,
            routeChangeCount = routeChangeCount,
            audioFocusAtStart = audioFocusAtStart ?: ObservedAudioFocusState.NOT_AVAILABLE,
            audioFocusLostDuringTurn = audioFocusLostDuringTurn,
            audioFocusRegainedDuringTurn = audioFocusRegainedDuringTurn,
            focusChangeCount = focusChangeCount,
        )

        fun elapsedMs(): Long = (System.nanoTime() - startedAtNanos) / 1_000_000L

        fun toTimestamps(): VoiceTurnTimestamps = VoiceTurnTimestamps(
            sttStartedAtMs = sttStartedAtMs,
            sttFinalAtMs = sttFinalAtMs,
            answerStartedAtMs = answerStartedAtMs,
            answerReadyAtMs = answerReadyAtMs,
            ttsRequestedAtMs = ttsRequestedAtMs,
            ttsFinishedAtMs = ttsFinishedAtMs,
            bargeInRequestedAtMs = bargeInRequestedAtMs,
            ttsPlaybackStartAtMs = ttsPlaybackStartAtMs,
            speechStartedAtMs = speechStartedAtMs,
            speechEndedAtMs = speechEndedAtMs,
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
     * Called from [SessionCoordinator.speakOut] when a real playback-start
     * observation ([TtsPlaybackStartedEvent]) arrives for the in-flight
     * turn's current TTS invocation (Live Voice Phase 0.2). Idempotent —
     * only the first observation per turn is kept, matching every other
     * mark here — and a no-op once the turn has already finished (`current`
     * is null by then), which is what stops a late/stale event from ever
     * mutating a completed or superseded turn: this recorder only ever has
     * one in-flight turn, and [SessionCoordinator] independently fences by
     * invocation id before this is ever called.
     */
    fun markTtsPlaybackStarted() = mark { if (it.ttsPlaybackStartAtMs == null) it.ttsPlaybackStartAtMs = it.elapsedMs() }

    /**
     * Called from [SessionCoordinator] when a real [SttSpeechEvent.Type.STARTED]
     * observation arrives for the in-flight turn's current STT invocation
     * (Live Voice Phase 0.3) — [SessionCoordinator] independently fences by
     * invocation id before this is ever called, exactly like
     * [markTtsPlaybackStarted]. Idempotent — only the first observation per
     * turn is kept — and a no-op once the turn has already finished.
     */
    fun markUserSpeechStarted() = mark { if (it.speechStartedAtMs == null) it.speechStartedAtMs = it.elapsedMs() }

    /** Same discipline as [markUserSpeechStarted], for [SttSpeechEvent.Type.ENDED]. */
    fun markUserSpeechEnded() = mark { if (it.speechEndedAtMs == null) it.speechEndedAtMs = it.elapsedMs() }

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

    /**
     * Live Voice Phase 0.4 — called from [SessionCoordinator] whenever
     * [com.simone.jarvismobile.audio.AndroidAudioRouteManager]'s passively
     * observed route state changes (see that class's own doc comment for
     * the real platform callbacks backing this). A no-op outside an
     * in-flight turn, exactly like every other mark here — a route change
     * observed between turns (or after this turn has already finished)
     * never mutates a finished or unrelated record.
     */
    fun markAudioRouteObservation(input: ObservedAudioRoute, output: ObservedAudioRoute, communicationDeviceActive: Boolean?) =
        mark { it.recordRouteObservation(input, output, communicationDeviceActive) }

    /**
     * Live Voice Phase 0.4 — called from [SessionCoordinator] whenever
     * [TextToSpeechEngine.audioFocusEvents] emits a real observation (see
     * [com.simone.jarvismobile.tts.AudioFocusGate], the sole focus owner).
     * Same no-op-outside-a-turn discipline as [markAudioRouteObservation].
     */
    fun markAudioFocusObservation(state: ObservedAudioFocusState) =
        mark { it.recordFocusObservation(state) }

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
                audio = turn.toAudioEvidence(),
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
        /** Live Voice Phase 0.4 — bounds routeChangeCount/focusChangeCount; a flapping device/focus source must never grow a turn's evidence unboundedly. */
        const val MAX_BOUNDED_COUNT = 50
    }
}
