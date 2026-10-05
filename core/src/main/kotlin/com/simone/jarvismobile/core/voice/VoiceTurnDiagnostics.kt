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
    /**
     * Live Voice Phase 0.3 — set only from a genuine platform
     * `RecognitionListener.onBeginningOfSpeech()` callback (see
     * [com.simone.jarvismobile.audio.AndroidOnDeviceSpeechEngine]), never
     * inferred from STT-started or any other timing. Marks when the
     * platform itself first detected the user beginning to speak.
     */
    val speechStartedAtMs: Long? = null,
    /**
     * Live Voice Phase 0.3 — set only from a genuine platform
     * `RecognitionListener.onEndOfSpeech()` callback, never inferred. Marks
     * when the platform itself first detected the user stopping speaking —
     * the anchor for [VoiceTurnDiagnostics.responsePlaybackAfterSpeechMs],
     * the primary product metric this phase adds: "how long after the user
     * stops speaking does JARVIS start playback?"
     */
    val speechEndedAtMs: Long? = null,
    /**
     * Live Voice Phase 0.5 — set only from a genuine platform
     * `RecognitionListener.onReadyForSpeech()` callback (see
     * [com.simone.jarvismobile.audio.AndroidOnDeviceSpeechEngine]), never
     * inferred from `transcribe()` being called, recognizer construction,
     * or any other proxy. Means only that the recognizer reports it is
     * ready for the user to speak — NOT that the user has started speaking,
     * that microphone audio is non-zero, or that recognition will
     * ultimately succeed. The anchor for
     * [VoiceTurnDiagnostics.sttReadyLatencyMs] (paired with [sttStartedAtMs])
     * and [VoiceTurnDiagnostics.speechStartAfterReadyMs] (paired with
     * [speechStartedAtMs]).
     */
    val sttReadyAtMs: Long? = null,
    /**
     * Live Voice Phase 0.6 — set only from the FIRST genuine non-blank
     * `onPartialResults()` observation of the turn's STT invocation (see
     * [com.simone.jarvismobile.audio.SttSpeechEvent.Type.PARTIAL]). The
     * transcript itself is never stored. Anchor for
     * [VoiceTurnDiagnostics.partialTranscriptLatencyMs] (paired with
     * [sttStartedAtMs]) and [VoiceTurnDiagnostics.partialAfterSpeechStartMs]
     * (paired with [speechStartedAtMs]).
     */
    val sttFirstPartialAtMs: Long? = null,
)

/**
 * Live Voice Phase 0.5 — cold-start/internal-retry evidence for one voice
 * turn's STT invocation, assembled by
 * [com.simone.jarvismobile.audio.VoiceTurnDiagnosticsRecorder] from
 * [com.simone.jarvismobile.audio.SttAttemptSummary], a single summary
 * emitted synchronously by `AndroidOnDeviceSpeechEngine.transcribe()`
 * itself right before it returns (not a platform callback, so unlike the
 * route/focus evidence in Phase 0.4 there is no staleness/generation
 * concern here beyond the same invocation-id filtering already applied
 * upstream, in [com.simone.jarvismobile.audio.SessionCoordinator]).
 * `attemptCount == null` means never observed this turn — never fabricated
 * as zero or one.
 */
data class VoiceTurnSttAttemptEvidence(
    /** How many internal recognizer attempts (this engine's own transient cold-start retry loop) this turn's STT invocation needed. `null` if never observed. */
    val attemptCount: Int? = null,
)

/**
 * Live Voice Phase 0.4 — bounded, privacy-safe classification of an audio
 * endpoint observed by [com.simone.jarvismobile.audio.AndroidAudioRouteManager]
 * — never a product name, MAC address, serial or any other Android device
 * string. [NOT_AVAILABLE] means "never observed this turn" (e.g. platform
 * evidence genuinely absent); it is distinct from [UNKNOWN], which means a
 * device WAS observed but did not match any of the known closed-world
 * classifications — the same distinction [com.simone.jarvismobile.audio.AudioDeviceKind]
 * already draws for [UNKNOWN] alone, extended here with an explicit
 * not-yet-observed case so a genuinely missing observation is never
 * conflated with "observed but unclassifiable".
 */
