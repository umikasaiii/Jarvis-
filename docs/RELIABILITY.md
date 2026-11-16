# Offline AI reliability — publication control (status, honest)

One pre-publication control sits between the model and the user for every evidence-based answer (`core/reliability`):

1. **`EvidencePackage`** — built ONCE before inference (after sanitising and every size limit). The prompt renders it and the verifier
   reads the same items; items are cut only at sentence boundaries (a first sentence that does not fit is *omitted and reported*,
   never cut into a fake complete one); items carry kind (knowledge / document / memory / tool), id, source key and text range.
   Tool results are one item per line under a character budget; what does not fit is named in the prompt and cannot support the answer.
2. **`ClaimVerifier`** — per proposition (attribution phrases like "secondo le fonti" are stripped, not trusted): lexical overlap only
   *selects* the evidence sentence; what decides is a set of consistency checks — numbers/units/sign/decimals in Italian notation,
   value↔thing attribution, polarity, names and their order, qualifiers kept ("circa", "solo", "se"…), no added universals, invented
   file/page references. `DERIVED` only through a checked unit conversion; two nearby sentences are not a derivation. It is **not NLI**:
   an undecidable paraphrase is `UNVERIFIED`.
3. **`PublicationPolicy`** — VERIFIED / ALLOWED_PARTS / FAITHFUL_EXTRACT / ABSTAIN. Rejected propositions are never shown. Summaries
   and comparisons keep coverage with verbatim extracts. Citation markers are *rebuilt* from the verified claim→passage links (model
   markers are discarded); the footer numbers match the markers and says "a supporto" vs "consultate".
4. **`AttemptLedger` / `AttemptBudget`** — one ledger per request, carried in the coroutine context and consumed by `InferenceSlot`
   at the native boundary (first attempt, degenerate retry, quality retry, revision). Max 2; an exhausted ledger = zero generations.
5. **`PublicationOrchestrator`** — policy → at most ONE revision that names the rejection → policy → best publication; the native
   conversation is invalidated whenever the published text is not the model's candidate, so a rejected draft cannot be continued.
6. **`RequestClassifier` / `AnswerFraming`** — only factual requests get evidence rules; with insufficient evidence the qualifier
   comes *before* the content; a read error is reported as a read error, not as missing information.

Wired in `SessionCoordinator`: Classic chat (`publishChatTurn`), opportunistic document evidence (structured blocks now part of the
package), Documents / summaries / comparisons and Knowledge/Memory focus (`generateAndPublish`).

## Not done / limits (do not read this as solved)
- No NLI/embedding judge (P2 not enabled). Lexical selection + checks can miss a wrong claim whose words all appear in a sentence
  that says something else; negation/entity/number/qualifier checks narrow this, they do not close it.
- Tool-only turns and answers with no retrieved evidence are not verified. The PC/remote chat path (`tryRemoteChat`) bypasses the gate.
- `KnowledgeRepository`/`DocumentImportManager` still swallow some internal read errors, so those surface as "absent".
- BM25 ranking, real tokenizer budget, model profiles, offline guard/trace and the 150-request live benchmark remain open.
- Verified: JVM tests in `core` (analysed cases, not blind). App wiring is compiled by CI only; nothing is DEVICE VERIFIED.

## M0 closure round (residual P0 findings)
- Limit formulas ("non so", "non posso verificare") are legitimate only while the clause states no number or proper name; a limit
  declaration is split from what follows its comma, and "VERIFIED" needs every checkable clause supported.
- Citations are per passage (`EvidenceItem.id`), never per title; same-title passages stay distinct in text and footer
  (`distinctLabels`); a claim is supported if ANY equally matching passage passes every check and cites only that one.
- Coverage extracts publish whole sentences only; a sentence over the budget is skipped and the omission is declared.
- No sources at all + precise facts (number/date/new proper name) → qualified as unverified model knowledge; general explanations,
  chit-chat, transforms, calculations and hypotheticals are never qualified. A read error is reported as a read error
  (the index-open failure in `KnowledgeRepository.gather` is no longer swallowed). `RequestClassifier` treats a request that names a
  specific thing as factual even without a recognised question formula.
