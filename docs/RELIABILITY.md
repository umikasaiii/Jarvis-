# Offline AI reliability — publication control (status, honest)

One pre-publication control now sits between the model and the user for every **knowledge/document/memory** answer
(`core/reliability`): `EvidencePackage` (immutable, one rendering for prompt AND verifier, sentence-boundary cuts, renderer-built
citations, invented `[n]` removed, role/template markers neutralised) → `ClaimVerifier` (per sentence: EXPLICITLY_SUPPORTED /
DERIVED / UNVERIFIED / CONTRADICTED / UNKNOWN; Italian numbers+units+decimals via `Quantities`, polarity, entity presence/order,
number attribution, short-answer relevance) → `PublicationPolicy` (VERIFIED / ALLOWED_PARTS / FAITHFUL_EXTRACT / ABSTAIN; a rejected
claim is never shown) → citation line from the items the published text rests on. `AttemptLedger` caps generative attempts at two
per request (quality retry; each retry has a recorded cause).

Wired in `SessionCoordinator`: Classic chat (`publishGrounded`, incl. mixed tool+knowledge turns — tool results join the package),
documents (`publishDocuments`, lenient for summaries/comparisons), Knowledge and Memory focus (`focusedAnswer`).

## Not done / limits (do not read this as solved)
- No NLI/embedding judge: an undecidable paraphrase becomes a faithful extract or a declared limit, not a guess (P2 not enabled).
- Answers with NO retrieved evidence (general chat) are unchanged — there is no evidence to verify against.
- Slot-level degenerate retry is not counted by `AttemptLedger` (worst case 3 generations, not 2).
- A verification-driven revision pass is not wired. Memory/conversation-state separation, real-tokenizer budgeting, BM25 re-rank,
  model profile registry, offline guard/trace and the 150-request live benchmark are NOT implemented in this round.
- Verified: JVM unit tests in `core` (`ReliabilityTest`, `ReliabilityCorpusTest`: analysed cases, not blind). App wiring is compiled by CI only; nothing is DEVICE VERIFIED.
