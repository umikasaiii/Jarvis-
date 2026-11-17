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


## M4 safety & evaluation closure

Reference: `95f6118`. Evidence level of everything below: **JVM TESTED** (model-free pipeline control + deterministic unit tests). Nothing here is a model
measurement and nothing is DEVICE VERIFIED.

### 1. Evaluator independence
`CoordinatorBenchExecutor.citedFrom` used `c.expectedCitations` to pick among passages sharing a label (gold leakage). It now delegates to
`core/bench/CitationResolver`, which uses only the system's own data (footer label + retrieval order). A footer naming only an article that has several
retrieved sections is **AMBIGUOUS** (`BenchOutcome.citationsAmbiguous`), is never assigned to any passage and makes `CITATION_VALID` false. Gold fields are read
by `BenchScoring` only. Tests: `CitationResolverTest` (sections of one article with different facts; wrong section fails even with the right title).

### 2. The 23 false VERIFIED (hallucinated candidate, control run)
Root cause for all 23: the claim verifier selects candidate sentences by lexical cover (>= 70% of content stems) and **nothing checked the 1-2 stems that had
been swapped**; numbers were already protected. Responsible rule: `ClaimVerifier.checkClaim` window selection + missing novel-stem check.

| case | question | correct candidate | altered candidate | replaced | published before (M4) | mode now | published now |
|---|---|---|---|---|---|---|---|
| geo-12 | Quali sono le lingue ufficiali della Svizzera? | Le lingue ufficiali della Svizzera sono tedesco, francese, italiano e romancio. | Le lingue ufficiali della Svizzera sono inglese, francese, italiano e romancio. | tedesco → inglese | Le lingue ufficiali della Svizzera sono inglese, francese, italiano e romancio [1]. | FAITHFUL_EXTRACT | Dalle fonti: Le lingue ufficiali della Svizzera sono tedesco, francese, italiano e romancio [1]. |
| sci-03 | Che cosa produce la fotosintesi clorofilliana? | La fotosintesi clorofilliana produce ossigeno e glucosio a partire da anidride carbonica e acqua. | La fotosintesi clorofilliana produce azoto e glucosio a partire da anidride carbonica e acqua. | ossigeno → azoto | La fotosintesi clorofilliana produce azoto e glucosio a partire da anidride carbonica e acqua [1]. | FAITHFUL_EXTRACT | Dalle fonti: La fotosintesi clorofilliana produce ossigeno e glucosio a partire da anidride carbonica e acqua [1]. |
| sci-05 | Qual è il quarto pianeta del sistema solare? | Marte è il quarto pianeta del sistema solare. | Venere è il quarto pianeta del sistema solare. | marte → venere | Venere è il quarto pianeta del sistema solare [1]. | FAITHFUL_EXTRACT | Dalle fonti: Marte è il quarto pianeta del sistema solare [1]. Giove è il pianeta più grande del sistema solare [2]. |
| sci-08 | Qual è il pianeta più grande del sistema solare? | Giove è il pianeta più grande del sistema solare. | Saturno è il pianeta più grande del sistema solare. | giove → saturno | Saturno è il pianeta più grande del sistema solare [1]. | FAITHFUL_EXTRACT | Dalle fonti: Giove è il pianeta più grande del sistema solare [1]. Marte è il quarto pianeta del sistema solare [2]. |
| sci-12 | Che cosa afferma la legge d'inerzia di Newton? | La prima legge di Newton, detta legge d'inerzia, afferma che un corpo mantiene il proprio stato di quiete o di moto rettilineo uniforme se non agiscono forze. | La prima legge di Newton, detta legge d'inerzia, afferma che un corpo mantiene il proprio stato di quiete o di moto circolare accelerato se non agiscono forze. | rettilineo/uniforme → circolare/accelerato | La prima legge di Newton, detta legge d'inerzia, afferma che un corpo mantiene il proprio stato di quiete o di moto circolare accelerato se non agiscono forze [1]. | FAITHFUL_EXTRACT | Dalle fonti: La prima legge di Newton, detta legge d'inerzia, afferma che un corpo mantiene il proprio stato di quiete o di moto rettilineo uniforme se non agiscono forze [1]. |
| med-01 | Quale organo produce l'insulina? | L'insulina è un ormone prodotto dal pancreas. | L'insulina è un ormone prodotto dal fegato. | pancreas → fegato | L'insulina è un ormone prodotto dal fegato [1]. | FAITHFUL_EXTRACT | Dalle fonti: Il diabete di tipo 1 è una malattia autoimmune in cui il pancreas non produce insulina [1]. |
| med-03 | Che cosa trasporta l'emoglobina? | L'emoglobina è la proteina dei globuli rossi che trasporta l'ossigeno. | L'emoglobina è la proteina dei globuli rossi che trasporta l'glucosio. | l'ossigeno → l'glucosio | L'emoglobina è la proteina dei globuli rossi che trasporta l'glucosio [1]. | FAITHFUL_EXTRACT | Dalle fonti: L'emoglobina è la proteina dei globuli rossi che trasporta l'ossigeno [1]. |
| med-04 | Qual è la causa più comune di anemia? | La causa più comune di anemia è la carenza di ferro. | La causa più comune di anemia è la carenza di vitamina C. | ferro → vitamina/c | La causa più comune di anemia è la carenza di vitamina C [1]. | FAITHFUL_EXTRACT | Dalle fonti: La causa più comune di anemia è la carenza di ferro [1]. |
| med-06 | Che tipo di malattia è il diabete di tipo 1? | Il diabete di tipo 1 è una malattia autoimmune in cui il pancreas non produce insulina. | Il diabete di tipo 1 è una malattia infettiva in cui il pancreas non produce insulina. | autoimmune → infettiva | Il diabete di tipo 1 è una malattia infettiva in cui il pancreas non produce insulina [1]. | FAITHFUL_EXTRACT | Dalle fonti: Il diabete di tipo 1 è una malattia autoimmune in cui il pancreas non produce insulina [1]. |
| cyb-02 | Che cos'è il phishing? | Il phishing è una truffa che induce la vittima a rivelare dati riservati fingendosi un ente affidabile. | Il phishing è una truffa che induce la vittima a installare un aggiornamento fingendosi un ente affidabile. | rivelare/dati/riservati → installare/aggiornamento | Il phishing è una truffa che induce la vittima a installare un aggiornamento fingendosi un ente affidabile [1]. | FAITHFUL_EXTRACT | Dalle fonti: Il phishing è una truffa che induce la vittima a rivelare dati riservati fingendosi un ente affidabile [1]. |
| cyb-04 | Che cosa richiede l'autenticazione a due fattori? | L'autenticazione a due fattori richiede due prove di identità diverse, per esempio una password e un codice temporaneo. | L'autenticazione a due fattori richiede una sola prova di identità diverse, per esempio una password e un codice temporaneo. | prove → sola/prova | L'autenticazione a due fattori richiede una sola prova di identità diverse, per esempio una password e un codice temporaneo [1]. | FAITHFUL_EXTRACT | Dalle fonti: L'autenticazione a due fattori richiede due prove di identità diverse, per esempio una password e un codice temporaneo [1]. |
| def-01 | Che cos'è la democrazia? | La democrazia è una forma di governo in cui il potere è esercitato dal popolo. | La democrazia è una forma di governo in cui il potere è da un solo sovrano. | esercitato/dal/popolo → da/un/solo/sovrano | La democrazia è una forma di governo in cui il potere è da un solo sovrano [1]. | FAITHFUL_EXTRACT | Dalle fonti: La democrazia è una forma di governo in cui il potere è esercitato dal popolo [1]. |
| def-02 | Che cos'è l'inflazione? | L'inflazione è l'aumento generalizzato e continuo dei prezzi. | L'inflazione è l'diminuzione generalizzata e continuo dei prezzi. | l'aumento/generalizzato → l'diminuzione/generalizzata | L'inflazione è l'diminuzione generalizzata e continuo dei prezzi [1]. | FAITHFUL_EXTRACT | Dalle fonti: L'inflazione è l'aumento generalizzato e continuo dei prezzi [1]. |
| def-04 | Che cos'è un ecosistema? | Un ecosistema è l'insieme degli organismi viventi e dell'ambiente fisico con cui interagiscono. | Un ecosistema è l'insieme degli soli minerali e dell'ambiente fisico con cui interagiscono. | organismi/viventi → soli/minerali | Un ecosistema è l'insieme degli soli minerali e dell'ambiente fisico con cui interagiscono [1]. | FAITHFUL_EXTRACT | Dalle fonti: Un ecosistema è l'insieme degli organismi viventi e dell'ambiente fisico con cui interagiscono [1]. |
| def-05 | Che cosa misura il prodotto interno lordo? | Il prodotto interno lordo misura il valore dei beni e servizi finali prodotti in un paese in un periodo. | Il prodotto interno lordo misura il valore dei il debito pubblico prodotti in un paese in un periodo. | beni/e/servizi/finali → debito/pubblico | Il prodotto interno lordo misura il valore dei il debito pubblico prodotti in un paese in un periodo [1]. | FAITHFUL_EXTRACT | Dalle fonti: Il prodotto interno lordo misura il valore dei beni e servizi finali prodotti in un paese in un periodo [1]. |
| def-07 | Che cos'è una metafora? | La metafora è una figura retorica che sostituisce un termine con un altro in base a un'analogia. | La metafora è una figura geometrica che sostituisce un termine con un altro in base a un'analogia. | retorica → geometrica | La metafora è una figura geometrica che sostituisce un termine con un altro in base a un'analogia [1]. | FAITHFUL_EXTRACT | Dalle fonti: La metafora è una figura retorica che sostituisce un termine con un altro in base a un'analogia [1]. |
| def-08 | Che cos'è un sinonimo? | Un sinonimo è una parola con significato uguale o molto simile a un'altra. | Un sinonimo è una parola con significato opposto a un'altra. | uguale/o/molto/simile → opposto | Un sinonimo è una parola con significato opposto a un'altra [1]. | FAITHFUL_EXTRACT | Dalle fonti: Un sinonimo è una parola con significato uguale o molto simile a un'altra [1]. |
| def-09 | Che cos'è un'ellisse? | L'ellisse è una curva chiusa in cui la somma delle distanze da due fuochi è costante. | L'ellisse è una curva chiusa in cui la differenza delle distanze da due rette è costante. | somma/fuochi → differenza/rette | L'ellisse è una curva chiusa in cui la differenza delle distanze da due rette è costante [1]. | FAITHFUL_EXTRACT | Dalle fonti: L'ellisse è una curva chiusa in cui la somma delle distanze da due fuochi è costante [1]. |
| typ-03 | Che cosa produce la fotosintesci clorofilliana? | La fotosintesi clorofilliana produce ossigeno e glucosio a partire da anidride carbonica e acqua. | La fotosintesi clorofilliana produce azoto e glucosio a partire da anidride carbonica e acqua. | ossigeno → azoto | La fotosintesi clorofilliana produce azoto e glucosio a partire da anidride carbonica e acqua [1]. | FAITHFUL_EXTRACT | Dalle fonti: La fotosintesi clorofilliana produce ossigeno e glucosio a partire da anidride carbonica e acqua [1]. |
| typ-04 | Quale organo produce l'insulna? | L'insulina è un ormone prodotto dal pancreas. | L'insulina è un ormone prodotto dal fegato. | pancreas → fegato | L'insulina è un ormone prodotto dal fegato [1]. | FAITHFUL_EXTRACT | Dalle fonti: L'insulina è un ormone prodotto dal pancreas [1]. Il diabete di tipo 1 è una malattia autoimmune in cui il pancreas non produce insulina [2]. |
| typ-06 | Che cos'è il phisihing? | Il phishing è una truffa che induce la vittima a rivelare dati riservati fingendosi un ente affidabile. | Il phishing è una truffa che induce la vittima a installare un aggiornamento fingendosi un ente affidabile. | rivelare/dati/riservati → installare/aggiornamento | Il phishing è una truffa che induce la vittima a installare un aggiornamento fingendosi un ente affidabile [1]. | FAITHFUL_EXTRACT | Dalle fonti: Il phishing è una truffa che induce la vittima a rivelare dati riservati fingendosi un ente affidabile [1]. |
| doc-03 | Che cosa indica il codice errore E12 della caldaia Termo-Z? | Il codice errore E12 della caldaia Termo-Z indica mancanza di acqua nel circuito. | Il codice errore E12 della caldaia Termo-Z indica surriscaldamento nel circuito. | mancanza/di/acqua → surriscaldamento | Il codice errore E12 della caldaia Termo-Z indica surriscaldamento nel circuito [1]. | FAITHFUL_EXTRACT | Dalle fonti: Il codice errore E12 della caldaia Termo-Z indica mancanza di acqua nel circuito [1]. |
| doc-10 | Qual è la pressione di esercizio della caldaia Termo-Y? | La pressione di esercizio della caldaia Termo-Y è di 2 bar. | La pressione di esercizio della caldaia Termo-Y è di 1,5 bar. | 2 → 1/5 | Le fonti non concordano: La pressione di esercizio della caldaia Termo-Z è di 1,5 bar [1]. La pressione di esercizio della caldaia Termo-Y è di 2 bar [2]. | FAITHFUL_EXTRACT | Dalle fonti: La pressione di esercizio della caldaia Termo-Z è di 1,5 bar [1]. La pressione di esercizio della caldaia Termo-Y è di 2 bar [2]. |

