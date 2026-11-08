# Checklist Honor 200 — PASSAGGIO 2 (baseline dati e aggiornamento)

**Nessuna riga è stata eseguita.** Compila "Esito" solo dopo averla provata sul telefono. Tutte le prove sono
non distruttive per i tuoi dati reali; dove serve un test distruttivo usa una **installazione isolata** (secondo profilo
utente Android, o un secondo telefono, o una build con applicationId diverso). Non cancellare mai i dati reali.

> **BETA 1/8 — aggiornamenti a questa checklist** (vedi `docs/BETA_STEP01_DEVICE_READINESS.md`)
> * L'applicationId della build di debug è `com.simone.jarvismobile.debug` (APK isolato: `com.simone.jarvismobile.debug.beta`): usa questo nei comandi `adb`/`run-as`.
> * Il backup in-app dell'app **già installata** non copre `memoria.md`, `agenda.md`, `automazioni.md`, `chat.json`, note background: prima di aggiornare esegui la **copia grezza** `tools/beta/raw_backup.*` e `verify_raw_backup.py` (punto 0 del documento BETA).
> * Stato di ogni prova: **PENDING** finché non la esegui tu. Nessuna è stata eseguita.

## 0. Prima di iniziare
1. Fai un **backup locale cifrato** dall'app (Impostazioni › Backup › «Esegui backup ora») e copia anche la cartella SAF se l'hai configurata. Annota la **Recovery Key**.
2. Conserva l'APK **attualmente installato** (per poterlo reinstallare): `adb shell pm path com.simone.jarvismobile` poi `adb pull <path> vecchia.apk`.
3. Annota: build id attuale (Diagnostica › Build), data/ora, versione Android/MagicOS, livello batteria.
4. Scarica la build da testare: `https://github.com/umikasaiii/Jarvis-/releases/download/latest-debug/app-debug.apk` (solo dopo che la CI è verde sul commit con le modifiche del PASSAGGIO 2). Annota lo SHA-256 e verifica che sia quello del commit.
5. Raccogli log: `adb logcat -c` prima di ogni prova; salva con `adb logcat -d -v time > logcat_provaN.txt`. Filtri utili: `JarvisDbGuard`, `AndroidRuntime`, `JarvisBackup`, `Room`.

Conta e annota PRIMA dell'aggiornamento (schermate o foto): n. note (Memoria + Archivio), n. attività e scadenze, n. automazioni, n. luoghi, n. documenti importati, modello AI caricato, ultime 3 voci di chat.

---

## 1. Avvio a freddo
* **Prep**: app chiusa (Impostazioni Android › App › JARVIS › Termina).
* **Passi**: apri l'app dall'icona.
* **Atteso**: dashboard in <10 s, nessun crash, nessun messaggio di errore database.
* **Verifica**: Diagnostica › «Avvio precedente»: nessun errore. `logcat` senza `FATAL EXCEPTION` né `IllegalStateException` da Room.
* **Log**: logcat dall'avvio; screenshot Diagnostica.
* **Se fallisce**: **P0** — non reinstallare né cancellare i dati; conserva logcat, `adb shell run-as com.simone.jarvismobile ls -l databases/` e `databases/premigration/`.

## 2. Aggiornamento in-place da versione precedente
* **Prep**: backup (punto 0), APK vecchio installato e usato (con dati reali).
* **Passi**: installa il nuovo APK **sopra** (`adb install -r app-debug.apk` o tap sul file; **non** disinstallare). Apri l'app.
* **Atteso**: installazione "aggiornamento", app si apre, dati presenti.
* **Verifica**: stesso conteggio del punto 0 per note/attività/automazioni/luoghi/documenti. Per Room: `adb shell run-as com.simone.jarvismobile ls -l databases/premigration/` — se l'aggiornamento ha cambiato versione schema dovrebbe esserci `jarvis-v<N>-<ts>.db` (con la 15→15 **non** viene creato: è normale).
* **Log**: logcat (`JarvisDbGuard`), output `ls`, conteggi prima/dopo.
* **Se fallisce**: **P0-2 resta aperto**. Se mancano dati: NON usare l'app; recupera il file in `databases/premigration/` e il backup, segnala.

## 3. Note personali
* **Passi**: apri Memoria e Archivio › Appunti; apri 3 note (una con checklist, una con colore/tema, una in cartella).
* **Atteso**: stesso titolo/testo/cartella/pin/tema di prima.
* **Verifica**: confronto con lo screenshot del punto 0; modifica una nota, riavvia l'app, la modifica persiste.
* **Se fallisce**: dati persi/corrotti → P0-1 non risolto; conserva `databases/` e logcat.

## 4. Attività, scadenze, notifiche
* **Passi**: Agenda: controlla liste, ⭐, sotto-attività, completate. Crea un promemoria a +3 minuti con l'app chiusa subito dopo.
* **Atteso**: stesse attività; il promemoria suona/notifica puntuale ad app chiusa.
* **Verifica**: orario notifica vs orario previsto; riavvia il telefono prima di un secondo promemoria a +10 min e controlla che arrivi.
* **Log**: orari, logcat (`ExactAlarm`, `Reminder`).
* **Se fallisce**: regressione sui promemoria (era verificata su device, fase 6e).

