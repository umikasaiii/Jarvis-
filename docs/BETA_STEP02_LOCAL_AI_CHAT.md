# BETA 2/8 — AI locale, chat conversazionale e memoria offline

Stato: **CI VERIFIED**. **NON** DEVICE VERIFIED: nessun test con modello reale su Honor 200 è stato eseguito.

## Commit
- Iniziale: `616860e` (branch `claude/step02-db-hardening-validation`)
- Branch BETA 2: `claude/beta02-local-ai-chat`
- Codice: `072698a` (motore, coda, memoria), `31bc557` (fix test guardia); questo documento è in un commit successivo, solo docs.
- `latest-debug` NON toccato: resta su `550ca02` (verificato). Il job di publish è saltato su questo branch.

## CI (run #511, id 42436420777, commit 31bc557) — tutto verde
Core test + build debug + unit test Android + lint + compilazione instrumented + schema Room; Windows PS5.1; APK isolato; report APK; publish **skipped**.
Run #510 (072698a) era fallito solo per un bug nel test di guardia (ricerca root repo), corretto in 31bc557. Test migrazione DB su emulatore (run 42435365803, 072698a) verde; file DB invariati dopo.
Core: 1729 test, 0 fallimenti (39 nuovi).

## APK isolato (applicationId `com.simone.jarvismobile.debug.beta`)
- Artifact `app-debug-isolated`: https://github.com/umikasaiii/Jarvis-/actions/runs/42436420777/artifacts/13599838229
- SHA-256 dello zip artifact: `39b69c1ad12737b321e487cf681f835333c7a2ebcd50d3e5f323e38ab9a5dd60`
- Contiene l'APK di commit 31bc557 (identico per codice a quello di questo commit docs, ma una nuova run CI produrrà un APK con SHA diverso: testare quello linkato).
- Coesiste con l'app esistente (package diverso, dati separati: i dati dell'app principale NON sono visibili).

## Problemi trovati
1. Load/unload/delete/import del modello non serializzati con una generazione in corso (race).
2. Import con stesso nome sovrascriveva il file in uso; copia non atomica.
3. Risposta "Ho capito…" (eco) quando il modello manca: sembrava una risposta reale.
4. Fallimenti del worker silenziosi (nessun messaggio in chat).
5. Possibili invii duplicati e risposte fuori ordine.
6. Messaggio inviato durante il caricamento del modello in competizione col load.
7. Domande su fatti personali senza ancoraggio alle note recuperate.

## Correzioni
- `LitertLmEngine`: loadMutex + chatMutex + token operazione, attesa di svolgimento con timeout 15 s, check RAM (`ModelLoadAdvisor`), stop nativo via `GenerationStopController`.
- `ModelManager`: import atomico (`.importing` + rename), validazione (`ModelImportPolicy`), spazio libero, rifiuto di sovrascrivere modelli in uso; delete scarica prima il modello.
- `SessionCoordinator`: messaggi onesti per modello assente/non caricabile (`ModelAvailability`), attesa del caricamento in corso, dedupe via `Transcript`, nota di grounding per domande su fatti memorizzati (`StoredFactQuestion`).
- Coda: guardia anti-duplicato (`EnqueueGuard`), ordine FIFO delle risposte, fallimento finale riportato in chat, scritture chat idempotenti per taskId.
- Nessuna API di rete nel percorso chat; Core PC resta off di default.

## Cosa è disponibile
Caricare `.litertlm` importato, chat con risposte reali del modello, contesto multi-turno (Conversation persistente + reseed da transcript), recupero memoria personale con grounding, stop/annulla generazione, errori chiari, persistenza chat/memoria dopo chiusura app.

## Mappa test automatici richiesti (JVM; motori finti solo nei test, non provano l'inferenza reale)
Modello mancante / incompatibile / errore di load: `ModelAvailabilityTest`, `ModelImportPolicyTest`, `ModelLoadAdvisorTest`. Messaggi consecutivi / ordine / duplicati: `TranscriptTest`, `EnqueueGuardTest`. Annullamento: `GenerationStopControllerTest`. Memoria: `StoredFactQuestionTest`. Nessun fallback cloud, errore in generazione, instradamento semplice, recupero dopo riavvio: `OfflineChatPipelineGuardTest` (scansione sorgenti, 11 test) + logica del worker/idempotenza.
**Limite onesto:** recupero dopo riavvio del processo, errore in generazione e annullamento nativo non hanno un test strumentato con modello reale; sono coperti da test di logica pura e scansione sorgenti.

## Limiti residui
- EmbeddingGemma non simulato né completato; la memoria usa il ranker lessicale/esistente.
- `MemoryRouter` (core) non cablato.
- Nessuno streaming a token reale.
- Il check RAM è euristico.
- Nessun test su dispositivo.

## Procedura di verifica su Honor 200 (manuale)
Installare l'APK isolato (non sovrascrive l'app principale). Annotare i valori reali, non stimarli.
1. Importare un `.litertlm` dalla schermata Modelli e caricarlo; cronometrare il tempo di caricamento.
2. Attivare modalità aereo / disattivare Internet.
3. Chat: almeno 10 scambi consecutivi; cronometrare il tempo alla prima risposta e totale.
4. Chiedere qualcosa che richiami un'informazione di un turno precedente.
5. Creare una nota/memoria di test (con la conferma prevista) e porre una domanda che la richiami.
6. Premere Stop durante una risposta; verificare che la UI non si blocchi; poi inviare un nuovo messaggio.
7. Con modello non caricato / file assente verificare il messaggio d'errore chiaro (nessuna risposta finta).
8. Chiudere completamente e riaprire l'app: verificare che chat e memoria siano presenti.
9. Controllare RAM (Impostazioni › Batteria/Memoria) e assenza di crash/ANR; salvare eventuale logcat.

## Da fare nei passaggi successivi
Test strumentati con motore finto sul worker; cablaggio memoria semantica; streaming; validazione su dispositivo.