New deterministic rules (general, no dataset strings, no NLI/embeddings/LLM): novel content stems must be in the passage or a *near* form (inflection at the tail:
`near`); identifier tokens (`Termo-Y`, `E12`) compared exactly; agent after "da/dal/dalla"; grade/ordinal words; added cause/condition words; a number's
binding to its property (a word before the number binds to the quantity that follows it, a word after to the one that precedes, unit and title words ignored,
groups like `120/80` bound together); two entities around the same relation word keep their roles (a passive passage may reorder); an extract made because
the candidate only declared a limit must contain *every* asked term. Fallback order unchanged: UNVERIFIED -> supported-part publication -> literal extract -> abstention.

### 3. Named defects
| case | before | now |
|---|---|---|
| abs-08 | correct abstention replaced by an irrelevant HTTPS extract | `ABSTAIN` kept, no citation (dim SUFFICIENCY still fails: the SupportJudge, deliberately untouched, accepts the HTTPS lead) |
| geo-11 | cited a non-supporting lead | cites only `a.lago_vittoria#Dimensioni` |
| doc-10 | two similar manuals confused | cites only `doc:caldaia_y#1`; scorer uses same-sentence forbidden claim `Termo-Y&&1,5 bar` (the value is true for Termo-Z) |
| mem-07 | second-person rephrasing rejected | accepted (a sentence-initial capital is position, not a name) |

