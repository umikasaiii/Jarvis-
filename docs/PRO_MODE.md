# Modalità Pro

Global NORMAL/PRO switch plus a local, tool-using "Archivio" knowledge base.
Written against the phase-6 architecture already in the repo; see
`CLAUDE.md`'s phase table for the honesty ledger (compiled vs. verified).

## 1. NORMAL vs. PRO

- **State**: `SettingsRepository.proModeActive` (DataStore boolean, default
  `false`), exposed as `ProModeManager.isActive: Flow<Boolean>`. App-wide,
  persistent, survives process death.
- **Toggle**: Impostazioni › "Modalità Pro" (switch), or voice — "attiva
  modalità pro" / "esci dalla modalità pro" (`core/promode/ProModeCommands.kt`).
  Voice phrases are recognised as a **whole utterance**, not a substring, so
  a sentence that merely mentions "modalità pro" in conversation is never
  misfired as a command (`ProModeCommandsTest`).
- **Indicator**: `ui/components/ProModeBadge.kt`, a small "PRO" pill shown
  only while active — in the Dashboard header and in Impostazioni. Its
  absence already means NORMAL; there is no separate "off" rendering,
  per the "piccolo segnale" request.
- **Routing**: `SessionCoordinator.generateAnswer()` — the single function
  both voice (`processTurn`) and typed chat (`processText`) funnel through
  — intercepts the two system commands (activate/deactivate) before
  anything else runs, then, if PRO is active, hands the transcript straight
  to `ProModeCoordinator.handle()`. NORMAL's `CommandMatcher`/alias/intent
  machinery is never reached in PRO: this is a fork at the top of
  `generateAnswer`, not a scattering of `if (proMode)` checks through the
  existing NORMAL code paths.
- **No cloud fallback**: `ProModeCoordinator` only ever calls `LlmRouter`,
  which only ever reaches a local `LitertLmEngine`. There is no cloud
  client anywhere in the class — "no cloud fallback in PRO" holds by
  construction, not by a runtime check that could be forgotten or bypassed.
  If the local model is unavailable, `handle()` returns a clear Italian
  error and stops; it never tries anything else.

## 2. ProModeManager / ProModeCoordinator

