package com.simone.jarvismobile.core.voice

/**
 * High-level outcome of one voice turn (one listen -> answer -> speak
 * cycle inside [com.simone.jarvismobile.audio.SessionCoordinator]'s
 * hands-free loop). See docs/JARVIS_MASTER_ARCHITECTURE.md, "Live Voice
 * Phase 0.1", for the full audit this taxonomy is derived from.
 */
enum class VoiceTurnOutcome {
    /** A reply was generated and speakOut() was called for it — true even if TTS was later cut short by barge-in. */
    COMPLETED,
    /** The very first listen of the session heard nothing; a recoverable error, not a graceful close. */
    NO_SPEECH,
    /** A follow-up listen heard nothing; the session closes quietly on silence, never a failure. */
    FOLLOW_UP_CLOSED,
    /** The user said the stop word ("ok") with nothing pending; a quiet, intentional stop. */
    LISTENING_STOPPED,
    STT_UNAVAILABLE,
    STT_FAILURE,
    PERMISSION_DENIED,
    /** cancel() was called and CancellationException unwound the turn before it reached a normal outcome. */
    CANCELLED,
    /** runSession()'s generic Exception catch. */
    CRASH,
}

/** Which stage of the turn a cancellation/crash/failure happened in. */
enum class VoiceTurnFailureStage {
    NONE,
    PERMISSION,
    STT,
    ANSWER_GENERATION,
    TTS,
    /** Interrupted/crashed after every stage's own timestamp was already set — should not normally occur. */
    UNKNOWN,
}

/**
 * Raw per-turn timestamps, milliseconds elapsed since the turn began —
 * never wall-clock, so a device clock change mid-turn can never corrupt a
 * duration (the monotonic source itself is owned by the Android-side
 * recorder; this type only carries already-relative values so the
 * computation below stays pure and independently testable).
 *
 * A null field means that point was genuinely never reached, or is not
 * observable from the current implementation — never a fabricated/proxy
 * value. In particular there is deliberately no "first TTS audio" field:
 * [com.simone.jarvismobile.audio.HybridTtsEngine.speak] suspends until
 * playback finishes/stops/fails, so the moment the first PCM sample is
 * actually emitted is not observable from outside without an invasive
 * change to that engine or [com.simone.jarvismobile.tts.PcmPlayer] — out of
 * scope for this phase. The gap is recorded in prose in the Master
 * Architecture doc instead of a field that would always read null.
 */
data class VoiceTurnTimestamps(
    val sttStartedAtMs: Long? = null,
    val sttFinalAtMs: Long? = null,
    val answerStartedAtMs: Long? = null,
    val answerReadyAtMs: Long? = null,
    val ttsRequestedAtMs: Long? = null,
    val ttsFinishedAtMs: Long? = null,
    val bargeInRequestedAtMs: Long? = null,
)

/**
 * One structured, privacy-safe voice-turn timing record.
 *
 * Contains ONLY timestamps/durations/enums/counters/booleans — never
 * transcript text, reply text, prompt content, tool arguments, agenda/
 * health/location data, contact names, or any other model-generated
 * content. This is derived observability only: nothing here drives
 * routing, permissions, semantic interpretation, tool execution, retries,
 * or conversation state — [com.simone.jarvismobile.core.state.ConversationStateMachine]
 * and [com.simone.jarvismobile.audio.SessionCoordinator] remain the sole
 * authorities for those.
 */
data class VoiceTurnDiagnostics(
    val turnId: String,
    val startedAtEpochMs: Long,
    val followUpIndex: Int,
    val cancellationRequested: Boolean,
    val outcome: VoiceTurnOutcome,
    val failureStage: VoiceTurnFailureStage,
    /** How long until the STT final result — null unless both endpoints were actually observed. */
    val sttFinalLatencyMs: Long?,
    /** How long answer generation took — null unless both endpoints were actually observed. */
    val answerLatencyMs: Long?,
    /** How long the speakOut() call was in flight — the whole call, not first-audio latency; see [VoiceTurnTimestamps]'s doc comment. */
    val ttsDurationMs: Long?,
    /** Total time from this turn starting to it finishing, whatever the outcome. */
    val totalTurnMs: Long?,
    val bargeInRequested: Boolean,
    /** How long after a barge-in request this turn's own TTS-await actually returned — null unless both events belong to this same turn and are correctly ordered. */
    val ttsStoppedAfterBargeInMs: Long?,
) {
    companion object {

        /** Never fabricates a value: null unless BOTH endpoints were actually observed. */
        private fun latency(startMs: Long?, endMs: Long?): Long? =
            if (startMs != null && endMs != null) endMs - startMs else null

        fun compute(
            turnId: String,
            startedAtEpochMs: Long,
            followUpIndex: Int,
            cancellationRequested: Boolean,
            outcome: VoiceTurnOutcome,
            failureStage: VoiceTurnFailureStage,
            timestamps: VoiceTurnTimestamps,
            finishedAtMs: Long?,
        ): VoiceTurnDiagnostics {
            val bargeInRequested = timestamps.bargeInRequestedAtMs != null
            // Only claimed when the barge-in request happened before (or at)
            // this same turn's own TTS-await actually returning — otherwise
            // the two events cannot be trusted to belong together and the
            // subtraction would be meaningless, so it stays null rather than
            // ever reporting a negative/invented latency.
            val ttsStoppedAfterBargeInMs = if (
                timestamps.bargeInRequestedAtMs != null &&
                timestamps.ttsFinishedAtMs != null &&
                timestamps.bargeInRequestedAtMs <= timestamps.ttsFinishedAtMs
            ) {
                timestamps.ttsFinishedAtMs - timestamps.bargeInRequestedAtMs
            } else {
                null
            }

            return VoiceTurnDiagnostics(
                turnId = turnId,
                startedAtEpochMs = startedAtEpochMs,
                followUpIndex = followUpIndex,
                cancellationRequested = cancellationRequested,
                outcome = outcome,
                failureStage = failureStage,
                sttFinalLatencyMs = latency(timestamps.sttStartedAtMs, timestamps.sttFinalAtMs),
                answerLatencyMs = latency(timestamps.answerStartedAtMs, timestamps.answerReadyAtMs),
                ttsDurationMs = latency(timestamps.ttsRequestedAtMs, timestamps.ttsFinishedAtMs),
                totalTurnMs = finishedAtMs,
                bargeInRequested = bargeInRequested,
                ttsStoppedAfterBargeInMs = ttsStoppedAfterBargeInMs,
            )
        }

        /**
         * Infers which stage a cancellation/crash interrupted, from which
         * timestamps are still missing on an otherwise in-flight turn —
         * derived from real evidence, never guessed from anything else.
         */
        fun inferInterruptedStage(
            timestamps: VoiceTurnTimestamps,
            permissionDenied: Boolean = false,
        ): VoiceTurnFailureStage = when {
            permissionDenied -> VoiceTurnFailureStage.PERMISSION
            timestamps.sttFinalAtMs == null -> VoiceTurnFailureStage.STT
            timestamps.answerReadyAtMs == null -> VoiceTurnFailureStage.ANSWER_GENERATION
            timestamps.ttsFinishedAtMs == null -> VoiceTurnFailureStage.TTS
            else -> VoiceTurnFailureStage.UNKNOWN
        }
    }
}

/** Bounded voice-turn diagnostics history — append then trim, never grows past [maxSize]. */
fun List<VoiceTurnDiagnostics>.appendBounded(entry: VoiceTurnDiagnostics, maxSize: Int): List<VoiceTurnDiagnostics> =
    (this + entry).takeLast(maxSize)
