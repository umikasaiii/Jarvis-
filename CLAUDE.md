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
cd core && ./gradlew test          # pure-Kotlin core suite
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
| 3 | Local LLM + model manager | **Verified on HONOR 200** (build `045bb77`) — engine is **LiteRT-LM** (`.litertlm`, `com.google.ai.edge.litertlm:litertlm-android`) behind `LlmEngine`; a `.litertlm` Gemma imported via the Models screen loads and generates on-device. Migrated off the MediaPipe LLM `.task` path (maintenance-mode API, corrupt-download "Unable to open zip archive"). The migration also lifted the toolchain: Kotlin 2.2.21, KSP 2.2.21-2.0.4, Hilt 2.57.2. **Multi-turn memory:** each fast/advanced engine keeps its own persistent `Conversation` (KV cache); the coordinator detects model switches/reloads and reseeds the destination from the shared bounded transcript so the two slots behave as one assistant. `maxNumTokens=4096`; "Nuova conversazione" clears both. Latest context-sharing changes are pending CI/device verification. |
| 4 | Full TTS + audio focus + follow-up | **Implemented, expanded (pending current CI/device check)** — installed offline voice selection, bounded rate/pitch, audio focus, hands-free follow-up, visible mic/orb barge-in, native Android Assistant-role activation and opt-in spoken worker replies. Background speech is off by default and uses a visible media-playback FGS. No custom always-on hotword. |
| 4b | External local neural voices | **Implemented (pending CI/device check)** — two optional ONNX voices behind the `NeuralTtsEngine` interface, **Kokoro v1.0** and **Piper (VITS)**, selectable in Impostazioni › «Voce JARVIS». Piper takes `<voice>.onnx` + `<voice>.onnx.json` (the config carries the model's own `phoneme_id_map`, sample rate and scales, so its token ids need no reconstruction); Kokoro takes `.onnx` + `voices-v1.0.bin`, which is never offered to Piper. File paths are stored per engine. Status shows «Pronto» only after a real runtime+model init. sherpa-onnx was evaluated and rejected: it is published on neither Maven Central nor a reachable JitPack, and ONNX Runtime already runs both graphs directly. Earlier notes: optional Kokoro v1.0 ONNX voice behind a new `NeuralTtsEngine` interface, selected in Impostazioni › «Voce JARVIS». The user imports `.onnx` + `voices-v1.0.bin` (and optionally `tokens.txt`/`config.json`); files are copied into app-private storage, the session loads lazily and stays warm between replies, and long answers are synthesised sentence by sentence into a streaming `AudioTrack`. Phonemes come from a rule-based Italian G2P in `:core` (no espeak-ng bundled). `HybridTtsEngine` picks neural or Android per utterance, so `SessionCoordinator`, STT and transcription are untouched. Nothing is bundled in the APK and inference never touches the network. |
| 5 | Memory V2 | **Implemented (pending current CI/device check)** — bounded app-private short recap plus stable editable records in `JARVIS/Memoria.md`; Temporary/Permanent/Sensitive types, visible sensitive marker, credential rejection, CRUD screen, bounded retrieval and Obsidian edit resync. Chat/voice writes use `CONFIRMING_WRITE`; no delicate fact is silently promoted to the vault. See ADR 0007. |
| 6 | Controlled Android tools | **Implemented, expanded (pending current CI/device check)** — original clock/battery/timer/alarm/torch/agenda/calculate tools plus app/settings allowlists, explicit external-calendar/call/SMS drafts, navigation, media search/control, confirmed active-notification reads and selected-vault search. Arguments always come from user text. No address book, direct call/send/save, broad package/storage/contact/calendar permission or arbitrary Intent. See ADR 0008. |
| 6b | Personal structured calendar | **Implemented (pending device check)** — `:core` `agenda` package (`AgendaEntry`, `ItalianDateTimeParser`, `Agenda`): a timed item is an appointment and an untimed item is a dated task, never "domani" hidden in prose. `AgendaRepository` stores both in `JARVIS/Agenda.md` (app-private fallback with later vault migration), supports alerts and confirmed completion, and the seven-day dashboard reads the real file. Generic calendar requests save locally; Google/Android Calendar is explicit draft export only. Clock arithmetic stays deterministic via `time_until`. See ADRs 0004 and 0010. |
| 6c | Understanding V3 | **Implemented (pending CI/device check)** — knowledge/conversation messages, including multiple questions, now take one coherent model turn. Deterministic tools run first; only plausible unfamiliar operations invoke the one-line LLM classifier. Low-confidence output cannot execute a tool, and generated role/template continuations are removed. |
| 6d | Persistent response queue | **Implemented (pending CI/device check)** — typed requests are persisted in Room and run by a long-running WorkManager worker with visible progress, real native cancellation, retry and idempotent chat writes. The chat send control becomes Stop while active; a 90-second native watchdog prevents infinite inference. Model load, memory retrieval and generation survive Activity closure/process recreation. A private “response ready” notification opens the chat; preview is opt-in. |
| 6e | Reminder engine | **Verified on HONOR 200** — exact-alarm reminders arrive on time with the app closed. Agenda entries keep stable IDs plus zero/multiple alert rules in human-readable Markdown metadata. Dashboard choices: due time, morning-of, 1/2/3/7 days before, custom time, or none. WorkManager/exact-alarm path persists notifications across app exit/reboot; reconciliation handles edits/deletes and the morning hour is configurable. |
| 6f | Automazioni | **Implemented, expanded (pending CI/device check)** — recurring ("ogni giorno alle 8") and conditional ("quando la batteria…") rules, plus a new one-shot **deferred command**: "alle 11.45 accendi la torcia" becomes a `Trigger.Once` + `Action.Tool` rule instead of firing the torch now. The tool action passes the existing `CommandMatcher` at save *and* fire time and is confined to a headless subset of the command allowlist (`flashlight`, `media_control`); every firing posts a visible notification. Scheduled on the same exact-alarm path as reminders; fires once, then is spent. See ADR 0011. Dashboard agenda block rebuilt as date + weekly strip (active day ringed) + vertical timeline with «In arrivo»/«Completato» badges. |
| 7 | Home Assistant | Not started |
| 8 | PC companion (`server/`) | Not started |
| 9 | Hardening / release | Not started |

**Definition of done for a phase:** the main chain compiles, unit tests pass,
lint is clean, docs/decisions updated. Never leave the main branch uncompilable.

## What is real vs. pending (honesty ledger)

- **Real & tested (JVM):** conversation state machine, hybrid router, tool JSON
  protocol + repair, tool registry/policies, calculate tool, log redactor,
  Markdown/frontmatter parser, retrieval ranker, Italian date/time parser +
  agenda model/formatting, Memory V2 codecs/summarizer and the neural-TTS layer
  (Italian G2P, phoneme vocabulary, NPZ voice-pack reader). → `cd core && ./gradlew test`.
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
- **Real & tested (JVM):** the automation model + codec, including the new
  `Trigger.Once`/`Action.Tool` round-trip through `Automazioni.md`
  (`cd core && ./gradlew test`). The deferred-command parser and its allowlist
  re-check live in `app/` (they need the real `CommandMatcher`) and carry a
  `:app:testDebugUnitTest` suite that only CI can run.
- **Pending on-device verification (Phases 4 → 6e + automations/dashboard):**
  walked through function by function in `docs/DEVICE_TEST_PHASES_4_6E.md`. Do
  not promote any of these from "pending device check" until its section is ticked
  off on the phone.
- **Not implemented yet:** local document/Wikipedia knowledge, Room/FTS vault
  index, HA, PC server, custom wake word, benchmarks, release signing and the
  final instrumented/device acceptance suite.
