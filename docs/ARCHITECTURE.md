# Architecture

JARVIS Mobile is a single Android application backed by a pure-Kotlin domain
core. This document describes the layers, the key interfaces, the conversation
state machine, and the routing/memory design.

## Layering

```
┌────────────────────────────────────────────────────────────┐
│ UI (Compose, Material 3)  — screens, one ViewModel per feature │
├────────────────────────────────────────────────────────────┤
│ ViewModels — StateFlow, unidirectional; thin Android adapters  │
├────────────────────────────────────────────────────────────┤
│ Domain core (:core, pure Kotlin, no Android)                   │
│   ConversationStateMachine · AssistantRouter · Tool protocol   │
│   ToolRegistry/policies · RetrievalRanker · MarkdownParser     │
│   LogRedactor                                                  │
├────────────────────────────────────────────────────────────┤
│ Engines & repositories (interfaces) — Android impls in :app    │
│   AudioRouteManager · VoiceActivityDetector · SpeechToTextEngine│
│   LlmEngine · TextToSpeechEngine · EmbeddingEngine             │
│   MemoryRepository · VaultRepository · ConversationRepository  │
│   RemoteInferenceClient · HomeAssistantClient · ModelManager   │
│   SecretStore · ConnectivityMonitor · PerformanceMonitor       │
├────────────────────────────────────────────────────────────┤
│ Platform: AudioManager, TextToSpeech, Room, DataStore,        │
│           Keystore, SAF, OkHttp, WorkManager                  │
└────────────────────────────────────────────────────────────┘
```

**Rule:** UI, microphone, model, files and network are never wired together in a
single Activity. The Activity hosts Compose; the ViewModel drives the pure state
machine; engines are injected behind interfaces and are individually swappable.

## Key interfaces (§5)

Defined so each engine has substitutable implementations:

| Interface | Implementations (present → planned) |
|-----------|--------------------------------------|
| `AudioRouteManager` | `AndroidAudioRouteManager` |
| `VoiceActivityDetector` | *(phase 2)* `SherpaVad` |
| `SpeechToTextEngine` | *(phase 2)* `SherpaSpeechToTextEngine`, `AndroidOnDeviceSpeechEngine` |
| `LlmEngine` | *(phase 3)* `LlamaCppLocalEngine`, `FakeLlmEngine`, `RemoteOpenAiCompatibleEngine` |
| `TextToSpeechEngine` | `AndroidOfflineTtsEngine` → `SherpaTtsEngine` |
| `EmbeddingEngine` | *(phase 5+, optional)* |
| `MemoryRepository` | *(phase 5)* `RoomMemoryRepository`, `ObsidianBackedMemoryRepository` |
| `VaultRepository` | *(phase 5)* SAF-based |
| `Tool` / `ToolRegistry` | `ToolRegistry` (core) + builtin tools |
| `AssistantRouter` | `HybridRouter` (core) |
| `RemoteInferenceClient` | *(phase 8)* |
| `HomeAssistantClient` | *(phase 7)* |
| `SecretStore` | *(phase 0+)* Keystore-backed |
| `ModelManager` | *(phase 3)* |
| `ConversationRepository` | *(phase 5)* Room |
| `ConnectivityMonitor` | *(phase 0+)* |
| `PerformanceMonitor` | *(phase 9)* |

The ones marked *(core)* are implemented and unit-tested today in `core/`.

## Conversation state machine (§6)

Implemented in `core/…/state/ConversationStateMachine.kt` as a pure reducer over
events, exposed as a `StateFlow<ConversationState>`.

Happy path:

```
Idle → PreparingAudio → Listening → FinalizingSpeech → Transcribing →
RetrievingMemory → Routing → (ThinkingLocal | ThinkingRemote) →
[AwaitingConfirmation] → [ExecutingTool] → Speaking → FollowUpWindow → Idle
```

Interrupt / error states: `Cancelled`, `PermissionRequired`, `BluetoothUnavailable`,
`ModelUnavailable`, `VaultUnavailable`, `NetworkUnavailable`, `RecoverableError(code)`,
`FatalError(code)`.

Guarantees (all covered by tests):

- Every active state is cancellable → `Cancelled`, and restartable.
- Every operation is expected to be driven with a `Timeout` event; timeouts land
  in a usable state (recoverable error or a sane fallback, e.g. remote→local).
- A new command (`BargeIn`) during `Speaking`/`FollowUpWindow` restarts capture.
- Illegal `(state,event)` pairs are no-ops (no crashes, no illegal transitions).
- After any error the machine returns to a usable state.

The loop is half-duplex, but the visible mic/orb supports barge-in: pressing it
while TTS is speaking stops synthesis and immediately opens the recognizer.

## Activation (§7)

