# Personal Archive

Extends `docs/PRO_MODE.md`'s Personal Archive (notes/to-watch) into the
three-layer model the user asked for — KNOWLEDGE / PERSONAL ARCHIVE /
PERSONAL DOCUMENTS — with generic shopping/custom lists, a federated search,
and NORMAL-mode voice/text routing through the same repositories Modalità
Pro's tools use. Read `docs/PRO_MODE.md` first; this document only covers
what changed or was added on top of it.

## 1. Three layers, kept separate

| Layer | Storage | Read-only from the model's POV? |
|---|---|---|
| KNOWLEDGE | `KnowledgeRepository` (existing "local Wikipedia"/guides) | Yes — never written by a tool |
| PERSONAL ARCHIVE | `ArchiveRepository` (notes/to-watch) + `ArchiveListRepository` (shopping/custom lists) | No — full CRUD |
| PERSONAL DOCUMENTS | `DocumentImportManager` (PDF/TXT/MD/images+OCR) | Read-only via tools; import/delete stay UI-driven |

`search_knowledge`/`search_archive`/`search_documents` are three distinct
tools precisely so the model (and NORMAL mode's disambiguation regexes,
§28 of the alias table) can pick the right layer instead of one search
silently blending sources the user asked to keep separate. `PersonalArchiveSearch`
is the one facade that federates the Archive layer's own two stores (notes
and lists) for `search_archive` — it does **not** reach into Knowledge or
Documents, which keep their own dedicated tools.

## 2. Database schema

Three new Room tables (migration `5→6`, non-destructive — see
`ArchiveMigrations.MIGRATION_5_6`), alongside the existing `archive_items`
(`4→5`, notes/to-watch, from `docs/PRO_MODE.md`):

- **`archive_lists`**: `id, name, type(SHOPPING|CUSTOM), createdAt, updatedAt`.
  Exactly one `SHOPPING` row is ever created (`ArchiveListRepository.shoppingList()`,
  auto-provisioned on first use, never duplicated); any number of `CUSTOM`
  rows.
- **`archive_list_items`**: `id, listId, title, description, status(OPEN|DONE),
  quantity, priority, dueDate, notes, link, tags, order, createdAt, updatedAt`
  — one generic shape for a shopping entry (quantity) or a custom-list entry
  (a spare part, a gift idea), per the explicit "don't make a table per list
  type" instruction.
- **`archive_links`**: `id, fromType, fromId, toType, toId, createdAt` — a
  typed edge between any two archive entities ("nota Assicurazione moto" ↔
  documento `assicurazione_moto.pdf`). `core/archive/ArchiveLink.kt` holds
  the pure model (`ArchiveRef(type, id)`, self-link rejected by `init`).
  **Prepared but not yet wired to a UI or a tool** — the schema and DAO exist
  and are tested at the model layer; no button creates a link yet. Honest
  gap, not a silent omission: linking wasn't part of any of the 17 essential
  test scenarios or the tool list the spec named, and there was no natural
  voice phrasing given for it either.

TODO stays on `AgendaRepository` (existing, phase 6b+ unified Google-Tasks-
style store) — not migrated into `archive_lists`, even though the spec's
§4 "Tipi iniziali" lists TODO alongside Shopping/To-watch/Note. Reusing
Agenda's already-complete due-date/list/star/sub-task model was the explicit
instruction ("se una tecnologia esiste già, riusala") and rebuilding TODO
under `ArchiveList` would have been a second, competing task system — the
exact duplication the spec repeatedly forbids.

## 3. What's genuinely new vs. reused

| Spec item | Reused | New |
|---|---|---|
| Notes | `ArchiveRepository` (from `docs/PRO_MODE.md`) | — |
| To-watch | `ArchiveRepository` | — |
| Shopping list | — | `ArchiveListRepository`, singleton `SHOPPING` list |
| Custom lists ("Ricambi moto") | — | `ArchiveListRepository`, `CUSTOM` lists |
| TODO | `AgendaRepository` | — |
| Documents/photos + OCR | `DocumentImportManager` | `searchImages()` (new method, same storage) |
| Knowledge/Wiki | `KnowledgeRepository` | — |
| Unified Archive search | `RetrievalRanker`/`HybridRanker` (`core/memory`) | `PersonalArchiveSearch` (thin federation, no new ranking algorithm) |
| Cross-entity links | — | `ArchiveLink` model + `archive_links` table (schema only, see §2) |

## 4. Archivio UI

