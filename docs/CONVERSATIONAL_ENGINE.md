# Conversational AI engine ("Motore JARVIS")

See ADR 0016 for the routing decision. This doc is the implementation
reference: architecture, files, what's real vs. simplified, and how to try it.

## Flow

```
Voice/Text Input
      |
      v
SessionCoordinator.generateAnswer(transcript)
      |
      v
JarvisEngineRouter.route(transcript, classic, conversational)
      |
      +-- Motore = Classico (default) ----------------> ClassicJarvisEngine
      |                                                   (= today's generateAnswer()
      |                                                    body, unmoved — includes
      |                                                    Modalità Pro's own gate)
      |
      +-- Motore = Conversazionale AI
              |
              +-- model not loaded --------------------> ClassicJarvisEngine (fallback)
              |
              +-- model loaded ------------------------> ConversationalJarvisEngine
                       |
                       v
                 FastPathRouter (CommandMatcher, accelerator only — a miss
                 always falls through, never blocks a free-form request)
                       |  miss
                       v
                 ContextAssembler (pending-task snapshot + bounded MemoryEngine
                 retrieval, capped by jarvis_context_budget_chars)
                       |
                       v
                 JarvisBrain (LlmRouter.chat + ResponseParser -> BrainReply)
                       |
                       v
                 ToolRouter (ToolCallBudget-capped wrapper over ToolRunner)  <---+
                       |                                                        |
                       +-- more tool calls needed -------------------------------+
                       |
                       v
                 ConversationManager.onToolExecuted (pending-task tracking)
                       |
                       v
                 spoken reply
```

`Shared Tool Layer` = `ToolRegistry`/`ToolRunner` (`core/tools`, `app/tools`),
unchanged, used identically by both engines and by Modalità Pro.

## New components

| Component | File | Role |
|---|---|---|
| `JarvisEngineMode` | `core/engine/JarvisEngineMode.kt` | CLASSICO / CONVERSAZIONALE / IBRIDA (reserved) |
| `ReasoningMode` | `core/engine/ReasoningMode.kt` | FAST / AUTO / DEEP -> `LlmRouter.ModelSlot` |
| `MemoryEntry`/`MemoryTier` | `core/memory/MemoryEntry.kt` | Normalised read-model for all four memory tiers |
| `EngineTurnDiagnostics` | `core/engine/EngineTurnDiagnostics.kt` | Per-turn telemetry, counts/booleans only |
| `BrainEvent`/`SentenceStream` | `core/engine/BrainEvent.kt` | Post-hoc sentence-chunked "streaming" |
| `BrainReply` | `core/engine/BrainReply.kt` | Ready(response, parsedCleanly) / Unavailable |
| `ToolCallBudget` | `core/engine/ToolCallBudget.kt` | Per-turn tool-call cap (pure, tested) |
| `JarvisEngine` | `app/engine/JarvisEngine.kt` | Shared `handle(transcript): String` contract |
| `JarvisEngineRouter` | `app/engine/JarvisEngineRouter.kt` | The one switch + the model-unavailable fallback |
| `ClassicJarvisEngine` | `app/engine/ClassicJarvisEngine.kt` | Thin wrapper over `SessionCoordinator.classicAnswer` |
| `ConversationalJarvisEngine` | `app/engine/ConversationalJarvisEngine.kt` | The new orchestrator |
| `JarvisBrain` | `app/engine/JarvisBrain.kt` | One structured-output model turn |
| `ContextAssembler` | `app/engine/ContextAssembler.kt` | Bounded prompt context |
| `MemoryEngine` | `app/engine/MemoryEngine.kt` | Four-tier facade (see below) |
| `ToolRouter` | `app/engine/ToolRouter.kt` | Budget-capped wrapper over `ToolRunner` |
| `FastPathRouter` | `app/engine/FastPathRouter.kt` | `CommandMatcher` accelerator |
| `ConversationManager` | `app/engine/ConversationManager.kt` | Cross-turn pending-task state |
| `ConversationalMemoryEntity`/`Dao` | `app/engine/memory/ConversationalMemoryEntity.kt` | Episodic tier, Room |
| `EngineMemoryMigrations` | `app/engine/memory/EngineMemoryMigrations.kt` | Migration 6->7 |

## Memory tiers (spec §6) — a facade, not a fourth store

| Tier | Backing store | Notes |
|---|---|---|
| WORKING | `ConversationMemoryStore` (existing) | Bounded, conversation-scoped, cleared by "Nuova conversazione" |
| EPISODIC | new `conversational_memory` Room table | The one genuinely new store — pending-task snapshots, cross-session |
| SEMANTIC | `MemoryIndex.retrieveSmart` (existing, vault-backed) | Lexical+embedding, fail-closed to lexical |
| USER | same as SEMANTIC | **Honest gap**: today's `MemoryRecord` schema has no reliable signal distinguishing "about the user" from a general fact — everything vault-backed surfaces as SEMANTIC. `USER` exists in the type for a future distinction. |

