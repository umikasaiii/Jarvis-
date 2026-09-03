# ADR 0011 — JARVIS Core remote AI engine

## Decision

Android can now delegate a conversational turn to JARVIS Core (the optional PC
companion) over the wire format formalized in `jarvis-protocol/main` v1.0.0.
The contract is treated as fixed: nothing in this change adds a field, enum
value or endpoint jarvis-protocol does not already declare.

- `core/remote/JarvisProtocol.kt` — request/response/stream/health/capabilities
  DTOs, field-for-field from jarvis-protocol's JSON Schemas.
- `core/remote/JarvisCoreClient.kt` — plain-JVM OkHttp client (GET
  `/v1/health`, `/v1/capabilities`, POST `/v1/ai/request`, `/v1/ai/stream`).
  Absorbs jarvis-core's three different real error-body shapes into one
  `CoreResult` sealed type, without changing the protocol.
- `core/remote/AiRouter.kt` — deterministic `LOCAL / REMOTE_FAST / REMOTE_BRAIN`
  decision for one turn. No second LLM call decides routing.
- `core/remote/CoreConnectionState.kt` — the centralized reachability state
  (`DISABLED/CONNECTING/ONLINE/DEGRADED/OFFLINE/ERROR`).
- `app/remote/CoreConnectionRepository.kt` — builds a client from current
  Settings (never a hardcoded IP), caches a health check for 20s, never polls
  on a timer.
- `app/remote/RemoteAiEngine.kt` — consumes Core's real SSE stream
  (start/token/done/error) and returns one assembled final answer, so it
  slots into `SessionCoordinator.chatReply()`'s existing single-final-reply
  pipeline instead of standing up a second, parallel chat surface.

All four live in `:core` except the two Android-Context-dependent
repositories, so the protocol/client/router layer compiles and is
unit-tested on plain JVM (OkHttp MockWebServer), the same guarantee the rest
of `:core` already has — no Android SDK required to verify it.

## Why fallback lives in `chatReply`, not in `HybridRouter`

`core/routing/AssistantRouter.kt` already declares a `RouteTarget.REMOTE_PC`
and `ConversationState.ThinkingRemote` — designed for exactly this. It is not
used here: `HybridRouter` was superseded, unwired, by the much more elaborate
deterministic-command/tool/agenda/automation pipeline `SessionCoordinator`
grew instead (verified: zero references to `HybridRouter`/`AssistantRouter`/
`RoutingContext` outside its own test and `core/routing/AssistantRouter.kt`
itself). Rebuilding that pipeline on top of `HybridRouter` now would risk
regressing tested, working local behavior for no benefit this phase needs.
`AiRouter` only fires once a transcript is already confirmed to be an
ordinary conversational/knowledge turn with no matching tool — a narrower,
later decision than `HybridRouter.route()`'s.

One consequence: the state machine's `Routed(RouteTarget.LOCAL)` dispatch
(`SessionCoordinator`, before `generateAnswer()` runs) still always fires
LOCAL — it is cosmetic/diagnostic today and was not rewired to reflect
`AiTarget`. `ThinkingRemote` and its `NetworkMissing -> ThinkingLocal`
fallback transition remain real, tested, and available for a future phase
that wants the state machine (not just the diagnostic string) to reflect
where a turn actually went.

## Fallback and cancellation

Core disabled, unreachable, degraded (protocol mismatch or
`llmAvailable=false`), or a request/stream that fails mid-turn: falls back
to the local engine within the SAME turn, so the user sees exactly one
reply, never two. A remote failure is never retried remotely inside a turn.
`SessionCoordinator.cancel()`/`cancelTextGeneration()` cancel both the local
router and `RemoteAiEngine`; cancelling the collected `Flow` closes the SSE
connection immediately (verified — see the cancellation test in
`core/src/test/.../JarvisCoreClientTest.kt`).

## Secrets

Core's optional bearer token is the one genuine secret this phase adds. It
is stored in Keystore-backed `EncryptedSharedPreferences`
(`SettingsRepository.coreApiToken`), not in the plain DataStore file that
holds host/port/enabled/timeout — see docs/SECURITY.md §21.

## Not done this phase

Event Bridge (`JarvisEvent`), `/v1/models`, token-by-token streaming display
in the chat UI (the SSE stream is real and tested at the client/engine
level; the UI still receives one assembled final reply, matching how local
replies already render — see `core/remote/RemoteAiEngine.kt`'s doc comment),
Personal Intelligence, RAG, proactivity. See jarvis-protocol/main's own
README for the protocol-level gaps (Event Bridge, the three-shape error
inconsistency) this phase deliberately did not touch.