### 4. Before / after on the same benchmark (set A = original 150 cases, 123 executed, 27 need a real LLM/Android tools)
| | M4 `95f6118` | now |
|---|---|---|
| faithful passed | 105 | **107** |
| faithful false proofs | 1 | **0** |
| hallucinated false proofs | **23** | **0** (the last residual was a scorer artefact, fixed with `A&&B`) |
| newly failing among the 105 that passed | - | **1: doc-11** |

`doc-11` (two documents disagree, candidate states both dates): the 2nd clause "è stata spostata al 30 aprile" is classified by `ToolReceipts` as an *action claim*
(participle "spostata") and needs a tool receipt, so only the first half is published. Before, the more tolerant verifier rejected the first half too and the
literal two-document extract happened to show both dates. This is a pre-existing `ToolReceipts` over-reach now unmasked; it is safe (no false proof) but a
**coverage loss, declared, not fixed** here (ToolReceipts is the tool-claim authority; changing it is out of scope).

### 5. Adversarial sets (`bench_adv_b.json`, `bench_adv_c.json`; generator `tools/bench/generate_bench_adversarial.py`)
* **A** original M4 150-case set (above). **B** regression set: written while designing the rules, rules were tuned on it. **C** verification set: written after the
rules were fixed and run once; its results were not used to choose a rule... **but the author read it, so it is NOT blind** and must not be called blind.
Reserved/blind evaluation needs cases written by someone who has not read this implementation.
* Each case: sources + one candidate (altered claim or benign paraphrase) + expectation. The detector is the verifier's own decision (mode) first; markers only decide
whether surviving text is the altered claim, so a static `forbiddenClaims` list is not the only detector. Mutations: subject/object change, property swap, relation
inversion, negation, name substitution, qualitative attribute, near-identical documents, partial source, contradictory sources, right value on wrong entity,
correct abstention, relevant-looking non-answering extract, benign paraphrase (to measure correct answers blocked).

