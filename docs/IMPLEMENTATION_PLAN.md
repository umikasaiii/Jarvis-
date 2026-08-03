# Implementation Plan

Vertical slices, each ending compilable + tested. Never leave the main branch
uncompilable. Current status is tracked in `CLAUDE.md`.

## Phase 0 — Foundations *(in progress)*
Project, Compose, DI (Hilt), Room, DataStore, navigation, safe logging, docs, CI.
- Done: `:core` module (state machine, router, protocol, policies, ranking,
  redaction) compiling with **58 passing unit tests**; version catalog; Gradle
  composite build; Android scaffolding; docs; CI workflow; templates.
- Pending: build `:app` in an SDK environment; Room/DataStore/SecretStore wiring;
  navigation graph; ConnectivityMonitor.
- **Exit:** Android project builds a debug APK in Android Studio/CI with SDK.

## Phase 1 — Audio loop *(code written)*
Button, Tile, foreground service, audio routing, recording, AirPods test,
fixed-text TTS playback.
- Done (code, unverified until SDK build): `AudioRouteManager` + Android impl,
  `AndroidOfflineTtsEngine`, `ListeningService`, `JarvisTileService`, Compose
  Home push-to-talk, permissions, deep link, ViewModel driving the core FSM.
- **Exit:** press → listen → stop → fixed Italian phrase spoken through the
  actually-selected device (AirPods when present, else phone).

## Phase 2 — STT + VAD
sherpa-onnx (pinned), importable model, VAD, transcription, transcript UI,
offline tests with WAV fixtures.
- **Exit:** airplane mode → press → Italian sentence → correct transcription.

## Phase 3 — Local LLM
llama.cpp binding, ModelManager, GGUF import + SHA-256, streaming, cancellation,
benchmark, Jarvis prompt, protocol wiring.
- **Exit:** airplane mode → voice → local Qwen → textual answer.

## Phase 4 — Full TTS
Answer in AirPods, audio focus handling, stop, follow-up window.
- **Exit:** airplane mode → full spoken conversation, no typing.

## Phase 5 — Obsidian memory
Vault selection (SAF), indexing, Room FTS, retrieval, read, write proposal,
confirmation, undo.
- **Exit:** voice question → answer grounded in a real Markdown note.

## Phase 6 — Tool system
Registry, validated JSON, policies, confirmations, local tools.
- **Exit:** model proposes a tool → app validates, confirms, really executes.

## Phase 7 — Home Assistant
Connection, state, services, whitelist, confirmations.

## Phase 8 — PC companion (`server/`)
FastAPI server, routing, streaming, fallback, VPN docs.

## Phase 9 — Hardening
Threat model, performance/thermal tests, release build, final docs.

## Working rules
After each slice: compile, run unit tests, lint, fix, update this plan, record a
decision in `docs/DECISIONS/`. If a library fails: diagnose, check primary docs,
pick a stable alternative, document why — no permanent mocks in the main path.
