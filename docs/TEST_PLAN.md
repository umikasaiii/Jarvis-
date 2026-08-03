# Test Plan

## Unit tests (`:core`, run anywhere)

`cd core && ./gradlew test` — **58 tests, all green**. Coverage:

| Area | Test file | Cases |
|------|-----------|-------|
| State machine | `ConversationStateMachineTest` | happy path, remote/local routing, cancel, barge-in, confirmation grant/deny, empty transcript, permission/bluetooth/timeout/fatal, illegal transitions, Flow observability |
| Router | `HybridRouterTest` | deterministic bypass, HA reachable/unreachable, PC offload, device-only, sensitive-stays-local, offline fallback, memory-sharing cap, simple-local |
| Tool protocol | `ResponseParserTest` | valid parse, tool calls, fence repair, braces-in-strings, plain-text fallback, empty, unknown keys |
| Tool registry/policy | `ToolRegistryTest` | unknown tool, read-only no-confirm, model can't downgrade confirmation, biometric for home-security, offline network tool, invalid args, calculate correctness/safety/div-by-zero |
| Redaction | `LogRedactorTest` | bearer/JWT/email/IP masking, content placeholder, benign text untouched |
| Markdown | `MarkdownParserTest` | frontmatter/title/tags/links, YAML list tags, heading/filename fallback, alias links, no-frontmatter |
| Retrieval | `RetrievalRankerTest` | relevance ordering, title/tag over body, irrelevant→empty, empty query, limit, recency tie-break |

## Planned unit tests (`:app`, JVM)

Confirmations, security policy gating, memory temporary/expiry, action expiry,
local fallback, model selection, error handling.

## Integration tests (fake engines)

`audio → STT → retrieval → LLM → TTS` with `FakeLlmEngine` and fakes for each
engine, asserting the state-machine sequence and that no network is touched offline.

## HTTP tests (MockWebServer)

Home Assistant and PC server: happy path, wrong token, timeout, disconnect,
malformed response, interrupted stream.

## Compose UI tests

Critical flows: press-to-talk visual states; permission-denied path; follow-up
window; diagnostics screen shows the real route.

## Manual device tests

See `docs/DEVICE_TEST_HONOR_200.md` for the full 20-step HONOR 200 checklist and
the acceptance scenarios A–F from the product spec.
