# M4 — 150-request benchmark: what exists, what was measured, what was not

Evidence levels used below: **CODE** (read in the source) · **JVM** (JVM test) · **EMULATOR** (Android emulator test) · **DEVICE** (real phone, real model). **Nothing in this file is DEVICE.**

## 1. Dataset (`core/src/main/resources/bench/bench_v1_cases.json`, fixtures `bench_v1_fixtures.json`, generator `tools/bench/generate_bench_v1.py`)
150 cases, 14 categories exactly as specified (geografia 12, scienze 12, medicina 10, informatica_cyber 12, definizioni 10, ragionamento 14, calcoli_strumenti 12,
conversazione 12, memoria 12, documenti 14, assenti 10, contraddittorie 8, ortografia 6, indirette 6), **100 DEV / 50 RESERVED**. Every case carries: id, category, tags,
turns, context (fixture refs), mode, source + version, expected answer, required/forbidden claims, supporting evidence, expected citations, expected/forbidden actions,
no-source behaviour, acceptable variants, risk, criterion, numeric gold with tolerance, split, `observed`, `requiresLlm`, `humanReview`. 85 synthetic encyclopedia
articles, 10 synthetic memory facts, 6 synthetic documents (no real user data). `BenchDataset.validate` refuses a set that breaks the schema, the quotas or the split.
The legacy `benchmark_v1.json` (133 prompts) and `PromptBudgetBenchmarkTest` are untouched and still run; they are a **prompt-size** benchmark, not a quality one.

**Blindness.** The author wrote and read all 150 cases: the bundled RESERVED split is reproducible but **not blind** (`observed=true` on every case; a test forbids
labelling any bundled case blind). A genuinely sealed set can be imported later with `BenchDataset.importReserved(json, fixtures)`: it is validated, stamped
`observed=false` only there, and its text fingerprint is returned for the report. The old 45-case holdout is not claimed to be unseen.

## 2. Runner, scoring, reports (`core/.../bench`)
- `BenchRunner`: single case / category / split / full suite via a selector; **resume** (results are saved atomically after every case; a run id is only resumed for the
  same dataset fingerprint, fixtures version and run kind); every case starts after `executor.reset()` (no state leaks; multi-turn cases keep their own context on purpose);
  an engine error is recorded as a failed case, never fatal; cancellation propagates.
- `RunIdentity` records commit, build SHA, dataset fingerprint, fixtures version, offline mode, model file + SHA-256, runtime/version/backend/slot/window, sampling,
  environment (`jvm` / `emulator` / `device:<hardware key>`). `deviceRun` is true only for a MODEL run on `device:*` with a model hash.
- Scoring is per dimension, never one word-match: retrieval, sufficiency, facts (whole-word, numbers normalised, citation markers ignored), forbidden claims, numeric
  tolerance (abs/rel), citation validity (a **real but non-supporting citation is an error**), abstention, conflict shown, routing/action, false proof (a forbidden claim
  published as verified or cited). A cautious, explicitly qualified general-knowledge answer is **not** a false proof (it is counted as a qualified answer). Cases that
  need human judgement are `passed = null` (never auto-passed). No LLM judge exists in the pipeline.
- Reports (`BenchReport`): Markdown, JSON, CSV, SVG chart — from recorded scores only; missing measurements print `NOT_MEASURED` / null, never 0; same input → same text.
  Export is voluntary (`BenchExport`, preview first; the UI is PENDING).

## 3. What the model-free PIPELINE CONTROL measured (JVM — dataset 1.0.0)
The control feeds **scripted candidates** (`faithful` = the gold sentence, `hallucinated` = the same sentence with its key value swapped) through the **production**
retrieval (two real synthetic ZIMs, real SQLite FTS4, `KnowledgeGatherer`), `EvidenceAssembler`, `PublicationPolicy`, `AnswerFraming` and the real `CalculateTool`. It
validates the harness and the gate. **It says nothing about any model's answer quality.** 27 cases (14 reasoning, 12 conversation, 1 document computation) need a real
model's own generation and are reported NOT EXECUTED (123 executed / 27 not executed). Memory and document evidence is *given* to the gate (their retrieval has its own tests).