enum class ObservedAudioRoute { PHONE, SPEAKER, WIRED_HEADSET, BLUETOOTH_HEADSET, AIRPODS, UNKNOWN, NOT_AVAILABLE }

/**
 * Live Voice Phase 0.4 — bounded, closed-world audio-focus observation.
 * [GRANTED]/[DENIED]/[DELAYED] come only from `AudioManager.requestAudioFocus()`'s
 * own return value (see [com.simone.jarvismobile.tts.AudioFocusGate.acquire]);
 * [GAIN]/[LOST_PERMANENT]/[LOST_TRANSIENT]/[LOST_TRANSIENT_CAN_DUCK] come only
 * from a real `AudioManager.OnAudioFocusChangeListener` callback — never
 * inferred from the mere fact that a request was made. [NOT_AVAILABLE] means
 * "never observed this turn" (distinct from [UNKNOWN], which means a real
 * callback/result fired but did not map to any of the known constants).
 */
enum class ObservedAudioFocusState { GRANTED, DENIED, DELAYED, GAIN, LOST_PERMANENT, LOST_TRANSIENT, LOST_TRANSIENT_CAN_DUCK, UNKNOWN, NOT_AVAILABLE }

/**
 * Live Voice Phase 0.4 — raw, privacy-safe audio route/focus evidence for one
 * voice turn, assembled by [com.simone.jarvismobile.audio.VoiceTurnDiagnosticsRecorder]
 * from real platform callbacks forwarded by [com.simone.jarvismobile.audio.SessionCoordinator]
 * (see that class's own doc comments for the exact observation contract).
 * Every field is closed-world/bounded by construction — no field here can
 * ever carry a product name, device id, or other free-form Android string.
 *
 * This is derived observability only, exactly like [VoiceTurnTimestamps]:
 * nothing here ever drove, or is driven by, an actual routing/focus
 * decision — [com.simone.jarvismobile.audio.AndroidAudioRouteManager] and
 * [com.simone.jarvismobile.tts.AudioFocusGate] remain the sole authorities
 * for that, unchanged by this phase.
 */
