# BETA 8 — HOTFIX 4: risposte fuori contesto (associazione risposta ↔ domanda)

Baseline: Beta `169cd4d` (Hotfix 3). Branch: `claude/beta08-hotfix4-answer-context`.
Stato: **CI VERIFIED solo a run completata; NON DEVICE VERIFIED** finché l'utente non riprova sull'Honor 200.

## Sintomo
Dopo «Che lingua si parla in Italia?» e «Cos'è un SSD?» (corrette), «Quanto è grande l'Asia?» e «Dimmi un piatto tipico italiano»
hanno ricevuto risposte con contenuto delle domande precedenti. L'utente aveva provato Stop e invii ravvicinati.

## Cosa è CONFERMATO (difetti reali nel codice, riproducibili con i test) e cosa NO
Non posso confermare che **uno di questi** abbia prodotto esattamente il sintomo sull'Honor 200 (nessun log dal dispositivo).
Sono però quattro difetti reali che permettono a una risposta di finire sotto la domanda sbagliata o di sopravvivere a Stop/Reset:

| # | Difetto (pre-fix) | Dove |
|---|---|---|
| C1 | **Nessun controllo al momento della scrittura.** `processText` scriveva la risposta in chat/cronologia/task senza verificare che il turno fosse ancora valido. Lo Stop si affidava solo a `throwIfStopped()` dentro la chiamata nativa: se il modello finiva nello stesso istante, o il percorso non passava dal nativo (strumenti, documenti), la risposta di una domanda **fermata** compariva comunque. Un «Nuova conversazione» durante la generazione faceva atterrare la risposta della vecchia chat nella chat nuova. | `SessionCoordinator.processText`, `newConversation` |
| C2 | **Retry fuori ordine.** Su errore il Worker fa `Result.retry()`; il retry (dopo ≥30 s) rielaborava la vecchia domanda quando le successive erano già state risposte e ne **accodava la risposta in fondo alla chat**: una risposta a una domanda precedente sotto la domanda corrente. | `AssistantTaskWorker` |
| C3 | **Lo Stop poteva colpire il messaggio successivo e riesumare quello fermato.** `cancelActive()` rileggeva in modo asincrono *tutti* i task attivi (anche uno inviato subito dopo il tap) e richiamava lo stop globale; un task fermato poteva essere riportato a `COMPLETED` (con notifica «risposta pronta») dall'`update` incondizionato del Worker. | `AssistantTaskQueue`, `HomeViewModel`, Worker |
| C4 | **Salvataggio cronologia non ordinato.** `persistChat` lanciava un `scope.launch` per ogni modifica su un dispatcher multi-thread: uno snapshot vecchio (o il salvataggio precedente al `clear()` di «Nuova conversazione») poteva finire per ultimo e resuscitare storia stantia, poi usata come recap nel prompt. | `SessionCoordinator.persistChat/newConversation` |

**Ipotesi ancora aperte (non provate):**
- il modello (Gemma 3 1B INT4) può ignorare una domanda breve quando il prompt di sistema contiene un recap lungo delle battute precedenti: non è stato cambiato il prompt (nessuna prova), e non attribuisco la causa al modello;
- stato nativo/KV-cache riutilizzato dopo uno stop: già coperto dall'Hotfix 3 (conversazione scartata dopo cancel/timeout/errore/output degenere);
- il contratto reale di LiteRT-LM (più `Conversation` per `Engine`, `cancelProcess` da altro thread) resta non verificabile qui;
- il percorso **vocale** (`processTurn`) non passa dal nuovo ledger: il suo commit è invariato.

Verifica anche eseguita: la risposta è sempre associata per `taskId` e `Transcript.existingReply` è per task (nessuna cache per testo); il motore
non usa stato condiviso tra turni oltre alla `Conversation` persistente voluta (memoria conversazionale).

## Correzione (una sola autorità, modifiche mirate)
- **`core/chat/TurnLedger`** (nuovo, puro): registra ogni turno (sequenza, `taskId`, ordine di arrivo, epoca conversazione). `check()` rifiuta un turno
  se: fermato (`STOPPED`), la conversazione è stata azzerata dopo il suo inizio (`CONVERSATION_RESET`), oppure una domanda più nuova è già stata
  risposta (`SUPERSEDED`).
