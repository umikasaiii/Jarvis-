# JARVIS Mobile

Offline-first personal voice assistant for Android, in Italian. Built for an
HONOR 200 (MagicOS / Snapdragon 7 Gen 3) with AirPods, designed to work **fully
in airplane mode**: press a button or Quick Settings tile, speak, and get a
spoken answer from a **local** language model — no account, no cloud, no hidden
recording.

> **Status:** active pre-release Android app. CI builds and publishes a debug APK;
> offline STT, LiteRT-LM chat, structured agenda, persistent background answers,
> Memory V2, controlled Android tools and configurable offline voice are present.
> Phone acceptance checks are still required before a signed release. See
> [`CLAUDE.md`](CLAUDE.md) for the honest phase-by-phase state.

## Why

- **Privacy by default.** Nothing leaves the device without explicit opt-in.
- **Works without internet.** Voice → transcription → local model → memory → voice.
- **Your notes are yours.** Memory lives in a readable **Obsidian** vault; indexes
  are rebuildable caches, never the only copy.
- **Your calendar is local.** Events and dated tasks live in readable
  `JARVIS/Agenda.md`; Google/Android Calendar is optional export, not a dependency.
- **Optional power-ups.** A home PC or Home Assistant can be added, but are never required.

## Architecture at a glance

```
[Button / Tile / Notification / jarvis://listen]
        │  (explicit start only)
        ▼
 AudioRouteManager ──▶ AudioRecord ──▶ VAD ──▶ SpeechToText (offline)
        │                                            │
        │                                            ▼
        │                                     MemoryRepository (Obsidian + Room FTS)
        │                                            │
        │                                            ▼
        │                                     AssistantRouter ──┬─▶ LlmEngine (local, llama.cpp)
        │                                                       ├─▶ RemoteInferenceClient (PC, opt-in)
        │                                                       └─▶ HomeAssistantClient (opt-in)
        ▼                                                              │
 TextToSpeechEngine (offline) ◀───────────── AssistantResponse (validated JSON) ─┘
```

A pure-Kotlin **`ConversationStateMachine`** governs the flow; all engines sit
behind interfaces and are swappable. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Repository layout

| Path | What |
|------|------|
| `core/` | Pure-Kotlin domain (no Android). Compiles & tests on any JVM. |
| `app/` | Android app: Compose UI, audio, foreground service, tile, DI. |
| `server/` | (Phase 8) optional FastAPI companion for a home PC. Not started. |
| `docs/` | Architecture, security, privacy, models, tests, device guide, ADRs. |

## Building

### Domain core (no Android SDK needed)

```bash
cd core
./gradlew test
```

### Android app (needs the Android SDK)

Requirements: JDK 17+, Android SDK (compileSdk 35), an internet connection that
can reach `dl.google.com` (the Android/AndroidX Maven host).

```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> If you are in a restricted network that blocks `dl.google.com`, the Android
> build cannot resolve AGP/AndroidX and will fail — this is an environment
> limitation, not a project one. Build from Android Studio or an unrestricted CI.

## Importing models

No LLM is bundled. Import a compatible `.litertlm` model on-device via the Models
screen. Speech recognition uses Android's installed offline recognizer; TTS lists
only installed non-network voices. See
[`docs/MODELS.md`](docs/MODELS.md) for recommended models by available RAM.

## Guides

- Device setup & tests: [`docs/DEVICE_TEST_HONOR_200.md`](docs/DEVICE_TEST_HONOR_200.md)
- Security & threat model: [`docs/SECURITY.md`](docs/SECURITY.md)
- Privacy model: [`docs/PRIVACY.md`](docs/PRIVACY.md)
- Test plan: [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md)
- Implementation plan & phases: [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md)

## License

See [`LICENSE`](LICENSE). Third-party components and their licenses are tracked in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
