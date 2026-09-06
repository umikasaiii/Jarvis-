# Device test checklist — FASE 2A.11 EmbeddingGemma Semantic Classifier

Nessuna infrastruttura Robolectric/instrumented esiste in questo repository
(vedi `CLAUDE.md`), quindi il comportamento REALE del classificatore
(`EmbeddingGemmaEngine`/`EmbeddingSemanticClassifier`) non è certificabile da
questo ambiente — solo lo strato puro (`EmbeddingMath`/`CentroidClassifier`/
`PrototypeSemanticClassifierEngine`/`SemanticClassifierInterpreterAdapter`) lo
è, ed è verde (`cd core && ./gradlew test`, 1052/1052 al momento di
scrivere, usando un embedder sintetico `FakeSemanticEmbedder` — MAI il vero
EmbeddingGemma, che questo ambiente non può scaricare o eseguire). Questo
file è la checklist minima da eseguire sull'HONOR 200 reale, con il vero
modello `embeddinggemma-300M_seq256_mixed-precision.tflite` +
`sentencepiece.model` importati in Impostazioni › Modelli › "Comprensione
semantica", prima di dichiarare **DEVICE ACCEPTANCE ✅** per FASE 2A.11.

**IMPORTANTE — nessuna delle frasi sotto è una copia esatta di
`core/src/main/resources/semantic/prototypes.json`**: sono parafrasi/
formulazioni nuove pensate apposta per misurare la generalizzazione reale del
classificatore, non la sua capacità di riconoscere un prototipo già visto
verbatim.

