# M2 — Advanced offline knowledge retrieval

Scope: improve the existing Knowledge Engine (no new model, no embeddings, no network). Everything below is bounded.

## What changed
- **Candidate selection (index)** — `KnowledgeIndex.search` ranks candidates by BM25 computed in Kotlin from SQLite **FTS4** `matchinfo(kx_fts,'pcnalx')`
  (k1=1.2, b=0.75; no FTS5 `bm25()`). Bounds: `scanCap` 2000 rows/query (only the small matchinfo blob is read), ≤40 queries, a drop-one-term level always runs,
  union re-scored by ONE disjunctive expression over ≤800 rows, top 60 re-ranked; final order = reciprocal-rank fusion of BM25 and the existing TF-IDF/heading ranker.
  If `matchinfo` is unusable the legacy docid-order selection runs (no failure).
- **Sections** — passages found by the index are de-duplicated against what was read by *passage identity* (article + section + text), not by article.
  Articles longer than the 80-chunk cap keep the lead + the chunks that share most stems with the question (heading counts double) instead of the first 80;
  text window 120k→300k chars. Citation identity (article, section, text) is unchanged (M0).
- **Deep pass** — one bounded second pass (≤2 articles) reads the articles the index pointed to when the first pass has no supporting window, or when the
  only evidence is an index-found lead; the same `EvidenceAssembler` judges them. A deep result is accepted only if it also names every capitalised
  entity of the question (typo-tolerant): an article about another lake never answers "il Lago Inesistente". No extra LLM call.
- **Errors are not empty results** — `KnowledgeGatherer.gatherDetailed` returns a `RetrievalReport` (sources failed, index failed/partial, deepened, articles read,
  stage names only). `CancellationException` is never swallowed and `shouldContinue` is polled between stages. `KnowledgeRepository.lastReport` exposes it;
  if nothing healthy could be consulted and nothing was found the repository throws (the existing retrieval-error path), and a partial search is tagged in the diagnostic.
- **Cache** — per opened archive, LRU of converted article text, ≤2,000,000 chars, key = entry path, identity = archive uuid + date (a new/replaced archive is a new
  object with an empty cache); no answer cache.
- **Typo** — title fuzzy probes now include adjacent transpositions of the first letters.
- **Schema** — unchanged (index SCHEMA "2"); existing indexes remain valid; nothing is deleted.

## Measured (JVM, synthetic corpus, same corpus & index for both rows)
Corpus: `RetrievalBench`, invented entities, 50 questions (dev/holdout split), real SQLite FTS4 + real ZIM fixture. `RetrievalBenchmarkTest` prints the table.
Caveat: the holdout was fixed before the M2 code but its aggregate was visible while the entity guard was added; it is not a blind test.

| set | variant | R@3 | R@5 | P@3 | MRR | section R@5 | false support (absent) | RELATED→STRONG | p50 / p95 ms | sqlite/q |
|---|---|---|---|---|---|---|---|---|---|---|
| ALL | baseline | 0.74 | 0.74 | 0.25 | 0.73 | 0.47 | 0/4 | 0 | 1.7 / 4.1 | 13.6 |
| ALL | M2 | 0.87 | 0.87 | 0.34 | 0.86 | 0.79 | 0/4 | 0 | 2.7 / 8.1 | 14.8 |
| DEV | baseline → M2 | 0.75 → 0.92 | | | 0.73 → 0.90 | 0.50 → 0.90 | 0/2 → 0/2 | | | |
| HOLDOUT | baseline → M2 | 0.73 → 0.82 | | | 0.73 → 0.82 | 0.44 → 0.67 | 0/2 → 0/2 | | | |

Latency is JVM-in-memory on a synthetic corpus: it says nothing about the Honor 200. Index size, indexing time and memory were not changed by M2 and were not re-measured.