`ui/archive/ArchiveScreen.kt` now has six tabs: **Tutto** (merged notes +
list items, searchable), **Appunti** (unchanged from `docs/PRO_MODE.md`),
**Liste** (the shopping list + every custom list, tap a custom list to open
its item dialog), **TODO**, **Da vedere** (unchanged), **Documenti**. TODO
and Documenti are deliberately **quick-link redirects** into the existing
`AgendaScreen` ("Attività") and `DocumentArchiveScreen` — both are already
complete, dedicated screens; duplicating either inside Archivio would be a
second, thinner implementation of something that already works well. The
screen works as a plain notes/lists app with zero AI involvement — every
action is a direct `ArchiveViewModel` call into the same repositories the
tools use, per spec §2's "must work without needing the AI."

## 5. Tools (current, authoritative — supersedes `docs/PRO_MODE.md` §5's table)

| Tool | Policy | Layer | Backed by |
|---|---|---|---|
| `search_knowledge` | READ_ONLY | Knowledge | `KnowledgeRepository` (unchanged, pre-existing name) |
| `search_memory` | READ_ONLY | (Memory V2, separate from the Archive) | `MemoryIndex.retrieveSmart` |
| `search_archive` | READ_ONLY | Personal Archive | `PersonalArchiveSearch` (notes + to-watch + list items) |
| `search_documents` | READ_ONLY | Personal Documents | `DocumentImportManager.documentEvidence` |
| `read_document_context` | READ_ONLY | Personal Documents | `DocumentImportManager.contextFor` (renamed from `get_attachment_context`) |
| `search_images` | READ_ONLY | Personal Documents (photos) | `DocumentImportManager.searchImages` (new) |
| `create_archive_item` | LOW_RISK_WRITE | Personal Archive | `ArchiveRepository.create` (type=note\|to_watch) |
| `read_archive_item` | READ_ONLY | Personal Archive | `ArchiveRepository.findByText`/`byKind` — lists all of a type with no title given |
| `update_archive_item` | LOW_RISK_WRITE | Personal Archive | `ArchiveRepository.update` |
| `delete_archive_item` | CONFIRMING_WRITE | Personal Archive | `ArchiveRepository.deleteByText` |
| `list_items` | READ_ONLY | Personal Archive | `ArchiveListRepository.itemsOf` (by `list`) or `ArchiveRepository.byKind` (by `type`) |
| `create_list` | LOW_RISK_WRITE | Personal Archive | `ArchiveListRepository.createList` |
| `add_list_item` | LOW_RISK_WRITE | Personal Archive | `ArchiveListRepository.addItem` — auto-creates the list on first use |
| `update_list_item` | LOW_RISK_WRITE | Personal Archive | `ArchiveListRepository.updateItem`/`completeItem` |
| `remove_list_item` | LOW_RISK_WRITE | Personal Archive | `ArchiveListRepository.removeItem` |

Every one of these is registered once in `di/ToolsModule.kt` and reachable
identically from PRO (model-chosen JSON tool call) and NORMAL
(`CommandMatcher`-matched, deterministic) — see §6.

## 6. NORMAL mode: the same repositories, via `CommandMatcher`

`SessionCoordinator.generateAnswer()`'s NORMAL branch already ran
`CommandMatcher.match()` before any LLM classifier; this follow-up adds
~20 new pattern-matching functions there (shopping/list/watch/note create,
list/complete/delete, and the three-layer search disambiguation), each
producing a `ToolCall` executed via the exact same `ToolRunner`/`Tool`
instances Modalità Pro's tool-calling protocol uses. There is one
implementation per operation; NORMAL and PRO differ only in *how* the
`ToolCall` gets constructed (regex vs. model JSON), never in what runs it —
satisfying spec §11's "NON creare due implementazioni separate" for real,
not just for notes/to-watch as in the original `docs/PRO_MODE.md`.

**Deliberate scope cut, disclosed**: the alias table the user supplied
(`jarvis_aliases_intents_it_v2.txt`) has hundreds of phrasings across dozens
of intents; only the ones backed by a concrete example or a golden test in
that file were implemented as deterministic regexes. Everything else
(most of sections 1-3, 9-21 of that file — reminders, calendar, media,
timers, Home Assistant, etc.) was **already** implemented in `CommandMatcher`
before this session, untouched here. Phrasings for shopping/lists/to-watch/
notes/archive-search *not* explicitly exemplified fall through to the
existing LLM intent classifier / chat path, same as any other
unrecognised utterance — this is the alias table's own stated design
("gli alias sono un acceleratore, non l'unico sistema di comprensione," §24).

**One deliberate behaviour change to existing NORMAL routing**: per the
alias table's own §34 ("ricordami di comprare il latte... può essere
SHOPPING_CREATE"), `"ricordami di comprare X"` / `"ricordami di acquistare X"`
now route to `add_list_item` (shopping) instead of falling through to the
pre-existing `remember`/reminder logic. This is the one explicit exception
to "don't touch what already works" — the user's own priority table asked
for it by name.

