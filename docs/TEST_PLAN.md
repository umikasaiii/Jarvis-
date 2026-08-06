# Test Plan

## Unit tests (`:core`, run anywhere)

`cd core && ./gradlew test`. Coverage includes:

| Area | Test file | Cases |
|------|-----------|-------|
| State machine | `ConversationStateMachineTest` | happy path, remote/local routing, cancel, barge-in, confirmation grant/deny, empty transcript, permission/bluetooth/timeout/fatal, illegal transitions, Flow observability |
| Router | `HybridRouterTest` | deterministic bypass, HA reachable/unreachable, PC offload, device-only, sensitive-stays-local, offline fallback, memory-sharing cap, simple-local |
| Tool protocol | `ResponseParserTest` | valid parse, tool calls, fence repair, braces-in-strings, plain-text fallback, empty, unknown keys |
| Tool registry/policy | `ToolRegistryTest` | unknown tool, read-only no-confirm, model can't downgrade confirmation, biometric for home-security, offline network tool, invalid args, calculate correctness/safety/div-by-zero |
| Redaction | `LogRedactorTest` | bearer/JWT/email/IP masking, content placeholder, benign text untouched |
| Markdown | `MarkdownParserTest` | frontmatter/title/tags/links, YAML list tags, heading/filename fallback, alias links, no-frontmatter |
| Retrieval | `RetrievalRankerTest` | relevance ordering, title/tag over body, irrelevant→empty, empty query, limit, recency tie-break |
| Understanding V2 | `UtteranceAnalysisTest` | explicit questions, comma/conjunction decomposition, comparison guard, context carry, complexity fallback |
| Agenda alerts | `AgendaEntryTest`, `ReminderScheduleTest` | legacy parsing, Markdown round trip, multiple alerts, morning/day offsets, untimed guard |

## App JVM tests

`LlmIntentClassifierTest` verifies high-confidence tool execution, low-confidence
blocking/escalation and reasoning routing. `CommandMatcherTest` keeps the real
battery follow-up and statement-vs-request regressions covered.

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

Additional acceptance checks for this release:

1. Send a long written request, immediately switch app and turn the screen off;
   observe processing progress and the private “response ready” notification.
2. Kill the Activity during generation; reopen from the notification and verify
   one user line plus one answer (no duplicate retry lines).
3. Cancel and retry from the Attività JARVIS tab.
4. Ask four tagliando questions in one message and verify four ordered answers;
   ask battery level then “È in carica in questo momento?”.
5. Create an agenda item with no alert; verify “Avviso non impostato”, then add
   morning-of + one-day-before and confirm both survive an app restart.
6. Change the morning setting, edit/remove an agenda entry, and verify stale
   notifications are rescheduled/cancelled.