Two classes, one job each (spec §2's "do not scatter checks, centralize"):

- `promode/ProModeManager.kt` — the on/off switch. Owns persistence, the
  voice command parse, and the one side effect a real mode change needs:
  `LlmRouter.resetConversation()`. (`LlmEngine.chat()` is stateful/KV-cached
  and only honours a new system prompt on the first call after a reset —
  without this, PRO's tool-protocol system prompt would never actually take
  effect after switching from NORMAL mid-conversation.)
- `promode/ProModeCoordinator.kt` — the PRO conversation loop itself:
  `STT/testo → here → Local LLM (+ tools) → risposta`. Builds the system
  prompt once (persona + the JSON tool-call protocol + the live tool
  catalog from `ToolRunner.available()`, so a tool registered in
  `ToolsModule` needs no prompt edit to appear here), parses the model's
  JSON via the existing `core/protocol` `ResponseParser`, executes any
  `tool_calls` via the existing `ToolRunner`/`ToolPolicy` machinery
  (identical confirmation gating to NORMAL mode — a `CONFIRMING_WRITE` tool
  still asks before acting), and asks the model for one bounded follow-up
  turn to phrase the final answer from the tool results.

## 3. NORMAL flow (unchanged)

`STT/testo → SessionCoordinator.generateAnswer() → CommandMatcher/alias
match → (tool | LLM classifier | LLM chat) → TTS/UI`, exactly as before.
Nothing in this feature modifies NORMAL's own branches — the PRO check is
a new early return, not a rewrite.

## 4. PRO flow

`STT/testo → SessionCoordinator.generateAnswer() → [system command? → done]
→ ProModeCoordinator.handle() → LlmRouter.chat() (system prompt = persona +
protocol + tool catalog) → ResponseParser → [tool_calls? → ToolRunner (one
round, confirmation-gated) → one follow-up chat() to compose the final
reply] → risposta → TTS/UI`.

## 5. Tools available to the PRO LLM

New, in `app/tools/ArchiveTools.kt`, registered in `di/ToolsModule.kt`
alongside every existing tool (all reachable from NORMAL mode too, since
`ToolRunner` is shared — PRO does not get a private tool set, it is simply
the only mode that reaches them purely through model-chosen JSON instead of
also being reachable via a fixed alias):

| Tool | Policy | Backed by |
|---|---|---|
| `search_memory` | READ_ONLY | `MemoryIndex.retrieveSmart` (Memory V2, unchanged) |
| `search_documents` | READ_ONLY | `DocumentImportManager.documentEvidence` (unchanged) |
| `get_attachment_context` | READ_ONLY | `DocumentImportManager.contextFor` (new method, same pipeline) |
| `create_note` | LOW_RISK_WRITE | `ArchiveRepository.create(NOTE, …)` |
| `read_note` | READ_ONLY | `ArchiveRepository.findByText` — fails closed (asks which) on >1 match |
| `update_note` | LOW_RISK_WRITE | `ArchiveRepository.update` by resolved id |
| `delete_note` | CONFIRMING_WRITE | `ArchiveRepository.deleteByText` — asks before deleting |
| `create_watch_item` | LOW_RISK_WRITE | `ArchiveRepository.create(TO_WATCH, …)` |
| `update_watch_item` | LOW_RISK_WRITE | `ArchiveRepository.update`/`setStatusByText` |

`search_wiki` and the TODO tools (`create_todo`/`update_todo`) are **not**
duplicated here: the existing `SearchKnowledgeTool` and Agenda/task tools
already cover those roles exactly (see §7), and the project's own rule is
to reuse rather than build a second implementation of the same thing.

## 6. Knowledge Base structure

No new storage for content that already had a correct, working home:

| Spec content | Storage | Why not new |
|---|---|---|
| Base knowledge / local "Wikipedia" | `KnowledgeRepository` (existing, SAF folder of `.md`/`.txt`, BM25-chunked) | Already exactly this |
| Personal memory (facts, "ricordati che…") | Memory V2 (`MemoryIndex`/`VaultRepository`, existing) | Already temporary-vs-permanent, already the CONFIRMING_WRITE path |
| Documents (PDF/TXT/MD/DOCX/EPUB/XLSX) + photos (OCR) | `DocumentImportManager`/Room (existing) | Already import → extract → chunk → index |
| Notes / to-watch list | **New**: `archive_items` Room table, `ArchiveRepository` | Genuinely didn't exist before |
| TODO | `AgendaRepository` (existing, phase 6b+ unified tasks) | Already a complete Google-Tasks-style store |

Each source keeps its own identity (`ArchiveItem.toMemoryChunk()` tags the
chunk's `folder` as `note`/`to_watch`; `DocumentChunkEntity` keeps its
document id; `KnowledgeChunk` keeps its file path) while all being rankable
through the same `RetrievalRanker`/`HybridRanker` algorithm — logical
separation, unified search, per spec §6/§9.

### Knowledge Pack directory convention (spec §12)

`KnowledgeRepository` reads a **user-picked SAF folder** (Impostazioni ›
Memoria), not a fixed in-app path — there is nowhere in the app's own
storage to create empty `knowledge/base/` etc. directories that would
actually be read by anything (and empty directories are not something git
tracks in the first place). The convention this prepares instead: when the
user is ready to add the initial Knowledge Pack, point the existing folder
picker at (or organise the picked folder as) a structure with these four
subfolders:

```
knowledge/
  base/        general reference material (encyclopedic entries, etc.)
  personal/    user-authored background info not tied to a single note
  reference/   manuals, specs, cheat sheets
  manuals/     device/app manuals
```

`KnowledgeIndexer` already walks a folder tree recursively, so this
structure indexes correctly with **zero code changes** — the subfolders are
an organisational convention for the user, not something the code
special-cases. Nothing has been placed under any of these names in this
change, per the explicit instruction to leave initial content for later.

## 7. RAG / retrieval pipeline

`file/note → extract → chunk → (lexical BM25 always, + embedding when a
model is imported) → RetrievalRanker/HybridRanker → top-N chunks with
source id/type/title/position/date → LLM`. This is the existing
`core/memory` + `core/document` pipeline, reused verbatim by
`ArchiveRepository.search`/`searchSmart` (mirrors
`MemoryIndex.retrieveSmart` line for line) — no second ranking algorithm
was written. The model is never handed a whole document or the whole
archive: `search_memory`/`search_documents`/`ArchiveTools` all cap results
(4-5 chunks), consistent with spec §6's "not the whole archive on every
request."

## 8. Photos / images as memory

Already-existing `DocumentImportManager` covers most of spec §8 today:
file/URI, date, display name, tags (`cat:` prefix groups photos with notes
in the archive view), and OCR text (opt-in, `settings.docOcrImages`) are
all stored per image via the same `DocumentEntity`/`DocumentChunkEntity`
Room schema documents use. **Not implemented, deliberately, per the spec's
own "not necessary now"**: a visual/content embedding and a locally
generated caption. `EmbeddingRepository`'s interface is text-only; nothing
in this change narrows it, so a future image encoder can be added behind
the same seam without touching `ArchiveRepository`, `DocumentImportManager`
or any tool.

## 9. Conversational memory (spec §10 — already correct, unchanged)

Nothing here was touched: `ConversationMemoryStore` already keeps the
per-session transcript separate from `VaultRepository`'s persisted
`MemoryRecord`s, and a permanent memory write already only happens via the
`remember`/`CONFIRMING_WRITE` path — never automatically from ordinary
conversation. PRO mode reuses this distinction unchanged.

## 10. Security (spec §11)

- Every archive/document/memory access from PRO goes through
  `ArchiveRepository`/`DocumentImportManager`/`MemoryIndex` — the LLM never
  gets filesystem or SQL access; it only ever emits a JSON tool call that
  Kotlin validates and executes.
  Destructive/ambiguous tools keep `ToolPolicy.CONFIRMING_WRITE` /
  fail-closed-on-ambiguity behaviour, same as NORMAL mode.
- Nothing new is sent to any external service; PRO mode has no network
  client at all.
- No content of a note/document is logged; `ProModeCoordinator`'s only log
  line is a tool-count integer (`pro_mode_prompt_built tools=N`), never
  spoken text or tool arguments.

## 11. Tests

`:core` (all passing, `cd core && ./gradlew test`):

- `ProModeCommandsTest` — activation/deactivation phrase recognition,
  and that ordinary conversation mentioning "modalità pro" is not
  misfired as a command (covers spec test items 1 (implicitly, NORMAL is
  the default absent a command) and 5).
- `ArchiveItemTest` — blank-title rejection, `toMemoryChunk()` content for
  both NOTE and TO_WATCH (supports test items 6-11's data layer).

**Not covered by automated tests at the app layer** — this project has
only 5 pre-existing app-level unit tests, none touching Room, Hilt, or a
mocked `LlmRouter`; that limitation predates this feature. The remaining
spec test items (2-4, 6-17: bypass of intent routing, tool execution
round-trips, Wiki+notes combined retrieval, document import+search,
restart persistence, chunked retrieval) are true **by construction** —
`SessionCoordinator`'s PRO branch has no code path back into
`CommandMatcher`; `ArchiveRepository`/Room persistence is the same pattern
already relied on elsewhere in the app — but are not exercised by a CI
test today. Closing this gap would need Robolectric or an instrumented
test harness, neither of which exists in this repository yet.

## 12. Known gaps / prepared-but-not-active

- Image content embedding / local captioning (§8) — interface seam ready
  (`EmbeddingRepository`), no model wired.
- Knowledge Pack `base/personal/reference/manuals` folders — convention
  documented above, deliberately left empty of content.
- The spec names a `search_wiki` tool; the existing, reused implementation
  is registered as `search_knowledge` (`tools/KnowledgeTools.kt`). No
  second tool was added under the `search_wiki` name — that would have
  been exactly the duplicate-implementation the spec explicitly says not
  to create. The PRO system prompt's tool catalog is built live from
  `ToolRunner.available()`, so the model always sees the real name.
- App-level automated tests for the PRO path (see §11).