- **7.1 Push-to-talk button** — big button with ready/listening/processing/speaking/error visuals.
- **7.2 Quick Settings tile** — `JarvisTileService` opens an explicit session; never records silently.
- **7.3 Foreground notification** — `ListeningService` shows Parla / Interrompi / Chiudi sessione.
- **7.4 Deep link** — `jarvis://listen` starts an explicit session (usable from launcher/Tasker).
- **7.5 Native Assistant role** — an `ACTION_ASSIST` Activity lets the configured
  MagicOS/Android gesture or button open a visible one-shot listening session.
- **7.6 Wake word** — deliberately not implemented. Any future detector requires
  explicit consent, persistent notification/indicator and a kill switch.

Long-press-volume via Accessibility is intentionally **not** implemented; the
device guide explains binding it externally (Tasker → `jarvis://listen`).

## Audio & AirPods (§8)

`AudioRouteManager` switches to communication mode, prefers a Bluetooth endpoint
that looks like AirPods, uses `setCommunicationDevice()` on API 31+, requests
transient audio focus, and reports the **actual** live endpoint. It falls back to
the phone without crashing and restores the prior route on session end. The
diagnostics screen surfaces input/output, sample rate, channels, Bluetooth state,
focus, and record/playback tests. We never claim the AirPods mic is in use when
Android is actually using the phone mic.

## Router (§13)

`HybridRouter` decides, in order: deterministic command (no LLM) → Home Assistant
intent → complexity/PC offload (privacy-gated) → local fallback. It emits a fully
auditable `RoutingDecision` (target, technical reason, timeout, memory-sharing
level, sensitivity, fallback). It never ships the whole vault anywhere; remote
memory sharing defaults to *selected fragments only* and is capped by the user's
`PrivacyProfile`.

## Memory (§14)

Memory V2 has two local layers. `ConversationMemoryStore` keeps a bounded,
deterministic recap in app-private storage and clears it with a new conversation.
Permanent/sensitive records live in `JARVIS/Memoria.md`: visible Markdown bullets
plus adjacent stable-ID metadata. Direct Obsidian edits preserve IDs and rebuild
topics/people/dates. `MemoryIndex` is an in-memory, rebuildable lexical cache;
only bounded relevant records enter a prompt. Every chat/voice write is
confirmation-gated, sensitive records are marked, and credentials are rejected.

Downloaded reference material is a separate knowledge layer, not personal
memory. Wikipedia ZIM files and manuals are searched locally and contribute only
bounded, source-labelled passages to generation; they are never copied into
`JARVIS/Memoria.md`. See [`LOCAL_KNOWLEDGE.md`](LOCAL_KNOWLEDGE.md).

## Tools (§15)

Typed `ToolRegistry`. Each `Tool` declares a schema, validates arguments, declares
its `ToolPolicy` (READ_ONLY … DESTRUCTIVE), `SensitivityLevel`, network need,
timeout, and returns a structured success/failure. The model can only ever reach
registered tools — never arbitrary classes, Intents, shells, files, or URLs. The
model's `requires_confirmation` hint can only *raise* the requirement, never lower
the policy-mandated one.

Android implementations use only fixed intents and scoped providers: app/settings
allowlists, explicit external-calendar/SMS/call drafts, navigation, media,
confirmed notification reads and selected-vault search. Calls and messages are
never sent directly, and the address book is not a capability. See ADR 0008.

The default personal calendar is independent of Google: structured timed events
and untimed dated tasks live in `JARVIS/Agenda.md`, retain completion/alert state,
and drive the real seven-day dashboard. An external calendar is opened only for
an explicit export request. See ADR 0010.

## Understanding V2

`TurnPlanner` first decomposes a message into bounded requests. Each request is
then classified by the fast model using the compact `intent|confidence` contract.
A score below the execution threshold cannot trigger a tool; deterministic
matching may still prove an explicit command, otherwise the request becomes
conversation and is escalated to the advanced model. Tool arguments always come
from the user's text. Results from earlier clauses are carried as bounded
same-turn context so pronouns and shortened follow-ups remain coherent.

## Persistent local work and notifications

Written requests enter `assistant_tasks` in Room and are executed as unique
WorkManager jobs. The worker exposes phase/progress, promotes long inference to
a visible foreground notification, restores the local model/memory after process
death, and writes task-tagged chat lines idempotently. Completion creates a
private notification that deep-links to the conversation. The UI can cancel or
retry jobs. This path does not capture audio and grants no new model privileges.
If the user explicitly enables spoken background answers, the same visible worker
temporarily declares media playback and uses the selected offline TTS voice.

Agenda notifications use a separate reconciliation layer. Stable entry IDs and
alert rules live in `Agenda.md`; WorkManager jobs are derived, replaceable state.
Reopening/reloading the agenda cancels stale jobs and recreates future ones.