**One deliberate overlap left untouched, disclosed**: the alias table's
`[NOTE_CREATE]` list includes "prendi nota", "segnati", "annota" — phrases
`CommandMatcher.REMEMBER_PREFIXES` already routes to Memory V2's `remember`
tool (pre-existing, tested, shipped before this session). Rather than move
already-relied-upon behaviour, `noteCreateCall` only claims genuinely new,
unclaimed phrasings ("crea una nota", "aggiungi una nota", "scrivi una nota",
"salva una nota", "crea/aggiungi appunto"). A user who says "segnati che…"
still gets Memory V2, unchanged; the Personal Archive's own notes are
reached through the phrasings above, the Archivio UI, or Modalità Pro.
`CommandMatcherTest.ordinaryRememberPhrasingStillGoesToMemoryNotTheArchive`
pins this down so it can't drift silently.

Also disclosed: `"cosa ho salvato su X"` is claimed by the pre-existing
`RECALL_RE` (→ `list_memories`, Memory V2) before this session's
`searchArchiveCall` gets a chance to run, since `RECALL_RE` is checked
earlier in `CommandMatcher.match()`. The alias table's own golden example
("Cosa avevo scritto io sull'OAuth?", imperfect tense "avevo", not "ho") is
unaffected and correctly reaches `search_archive` — verified by
`CommandMatcherTest.whatDidIWriteSearchesThePersonalArchiveNotMemory`.

## 7. Documents pipeline (unchanged, cited per spec §7)

`DocumentImportManager.documentEvidence()` already returns passages with a
citation (`DocumentChunk.citation`: file name + page or section), and
`contextFor()` already fails closed (returns nothing) on an ambiguous name
match rather than guessing — both pre-existing, confirmed unchanged. No new
document storage; §7 was already satisfied before this session.

## 8. Photos pipeline

`DocumentImportManager.searchImages(query)` (new): filters to `image/*`
documents, matches by display name/title/tag first, then by OCR chunk text
(`dao.chunksFor`) — lexical only, same honesty as `docs/PRO_MODE.md` §8 on
why no visual embedding model is wired. `search_images` is a **narrower**
tool than `search_documents` on purpose: "trova la ricevuta delle gomme"
should not also surface an unrelated PDF that happens to mention "gomme."

## 9. Unified search

`PersonalArchiveSearch.search(query)` federates `ArchiveRepository.searchSmart`
(notes/to-watch) and `ArchiveListRepository.searchSmart` (list items),
re-sorts by score, and is the one implementation behind both the
`search_archive` tool and (indirectly, via the same repositories) the
Archivio UI's own "Tutto" tab search box — not two search implementations
wearing different UI. Both underlying repositories already blend lexical
(`RetrievalRanker`, always available) with semantic scores
(`EmbeddingRepository`, when a model is imported) via the existing
`HybridRanker.fuse` — no third ranking algorithm.

## 10. Tests

`:core` (all passing, `cd core && ./gradlew test`):

- `ArchiveListTest` — blank name/title rejection, `toMemoryChunk(listName)`
  carries title/list-name/description.
- `ArchiveLinkTest` — self-link rejected, a real two-entity link round-trips.

`:app` (`CommandMatcherTest`, only runnable in CI — no Android SDK here):
new cases for shopping create/list/complete, custom list create/add/remove,
watch create/complete, note-create vs. Memory-V2 overlap, and the three-way
search disambiguation (archive vs. documents vs. knowledge) — all verified
against the actual regex patterns via a standalone `java.util.regex` harness
in this environment (Kotlin/Java share the same regex engine on the JVM),
since `:app` cannot compile here (see §11). Of the spec's 14 essential test
items: notes CRUD/search, shopping list, TODO CRUD (pre-existing, untouched),
to-watch CRUD, custom list, persistence-after-restart (Room, same pattern
as every other table in this app), NORMAL/PRO sharing one repository, and
no Knowledge Base duplication are covered by construction or by the tests
above; **import PDF / extraction+search of a PDF / AI-document-query /
combined-search tests were not added as new automated tests** —
`docs/PRO_MODE.md` already covers document-tool behaviour and this session
changed nothing about the document pipeline itself beyond `searchImages`;
combined search is covered structurally by `PersonalArchiveSearch` but has
no dedicated automated test in this pass.

## 11. Build status (carried over, not new)

This branch still has phase 4b (Supertonic)'s missing
`app/libs/sherpa-onnx-1.13.5.aar` and model files, so `./gradlew
:app:assembleDebug` fails at the same pre-existing point regardless of this
feature's correctness. `cd core && ./gradlew test` is green.
