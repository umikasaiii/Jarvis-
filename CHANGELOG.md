# Changelog

## Memory V2, controlled Android tools and definitive voice

- Added a bounded structured conversation recap and stable editable
  Temporary/Permanent/Sensitive memory records in Obsidian, with credential
  rejection, CRUD and resynchronization after direct vault edits.
- Added fixed Android tools for supported apps/settings, calendar/call/SMS
  drafts, navigation, media, explicitly confirmed active notifications and
  selected-vault search. No direct calls/messages, arbitrary intents or broad
  storage/package permissions.
- Promoted `Agenda.md` to JARVIS's personal offline calendar: timed events and
  untimed tasks, real seven-day dashboard, confirmed task completion and
  explicit-only export to Google/Android Calendar. Address-book access removed.
- Added installed offline voice selection, speech rate/pitch, visible barge-in,
  native Android Assistant-role activation and opt-in spoken background replies.

## Understanding V3, faster turns and real stop

- Ordinary and multi-question chat now uses one model answer instead of one classification plus one answer per clause.
- Deterministic Android tools run before the optional model classifier; small-model role prefixes and generated user continuations are removed.
- The chat send control becomes a red Stop button while a response is running and forwards cancellation into LiteRT-LM native inference.
- Every native generation has a 90-second watchdog; cancelled/expired conversations are discarded instead of being retried accidentally.
- Assigning the same local model to both slots no longer loads a second copy into RAM.

## Understanding V2, background answers and reminders

- Added multi-request turn planning with confidence-gated local tool routing and automatic advanced-model escalation.
- Added a Room + WorkManager response queue, visible progress/cancel/retry, private ready notifications and idempotent chat recovery.
- Added structured agenda alert rules, dashboard alert selection, configurable morning time and persistent reminder notifications.

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions are pre-1.0.

## [Unreleased]

### Added
- **Domain core (`:core`)** — pure-Kotlin, no Android, **58 passing unit tests**:
  - `ConversationStateMachine` with the full happy path + interrupt/error states,
    cancellation, barge-in, timeouts, and Flow observability.
  - `HybridRouter` + `RoutingDecision` with deterministic-command bypass,
    Home-Assistant intent handling, privacy-gated PC offload, and memory-sharing caps.
  - Tool JSON protocol (`AssistantResponse`, `ToolCall`, `MemoryProposal`) with a
    one-shot repair parser; `ToolRegistry` + typed `ToolPolicy`/`SensitivityLevel`;
    safe `CalculateTool` (hand-written parser, no eval).
  - `RetrievalRanker` (FTS-style: title/tags/body TF/recency/folder) and a
    dependency-free Markdown/frontmatter parser.
  - `LogRedactor` masking tokens/emails/IPs/phones.
- **Android app (`:app`)** — written, pending first SDK build:
  - `AudioRouteManager` + `AndroidAudioRouteManager` (AirPods-aware,
    `setCommunicationDevice`, audio focus, real route reporting).
  - `AndroidOfflineTtsEngine` (Italian, offline-only voices).
  - `ListeningService` foreground service (Parla/Interrompi/Chiudi) + `SessionBus`.
  - `JarvisTileService` Quick Settings tile; `jarvis://listen` deep link.
  - Compose Home push-to-talk screen + `HomeViewModel` driving the core FSM;
    runtime permissions; Hilt DI.
- **Docs** — CLAUDE.md, README, ARCHITECTURE, IMPLEMENTATION_PLAN, SECURITY,
  PRIVACY, MODELS, TEST_PLAN, DEVICE_TEST_HONOR_200, ADRs 0001–0003.
- **Tooling** — version catalog, Gradle composite build, CI workflow, issue/PR
  templates, `.gitignore`, third-party notices.

### Notes
- No models, tokens, or personal audio are included in the repository.
- The Android module has not been compiled in the scaffolding environment (no
  Android SDK; `dl.google.com` blocked) — see `docs/DECISIONS/0003`.
