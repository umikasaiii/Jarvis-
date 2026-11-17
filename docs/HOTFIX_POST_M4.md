# Hotfix post-M4 — findings from the first Honor 200 tests

Baseline `ba4bb24`. Evidence levels used below: **CODE VERIFIED** (read in the production path), **JVM TESTED**, **ANDROID EMULATOR TESTED** (CI emulator, real Hilt graph, model/retrieval replaced by a seam — never a real model),
**REAL DEVICE VERIFIED** (none yet: nothing here is DEVICE VERIFIED).

## H1 — personal memory (Test F: "Orione" lost after reopening the app)
* **Cause (CODE VERIFIED).** `MemoryIndex.ensureBuilt()` returned immediately when no Obsidian vault was configured. Since "Memoria" moved to the local archive (no vault), the in-memory retrieval index was therefore built
  only by `remember()` (which calls `rebuild()`). After a process restart the index was empty, the Memory screen still listed the note (it reads the file directly), and `retrieveSmart` found nothing — so the question fell through to
  the encyclopedia (Wikibooks "progetti universitari") and a "no verifiable information" answer. It worked in the same session because the save had just rebuilt the index.
* **Fix.** `ensureBuilt()` no longer depends on a vault; `retrieveSmart()` builds the index on its first use in a fresh process (self-healing, independent of any screen). `StoredFactQuestion.skipEncyclopedia()` keeps a question about
  something the user told JARVIS on personal memory: the library is not queried when memory has a lexical hit or the question carries an explicit personal marker ("mio/mia", "avevo", "ti ho detto"), so "ricordi il teorema di
  Pitagora?" still reaches the library, and a missing fact is declared missing instead of being filled by an article that shares "progetto". Two more formulations ("come avevo chiamato…", "qual era il codice che ti avevo detto…") are recognised.
* **Not dependent on the native conversation (CODE VERIFIED).** Notes are retrieved from the index on every turn; model/slot change, rotation and reload only reset the injected-context bookkeeping, not the index.
* Tests: JVM `PersonalRecallRoutingTest` (4 wordings rank the note first among look-alikes; ordinary questions are not personal); emulator `HotfixMemoryInstrumentedTest` (real coordinator + real MemoryIndex: immediate recall, process-restart
  simulated with `MemoryIndex.clear()`, 3 other wordings, missing fact, edited note, deleted note, ordinary "progetto" question, irrelevant ZIM present). The test creates one synthetic record and deletes only that record.

## H2 — irrelevant sources (Tests B and D)
* **Cause (reproduced on JVM with synthetic ZIM + FTS4 + gatherer).** Three independent mechanisms: (1) the typo-tolerant title search matched "prodotta" to the title "Prodotto a tabacco…" and "variabile" to "Variable Length Subnet Masking"
  (one edit away) and the article was then listed as *consulted* / *related* although it shared only a frame word; (2) `SupportJudge` treated a title made of a frame word ("Organo (anatomia)") as "the article is about the subject" and accepted
  half of the remaining terms — the *frame* words "funzione", "svolge" — without the discriminating term "insulina"; (3) the index query relaxed by dropping up to two terms from the full set, which could leave **only** frame words and
  never reached the passages that contain "insulina".
* **Fix (general, no topic words).** A closed list of question-frame stems (`SupportJudge.GENERIC`: produce/prodotto, vero, viene, funzione, svolge, serve, esempio, pratico, organo…). An article is "about" the question only through a
  non-frame term; the terms that say what is asked decide sufficiency (frame words count only if the question has nothing else); the index also queries the central terms first; an unrelated fuzzy title guess is not listed as consulted unless it shares
  another central term of a multi-term question. Chunk-level scoring, thresholds, the typo tolerance itself and the "incomplete search is not absence" logic are unchanged.
* Tests: `HotfixRetrievalRelevanceTest` (6 independent cases incl. thyroxine, GDP-like titles, a frame-only question and the fuzzy network article).
* **Insulin outcome.** "Quale organo produce l'insulina e quale funzione svolge?" → grounded on the pancreas passages, no "Organo" article. "È vero che l'insulina viene prodotta dal fegato?" → no tobacco article; the liver article may appear only as a *related*
  (not supporting) candidate; the claim is not established. Note: JARVIS does not yet turn the pancreas passages into an explicit "no, è falso" — it declares the claim unsupported.
* **Limits.** The frame list is finite; a different generic word not in it can still drag in a look-alike article. Real WikiMed behaviour (article set, titles, redirects) is unverified until repeated on the phone.

## H3 — unverified general answers (Test E: "L'insulina è prodotta principalmente dal fegato")
* **Responsible component (CODE VERIFIED).** The pipeline does not rewrite model sentences: for an answer without evidence it only prepends a qualifier (`AnswerFraming.frame`) and strips presentation notes; `JVM` and `emulator` tests assert the model's sentence
  survives verbatim. The wrong content is the **model's raw output** (Gemma 1B, simulated in tests). Two pipeline defects made it worse: (a) the retrieval still ran although the user said not to use the archives, feeding the model
  unrelated/related passages (the liver article), and (b) for an answer with no number or capitalised name the qualifier was **omitted** (`statesPreciseFacts` false), so a medical sentence could be published with no label at all.
