package com.simone.jarvismobile.audio

/**
 * Fired at most once per [invocationId], the first time a real playback
 * subsystem event proves that TTS playback has actually started for that
 * invocation (Live Voice Phase 0.2 — docs/JARVIS_MASTER_ARCHITECTURE.md).
 *
 * Never fired on the mere call to [TextToSpeechEngine.speak], never on
 * synthesis start, never on the first PCM chunk generated, never on a lone
 * [com.simone.jarvismobile.tts.PcmPlayer.write] call succeeding — see
 * [AndroidOfflineTtsEngine] (the platform's own `onStart` utterance
 * callback, armed for exactly the first queued segment of an invocation)
 * and [PcmPlayer.playbackStartEvents][com.simone.jarvismobile.tts.PcmPlayer]
 * (`AudioTrack.getPlaybackHeadPosition` genuinely advancing past this
 * session's own baseline) for what each engine actually treats as
 * trustworthy evidence.
 *
 * [invocationId] is opaque — a generated id, never the spoken text — so a
 * late/duplicate event from an already-superseded invocation is rejected
 * deterministically by whoever is still tracking that exact invocation
 * (an exact string match, never a time window). This is derived
 * observability only: nothing consumes it to drive routing, retries,
 * permissions, conversation state or tool execution — see
 * [VoiceTurnDiagnosticsRecorder.markTtsPlaybackStarted].
 *
 * Deliberately the smallest coherent event contract for this phase: no
 * PlaybackFinished/Stopped/Failed siblings exist because nothing here needs
 * them yet — completion/stop/failure of a `speak()` call is already known
 * by that suspend call returning, and inventing three more event variants
 * with no consumer would be a second, redundant TTS lifecycle source of
 * truth, exactly what this contract must not become.
 */
data class TtsPlaybackStartedEvent(val invocationId: String)
