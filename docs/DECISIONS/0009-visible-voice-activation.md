# ADR 0009 — Visible voice activation and offline speech

## Decision

The user can assign JARVIS the native Android Assistant role. The system
gesture/button starts the exported `ACTION_ASSIST` Activity, opens the visible
chat and begins one explicit listening session. We do not ship a custom always-on
hotword detector.

Android TTS exposes only installed non-network voices. The user can select a
voice and configure bounded rate/pitch. Pressing the microphone while JARVIS is
speaking stops synthesis and immediately reopens listening (barge-in).

Spoken background answers are opt-in and disabled by default. When enabled, the
WorkManager job switches its visible foreground service to the media-playback
type for the duration of speech. Cancelling the job or starting a visible voice
session stops that playback.

## Consequences

Activation follows MagicOS/Android policy and remains visible and revocable.
There is no hidden microphone or persistent wake-word battery drain. Available
voices depend on offline TTS data installed on the phone.