* **Fix.** `GeneralAnswerPolicy`: an explicit request for general knowledge skips retrieval and always prints "Come richiesto, questa risposta viene dalla conoscenza generale del modello: non è verificata nelle fonti locali."; for high-risk subjects (closed lexicon:
  health, drugs, law, money, safety) the limit and "controllala su una fonte affidabile" are always stated when no source stands behind the answer; no citations or footers are ever attached to model knowledge. The five answer classes (SOURCE_SUPPORTED,
  PERSONAL_MEMORY, DETERMINISTIC_TOOL, GENERAL_UNVERIFIED, ABSTENTION) are defined and tested (`GeneralAnswerPolicy.classify`).
* **Honest limit.** The label is not a safety guarantee: JARVIS does **not** verify or correct a model's general-knowledge sentence, and does not detect that it contradicts a related passage. A 1B model can still be wrong; the user is told so.
  No fact ("pancreas") is hard-coded anywhere outside test fixtures.

## H4 — model stability and diagnostics
* **Already present (HOTFIX 3/5, M3).** Content-free event trail (`InferenceDiagnostics`: CHAT_START/CHAT_END, TIMEOUT, WEDGED, PROCESS_START, PREVIOUS_EXIT with Android's exit reason — crash, native crash, ANR, low memory, signal, user), memory/battery snapshots, per-generation metadata,
  `LoadGuard` (load interruption detected; no automatic reload; nothing deleted), `ModelResidencyPolicy` (park other slots only when the estimate says so), `ModelLoadPolicy`. No automatic retry of an interrupted load.
* **Added.** `LoadGuard.beginGeneration/finishGeneration`: a durable marker for the duration of every generation. If the process dies while generating, the next start records a *generation* interruption (distinct from a load interruption), the model is **not** reloaded
  by itself, the Models screen says so without naming a cause, and a smaller model can be used. `RequestTrace` (counts/booleans/codes only — no text): intent, memory queried/found/admitted, knowledge queried, candidate passages, relevance, retrieval error, model available, publication mode,
  fallbacks, duration, abstention reason; bounded (100), persisted in its own file, shown in Diagnostics › "Diagnostica inferenza LLM", erasable with "Cancella traccia richieste". The app does **not** export it; the user may copy the text.
* **Gemma 4 E4B freeze: cause PENDING.** Nothing in this hotfix proves or disproves OOM, native crash, thermal or any other cause. Nothing here loads a heavy model in CI.
  **Data to collect on the Honor 200 (see the checklist below):** `adb logcat -b all` around the freeze (look for `lowmemorykiller`, `libc` fatal signals/tombstones, `ActivityManager: Killing`, `ANR in`), `adb shell dumpsys meminfo com.simone.jarvismobile.debug.beta` and `dumpsys activity exit-info`
  (Android's recorded exit reason), the app's Diagnostics event trail (last CHAT_START without CHAT_END, PREVIOUS_EXIT, MEMORY figures), thermal state (`adb shell dumpsys thermalservice`), free RAM before loading (Models screen estimate), whether another model was resident, and the E4B file size.

## Benchmarks (model-free pipeline control, same dataset, same commands)
| | M4 safety closure `ba4bb24` | this hotfix |
|---|---|---|
| set A faithful passed / false proofs | 107 / 0 | **108 / 0** |
| set A hallucinated false proofs | 0 | **0** |
| set B (regression) false VERIFIED / ALLOWED_PARTS / bad citations | 0 / 0 / 0 | 0 / 0 / 0 |
| set C (verification, not blind) false VERIFIED / ALLOWED_PARTS / bad citations | 0 / 0 / 0 | 0 / 0 / 0 |
The 23 earlier false proofs did not return. No gold was edited in this hotfix.

## Checklist to repeat on the Honor 200 (REAL DEVICE — none of this is verified yet)
1. Install the new Beta; with Gemma 1B: "Ricorda che il codice del mio progetto di prova è Orione" → ask → close the app (swipe away) → reopen → ask again, then "Come avevo chiamato il progetto di prova?" and "Ti ricordi il nome in codice del progetto?". Expect "Orione", source Memoria, no Wikibooks article.
2. "Che cos'è un progetto in informatica?" must still search the library; "Qual è il codice del mio progetto di prova?" with the note deleted must say it was not found.
3. "Quale organo produce l'insulina e quale funzione svolge?" and "È vero che l'insulina viene prodotta dal fegato?": no tobacco / "Organo (anatomia)" in the consulted sources.
4. "Spiegami cos'è una variabile in Python, con un esempio pratico": no "Variable Length Subnet Masking" / "Variable location address".
5. The Test E sentence: the answer must start with the "Come richiesto…" label and the health caution; record whether the model is still wrong (that is the model, not the pipeline).
6. Gemma 4 E4B: only after noting free RAM and thermal state; capture the logs above; if it freezes, reopen JARVIS and read Models › "Risposta interrotta" / Diagnostics; do not retry automatically; go back to Gemma 1B.
