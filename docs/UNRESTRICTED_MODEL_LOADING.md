# Caricamento libero dei modelli AI locali (2026-11-16)

Stato: **CI VERIFIED** a run completata; **non DEVICE VERIFIED**. Nessun modello reale è stato caricato in questo ambiente (né su emulatore): i test JVM/emulatore provano logica, sequenza e protezioni, **non** che Gemma 4 E4B (3.490 MB) o Qwen 3.5 4B funzionino sull'Honor 200.

## 1. Analisi: cosa bloccava davvero il caricamento

Confronto con `550ca02` (ultimo stato in cui il motore chiamava direttamente `Engine.initialize()`):

| Elemento | `550ca02` | Stato prima di questo intervento |
|---|---|---|
| Runtime | LiteRT-LM `0.14.0` (`litertlm-android`), Kotlin 2.2.21, AGP 8.7.3 | identico |
| `EngineConfig` | `Backend.CPU()`, `maxNumTokens = 4096`, `cacheDir = cacheDir` | identico |
| Slot | fast + advanced + classifier (3 `LitertLmEngine`) | identico |
| Pre-controllo RAM | **assente** | `ModelLoadAdvisor.assess` introdotto in `072698a` (BETA 2/8, 2026-11-08): se `availMem < file × 1,15 / 2` lanciava `InsufficientMemoryException` **prima** di `initialize()` |

Con i numeri segnalati: file 3.490 MB ⇒ stima 4.013 MiB, soglia di blocco 2.006 MiB, disponibile 1.410 MiB ⇒ blocco. La stessa chiamata `Engine.initialize()` del tempo non veniva più tentata. **Conclusione verificata nel codice:** il blocco è una decisione del software, non un rifiuto del runtime; la differenza di versione/formato/configurazione tra `550ca02` e oggi sul percorso di caricamento è nulla. **Non è dimostrato** che il caricamento riesca: la RAM «disponibile» di Android è un dato di sistema (cache recuperabile inclusa), non la memoria allocabile dal processo, e solo il tentativo reale lo dice.

Altre cause di rischio trovate: (a) l'avvio ricaricava in sequenza **tutti** i modelli selezionati, senza distinguere un modello che aveva già fatto chiudere il processo ⇒ possibile **ciclo di chiusure**; (b) caricamenti avviabili da punti diversi senza un'unica coda; (c) nessuna diagnostica reale (stima vs misura).

## 2. Cosa è cambiato

- **`ModelLoadAdvisor`** resta, ma è **solo consultivo** (messaggio riscritto: «stima prudenziale, non un limite»). Il motore non lancia più nulla in base alla RAM: chiama sempre `Engine.initialize()` e riporta l'errore **reale** del runtime.
- **`ModelLoadPolicy`** (core, testata): `Proceed(avviso)` o `NeedsConfirmation`; **non esiste l'esito «rifiuta»**, per nessuna dimensione di file né livello di RAM (test su 140 combinazioni). Predefinito prudente: con stima insufficiente chiede **«Carica comunque»** (a ogni tentativo). Preferenza persistente **«Consenti caricamento sperimentale di modelli pesanti»** (Modelli): tenta senza riproporre la conferma.
- **`ModelLoader`** (unico punto di caricamento, manuale e all'avvio): una sola coda (`Mutex`), il modello precedente dello slot è rilasciato e **atteso** (`unloadAndAwait`) prima del successivo; solo se la stima è insufficiente libera **altri slot residenti** uno alla volta (prima il classificatore, poi l'avanzato; mai il rapido da solo), attendendo la chiusura nativa e **rimisurando** la memoria dopo ciascuno. Gli slot «in pausa» restano **selezionati** nelle impostazioni (non eliminati). Nessun `System.gc()`, nessun root/API private.
- **Marcatore durevole** (`LoadGuard` + `ModelLoadGuard`, SharedPreferences `commit()`): scritto **prima** di `initialize()`, cancellato al ritorno (successo o errore catturato). Se Android uccide il processo (OOM nativo non intercettabile) il marcatore resta: al riavvio l'app segnala «caricamento interrotto», **non ricarica da sola** quel modello (nessun ciclo), e il blocco automatico cade solo con un successo dello stesso file o con «Ho capito». Il modello, le chat, le note, i documenti, il DB, gli indici ZIM/FTS e Guardian non vengono toccati: il journal contiene poche righe di testo.
- **Avvio**: carica il rapido; avanzato/classificatore solo se la stima non è insufficiente (o in modalità sperimentale); mai un modello interrotto. Il motivo è mostrato nella schermata Modelli e nel messaggio «modello non disponibile».
- **Diagnostica** (Modelli › «Diagnostica memoria»): RAM fisica, disponibile secondo Android, `lowMemory`, PSS del processo, e per ogni caricamento: modello, slot, backend (CPU), dimensione file, stima (euristica), giudizio, memoria dopo, **crescita misurata (PSS) e differenza dalla stima**, durata, esito, errore del runtime, slot messi in pausa; con la nota che «disponibile» non è «allocabile» e la stima non è un limite hardware.
- **Invariati:** controlli di esistenza/validità del file e di formato (li fa il runtime), `InferenceSlot` e la serializzazione di Hotfix 3/4/5, `ModelManager` (nessuna cancellazione), LiteRT-LM come runtime.

