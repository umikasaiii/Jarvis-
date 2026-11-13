# BETA 8 — HOTFIX 5: Quality Engine (qualità strutturale dell'AI locale)

Baseline: Beta `03086e9` (Hotfix 4). Branch: `claude/beta08-hotfix5-quality-engine`.
Stato: **CI VERIFIED solo a run completata; NON DEVICE VERIFIED.** Nessuna misura di qualità del modello reale è stata eseguita
(nessun modello né Honor 200 in questo ambiente): vedi «Cosa è e cosa NON è misurato».

## 0. Vincolo: nessuna correzione per domande specifiche
Nessuna risposta, whitelist, regex di risposta o conoscenza è stata codificata. Le domande osservate (lingua, SSD, Asia, piatto, 12×12) sono
solo evidenze; nel benchmark sono 4 voci su 133 (solo split `dev`, le loro parafrasi sono `holdout`).

## 1. Audit del percorso reale (UI → commit)
```
HomeViewModel.onSendText → AssistantTaskQueue.enqueueResponse (Room) → WorkManager AssistantTaskWorker (FGS dataSync)
 → SessionCoordinator.processQueuedText → processText → ChatTurnRunner [lane = sessionMutex, TurnLedger]   (Hotfix 4)
 → generateAnswer:  offlineHubAnswer / documentAnswer (solo se NON Modalità Pro)
 → JarvisEngineRouter.route( setting jarvisEngineMode )
      CLASSICO (DEFAULT) → classicAnswer → [Pro] → pending → CommandMatcher (strumenti deterministici, es. CalculateTool)
          → agenda intents → ToolIntentGate → LlmIntentClassifier (generate su classifierEngine) → chatReply
          chatReply: memoria (retrieveSmart) + KnowledgeRepository.find (Evidence.Grounded) + documentEvidence
                     → router.chat → LitertLmEngine → InferenceSlot (Hotfix 3) → LiteRT-LM (CPU)
      CONVERSAZIONALE (opzionale, spento di default; solo con modello caricato) → ConversationalJarvisEngine → JarvisBrain
          (prompt propri: core.engine.SystemPromptComposer FAST/RICH, ContextAssembler, ConversationManager)
 → pulizia (AssistantReplyCleaner) → commit (ChatTurnRunner.check + sink.append)                          (Hotfix 4)
```
**Usato davvero dalla BETA:** Motore Classico (default), `chatReply`, `LlmRouter`→`LitertLmEngine`→`InferenceSlot`, `KnowledgeRepository`/documenti,
`CommandMatcher`/`CalculateTool`, `ChatTurnRunner`/`TurnLedger`. **Solo predisposto / non usato per i test osservati:** Motore Conversazionale
(opt-in), modalità IBRIDA (ricade su Classico), interrogazione diretta di archivi Kiwix ZIM (non esiste un lettore ZIM: `docs/LOCAL_KNOWLEDGE.md`),
slot ADVANCED (usato solo se l'utente ha importato un secondo modello). Un `SystemPromptComposer` esiste solo per il Conversazionale; il
Classico componeva il prompt dentro `SessionCoordinator.buildSystem`. **Il problema osservato proviene dal Classico: il Conversazionale non è stato toccato** (guard statico).

### Problemi confermati nel codice (Classico, prima di questa correzione)
1. **Prompt di sistema grande e contraddittorio** per un modello da 1B: 3,3 KB di istruzioni (~1.100 token stimati) con «breve» e «dai la risposta e basta» + persona «vai dritto al punto» (spinge a risposte tagliate), un lungo blocco di regole negative («non inventare mai nulla… Non lo so») che per un modello piccolo porta a evasività o a imprecisione sui numeri, e «Sei JARVIS … Ti chiami X» (presentazioni).
2. **Cronologia incollata come testo nel blocco di sistema** («Simone: … / Tu: …», fino a 16 messaggi × 320 caratteri) — formato che invita alla continuazione del dialogo (il pulitore esiste proprio per tagliare i «Tu:» simulati) e usato **in aggiunta** alla memoria breve; nessun budget.
3. **Nessun controllo della finestra da 4096 token**: la conversazione nativa cresce a ogni turno e l'app non sapeva quanto restava per la risposta; il comportamento all'overflow non è documentato dall'SDK.
4. **Nessuna validazione**: una risposta vuota/troncata/copia di un turno precedente veniva mostrata così com'era; i numeri generati dal modello erano presentati come fatti.
5. **Diagnostica di generazione assente** (solo eventi del motore, nessuna traccia per risposta).
6. **Campionamento non impostato**: si usavano i default del modello (non documentati nell'SDK).

### Ipotesi NON dimostrate
- Che la qualità percepita migliori col nuovo prompt/campionamento: **nessun A/B sul modello reale** è stato possibile qui.
- Che Gemma 3 1B INT4 sbagli i numeri anche con il prompt migliore: molto probabile (vedi §7), ma non misurato.

## 2. Q1 — Diagnostica della generazione
`GenerationTrace` (`:core`, solo metadati: nessun campo di testo per costruzione): turno (sequenza del ledger Hotfix 4), motore, percorso
(`chat_general` / `chat_grounded` / `chat_tool_grounded`), slot, epoca, dimensioni in caratteri di sistema/storia/evidenza/domanda, **token stimati
(dichiarati stime)**, finestra e riserva, turni di seed e scartati, rotazione, latenza di generazione, caratteri grezzi/puliti, flag di validazione,
retry, **terminazione** (COMPLETED/CANCELLED/TIMEOUT/ERROR/NO_OUTPUT), provenienza e se è stata aggiunta una nota. Token generati e motivo di arresto
dell'SDK sono scritti `UNAVAILABLE` (il runtime non li espone). Persistenza: `files/diag/gen_trace.log` (limitato) + Logcat + Diagnostica ›
«Diagnostica inferenza LLM». Commit/scarto del turno: eventi `TURN_*` dell'Hotfix 4 (stessa sequenza) → si distingue modello / routing / prompt / pulizia / commit.
**Ispezione testi** (prompt, risposta grezza e pulita): solo da Diagnostica › «Attiva», **in memoria, locale, scade dopo 10 minuti, mai su disco né in rete**.

## 3. Q2 — Prompt e contesto (misurato)
- Asset `prompts/jarvis_system_it.md`: **3.276 → 1.279 caratteri**; istruzioni positive, una sola regola di completezza («proporzionato, finisci le frasi»),
  nessun «sempre breve», niente saluti/presentazioni se non richiesti, regola sulle fonti, «circa»/incertezza per numeri e definizioni, strumenti per calcoli.
  Mantenuti: identità, italiano, persona configurabile, capacità reali, privacy («non inventare dati personali… sei offline»), priorità alla richiesta attuale.
- `ClassicPromptComposer` (`:core`): sistema = base + persona + nome + data + appunti (cap a budget) + memoria breve (cap). **Nessun recap nel sistema.**
- La cronologia va al motore come **turni reali user/model** (`initialMessages`, API documentata LiteRT-LM v0.14.0), normalizzati (alternanza, finale model),
  con budget: l'ultima domanda non è mai troncata, si tengono i turni più recenti interi, una risposta lunga è accorciata a fine frase. Una conversazione nativa viva ignora il seed (la sua memoria è già nella KV cache: niente duplicazione).
- `ConversationTokenLedger`: stima l'uso della conversazione viva e la ruota (nuova conversazione + seed compatto) prima che la riserva di risposta (1.024 token) venga erosa.
- Le note di presentazione («Nota: …») non vengono mai rimandate al modello come sue parole.

**Misura reale (test puro `PromptBudgetBenchmarkTest`, 133 richieste × storie da 0/3/8/15 scambi, token STIMATI a 3 caratteri/token):**
| storia | prima media/max | dopo media/max |
|---|---|---|
| 0 | 1280 / 1328 | 590 / 637 |
| 3 | 1650 / 1717 | 985 / 1055 |
| 8 | 2204 / 2323 | 1251 / 1330 |
| 15 | 2204 / 2317 | 1251 / 1335 |
Blocco di sistema base: ~1.227 → ~539 token stimati. In una chat lunga simulata (60 turni, risposte da 480 caratteri) il vecchio comportamento
erode la riserva di risposta al turno 10 senza alcuna protezione; il nuovo ruota la conversazione 4 volte. (Ricostruzione fedele del vecchio costruttore; dettaglio: `docs/quality/prompt_budget_measured.md`.) Questo misura **dimensione/spazio**, non qualità.

## 4. Q3 — Configurazione LiteRT-LM (verificata sulla documentazione ufficiale al tag v0.14.0)
Disponibile: `EngineConfig(modelPath, backend, maxNumTokens, cacheDir, …)`; `ConversationConfig(systemInstruction, initialMessages, samplerConfig, tools, …)`;
`SamplerConfig(topK, topP, temperature)`; `sendMessage` sincrono / `sendMessageAsync`; `Conversation.cancelProcess()`.
**Non esposti** (quindi non inventati): token generati, motivo di arresto, limite massimo di token di output separato, conteggio token del prompt.
Applicato: `initialMessages` per la cronologia; **`SamplerConfig(topK=40, topP=0.9, temperature=0.7)`** (`SamplingProfiles.CONSERVATIVE`) sulla conversazione di chat
(i default del modello non sono documentati; temperatura/nucleo più stretti riducono gli scivoloni casuali di token, tipicamente sui numeri, senza avvicinarsi al greedy che nei modelli piccoli genera loop).
**Non misurato su dispositivo:** è l'unica variabile da confrontare sull'Honor 200 (Prova 4). RAM/CPU invariati (stessa finestra 4096, stesso backend CPU). Helper e classificatore (`generate`/`chatStateless`) mantengono il campionamento del modello.

## 5. Q4 — Accuratezza e grounding offline
Mappa per tipo di informazione (cosa esiste, cosa è stato aggiunto):
| Tipo | Fonte di verità | Stato |
|---|---|---|
| Dati personali | appunti/memoria reali (`retrieveSmart`), `StoredFactQuestion` (ammette di non sapere) | già presente, invariato |
| Dispositivo/operazioni | strumenti reali (`CommandMatcher` → `ToolRunner`) | già presente; provenienza `TOOL` ora esplicita |
| Calcoli | `CalculateTool` deterministico quando la richiesta è riconosciuta | già presente (la classificazione resta quella di `CommandMatcher`) |
| Documenti | `documentAnswer`/`documentEvidence` con citazioni reali | già presente |
| Conoscenza generale | memoria del modello | **nuovo:** provenienza `MODEL_GENERAL` + nota onesta se la risposta contiene numeri |
| Fonte offline pertinente | `KnowledgeRepository.find` → `Evidence.Grounded` (preferita alla memoria del modello, già nel prompt) | **nuovo:** i numeri della risposta che non compaiono nelle fonti vengono segnalati |
Nessun router per parole chiave: la decisione si basa sull'**evidenza recuperata** (c'è/non c'è) e sulla **forma dell'output** (numeri con ≥3 cifre, decimali, percentuali, unità/magnitudo), non sul tipo di domanda.
Nessun secondo database né orchestratore; dati personali e conoscenza generale restano separati. **Dipendenza dichiarata, non operativa:** una verifica fattuale generale richiede contenuti offline
(es. un pacchetto di conoscenza indicizzato nel `KnowledgeRepository`; gli archivi Kiwix ZIM **non** sono interrogabili direttamente: manca un lettore ZIM). Nulla è stato scaricato né incluso nell'APK.
Un secondo passaggio dello stesso modello **non** è usato come verifica indipendente.

## 6. Q5 — Validazione (una sola fase, riusabile)
`AnswerQuality` + `ValidatedGeneration` (`:core`): vuoto; incompleto (frase interrotta: parole funzione finali, separatori, parentesi/virgolette aperte); degenere (riusa `DegenerateOutput`); **copia di una risposta già data a una domanda diversa** (similarità Jaccard ≥ 0,8, esente se la domanda è la stessa); numeri non supportati dalle fonti.
Politica: **un solo retry**, su conversazione fresca, solo per vuoto/incompleto/eco (il degenere lo ritenta già lo slot: nessun doppio retry), passa da `LlmRouter`→`InferenceSlot` quindi resta annullabile; se è un'eco il retry riparte senza cronologia. Se nulla è utilizzabile: messaggio onesto, mai una risposta fabbricata.
Una risposta solo «incompleta» ma unica disponibile viene mostrata (tagliarla ulteriormente peggiora). Corrispondenza `taskId`/conversazione/risposta: invariata (Hotfix 4).

## 7. Q7 — Limiti del modello
Gemma 3 1B INT4 è adeguato a conversazione breve, definizioni semplici e riformulazione di risultati forniti dal sistema; **non** a richiamare numeri/misure precise a memoria: la sua conoscenza fattuale a 1B parametri quantizzati
è intrinsecamente inaffidabile, e nessun prompt la rende affidabile. Per questo la correzione non promette accuratezza: usa strumenti e fonti dove esistono, dichiara «non verificato offline» sui numeri generati e non mascherata con risposte preimpostate.
Raccomandazione (non applicata automaticamente, nessun download): importare nello slot **ADVANCED** già esistente un modello più capace (la logica `needsReasoning`/`selectSlot` lo usa già per le domande articolate). Nessun confronto con un modello alternativo è stato eseguito: nessun artefatto disponibile qui.

## 8. Q6 — Benchmark: cosa esiste e cosa è stato eseguito
Dataset `core/src/test/resources/quality/benchmark_v1.json`: **133 richieste**, 12 categorie (≥10 ciascuna), 45 `holdout` (parafrasi incluse, 6 gruppi), 4 voci «osservate» (solo `dev`), riferimenti automatici (`ref`) solo dove il fatto è oggettivo.
| Tipo di test | Eseguito | Cosa prova |
|---|---|---|
| 1. Puri (codice) | **Sì** (`:core`, CI) | budget prompt prima/dopo; validatore su banco di output **sintetico** (45 buone, 15 incomplete, 6 eco, 6 degeneri, 3 vuote): 0 falsi positivi sulle buone, richiamo 0,967 (29/30; l'eco parafrasata non è rilevata); integrità del dataset |
| 2. Motore finto | **Sì** (`:core`, CI) | orchestrazione: retry unico, eco scartata, note di provenienza, associazione risposta↔domanda (Hotfix 4) |
| 3. Strumentati Android (emulatore) | **Sì** a CI completata (`QualityAssetInstrumentedTest`, routing/regex/Room) | asset di prompt nell'APK e budget su runtime Android; **senza modello** |
| 4. Reali LiteRT-LM su Honor 200 | **No** | richiede dispositivo + modello: procedura in §9 |
Confronto prima/dopo con valori misurati: **solo** dimensioni del prompt (§3). Nessun punteggio di correttezza/allucinazione del modello è stato generato: non è stato eseguito alcun test con il modello reale.

## 9. Prove sull'Honor 200 (ripetibili)
Prima: Diagnostica › «Attiva» ispezione testi (opzionale). Tra una prova e l'altra: «Nuova conversazione». Dopo: «Mostra eventi recenti» per leggere `GEN …`.
1. **Domande generali nuove** (non usate nello sviluppo): «Qual è la capitale del Portogallo?», «Che lingua si parla in Brasile?», «Dimmi un dolce tipico spagnolo».
   PASS: ognuna risponde alla propria domanda con frasi complete, senza saluti/presentazioni, nessun contenuto di domande precedenti.
2. **Numeriche senza fonte**: «Quanto è lungo il fiume Nilo?», «In che anno è stata fondata Roma?». PASS: risposta data (non un rifiuto) **e** riga «Nota: i numeri vengono dalla memoria del modello, non da una fonte verificata offline.»; se il valore è sbagliato è un limite del modello, non un fallimento della prova (la nota deve esserci).
   **Con fonte:** importa un documento con un numero noto e chiedi quel dato. PASS: risposta basata sul documento con citazione; se il numero non è nelle fonti compare «Nota: alcuni valori non compaiono nelle fonti consultate.».
3. **Definizioni tecniche**: «Cos'è una CPU?», «Cos'è la crittografia?». PASS: definizione completa e coerente (frase finale compiuta); imprecisioni terminologiche vanno annotate, non sono un errore di sistema.
4. **Consecutive e memoria**: chiedi 6 domande diverse di fila, poi «Qual era la prima domanda che ti ho fatto?». PASS: ogni risposta pertinente; la memoria cita la prima domanda. (Opzionale A/B del campionamento: ripeti con la Beta `beta-03086e9`.)
5. **Memoria conversazionale**: «Ricorda che il mio colore preferito è il verde», altra domanda, poi «Qual è il mio colore preferito?». PASS: «verde» da conversazione/appunti, senza inventare.
6. **Stop e Nuova conversazione**: domanda lunga → Stop → altra domanda; poi durante una risposta «Nuova conversazione» → domanda nuova. PASS: nessuna risposta della domanda fermata, nulla della chat precedente; in Diagnostica `TURN_DISCARD`, `rotated`/`retried` coerenti.
7. **Risposte incomplete**: 10 domande che richiedono 3–5 frasi (es. «Spiegami in poche frasi come funziona il Wi-Fi»). PASS: nessuna risposta termina a metà frase; se `retried=true` nella traccia, la risposta mostrata è completa; se compare «Il modello non è riuscito a rispondere…» annota quante volte.
Annota: `genMs`, `flags`, `retried`, `end=` per ogni prova.
