# BETA 6/8 — Biblioteca Offline (Kiwix) e Organic Maps

Stato: **CI VERIFIED** (commit codice `6a9420a`). **NON DEVICE VERIFIED**: nessuna prova su Honor 200, nessuna prova con Kiwix o Organic Maps reali installati.
Legenda: *Implementato* = nel codice · *CI* = coperto da test automatici passati · *Device* = confermato su Honor 200 (nessuna funzione lo è ancora).

## 1. Baseline e commit
- Partenza: `claude/beta05-offline-translator` (`b1c6016`). Branch: `claude/beta06-kiwix-organicmaps`.
- Commit di correzione dopo i primi run CI falliti: `967c333` (smart-cast), `ddc32c8` (etichetta destinazione nullable), `6a9420a` (due commenti che facevano scattare i test-guardia). Questo report: commit successivo, solo docs.
- Room resta alla versione 15 (nessuna migrazione). Nessun merge, `latest-debug` non toccato (resta `550ca02`, job publish saltato).

## 2. Cosa è stato fatto
**`:core` (puro, testato):** rilevamento varianti app (`ExternalApps`), costruzione link Organic Maps (`OrganicMapsLinks`), regole sugli archivi ZIM (`KiwixZim`), riconoscimento comandi (`OfflineHubCommands`).
**`app/`:** `ExternalAppLauncher` (verifica con `queryIntentActivities`/`getLaunchIntentForPackage`), `ZimLibrary` (riferimenti SAF in sola lettura), `OfflineHubService` (unico punto d'ingresso), schermate «Biblioteca Offline» e card Organic Maps (Mappe offline), scelta del provider di navigazione, `<queries>` nel manifest, integrazione in `SessionCoordinator` (comandi riconosciuti prima dei motori) e negli strumenti di Modalità Guida (`DrivingTools`).

## 3. Kiwix — Biblioteca Offline
- Rileva `org.kiwix.kiwixmobile` (Play) e `org.kiwix.kiwixmobile.standalone`; se ci sono entrambe chiede quale usare (preferenza salvata), non sceglie in silenzio.
- Se Kiwix non è installato lo dice. **Non installa nulla, non apre il Play Store.**
- Cartella/file ZIM collegati con Storage Access Framework, permesso persistente **solo lettura**; **nessuna copia** nello storage privato. Si legge solo il numero magico (4 byte) per riconoscere un ZIM; mai il contenuto. Stati: raggiungibile, permesso revocato, illeggibile, non è uno ZIM.
- Apertura di un archivio: `ACTION_VIEW` sull'URI `content://` indirizzato al pacchetto scelto. Se non gestito, apre Kiwix con istruzioni (Libreria → seleziona il file).
- Ricerca: apre Kiwix con la ricerca/testo; l'effetto reale dipende da Kiwix (comportamento di `PROCESS_TEXT` non verificato).
- **L'AI NON interroga i contenuti degli ZIM.** Non esiste un lettore (libzim) in JARVIS. `aiQueryable` è sempre falso; a una domanda su un articolo presente solo in uno ZIM JARVIS risponde onestamente che non può leggerlo e propone di aprirlo in Kiwix. Gli ZIM non alimentano il RAG di BETA 4 (guardia nei test). Lettura di estratti condivisi a mano: **non implementata**. Lettore ZIM integrato: roadmap.

## 4. Organic Maps
Schemi derivati dal sorgente ufficiale (`libs/map/mwm_url.cpp`, `Factory.java`; omaps.app e api.github.com non raggiungibili dall'ambiente):
- `om://search?query=…` (+ fallback `geo:0,0?q=…`)
- `om://map?ll=<lat>,<lon>&n=<nome>` (`ll` per primo) e `geo:<lat>,<lon>`
- `om://route?sll=…&saddr=…&dll=…&daddr=…&type=vehicle|pedestrian|bicycle|transit` — servono tutti e cinque i parametri, in quest'ordine; **prepara solo l'anteprima del percorso**, la guida la avvia l'utente.
- `om://v2/dir` e `om://v2/nav` non esistono nel parser → **non usati**.
- Pacchetti: `app.organicmaps`, `app.organicmaps.web`.
- Modalità: auto, a piedi, bici (+ trasporto pubblico nel modello).
- Coordinate: solo luogo salvato dall'utente o coordinate scritte/dette dall'utente (validate: lat ±90, lon ±180). **Mai inventate.** Destinazione ambigua → domanda, non coordinate. Percorso senza partenza nota → mostra la destinazione e lo spiega. **Nessun geocoder online, nessun fallback cloud, nessun Core PC.**
- Provider di navigazione: JARVIS interno / Organic Maps / Google Maps, scelta esplicita in Impostazioni; predefinito invariato (Google Maps). Mai cambiato dai lanciatori. Navigatore interno e mappe offline restano.
- Comandi: «Apri Organic Maps», «Cerca una farmacia su Organic Maps», «Mostra 41.9028, 12.4964 su Organic Maps», «Portami a X con Organic Maps», «Apri il navigatore offline». La chat ordinaria non viene catturata (test).
- Ritorno a JARVIS: lo gestisce Android (tasto indietro/recenti); JARVIS non ha un meccanismo dedicato.

## 5. Test e CI
- **Core JVM:** `OfflineHubTest` (34 test, nuovi) + suite core esistente: job CI verde.
- **Android unit JVM:** `OfflineHubGuardTest` (9 scansioni dei sorgenti: nessuna API di rete/geocoder, nessuna installazione né storage ampio, nessuna copia/lettura integrale degli ZIM, solo intent documentati, risposta onesta sugli ZIM senza RAG, ordine di instradamento, provider rispettato con Google ancora opzione, navigatore interno presente, Room v15). Job «Android unit tests» verde.
- **Emulatore (workflow «DB migration tests»):** `OfflineHubIntentInstrumentedTest` (4 test) con componenti Android reali: nessuna delle due app presente → il lanciatore lo dice; link Organic Maps ben formati e instradabili verso un'Activity di prova; intent Kiwix instradabili solo al pacchetto scelto; intent senza gestore → «non gestito», senza crash. Run 42593481401: verde.
- **Limite esplicito:** le Activity di prova sono finte. I test strumentati **non provano** che Kiwix o Organic Maps reali accettino i link, né che il percorso si prepari.
- CI #521 (run 42593481708): core + build debug, test unitari, schema Room, compilazione test strumentati, lint, APK isolato, report APK, PS 5.1: **tutti verdi**; publish **saltato**.
- Mappa dei 22 temi richiesti: tutti coperti da test core/guard/strumentati tranne quelli che richiedono app reali (SAF revocato su file reale, comportamento in aereo di Kiwix/OM, routing offline reale di OM), che sono **solo manuali**.

## 6. APK isolato
- Artifact `app-debug-isolated` (applicationId `com.simone.jarvismobile.debug.beta`), digest dello zip: `sha256:7225239363f9668532e6d665370409862b5b8968a2cbf92d4bb202e8b80f2f00`. Run: https://github.com/umikasaiii/Jarvis-/actions/runs/42593481708
- Lo SHA-256 dell'APK interno non è stato estratto qui: calcolarlo con `sha256sum` dopo il download.

## 7. Limiti noti
- Nessun accesso AI ai contenuti ZIM; nessun estratto condiviso.
- Il comportamento delle app esterne (Kiwix `PROCESS_TEXT`, Organic Maps con `geo:`) non è verificato su dispositivo.
- Non si può sapere se Organic Maps ha le mappe della zona scaricate: se mancano, è Organic Maps a chiederle (JARVIS non scarica nulla).
- Con partenza non nota il percorso non può essere preparato; viene mostrata la destinazione.

## 8. Checklist manuale Honor 200 (modalità aereo)
Prerequisiti: Kiwix e Organic Maps installati da te, almeno uno ZIM e le mappe della tua zona già scaricati; installare l'APK isolato.
1. Impostazioni › Biblioteca Offline: rileva Kiwix (e la variante); con entrambe chiede quale usare.
2. Collega la cartella ZIM: i file compaiono come raggiungibili, nessuna copia (controlla lo spazio usato dall'app).
3. Apri un ZIM da JARVIS: si apre in Kiwix, altrimenti compaiono le istruzioni.
4. Revoca il permesso della cartella dalle impostazioni di Android: JARVIS mostra «permesso revocato».
5. **Chiedi a JARVIS di un articolo presente solo nello ZIM: deve ammettere di non avervi accesso diretto e proporre di aprire Kiwix.** Se risponde con contenuto dello ZIM, è un difetto.
6. «Apri Organic Maps», «Cerca una farmacia su Organic Maps»: si apre la ricerca in OM.
7. «Mostra 41.9028, 12.4964 su Organic Maps»: punto corretto a Roma. Con coordinate non valide: rifiuto.
8. «Portami a [luogo salvato] con Organic Maps»: anteprima del percorso in OM (auto/piedi/bici); la guida parte da OM.
9. Cambia provider in Impostazioni e ripeti da Modalità Guida: usa il provider scelto; Google Maps resta selezionabile.
10. Disinstalla/disabilita una delle due app: JARVIS lo dice senza installare nulla.
Nessuno di questi passaggi è stato eseguito: riportarne l'esito prima di marcare qualcosa come DEVICE VERIFIED.