## Remaining misses (not solved)
- 3 long-article questions whose wording shares no stem with the answering section ("alto" vs "altezza", "lungo" vs "lunghezza", "profondo" vs "profondità"): needs semantic matching (out of scope).
- 1 deep-section question with the same lexical gap.
- 2 "answer beyond candidate limit" questions: the fillers literally mention the topic and are judged as supporting by `SupportJudge` (a sufficiency-judge limit, not retrieval).
- Real archives (Wikipedia) are not covered by this benchmark; efficacy there is unproven.

## Tests
JVM: `M2RetrievalTest` (15), `RetrievalBenchmarkTest`; core suite 2207 tests, 0 failures. Android: `M2RetrievalInstrumentedTest` (production `AndroidFtsDb`, `SafByteSource`,
real zstd, real assembler — not the Hilt `KnowledgeRepository`), listed in `db-migration-tests.yml`.

---

# M2 FINAL CLOSURE (sufficiency, per-request report, partial search, Android path)

## Defects confirmed and fixed
| defect | root cause | fix |
|---|---|---|
| "Non si conosce l'anno…" judged as proof of a year | `SupportJudge.hasNumber` counted **any digit**, including the digits inside the identifier `Frazione042`; no notion of an epistemic denial | numbers are standalone tokens only (`statesNumber`); a sentence that denies knowing the value (`withholdsValue`: negation + know/document verb or "sconosciuto/ignoto/incerto") cannot support; a negated number ("non nel 1500") is ignored; a year question needs a year-sized number that is not a measure ("82 metri"), a measure question ignores a number introduced as a year ("nel 1347") |
| a passage about ANOTHER entity supported the question ("Faro di Ostrena" for "Faro di Brenda") | candidates found only by their words were never checked against the question's proper nouns | per-passage proper-noun check in `EvidenceAssembler` for passages not found through a title the question names (typo tolerant) |
| lead "supported" with 4/5 terms while the answering section sat in the same article | tolerance for one missing term | the incomplete passage is **completed from the same article** (the passage that states the missing terms is added and cited with its own text); nothing outside the article is borrowed |
| the best BM25 candidate lost to 300 look-alikes in the final cut | TF-IDF/RRF cut at 12 | the head of the BM25 order (3) is always kept |
| deep pass duplicated the index lead | identity set did not include index-found passages | dedupe against both |
| `KnowledgeRepository.lastReport` global | evidence and report were two reads | `gatherWithReport()` returns one immutable `Gathered`; `lastReport` removed; `find()` no longer hides read errors |
| an archive that failed to open vanished from the search | `ZimKnowledge.sources()` dropped it silently | `sourcesChecked()` counts it; it becomes a failed source in the report |
| cache could exceed its quota with one big article | eviction kept ≥1 entry | an article larger than the quota is used but never stored; stats `cacheHits / cacheMisses / cacheNotStored` |
| `runCatching` swallowed cancellation in BM25 fallback, rescore, fuzzy title probe, listing locate, schema read | stdlib `runCatching` catches `CancellationException` | `catchingKeepCancel` |
| instrumented tests deleted `knowledge_fts.db` (the user's index) | hard-coded name | unique per-test database names; production `KnowledgeIndexManager.dbName` is a test seam only |

## Lexical gaps (P1-A)
- "profondo/profondità": already the same stem; failed only because "ottantadue" was not a number word → compound number words recognised.
- "alto/altezza", "lungo/lunghezza": a **single derivational family** (-ezza) is normalised (`dimensionRoot`). It is used (a) to pick which sections of a long article to read and (b) to mark the passage **RELATED** — never SUPPORTED, because the existing, tested product rule says a differently-worded property is a candidate, not proof (`GroundingVerifierTest`). `-ità` is deliberately excluded (università/universo). These two questions therefore stay *non verifiable* by design and are listed as P2 (needs a semantic layer).
- "gas that insulates a planet…" on the real archive: the question names the effect by its definition; still open (P2).

## User-visible partial search (P1-C)
`AnswerFraming`: new state `NO_RESULT_PARTIAL` ("non posso dire che l'informazione sia assente"), `PARTIAL_SEARCH_NOTE` after a verified answer, `PARTIAL_INSUFFICIENT_NOTE` after an "insufficient" answer. A supported answer from a search where only the index was incomplete carries no warning (the archives were read directly); a failed source/index does. The note is appended outside the verified text, adds no claim and no citation.

## Benchmark (same corpus, JVM, synthetic) — 56 questions: 6 added BLIND2 (written before the sufficiency rules; not used to choose them)
"baseline" = LEGACY retrieval options with today's judge. **Pre-closure M2 (`99bdd80`) on the same 56 questions**, measured by running the new bench on that commit: R@5 0.88, false support on answerable 3, supported absent 1/8, MRR 0.85; the legacy baseline had 8 false supports on answerable questions.

| set | variant | R@5 | supported R@5 | P@3 | MRR | section R@5 | false support answerable | false support absent | p50/p95 ms | SQLite q |
|---|---|---|---|---|---|---|---|---|---|---|
| ALL | baseline (legacy) | 0.76 | 0.76 | 0.25 | 0.74 | 0.47 | 6 | 0/8 | 4.5/24 | 14.1 |
| ALL | M2 `99bdd80` | 0.88 | 0.88 | 0.34 | 0.85 | 0.79 | **3** | **1/8** | 2.8/9.8 | 15.2 |
| ALL | M2 closure | **0.98** | 0.94 | 0.33 | 0.93 | 1.00 | **0** | **0/8** | 2.7/9.3 | 15.2 |
| DEV | M2 closure | 0.96 | 0.93 | 0.32 | 0.91 | 1.00 | 0 | 0/6 | | |
| HOLDOUT (seen in aggregate) | M2 closure | 1.00 | 0.95 | 0.33 | 0.95 | 1.00 | 0 | 0/2 | | |
| BLIND2 | baseline → closure | 1.00 → 1.00 | 1.00 | 0.33 | 1.00 | n/a | 0 → 0 | 0/4 → 0/4 | | |

By category (closure): lead 6/6, section-by-title 9/9, long-article 4/4 (2 of them RELATED-only), index-lead 6/6, deep section 6/6, typos 6/6, similar 4/4, number 3/3, absent 4/4, unknown value 3/3, identifier number 1/1, other property 1/1, negated 2/2, candidate-limit **1/2**.
Remaining miss: "In che anno fu costruito il ponte romano antico **del comune**?" — the only passages containing "comune" say the year is unknown; the answering passage does not mention a comune. Result: honest *insufficient* (no false support). P@3 fell from 0.35 to 0.33 because completion now returns two passages of one article.
Caveat: the corpus is synthetic and its failure classes were known; the rules are general but this is a regression bench, not proof of real-world quality.

## Real archive (`M2RealArchiveTest`, env-gated, not committed)
openzim "Climate change by Wikipedia" (English, CC BY-SA, 9 MB, 20,568 entries, 2024-06-18; "mini" edition: leads only, **no inner sections**). 12 questions (9 answerable, 3 absent): supported recall 8/9 for both variants; the miss is index-only and definitional ("which gas insulates a planet…"). Legacy variant supported the absent "population of Atlantis"; M2 did not (0/3). Index of 6,387 passages: 12.5 MB, 1.27 s on the JVM. Citation identity verified (article, `zim:<uuid>/Paris_Agreement`, collection + date). Deep sections of long articles and Italian archives: **PENDING on the Honor 200** (protocol below).

## Honor 200 protocol (not run)
1. Install the Beta, add a real Italian Wikipedia ZIM (not bundled), verify it, build the lead index.
2. Ask 15 questions with the answer in a lead, 10 in an internal section (long articles: "Roma", "Seconda guerra mondiale"), 5 absent, 5 with a typo; note supported / insufficient / absent and whether the source footer cites the right section.
3. Make one archive unreadable (move the file) and repeat three questions: the answer must carry "Ricerca parziale", and an absent topic must NOT be declared absent.
4. Record latency per question (stopwatch), index size (Impostazioni › Conoscenza), battery %, thermal state; repeat with airplane mode.
