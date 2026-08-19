# ADR 0016: Conversational AI engine as a router in front of Classic

## Status

Accepted. Core primitives CI-verified (`:core`); `app/` layer written and
reviewed against the real source, pending a CI/device build (see
`docs/CONVERSATIONAL_ENGINE.md` for the full honesty ledger).

## Context

JARVIS's entire turn-dispatch logic lived in one place —
`SessionCoordinator.generateAnswer()` — a deterministic command/intent
pipeline (`CommandMatcher`, `AgendaIntentRouter`, `TurnPlanner`,
`LlmIntentClassifier`) with a single early-return gate for Modalità Pro
(`ProModeManager.currentlyActive()`). The user asked for a second, LLM-first
orchestrator — free-form understanding, multi-turn state, retrieval-backed
memory — selectable in Settings, without duplicating any existing
calendar/reminder/navigation/tool logic, and without changing Modalità Pro's
behavior or moving it out of the existing ("Classic") path.

## Decision

Add `JarvisEngineMode` (`CLASSICO` default / `CONVERSAZIONALE` /
`IBRIDA` reserved) and route on it with the exact same one-`if` pattern
`ProModeManager`/`ProModeCoordinator` already use for NORMAL/PRO, one axis
higher:

```
SessionCoordinator.generateAnswer(transcript)
  -> JarvisEngineRouter.route(transcript, classic, conversational)
       CLASSICO        -> classic.handle(transcript)
       CONVERSAZIONALE  -> conversational.handle(transcript)   (if model loaded)
                        -> classic.handle(transcript)            (if not — the one
                                                                    fallback-to-Classic
                                                                    case this router owns)
       IBRIDA          -> classic.handle(transcript)   (no UI path selects this yet)
```

`classic.handle()` is `ClassicJarvisEngine { classicAnswer(it) }` —
`classicAnswer()` is `generateAnswer()`'s entire previous body, moved to a
new name with **zero lines changed**. Modalità Pro's own gate
(`proModeManager.currentlyActive()`) lives inside that body, unmoved — so it
stays reachable only when Motore = Classico, exactly as required, without a
single line of `ProModeManager`/`ProModeCoordinator` being touched.

`ConversationalJarvisEngine` is new: `FastPathRouter` (reuses
`CommandMatcher`, accelerator-only — a miss always falls through to the
model) → `ContextAssembler` (pending-task snapshot + bounded memory
retrieval) → `JarvisBrain` (one `LlmRouter.chat()` call, parsed via the
existing `ResponseParser`/`AssistantResponse` contract — no new protocol) →
`ToolRouter` (a per-turn `ToolCallBudget` wrapped around the existing
`ToolRunner.run()`, unchanged validation/confirmation) → `ConversationManager`
(cross-turn pending-task state, the "anzi, alle 18" case). Every tool call
either engine ever makes goes through the same `ToolRegistry`/`ToolRunner`.

## Consequences

- Classic mode's behavior is unchanged by construction, not by testing —
  the moved method's body was never edited.
- Modalità Pro is unreachable from the conversational engine by construction
  — `ConversationalJarvisEngine` never imports `ProModeManager`/`ProModeCoordinator`.
- A new tool (`update_agenda_notes`) and two small additive fields
  (`ToolOutcome.Done.raw`, `id` in `add_reminder`/`add_task` output) were
  needed to let `ConversationManager` track a just-created entry — both
  land in the shared tool layer, so Classic mode gains the same capability
  rather than the engines diverging.
- No real token streaming exists in the LLM stack; "streaming" here is an
  honest post-hoc sentence chunk of an already-complete reply
  (`BrainEvent`/`SentenceStream`, built on the existing `SpeechShaper`).
- Four memory tiers are a read-model over three existing stores plus one new
  Room table (`conversational_memory`, migration 6→7) — not four physical
  stores. See `docs/CONVERSATIONAL_ENGINE.md` for the full mapping and honest
  gaps (Semantic vs. User isn't a real distinction in today's schema; true
  token streaming and Ibrida mode are deferred).

## Alternatives considered

- **Physically extracting Classic's ~1500 lines into a standalone class.**
  Rejected: `app/` cannot be compiled in this development environment (no
  Android SDK), so a large mechanical move touching dozens of private fields
  could not be verified before a device build. Wrapping the existing method
  behind a one-line lambda (`ClassicJarvisEngine`) gets the same "Classic
  is byte-for-byte identical" guarantee with far less blast radius.
- **A shared `JarvisBrain`-owned tool-execution loop.** Rejected in favor of
  keeping the loop in `ConversationalJarvisEngine` and `JarvisBrain` strictly
  reasoning-only (never touching `ToolRunner`), matching the requirement to
  keep reasoning and execution on separate call stacks.
