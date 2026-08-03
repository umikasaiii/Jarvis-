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
| 1 | Audio loop: button/Tile/service/routing → fixed TTS in AirPods | **Compiles + APK built in CI** — real AudioRecord capture, coordinator, diagnostics; **on-device HONOR 200 test still pending** (docs/PHASE1_HONOR_TEST_CHECKLIST.md) |
| 2 | STT + VAD (sherpa-onnx) | Not started |
| 3 | Local LLM (llama.cpp) + model manager | Not started |
| 4 | Full TTS + audio focus + follow-up | Partially scaffolded (TTS engine + follow-up in ViewModel) |
| 5 | Obsidian memory (SAF, FTS, retrieval) | Ranking + parser done in `:core`; SAF/Room pending |
| 6 | Tool system | Protocol, registry, policies done in `:core`; Android tools pending |
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
  is produced (`docs/ANDROID_BUILD_AUDIT.md`). On-device behavior is **not** yet
  verified (`docs/PHASE1_HONOR_TEST_CHECKLIST.md`).
- **Not implemented yet:** STT/VAD, llama.cpp, Room/SAF vault, HA, PC server,
  benchmarks, release signing, instrumented tests.