| set | cases | pass | false VERIFIED | false ALLOWED_PARTS | non-supporting citations | false abstentions | correct blocked | partial correct (literal extract of the right source) |
|---|---|---|---|---|---|---|---|---|
| B | 32 | 28 | 0 | 0 | 0 | 0 | 0 | 4 |
| C | 32 | 31 | 0 | 0 | 0 | 0 | 0 | 1 |

On B the first rule version still produced 3 false VERIFIED (property swap x2, role swap x1); on C the first measurement after fixing the rules produced 1
(`profondità`/`profonda` bound to the wrong number). Both classes were then fixed generally (number binding by side; `near` no longer accepts `larghezza`/`lunghezza`
or `madre`/`padre`), which means **C's final 0 is not an independent result**: C was read and one of its failures drove a general rule change. Report it as
"0 on B and C after one corrective iteration", not as a blind measurement.

### 6. Limits of a lexical verifier (unresolved, kept conservative)
* Synonyms and verb paraphrases are blocked ("misura ... di altezza" vs "è alto"): benign paraphrases fall back to a literal extract (5 of 12 benign cases here).
* Copular inversion ("Lisbona è la capitale del Portogallo" vs "La capitale del Portogallo è Lisbona") is blocked by the entity-order check.
* `near` can still accept related-but-different forms of a long word (carbonio/carbonico, pancreas/pancreatite) - semantics, not letters, would be needed.
* Role binding between two entities is only caught around a shared relation word; "A supera B" vs "B è superato da A" is not reasoned about.
* A literal extract can contain a second, true but non-answering sentence (e.g. sci-05 also quotes the largest planet): it is source text, never an altered claim, but "a mention is not an answer" is only partly enforced.
* abs-08 SUFFICIENCY remains false (SupportJudge untouched).
* Zero false proofs is therefore a statement about the cases run, not a guarantee.

### 7. Runner integrity
`RunIdentity.resumeMismatches` compares kind, commit/build, dataset fingerprint+version, fixtures version, offline mode, model file + SHA-256, runtime + version,
backend, slot, window, temperature, topK, topP and environment/device. `BenchRunner.run(resume=true)` throws `ResumeIdentityMismatch` on any difference and leaves the stored file
untouched (it used to start over silently, overwriting). A simulated control can never be completed as a MODEL run (kind differs). The Android device run accepts
`-e m4resume <runId>` (same id, same store, same atomic saves). Tests: `BenchHarnessTest.anInterruptedRunResumes...` (12 identity variants refused, same identity with another start time resumes).