data class VoiceTurnAudioEvidence(
    val initialInputRoute: ObservedAudioRoute = ObservedAudioRoute.NOT_AVAILABLE,
    val initialOutputRoute: ObservedAudioRoute = ObservedAudioRoute.NOT_AVAILABLE,
    val finalInputRoute: ObservedAudioRoute = ObservedAudioRoute.NOT_AVAILABLE,
    val finalOutputRoute: ObservedAudioRoute = ObservedAudioRoute.NOT_AVAILABLE,
    /**
     * Whether a genuinely active communication device (`AudioManager.communicationDevice`,
     * API 31+) was observed at the moment of the turn's first route
     * observation. `null` means never observed this turn — never coerced to
     * `false`, which would wrongly claim proof of absence.
     */
    val communicationRouteAppliedAtStart: Boolean? = null,
    /** Bounded count of genuine (de-duplicated) route changes observed during the turn — never unbounded. */
    val routeChangeCount: Int = 0,
    val audioFocusAtStart: ObservedAudioFocusState = ObservedAudioFocusState.NOT_AVAILABLE,
    val audioFocusLostDuringTurn: Boolean = false,
    /** Only ever true if a loss was observed first — a gain with no prior loss is not a "regain". */
    val audioFocusRegainedDuringTurn: Boolean = false,
    /** Bounded count of genuine (de-duplicated) focus-state changes observed during the turn — never unbounded. */
    val focusChangeCount: Int = 0,
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
    /**
     * Live Voice Phase 0.3 — onBeginningOfSpeech -> onEndOfSpeech, i.e. how
     * long the platform observed the user actually speaking. Null unless
     * both boundary callbacks were observed for this same turn AND are
     * correctly ordered (start <= end) — never fabricated, never negative.
     */
    val userSpeechDurationMs: Long?,
    /**
     * Live Voice Phase 0.3 — onEndOfSpeech -> the final [com.simone.jarvismobile.audio.SttResult]
     * (see [sttFinalAtMs][VoiceTurnTimestamps.sttFinalAtMs] via [sttFinalLatencyMs]'s
     * own start point). How long STT took to finalize after the platform
     * detected the user had stopped speaking. Null unless both endpoints
     * were observed and speechEnd <= sttFinal.
     */
    val sttFinalizationAfterSpeechMs: Long?,
    /**
     * Live Voice Phase 0.3 — the PRIMARY product metric this phase adds:
     * onEndOfSpeech -> the same trustworthy TTS playback-start evidence
     * Phase 0.2 introduced ([ttsPlaybackStartLatencyMs]'s own end point) —
     * "how long after the user stops speaking does JARVIS start playback?"
     * Null unless both endpoints were observed and speechEnd <=
     * ttsPlaybackStart. Deliberately named for exactly what it measures,
     * never "conversational latency" or any other undocumented alias.
     */
    val responsePlaybackAfterSpeechMs: Long?,
    /**
     * Live Voice Phase 0.5 — `SessionCoordinator` requests STT
     * ([VoiceTurnTimestamps.sttStartedAtMs]) -> the real platform
     * `onReadyForSpeech()` observation ([VoiceTurnTimestamps.sttReadyAtMs]).
     * Null unless both endpoints were observed and ordered (request <=
     * ready) — never fabricated, never negative.
     */
    val sttReadyLatencyMs: Long?,
    /**
     * Live Voice Phase 0.5 — `onReadyForSpeech()` -> the real platform
     * `onBeginningOfSpeech()` observation ([VoiceTurnTimestamps.speechStartedAtMs]).
     * Null unless both endpoints were observed and ordered (ready <=
     * speech-started) — never fabricated, never negative. READY does not
     * mean the user started speaking; this metric is exactly the gap
     * between the two distinct platform facts.
     */
    val speechStartAfterReadyMs: Long?,
    /**
     * Live Voice Phase 0.6 — STT request/start -> the FIRST real non-blank
     * partial transcript observation ([VoiceTurnTimestamps.sttFirstPartialAtMs]).
     * Null unless both endpoints were observed and ordered; a final result
     * without any partial yields null — the final transcript time is never
     * substituted.
     */
    val partialTranscriptLatencyMs: Long?,
    /**
     * Live Voice Phase 0.6 — real `onBeginningOfSpeech()` -> first real
     * partial transcript observation. Null unless both endpoints were
     * observed and ordered (speech-started <= first-partial); never
     * negative, never fabricated.
     */
    val partialAfterSpeechStartMs: Long?,
    /**
     * Live Voice Phase 0.5 — how many internal recognizer attempts
     * ([com.simone.jarvismobile.audio.AndroidOnDeviceSpeechEngine]'s own
     * transient cold-start retry loop, unchanged by this phase) this turn's
     * STT invocation needed. `null` means never observed this turn — never
     * fabricated as `1`.
     */
    val sttAttemptCount: Int?,
    /** True only when [sttAttemptCount] is known AND greater than one — `null` (not `false`) when [sttAttemptCount] itself is unknown, never implying "exactly one attempt" from an absence of evidence. */
    val sttColdStartRetryObserved: Boolean?,
    /** Live Voice Phase 0.4 — see [VoiceTurnAudioEvidence]'s own doc comment for the observation contract. */
    val initialInputRoute: ObservedAudioRoute,
    val initialOutputRoute: ObservedAudioRoute,
    val finalInputRoute: ObservedAudioRoute,
    val finalOutputRoute: ObservedAudioRoute,
    /** Derived only from [initialInputRoute]/[finalInputRoute] — never from mere Bluetooth connectivity, which proves nothing about what STT/capture actually used (§22: never infer "Bluetooth mic in use" from connectivity alone). */
    val bluetoothInputObserved: Boolean,
    val bluetoothOutputObserved: Boolean,
    val communicationRouteAppliedAtStart: Boolean?,
    /** True only when [routeChangeCount] is greater than zero — never inferred any other way. */
    val routeChangedDuringTurn: Boolean,
    val routeChangeCount: Int,
    val audioFocusAtStart: ObservedAudioFocusState,
    val audioFocusLostDuringTurn: Boolean,
    val audioFocusRegainedDuringTurn: Boolean,
    val focusChangeCount: Int,
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
            /** Live Voice Phase 0.4 — defaulted so every existing caller/test is unaffected. */
            audio: VoiceTurnAudioEvidence = VoiceTurnAudioEvidence(),
            /** Live Voice Phase 0.5 — defaulted so every existing caller/test is unaffected. */
            sttAttempts: VoiceTurnSttAttemptEvidence = VoiceTurnSttAttemptEvidence(),
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

            // Live Voice Phase 0.3 — same non-negative/correctly-ordered
            // discipline as ttsPlaybackStartLatencyMs above: a pair is only
            // ever claimed when both endpoints exist AND the earlier one is
            // <= the later one, otherwise null (never a fabricated or
            // negative duration).
            fun orderedLatency(startMs: Long?, endMs: Long?): Long? =
                if (startMs != null && endMs != null && startMs <= endMs) endMs - startMs else null

            val userSpeechDurationMs = orderedLatency(timestamps.speechStartedAtMs, timestamps.speechEndedAtMs)
            val sttFinalizationAfterSpeechMs = orderedLatency(timestamps.speechEndedAtMs, timestamps.sttFinalAtMs)
            val responsePlaybackAfterSpeechMs = orderedLatency(timestamps.speechEndedAtMs, timestamps.ttsPlaybackStartAtMs)

            // Live Voice Phase 0.5 — same ordered/never-fabricated discipline.
            val sttReadyLatencyMs = orderedLatency(timestamps.sttStartedAtMs, timestamps.sttReadyAtMs)
            val speechStartAfterReadyMs = orderedLatency(timestamps.sttReadyAtMs, timestamps.speechStartedAtMs)
            // Live Voice Phase 0.6 — same ordered/never-fabricated discipline.
            val partialTranscriptLatencyMs = orderedLatency(timestamps.sttStartedAtMs, timestamps.sttFirstPartialAtMs)
            val partialAfterSpeechStartMs = orderedLatency(timestamps.speechStartedAtMs, timestamps.sttFirstPartialAtMs)
            val sttColdStartRetryObserved = sttAttempts.attemptCount?.let { it > 1 }

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
                userSpeechDurationMs = userSpeechDurationMs,
                sttFinalizationAfterSpeechMs = sttFinalizationAfterSpeechMs,
                responsePlaybackAfterSpeechMs = responsePlaybackAfterSpeechMs,
                sttReadyLatencyMs = sttReadyLatencyMs,
                speechStartAfterReadyMs = speechStartAfterReadyMs,
                partialTranscriptLatencyMs = partialTranscriptLatencyMs,
                partialAfterSpeechStartMs = partialAfterSpeechStartMs,
                sttAttemptCount = sttAttempts.attemptCount,
                sttColdStartRetryObserved = sttColdStartRetryObserved,
                initialInputRoute = audio.initialInputRoute,
                initialOutputRoute = audio.initialOutputRoute,
                finalInputRoute = audio.finalInputRoute,
                finalOutputRoute = audio.finalOutputRoute,
                bluetoothInputObserved = audio.initialInputRoute.isBluetooth() || audio.finalInputRoute.isBluetooth(),
                bluetoothOutputObserved = audio.initialOutputRoute.isBluetooth() || audio.finalOutputRoute.isBluetooth(),
                communicationRouteAppliedAtStart = audio.communicationRouteAppliedAtStart,
                routeChangedDuringTurn = audio.routeChangeCount > 0,
                routeChangeCount = audio.routeChangeCount,
                audioFocusAtStart = audio.audioFocusAtStart,
                audioFocusLostDuringTurn = audio.audioFocusLostDuringTurn,
                audioFocusRegainedDuringTurn = audio.audioFocusRegainedDuringTurn,
                focusChangeCount = audio.focusChangeCount,
            )
        }

        private fun ObservedAudioRoute.isBluetooth(): Boolean =
            this == ObservedAudioRoute.BLUETOOTH_HEADSET || this == ObservedAudioRoute.AIRPODS

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