- **`core/chat/ChatTurnRunner`** (nuovo): l'**unico** codice che scrive le righe di un turno scritto. Ordine: registra → corsia (la stessa `sessionMutex`
  del coordinatore, nessun secondo lock) → `check` → riga utente → genera → **`check` di nuovo** → riga risposta → commit. Un turno rifiutato non scrive nulla.
- `SessionCoordinator`: `processText` delega al runner; `cancelTextGeneration()` avvisa **prima** il ledger e poi il nativo; `newConversation()` invalida
  i turni in volo **prima** di toccare stato/engine e annulla il nativo; nuovo `cancelTurn(taskId)`; `persistChat`/`clear` passano da `SerialWriter` (ordine garantito).
- Coda/Worker: Stop e «Nuova conversazione» annullano solo i task creati **fino al tap** (`activeIdsUpTo(cutoff)`), per-task `cancelTurn` invece dello stop globale;
  `Discarded` ⇒ nessun retry, nessun TTS, nessuna notifica; `completeUnlessCancelled` impedisce di riportare a COMPLETED un task fermato; un **retry**
  di una domanda più vecchia di una già risposta non viene risposto fuori ordine (riga esplicita in chat, `superseded_retry`).
- Nessun dato cancellato o migrato (solo query/UPDATE nuove, schema Room invariato). Nessuna risposta preimpostata che mascheri il problema.

## Diagnostica (nessun contenuto personale)
Stessa traccia di `InferenceDiagnostics` (Diagnostica › «Diagnostica inferenza LLM»). Eventi `TURN_BEGIN/COMMIT/DISCARD/STOP/NEW_CONVERSATION` con:
sequenza del turno (`n1`), hash a 24 bit del `taskId` (`n2`, mai il testo), epoca conversazionale (`epoch`), epoca del motore (`tag=eng<N>`) e, per gli scarti, il
motivo (`STOPPED/CONVERSATION_RESET/SUPERSEDED`). Una risposta obsoleta scartata è quindi distinguibile da una committata.

## Test — chi prova cosa
| Tipo | Cosa | Dove |
|---|---|---|
| **JVM, motore finto** (reply = `R(<testo inviato>)`, runner+ledger+`InferenceSlot` reali) | 1) dieci domande consecutive; 2) due domande ravvicinate; 3) Stop in generazione + nuova domanda (anche Stop nell'istante in cui il modello finisce); 4) Nuova conversazione durante generazione (anche nell'istante di fine); 5) riavvio con richieste in coda (nessun duplicato, ordine) e retry tardivo scartato; 6) timeout e recupero; 7) mix casuale con seed (5 serie × 40 operazioni): nessuna risposta sotto la domanda sbagliata né con taskId duplicati; turno in coda fermato mai arrivato al motore | `core/.../ChatTurnRunnerTest`, `TurnLedgerTest`, `SerialWriterTest` |
| **JVM, guard statici** | il commit passa solo dal runner; ordine invalidazione→stato; scritture ordinate; Worker senza retry per `Discarded`; cutoff dello Stop | `app/.../TurnCommitGuardTest` |
| **Emulatore, Room reale** | cutoff di Stop, `completeUnlessCancelled`, retry superato, riga attiva dopo riapertura DB | `AssistantQueueRecoveryInstrumentedTest` |
| **Modello reale / Honor 200** | **nessuno**: questi test non provano l'inferenza reale | — |

## Tre prove pratiche sull'Honor 200
1. Chiedi in sequenza: «Che lingua si parla in Italia?», «Cos'è un SSD?», «Quanto è grande l'Asia?», «Dimmi un piatto tipico italiano», «Quanto fa 12 per 12?» — ognuna deve rispondere **solo** alla propria domanda.
2. Invia una domanda lunga, tocca **Stop** subito e invia **subito** un'altra: deve comparire solo la risposta della seconda; la prima resta senza risposta e senza notifica «risposta pronta».
3. Durante una risposta tocca «Nuova conversazione», poi fai una domanda nuova: nella chat non deve comparire nulla della conversazione precedente. Poi apri Diagnostica › «Diagnostica inferenza LLM»: gli eventi `TURN_DISCARD` (motivo) e `TURN_COMMIT` mostrano cosa è stato scartato.
