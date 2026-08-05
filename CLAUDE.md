# CLAUDE.md — JARVIS Mobile

Working notes for AI/coding agents (and humans) in this repository. Keep it
current: goals, constraints, build commands, conventions, and **phase state**.

## Product goal

An **offline-first** Italian personal assistant for Android, tuned for an
HONOR 200 (MagicOS, Snapdragon 7 Gen 3) with AirPods over Bluetooth. Core loop:
button/Tile → route mic to AirPods → capture → local VAD → offline STT → optional
memory retrieval → router → local LLM (or PC/Home Assistant when authorized) →
offline TTS → follow-up window. **Everything essential must work in airplane mode.**

## Non-negotiable constraints

See `docs/PRIVACY.md` and `docs/SECURITY.md` for the full list. Highlights:

- Offline-first; privacy by default; no mandatory account/cloud.
- No hidden recording; no always-on background mic in the default config.
- Any background mic use is a foreground service + persistent notification.
- No Accessibility abuse, no `MANAGE_EXTERNAL_STORAGE`, no arbitrary shell from the model.
- No secrets/personal content in logs. Secrets only in Android Keystore.
- The Obsidian vault is the human-readable source of truth; vector DBs are rebuildable caches.
- Every engine is swappable behind an interface.

## Repository layout

```
core/     Standalone pure-Kotlin domain (NO Android). Compiles & unit-tests on any JVM.
          state machine · router · tool protocol · policies · retrieval ranking · redaction
app/      Android application (Compose, Hilt, Room, audio, services). Consumes :core
          as a composite (included) build.
server/   (Phase 8) optional FastAPI companion for a home PC. Not started yet.
docs/     Architecture, plans, security, privacy, models, tests, device guide, decisions.
```

## Build & test commands

**Domain core (works anywhere, incl. CI without the Android SDK):**
```bash
cd core && ./gradlew test          # 58 unit tests, all green
```

**Android app (requires the Android SDK + AGP; see "Environment" below):**
```bash
./gradlew :app:assembleDebug       # build debug APK
./gradlew :app:testDebugUnitTest   # JVM unit tests
./gradlew :app:lint                # Android lint
./gradlew :app:connectedDebugAndroidTest   # instrumented/Compose tests (device/emulator)
```
Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Environment note (important for agents)

The container used to scaffold this project has **JDK 21, Gradle 8.14.3, CMake,
Python** but **no Android SDK/NDK**, and its egress policy **blocks
`dl.google.com`** — the host that serves the Android SDK, the Android Gradle
Plugin and all AndroidX/Compose/Material3 artifacts. Consequences:

- The `core/` module **is** compiled and tested here (Maven Central is reachable).
- The `app/` module **cannot** be compiled here. It is written to build in Android
  Studio / any environment with SDK access. Do not claim it compiles until it has
  been built somewhere with the SDK.
- Model files (GGUF, sherpa-onnx) and Hugging Face are also blocked/here-absent;
  models are imported by the user on-device (see `docs/MODELS.md`).

## Conventions

