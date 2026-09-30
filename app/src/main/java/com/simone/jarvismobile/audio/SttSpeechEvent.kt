package com.simone.jarvismobile.audio

/**
 * Live Voice Phase 0.3 — fired from [AndroidOnDeviceSpeechEngine]'s
 * `RecognitionListener.onBeginningOfSpeech()`/`onEndOfSpeech()` callbacks,
 * the platform's own real speech-boundary signals (never inferred from
 * timing, never from [SpeechToTextEngine.partial] activity, never
 * synthesized). Mirrors the precedent set by [TtsPlaybackStartedEvent] in
 * Phase 0.2: opaque [invocationId] identity, zero content (no transcript,
 * no partial text, no audio, no language), a bounded two-value taxonomy,
 * and instance-scoped emission — never a companion object — so
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
 * consumer.
 *
 * Derived observability only: nothing consumes this to drive recognition
 * behavior, retries, routing, permissions, semantic interpretation, tool
 * execution or conversation state — see
 * [VoiceTurnDiagnosticsRecorder.markUserSpeechStarted]/
 * [VoiceTurnDiagnosticsRecorder.markUserSpeechEnded].
 *
 * Deliberately the smallest coherent event contract for this phase: no
 * further variants exist because nothing here needs them yet.
 */
data class SttSpeechEvent(val invocationId: String, val type: Type) {
    enum class Type { STARTED, ENDED }
}