| category | executed | passed (faithful candidate) |
|---|---|---|
| geografia | 12 | 10 (83.3%) |
| scienze | 12 | 9 (75.0%) |
| medicina | 10 | 8 (80.0%) |
| informatica_cyber | 12 | 7 (58.3%) |
| definizioni | 10 | 10 (100%) |
| assenti | 10 | 9 (90.0%) |
| contraddittorie | 8 | 8 (100%) |
| ortografia | 6 | 6 (100%) |
| indirette | 6 | 3 (50.0%) |
| documenti | 13 of 14 | 12 (92.3%) |
| memoria | 12 | 11 (91.7%) |
| calcoli_strumenti | 12 | 12 (100%) — exact, through the real calculator |
| ragionamento / conversazione | 0 of 26 | NOT_MEASURED (needs a real model) |
Total with the faithful candidate: **105 / 123 executed passed**, 18 failed, **1 false proof**. With the **hallucinated** candidate: 23 false proofs (below).
Latency/RAM/battery: **NOT_MEASURED** here (JVM in-memory timings are not comparable with the Honor 200 and are not reported as performance).

### The 18 failures are findings, not tuning targets (SupportJudge and ClaimVerifier were NOT modified to improve them)
- 13 are **sufficiency misses on natural questions**: the fixture states the fact but the question uses other words ("significa la sigla", "usa… per cifrare", "sulla Terra" vs "terrestre"): the lexical judge declares *insufficient*, so the system answers with an explicit "not verified" qualifier instead of a cited answer (known limit, P2 semantic layer).
- `geo-11`: the citation points to the article's lead, which does not state "il più grande" (the claim is in the section) — an authentic but non-supporting citation.
- `abs-08` ("chi ha inventato HTTPS?"): the judge accepts a lead that shares "protocollo HTTPS", the model correctly abstains, and the gate **replaces the abstention with an unrelated extract** (porta 443). A real false-support path.
- `doc-10`: two similar manuals ("Termo-Y" asked, "Termo-Z" also present): the gate lists both values as a conflict and cites the wrong manual.
- `mem-07`: "Bevi il caffè…" is rejected against "L'utente beve il caffè…" (second-person verb inflection): memory answers in the second person can be dropped by the verifier.

### Most important measured weakness: single-word substitution is verified
With the hallucinated candidate (the gold sentence with ONE key word swapped, e.g. "prodotto dal **fegato**" for "…pancreas"), **23 of 123 were published as VERIFIED with a citation**
(`PublicationPolicy` mode VERIFIED), all with non-numeric swaps; **every swapped number/identifier was rejected (0 false proofs among the numeric cases)** and no hallucinated claim
was ever verified for an *absent* topic. The claim verifier works on lexical overlap with a coverage threshold, so a long sentence with one wrong content word can pass.
This is the M0 gate's documented limit ("co-occurrence, not entailment") now measured; it is **not fixed in M4** (out of scope: M0 is not reopened) and it is the first thing
a model run on the device must be checked against. `BenchPipelineControlTest` pins the current counts as a regression baseline (faithful ≥ 105 passed, ≤ 1 false proof;
hallucinated ≤ 23 false proofs; 0 on numeric swaps).

## 4. Not done / PENDING
- Any run with a real model (**M4 DEVICE VERIFIED = PENDING**): `M4DeviceRunInstrumentedTest` (adb, skipped unless `m4device=true` and a model is selected) and the manual procedure in `docs/M4_HONOR200_PROCEDURE.md`.
- Real Italian ZIM, real memory/document stores on a device, FAST/ADVANCED, thermal/battery/RAM/CPU on the phone, airplane-mode runs.
- In-app UI to start a run and to export the report (the interface and the adb runner exist; the screen is PENDING).
- Reasoning/conversation quality (needs a model); human review of the HUMAN_REVIEW cases.
- Device-mode citation checking is article-level (the footer names the source, the passage only for duplicate labels).
