package com.simone.jarvismobile.audio

/**
 * Live Voice Phase 0.3 (extended in Phase 0.5 with [Type.READY]) — fired
 * from [AndroidOnDeviceSpeechEngine]'s `RecognitionListener` callbacks, the
 * platform's own real signals (never inferred from timing, never from
 * [SpeechToTextEngine.partial] activity, never synthesized). Mirrors the
 * precedent set by [TtsPlaybackStartedEvent] in Phase 0.2: opaque
 * [invocationId] identity, zero content (no transcript, no partial text,
 * no audio, no language), a bounded closed-world taxonomy, and
 * instance-scoped emission — never a companion object — so
 * [RecognizerWakeWordEngine]'s own separate [AndroidOnDeviceSpeechEngine]
 * instance can never contaminate [SessionCoordinator]'s voice-turn
 * diagnostics; it simply has no subscriber wired to it.
 *
 * [invocationId] identifies the outer [SpeechToTextEngine.transcribe] call
 * (one per turn), never the engine's own internal transient-retry attempts.
 * A late callback belonging to an already-abandoned internal retry attempt
 * (or to an entirely different, already-superseded [transcribe] call) is
 * rejected deterministically — by generation identity, never a time window
 * — before it can ever reach here; see [AndroidOnDeviceSpeechEngine]'s own
 * fencing. Retry attempt numbers are never exposed here or to any
 * consumer — see [SttAttemptSummary] for that, a deliberately separate,
 * non-boundary-event channel.
 *
 * Derived observability only: nothing consumes this to drive recognition
 * behavior, retries, routing, permissions, semantic interpretation, tool
 * execution or conversation state — see
 * [VoiceTurnDiagnosticsRecorder.markSttReady]/
 * [VoiceTurnDiagnosticsRecorder.markUserSpeechStarted]/
 * [VoiceTurnDiagnosticsRecorder.markUserSpeechEnded].
 *
 * Deliberately the smallest coherent event contract for this phase: no
 * further variants exist because nothing here needs them yet.
 */
data class SttSpeechEvent(val invocationId: String, val type: Type) {
    /**
     * [READY] — Live Voice Phase 0.5 — the platform's own
     * `RecognitionListener.onReadyForSpeech()` callback: the recognizer
     * reports it is ready for the user to speak. This does NOT mean the
     * user has started speaking ([STARTED]/`onBeginningOfSpeech()`), that
     * microphone audio is non-zero, or that recognition will ultimately
     * succeed — it is a distinct, earlier platform fact, never conflated
     * with the other two.
     */
    /**
     * [PARTIAL] — Live Voice Phase 0.6 — the FIRST genuine, non-blank
     * `RecognitionListener.onPartialResults()` observation of one internal
     * recognizer attempt. Carries only the FACT that a partial transcript
     * was observed — never its text, confidence or any Bundle content (the
     * user-facing [SpeechToTextEngine.partial] flow remains the only place
     * partial text lives, unchanged). Fenced by the same `attemptGeneration`
     * as the other three types; emitted at most once per attempt.
     */
    enum class Type { READY, STARTED, ENDED, PARTIAL }
}

/**
 * Live Voice Phase 0.5 — fired once by [AndroidOnDeviceSpeechEngine.transcribe]
 * itself, synchronously right before it returns, carrying how many internal
 * recognizer attempts (its own transient cold-start retry loop — see
 * [AndroidOnDeviceSpeechEngine]'s own `RETRY_BACKOFF_MS`/`attempt`, both
 * entirely unchanged by this phase) that exact invocation needed.
 * Deliberately a separate, non-boundary-event channel from
 * [SttSpeechEvent]: this is retry bookkeeping, not a speech boundary, and
 * folding it into [SttSpeechEvent.Type] would have conflated two different
 * observation domains. [invocationId] is the same external identity passed
 * to [SpeechToTextEngine.transcribe] — never the engine's own internal
 * `attemptGeneration` counter (see that field's own doc comment for the
 * distinction); per-attempt retry reasons/error codes/text are never
 * exposed here, only a plain bounded count.
 */
data class SttAttemptSummary(val invocationId: String, val attemptCount: Int)
