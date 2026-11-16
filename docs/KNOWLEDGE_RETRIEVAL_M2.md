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