## 5. Automazioni
* **Passi**: Automazioni › elenco e «Regole avanzate»: controlla regole e luoghi salvati; attiva una regola a orario vicino (innocua: notifica).
* **Atteso**: regole/luoghi intatti, la regola scatta una volta sola.
* **Verifica**: conteggio come al punto 0; una sola notifica; Diagnostica per l'ultimo trigger.
* **Se fallisce**: duplicati o regole mancanti → conserva log + `databases/`.

## 6. Modello AI locale
* **Passi**: Impostazioni › Modelli: il modello importato è ancora indicato; carica il motore rapido.
* **Atteso**: «Pronto» senza reimportare.
* **Verifica**: invia «ciao»; risposta generata.
* **Se fallisce**: percorso modello perso (non dovrebbe: è in DataStore/file, non in Room) — segnala.

## 7. Conversazione senza Internet e senza Core PC
* **Prep**: modalità aereo ON (Wi-Fi e dati OFF); Core PC disattivato (default).
* **Passi**: 5 turni di chat (ricordare un dettaglio, chiedere l'ora, creare una nota, una domanda di conoscenza).
* **Atteso**: risposte locali, nessun errore di rete.
* **Verifica**: l'ultimo turno ricorda il primo; nota creata presente in Archivio.
* **Log**: logcat; Diagnostica.
* **Se fallisce**: dipendenza da rete nascosta → segnalala con la frase usata.

## 8. Arresto e riavvio immediato dell'assistente
* **Passi**: tocca l'orb (ascolto) → tocca di nuovo subito per fermare → tocca di nuovo subito per riavviare; ripeti 5 volte; poi dì «ok» da solo durante un ascolto.
* **Atteso**: si ferma subito e riparte subito, mai bloccato su «Pronto».
* **Se fallisce**: regressione del fix `sessionJob` (fase 4).

## 9. Schermo spento
* **Passi**: con wake word spenta (default) avvia l'ascolto, blocca lo schermo, aspetta 1 minuto; sblocca. Poi crea un promemoria a +2 min e blocca lo schermo.
* **Atteso**: nessun microfono nascosto (indicatore microfono Android assente a schermo spento); il promemoria notifica comunque.
* **Se fallisce**: microfono attivo a schermo spento = **violazione privacy**, segnala subito.

## 10. Briefing mattutino e notifiche
* **Prep**: briefing mattutino attivo; annota orario sveglia/«ora configurata».
* **Passi**: per **3 mattine consecutive** non toccare l'app prima dell'ora; al primo sblocco guarda le notifiche. Controlla Diagnostica › evidenza trigger.
* **Atteso**: **una sola** notifica per mattina, dopo il primo sblocco/sveglia (il fallback orario è accettabile).
* **Verifica**: per ogni giorno: n. notifiche (0/1/2+), orario, quale segnale (FIRST_UNLOCK / NEXT_ALARM / CONFIGURED_TIME).
* **Log**: schermate Diagnostica ogni mattina.
* **Se fallisce**: duplicati o assenza → P1-2 confermato, allega le evidenze.

## 11. Backup locale, ripristino, riavvio
* **ATTENZIONE**: il ripristino sovrascrive i dati: eseguilo **solo** su installazione isolata o dopo aver copiato il backup e annotato tutto.
* **Passi (installazione isolata)**: installa l'APK in un secondo profilo/telefono, importa/copia il backup e inserisci la Recovery Key; ripristina; riavvia l'app.
* **Atteso**: restore a staging, al riavvio i dati tornano; nessun crash; coda risposte non riprende azioni pendenti (sanificata).
* **Verifica**: conteggi come al punto 0; `databases/premigration/` creato se la versione del backup è < 15.
* **Se fallisce**: backup non ripristinabile = **P0**; non cancellare l'originale.

## 12. Nessun crash o comportamento distruttivo durante l'aggiornamento
* **Passi**: ripeti il punto 2 una seconda volta (reinstalla sopra la stessa build, poi sopra una build successiva quando disponibile) e controlla logcat per l'intera sequenza.
* **Atteso**: nessun `FATAL EXCEPTION`, nessun `UnsupportedDatabaseVersionException`, dati invariati.
* **Se vedi `UnsupportedDatabaseVersionException`**: stai installando una build **più vecchia** su un database più nuovo. Il file non è stato toccato: reinstalla la build corretta (non disinstallare).

---

### Esito complessivo (da compilare)
| Prova | PASS / FAIL / N.D. | Data | Build id | Note / file log |
|---|---|---|---|---|
| 1 Avvio a freddo | | | | |
| 2 Aggiornamento in-place | | | | |
| 3 Note | | | | |
| 4 Attività/notifiche | | | | |
| 5 Automazioni | | | | |
| 6 Modello AI | | | | |
| 7 Chat offline | | | | |
| 8 Stop/riavvio | | | | |
| 9 Schermo spento | | | | |
| 10 Briefing (3 mattine) | | | | |
| 11 Backup/ripristino | | | | |
| 12 Nessun crash update | | | | |

P0-2 si chiude solo con PASS documentati almeno su 1, 2, 3, 4, 5, 11 e 12.
