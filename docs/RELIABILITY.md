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