- Tool-only turns: the model's restatement may not contain a number/name absent from the receipt; otherwise the receipt is rendered verbatim.

### Paths still outside the gate (known, not hidden)
| Path | Why | Gate to close |
|---|---|---|
| `tryRemoteChat` (PC / Core PC) | remote answers are not verified; changing it belongs to the network policy (M3) | M3 |
| Chat with no retrieved evidence | nothing to verify against; handled only by the unverified-knowledge qualifier for precise facts | M1/P2 |
| Internal read errors inside `DocumentImportManager` (`runCatching … getOrDefault`) | surface as "no result" | M1 |
| Semantic paraphrase / NLI | lexical selection + consistency checks only | P2 (disabled unless proven) |

## M0 final qualification round
- **Tool receipts are structured** (`ToolReceipt`: tool, status DONE/FAILED/CANCELLED/NEEDS_CONFIRMATION/INFO, the tool's sentence). A claim that an
  action was carried out needs a DONE receipt reporting the SAME kind of action ("acceso" is not supported by "Torcia spenta"); an
  info receipt (battery…) authorises no "fatto"; a failed/cancelled/unconfirmed receipt contradicts success; a claimed failure when the tool
  succeeded is rejected too. A value is accepted only attached to the property the receipt states. If the restatement is not provable,
  the receipts are rendered verbatim — no extra generation, no second model. Earlier turns of the same message ("Simone:/JARVIS:") are context, never evidence.
- **Conflicting passages**: if one passage supports a claim and another equally relevant one states a different value, the claim is not
  published as one version of the fact; both passages are quoted with their own markers.
- **Footer order**: numbering follows citation order, not recency. The "most recent first" statement is removed from the numbered footer
  (dates, when the source has one, are shown beside each source; none are invented).
- **Real-coordinator test** (`SessionCoordinatorPublicationInstrumentedTest`, emulator workflow): the production `SessionCoordinator` via the
  Hilt graph and `processQueuedText`, with only the model call and the knowledge retrieval replaced through `ChatTestSeam`
  (null in production). The seam is counted against the same attempt ledger. It proves the publication pipeline, not model quality.

## M1 — memory, conversation state, context budget
- **Provenance** (`MemoryRecord` additive fields, old files load unchanged): source (USER_STATEMENT, USER_PREFERENCE, TOOL_RESULT, DOCUMENT, ZIM,
  MODEL_INFERENCE, HYPOTHESIS, UNVERIFIED, REJECTED_DRAFT), verification, sourceRef, conversationId, status (ACTIVE / SUPERSEDED / CONFLICT),
  supersedes / supersededBy, subjectKey. `MemoryAdmission`: model inference, hypotheses, unverified items and rejected drafts are never durable;
  a document/ZIM/tool item is a referenced source, never a personal fact. The existing explicit-save confirmations are untouched; no new automatic saves.
- **Corrections** (`MemoryLedger`): "Correggo: ora peso 80 kg" marks the earlier value SUPERSEDED and links both (history kept for audit); a different value
  with no correction cue marks both CONFLICT (offered to the model flagged as unresolved); the same value twice is stored once. Retrieval never offers a superseded value.
- **Three levels stay separate**: published history (chat store; what the next turn is rebuilt from, footers stripped, uncertainty qualifiers kept) · native
  context (dropped whenever the published text is not the model's candidate, on model/slot change, on rotation) · durable memory (only explicit saves).
- **Recap** (`ShortTermMemorySummarizer`): questions/commands without "?", suppositions and guesses are not recorded as the user's facts; a correction replaces the earlier
  value; the prompt label says they are unverified user statements.
- **Budget** (`ContextFit`): estimates only — no tokenizer API of LiteRT-LM 0.14.0 was verified (the artifact is not available in the build environment and none was invented);
  `TokenEstimator` keeps its 3 chars/token over-count. The evidence is fitted to what remains after system + history + question + framing + a revision reserve; whole items are
  dropped (memory, then later passages; receipts never) and the prompt says so; a request that alone exceeds the window is refused explicitly (no silent truncation, no generation).
  Rotation is now also decided on the ASSEMBLED message, before overflow. Window size is a `ContextBudget` parameter (not hard-wired as universal).
- **Read errors**: personal-memory rewrites fail closed when the archive cannot be read (previously an unreadable file looked empty and was overwritten);
  document reads that answer questions throw `DocumentReadException` instead of returning an empty list (cancellation untouched).
- Not done in M1: real tokenizer integration; DAO-level instrumented fault injection for documents; durable storage of tool receipts (nothing writes them to memory).

## M1 closure — facts verified in CI (not assumed)
- **LiteRT-LM 0.14.0 API inspection** (CI step "LiteRT-LM API inspection", `javap -public` on the real AAR): there is NO standalone tokenizer and no way to count the
  tokens of a text BEFORE sending it. The only token-related public members are `Conversation.getTokenCount(): Int` (tokens of a live conversation),
  `BenchmarkInfo.getLastPrefillTokenCount()/getLastDecodeTokenCount()` (measured after a turn), `EngineConfig.getMaxNumTokens()`. Consequence: pre-send budgeting stays an
  ESTIMATE (`TokenEstimator`, class-aware, pessimistic per character class but not a proven bound); measured post-turn counts exist and are NOT yet wired into the ledger
  (they would have to travel NativeConversation → InferenceSlot → LitertLmEngine → LlmRouter → coordinator; planned together with M3's runtime work). `ContextEvent.measured` is therefore always false today.
- **Memory archive**: structured records that cannot be decoded are reported (`ParseResult.damaged`) and re-emitted verbatim by every rewrite; legacy bullets and unknown
  future enum values load normally; writes go through `AtomicTextFile` (temp file + fsync + atomic rename, last good version kept as `.bak`); an unreadable archive blocks the write.
- **Final prompt**: the assembled message is measured (system + history + whole message + a revision reserve) and evidence is dropped one item at a time (memory first, then later
  passages; receipts never) until it fits; if the minimum does not fit, the request is refused BEFORE any generation. Sources retrieved but left out are announced as "not used in this
  request", never as "nothing found"; they are never cited.
- **Slots**: `tokenLedgers`, `activeSystems`, `activeEpochs`, `injectedContexts` are keyed by slot (FAST and ADVANCED never share a ledger); a slot/model/engine change and a rotation
  are recorded as reset reasons in the (local, voluntary-export) context diagnostics.

## M1 storage-integrity gate (memory archive `memoria.md`)
- **Format contract.** Known lines: blank, the header, structured records (`<!-- jarvis-memory-v2:{json} -->`), legacy bullets, and the bullet that directly follows
  a structured record (its old "echo": never trusted, consumed if it only differs in formatting, kept verbatim if it says something else). Every other line, every structured
  line that cannot be decoded (corrupt, truncated, damaged prefix, leading/trailing spaces included) and every later record that REUSES an id with different content is
  returned as "damaged" exactly as written and re-emitted unchanged by every rewrite. Only the line terminator (`\r\n` → `\n`) is normalised. Preserved lines are never
  interpreted as facts. Identical duplicates are dropped (nothing is lost).
- **Future fields.** A record with an unknown key or enum value loads (unknown enum → default for the app's own use) and its original JSON is kept, so a rewrite
  writes the unknown parts back unless the user changes that very field. Valid archives rewrite to the same content.
- **`AtomicTextFile`.** temp file + fsync + `.bak` of the previous version + atomic rename. The `.bak` is refreshed only if the current main file still holds at least one
  decodable record (a garbled or emptied main file cannot overwrite the last good copy); if the main file is missing the last good copy is read instead of an empty archive.
  A stale `.tmp` is an unsaved write and is overwritten. NOT promised: protection from media corruption or from a power loss the storage does not honour after fsync —
  JVM tests simulate an interrupted write, they cannot prove power-loss behaviour. The `.bak` is one generation, not a history.
- **Restart.** A fresh reader of the real file is tested on the emulator; a real process restart is NOT exercised in the current workflow.
