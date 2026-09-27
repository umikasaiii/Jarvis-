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
 * value.
 *
 * Live Voice Phase 0.1 shipped without a playback-start field:
 * [com.simone.jarvismobile.audio.HybridTtsEngine.speak] suspends until
 * playback finishes/stops/fails, and at that time the moment playback
 * actually began was not observable from outside without touching that
 * engine or [com.simone.jarvismobile.tts.PcmPlayer]. Phase 0.2 closes that
 * gap for real — see [ttsPlaybackStartAtMs] below — by having each engine
 * signal a genuine playback-subsystem event (never a bare `speak()` call,
 * never synthesis start) up through a small, opaque, invocation-scoped
 * event contract; the recorder still owns the only [System.nanoTime] read
 * on that event's arrival, exactly as it already does for every other
 * mark here.
 */
data class VoiceTurnTimestamps(
    val sttStartedAtMs: Long? = null,
    val sttFinalAtMs: Long? = null,
    val answerStartedAtMs: Long? = null,
    val answerReadyAtMs: Long? = null,
    val ttsRequestedAtMs: Long? = null,
    val ttsFinishedAtMs: Long? = null,
    val bargeInRequestedAtMs: Long? = null,
    /**
     * Live Voice Phase 0.2 — set only when a real playback-subsystem event
     * (not a bare call to [com.simone.jarvismobile.audio.TextToSpeechEngine.speak],
     * not synthesis start, not the first PCM chunk generated, not a lone
     * [com.simone.jarvismobile.tts.PcmPlayer.write] call succeeding) proved
     * that this turn's TTS invocation actually started producing audio at
     * the playback subsystem — [android.media.AudioTrack.getPlaybackHeadPosition]
     * genuinely advancing for the neural path, the platform's own
     * `onStart` utterance callback for the Android TTS path. Software can
     * prove playback started at the playback subsystem; it cannot prove
     * the user physically heard the speaker — hence "playback start", never
     * "first audible sample", everywhere this is named.
     */
    val ttsPlaybackStartAtMs: Long? = null,
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
    /**
     * `SessionCoordinator` requests speech → first trustworthy playback-start
     * observation for that same TTS invocation (Live Voice Phase 0.2). Null
     * whenever the request timestamp or the playback-start evidence is
     * missing, OR the two are not validly ordered (playback-start evidence
     * timestamped before the request — e.g. a stale/rejected callback from a
     * prior, already-superseded invocation) — never fabricated, never a
     * negative duration. A barge-in that stops playback before it ever
     * started leaves this null; a barge-in requested *after* a genuine
     * playback-start observation does not erase that observation — the two
     * are independent evidence, exactly like [ttsStoppedAfterBargeInMs]
     * above is independent of [bargeInRequested].
     */
    val ttsPlaybackStartLatencyMs: Long?,
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

            // Only claimed when the request timestamp precedes (or coincides
            // with) the playback-start evidence — otherwise the evidence
            // cannot be trusted to belong to this invocation's request (e.g.
            // a stale callback from an already-superseded invocation, which
            // the recorder/session owner reject deterministically by
            // invocation identity before this ever runs — this ordering
            // check is a second, independent safeguard against ever
            // reporting a negative/invented latency).
            val ttsPlaybackStartLatencyMs = if (
                timestamps.ttsRequestedAtMs != null &&
                timestamps.ttsPlaybackStartAtMs != null &&
                timestamps.ttsRequestedAtMs <= timestamps.ttsPlaybackStartAtMs
            ) {
                timestamps.ttsPlaybackStartAtMs - timestamps.ttsRequestedAtMs
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
                ttsPlaybackStartLatencyMs = ttsPlaybackStartLatencyMs,
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