- Kotlin, Compose, Material 3, Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`).
- Unidirectional data flow: `ViewModel` → `StateFlow` → Compose. Domain logic lives in `:core`.
- DI with Hilt. Room for structured data, DataStore for prefs, Keystore for secrets.
- Interfaces first (`AudioRouteManager`, `SpeechToTextEngine`, `LlmEngine`, …); Android
  impls bound in `di/`. Fakes for tests.
- Italian UI strings in `res/values/`. Technical logs only, always redacted (`LogRedactor`).
- Never commit models, tokens, audio, or personal notes.

## Phase state

| Phase | Scope | Status |
|------|-------|--------|
| 0 | Foundations: project, DI, Compose, docs, CI | **Done (build-verified)** — core 71 tests green; Android app compiles + debug APK produced by CI (run #2, `f8e94dd`) |
| 1 | Audio loop: button/Tile/service/routing → fixed TTS in AirPods | **Verified on HONOR 200** — real AudioRecord capture confirmed on-device (build `7342e6a`); press → record → fixed TTS reply. No STT/LLM by design. Root causes fixed on the way: stable debug signing (updates now install in place) and a MagicOS foreground-service/audio-focus conflict (orb now uses the same minimal capture path as the working diagnostics test). |
| 2 | STT (offline) | **Verified on HONOR 200** (build `8ee7275`) — offline Android on-device recognizer transcribes Italian; Home shows partial + transcript, Diagnostics has an STT test. sherpa-onnx (bundled, model-import) can replace it behind `SpeechToTextEngine`. VAD via recognizer end-of-speech. |
| 3 | Local LLM + model manager | **Verified on HONOR 200** (build `045bb77`) — engine is **LiteRT-LM** (`.litertlm`, `com.google.ai.edge.litertlm:litertlm-android`) behind `LlmEngine`; a `.litertlm` Gemma imported via the Models screen loads and generates on-device. Migrated off the MediaPipe LLM `.task` path (maintenance-mode API, corrupt-download "Unable to open zip archive"). The migration also lifted the toolchain: Kotlin 2.2.21, KSP 2.2.21-2.0.4, Hilt 2.57.2. **Multi-turn memory:** `LitertLmEngine` keeps ONE persistent `Conversation` (KV cache) reused across turns/presses so the model remembers the chat; `maxNumTokens=4096` context; `LlmEngine.chat()`/`resetConversation()`; "Nuova conversazione" clears it. |
| 4 | Full TTS + audio focus + follow-up | **Implemented (pending device check)** — hands-free loop in `SessionCoordinator.runTurn`: after speaking, the mic re-opens for a follow-up window (recognizer silence timeout) and loops until silence / follow-up disabled / `MAX_FOLLOW_UPS`. TTS holds TRANSIENT audio focus only while speaking (music ducks/pauses, stops on focus loss); focus is never held around listening (MagicOS mic fix preserved). Toggle in Settings (`followUpEnabled`, default on). |
| 5 | Obsidian memory (SAF, retrieval, write) | **Implemented (pending device check)** — read+write vault memory loop. `VaultRepository` (SAF `OpenDocumentTree`, persisted read+write permission) reads `.md` and appends to `JARVIS/Memoria.md`; `MemoryIndex` parses (core `MarkdownParser`), chunks by heading, ranks (core `RetrievalRanker`) in memory; retrieval injected as grounding context in `SessionCoordinator.generateAnswer`. "ricorda … / prendi nota …" (voice or text) saves a note and reindexes. Memory screen picks/reindexes/disconnects the vault. Room/FTS on-disk index still pending (optimization; in-memory index rebuilt at launch is fine for a personal vault). |
| 6 | Tool system | **Implemented (pending device check)** — Android tools wired to the core registry: `get_time`, `battery_status`, `set_timer`, `set_alarm`, `flashlight`, `remember` (vault), plus core `calculate`. `CommandMatcher` maps Italian phrases → `ToolCall` **deterministically before the LLM** (reliable with a small model, works with no model loaded); `ToolRunner` resolves via `ToolRegistry`, enforcing the tool's own policy/timeout and asking spoken confirmation for anything above LOW_RISK_WRITE. Commands tab lists the real registry with example phrases. |
| 7 | Home Assistant | Not started |
| 8 | PC companion (`server/`) | Not started |
| 9 | Hardening / release | Not started |

**Definition of done for a phase:** the main chain compiles, unit tests pass,
lint is clean, docs/decisions updated. Never leave the main branch uncompilable.

## What is real vs. pending (honesty ledger)

- **Real & tested (JVM):** conversation state machine, hybrid router, tool JSON
  protocol + repair, tool registry/policies, calculate tool, log redactor,
  Markdown/frontmatter parser, retrieval ranker. → `cd core && ./gradlew test`.
- **Compiled + packaged by CI (GitHub Actions, Android SDK):** all of `app/` —
  audio route manager, real AudioRecord capture, offline TTS, listening foreground
  service, QS tile, Compose Home + Diagnostics, permissions, DI wiring. A debug APK
  is produced (`docs/ANDROID_BUILD_AUDIT.md`), signed with a committed stable debug
  key so updates install in place, and published to the `latest-debug` GitHub Release.
- **Verified on-device (HONOR 200, build `7342e6a`):** microphone capture on the
  main push-to-talk control (the Home orb), after fixing a MagicOS
  foreground-service/audio-focus conflict — the orb now uses the same minimal
  capture path as the Diagnostics "Test microfono".
- **Verified on-device (HONOR 200, build `045bb77`):** offline STT (Phase 2) plus
  the **LiteRT-LM** local LLM (Phase 3) — a user-imported `.litertlm` Gemma loads
  and generates a reply fully on-device, no network.
- **Not implemented yet:** VAD tuning, Room/SAF vault, HA, PC server,
  benchmarks, release signing, instrumented tests.