`MemoryEngine.retrieve()` never sends the whole vault/history to the model —
always a bounded top-N (`jarvis_memory_topn`), and a no-op when
`jarvis_memory_enabled` is off. Deliberately does **not** touch
`KnowledgeRepository` (Wiki/Documents) — that stays Modalità Pro's exclusive
role inside the Classic engine, per the explicit constraint that Pro's
Wiki/Knowledge-querying function is not reincorporated here.

## Settings (Impostazioni › Motore JARVIS)

`SettingsRepository`: `jarvis_engine_mode`, `jarvis_reasoning_mode`,
`jarvis_memory_enabled`, `jarvis_streaming_enabled`, `jarvis_tool_loop_cap`
(default 4), `jarvis_memory_topn` (default 6), `jarvis_context_budget_chars`
(default 6000), `jarvis_fastpath_enabled`, `jarvis_auto_context_enabled`,
`jarvis_conversational_model_slot`, `jarvis_engine_diagnostics_verbose`.

UI: a single "Motore conversazionale AI" switch (Classico is the only other
value the UI offers, so a boolean is the honest mapping — see
`EngineSettingsSection` in `SettingsScreen.kt`), sub-settings visible only
when the switch is on, and a "Cancella memoria conversazionale" two-tap
button (same confirm-on-second-tap pattern as `MemoryScreen`'s delete) that
wipes only the Episodic tier — the vault's permanent memories are untouched.

## Diagnostics

`DiagnosticsViewModel.engineDiagnostics` exposes
`ConversationalJarvisEngine.diagnostics` (bounded to the last 20 turns) into
a debug-only card in `DiagnosticsScreen` (`BuildConfig.DEBUG`, same gate as
the Supertonic A/B panel). Shows fast-path hit, fallback, parse-error,
memories retrieved, tool counts, time-to-first-emit, total turn time — never
the reply text or tool arguments (`LogRedactor` discipline extended to a
type that structurally cannot carry personal content).

**Honest limitation**: only the conversational engine populates this feed —
Classic mode has no equivalent diagnostics wrapper yet, so the card is empty
until at least one conversational turn has run.

## What's real vs. simplified — honesty ledger

- **Real**: the routing gate, `ClassicJarvisEngine`'s byte-for-byte wrapping,
  Modalità Pro's continued Classic-only reachability, the tool-call budget,
  the multi-round brain/tool loop (bounded, `MAX_BRAIN_ROUNDS = 6`), the
  fissativo multi-turn example (`add_reminder` -> `move_agenda` ->
  `update_agenda_notes`, all via the tracked pending-task id), the
  fallback-to-Classic-on-model-unavailable / canned-message-on-other-failure
  split, the Room migration for Episodic memory.
- **Real but simplified, documented above**: memory tiers are a facade over
  3.5 stores, not 4; SEMANTIC/USER share one store.
- **Honestly not real streaming**: `BrainEvent`/`SentenceStream` chunk an
  already-complete reply. `LlmEngine`/`LitertLmEngine` have no token
  callback API. The `Flow`-shaped contract is ready for one if it's ever
  added.
- **Deferred, interface-ready only**: Ibrida mode (`JarvisEngineMode.IBRIDA`
  exists, no UI selects it, router falls back to Classico), voice barge-in
  (no new state added to `ConversationStateMachine`), entity-relation-entity
  memory triples (`MemoryEntry.entityTriples`, always empty), embeddings
  beyond what `EmbeddingRepository`/`MemoryIndex` already do.
- **Build status**: `:core` primitives are compiled and tested here
  (`cd core && ./gradlew test`). `app/` cannot be compiled in this
  environment (no Android SDK) — every file above was written against the
  real, read source (not guessed), but is CI/device-build-pending like every
  other `app/`-layer phase in this project. See CLAUDE.md's phase table.

## Try it from the app

1. Impostazioni › Motore JARVIS › attiva "Motore conversazionale AI" (richiede
   un modello locale caricato in Impostazioni › Modelli).
2. Di' o scrivi una richiesta libera, es. "Qui è troppo buio" — non è un
   comando riconosciuto da CommandMatcher, quindi passa al modello.
3. Prova la sequenza multi-turno: "Ricordami domani di comprare il
   fissativo" → "Anzi, alle 18" → "E aggiungi anche la carta vetrata" — le
   tre frasi devono finire sullo stesso promemoria (verificabile in Agenda).
4. Impostazioni › Motore JARVIS › "Cancella memoria conversazionale" per
   svuotare solo la memoria episodica.
5. Diagnostica (build debug) mostra il pannello "Motore conversazionale" con
   la telemetria dell'ultimo turno.