Per ogni turno, apri Diagnostica › pannello "Motore conversazionale (debug)"
subito dopo la risposta e annota: `semBackend` (deve leggere `EMBEDDING`, mai
`GEMMA_LEGACY`, a meno che l'A/B debug non sia stato attivato apposta),
`tokenizzazione`/`embedding`/`classificazione`/`totale`/`coldStart` (ms),
oltre ai campi già esistenti dalle fasi precedenti (`intent`, `domini`,
`operazione`, `esito`, `path`, `retry`, `legacyUsato`).

Per ciascuna riga della tabella, registra: **expectedIntent**,
**expectedDomains**, **actualIntent**, **actualDomains**, **confidence**
(`intentScore`), **OOD** (sì/no), **semanticTotalMs**, **route**
(`ANSWER`/`HANDOFF_LLM`/`LEGACY_FALLBACK`), **PASS/FAIL**.

## Categoria A — Known intent, unseen wording (10 frasi)

Stessi intent/domini del corpus, formulazione mai vista letteralmente.

| # | Frase | expectedIntent | expectedDomains |
|---|-------|-----------------|------------------|
| A1 | "Mi conviene portare l'ombrello uscendo domani mattina?" | CAPABILITY_QUERY | WEATHER |
| A2 | "Con che vestiti mi conviene uscire oggi, farà freddo?" | CAPABILITY_QUERY | WEATHER |
| A3 | "Ho qualcosa segnato per dopodomani pomeriggio?" | CAPABILITY_QUERY | AGENDA |
| A4 | "Riesci a dirmi se stanotte ho riposato abbastanza?" | CAPABILITY_QUERY | HEALTH |
| A5 | "Il mio battito di stamattina era nella norma?" | CAPABILITY_QUERY | HEALTH |
| A6 | "Metti su la prossima canzone, per favore" | DIRECT_COMMAND | MEDIA |
| A7 | "Fammi vedere quanto spazio libero resta sul telefono" | CAPABILITY_QUERY | DEVICE_INFO |
| A8 | "Da dove viene il nome dell'oceano Pacifico?" | KNOWLEDGE_QUERY | KNOWLEDGE |
| A9 | "Segna sul calendario che devo chiamare il dentista venerdì" | DIRECT_COMMAND | AGENDA |
| A10 | "Non ho capito una parola di quello che hai detto" | CLARIFICATION | — |

## Categoria B — Cross-domain contamination (8 frasi, in sequenza)

Ogni coppia: un turno stabilisce il topic, il turno successivo deve
cambiare dominio SENZA ereditare quello precedente.

| # | Frase | expectedIntent | expectedDomains | Note |
|---|-------|-----------------|------------------|------|
| B1 | "Come ho dormito nell'ultima settimana?" | CAPABILITY_QUERY | HEALTH | stabilisce HEALTH |
| B2 | "E il tempo previsto per venerdì?" | CAPABILITY_QUERY | WEATHER | deve passare a WEATHER, mai restare su HEALTH |
| B3 | "Fa più caldo oggi rispetto a ieri?" | CAPABILITY_QUERY | WEATHER | stabilisce WEATHER |
| B4 | "Ho impegni segnati per sabato prossimo?" | CAPABILITY_QUERY | AGENDA | deve passare ad AGENDA |
| B5 | "Quanti appuntamenti ho questo mese?" | CAPABILITY_QUERY | AGENDA | stabilisce AGENDA |
| B6 | "Quanta memoria interna ha ancora libera il telefono?" | CAPABILITY_QUERY | DEVICE_INFO | deve passare a DEVICE_INFO |
| B7 | "Cos'è di preciso la latenza di rete?" | KNOWLEDGE_QUERY | KNOWLEDGE | stabilisce KNOWLEDGE |
| B8 | "Quanta ne ho effettivamente disponibile io sul dispositivo?" | CAPABILITY_QUERY | DEVICE_INFO | anafora: "ne" = RAM del device, mai VRAM/conoscenza generica |

## Categoria C — Follow-up (6 frasi, in tre sequenze)

| # | Frase | expectedIntent | expectedDomains | Note |
|---|-------|-----------------|------------------|------|
| C1 | "Cosa ho in programma per domani?" | CAPABILITY_QUERY | AGENDA | stabilisce AGENDA |
| C2 | "E il giorno dopo ancora?" | CAPABILITY_QUERY | AGENDA | referenceMode=ELLIPSIS, eredita AGENDA |
| C3 | "Quanta RAM monta di preciso questo telefono?" | CAPABILITY_QUERY | DEVICE_INFO | stabilisce DEVICE_INFO |
| C4 | "E lo spazio di archiviazione, quello è libero?" | CAPABILITY_QUERY | DEVICE_INFO | eredita/conferma DEVICE_INFO |
| C5 | "Che tempo farà sabato?" | CAPABILITY_QUERY | WEATHER | stabilisce WEATHER |
| C6 | "E la domenica successiva com'è messa?" | CAPABILITY_QUERY | WEATHER | eredita WEATHER |

## Categoria D — Multi-source reasoning (5 frasi)

| # | Frase | expectedIntent | expectedDomains |
|---|-------|-----------------|------------------|
| D1 | "Tenendo conto di quanto ho dormito e di cosa ho da fare domani, a che ora dovrei coricarmi stasera?" | MULTI_SOURCE_REASONING | HEALTH, AGENDA |
| D2 | "Se domani piove e ho anche la riunione delle 9, mi conviene uscire prima?" | MULTI_SOURCE_REASONING | WEATHER, AGENDA |
| D3 | "Il mio sonno di stanotte più il freddo previsto per oggi, mi consigli di allenarmi lo stesso?" | MULTI_SOURCE_REASONING | HEALTH, WEATHER |
| D4 | "Considerando gli impegni di questa settimana e come sto dormendo, quando mi conviene riposare di più?" | MULTI_SOURCE_REASONING | AGENDA, HEALTH |
| D5 | "Vista la previsione di domani e quello che ho segnato in agenda, devo portare la giacca a vento alla riunione?" | MULTI_SOURCE_REASONING | WEATHER, AGENDA |

## Categoria E — Hard negatives (6 frasi)

| # | Frase | expectedIntent | expectedDomains | Note |
|---|-------|-----------------|------------------|------|
| E1 | "In termini generali, a cosa serve la RAM in un computer?" | KNOWLEDGE_QUERY | KNOWLEDGE | mai DEVICE_INFO |
| E2 | "Il mio cellulare quanta memoria RAM ha effettivamente a bordo?" | CAPABILITY_QUERY | DEVICE_INFO | mai KNOWLEDGE_QUERY generico |
| E3 | "Domani sarà una bella giornata di sole o no?" | CAPABILITY_QUERY | WEATHER | mai HEALTH anche se "domani" segue turni HEALTH precedenti |
| E4 | "Il battito cardiaco medio di un adulto qual è di solito?" | KNOWLEDGE_QUERY | KNOWLEDGE | mai HEALTH (non chiede il MIO dato) |
| E5 | "Ho qualcosa di importante da fare la settimana che viene?" | CAPABILITY_QUERY | AGENDA | mai KNOWLEDGE_QUERY generico su "settimana" |
| E6 | "Cosa vuol dire di preciso quando un'app va in background?" | KNOWLEDGE_QUERY | KNOWLEDGE | mai SYSTEM_APP/DEVICE_INFO |

## Categoria F — OOD / open-world (6 frasi)

Domande genuinamente nuove, mai viste nel corpus né simili a un prototipo —
devono risultare `OOD=true`/`HANDOFF_LLM`, mai forzate in una capability.

| # | Frase | expected |
|---|-------|----------|
| F1 | "Secondo te conviene di più investire in obbligazioni o in azioni quest'anno?" | OOD → HANDOFF_LLM |
| F2 | "Qual è la differenza tra un motore diesel e uno a benzina?" | OOD → HANDOFF_LLM (o KNOWLEDGE_QUERY se il classificatore la riconosce come conoscenza generale — mai una capability grounded inventata) |
| F3 | "Puoi consigliarmi un libro di fantascienza da leggere questa estate?" | OOD → HANDOFF_LLM |
| F4 | "Zorgle blaminto della fasca whurendi non esiste per davvero" | OOD → HANDOFF_LLM (gibberish puro, mai una capability forzata) |
| F5 | "Mi spieghi come funziona la fotosintesi clorofilliana in breve?" | OOD → HANDOFF_LLM (o KNOWLEDGE_QUERY) |
| F6 | "Secondo te è meglio allenarsi la mattina presto o la sera tardi?" | OOD → HANDOFF_LLM |

## Categoria G — Device-info vs knowledge, ambiguità/edge case (3 frasi)

| # | Frase | expectedIntent | expectedDomains |
|---|-------|-----------------|------------------|
| G1 | "Attivalo per favore" (fuori contesto, nessun topic precedente) | CLARIFICATION o OOD | — (mai una capability indovinata a caso) |
| G2 | "Ciao JARVIS, come butta oggi?" | CONVERSATION | — |
| G3 | "Puoi ripetere l'ultima cosa che hai detto, non ho sentito bene?" | CLARIFICATION | — |

## Categoria H — Hard commands (whitelist, non deve mai passare dal classificatore)

| # | Frase | expected route |
|---|-------|-----------------|
| H1 | "Accendi la torcia" | FastPath (hard command), nessuna chiamata al classificatore semantico |
| H2 | "Metti in pausa la riproduzione" | FastPath (hard command, `media_control`) |

## Esito e criteri di chiusura

- **AUTOMATED GATE**: `cd core && ./gradlew test` verde (1052/1052 al momento
  della stesura) — già soddisfatto.
- **DEVICE PERFORMANCE GATE**: `semanticTotalMs` misurato per almeno 10 turni
  della tabella sopra, riportando mediana e P95 nel report finale.
- **SEMANTIC ACCURACY GATE**: almeno l'80% delle 46 righe sopra deve avere
  `actualIntent`/`actualDomains` coerenti con `expectedIntent`/
  `expectedDomains` (categoria F esclusa dal conteggio di dominio esatto,
  conta solo `OOD=true`/`HANDOFF_LLM` come PASS).

Non dichiarare **DEVICE ACCEPTANCE ✅** finché questa tabella non è stata
eseguita per intero sul vero HONOR 200 con il vero modello EmbeddingGemma
caricato — un'esecuzione con `semBackend=GEMMA_LEGACY` (A/B debug) non
conta come verifica di questa fase.

## Onestà — limiti noti, non risolvibili da questo fix

- Le soglie di produzione (`ClassifierThresholds.CONSERVATIVE_DEFAULT`) sono
  provvisorie/conservative, MAI calibrate contro un vero embedding
  EmbeddingGemma in questo ambiente (nessun accesso di rete a
  `litert-community/embeddinggemma-300m`) — un `thresholds.json` calibrato
  con `tools/semantic_classifier/calibrate.py` contro il vero modello dovrebbe
  sostituirle prima di un rilascio definitivo.
- Il classificatore centroide/prototipo (`PrototypeSemanticClassifierEngine`)
  è la sola implementazione runtime oggi — nessuna testa di classificazione
  addestrata (`LearnedHeadClassifier`) esiste ancora, per l'assenza di un vero
  embedder in questo ambiente (vedi `tools/semantic_classifier/README.md`).
- `semanticLatencyMs`/`modelColdStartMs` sono misurabili solo su device con
  il vero modello caricato — nessun numero di questo tipo è stato "misurato"
  in questo ambiente, solo dichiarato come infrastruttura pronta.