## 3. Test eseguiti (nessuno è una prova su Honor 200)

- JVM `:core` — `UnrestrictedLoadingTest`: il caso segnalato (3.490 MB / 1.410 MiB) ⇒ stima insufficiente, avviso, tentativo sempre possibile; nessuna combinazione file×RAM×preferenza rifiutata; predefinito prudente; avvio automatico più stretto; journal (nessuna traccia dopo un caricamento concluso; segnalazione al riavvio dopo uccisione; nessun ciclo; riarmo solo con successo/«Ho capito»; journal corrotto non lancia); politica di residenza (classificatore prima, avanzato poi, rapido mai, nulla se la stima non è insufficiente); contenuto del report.
- JVM `:app` (guardie sul sorgente, in CI): nessuna eccezione per RAM nel motore, marcatore scritto **prima** di `initialize()` e chiuso nei due esiti, ogni caricamento passa dal `ModelLoader`, nessuna cancellazione nei percorsi di caricamento, nessun gc forzato/root/API private.
- **Non eseguiti (richiedono il modello reale su Honor 200):** Gemma 3 1B INT4, tentativo Gemma 4 E4B e Qwen 3.5 4B, cambio FAST→ADVANCED e ADVANCED→FAST con modelli grandi, stop durante inferenza, caricamento dopo conversazione lunga, avvio dopo uccisione reale del processo. Le garanzie su Hotfix 3/4/5 (slot, stop, conversazione) derivano dall'**assenza di modifiche** a `InferenceSlot`, `ChatTurnRunner`, `ValidatedGeneration` e dalla loro suite JVM ancora verde.

## 4. Limiti residui

- Un OOM nativo può terminare l'intero processo senza che l'app possa reagire: il marcatore permette solo di **segnalarlo al riavvio** e di non ripetere il tentativo da soli. Non si può impedirlo né intercettarlo.
- Con la modalità sperimentale l'app non protegge più dal tentativo: un modello troppo pesante può rendere il telefono lento prima che Android intervenga.
- La stima (`file × 1,15`) e le soglie sono euristiche non tarate sull'Honor 200; la crescita misurata è il PSS del processo, non la memoria totale impegnata dal runtime (le pagine mappate dal file possono essere contate in modo diverso).
- Due modelli grandi nei due slot restano possibili se l'utente li carica (con conferma o modalità sperimentale): la liberazione automatica avviene solo per classificatore/avanzato quando si carica un modello più pesante.

## 5. Procedura di verifica sull'Honor 200

1. Modelli › attiva **«Consenti caricamento sperimentale di modelli pesanti»** (o lascia spento e usa «Carica comunque»).
2. Importa `gemma-4-E4B-it.litertlm` (3.490 MB) e premi **Rapido**. Annota: tempo, esito, riga «Diagnostica memoria» (stima vs crescita misurata), eventuale errore del runtime.
3. Se riesce: chatta e premi **Stop** durante una risposta; poi carica Gemma 3 1B INT4 e verifica il cambio di modello senza due modelli grandi contemporanei.
4. Se l'app si chiude durante il caricamento: riapri, verifica l'avviso «Caricamento interrotto», che il modello **non** venga ricaricato da solo e che chat/note/documenti siano intatti; poi carica un modello più piccolo.
5. Con modalità spenta: ripeti 2 e verifica la richiesta «Carica comunque / Annulla» (Annulla non cambia nulla).
